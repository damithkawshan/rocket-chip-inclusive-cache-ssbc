# Coder task 005 — honest A-channel hit accounting + interval sampling of the SBC counters

**Date:** 2026-09-09 · **Author:** thinker session
**Branch:** `sbc-sampling`, cut from tag `sbc-baseline-not-verified-2026-09-09` (= `d8671cf` on `sbc-paper-aligned`)
**Depends on:** 004 (closed). Builds directly on the counters 004 added.

## Why

Task 004 built `L2_Accesses` / `L2_Hits` and Amendment 1 correctly removed SBC's internal probes
from them. That fix stands and is in the RTL today. **Three further defects remain**, all found by
reading the RTL on 2026-09-09, and all of them bias the headline hit rate:

1. **Channel pollution.** The counters increment on `io.result.valid && !internalRead`. The
   Directory sees every lookup but **not which channel it came from**, so C-channel Releases and
   X-channel flushes are counted as memory accesses. They are not. Worse, a C Release is a
   *guaranteed* hit — `MSHR.scala:1584` asserts `new_meta.hit` on that branch, because inclusion
   means the L1 can only release a line the L2 holds. Every Release adds +1 to **both** numerator
   and denominator, dragging the measured hit rate toward 100%. This is systematic bias, not noise.

2. **Repeat hits are invisible.** When an MSHR reloads and the tag already matches
   (`allocate.bits.repeat`), it is a hit that **never reads the directory**
   (`MSHR.scala:1296` uses `final_meta_writeback` instead). No counter sees it. We undercount hits.

3. **A permission upgrade is counted as a hit.** `MSHR.scala:1631-1632`: a line held in BRANCH that
   needs T sets `s_acquire := false.B` and goes out with param **BtoT**. The directory says "hit",
   but a full outer round trip is paid. This is the ordinary load-then-store pattern — a load
   fetches NtoB so the L2's own state is BRANCH, then a store must upgrade — so it is expected to
   be **common**, not a corner case.

Separately, we have no way to see **how the saturation counters evolve over time**. We can read one
set's live value over `SBC_SetSel`/`SBC_SetSat`, but there is no history. Phase behaviour is
invisible.

## Ground rule

**`L2_Accesses` (0x3A8) and `L2_Hits` (0x3B0) must not change** — not their location, not their
increment condition, not their value. Everything below is **purely additive**. The `sbc-baseline-not-verified-2026-09-09`
tag exists so that any number measured before this task stays reproducible after it.

---

## Part 1 — A-channel counter set

