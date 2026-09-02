# Coder task 004 — total L2 hit-rate counters

**Date:** 2026-09-01 · **Author:** thinker session
**Depends on:** none (independent of 003; can run on any config, SBC on or off)

## Why

Every SBC counter we have (`secHits`, `secMiss`, ...) counts only the **secondary path** — lookups
that already missed the home set and are checking a partner set for a parked line. There is **no
counter for the L2 as a whole**: no total accesses, no total hits, no total misses. CLAUDE.md already
flags this: *"there is no L2-access counter, so any miss rate figure is arithmetic, not
instrumentation."* Still true.

Without this we cannot answer "did SBC improve the overall hit rate", on any benchmark, ever — we can
only ever show the mechanism fired, never that it helped.

## What to build

Two new free-running counters, **always active** (not gated behind `enableSetBalancing` — this is a
baseline cache statistic, useful with SBC on or off):

- `L2_Accesses` — every primary directory lookup result.
- `L2_Hits` — the subset of those that hit.

(Misses are `Accesses - Hits`; no separate counter needed, but add one if it is free — your call.)

## Where

`Directory.scala`: `io.result.valid` pulses once per primary lookup result; `io.result.bits.hit`
tells hit vs miss on that same cycle (`Directory.scala:286-288`). This is the natural tap point — one
lookup, one pulse, already both signals you need on the same cycle. Do **not** derive this from
`Scheduler`/`MSHR` state; the Directory result is the single source of truth for hit/miss and is
already used that way by the existing `secHits`/`hit` logic.

Follow the existing counter wiring pattern (`SBC_Migrations` etc. in `SetBalanceUnit.scala` →
`SBCStats` → `Control.scala` regmap) — these two are free-running counters, so they can live as plain
registers wherever is most natural (in `Directory.scala` itself, or bundled through
`SetBalanceUnit`/`SBCStats` if that keeps the wiring pattern consistent — your judgment).

## MMIO

Add two new read-only registers at the next free offsets after `SBC_Parked` (`0x3A0`):

| Offset | Field | Meaning |
|---|---|---|
| `0x3A8` | `L2_Accesses` | Total primary directory lookups (hit + miss), free-running |
| `0x3B0` | `L2_Hits` | Total primary hits, free-running |

Update `sw/sbc_mmio.h` with both offsets so all test/benchmark binaries can read them.

