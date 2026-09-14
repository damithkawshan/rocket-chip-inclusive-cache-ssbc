# Coder report 005 — counters that follow the cache terminology, all counters in one place, interval sampling

**Date:** started 2026-09-15 · **Author:** coder session · **Status:** IN PROGRESS — commit 0 done, commit 1 not started

> Template. Fill it in as you go, not at the end. A task that stops early still gets a report saying
> where it stopped and why.

## Summary

**Commit 0 landed.** Every monitoring counter now lives in `PerfCounters.scala`: the 12 SBC event
counters and `SBC_Parked` (from `SetBalanceUnit`), `L2_Accesses` / `L2_Hits` (from `Directory`), and the
006 outer-port counters. Same conditions, same cycle, same clears. `enablePerfCounters = false` now
removes all of it plus the `SBC_SetSel` / `SetSat` / `Status` bit 2 / `AtAssoc` read-backs; those
registers read 0. `L2_MemUpgrades` is renamed `L2_MemAcqPerm` (same address `0x3D8`). C1 and C2 pass:
stress test 7/7 with 0 asserts on all four builds, switch test T1–T4 PASS, and the flag-off Verilog has
no counter hardware. Commits 1–3 not started.

## Commits

| # | What | Hash | Checks | Notes |
|---|---|---|---|---|
| 0 | move counters into `PerfCounters`, one flag, `L2_MemAcqPerm` | _(this commit; hash in the next REPORT update)_ | C1 ✅ C2 ✅ | No `PopCount` yet: MSHR pulses still OR-reduced, fed into `log2Ceil(mshrs+1)`-bit inputs that are added |
| 1 | outcome counters, `PopCount`, `L2_StatsHold`, `sbc_read`, tests | | C3–C13 | |
| 2 | three counter fixes | | C14–C16 | |
| 3 | expose `sat`, `IntervalSampler` | | C17–C20 | |

## Elaboration values

| | SBC config | NoSbc config |
|---|---|---|
| `mshrs` | 7 | 7 |
| `secondary` | 33 | 33 |
| in-progress bound `(mshrs − 2) + secondary` | 38 (matches TASK T5) | 38 |
| `[SBC][elab] BRANCH reachability` line | `p=false m=true r=false b=false (b false => secPerm=0 is correct)` | n/a |
| sampler words per snapshot / RAM bits | | n/a |

Printed by the new `[SBC][elab] PerfCounters:` line (`sw/verilator_logs/005-c0-console.log`).

## Checks

| # | Check | Command / log path | Result |
|---|---|---|---|
| C1 | Move works — tests pass, moved counters still count | `SBC_LABEL=005-c0 ./sw/scripts/run_sbc.sh`, then `SBC_TESTS=sbc_migrate_switch_test SBC_CONFIGS=VerilatorRocket8KL116KL2Config SBC_CLEAN=0 SBC_LABEL=005-c0 ./sw/scripts/run_sbc.sh`. Logs `*_005-c0` | ✅ **PASS.** Stress test 7/7, 0 asserts on SBC and NoSbc. Switch test T1–T4 PASS (the `sbc_stats.txt` summary says "3/3" — it did in `006-closeout` too; the parser does not count the T4 line). Every counter a test prints is non-zero where `006-closeout` was non-zero and zero where it was zero — table below. See findings F2 (values differ) and F3 (four counters no test prints) |
| C2 | Flag off — no monitoring hardware, stress test 7/7 | Two temporary configs `VerilatorRocket8KL116KL2NoPerfConfig` / `…NoSbcNoPerfConfig` (= the two stock configs + `enablePerfCounters = false`), added to `RocketConfigs.scala` uncommitted and **deleted after the run**. `SBC_CONFIGS="…NoPerfConfig …NoSbcNoPerfConfig" SBC_CLEAN=0 SBC_LABEL=005-c0 ./sw/scripts/run_sbc.sh`. Verilog searched in `sims/verilator/generated-src/chipyard.harness.TestHarness.<cfg>/gen-collateral/` | ✅ **PASS.** Both elaborate. Stress test 7/7, 0 asserts on both (every counter line reads 0, as expected). Verilog, flag off → on: `PerfCounters` module 0 → 1; counter registers (`memReads`, `memAcqPerm`, `l2Accesses`, `l2Hits`, `migrations`, `secHits`, `parked`, `cycles`) 0 → 8 (SBC) / 5 (NoSbc); `sbcSetSel` register 0 → 1; uses of `io_satReadSet` in `SetBalanceUnit` 0 → 5 |
| C3 | Regression — 7/7, switch 4/4 | | |
| C4 | One-pulse-per-cycle asserts quiet | | |
| C5 | Access identity — in progress min / max per case | | |
| C6 | Port identity — exact | | |
| C7 | Second-search identity | | |
| C8 | Repeat after a flush | | |
| C9 | Upgrade misses — value, and why if 0 | | |
| C10 | Probed hits — value | | |
| C11 | Hold | | |
| C12 | 64-bit reads | | |
| C13 | Same binary, SBC vs NoSbc `L2_AccessA` | | |
| C14 | Write-back fix — before / after | | |
| C15 | Declines — attempted identity | | |
| C16 | Parked-count assert — fired? | | |
| C17 | Sampler off — no hardware | | |
| C18 | Sampler matches live `SBC_SetSat` | | |
| C19 | Snapshot is one instant | | |
| C20 | Sampler under pressure | | |
| C21 | Area, flag on / off | | |

### C1 counter values (`migration_stress_test`, end of run), `005-c0` against `006-closeout`

