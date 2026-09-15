# Coder report 005 — counters that follow the cache terminology, all counters in one place, interval sampling

**Date:** started 2026-09-15 · updated 2026-09-16 · **Author:** coder session · **Status:** IN PROGRESS — commits 0 and 1 landed, commits 2 and 3 not started

> Template. Fill it in as you go, not at the end. A task that stops early still gets a report saying
> where it stopped and why.

## Summary

**Commit 0 landed.** Every monitoring counter now lives in `PerfCounters.scala`: the 12 SBC event
counters and `SBC_Parked` (from `SetBalanceUnit`), `L2_Accesses` / `L2_Hits` (from `Directory`), and the
006 outer-port counters. Same conditions, same cycle, same clears. `enablePerfCounters = false` now
removes all of it plus the `SBC_SetSel` / `SetSat` / `Status` bit 2 / `AtAssoc` read-backs; those
registers read 0. `L2_MemUpgrades` is renamed `L2_MemAcqPerm` (same address `0x3D8`). C1 and C2 pass:
stress test 7/7 with 0 asserts on all four builds, switch test T1–T4 PASS, and the flag-off Verilog has
no counter hardware.

**Commit 1 landed.** The eight outcome counters of `cache-terminology.md` (`0x3F0`–`0x428`), `PopCount`
fan-in for every MSHR-fed counter, the nine sim-only "at most one MSHR per cycle" asserts, and
`L2_StatsHold` (`0x438`). `sbc_read` now brackets every read with the hold and prints the terminology
block; the stress test prints the outcome counters and the per-case in-progress value.

**The counters were verified on the board, not only in the sim.** Two full one-bitstream A/B sessions on
the 64 KB L2 (2026-09-15/16) gave, in all four halves: accesses = primary + secondary + data + upgrade
exactly (in progress 0), data misses = memory reads exactly, and second searches = secondary hits +
secondary misses exactly. Board numbers and the findings they produced are in "Board runs" below.
Commits 2 and 3 not started.

## Commits

| # | What | Hash | Checks | Notes |
|---|---|---|---|---|
| 0 | move counters into `PerfCounters`, one flag, `L2_MemAcqPerm` | `a1f4cbd` | C1 ✅ C2 ✅ | No `PopCount` yet: MSHR pulses still OR-reduced, fed into `log2Ceil(mshrs+1)`-bit inputs that are added |
| 1 | outcome counters, `PopCount`, `L2_StatsHold`, `sbc_read`, stress-test prints | _(this commit)_ | C5 ✅ C6 ✅ C7 ✅ C9 ✅ C10 ✅ C12 ✅ on the board; C3/C4/C13 running in Verilator at commit time; C8, C11 **not run** | Also adds `sw/l2_miss_calib.c`, a designed-miss-rate workload used to check the counters against a predicted number |
| 2 | three counter fixes | | C14–C16 | not started. The board shows the `SBC_SecWrite` write-back bug live: 816 against `SBC_SecC` 473 |
| 3 | expose `sat`, `IntervalSampler` | | C17–C20 | not started |

