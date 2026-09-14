# Coder report 005 — counters that follow the cache terminology, all counters in one place, interval sampling

**Date:** _(fill in)_ · **Author:** coder session · **Status:** OPEN

> Template. Fill it in as you go, not at the end. A task that stops early still gets a report saying
> where it stopped and why.

## Summary

_One paragraph: what landed, what did not, the headline numbers._

## Commits

| # | What | Hash | Checks | Notes |
|---|---|---|---|---|
| 0 | move counters into `PerfCounters`, one flag, `L2_MemAcqPerm` | | C1–C2 | |
| 1 | outcome counters, `PopCount`, `L2_StatsHold`, `sbc_read`, tests | | C3–C13 | |
| 2 | three counter fixes | | C14–C16 | |
| 3 | expose `sat`, `IntervalSampler` | | C17–C20 | |

## Elaboration values

| | SBC config | NoSbc config |
|---|---|---|
| `mshrs` | | |
| `secondary` | | |
| in-progress bound `(mshrs − 2) + secondary` | | |
| `[SBC][elab] BRANCH reachability` line | | n/a |
| sampler words per snapshot / RAM bits | | n/a |

## Checks

| # | Check | Command / log path | Result |
|---|---|---|---|
| C1 | Move works — tests pass, moved counters still count | | |
| C2 | Flag off — no monitoring hardware, stress test 7/7 | | |
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

_Anything surprising. A failing check is a finding — never change RTL or a test to force it green
mid-run._

## Where the work order is wrong

_If TASK.md does not match the RTL, say what and where. Do not work around it silently._

## Logs

_Paths under `sw/verilator_logs/`._
