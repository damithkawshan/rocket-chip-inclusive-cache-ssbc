# Coder report 013 — make the saturation counter follow the paper

**Started:** 2026-09-30 · **Branch:** `sbc-013-sat-counter` · **Status:** IN PROGRESS — C0 committed (`93eee79`), at stop point (b)

> Fill this in as you go, not at the end. A stage with an empty row is not done. Cite log paths — never
> paste logs. Anything unexpected goes under "Findings" and you stop and ask.

## ▶ RESUME HERE

- User gave go at stop point (b) 2026-09-30: "yes go ahead wth c1 and control bitstream build".
- Control bitstream build launched in tmux `sbc013` (chipyard commit `9f25ba67`, config
  `FPGASingleRocketVCU118L132K1024K8WL2ConfigSBCPLRU`, tag `control-013c0`). Build log:
  `chipyard/scripts/logs/013-build-control-013c0-20260930-211153.log`. Running in the background.
- Now starting C1 (gap 1 fix) in parallel while the build runs.

## Findings

- **Operational mistake, not an RTL/design finding (2026-09-30).** Launched the Stage B gate with a
  manual `nohup ... &; disown`, then later tried to stop it by killing the wrapper PID. That killed only
  `run_013_gate.sh` (the loop driver); its already-forked `run_sbc.sh` child for the first config
  (`VerilatorRocket8KL116KL2Config`) was reparented to init and ran to completion on its own, but the
  loop never advanced to the second config (`VerilatorRocket8KL116KL2NoSbcConfig`) because its driver was
  dead. Result: only 2 of 4 expected run dirs were produced. Recovered by running the missed NoSbc leg
  directly (`SBC_CONFIGS=VerilatorRocket8KL116KL2NoSbcConfig ... SBC_CLEAN=0`, label kept `013-base`) so
  the run dirs land where the gate expects them. Lesson: track long sim runs via the tool's own
  `run_in_background`, not manual `nohup`/`disown` plus a later `kill`.

## Pre-flight (TASK §4)

