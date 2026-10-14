# Coder exchange

One task at a time. **The highest-numbered directory is the active one.** (010 is an exception —
a board investigation, not an RTL task; it carries `PROGRESS.md` beside its two files. **014 is an
exception too** — a host-only benchmark build with no FPGA or board step, so it ran in parallel with
015 rather than ahead of it.)

> **⭐ VERY IMPORTANT: [018](018-dst-dirty-ways/TASK.md) is CLOSED (2026-10-15; filed 2026-10-12, board 2026-10-13).** It adds
> a runtime destination setting so a migration may overwrite a dirty way that no L1 holds. Today 82–83% of attempts
> abort on the 512 KB image. RTL, simulation gate and a first board campaign (12 valid windows) are done and committed (2026-10-15, tag `sbc-018-dstmode-512k-n4096-b128-best-2026-10-15`).
> **User decision 2026-10-13: setting 1 is the one we use.** A 9-point LU sweep (b 64/128/256 × n 512/1024/2048, off
> vs setting 1) finished 18:44, 18 of 18 valid: b128 **−5.7 / −4.6 / −10.7%**, b64 ≈ 0, b256 ≈ −0.4%. A replicate of
> n2048 b128 (−9.7%) and an n4096 b128 pair (**−20.9%**, replicate **−21.1%**) finished 2026-10-14. Read its [HANDOFF](018-dst-dirty-ways/HANDOFF.md) first.