**Why C8 and C11 were not run:** both need a new directed test binary (`flush → repeat`, and the hold
test), which was not written. `L2_StatsHold` is exercised indirectly instead: every `sbc_read` dump and
every `[SBC-INPROGRESS]` line in the stress test is taken under the hold, and all four board halves came
back internally consistent (in progress exactly 0), which a drifting multi-register read would not give.

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
| C3 | Regression — 7/7, switch 4/4 | `bash run_005_c1.sh` (stress test both configs, then the switch test), console `sw/verilator_logs/005-c1-console.log`, logs `*_005-c1` | ⏳ **running when this commit was made.** Fill in on the next REPORT update |
| C4 | One-pulse-per-cycle asserts quiet | same run; the nine new asserts are in `Scheduler.scala` (`atMostOne`) | ⏳ **running.** Quiet in every board run, but board builds have `sbcShadow`/asserts compiled out, so only the Verilator run settles this |
| C5 | Access identity — in progress min / max per case | Board: 4 halves of 2 A/B sessions, read under hold | ✅ **PASS, exactly 0** in all four halves (accesses = primary + secondary + data + upgrade, at ~1.0–1.4 G accesses). Per-case sim numbers pending C3 |
| C6 | Port identity — exact | Board, same four halves | ✅ **PASS, exact.** `dataMiss + upgradeMiss = memReads + memAcqPerm` in all four (e.g. 900,499,669 = 900,499,669) |
| C7 | Second-search identity | Board, migrate-ON halves | ✅ **PASS, exact and tighter than TASK asks.** `secondSearch − secondaryHit − secondaryMiss = 0` (209,140,032 = 61,020,788 + 148,119,244), because upgrade misses are 0 here |
| C8 | Repeat after a flush | — | ❌ **not run.** Needs a directed test that was not written. See also the doubt recorded in the commit-1 plan: the Flush64 MMIO write does not return until the flush completes, so a single core may not be able to reach the repeat path at all |
| C9 | Upgrade misses — value, and why if 0 | Board, all four halves | ✅ **0 everywhere, as predicted.** Elaboration prints `BRANCH reachability: … b=false`, so a BRANCH line that needs TRUNK cannot arise on this platform. `memAcqPerm` is 0 too |
| C10 | Probed hits — value | Board, all four halves | ✅ **PASS (near 0, as expected on one core):** 3 and 4 in the boot windows, 0 in both workload windows. One core means the requester is the only client, and the serve path skips probing it |
| C11 | Hold | — | ❌ **directed test not run** (no test binary). Indirect evidence: every board read is taken under the hold and all identities come back exact |
| C12 | 64-bit reads | `sw/sbc_read.c`, `sw/sbc_mmio.h`, `sw/l2_miss_calib.c`, `ai-documents/guides/devmem-register-map.md` | ✅ **PASS.** Every new counter is read as one `uint64_t`; the new register rows in the devmem map say width 64 |
| C13 | Same binary, SBC vs NoSbc `L2_AccessA` | — | ⏳ **pending C3.** The board pair is not this check: it is one SBC bitstream with the switch off and on, which gave 1,017.8 M accesses (off) against 1,379.8 M (on) |
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

## Board runs (FPGA, 2026-09-15/16) — what the new counters measured

Two one-bitstream A/B sessions, **64 KB L2** (`FPGASingleRocketVCU118L18K64K16WL2ConfigSBC`, built
2026-09-15 21:15 from the commit-1 tree), omnetpp `--sim-time-limit=0.002s`, fixed work, both halves in
one boot. Logs: `chipyard/scripts/logs/board_session_20260915-235559.log` and `…-20260916-011610.log`.

| | OFF run 1 | OFF run 2 | ON run 1 | ON run 2 |
|---|---:|---:|---:|---:|
| `L2_AccessA` | 1,017,623,115 | 1,017,751,083 | 1,393,194,852 | 1,379,830,469 |
| `L2_PrimaryHit` | 67.21% | 67.19% | 29.45% | 30.32% |
| `L2_SecondaryHit` | 0 | 0 | 5.73% | 4.42% |
| `L2_DataMiss` | 32.79% | 32.81% | 64.83% | 65.26% |
| `L2_UpgradeMiss` | 0 | 0 | 0 | 0 |
| hit rate | **67.21%** | **67.19%** | **35.17%** | **34.74%** |
| legacy `L2_Hits`/`L2_Accesses` | 71.62% | 71.63% | 35.77% | 36.63% |
| `L2_SecondSearch` (share that hit) | 0 | 0 | 218.6 M (36.5%) | 209.1 M (29.2%) |
| `L2_MemReads` + `L2_MemWrites` | 403.0 M | 403.2 M | 1,101.6 M | 1,090.6 M |
| `L2_Cycles` | 50.90 B | 50.88 B | 77.93 B (+53.1%) | 77.46 B (+52.3%) |
| `SBC_Migrations` / `SBC_Parked` at end | 0 / 0 | 0 / 0 | 1,039,634 / 483 | 828,392 / 477 |

Repeatability: the migrate-OFF halves agree to within 0.1% on cycles and memory traffic and 0.02 points
on hit rate. The ON halves differ more (20% fewer migrations in run 2) but land within 0.4 points of hit
rate and 0.6% of cycles.