| item | value |
|---|---|
| start commit (`git log --oneline -1`) | `cd7924b` docs: file coder task 013 (on `sbc-009-redo`, parent of this branch) |
| `git status --short` — any tracked change? | none — only pre-existing untracked files unrelated to this task (daily-summary/2026-09-30.md, calib_sweep.csv, PNGs, scratch/, sw/vivado.*, tmp.md) |
| `CLAUDE.md` size in bytes | 714 lines (matches TASK.md's "restored in 0c9265e" expectation) |
| Vivado running at start? | no build (`vivado -nojournal`) running; only `hw_server`/`cs_server` (board programming daemons) live |

## Plan (stop point a)

Plan presented and the doc-tracker `M` lines (CLAUDE.md, ai-documents/README.md, ai-documents/coder/README.md)
explained as the task-013-filing commit, not stray edits. User's answer 2026-09-30: "commit them to indicate
the start of the task" — committed as `cd7924b` on `sbc-009-redo`, then branched `sbc-013-sat-counter` from it.
Taken as go-ahead to proceed with preflight + Stage B + C0 (stop point (b) still applies before C1).

## Stage B — baseline (TASK §6)

| run | result | cases | crash/asserts | run dir |
|---|---|---|---|---|
| stress / SBC | PASS (26,422,506 cyc) | 8/8 | NONE | `migration_stress_test_VerilatorRocket8KL116KL2Config_013-base` |
| stress / NoSbc | PASS (26,134,936 cyc) | 7/7 | NONE | `migration_stress_test_VerilatorRocket8KL116KL2NoSbcConfig_013-base` |
| switch / SBC | PASS (1,409,766 cyc) | 3/3 | NONE | `sbc_migrate_switch_test_VerilatorRocket8KL116KL2Config_013-base` |
| switch / NoSbc | PASS (505,366 cyc) | — (no numbered cases in this test) | NONE | `sbc_migrate_switch_test_VerilatorRocket8KL116KL2NoSbcConfig_013-base` |

All four pass the §5.2 bar. `a318711`'s `SBC_AtAssoc` decode change (the concern flagged in §6) did not
break `case_teardown` — it ran and PASSED on the SBC config.

Gate logs: `sw/verilator_logs/013-gate-base.log` (SBC-config leg) +
`sw/verilator_logs/013-gate-base-nosbc.log` (NoSbc-config leg, run separately — see Findings).

MMIO block, SBC stress run:

| migrations | attempted | aborted | secHits | secMiss | l2Accesses | l2Hits |
|---|---|---|---|---|---|---|
| 46551 | 46598 | 196 | 3312 | 67744 | 434928 | 302089 |

Cross-check block: all `ok` except `secMiss` (`67744` vs `SEC-MISS 67747`, `~ delta +3`, expected —
printf tail after the final MMIO read). Copy/commit block: `ok`.

## C0 — sim-only sizing totals (TASK §7)

Commit: `93eee79` — `013 C0: sim-only totals that size the saturation-counter gaps`

| # | status | result |
|---|---|---|
| C0-a gate | ✅ | all four run dirs PASS, 0 asserts (stress 8/8 SBC, 7/7 NoSbc; switch PASS both) |
| C0-b identity, 4 pairs | ✅ | `IDENTICAL` ×4 (base vs c0), including cycle counts — the debug-only edit changed no behavior |
| C0-c last `SAT-SUM` lines | ✅ | see sizing table below (raw counters) |
| C0-d `HOT` lines | ✅ | stress 4017, switch 8 |

### Sizing table

| run | lookDemand | lookOther | gap 1 = lookOther/(lookDemand+lookOther) | repeatDemand | gap 5 = repeatDemand/(lookDemand+repeatDemand) | popWrongKey | gap 3 exposure = popWrongKey/lookDemand | `HOT` lines |
|---|---|---|---|---|---|---|---|---|
| stress / SBC | 335,212 | 101,160 | **23.18%** | 2 | 0.0006% | 6,280 | **1.87%** | 4,017 |
| switch / SBC | 9,275 | 326 | 3.40% | 1 | 0.0108% | 10 | 0.11% | 8 |

Reading: on the stress test, nearly a quarter of what moves the saturation counter today is not a demand
access (gap 1) — in the same ballpark as the board's 21.4% on omnetpp (`98aedafa…929e`, 2026-09-26).
`popWrongKey` (gap 3 exposure) confirms M17 is live in sim, not just a theoretical path: ~1.9% of demand
primary lookups on the stress test are queue-pops whose advice would be keyed to the wrong set today.
Gap 5 (repeats) is negligible on both — expected, these are synthetic stress tests, not a real workload
with tight reuse. `HOT` (4,017 / 8) is the paper's own trigger point count — every stage after C1 is
expected to move it, no pass/fail on direction.

Board context for gap 1: omnetpp on image `98aedafa…929e` (2026-09-26) — at least 21.4% of counter events
were not accesses.

**Stop point (b):** the user's answer on C1 and on the control build:

## C1 — only demand accesses move the counter (TASK §8)

Commit: `86a8866` — `013 C1: only demand accesses move the saturation counter (gap 1, M18)`

| # | status | result |
|---|---|---|
| C1-a gate | ✅ | all four PASS, 0 asserts (stress 8/8 SBC, 7/7 NoSbc; switch PASS both) |
| C1-b NoSbc identity ×2 | ✅ | `IDENTICAL` ×2 (base vs c1), both NoSbc runs — cycle-for-cycle unchanged, as expected (no SBC hardware built there) |
| C1-c `satFeed` = `lookDemand` | ✅ | `bad 0` on both stress (804 SAT-SUM lines) and switch (43 lines) SBC runs |
| C1-d numbers | ✅ | see table. Stress-test cycle count moved 26,422,506 → 26,334,166 (SBC config only) — expected, the migration schedule itself changed |

| SBC stress run | c0 | c1 | Δ |
|---|---|---|---|
| migrations | 46551 | 54160 | +16.3% |
| attempted | 46598 | 55177 | +18.4% |
| aborted | 196 | 1026 | +5.2x |
| secHits | 3312 | 3191 | -3.7% |
| secMiss | 67744 | 67610 | -0.2% |
| l2Accesses | 434928 | 434994 | +0.02% |
| l2Hits | 302089 | 303523 | +0.5% |
| `HOT` lines | 4017 | 16843 | +4.2x |

Reading (no pass/fail on direction, per TASK §8.5): with write-backs and X-flushes no longer falsely
cooling sets, sets reach `T_hi` far more often (`HOT` 4017→16843) — matching the C0 sizing table's 23.18%
gap-1 exposure. More hot triggers → more migration attempts (+18.4%) and, since destinations are the same
finite pool, more aborts (+5.2x). Net hit rate ticked up slightly (l2Hits +0.5%) — consistent with the
counter now reflecting real demand pressure instead of being diluted by non-access traffic.

## C2 — test after the update, on the looked-up set (TASK §9)

Commit: `<pending>` — `013 C2: test for hot after the update, on the looked-up set (gaps 2+3, M17)`

| # | status | result |
|---|---|---|
| C2-a gate (incl. the new assert) | ✅ | all four PASS, 0 asserts. The mis-keying assert never fired |
| C2-b NoSbc identity ×2 | ✅ | `IDENTICAL` ×2 (base vs c2) |
| C2-c `HOT-NOW` lines | ✅ | stress 108,293; switch 1,493 — both > 0 |
| C2-d `HOT-NOW … sat=14` lines | ✅ | 16,735 > 0 on the stress run — advice given exactly on the miss that takes a set to `T_hi`, a path that was impossible before C2 |
| C2-e numbers | ✅ | see table |
| leftover grep (`migrateQuery`, `migrateResp.migrate`, `isDemandA`, `hotOK`) | ✅ | prints nothing |

| SBC stress run | c1 | c2 | Δ |
|---|---|---|---|
| migrations | 54,160 | 64,178 | +18.5% |
| attempted | 55,177 | 64,310 | +16.6% |
| aborted | 1,026 | 134 | **-87%** |
| secHits | 3,191 | 2,998 | -6.0% |
| secMiss | 67,610 | 67,962 | +0.5% |
| `HOT` lines | 16,843 | 16,801 | ~flat (expected — `HOT` observes `crossedHot`, unchanged by C2) |
| `ADVICE-MIG` lines | 91,605 | 108,293 | +18.2% |

Reading (no pass/fail on direction): the big number here is **aborted, -87%**. Before C2, advice could be
stale (tested at arrival, not after this access's own update) or mis-keyed to the wrong set (M17,
popWrongKey ~1.87% of lookups in C0's sizing). Both defects could advise a migration that then found no
real basis and aborted. With advice now answered after the update, on the looked-up set, migrations rose
+18.5% while aborts fell 8x — advice quality improved, not just advice volume.

## C3 — the second search updates the partner (TASK §10)

Commit: 

| # | status | result |
|---|---|---|
| C3-a gate (incl. `case_teardown`) | | |
| C3-b NoSbc identity ×2 | | |
| C3-c `satFeedSec` = `secDemand` | | |
| C3-d `D-TAP` hit=1 / hit=0 lines | | |
| C3-e `D-TAP` arithmetic | | |
| C3-f numbers | | see table |

| SBC stress run | c2 | c3 | Δ |
|---|---|---|---|
| migrations | | | |
| attempted | | | |
| aborted | | | |
| secHits | | | |
| secMiss | | | |
| `HOT` lines | | | |
| `ADVICE-MIG` lines | | | |
| `SEC-SERVE` lines | | | |
| `SEC-MISS` lines | | | |

**Stop point (c):** the user's answer on the candidate build:

## Bitstreams (TASK §12)

chipyard commit (configs + build script): 

| image | commit | tag | sha256 | WNS | TNS | WHS | DRC | provenance | build log |
|---|---|---|---|---|---|---|---|---|---|
| control | | `control-013c0` | | | | | | | |
| candidate | | `candidate-013c3` | | | | | | | |

**Stop point (d):** the user's answer on the board session:

## Board (TASK §13)

### Predictions — written before the first run

- P1:
- P2:

### omnetpp (`--sim-time-limit=0.1s`, fixed work)

| image | half | L2_Cycles | memReads | memWrites | L2_Accesses | L2_AccessA | hit rate | attempted | migrations | dst aborts dirty / held / both | parked at end | secHits | second searches | rc | log |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| control | OFF | | | | | | | | | | | | | | |
| control | ON | | | | | | | | | | | | | | |
| candidate | OFF | | | | | | | | | | | | | | |
| candidate | ON | | | | | | | | | | | | | | |

### calib sweep

| image | transcript | points | cliff per row (P = 0, 2, 4, 6, 8) | median \|Δcycles difference\| vs control |
|---|---|---|---|---|
| control | | | | — |
| candidate | | | | |

### Verdict

- P1:
- P2:

## Findings

(anything unexpected — reported, not fixed)
