# ai-documents — index and status tracker

What is in this folder, grouped, plus one tracker of every task and how far it got.
Updated 2026-09-14. **Folders applied on 2026-09-14** — the layout is in section 10.

**Doc labels**
- 🟢 **Use now**
- 📘 **History** — was right when it was written
- ⚠️ **Out of date** — the design changed; a note at the top of the doc says what to read instead
- ⏸ **On hold**

**Three rules for keeping docs honest**
- A doc we still use (this README, CLAUDE.md, guides, the workplan): **edit the wrong line.**
- A history doc (day logs, weekly reports, phase docs, coder reports): **add a note at the top**; do not
  rewrite old lines.
- A doc that is wrong from top to bottom: **move it to `obsolete_files_do_not_refer/`.**

---

## 0. Where we are (2026-09-14)

- **Branches:** `sbc-paper-aligned` is the main branch. `sbc-sampling` is a testing branch and will be
  merged back into the main branch.
- **Phase:** measuring speed on the FPGA board and improving it. Building is finished. What building
  left behind is in section 1d, and explained in CLAUDE.md ("Build-phase leftovers").
- **Correct data:** yes. With the feature on, the cache still returns correct data (simulation tests
  and a real program).
- **Speed: SBC is slower today.** Same program, same amount of work, feature on vs off:
  - it takes **32% to 51% longer**
  - it goes to main memory **2.5 to 7 times more often**
  - each setup was measured once, not repeated yet ([details](performance/fpga-ab-baseline-2026-09-11.md), section 4)
- **Found on 2026-09-14** while checking the old pairing spec against the code:
  - nothing ever ends a pairing between two sets (L8)
  - a line served from its partner set counts as a *miss* for the heat counter (M11)
- **Doing now:** coder task 006 — make the counters 64-bit (**must do**) so long runs cannot overflow them.
- **Plan:** [workplan-parked-occupancy-2026-09-11.md](performance/workplan-parked-occupancy-2026-09-11.md) — find out
  why moved lines make the cache slower.
- **Moved to the last phase:** task 003's final tests (GATE 5) and the copy self-check.
- **On hold:** coder task 005 (count hits honestly).

---

## 1. Status tracker

**How to use it:** when a piece of work changes, update its row in the same change. Never delete a
row — mark it ✅ or ⛔.

Marks: ✅ done · 🟡 half done · 🔴 not started · ⏭ moved to the last phase · ⏸ on hold · ⛔ dropped ·
🐞 known bug, not fixed

### 1a. Phases

| # | Phase | Status | What is left | Doc |
|---|---|---|---|---|
| P0 | Watch the cache: count hits and misses for every set | ✅ | — | [SBC_integration_plan.md](design/SBC_integration_plan.md) |
| P1 | Building blocks for moving a line to another set | ✅ 2026-06-24 | — | [phase-1.md](tasks/phase-1.md) |
| P2 | Move a line out of a busy set instead of throwing it away | ✅ 2026-08-17 | L1, L2, L3 | [phase-2.md](tasks/phase-2.md) |
| P2.5 | Ask the CPU's cache first, so more lines can move | ✅ 2026-08-18 | — | [phase-2.5-probe-then-migrate.md](tasks/phase-2.5-probe-then-migrate.md) |
| P3 | Copy a moved line back home when it is needed | ⛔ replaced by P3R | — | [phase-3.md](tasks/phase-3.md) |
| P3R | Use a moved line right where it sits | 🟡 works, not fully tested | L4, L5, L7 | [coder/003](coder/003-serve-in-place/) |
| P4 | End old set pairings + stop moving lines nobody reuses | 🔴 not built | L6, L8 — *corrected 2026-09-14: this row wrongly said the clean-up was built* | [SBC_integration_plan.md](design/SBC_integration_plan.md) |
| M | Measure speed on the FPGA and improve it | 🟡 **now** | section 1c | [workplan-parked-occupancy-2026-09-11.md](performance/workplan-parked-occupancy-2026-09-11.md) |
| LAST | Final checks before any final claim | ⏭ later | L1, L4, L7 | this tracker |