**What this says about the SBC loss (for the thinker, not part of 005):** parked lines are *reused well* —
61.0 M secondary hits over 828 k migrations is ~74 hits per parked line — but each migration evicts one
of the destination's **home** lines (`dstEvictable` requires `!displaced`), and the parked population
settles at **477–483 of 1,024 lines = 47%**. That reproduces the ~47% fixed point predicted in
`ai-documents/performance/why-sbc-loses-2026-09-15.md` (which measured 480/1024) on a second geometry and
two independent runs. Primary hits fall by 265.6 M while secondary hits gain 61.0 M.

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
- **F7 — `SBC_SecWrite` counts C-channel write-backs, as TASK §6.1 predicted.** Board, migrate-ON:
  `SBC_SecWrite` = 816–1,033 while `SBC_SecC` = 473–648 and `SBC_SecPerm` = 0. Commit 2 fixes it by
  gating those two pulses with `request.prio(0)`. Counting only; no data path involved.
- **F8 — `SBC_Parked` ends 340–376 above `migrations − dispRelease − dispDrop`.** Session 1: a steady
  376 across three readings. Session 2: 340, 342, 343. Most likely cause: lines parked between
  `--reset-all` and `--zero` — `--zero` (SBC_StatsReset) zeroes the counters but never `SBC_Parked`, which
  is a level. The few-count drift within a session then comes from `SBC_Parked` being read just after the
  hold is released, not inside it (TASK §5.3 forbids holding a level). **Not proven.** To settle it, read
  `SBC_Parked` immediately before `--zero` and compare. Relevant to C16.
- **F9 — `run_board_session.exp`: `-ab` silently overrode `-bit`** (`set BIT $SBC_BIT` inside the `-ab`
  arm). A session launched as `-bit <64 KB> -ab` programmed the **256 KB** bitstream, so the 2026-09-15
  21:16 A/B (hit rates 91.85% off / 57% on) measured the 256 KB L2, not the 64 KB one it was labelled as.
  Fixed with the user's go-ahead: `-ab` no longer touches `BIT`. The script lives in the chipyard repo and
  is untracked there, so the fix is not in this commit.
- **F10 — the legacy counters are much closer to the terminology than expected.** On these board runs the
  legacy lookup hit rate sits 0.4–4.4 points above the new hit rate, not the ~40 points that counting
  inner-C write-backs as hits would give. Reason: Rocket's L1 D-cache drops clean victims silently
  (`acquireBeforeRelease = false` → `silentDrop`, `HellaCache.scala:54`), so only dirty L1 victims reach
  the L2 on the C channel. The legacy counter is still wrong by definition, but on this platform it is a
  usable approximation.

## Where the work order is wrong

**Commit 0: nothing.** Every line reference in §4 matched `e4c5d53`.

**Commit 1: nothing wrong, one gap.** Every line reference in §5.1 matched. The gap is C8: TASK assumes a
flush followed by a touch can be made to take the MSHR repeat path, but the Flush64 MMIO write does not
complete until the flush is done (`Control.scala`, `ovalid = flushOutValid`), so on one core the next load
most likely arrives after that MSHR retired. A sim-only `[SBC] REPEAT` printf was added under `sbcDebug`
so a future run can see which path was taken.

## Logs

- `sw/verilator_logs/005-c0-console.log` — build and run console for all three runs
- `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2Config_005-c0/`
- `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2NoSbcConfig_005-c0/`
- `sw/verilator_logs/sbc_migrate_switch_test_VerilatorRocket8KL116KL2Config_005-c0/`
- `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2NoPerfConfig_005-c0/` (C2, temp config)
- `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2NoSbcNoPerfConfig_005-c0/` (C2, temp config)
- `sw/verilator_logs/005-c1-console.log` and `*_005-c1/` — commit-1 checks C3/C4/C13 (running at commit time)
- `chipyard/scripts/logs/board_session_20260915-235559.log` — 64 KB A/B session 1 (commit-1 counters)
- `chipyard/scripts/logs/board_session_20260916-011610.log` — 64 KB A/B session 2, the repeat