| Counter | SBC 005-c0 | SBC 006-closeout | NoSbc 005-c0 | NoSbc 006-closeout |
|---|---:|---:|---:|---:|
| `SBC_Migrations` | 14,903 | 14,920 | 0 | 0 |
| `SBC_Attempted` | 30,113 | 29,816 | 0 | 0 |
| `SBC_Aborted` | 15,317 | 14,992 | 0 | 0 |
| `SBC_SecHits` | 15,285 | 21,069 | 0 | 0 |
| `SBC_SecMiss` | 50,893 | 46,121 | 0 | 0 |
| `SBC_SecPerm` | 0 | 0 | 0 | 0 |
| `L2_Accesses` | 343,773 | 343,708 | 350,074 | 350,880 |
| `L2_Hits` | 180,560 | 180,440 | 216,974 | 217,586 |
| `L2_MemReads` | 147,971 | 142,258 | 133,109 | 133,301 |
| `L2_MemWrites` | 23,845 | 19,217 | 26,403 | 26,503 |
| `L2_MemAcqPerm` (`memUpgrades=` in old logs) | 0 | 0 | 0 | 0 |
| `L2_MemRelClean` | 109,159 | 108,057 | 106,642 | 106,734 |
| `L2_Cycles` | 11,302,999 | 11,171,846 | 11,514,857 | 11,528,105 |

Switch test (`SBC_Parked`, and `SBC_SecHits + SBC_DispRelease + SBC_DispDrop` as "serve/retire
events"): T2 `migrations=0 parked=0`, T3 `migrations=2 parked=2`, T4 `3 -> 3`, events `211 -> 399`,
parked at flip 3 — **identical to `006-closeout`**.

## Numbers

_Same test program on both configs._

| | SBC | NoSbc |
|---|---:|---:|
| `L2_AccessA` | | |
| `L2_PrimaryHit` | | |
| `L2_SecondaryHit` | | |
| `L2_DataMiss` | | |
| `L2_UpgradeMiss` | | |
| hit rate = (primary + secondary) ÷ accesses | | |
| `L2_ProbedHit` | | |
| `L2_SecondSearch` / `L2_SecondaryMiss` | | |
| legacy `L2_Accesses` / `L2_Hits` | | |
| `SBC_Attempted` / `SBC_Migrations` / `SBC_Aborted` | | n/a |
| `SBC_SecWrite`, `SBC_SecPerm` before / after the fix (C14) | | n/a |
| `SBC_Declined` | | n/a |

## Findings (reported, not fixed)

- **F1 — `sbc_stats.py` flags `secHits` "!! MISMATCH delta +19" (commit 0 run).** Not a counting error.
  The gap is +19 in both `006-closeout` and `005-c0`; the parser allows `max(8, 0.1% of the count)`
  (`sbc_stats.py:249`). `006-closeout` had 21,088 serves (allowance 21 → "tail"), `005-c0` has 15,304
  (allowance 15 → "MISMATCH"). The `secHit` pulse and the `SEC-SERVE` printf sit in the same
  `when (willServe)` branch (`MSHR.scala:1377-1440`), so they can only differ by events after the test's
  final MMIO read. Script not changed.
- **F2 — the Verilator sim is not bit-reproducible across builds, so counter values cannot be compared
  with older runs.** The `.out` traces of `006-closeout` and `005-c0` already differ at cycle 32 (the
  value logged for a write to `x0` at `csrw mideleg`), before any L2 traffic; the NoSbc build, where commit
  0 changes nothing but where counters live, differs too. This is why TASK §0 says values need not match.
  The evidence that cache behaviour did not change is therefore: the diff touches only counter logic and
  MMIO read-backs; all tests pass; and the short switch test gives exactly the `006-closeout` numbers.
- **F3 — four moved counters are printed by no test, so C1 cannot show they still count:**
  `SBC_SecWrite`, `SBC_SecProbe`, `SBC_SecC`, `SBC_HomeBranch`. `SBC_DispRelease` / `SBC_DispDrop` are seen
  only as a sum in the switch test. Their wiring is the same as the printed ones (MSHR pulse → OR →
  `+ in`). Commit 1 makes the stress test print every counter, so C3 covers them.
- **F4 — the `SBC_Reset issued while lines are still parked` assert moved with `SBC_Parked`** into
  `PerfCounters`, so it is absent when `enablePerfCounters = false`. It is sim-only, and flag-off builds are
  for area.
- **F5 — `SBC_Parked` is now `log2Ceil(sets·ways + 1)` bits (TASK §4.2):** 7 bits on the 8×8 sim config.
  It can only exceed `sets·ways` if it drifts from the real count (the question C16 asks); it would then
  wrap, where the old 64-bit register would not.
- **F6 — stale commented-out code:** the commented `DUMP` block in `SetBalanceUnit.scala` (cleanup Batch A)
  still names `io.stats.migrations` / `attempted` / `aborted`, which no longer exist. Left alone (out of
  scope); delete it with Batch A.

## Where the work order is wrong

**Commit 0: nothing.** Every line reference in §4 matched `e4c5d53`.

## Logs

- `sw/verilator_logs/005-c0-console.log` — build and run console for all three runs
- `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2Config_005-c0/`
- `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2NoSbcConfig_005-c0/`
- `sw/verilator_logs/sbc_migrate_switch_test_VerilatorRocket8KL116KL2Config_005-c0/`
- `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2NoPerfConfig_005-c0/` (C2, temp config)
- `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2NoSbcNoPerfConfig_005-c0/` (C2, temp config)
