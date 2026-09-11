# Coder report 005 — honest A-channel hit accounting + interval sampling

**Date:** _(fill in)_ · **Author:** coder session · **Status:** OPEN

> Template. Fill in as you go, not at the end. A task that stops early still gets a report
> saying where it stopped and why.

## Summary

_One paragraph: what landed, what did not, and the headline number._

## What was built

| Part | File(s) | Landed? | Notes |
|---|---|---|---|
| 1 — A-channel counters | | | |
| 2 — SBU saturation vector output | | | |
| 3 — IntervalSampler | | | |
| 4 — parkCount assert fix (B) | | | |
| MMIO + `sbc_mmio.h` + `sbc_read.c` + CLAUDE.md table | | | |

## Verify

Answer each numbered check from TASK.md. State the command run and the observed result.

| # | Check | Result |
|---|---|---|
| 1 | Zero-hardware with flag off | |
| 2 | stress test 7/7, `L2_Accesses`/`L2_Hits` unchanged vs `sbc-baseline-not-verified-2026-09-09` | |
| 3 | Counter identity — residual reported | |
| 4 | Channel bias quantified (A-only rate vs 62.10% baseline) | |
| 5 | Repeat-after-flush directed test | |
| 6 | H1 vs H2 gap, explained by `L2_AcqUpgrade` | |
| 7 | Cross-config `L2_ReqA` sanity | |
| 8 | Sampler value matches `SBC_SetSat` | |
| 9 | `SMP_Dropped` non-zero under pressure | |

## Numbers

_The table this task exists to produce. Same workload, both configs._

| | `L2_ReqA` | H1 | `L2_LookupA` | H2 | `L2_AcqUpgrade` |
|---|---:|---:|---:|---:|---:|
| SBC off | | | | | |
| SBC on | | | | | |

## Findings (reported, not tuned)

_Anything surprising. Per the standing rule: a failing case is a finding to REPORT — never fix RTL
or tweak the test to force green mid-run._

- Did Part 4 finding B's assert actually fire once corrected? If so, finding C (parkCount/nParked
  drift) is real and needs its own fix.

## Anything the work order got wrong or left open

_The thinker's spec is not authoritative over the RTL. If something in TASK.md does not match what
is actually there, say so here rather than working around it silently._
