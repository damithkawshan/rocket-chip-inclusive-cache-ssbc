# Coder exchange

One task at a time. **The highest-numbered directory is the active one.**

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
| 005 | Honest A-channel hit accounting + interval sampling of the SBC counters | **parked** — filed 2026-09-09, never started. De-prioritised by 006: the headline metric moved from hit rate to main-memory traffic, so 005 is no longer blocking a result. Re-open after 006 |
| 006 | Migration on/off switch (`SBC_MigrateEnable`, default OFF) + main-memory traffic counters | **half done** — Parts A/B/D/E landed, C partial; switch + traffic counters + `L2_Cycles` all built and tested. First one-bitstream board A/B run 2026-09-11: switch-off half scored 90.19% vs 90.24% for a separately built NoSbc bitstream, so the one-bitstream method holds. **Update 2026-09-14: fixed-work A/B done on the board** — 3 pairs finished (2026-09-11 → 09-13) with smaller `--sim-time-limit` values: SBC +32% to +51% cycles, 2.5× to 7× memory accesses ([fpga-ab-baseline-2026-09-11.md](../performance/fpga-ab-baseline-2026-09-11.md) §4). REPORT updated (Amendment 3). **Still owed — must do (Amendment 4):** tests + commit of the 64-bit counters, then a bitstream rebuild |
