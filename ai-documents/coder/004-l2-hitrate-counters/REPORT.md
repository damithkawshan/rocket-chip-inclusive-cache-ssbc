# Coder report — 004 (L2 hit-rate counters)

**Date:** 2026-09-02 · **Author:** coder session · **Status:** CLOSED
**Work order:** [TASK.md](TASK.md)

---

## Summary

Added two always-on, free-running L2 counters — `L2_Accesses` (every primary directory lookup) and
`L2_Hits` (the subset that hit) — tapped off `Directory.io.result.valid` (ren2) and
`Directory.io.result.bits.hit`, exposed as read-only MMIO at **0x3A8 / 0x3B0**. Both configs elaborate
and run clean; both counters are **non-zero on both configs** (the `Scheduler:715` trap was avoided);
the change is **purely additive**; and `sbc_stats.py` now renders filled-in `TOTAL L2` and
`WHAT SBC RECOVERED` blocks. **Headline number:** on `migration_stress_test`, SBC-off hits **62.10%**
of primary lookups; SBC-on hits **42.77%** primary / **44.81%** effective (after secondary recovery).
On this workload/geometry SBC is **net-negative** — reported as a finding, not tuned.

## What was built

| File | Change |
|---|---|
| `Directory.scala` | Two 64-bit `RegInit` counters (`l2AccCount`/`l2HitCount`), `++` on `io.result.valid` / `io.result.valid && io.result.bits.hit`; new outputs `io.l2Accesses` / `io.l2Hits`. |
| `Scheduler.scala` | New outputs `io.l2Accesses` / `io.l2Hits`, driven `:= directory.io.…` **outside** the `if (enableSetBalancing) … else { io.sbcStats := 0 }` block. |
| `InclusiveCache.scala` | `ctrl.module.io.l2Accesses := mods(bank).io.l2Accesses` (+ hits), unconditional — both configs. |
| `Control.scala` | New `Input` ports + `RegField.r(64, …)` at **0x3A8** (`L2_Accesses`) / **0x3B0** (`L2_Hits`); appended to the regmap. Not gated on `enableSetBalancing`; not wired into `SBC_Reset`. |
| `sw/sbc_mmio.h` | `SBC_L2_ACCESSES` (0x3A8), `SBC_L2_HITS` (0x3B0). |
| `CLAUDE.md` | Register-map table rows for 0x3A8 / 0x3B0. |
| `sw/migration_stress_test.c`, `sw/matmult_stress.c`, `sw/serve_in_place_test.c` | `l2Accesses=`/`l2Hits=` added to the `[SBC-COUNTERS]` / `[SIP-TOTALS]` line. `sbc_stats.py` untouched. |

**Which file owns the registers:** `Control.scala` (RegField.r). **Wiring path:**
`Directory` (counters) → `Scheduler.io.l2Accesses/l2Hits` (driven outside the SBC gate) →
`InclusiveCache` → `Control` regmap. **How the `Scheduler:715` trap was avoided:** the counters do
**not** travel through `SBCStats`. They live in `Directory` (always instantiated — unlike
`SetBalanceUnit`) and reach `Control` on a dedicated Scheduler output that is assigned *after/outside*
the `enableSetBalancing` if/else that zeroes `io.sbcStats`. Proof they are non-zero with SBC off: the
NoSbc run below reads `l2Accesses=349025 l2Hits=216743`.

## Verify

Run: `sw/scripts/run_sbc.sh` (SBC-on, run-binary-debug) + `make run-binary` (NoSbc, non-debug) — both
with `+dramsim`. LABEL `l2-hitrate-counters-004`.

1. **Both configs elaborate + run clean.** SBC-on: `*** PASSED ***` @ 22,725,786 cyc, 0 asserts.
   NoSbc: `*** PASSED ***` @ 22,952,706 cyc, 0 asserts. 3 elaboration warnings each (the pre-existing
   rocket-chip `Error.scala` printf-deprecation ones, unrelated).