**Update the register-map table in CLAUDE.md in the same change** (per CLAUDE.md's own rule: "If you
change any register offset in Control.scala, update the SW-side header in the same change").

## Reset behaviour

These two counters are **not** part of SBC state — do **not** wire them into `SBC_Reset` (0x358).
They should be genuinely free-running for the life of the simulation, so a hit-rate comparison across
a whole run is always well-defined regardless of when/whether `SBC_Reset` was used.

## Verify

- Elaborate `VerilatorRocket8KL116KL2NoSbcConfig` and `VerilatorRocket8KL116KL2Config` — both must
  build clean; the two counters must be present and incrementing under a trivial load/store test
  (e.g. read `L2_Accesses`/`L2_Hits` before and after a handful of loads and confirm both counters
  move sensibly — `L2_Hits <= L2_Accesses`, and a repeated load to the same address should raise
  `L2_Hits` on the second access).
- No existing counter's value should change — this is purely additive.
- Report the two new offsets, confirm which file owns the registers, and give one sample
  before/after read from a short run.

## What NOT to do

- Don't gate these behind `enableSetBalancing` — they must exist and be meaningful with SBC off too,
  since the whole point is an SBC-on vs SBC-off hit-rate comparison.
- Don't fold them into `SBC_Reset`.
- Don't derive hit/miss from anywhere other than the Directory's own `io.result.bits.hit` — a
  re-derivation elsewhere risks disagreeing with the signal every other hit/miss decision already
  uses.

## Reporting is already wired — you only need the RTL + the printf

`sw/scripts/sbc_stats.py` (updated 2026-09-02) already has the "hit / miss summary" section that
consumes these counters. It currently prints `n/a` and points at this task. **Nothing in the script
needs editing.** All you have to do is make the test binary print the two values on its existing
`[SBC-COUNTERS]` / `[SIP-TOTALS]` line — the parser is a generic `key=value` scrape and accepts any
of `L2_Accesses` / `l2Accesses` / `accesses` (and the matching `L2_Hits` spellings), case-insensitive.
A `misses` field is optional; the script derives it.

Once those two values appear, the script additionally prints **"WHAT SBC RECOVERED"** — secondary hits
as a share of all misses, and the effective hit rate with SBC vs the baseline hit rate. That is the
number this whole task exists to produce, so please confirm it renders in your report.

---

## Amendment 1 — measurement defect found in thinker review (2026-09-02)

**The counters are built correctly, but `L2_Accesses` counts SBC's own internal directory probes.
The SBC-on vs SBC-off comparison in REPORT.md is therefore not like-for-like, and its headline
`42.77%` must not be quoted.**

### The defect

`Directory.scala` counts on bare `io.result.valid`. But an `internalRead` lookup is *forced* to miss:

```scala
// Directory.scala:230
!internalRead && w.tag === tag && w.state =/= INVALID && !w.displaced && (...)
```

So every internal probe increments **accesses** and never **hits**. It does not merely inflate the
denominator — it deflates the hit rate directly. REPORT.md spotted the denominator half and then
concluded *"the honest comparison is hit rate, and even there SBC is behind"*; that does not follow,
because the hit rate is contaminated by the same reads.

The SBC observation tap one line above already excludes them, for exactly this reason:

```scala
// Directory.scala:301
io.tap.valid := ren2 && !internalRead
```

This is the second time this repo has been bitten by internal reads polluting a measurement. The
first is recorded in CLAUDE.md: the destination probe reached the tap, so *"84% of all HOT events
were the cache heating sets by probing them."*

### The fix — one term

```scala
when (io.result.valid && !internalRead) {
```

`internalRead` is already in scope at that point (`Directory.scala:167`). No spec change: TASK.md
asks for *primary* lookups, and an internal dread is not one.

### Corrected numbers from the same run

`migration_stress_test`, internal reads = 105,623 (one per `DREAD-SCHED`; `MSHR.scala:576` marks
every dread `internalRead := true.B`, and `Scheduler.scala:425` is the only producer).

| | accesses | hits | hit rate |
|---|---:|---:|---:|
| SBC OFF | 349,025 | 216,743 | **62.10%** |
| SBC ON — as measured | 451,587 | 193,166 | 42.77% ❌ artifact |
| SBC ON — demand only | 345,964 | 193,166 | **55.83%** |
| SBC ON — demand + secondary | 345,964 | 202,353 | **58.49%** |

**The cross-check that proves the correction.** Same binary, same workload, so the demand-access
count must be nearly identical between configs. Corrected: 345,964 vs 349,025 — **0.88% apart**.
Uncorrected: 451,587 vs 349,025 — **29.4% apart**.

### What survives and what does not

- **Survives:** SBC is net-negative on this workload/geometry. Direction unchanged.
- **Does not survive:** the magnitude. The real gap is **58.49% vs 62.10%** (3.6 points), not
  44.81% vs 62.10% (17.3 points) — roughly a 5x overstatement.
- Everything else in REPORT.md is confirmed: the `Scheduler:715` trap was correctly avoided, the
  change is purely additive, and both counters are non-zero on both configs.

### Also worth fixing while there

- The two runs used **different flows** — SBC-on via `run-binary-debug` (VCD), NoSbc via plain
  `run-binary`, because the traced NoSbc run stalled on an ~82 GB VCD. Both had `+dramsim` and
  tracing does not change functional behaviour, so the counters are trustworthy; but the next
  comparison run should use one flow for both configs.
- `InclusiveCache.scala` reads only `mods(bank)` for the control block, so on a **multi-bank** build
  these counters report one bank, not the cache. Same limitation as the existing `sbcStats` wiring,
  and harmless on the single-bank eval config — but it must not be quoted as a whole-cache figure
  once banks > 1.