### 1b. Coder tasks

| # | Task | Status | What is left | Last update |
|---|---|---|---|---|
| 000 | Stop the cache heating sets just by looking at them; pick cold sets better | ✅ | — | 2026-08-24 |
| 001 | First version of "find the moved line again" | ✅ | — | 2026-08-28 |
| 002 | Fix a one-cycle timing bug in set pairing | ✅ | — | 2026-08-28 |
| 003 | Use a moved line right where it sits | 🟡 | final tests (GATE 5) ⏭ moved to the last phase | 2026-09-14 |
| 004 | Count all cache hits and misses | ✅ | — | 2026-09-02 |
| 005 | Count hits honestly + sample counters over time | ⏸ | not started | 2026-09-09 |
| 006 | On/off switch + count trips to main memory | 🟡 | 64-bit counters: tests and commit (**must do**) | 2026-09-14 |

### 1c. Measure and improve (current phase)

| # | Item | Status | Notes |
|---|---|---|---|
| M1 | Same-work speed test on the board, feature on vs off | ✅ 2026-09-13 | 3 tests: 32–51% slower, 2.5–7× more trips to memory |
| M2 | Repeat each test 3 times | 🔴 | every number so far comes from one run |
| M3 | Watch moved lines pile up for one hour | 🔴 | workplan step 2 |
| M4 | Try two eviction fixes: stop over-protecting moved lines, and stop always evicting the first slot | 🔴 | workplan step 3 |
| M5 | Count hits honestly (task 005) | ⏸ | needed before quoting any hit rate |
| M6 | 64-bit counters | 🟡 **must do** | code and docs changed; tests, commit and a new FPGA image still owed |
| M7 | Is the cache's cycle counter the same clock as the CPU? | 🔴 | the on/off comparison is fair either way |
| M8 | The "moves attempted" counter does not add up | 🔴 | attempted is smaller than moved + aborted |
| M9 | Check that changed moved lines are always saved, never lost | 🔴 | cheap to check, bad if wrong |
| M10 | Try the CPU-cache setting that makes it report lines it drops | 🔴 | never tried; should cut wasted move attempts |
| M11 | Decide: should a line served from its partner count as a hit for heat? | 🔴 | today it counts as a miss — by default, nobody decided it. A set being helped still looks hot |

### 1d. Left over from the build phases — details in CLAUDE.md

| # | Item | From | Status | Notes |
|---|---|---|---|---|
| L1 | Check that each copied line landed correctly | P2 | ⏭ | removed because it got stuck; never rebuilt |
| L2 | Last-resort eviction of moved lines | P2 | 🟡 | only proven in a forced test |
| L3 | Rare timing-window bug | P2 | 🐞 | never seen in a run; do not build a fix in advance |
| L4 | Final tests of task 003 (GATE 5) | P3R | ⏭ | 6 of 12 cases unproven; the two-core cases never ran |
| L5 | Move lines that have been written to | P3R | 🔴 | only unchanged lines can move today |
| L6 | Stop moving lines from sets whose moved lines are never reused | P4 | 🔴 | not built |
| L7 | Test "serve a moved line that needs write permission" | P3R | ⏭ | needs two cores; never happened in any run |
| L8 | End a pairing once its moved lines are gone (teardown) | P4 | 🔴 | never built — every pairing lasts forever (found 2026-09-14) |

### 1e. Tidy-up — low priority, do during the improve phase

| # | Item | Status | Notes |
|---|---|---|---|
| H1 | Remove dead copy logic from the feature-off build | 🔴 | 240 LUT / 529 FF; makes the baseline fair |
| H2 | Delete an old commented-out debug block | 🔴 | |
| H3 | Fix 2 code comments that describe deleted hardware | 🔴 | |
| H4 | The "arm a set" register does nothing in auto mode | 🟡 | written down, not removed |
| H5 | Write the priority-order guide | ✅ 2026-09-14 | [priority-orders.md](guides/priority-orders.md) |
| H6 | Commit the chipyard-side changes (configs, board scripts) | 🔴 | |
| H7 | Merge `sbc-sampling` into `sbc-paper-aligned` | 🔴 | next, after the 64-bit commit |
| H8 | Move docs into folders, then fix links | ✅ 2026-09-14 | 24 files moved; one link to a long-deleted file marked as deleted |
| H9 | Remove debug and measurement hardware for the final area number | 🔴 | list in CLAUDE.md; only after the last measurement |