2. **hits ≤ accesses on both.** SBC-on 193166 ≤ 451587 ✓. NoSbc 216743 ≤ 349025 ✓.
3. **Both counters non-zero on both configs** (trap check). SBC-on `l2Accesses=451587 l2Hits=193166`;
   **NoSbc `l2Accesses=349025 l2Hits=216743`** — non-zero with SBC off ✓.
4. **No existing counter changed — purely additive.** `git diff` on the four RTL files is +36 / −1;
   the single deletion is the `0x3A0 -> Seq(sbcParkedField)` regmap line gaining a trailing comma so
   the two new entries can follow — no existing signal, counter, or logic path was modified. The new
   counters are pure observers with their own IO path.
5. **`sbc_stats.py` blocks (pasted from both runs):**

```
SBC-ON  (VerilatorRocket8KL116KL2Config)
  TOTAL L2 (every primary directory lookup)
    accesses  : 451587
    hits      : 193166   (42.77%)
    misses    : 258421  (57.23%)
  SECONDARY (partner-set search, only after a home-set miss)
    searches  : 64220
    hits      : 9187   (14.31%)
    misses    : 55033  (85.69%)
  WHAT SBC RECOVERED
    secondary hits as a share of all misses : 9187/258421 = 3.56%
    effective hit rate with SBC             : (193166+9187)/451587 = 44.81%   (baseline 42.77%)

SBC-OFF (VerilatorRocket8KL116KL2NoSbcConfig)
  TOTAL L2 (every primary directory lookup)
    accesses  : 349025
    hits      : 216743   (62.10%)
    misses    : 132282  (37.90%)
  SECONDARY (partner-set search)
    searches  : 0   hits : 0   misses : 0
  WHAT SBC RECOVERED
    secondary hits as a share of all misses : 0/132282 = 0.00%
    effective hit rate with SBC             : (216743+0)/349025 = 62.10%   (baseline 62.10%)
```

6. **Two hit rates side by side:**

| | primary hit rate | effective (incl. secondary) | total accesses |
|---|---:|---:|---:|
| **SBC OFF** (NoSbc) | **62.10%** | 62.10% | 349,025 |
| **SBC ON**  (stock) | 42.77% | **44.81%** | 451,587 |

## Findings (reported, not tuned — per the work order)

- **SBC is net-negative on this workload/geometry.** SBC-off hits 62.10%; SBC-on manages only 44.81%
  effective. Consistent with CLAUDE.md's standing note that a displaced/parked line cannot serve a
  primary hit, so migration on the tiny 8-set geometry lowers primary associativity faster than the
  3.56%-of-misses secondary recovery buys back. This is now *measured*, not arithmetic.
- **SBC-on issues ~102,562 MORE primary lookups than SBC-off for the identical workload**
  (451,587 vs 349,025). `io.result.valid` (as the work order specifies for the tap) counts SBC's
  internal machinery too: `Directory.scala:298`'s SBC tap uses `ren2 && !internalRead`, but this task
  taps `ren2` directly, so `L2_Accesses` on SBC-on includes the destination-probe `internalRead`
  lookups and the migration copies that SBC-off never issues. The two access counts are therefore not
  a like-for-like denominator — the honest comparison is *hit rate*, and even there SBC is behind.
  Flagging in case the intent was demand-only accesses (would need `&& !internalRead`, a spec change).

## Anything the work order got wrong or left open

- **`run_sbc.sh` (run-binary-debug) stalled on the NoSbc run** — the debug build writes an ~82 GB VCD;
  the NoSbc traced run spun at full CPU without completing. Re-ran NoSbc via `make run-binary`
  (non-debug, still `+dramsim`), which completed cleanly. The SBC-on numbers above are from run_sbc;
  the NoSbc numbers are from the non-debug run-binary. Nothing about the counters is affected.
- The CLAUDE.md MMIO table was already stale for 0x360–0x3A0 (missing rows). I added only the two new
  0x3A8/0x3B0 rows as instructed; the 0x360–0x3A0 gap predates this task.
