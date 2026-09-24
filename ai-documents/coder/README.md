# Coder exchange

One task at a time. **The highest-numbered directory is the active one.** (010 is an exception —
a board investigation, not an RTL task; it carries `PROGRESS.md` beside its two files.)

```
coder/
├── README.md          ← this file (protocol; rarely changes)
└── NNN-short-name/
    ├── TASK.md        ← thinker → coder.  The work order. Coder does not edit.
    └── REPORT.md      ← coder → thinker.  Coder fills in the template. Thinker does not edit.
```

## Rules

1. **Two files per task. No more.** Analysis, measurements, and rationale go *inside* `REPORT.md`,
   not into new files. (`003` carries a third file, `diagram.md`, by explicit request — a visual
   companion to the work order. The rule still binds for *analysis*.) If something genuinely deserves its own document, `REPORT.md` says so and the
   thinker creates it under `ai-documents/` — not here.
2. **`TASK.md` is append-only after the coder starts.** If the work order changes mid-task, the
   thinker appends a dated `## Amendment` section rather than rewriting. The coder must be able to
   see what the instructions said when the work began.
3. **`REPORT.md` is the coder's only channel.** Write it as you go, not at the end. A task that stops
   early still gets a report saying where it stopped and why.
4. **Never delete a closed task directory.** It is the record of what was asked and what came back.
5. Big logs stay in `sw/verilator_logs/` (gitignored). `REPORT.md` cites paths, never pastes dumps.

## Task lifecycle

- **Open** — `TASK.md` exists, `REPORT.md` is the untouched template.
- **In progress** — coder is editing `REPORT.md`.
- **Closed** — `REPORT.md` has a filled-in verdict. The thinker opens the next numbered directory.

## Index

For every task across the project — phases, leftovers, hygiene — see the status tracker in
[../README.md](../README.md). Keep both in step.

| # | Task | Status |
|---|---|---|
| 000 | internalRead + DSS rotation (pre-convention; filed retroactively) | closed |
| 001 | Phase 3 — close the reuse loop (pin → search → serve → teardown) | **closed** (2026-08-28) — commits 1-3 landed; commit 4 handed to 002 |
| 002 | Fix the partner-set latch, then land commit 4 | **closed** (2026-08-28) — C1+C3 landed (`0f5a7ac`); both hypotheses killed by gates; instrumentation pass withdrawn |
| 003 | Serve in place, and let displaced lines be first-class (dirty + client-held) | **correctness done, not formally closed** (re-checked 2026-09-14) — GATE 4 green 2026-08-30 (7/7, 0 asserts); real-workload clean 2026-08-31 (Amendment 11); P1/P2/P5/P6/P7 closed. **GATE 5 moved to the last phase** (decided 2026-09-14). REPORT refreshed (Amendment 12, 2026-09-14) |
| 004 | Total L2 hit-rate counters (Accesses/Hits, always-on, independent of SBC) | **closed** (2026-09-02) — both counters landed; Amendment 1 removed `internalRead` probes from the denominator (42.77% was an artifact; corrected 58.49% vs 62.10%) |
| 005 | Counters that follow the cache terminology, all counters in one place, interval sampling | **open — ready to start.** TASK rewritten 2026-09-14 as one clean work order for a fresh coder chat (the 2026-09-09 version is in git at `61505c1`). Four commits: move every monitoring counter into `PerfCounters` behind one flag and rename `L2_MemUpgrades` → `L2_MemAcqPerm`; eight outcome counters ([cache-terminology.md](../guides/cache-terminology.md)), `PopCount` fan-in, `L2_StatsHold`; three counter fixes (`SBC_SecWrite`/`SBC_SecPerm` write-backs, `SBC_Declined`, parked-count assert); expose `sat` + interval sampler. Binding timing and sampling rules: TASK §2.2 |
| 006 | Migration on/off switch (`SBC_MigrateEnable`, default OFF) + main-memory traffic counters | **closed** (2026-09-14, `e4c5d53`) — Parts A/B/D/E landed, C partial; switch + traffic counters + `L2_Cycles` all built and tested. First one-bitstream board A/B run 2026-09-11: switch-off half scored 90.19% vs 90.24% for a separately built NoSbc bitstream, so the one-bitstream method holds. **Update 2026-09-14: fixed-work A/B done on the board** — 3 pairs finished (2026-09-11 → 09-13) with smaller `--sim-time-limit` values: SBC +32% to +51% cycles, 2.5× to 7× memory accesses ([fpga-ab-baseline-2026-09-11.md](../performance/fpga-ab-baseline-2026-09-11.md) §4). REPORT updated (Amendment 3). **64-bit counters landed 2026-09-14 (`06fcca8`)** — Amendment 4's tests and commit are done. **Amendment 5 (2026-09-14), corrected in §15.6:** REPORT fixes and log paths. **Amendment 6 (2026-09-14):** close-out clean-up — delete a sim check that false-alarms after a stats reset, stale comments, dead `sbc_read` code, a switch-test case that passes with nothing parked — then close; rebuild the bitstream once, after 005 |
| 007 | Put the paper's placement and eviction rules back (guests evictable, slot reuse, teardown, guest cap) | **C0–C3 landed** (`1486d4a`, `f885382`, `060e96d`, `744fabb`) + B7-1 fix (`c6a824c`). C4 (guest cap) staged, not applied — likely not needed (008 board: 9 parked at end). B7-2 (256 KB comb loop) open |
| 008 | PLRU replacement behind `L2_Replacement` (`0x490`), guests enter as most-recent; destination aborts counted by reason | **C1 + C2 landed** (`7494296`, `201ebae`), board-tested 2026-09-17: PLRU alone −3.56% cycles on the plain L2; SBC on top still a tie. C2's destination pick (option B) is a workaround, replaced by 009 |
| 009 | A migration evicts D's least-recent line whatever its state (probe + write-back), PLRU mode only, no new register or counter | **C1 + C2 landed** (`739bd2a`, 2026-09-21) — but **`REPORT.md` was never filled in**, so gates V2 and V4 have no recorded result. **The resulting build hangs the board** (task 010): 0 completions in 4 PLRU runs. Superseded by **011** |
| 010 | Hang bisection — find the newest bitstream on which omnetpp still completes | **closed** (2026-09-24). Not an RTL task. Found: candidates #5/#6/#7 are **one bitstream**, so the question is unanswerable as posed; the pass/fail boundary is between **008 C2** (completes, incl. PLRU) and **009 C2 `739bd2a`** (PLRU 0/4, random 3/4). REPORT §13 is an RTL review with six suspected defects — the input to 011 |
| 011 | Re-land 009's destination eviction safely (it hangs the board as built) | **open** (2026-09-24). **Fallback taken:** branch `sbc-009-redo`, `739bd2a` RTL reverted (`f385555`), verified byte-identical to `201ebae`. Task is now a staged **re-land** (§11), not a patch. Needs **D5** (re-land shape; (b) restructure recommended) and D3/D4. Six leads, one certain (F1: `evictW` is compile-time, so random mode runs the new path — contradicts 009's U2). Gate is 4 consecutive PLRU board completions, not a green sim |