**State 2026-10-15:** task 018 is closed (board work done, committed, merged, tagged); no task or run is active. 017 (LU alone on the 512 KB image) was
stopped by the user on 2026-10-12 with 15 valid windows. 016 (bodytrack) has one sweep done and waits on a decision
about repeats; 015 is parked; 013's 1 MB board A/B is set aside. Config in use: 512 KB 8-way L2 + 32 KB 8-way L1,
one core.

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
| 005 | Counters that follow the cache terminology, all counters in one place, interval sampling | **in progress, stalled** (re-checked 2026-10-12) — **commits 0 and 1 landed** (`a1f4cbd`, `b3bd6e4`, 2026-09-15/16), board-verified; **commits 2 and 3 not started** (the three counter fixes and the interval sampler; tracker M8, M15). TASK rewritten 2026-09-14 as one clean work order for a fresh coder chat (the 2026-09-09 version is in git at `61505c1`). Four commits: move every monitoring counter into `PerfCounters` behind one flag and rename `L2_MemUpgrades` → `L2_MemAcqPerm`; eight outcome counters ([cache-terminology.md](../guides/cache-terminology.md)), `PopCount` fan-in, `L2_StatsHold`; three counter fixes (`SBC_SecWrite`/`SBC_SecPerm` write-backs, `SBC_Declined`, parked-count assert); expose `sat` + interval sampler. Binding timing and sampling rules: TASK §2.2 |
| 006 | Migration on/off switch (`SBC_MigrateEnable`, default OFF) + main-memory traffic counters | **closed** (2026-09-14, `e4c5d53`) — Parts A/B/D/E landed, C partial; switch + traffic counters + `L2_Cycles` all built and tested. First one-bitstream board A/B run 2026-09-11: switch-off half scored 90.19% vs 90.24% for a separately built NoSbc bitstream, so the one-bitstream method holds. **Update 2026-09-14: fixed-work A/B done on the board** — 3 pairs finished (2026-09-11 → 09-13) with smaller `--sim-time-limit` values: SBC +32% to +51% cycles, 2.5× to 7× memory accesses ([fpga-ab-baseline-2026-09-11.md](../performance/fpga-ab-baseline-2026-09-11.md) §4). REPORT updated (Amendment 3). **64-bit counters landed 2026-09-14 (`06fcca8`)** — Amendment 4's tests and commit are done. **Amendment 5 (2026-09-14), corrected in §15.6:** REPORT fixes and log paths. **Amendment 6 (2026-09-14):** close-out clean-up — delete a sim check that false-alarms after a stats reset, stale comments, dead `sbc_read` code, a switch-test case that passes with nothing parked — then close; rebuild the bitstream once, after 005 |
| 007 | Put the paper's placement and eviction rules back (guests evictable, slot reuse, teardown, guest cap) | **C0–C3 landed** (`1486d4a`, `f885382`, `060e96d`, `744fabb`) + B7-1 fix (`c6a824c`). C4 (guest cap) staged, not applied — likely not needed (008 board: 9 parked at end). B7-2 (256 KB comb loop) open |
| 008 | PLRU replacement behind `L2_Replacement` (`0x490`), guests enter as most-recent; destination aborts counted by reason | **C1 + C2 landed** (`7494296`, `201ebae`), board-tested 2026-09-17: PLRU alone −3.56% cycles on the plain L2; SBC on top still a tie. C2's destination pick (option B) is a workaround, replaced by 009 |
| 009 | A migration evicts D's least-recent line whatever its state (probe + write-back), PLRU mode only, no new register or counter | **C1 + C2 landed** (`739bd2a`, 2026-09-21) — but **`REPORT.md` was never filled in**, so gates V2 and V4 have no recorded result. **The resulting build hangs the board** (task 010): 0 completions in 4 PLRU runs. Superseded by **011** |
| 010 | Hang bisection — find the newest bitstream on which omnetpp still completes | **closed** (2026-09-24). Not an RTL task. Found: candidates #5/#6/#7 are **one bitstream**, so the question is unanswerable as posed; the pass/fail boundary is between **008 C2** (completes, incl. PLRU) and **009 C2 `739bd2a`** (PLRU 0/4, random 3/4). REPORT §13 is an RTL review with six suspected defects — the input to 011 |
| 011 | Re-land 009's destination eviction safely (it hangs the board as built) | **closed — carried out as 012** (re-checked 2026-10-12; its REPORT was never started). Was **open** (2026-09-24). **Fallback taken:** branch `sbc-009-redo`, `739bd2a` RTL reverted (`f385555`), verified byte-identical to `201ebae`. Task is now a staged **re-land** (§11), not a patch. Needs **D5** (re-land shape; (b) restructure recommended) and D3/D4. Six leads, one certain (F1: `evictW` is compile-time, so random mode runs the new path — contradicts 009's U2). Gate is 4 consecutive PLRU board completions, not a green sim |
| 012 | Re-land 009's destination eviction, staged and board-gated | **C1 + C2 landed** (`97d0162`), image `af11762b…3ec4`: board gate 4 of 4 completions, dirty destination aborts 48k → 0, **no performance change**. C3 (client-held destination) not built, by decision after the 2026-09-25 envelope result |
| 013 | Make the saturation counter follow the paper: demand accesses only (gap 1, M18), test after the update on the looked-up set (gaps 2+3, M17), the second search updates the partner (gap 4); gap 5 measured only. Then a control and a candidate single-core 1 MB image with the paper's 32 kB 8-way L1, A/B on the board | **RTL done; 1 MB board A/B set aside** (2026-10-12, user decision; next config is 512 KB 8-way + 32 kB 8-way L1). The C3 RTL is in every image since 2026-10-01 |
| 014 | Build SPLASH-2 `radix` and `fft` for the board (host-only, no FPGA) | **done** (2026-10-07; §5.4 `fft_noalias` not authorised, not built) — recipe verified before the task was written: both compile and link statically. `parsecmgmt` alone is NOT enough — the SPLASH-2 makefiles hard-assign `LDFLAGS = -lpthread -lm` and discard the bldconf's `-static`, which is why `inst/lu_ncb` is dynamic while `staged/lu_ncb` is static. Feeds task 015 §S6 |
| 015 | Multi-application SBC verification campaign: does the benefit come from interference, or from a low hit rate? | **parked at stop (c)** (2026-10-09) — the active task. Prerequisite fixes, R0 and S1 done; the 2026-10-05 headline (−12.58%) was mostly board age: **fresh-vs-fresh −1.99%, p 0.073** (F11). Side studies on request, 2026-10-08: LU single-core sweep (18 of 18 valid), LU pair (2 runs), LU pair search (8 of 8 windows; SBC recovers **6% / 2% / −3%** of the interference, F15); 2026-10-09: LU + FFT grid (24 of 24 windows), results in `performance/fresh-boot-experiments-2026-10-09.md`. **S2 the aggressor dose-response curve (the deciding measurement) not started.** S7 (dual core, per-app attribution) is the only route past the no-per-app-counter limit |
| 016 | bodytrack, SBC off vs on, then one parameter at a time (cameras, particles, layers, threads, serial model, BMP output); report in the shape of the LU sweep | **one sweep done** (2026-10-10 21:22; 36 of 36 windows valid, 18 settings, no repeats yet; the user decides the repeats). SBC never clearly saves; it **costs 3.7–4.1% memory accesses at 1600–4000 particles** (lost primary hits); small savings (1.2–1.9%) only around 400 particles; low hit rate (79–83%) loses 1.1–1.6%. [REPORT](016-bodytrack-sbc-sweep/REPORT.md). Task notes: independent of 015's parked stages. Host facts verified before the task was written (qemu-user on the real binary): `frames` is not a usable knob (one frame staged), `particles` below 5 hangs, `model` 1/3 are silent no-ops, and `main()` always exits 0, so the checker reads the program's output and the `poses.txt` md5 (an independent host oracle). ~35 fresh-boot windows (about 18 h) plus one calibration boot. Stops at plan, host tests, calibration, end of Phase 1, end of Phase 2 |
| 017 | LU alone, single core, SBC off vs on, on the 512 KB 8-way L2 + 32 KB 8-way L1 image (`37923e6b…`): the 256 KB sweep's 9 points, one fresh boot per window, calibration boot first | **open, not started** (filed 2026-10-12). Campaign folder `clean-runs/lu-sweep-single-core-512k-20261012/` prepared (tools copied; only `BIT1`, `BIT1_SHA`, `SWEEP` changed). 18 windows (~8.5 h) + calibration. Stops at plan, calibration + predictions, end of chain, report |
| 018 | ⭐ **VERY IMPORTANT.** Destination setting: a migration may overwrite a dirty way that no L1 holds. Runtime `SBC_DstMode` (0x4B0: 0 = today, 1 = clean then dirty, 2 = least-recent of either), counter `SBC_DstRescue` (0x4B8). Held ways stay an abort: no destination probe | **closed** (2026-10-15). Why: 82–83% of attempts abort on a held least-recent way on the 512 KB image (017); task 012's write-back already handles a dirty way with no L1 bit, but only the least-recent one was ever offered. Done: RTL + software + gate (I1–I4 pass, I5 fails for setting 2 = F4; uncommitted), image `502649e2…`, first campaign stopped by the user at 12 valid windows (n512 b128, n1024 b64, n384 b128; off, settings 0/1/2): memory accesses vs off n512 b128 −1.97 / **−4.97** / −3.46%, n1024 b64 −0.37 / −1.32 / −1.35%, n384 b128 +0.9 / +0.5 / +0.9%. **Setting 1 chosen (user).** 9-point sweep (18 of 18 valid): b128 −5.7 / −4.6 / **−10.7%** at n 512 / 1024 / 2048, b64 +0.0 to −1.4%, b256 −0.3 to −0.5%. Replicate n2048 b128 −9.7%; **n4096 b128 −20.9%, replicate −21.1%**. Committed 2026-10-15 (RTL `494ab5e`, merged into `sbc-013-sat-counter`, tag `sbc-018-dstmode-512k-n4096-b128-best-2026-10-15`). Open: corrected calib sweep, LU data check. [REPORT](018-dst-dirty-ways/REPORT.md) |
