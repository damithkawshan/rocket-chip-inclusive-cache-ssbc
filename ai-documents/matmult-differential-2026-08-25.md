# matmult differential run — SBC is data-safe, and 42% slower

**Date:** 2026-08-25 · **Status:** result, signed off by measurement
Companions: [phase-3.md](phase-3.md) · [destination-side-blocker.md](destination-side-blocker.md) ·
[phase-2.md](phase-2.md)

**First end-to-end SBC-on vs SBC-off comparison on a real third-party benchmark.**
Two findings, and they point in opposite directions.

---

## Headline

| | SBC on | SBC off (control) | |
|---|---:|---:|---|
| **Checksum** | **29824** | **29824** | ✅ identical |
| Mismatches / asserts | 0 / 0 | 0 / 0 | ✅ |
| Harness banner | `*** PASSED ***` | `*** PASSED ***` | ✅ both ran to completion |
| Migrations committed | 1,737 | 0 | |
| **Cycles** | **22,278,686** | **15,683,316** | 🔴 **+42.0%** |
| **DRAM fetches (OUTER-A)** | **122,144** | **13,147** | 🔴 **9.29×** |
| L2 evictions (EVICT-ASSESS) | 122,080 | 13,083 | 9.33× |

- **Correctness: PASS.** Identical architectural result with 1,737 migrations in flight. `29824` is now
  an externally verified reference, not a self-reported number.
- **Performance: a large regression.** Not a rounding error. Cause is a hypothesis, not established —
  see the caveats below before quoting any mechanism.

---

## The test

**Benchmark:** `matmult` from bringup-bench (third-party, computation unmodified), N overridden 64 → 32
at build time on a copy so the upstream repo stays pristine.

- Multiplies two 32×32 `int` matrices.
- **Self-checking by construction:** computes the product twice with different loop orders
  (`i,j,k` then `i,k,j`), compares all 1,024 elements, then checksums the result.
- Deterministic input (`libmin_srand(12345)`, Mersenne Twister) — required for a differential run.
- **Causes set imbalance structurally:** a row is 32×4 = 128 B = 2 cache lines, so the `B[k][j]` column
  traversal strides 128 B and the set index advances by 2 — that stream touches only **4 of the 8 sets**.
  This is real imbalance, unlike `migration_stress_test` which hand-picks `HOT_SET=5`.

**Sizing (checked before running):** N=64 upstream would be 64 KB of matrices = 16× the L2 and
~524k inner iterations — hours of Verilator. N=32 gives 16 KB (4× L2) and ~65k iterations, comparable to
the stress test's proven runtime.

**Port:** bringup-bench needs only four target functions. `sw/bringup/libtarg_chipyard.c` implements
putc/success/fail/sbrk over the riscv-tests HTIF path; `sw/scripts/compile_bringup.sh <bench> [N]` builds
any benchmark in the suite. Note `crt.S` does **not** zero `.bss` and the sim randomises DRAM, so the
heap pointer is `.data`-resident rather than a zero-initialised static.

## The two configs

Identical in every respect except SBC: `nWays=8, capacityKB=4, subBankingFactor=2`, L1 I$/D$ each
`2 sets × 2 ways × 64 B = 256 B`, 1 Big Rocket core, same `AbstractConfig`.

| | config | difference |
|---|---|---|
| SBC | `VerilatorRocket8KL116KL2Config` | `sbcAutoMigrate = true` (`enableSetBalancing` defaults true) |
| control | `VerilatorRocket8KL116KL2NoSbcConfig` | `enableSetBalancing = false` |

**Control verified genuinely SBC-free**, two ways:

1. **Statically** — every migration entry point is gated: `adviceMigrate` / `dstOfferValid` are
   `WireInit(false.B)` and assigned only inside `if (enableSetBalancing)` (Scheduler `:191/:197/:470`);
   `migFastWantW` and `migFastDecline` are gated by `enableSetBalancing.B`
   ([MSHR.scala:727/739](../design/craft/inclusivecache/src/MSHR.scala#L727)); `migDeferred` is only set
   under the gate at `:1011`. With no migration, `s_dread` never fires, so `internalRead` is permanently
   false and the Directory behaves exactly as upstream. SBU/DSS are not instantiated.
2. **Empirically** — **0 `MIG-*` lines of any kind** in the control output.

⚠️ **Gotcha for future runs:** `OUTER-A` / `EVICT-ASSESS` / `EVICT-NORMAL` printfs are gated by
**`sbcDebug`, not `enableSetBalancing`**, so the control still emits ~39k `[SBC]` lines. They are pure
observation. "Zero `[SBC]` lines" is the **wrong** check for an SBC-off run — grep `MIG-` instead.

⚠️ The checksum is printed to `bringup_matmult.log` (stdout). `bringup_matmult.out` is the
spike-dasm'd trace (stderr). Grepping the wrong one reports "no result line" on a perfectly good run.

---

## Why it got slower — leading hypothesis, not yet proven

**What is measured:** 9.29x the DRAM fetches for identical work. `OUTER-A` fires on
`io.schedule.bits.a.valid && io.schedule.ready` ([MSHR.scala:496](../design/craft/inclusivecache/src/MSHR.scala#L496))
— a true count of outer fetches, gated only by `sbcDebug`, so the condition is identical in both
configs. Same binary (md5 `faefe80f…`), both `*** PASSED ***`.

⚠️ **What is NOT measured: the miss *rate*.** An earlier draft of this doc claimed "~10% -> ~93%".
There is no L2-access counter, so that denominator was **estimated, not measured** — do not quote it as
a measurement. For scale only: the program runs 2 x 32³ = 65,536 inner iterations at ~2 loads each
(~131k memory ops), and the L1D is 4 lines so nearly all reach L2 — which would put SBC near a
~60-90% miss rate against a baseline near ~10%. **Arithmetic, not instrumentation.** An L2-access
counter would settle it.

**Hypothesis: sets are filling with displaced lines, which cannot serve hits and are almost unevictable.**
A displaced line is excluded from hit detection *and* from every victim tier except the last-resort
reclaim tier, so a set that accumulates them keeps recycling its few remaining native ways — effective
associativity collapses. CLAUDE.md already warns of exactly this ("a 7/8-displaced set recycles its one
native way until Phase-3 spreading").

Supporting evidence from this run:

| Signal | Value | Reading |
|---|---:|---|
| `displaced` share of destination rejects | **22.9%** | up from 1.0% on the stress test — destination sets really are full of parked lines |
| `EVICT-DISPLACED-RECLAIM` | **1,681** | vs 1,737 commits — nearly every parked line is evicted again before the run ends |
| `MIG-PROBE-DIRTY` | 1,895 | source-side dirty rejects, no longer negligible on a write-heavy workload |

**Stronger than the reclaim ratio: in Phase 2 a displaced line can NEVER serve a hit.** Hit detection
excludes them by construction (`w.tag === tag && w.state =/= INVALID && !w.displaced`,
[Directory.scala:~146](../design/craft/inclusivecache/src/Directory.scala)). So it is not that 97% of
migrations were wasted — **100% of them were, by design**, until Phase 3 adds the secondary search.
Each migration costs an internal copy plus a way that cannot hit, and returns nothing.

⚠️ **Honest caveat: the magnitude is not fully accounted for.** 1,737 parked lines cannot by themselves
produce 109,000 extra DRAM fetches. The capacity-collapse mechanism above explains a cliff qualitatively,
but nothing here *measures* how many ways are displaced at steady state. **Do not quote the mechanism as
established** until a displaced-occupancy counter confirms it.

**Fair-comparison caveat:** this is a 64-line L2. Parking 1,737 lines is enormous relative to capacity,
so the penalty is amplified by the tiny geometry. A realistic L2 would dilute it. The *direction* would
not change — migration without reuse is always a net cost — but the 42% figure is specific to this
config and must not be quoted as SBC's general overhead.

---

## What this changes

**Phase 3 is not an enhancement. It is what makes SBC not-negative.** We predicted this
(HOT events 4,113 → 27,334 on 2026-08-24) but had never measured it end to end against a control.
The 9.29× traffic figure is the number that justifies the remaining work — and the number any
performance claim has to beat.

**Correctness is answered for this workload.** The migration datapath — SCU copy, displaced install,
dual dir-write, displaced-aware hit, hazard interlocks, reclaim tier — survived 1,737 migrations on an
unmodified third-party benchmark with a redundant-computation oracle, and produced bit-identical output.

**Residual correctness gap:** both computations read the same A and B, so corruption of the *input*
matrices would be invisible to the internal C-vs-refC check. The differential run closes most of that
(a corrupted input would change the checksum, and it did not), but a value corrupted and then overwritten
before the checksum is computed would still slip through. Full-region verification remains owed.

---

## Reproducing

```bash
# build (N override applied to a copy; upstream repo untouched)
./sw/scripts/compile_bringup.sh matmult 32

# run either config
make -C $CY/sims/verilator run-binary \
  BINARY=sw/build/bringup_matmult.riscv \
  CONFIG=VerilatorRocket8KL116KL2Config      # or ...NoSbcConfig
```

Logs kept at `sw/verilator_logs/bringup_matmult_VerilatorRocket8KL116KL2{,NoSbc}Config_n32/`.
