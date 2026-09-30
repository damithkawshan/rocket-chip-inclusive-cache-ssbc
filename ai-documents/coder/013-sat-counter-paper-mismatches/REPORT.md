# Coder report 013 — make the saturation counter follow the paper

**Started:** — · **Branch:** `sbc-013-sat-counter` · **Status:** NOT STARTED

> Fill this in as you go, not at the end. A stage with an empty row is not done. Cite log paths — never
> paste logs. Anything unexpected goes under "Findings" and you stop and ask.

## ▶ RESUME HERE

- (where you stopped, what is next, what you are waiting for — one line each)

## Pre-flight (TASK §4)

| item | value |
|---|---|
| start commit (`git log --oneline -1`) | |
| `git status --short` — any tracked change? | |
| `CLAUDE.md` size in bytes | |
| Vivado running at start? | |

## Plan (stop point a)

(your plan of at most 10 lines, and the user's answer)

## Stage B — baseline (TASK §6)

| run | result | cases | crash/asserts | run dir |
|---|---|---|---|---|
| stress / SBC | | | | |
| stress / NoSbc | | | | |
| switch / SBC | | | | |
| switch / NoSbc | | | | |

Gate log: 

MMIO block, SBC stress run:

| migrations | attempted | aborted | secHits | secMiss | l2Accesses | l2Hits |
|---|---|---|---|---|---|---|
| | | | | | | |

## C0 — sim-only sizing totals (TASK §7)

Commit: 

| # | status | result |
|---|---|---|
| C0-a gate | | |
| C0-b identity, 4 pairs | | |
| C0-c last `SAT-SUM` lines | | |
| C0-d `HOT` lines | | |

### Sizing table

| run | lookDemand | lookOther | gap 1 | repeatDemand | gap 5 | popWrongKey | gap 3 exposure | `HOT` lines |
|---|---|---|---|---|---|---|---|---|
| stress / SBC | | | | | | | | |
| switch / SBC | | | | | | | | |

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
