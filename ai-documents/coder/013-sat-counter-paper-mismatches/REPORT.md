# Coder report 013 — make the saturation counter follow the paper

**Started:** 2026-09-30 · **Branch:** `sbc-013-sat-counter` · **Status:** IN PROGRESS — C0 committed (`93eee79`), at stop point (b)

> Fill this in as you go, not at the end. A stage with an empty row is not done. Cite log paths — never
> paste logs. Anything unexpected goes under "Findings" and you stop and ask.

## ▶ RESUME HERE

- User gave go at stop point (b) 2026-09-30: "yes go ahead wth c1 and control bitstream build".
- Control bitstream build launched in tmux `sbc013` (chipyard commit `9f25ba67`, config
  `FPGASingleRocketVCU118L132K1024K8WL2ConfigSBCPLRU`, tag `control-013c0`). Build log:
  `chipyard/scripts/logs/013-build-control-013c0-20260930-211153.log`. Running in the background.
- C1, C2, C3 all done and committed (86a8866, 89d9c20, and C3 pending). Control bitstream DONE:
  sha256 `3abaca528fbd...` (see Bitstreams table), no MISMATCH, WNS positive, 54 DRC warnings (routine).
- C1 (86a8866), C2 (89d9c20), C3 (fed7227) all done, all four gates green each stage, base identity
  held throughout on NoSbc. Control bitstream DONE (sha256 `3abaca52...`).
- User gave go at stop point (c) 2026-09-30 ("what happened to previous build? ... continue to latest
  bitstream build"). Candidate build launched in tmux `sbc013` (commit `ec92d82` = C3 RTL `fed7227` +
  REPORT.md only, tag `candidate-013c3`). Log:
  `chipyard/scripts/logs/013-build-candidate-013c3-20260930-233256.log`. Running now.
- Candidate bitstream DONE: sha256 `1bcba361...`, WNS positive, no MISMATCH. Provenance diff vs
  control confirms only Directory/Scheduler/MSHR/SetBalanceUnit.scala differ - clean isolated A/B.
- At **stop point (d)**: waiting on the user's go before the board session (TASK §13).

## Findings

- **My awk verification scripts (C1-c/C3-c/C3-e) were vacuously passing, not an RTL bug (2026-09-30).**
  Chisel/Verilator's `%d` printf right-pads a field to the decimal width of the signal's *bit-width*
  (e.g. a 4-bit `satBits` value gets a 2-char field, so `sat=0->1` prints as `sat= 0-> 1`). Plain `awk`
  splits on that internal whitespace, so `"lookDemand=              335212"` becomes TWO fields
  (`"lookDemand="` with an empty value, and a bare `"335212"`), silently zeroing both sides of the
  comparison so `d=0-0=0` passes regardless of the real values. First caught on C3-e (`D-TAP` arithmetic),
  which showed 20,729/70,398 "BAD" lines — investigated instead of reported blind, traced to the same
  padding in the `sat=` field. Fixed by `sed -E 's/=[[:space:]]+/=/g; s/->[[:space:]]+/->/g'` before the
  `awk`, then re-ran C1-c, C3-c and C3-e: all three now show a *real* `bad 0`. TASK.md's own C1-c/C3-c
  commands have this same bug — future stages should pipe through the same `sed` fix first. The RTL was
  never wrong; only my check was. Lesson: when a numeric printf check on a Chisel signal gives a
  suspiciously round result (like `bad 0` on the first try for a brand-new signal), sanity-check the raw
  log line with `cat -A` before trusting it.
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

Commit: `89d9c20` — `013 C2: test for hot after the update, on the looked-up set (gaps 2+3, M17)`

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

Commit: `fed7227` — `013 C3: a demand second search updates the partner's counter (gap 4)`

| # | status | result |
|---|---|---|
| C3-a gate (incl. `case_teardown`) | ✅ | all four PASS, 0 asserts. `case_teardown`: PASS (`src=1 dst=2 pairing_gone=yes after 16 partner misses`) |
| C3-b NoSbc identity ×2 | ✅ | `IDENTICAL` ×2 (base vs c3) |
| C3-c `satFeedSec` = `secDemand` | ✅ (after fixing my check, see Findings) | `bad 0` on both stress (805 lines) and switch (43 lines) |
| C3-d `D-TAP` hit=1 / hit=0 lines | ✅ | hit=1: 2,427; hit=0: 67,971 — both > 0 |
| C3-e `D-TAP` arithmetic | ✅ (after fixing my check, see Findings) | `bad 0` across all 70,398 D-TAP lines |
| C3-f numbers | ✅ | see table |

