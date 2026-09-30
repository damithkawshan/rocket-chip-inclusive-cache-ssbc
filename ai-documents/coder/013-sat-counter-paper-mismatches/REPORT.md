# Coder report 013 — make the saturation counter follow the paper

**Started:** 2026-09-30 · **Branch:** `sbc-013-sat-counter` · **Status:** IN PROGRESS — Stage B running

> Fill this in as you go, not at the end. A stage with an empty row is not done. Cite log paths — never
> paste logs. Anything unexpected goes under "Findings" and you stop and ask.

## ▶ RESUME HERE

- Stage B SBC-config leg done (8/8 stress, switch PASS, both clean). NoSbc-config leg running now
  (`sw/verilator_logs/013-gate-base-nosbc.log`) after the operational mistake below. Waiting on it
  before touching any RTL.
- Next: once NoSbc leg lands, check §5.2 pass bar on all four run dirs, fill Stage B table, then start C0.

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

Commit: `<pending>` — `013 C0: sim-only totals that size the saturation-counter gaps`

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

Commit: 

| # | status | result |
|---|---|---|
| C1-a gate | | |
| C1-b NoSbc identity ×2 | | |
| C1-c `satFeed` = `lookDemand` | | |
| C1-d numbers | | see table |

| SBC stress run | c0 | c1 | Δ |
|---|---|---|---|
| migrations | | | |
| attempted | | | |
| aborted | | | |
| secHits | | | |
| secMiss | | | |
| l2Accesses | | | |
| l2Hits | | | |
| `HOT` lines | | | |

## C2 — test after the update, on the looked-up set (TASK §9)

Commit: 

| # | status | result |
|---|---|---|
| C2-a gate (incl. the new assert) | | |
| C2-b NoSbc identity ×2 | | |
| C2-c `HOT-NOW` lines | | |
| C2-d `HOT-NOW … sat=14` lines | | |
| C2-e numbers | | see table |
| leftover grep (`migrateQuery`, `migrateResp.migrate`, `isDemandA`, `hotOK`) | | |

| SBC stress run | c1 | c2 | Δ |
|---|---|---|---|
| migrations | | | |
| attempted | | | |
| aborted | | | |
| secHits | | | |
| secMiss | | | |
| `HOT` lines | | | |
| `ADVICE-MIG` lines | | | |

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