---

## 2. Guides — how we work — `guides/` (+ two files elsewhere)

- 🟢 [../CLAUDE.md](../CLAUDE.md) — rules for working on this code, current status, build-phase leftovers.
- 🟢 [coder/README.md](coder/README.md) — how the thinker and the coder hand work to each other.
- 🟢 [guides/fpga-linux-run.md](guides/fpga-linux-run.md) — build the FPGA image, boot Linux on the board, read the counters.
- 🟢 [guides/devmem-register-map.md](guides/devmem-register-map.md) — every counter and switch the cache exposes.
- 🟢 [guides/priority-orders.md](guides/priority-orders.md) — who goes first when two parts of the cache want the same thing.

## 3. Task documents — `tasks/` and `coder/`

### 3a. Phase documents and plans — `tasks/`

- 📘 [phase-1.md](tasks/phase-1.md) — building blocks for moving a line.
- 📘 [phase-2.md](tasks/phase-2.md) — move a line out of a busy set.
- 📘 [phase-2.5-probe-then-migrate.md](tasks/phase-2.5-probe-then-migrate.md) — ask the CPU's cache first, so more lines can move.
- ⚠️ [phase-3.md](tasks/phase-3.md) — the old "copy it back home" design (not built). Its last section lists the pairing rules that **are** built.
- 📘 [July18AfterBreakWorkplan.md](tasks/July18AfterBreakWorkplan.md) — the Phase-3 restart plan (18 Aug); every step is done or replaced.
- 🟢 [code-cleanup-suggestions.md](tasks/code-cleanup-suggestions.md) — tidy-up list, and what NOT to touch.

### 3b. Coder work orders — `coder/` — each has `TASK.md` + `REPORT.md`

- 📘 [000](coder/000-internalread/) · 📘 [001](coder/001-phase3-reuse-loop/) · 📘 [002](coder/002-partner-latch-fix/)
- 🟢 [003](coder/003-serve-in-place/) — the current design, with diagrams.
- 📘 [004](coder/004-l2-hitrate-counters/) · ⏸ [005](coder/005-hit-accounting-and-sampling/)
- 🟢 [006](coder/006-migrate-switch-and-memory-traffic/) — **active**.

## 4. Speed and improvement — `performance/`

- 🟢 [fpga-ab-baseline-2026-09-11.md](performance/fpga-ab-baseline-2026-09-11.md) — board numbers. **Section 4 is the main result.**
- 🟢 [workplan-parked-occupancy-2026-09-11.md](performance/workplan-parked-occupancy-2026-09-11.md) — **the current plan**: why SBC is slower, and three steps to find out.
- 📘 [omnetpp-differential-2026-09-10.md](performance/omnetpp-differential-2026-09-10.md) — first board test (fixed time, not fixed work). Its moved-line analysis still holds.
- 📘 [destination-side-blocker.md](performance/destination-side-blocker.md) — why most moves were refused at the target set (2026-08-24).
- ⚠️ [matmult-differential-2026-08-25.md](performance/matmult-differential-2026-08-25.md) — first simulation test. Numbers are old — do not quote.

## 5. Daily reports — `daily-summary/`