Nine new free-running 64-bit counters, **inner-A only**, always active (not gated on
`enableSetBalancing` — like 004's, these are baseline cache statistics).

| Counter | Event |
|---|---|
| `L2_ReqA` | an inner-A demand request is accepted from L1 (`request.fire && prio(0) && !control`) |
| `L2_AcqA` | an outer A channel beat fires (first beat only) |
| `L2_AcqUpgrade` | subset of `L2_AcqA` where `param === BtoT` — the permission upgrades |
| `L2_LookupA` / `L2_LookupHitA` | fresh-alloc directory lookup for an A request, and its hits |
| `L2_ReloadLookupA` / `L2_ReloadHitA` | reload-path lookup (tag mismatch) and its hits |
| `L2_Repeat` / `L2_RepeatHit` | reload with tag match (no lookup), and the subset that are hits |

These give two hit-rate definitions from one run:

- **H1, no outer transaction** = `(L2_ReqA - L2_AcqA) / L2_ReqA`
- **H2, directory hit, exactly as the TL_signal_analysis branch measured it** = `L2_LookupHitA / L2_LookupA`
- **H2 full** = `(LookupHitA + ReloadHitA + RepeatHit) / (LookupA + ReloadLookupA + Repeat)`

### `L2_RepeatHit` — do NOT assume a repeat is a hit

This is the trap. `final_meta_writeback.hit` is **not always true**: `MSHR.scala:660` sets
`hit := false` for a control/flush request, so a repeat following a flush is a genuine **miss**.
Gate on the actual hit bit. The `TL_signal_analysis` branch's `SaturationCounter` counted every
repeat as a hit unconditionally — do not copy that.

### How to get the channel bit — read this before writing code

The Directory cannot distinguish channels today. **Do not build a parallel pipeline of
`RegEnable`s alongside the directory read to carry the channel down.** That is what the
`TL_signal_analysis` branch did and it is exactly the pattern we are avoiding — it duplicates
alignment logic that already exists and drifts out of sync when the read path changes.

Instead: **add a 2-bit `lookupClass` field to the `DirectoryRead` bundle**, driven by the
Scheduler where it already drives `set` / `tag` / `secondarySearch`. The Directory's existing
alignment registers (`Directory.scala:157-171`, the `params.dirReg(RegEnable(io.read.bits.X, ren), ren1)`
block) carry it to the result **for free**, exactly as they already do for `secondarySearch`.
Suggested encoding: `0 = other`, `1 = demand-A fresh alloc`, `2 = demand-A reload`.

The three events that never touch the directory — request accepted, outer Acquire, repeat — are
counted in the Scheduler from signals already present there (`request.fire`, `request.bits.prio`,
`io.out.a.fire`, `m.io.allocate.bits.repeat`).

### Where the counters live

One new block, `AccessCounters`, instantiated in the Scheduler. It takes the Directory's widened
observation tap plus the three Scheduler-local pulses. Keeping all nine in one module is the point
— do not scatter them.

`Directory.scala`'s existing `l2AccCount` / `l2HitCount` stay exactly where they are.

---

## Part 2 — expose the saturation counters

Add a read-only output on `SetBalanceUnit` carrying its existing per-set saturation counter vector
(`sat`). Nothing else changes in that module. This is the **only** new wire the sampler needs — it
reuses counters that already exist rather than building a second set.

---

## Part 3 — `IntervalSampler`

One new file. **It must be payload-agnostic** — it does not know what a saturation counter is.

- **In:** a flat bundle of bits, plus `enable` and `interval`
- **Every `interval` cycles:** latch the bundle into a freeze register, then stream it into a
  snapshot RAM at 64 bits per cycle
- **Out:** indexed readback (`snapIdx`, `wordIdx` → `readData`), snapshot count, full flag,
  dropped-snapshot count

Three requirements:

- **The freeze register is load-bearing.** Counters keep updating while the frozen copy streams
  out, so sampling never stalls the cache. Do not sample directly from the live vector.
- **A snapshot that comes due while the previous is still streaming is dropped and counted.**
  That counter is how the user knows the interval was set too short. Do not silently overwrite.
- **Recording stops when the RAM is full.** Do not wrap.

Store **raw counter values**, not quantised buckets. Bucketing happens in the python parser.
This removes the threshold registers and comparator tree the old branch had, and keeps full
resolution. Depth: 1024 snapshots, parameterised.

Optional **decay** as a runtime knob: every `decayPeriod` cycles, right-shift every saturation
counter by `decayShift`. **Default off** — SBC's migration decisions read these same counters, so
decay-on changes SBC behaviour, and every verified number to date was measured with it off.

Gate the whole of Parts 2–3 behind one new micro-parameter, default `false`, plumbed through
`WithInclusiveCache`. With it off, **zero hardware** — the standing repo rule.

---

## Part 4 — two counter bugs found in the SBC audit

Both sit in `SetBalanceUnit.scala`, in code Part 1 already touches. Fix them here.

**B — the over-parking assert is vacuous.**
```scala
when (parkCount(incSet) =/= params.cache.ways.U) { parkCount(incSet) := parkCount(incSet) + 1.U }
assert (!parkInc || parkCount(incSet) <= params.cache.ways.U, "SBC: more lines parked out of ...")
```
The increment is already clamped at `ways`, so `parkCount <= ways` holds by construction and the
assert can never fire. It was written to catch a commit arriving when the count is already at
`ways` (so the increment is silently dropped). Change the comparison to `=/= params.cache.ways.U`.

**C — `parkCount` and `nParked` drift.** `parkCount` saturates at `ways`; `nParked` does not, so a
commit dropped by that clamp still increments `nParked` and `SBC_Parked` over-reports. Once B fires
this becomes visible. Note it in your report; no separate fix needed unless B actually fires.

**Do not touch** `SBC_HomeBranch`. It counts `hit && state === BRANCH` on every directory result,
any channel, without checking `req_needT` — so it is *not* an upgrade counter. That is why
`L2_AcqUpgrade` is being added instead. Leave the existing counter alone; changing it would break
continuity with earlier measurements.

---

## MMIO

Next free offset after `SBC_StatsReset` (0x3B8) is **0x3C0**. Window is 4 KiB, so there is room.

| Offset | Field |
|---|---|
| `0x3C0` | `L2_ReqA` |
| `0x3C8` | `L2_AcqA` |
| `0x3D0` | `L2_AcqUpgrade` |
| `0x3D8` | `L2_LookupA` |
| `0x3E0` | `L2_LookupHitA` |
| `0x3E8` | `L2_ReloadLookupA` |
| `0x3F0` | `L2_ReloadHitA` |
| `0x3F8` | `L2_Repeat` |
| `0x400` | `L2_RepeatHit` |
| `0x420` | `SMP_Ctrl` — bit0 enable, bit1 reset (auto-clearing pulse) |
| `0x428` | `SMP_Interval` (cycles) |
| `0x430` | `SMP_DecayPeriod` (0 = disabled) |
| `0x438` | `SMP_DecayShift` |
| `0x440` | `SMP_SnapIdx` (W) |
| `0x448` | `SMP_WordIdx` (W) |
| `0x450` | `SMP_ReadData` (R, 64-bit) |
| `0x458` | `SMP_Status` — bit0 full, bit1 streaming |
| `0x460` | `SMP_WriteCount` |
| `0x468` | `SMP_Dropped` |
| `0x470` | `SMP_Geom` — sets, satBits, wordsPerSnap, depth |

Two notes carried from the existing code:
- `SMP_ReadData` is 64-bit and needs the same `control.beatBytes >= 8` guard `flush64` uses.
- The write-only pulse fields must use the fire-and-forget `RegWriteFn` form already used by
  `SBC_Reset` (`Control.scala:174-177`) — `ovalid` must not track `ivalid` or the D beat never
  fires on real fabric (hangs on FPGA, not in sim).

**Mirror every new offset into `sw/sbc_mmio.h` AND `sw/sbc_read.c` in the same change**, per the
CLAUDE.md rule. In `sbc_read.c`, **append to `REGS[]`, never insert** — `show()` indexes it
positionally (`v[13]`/`v[14]` are accesses/hits). Update the CLAUDE.md register table too.

## Reset behaviour

The nine Part-1 counters are baseline statistics, like 004's. **Do not** wire them into `SBC_Reset`
(0x358). **Do** include them in `SBC_StatsReset` (0x3B8), which exists for exactly this — a
per-window zero that leaves the migration flow running.

---

## Verify

1. **Zero-hardware check.** Build a stock config with the new flag off and confirm the generated
   Verilog is unchanged versus `sbc-baseline-not-verified-2026-09-09`. This is the standing repo rule.
2. **Nothing regressed.** `migration_stress_test` on `VerilatorRocket8KL116KL2Config` must stay
   **7/7 PASS, 0 asserts**, and `L2_Accesses`/`L2_Hits` must read **identically** to a
   `sbc-baseline-not-verified-2026-09-09` run of the same binary. Run through `make run-binary` / `run_sbc.sh` so
   `+dramsim` is passed — a bare `$SIM` invocation omits it and false-fires the BankedStore shadow
   assert at cycle 27141. That is a known false positive, not corruption.
3. **Counter identity — the test that proves Part 1.** With SBC off,
   `L2_ReqA` must equal `L2_LookupA + L2_ReloadLookupA + L2_Repeat + (requests merged but not yet
   reloaded)`. Report the residual. If it does not close, the decomposition is wrong.
4. **Channel bias, quantified.** Report `L2_Accesses - L2_LookupA` and the resulting hit rates side
   by side. This number *is* the C+X pollution that motivated the task — it should be non-trivial.
   004 Amendment 1 measured SBC-off at 349,025 accesses / 216,743 hits = 62.10%; say what the
   A-only rate is for the same workload.
5. **Repeat-after-flush — directed test.** Flush a line over MMIO, then touch it again so the MSHR
   takes the repeat path. `L2_Repeat` must increment while `L2_RepeatHit` does **not**. This is the
   exact case the old branch got wrong; do not skip it because it looks unreachable.
6. **H1 vs H2 gap.** Measure both on a real workload. Expect a **large** gap, since load-then-store
   is common. Cross-check it against `L2_AcqUpgrade` — the gap should be explained by it. A gap near
   zero, or one that `L2_AcqUpgrade` does not explain, means a counter is misplaced.
7. **Cross-config sanity, per 004 Amendment 1's method.** Same binary on SBC-on and SBC-off: the
   demand access count (`L2_ReqA`) must be nearly identical between configs, since the program is
   the same. 004 used this to prove its correction — a large divergence means contamination.
8. **Sampler correctness.** Read one set's live value via `SBC_SetSel`/`SBC_SetSat` and confirm it
   matches that set's column in a snapshot taken at the same time.
9. **Sampler pressure.** Run with an interval short enough to force drops and confirm `SMP_Dropped`
   is non-zero and `SMP_WriteCount` stops at the RAM depth.

## What NOT to do

- Do not change `L2_Accesses` / `L2_Hits`, or fold them into the new block.
- Do not build a parallel `RegEnable` pipeline to carry the channel bit — widen `DirectoryRead`.
- Do not count a repeat as a hit without checking the hit bit.
- Do not quantise into buckets in hardware; dump raw and bucket in python.
- Do not port `TLDirMonitor.scala`, `SaturationCounter.scala` or `PerfProbe.scala` from
  `TL_signal_analysis`. Their per-set counters duplicate `SetBalanceUnit`, their hit/miss counters
  duplicate 004's, and `TLDirMonitor` carries an **unconditional per-cycle `printf` inside a
  per-set/per-source loop** that would emit thousands of lines per cycle.
- Do not enable decay by default.
- Do not reorder `BankedStore` priorities for any reason (load-bearing for deadlock freedom).

## Scope note — per-core breakdown is deliberately out

The dropped `TLDirMonitor` also tracked activity per `(set, source)`. We are not building that:
it needs a B-channel tap and a directory-write tap, and it is not in the requirement. If it is
wanted later it is additive — widen SBC's counters to per-`(set, core)` and the sampler carries the
wider bundle unchanged. That is the reason Part 3 insists the sampler be payload-agnostic.

## Not in this task

Instruction and cycle counts (`mcycle` / `minstret`) are **not** cache state and must not be added
to the L2. They are read in the workload wrapper around the sampling window so each dump has a time
base. Thinker will handle the software side.