| SBC stress run | c2 | c3 | Δ |
|---|---|---|---|
| migrations | 64,178 | 64,183 | +5 (~flat) |
| attempted | 64,310 | 64,315 | +5 (~flat) |
| aborted | 134 | 134 | 0 |
| secHits | 2,998 | 2,998 | 0 |
| secMiss | 67,962 | 67,967 | +5 |
| `HOT` lines | 16,801 | 24,247 | **+44%** |
| `ADVICE-MIG` lines | 108,293 | 108,296 | +3 (~flat) |
| `SEC-SERVE` lines | 2,998 | 2,998 | 0 |
| `SEC-MISS` lines | 67,966 | 67,971 | +5 |

Reading (no pass/fail on direction): `HOT` jumps +44% — with 67,971 second-search misses vs only 2,427
hits, the partner now heats far more than it cools, so more sets (mostly the partner/destination sets)
cross `T_hi`. `migrations`/`attempted` barely move: `hotNow` already excludes a set that is someone's
destination (`!(tEntry.valid && tEntry.sd)`), so heating a destination more does not make it eligible to
source a migration — it just makes the counter's *history* match the paper's Fig. 2, which was the point
of this stage. This is a faithfulness fix; no speed claim.

**Stop point (c):** the user's answer on the candidate build:

## Bitstreams (TASK §12)

chipyard commit (configs + build script): `9f25ba67` — "013: single-core 1 MB 8-way config with the
paper's 32 kB 8-way L1" (adds `SingleRocketVCU118L132K1024K8WL2ConfigSBCPLRU`,
`FPGASingleRocketVCU118L132K1024K8WL2ConfigSBCPLRU`, `build_1mb_8w_l132k.sh`).

| image | commit | tag | sha256 | WNS | TNS | WHS | DRC | provenance | build log |
|---|---|---|---|---|---|---|---|---|---|
| control | `dc96fec` (= C0 RTL `93eee79` + REPORT.md only) | `control-013c0` | `3abaca528fbd48703ba9c10a0eab53b9d7b299d390250a3f51827c6a99e5dbe7` | 0.234 / 0.010 / 0.143 (3 clock groups, all positive) | 0.000 / 0.000 / 0.000 | — | 54 (all Warning: DSP input/output pipelining, IO buffering — pre-existing, unrelated to SBC RTL) | ✅ no MISMATCH, all 27 .scala hashes match | `chipyard/scripts/logs/013-build-control-013c0-20260930-211153.log` |
| candidate | `ec92d82` (= C3 RTL `fed7227` + REPORT.md only) | `candidate-013c3` | `1bcba361c6fa26cbce017ceb914ef474086057a632f3949c712eecabfcbcca42` | 0.359 / 0.010 / 0.143 (all positive) | 0.000 / 0.000 / 0.000 | — | 54 (same routine profile as control) | ✅ no MISMATCH, all 27 .scala hashes match | `chipyard/scripts/logs/013-build-candidate-013c3-20260930-233256.log` |

Build took ~1h48m (21:11:53 → 22:59:47, control) and ~1h13m (23:32:56 → 00:45:34, candidate). Archived:
`fpga/bitstream_storage/FPGASingleRocketVCU118L132K1024K8WL2ConfigSBCPLRU-1MB-8way-L1-32K8W-control-013c0-2026-09-30.bit` /
`...-candidate-013c3-2026-09-30.bit`.

**Provenance diff, control vs candidate** — confirmed the ONLY `.scala` files that differ are exactly the
four C1–C3 touched: `Directory.scala`, `Scheduler.scala`, `MSHR.scala`, `SetBalanceUnit.scala`. Every
other source file (including `PerfCounters.scala`, `Control.scala`) is byte-identical between the two
images — a clean, isolated A/B.

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