- 📘 [2026-08-18](daily-summary/2026-08-18.md) — why lines almost never moved, and the fix.
- 📘 [2026-08-21](daily-summary/2026-08-21.md) — two bugs fixed; first fully clean test run.
- 📘 [2026-08-28](daily-summary/2026-08-28.md) — two theories about bad data tested and ruled out.
- 📘 [2026-08-31](daily-summary/2026-08-31.md) — a moved line served correct data in a real program.
- 📘 [2026-09-01](daily-summary/2026-09-01.md) — what our tests check, in plain English.
- 📘 [2026-09-03](daily-summary/2026-09-03.md) — bug fixed, first fair on/off number, gap to the paper.
- 📘 [2026-09-08](daily-summary/2026-09-08.md) — feature-off build; what SBC costs in area and timing.
- 📘 [2026-09-10](daily-summary/2026-09-10.md) — first board test; why the hit rates cannot be trusted yet.
- 📘 [2026-09-11](daily-summary/2026-09-11.md) — task 006 built. **Latest day log** (none for 09-12 / 09-13 — those runs are in the 2026-09-14 weekly report).

## 6. Weekly reports — `weekly-report/`

- 📘 [2026-08-21.md](weekly-report/2026-08-21.md) (+ `.html`) — moving lines works end to end.
- 📘 [2026-08-31.md](weekly-report/2026-08-31.md) (+ `.html`) — a moved line served real data for the first time.
- 🟢 [2026-09-14.md](weekly-report/2026-09-14.md) (+ `.html`) — first fair test on the board: SBC is slower. Replaces the 09-08 weekly plan.

## 7. Design notes and diagrams — `design/`

- 📘 [SBC_integration_plan.md](design/SBC_integration_plan.md) — the original plan (phases 0–4) and the decisions we locked in.
- 📘 [SetBalanceUnit_design.md](design/SetBalanceUnit_design.md) — early design note for the set-tracking block.
- 📘 [SBC_implementation_challenges.md](design/SBC_implementation_challenges.md) — list of risks: bad data, deadlock, lost updates.
- ⚠️ [diagram.md](design/diagram.md) — part A (moving a line) is still right; part B (copy it back home) was never built.

## 8. Bugs — `bugs/`

- 🟢 [bug-fix-log.md](bugs/bug-fix-log.md) — every bug found, with its status, plus lessons for debugging.

## 9. Background — `background/` and retired files

- 📘 [rolan-et-al-SBC.md](background/rolan-et-al-SBC.md) + [178-rolan-1-2.pdf](background/178-rolan-1-2.pdf) — the SBC paper.
- ⛔ `obsolete_files_do_not_refer/` — 10 retired files. Do not use. Retired on 2026-09-14:
  `complexity-comparison.md`, and `spec-sbc-phase3-prereqs.md` (its still-built parts are now at the
  end of [tasks/phase-3.md](tasks/phase-3.md)).

---

## 10. Folder layout — applied 2026-09-14

```
rocket-chip-inclusive-cache/
├── CLAUDE.md                              (stays here)
└── ai-documents/
    ├── README.md                          this file
    ├── guides/
    │   ├── fpga-linux-run.md
    │   ├── devmem-register-map.md
    │   └── priority-orders.md
    ├── tasks/
    │   ├── phase-1.md
    │   ├── phase-2.md
    │   ├── phase-2.5-probe-then-migrate.md
    │   ├── phase-3.md
    │   ├── July18AfterBreakWorkplan.md
    │   └── code-cleanup-suggestions.md
    ├── coder/                             000 … 006, unchanged
    ├── performance/
    │   ├── fpga-ab-baseline-2026-09-11.md
    │   ├── workplan-parked-occupancy-2026-09-11.md
    │   ├── omnetpp-differential-2026-09-10.md
    │   ├── destination-side-blocker.md
    │   └── matmult-differential-2026-08-25.md
    ├── daily-summary/                     day logs, unchanged
    ├── weekly-report/
    │   ├── 2026-08-21.md / .html
    │   ├── 2026-08-31.md / .html
    │   └── 2026-09-14.md / .html
    ├── design/
    │   ├── SBC_integration_plan.md
    │   ├── SetBalanceUnit_design.md
    │   ├── SBC_implementation_challenges.md
    │   └── diagram.md
    ├── bugs/
    │   └── bug-fix-log.md
    ├── background/
    │   ├── rolan-et-al-SBC.md
    │   └── 178-rolan-1-2.pdf
    └── obsolete_files_do_not_refer/       10 retired files
```
