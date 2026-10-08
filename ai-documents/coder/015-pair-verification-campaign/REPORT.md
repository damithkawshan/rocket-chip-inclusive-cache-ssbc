# Coder report 015 — multi-application SBC verification campaign

**Started:** 2026-10-07 · **Status:** PARKED AT STOP POINT (c) — R0, S1, fresh ON and fresh OFF done; headline re-measured fresh-vs-fresh at −1.99% (F11); S2 not started. Side studies on request, 2026-10-08: LU single-core sweep (18 of 18 valid), LU pair (2 runs), LU pair search (8 of 8 windows valid, F15)

> Fill this in as you go, not at the end. A stage with an empty row is not done. Cite log paths —
> never paste logs. Anything unexpected goes under "Findings" and you stop and ask.

## ▶ RESUME HERE

Task 014 is done first, as the user asked (`staged/radix` and `staged/fft` are static; see
[014 REPORT](../014-splash2-radix-fft-build/REPORT.md)). Then §4 P1 and P2 were written in
`scripts/ssbc_scripts/run_pair_session.exp` (89 changed lines, 9 hunks, on top of the user's own
uncommitted edits) and **T1–T8 all pass**. **Nothing is committed** (user: "do not add any commits
till we finalize anything"). The user said go and **R0 ran 2026-10-07** (one boot, `BIT1`, OFF,
`-repeat 3`, tmux `sbc015-r0`): all 9 rows OK, every counter identity exact, every band passes on the
mean — except pair run 1, which the user then **excluded from the OFF pool (F9)**. A chained session ran
S1 (3 more OFF repeats, same boot), one ON window on that same boot, and two fresh-boot ON windows; all
passed their stage checks (tmux `sbc015-chain`, status file in the scratchpad). **Result:** cold-boot ON
(n=4 across four boots) reads **11,910,456** against OFF n=5 **13,947,205** = **−14.60%**, reads −14.4%,
writes −15.1%, Welch p = 8.3e-6. **But** every ON window is the first window after a fresh boot and no OFF
window was, and OFF memAcc drifts with board age (F8); the one ON window on an aged boot read **+1.03%**.
**Then the matched control ran** (user-approved, 3 fresh boots, pair OFF as the first and only window,
command identical to the fresh ON runs except `-phases off`, tmux `sbc015-freshoff`). **Fresh-vs-fresh, the
pair reads OFF 12,151,968 (n=3) vs ON 11,910,456 (n=4): −1.99%, reads −2.02%, writes −1.92%, Welch p = 0.073.**
So **~90% of the −12.58% / −14.60% headline was board age, not SBC** (F11). LU alone, fresh-vs-fresh, is
**−14.24%** (ON n=1). Parked at **stop (c)**; S2 not started. Read F8 and F11 first.

**2026-10-08 (user request, outside TASK §7): a single-core LU n/b sweep, fresh-vs-fresh, one run per arm**
(9 points × OFF/ON = 18 boots; tmux `sbc015-lusweep`; section "LU single-core sweep" below). **DONE 2026-10-08 10:25, 18 of 18
stages valid, nothing given up.** SBC cuts memory traffic (reads+writes) by **3.2–4.7% at every n (b=128)**, by
**12.9% at n=512 b=64**, 1.3% at b=256, and by nothing (±0.4%) at b=16 and b=32, where the hit rate is already 93–94%.
The old one-boot sweep got the small points badly wrong (+4% to +61%) and b=64 as −0.6%.
Same day the logs of the clean (fresh-boot) runs were moved to `chipyard/scripts/logs/clean-runs/`
(index `clean-runs/README.md`, old→new map `clean-runs/MOVED.tsv`); the paths in this REPORT are the new ones.

**2026-10-08 (user request, outside TASK §7): LU alone fits, a second LU thrashes next to it; can SBC win some back? Single core, fresh boot per window, one run per arm.**
LU b=32 (hits 94.4% alone) and LU b=128 (47.8% alone) run together on the one core, both to completion (section "LU pair on one core" below). **DONE 15:55, 2 of 2 stages valid.**
Together the two make +8.1% more memory accesses than alone (interference +1,112,710). SBC cuts the pair's accesses by 4.65% (693,335); about 449 K of that it would also give the two programs alone,
so the part recovered from the interference is **22% of it** in run 1 (band fixed beforehand: 10-25% = "weak, repeat before believing"; 17% mean of two runs, F14). Board cycles move only 0.19%. n = 1 per arm.

**2026-10-08 evening: the repeat (run 2) and a model-guided search for the worst pair.**
- Run 2 of the same pair (fresh boots, 17:06-18:03, 2 of 2 valid, folder `r2/`): SBC-off pair 14.923 M vs run 1 14.903 M (0.14% apart); SBC-on 14.343 M vs 14.209 M (0.93% apart).
- Interference: +1.12 M (SBC off) to +0.93 M (SBC on). **SBC recovers 0.19 M = 17% of it** (22% in run 1, 12% in run 2). Still the weak band; not settled (F14).
- The 0.13 M swing of the SBC-on window between runs is as large as the effect, so this pair cannot settle it. A pair with several times the interference can: the model (`lu-pair-search-20261008/model/lusim.c`) predicts b64+b64 is the worst, +3 to +8 M accesses.
- The test of that prediction is done (tmux `sbc015-lupair`, 2026-10-08 18:04-22:14, 8 of 8 windows valid): five SBC-off windows, all checks P1-P5 pass, order as predicted (b64+b64 worst, +8.50 M = +72%). Section "LU pair search" below; plan and fixed predictions in `lu-pair-search-20261008/PLAN.md`.
- SBC-on for three pairs (F15): recovered **6%** (b64+b64), **2%** (b32+b32), **-3%** (b64+b256). All below the 10% band fixed before run 1. SBC's gain on a pair is what each program gains alone; nothing extra from the sharing. b64 pairs carry about +/-1 M page-colour noise, so b64+b64 is not conclusive.

## Findings

**F1 — T4/T5 cannot be run with `-list` as TASK §4.1 writes them.** `-list` exits at the end of the
plan section (`run_pair_session.exp`, the `if {$DO_LIST}` block), **before** SAFETY LOCK 1.2 (inside
`if {"on" in $PHASES}`, further down). So `-pair lu,lu -phases on -list` prints the plan and exits 0 —
it is never refused, and T5 "passes" trivially. I did **not** change the harness. Instead I ran the
real, edited harness text as a **board-free copy** (scratchpad `locktest/lockcopy.exp`): three
mechanical differences from the real file — absolute `LIB` path, logs redirected to the scratchpad,
and `exit 0` inserted straight after the lock block. Anything that passes the lock prints
`LOCK-PASSED` and exits before any serial access. Results are under T4/T5 below. **Worth a decision
later (not done):** moving the `-list` exit below the lock would let `-list` preview a refusal.

**F2 — the report takes its labels from the UNFILTERED plan, not `$PLANNED` as TASK §4/P1 says.**
`-bench` replaces `$PLANNED` with the filtered list, and every ON session uses `-bench` with one
label. Deriving the solo labels from `$PLANNED` would leave an empty list there and crash
`lassign` / `find_row`. So I added `FULLPLAN` (the plan before `-bench`), and `SOLO_A`, `SOLO_B`,
`PAIR_LABEL` are derived from it. Same intent as TASK (no hand-built label strings), no crash on the
ON path. Verified by test R3 below.

**F3 — 014 changes two things in this task's S6** (full detail in the 014 REPORT, F1 and F2):
(a) `fft` allocates **three** arrays, so `-fft-m 16` is **3 MB = 12x L2**, not "2 MB / 8x" as TASK §7 S6
and the P2 comment in TASK §4 say; the nearest-2x choice is `-fft-m 14` (520 KB per phase, 780 KB overall).
(b) `-fft-l 4` and `-fft-l 6` are **the same program at `-fft-m 12` and below**; they differ only for
`-m >= 14`. The P2 default `FFT_M 16` follows TASK as written. I have **not** edited TASK.md.

**F4 — the new `radix 1 fft 1` entries in the core-count dict are constants.** Both commands hard-wire
`-p1`, so those two checks can never fail; they document the invariant, they do not enforce it. The
check **still silently skips** `blackscholes` and `nop` (no entry), same defect as before, left as is
per rule 10. A one-line "no entry, check skipped" note would make it visible — not done, your call.

**F6 — the first pair repeat is the lowest in both sessions.** 10-05 OFF: 12.76 M / 14.11 M / 14.08 M.
R0 (2026-10-07): 12.49 M / 13.95 M / 13.63 M. Run 1 is 8–10% below the other two on 10-05 and 7–10%
below on R0. Two sessions, n=3 each, so this is a pattern and not yet a result; I have no mechanism
and have not looked for one. It matters for S1: if the first repeat is systematically low, a mean over
`-repeat 6` mixes it in with weight 1/6, and "do not pool across boots" gets harder to judge. S1 should
report the per-repeat values, not only the mean.

**F7 — preview only, S1 owns it: the headline moves with the OFF sample.** The 10-05 ON pair mean
(11,933,076, n=2) against R0's OFF pair mean (13,357,254) gives **−10.66%**, against −12.58% with the 10-05
OFF mean. Not a finding about SBC; it shows how much the n=3 OFF denominator moves the number. Cross-boot
and cross-day, so not a valid test on its own.

**F8 — OFF memAcc drifts upward within one boot, and the solos drift far more than the pair.**
Same boot, same work (accessA within 0.5% of R0), S1's OFF batch (`pair_runs_20261007-181011.csv`, run
~25 min after R0's) against R0's:

| label | R0 mean memAcc | S1 mean memAcc | change | hit % R0 → S1 |
|---|---:|---:|---:|---|
| solo-lu | 6,375,555 | 7,491,159 | **+17.5%** | 74.76 → 70.80 |
| solo-streamcluster | 1,475,484 | 2,087,762 | **+41.5%** | 91.63 → 87.71 |
| pair | 13,357,254 | 14,052,379 | **+5.2%** | 73.68 → 72.29 |

Pair OFF, all six in run order: 12.49 / 13.95 / 13.63 / **14.13 / 14.03 / 14.00 M**. The 10-05 session read
12.76 / 14.11 / 14.08 M. So across two boots and two days the pair **ramps from a low first reading to a
plateau near 14.0–14.1 M**. That is what F6 saw, and it is not "first repeat" specifically.
**Consequences (observations, not yet tested):** (a) the OFF baseline depends on how long the board has been
up, so "OFF shares one boot" assumes something the data contradicts; (b) the interference denominator moves:
sum of solos 7.85 M (R0) vs 9.58 M (S1), so I_off is 5.51 M (+70%) vs 4.47 M (+47%) on the same boot;
(c) the 10-05 cold-boot ON pair (11.93 M, first window after boot) sits −15% under the plateau but only about
−5% under the low first readings (12.49 / 12.76 M), so the headline depends on which OFF age it is compared
against. **Hypothesis, unverified:** the L2's set index uses address bits 6–14 and bits 12–14 come from the
physical page, so which pages Linux hands out could change conflict misses as the free lists age. Nothing here
tests that. **Not yet measured and needed as the matched control:** a pair OFF window as the first and only
window after a fresh boot.

**F8 follow-up (2026-10-08, first stage of the LU sweep): the board-age effect is not universal.** A fresh-boot
OFF window of `lu_ncb -p1 -n512 -b128`, run directly by `run_parsec_session.exp` (no `pair.sh`), read memAcc
**12,609,697**, accessA 12,894,462, hit 47.83% (`clean-runs/lu-sweep-single-core-20261008/off/lu-p1-n512-b128-plru/`).
The old 72-run sweep's OFF windows #28–30 for the same point, taken on a board aged by 27 earlier windows, read
12,564,174 / 12,581,808 / 12,588,985 (mean 12,578,322). The fresh value is **+0.25%** above that mean: no visible
age effect. The effect measured through `pair.sh` (solo-LU b=64, hit ~75%, +17–21% on an aged board) therefore
depends on the workload point and/or the harness. **One reading that fits both, untested:** when the misses are
mostly capacity misses (b=128, 48% hit) page placement changes little; when hits depend on avoiding conflict misses
(b=64, 75% hit) it can change a lot. **Do not generalise F8 beyond points like the ones measured.** Only this one
point can be compared with old data (the old sweep used p=1 only at the centre; its other points used p=2).

**F9 — USER DECISION (2026-10-07): R0 pair run 1 is excluded from the OFF pool.** The user's reason, as
given: it "is not an identical run". Run: `pair-lu+streamcluster` rep 1 of `clean-runs/solo-lu-b64/off/pair_runs_20261007-171552.csv`,
memAcc **12,492,877** (the one reading outside the 12.76–14.11 M band, F6). **The row stays in the CSV and in
the R0 table; only the statistics exclude it, and every table that uses the n=5 pool must say so.**
My note, recorded so the reader can weigh it: dropping the lowest OFF reading raises the OFF baseline and so
deepens the SBC reduction. Nothing I measured shows the run differed — same command, same boot, `accessA`
within 0.5% of the others — and 10-05's first pair reading (12,757,765) is the same kind of value, which makes
it look systematic rather than random. The rule has **not** been applied to 10-05's own first run; whether it
should be, for consistency, is open.

Effect, interim, cold-boot ON pool n=3 (10-05 ×2 = 11,944,500 / 11,921,652, plus ON-b2 11,925,302; mean
11,930,485, sd 12,274, 0.10%). Welch's p implemented in scratchpad `welch.py` and checked against three known
values (t=3.85 df=2 → 0.0613; t=2.0 df=10 → 0.0734; t=2.571 df=5 → 0.0500):

| OFF pool | n | ON change | t | df | p |
|---|---:|---:|---:|---:|---:|
| all 6 (R0 + S1) | 6 | −12.95% | 7.03 | 5.01 | 8.9e-4 |
| **5, R0 run 1 excluded** | 5 | **−14.46%** | 23.42 | 4.05 | 1.8e-5 |
| S1 only (settled) | 3 | −15.10% | 50.33 | 2.12 | 2.7e-4 |

**Read the p-values with care.** They are small because the ON runs are almost identical to each other (sd
0.10%). They say the cold-boot ON pool differs from the OFF pool. They do **not** say SBC caused it: every ON
run is the first window after a fresh boot and every OFF run came after other runs, so board age is confounded
with the migrate switch (F8). Excluding run 1 does not remove that; the cold-boot OFF control would.

**F10 — TASK §8's expected ON-row shortfall does not match the ON rows.** TASK says that on ON rows
`memReads − memWrites − memRelClean` should show "a small shortfall (1–4,459, ≤0.11% of memWrites)". The
three ON rows measured today give **246,289 / 256,080 / 368,469 — 6.0–7.8% of memWrites**, i.e. **98.4–98.6% of
the migration count** (250,105 / 260,258 / 373,727). That is inside the harness's own bound (`counter_fault`:
evictionless fills ≤ migrations + 64) and agrees with its comment (measured 10-05 gaps 252,146 / 269,857
against migrations 256,518 / 274,316, ratio ~98.3–98.4%). So the TASK figure looks wrong and the harness
comment right. I did **not** read the RTL, so I offer no mechanism beyond the harness comment (a migration
frees a way without an announced eviction). The 10-05 CSVs predate the `memRelClean` column, so those rows
could not be recomputed here. The check that gates each row passed on all 21 rows.

**F11 — fresh-vs-fresh, extracted from existing runs (user's rule: same history before the measured
run; no new board runs).** Scanned all 20 `pair_session_*.log` (scratchpad `scan_sessions.py`); position in
the boot from the transcript, uptime from `[PAIR-TIME] t0=`.

- **Solo LU — exact match exists.** In both BIT1 OFF sessions `solo-lu` was **run 1 after a fresh boot**
  (`-121456` 6,179,953 at t0=574 s; `-171552` 6,200,701 at t0=666 s), and the 10-05 `solo-lu` ON
  (`-160348`) was also run 1 after a fresh boot (5,308,519 at t0=606 s). **ON vs OFF: −14.24%, reads
  −14.38%, writes −14.07%**, hit 75.4% → 78.9%, accessA −0.08%. OFF n=2 (two boots, agree within 0.3%), ON n=1.
- **Pair — no exact match existed, so it was MEASURED (2026-10-07, user's go, 3 fresh boots).** Each boot:
  `-prog BIT1 … -phases off -bench pair-lu+streamcluster`, the pair as run 1 after the boot, nothing before it.
  Bitstream sha256 `5ace41250e7c5141…` for all 7 runs (one file, unchanged since 2026-10-01). Every OFF row:
  migrations 0, secondaryHit 0, secondSearch 0, status OK, rc 0, nproc 1.

  | phase | run | uptime at start | memAcc | memReads | memWrites | hit % | accessA |
  |---|---|---:|---:|---:|---:|---:|---:|
  | OFF | `20261007-212426` | 690 s | 12,198,706 | 8,018,800 | 4,179,906 | 75.80 | 33,129,918 |
  | OFF | `20261007-215826` | 711 s | 12,006,549 | 7,846,413 | 4,160,136 | 76.24 | 33,021,409 |
  | OFF | `20261007-223246` | 698 s | 12,250,649 | 8,075,231 | 4,175,418 | 75.67 | 33,192,854 |
  | ON | `20261005-125128` | 593 s | 11,944,500 | 7,860,310 | 4,084,190 | 76.30 | 33,171,486 |
  | ON | `20261005-144834` | 610 s | 11,921,652 | 7,826,560 | 4,095,092 | 76.33 | 33,063,598 |
  | ON | `20261007-181722` | 621 s | 11,925,302 | 7,836,083 | 4,089,219 | 76.36 | 33,151,007 |
  | ON | `20261007-185013` | 606 s | 11,850,371 | 7,751,277 | 4,099,094 | 76.61 | 33,132,540 |

  | metric | OFF n=3 (sd) | ON n=4 (sd) | change | Welch t / df / p |
  |---|---:|---:|---:|---|
  | **memAcc** | 12,151,968 (1.06%) | 11,910,456 (0.35%) | **−1.99%** | 3.13 / 2.31 / **0.073** |
  | memReads | 7,980,148 (1.49%) | 7,818,558 (0.60%) | −2.02% | 2.22 / 2.47 / 0.13 |
  | memWrites | 4,171,820 (0.25%) | 4,091,899 (0.16%) | −1.92% | 11.72 / 3.19 / 0.001 |
  | accessA (same work) | 33,114,727 | 33,129,658 | +0.05% | n.s. |
  | L2 cycles | 2,492,692,653 | 2,495,700,320 | +0.12% | n.s. |
  | hit % | 75.90 | 76.40 | +0.50 pt | |

  The OFF and ON ranges do not overlap (OFF 12.007–12.251 M, ON 11.850–11.945 M), but with OFF n=3 the
  memAcc difference is p = 0.073, not below 0.05. The fresh OFF windows started 80–100 s later in uptime
  than the ON ones (longer boot/staging); no workload ran in that gap. The earlier proxy (−5.66%, from the
  first pair of each OFF session) **overstated** the effect: real fresh OFF is ~3.8% below those readings.
- **Solo streamcluster — no match** (OFF ran 4th–6th, ON ran 1st).

**Decomposition of the 10-05 headline** (ON 11,933,076 vs OFF 13,650,286 = −12.58%): aged OFF → fresh OFF
13.65 M → 12.15 M (**−11.0%**, board age, migration off throughout), then fresh OFF → fresh ON 12.15 M →
11.91 M (**−2.0%**, SBC). About nine-tenths of the headline was board age.

**LU alone vs the pair, fresh-vs-fresh:** LU alone −14.24% (saves 881,808 accesses, ON n=1); the pair −1.99%
(saves ~241 k, ~27% of LU's solo saving). In the pair SBC does 17× the migrations of LU alone (260 k vs 15 k)
for ~2× the secondary hits, so memAcc saved per migration falls from ~59 to under 1. Both arms sit at a
similar hit rate (LU alone 75.4%, pair 75.9%), so **hit rate alone does not explain the difference** —
neither of TASK §1's two framings fits cleanly. Consistent with (not proven by) CLAUDE.md's rule that SBC
pays only while a hot set's overflow fits the spare ways of its partner: a co-runner would occupy those ways.

**F12 — why the 72-run sweep shows LU at −4.01% and this report shows −14.24% (user asked, 2026-10-07).**
The sweep (`lu_runs_20261002-010911_recovered.csv`, single-core BIT1) reproduces exactly: `n=512 b=128 p=1
plru` OFF 12,578,322 → ON 12,074,483 = −4.01%; `n=512 b=64 p=2` −0.63%. It is **not** the same measurement:
(a) **different LU setting** — b=128 there, b=64 here; the sweep has no b=64 p=1 point. (b) **different
method** — the transcript has 0 reprograms and 0 boots (attached to a board already running), all 36 OFF
windows ran first (#1–36) and all 36 ON windows after (#37–72); this point's OFF was windows #28–30 and its
ON windows #64–66, i.e. ON ran 36 windows later on an older board (F8: raises memAcc, hides SBC's benefit)
**and** after 27 earlier ON windows on the same boot (parked lines carried over, which TASK §3.2 says
flatters SBC). Two biases in opposite directions; the net is unknown. TASK §3.2 already says not to cite it.
The n=768 "cold" pair −3.50% / −3.44% (`lu_runs_20261003-203825`, `-20261004-065147`) is dual-core, n=768
b=128 p=2, with OFF as window 1 and ON as window 2 on the same boot — ON one run older than OFF, so by F8 it
likely understates. **LU at b=128 has never been measured fresh-vs-fresh.**
*Update 2026-10-08 (see the F8 follow-up):* at this one point the OFF baseline shows no age effect (fresh 12.61 M vs
aged 12.58 M), so the first of the two biases (an older board raising ON memAcc) may be absent here and only the
second (parked lines carried over from 27 earlier ON windows, which TASK §3.2 says flatters SBC) would remain.
That would make the old −4.01% an over-estimate. **Untested prediction:** the fresh-boot ON window of the LU
sweep at this point (stage 10) reads closer to its OFF than the old ON did.

**F13 — TASK §9's run-time projection rests on the confounded A/B (found 2026-10-08).** TASK §9 and the weekly report
of 2026-10-07 (§6, item 5) quote a "measured miss penalty" of 27.9 cycles, memory at 10% of run time, damping 9.6×, and
3.86% / 6.15% / 7.66% at 100 / 200 / 300 cycles. 27.9 reproduces exactly as Δcycles / ΔmemReads between the 10-05 headline
arms (aged OFF n=3 vs cold ON n=2, cycles −1.31%), so it is a net ratio that includes SBC's own on-chip cost and the
board-age confound of F8/F11, not a miss penalty. Measured with migration off and the same work, a DRAM read costs about
**38 L2 cycles** (the two fresh-boot OFF LU n=768 runs, and the 80-point calib fit; LU sweep section). Fresh-vs-fresh (F11)
the pair's reads fall 2.02% and its L2 cycles rise 0.12%; the LU sweep's model (`tools/project_dram_latency.py`, same
assumptions) puts the pair at **−0.23% / −0.61% / −0.86%** at 100 / 200 / 300 cycles per read, not 3.9–7.7%. TASK.md is
not edited.

**F14 — the b32+b128 pair cannot settle how much SBC recovers (2026-10-08).** Two runs, each SBC-off and SBC-on on fresh boots.
Recovered share of the interference: 22% (run 1), 12% (run 2), mean 17%; the two differ by 10 percentage points.
SBC-off pair traffic repeats to 0.14%, SBC-on to 0.93%; the SBC-on difference (0.13 M) is as large as the effect (0.13-0.24 M).
Rough noise range of the mean: 6-27% (per-window noise 0.6% alone from the LU n=768 repeat, 0.5% pair from these repeats). By the bands fixed before run 1: weak.
The first page said "+/-12 pp"; that counted one window's noise only. Values: `lu-pair-single-core-20261008/RESULTS.md`.

**F15 — SBC recovers little of the pair interference in the three large-interference pairs (2026-10-08).** SBC-on windows on fresh boots, one per pair, after the SBC-off windows.
Memory accesses (M): baseline = the two programs alone; interference = pair - baseline; recovered = interference(off) - interference(on).

| pair | baseline off / on | pair off / on | interference off / on | recovered | band (fixed before run 1) |
|---|--:|--:|--:|--:|---|
| b64 + b64 | 11.84 / 10.31 | 20.33 / 18.33 | +8.50 / +8.02 | 0.48 M = 6% | below 10%: no |
| b32 + b32 | 2.36 / 2.37 | 5.35 / 5.29 | +2.99 / +2.92 | 0.06 M = 2% | below 10%: no |
| b64 + b256 | 17.90 / 16.99 | 21.11 / 20.29 | +3.21 / +3.31 | -0.10 M = -3% | below 10%: no |

- The pair drops by about what the two programs drop alone (b64+b64: pair -2.00 M, baseline -1.52 M). Nothing extra from the sharing.
- Larger interference did not mean more recovery: b32+b128 (+1.1 M) gave 17%, the three larger pairs 6%, 2%, -3%.
- Noise range in RESULTS.md (4-8%, 1-3%, -9 to 3%) counts window noise only (0.5-0.6%). It leaves out page-colour noise of b64 windows (solo and pair get different physical pages; model spread 0.4-0.5 M per b64 program, about +/-1 M for a pair with b64). b64+b64 (0.48 M) and b64+b256 are therefore not conclusive; b32+b32 has no b64 and is the cleanest: 2%, SBC did nothing for it.
- Not tested: why. Open guesses only: b32+b32 interference (+126%) is probably not L2 set conflict (its blocks fit; hit rate stays 90.5% with SBC on or off); the knife-edge b64+b64 thrash needs all 8 ways, which moving cold lines cannot give back.
- Not run: SBC-on for b64+b128 and b128+b128 (small interference); a repeat of b64+b64 (SBC off and on) to measure the page-colour spread directly. Values: `lu-pair-search-20261008/RESULTS.md`.

**F5 — smaller notes.** (i) `wl_lu_cb_cmd` follows TASK exactly and so has no `-lucheck` (`-t`) handling,
unlike `wl_lu_cmd`. (ii) One report test (R1, below) passed `-allow-dirty-on` to a **board-free copy** so
that both phases could be fed fake rows; that copy has no serial code, and the flag was never passed to
the real harness. (iii) Backups are in the session scratchpad, not `/tmp` as TASK says. (iv) `bodytrack`
and `barnes` are **not** added: S5 needs the `sequenceB_1` staging port, S5b needs P3.

---

## Prerequisite fixes (TASK §4)

| # | fix | done | commit |
|---|---|---|---|
| P1 | self-pair solo labels disambiguated; report derives labels from the plan (F2) | yes, 2026-10-07 | **not committed** (user's instruction) |
| P2 | `wl_known` extended (`lu_cb`, `radix`, `fft`) **and** `_thr` dict covers each (F4). `bodytrack`/`barnes` deferred to S5/S5b | yes, 2026-10-07 (the three ids) | **not committed** |
| P3 | `barnes` stdin wrapper (only if S5b reached) | not reached | — |

Backup of the pre-edit harness and `lib/`: scratchpad `run_pair_session.exp.bak-20261007`
(sha256 `486bec94…bb70b`) and `lib.bak-20261007/`. `lib/` is unchanged by this work.
The harness already carried the user's uncommitted edits (302 lines); mine are 89 changed lines on top.
Whoever commits needs to decide whether those two sets go in together.

### Unit tests (TASK §4.1)

| # | test | result |
|---|---|---|
| T1 | `info complete` parses | **PASS** — `info complete -> 1` |
| T2 | `-pair lu,lu -list` → two distinct solo labels | **PASS** — `solo-lu-a`, `solo-lu-b`, `pair-lu+lu` (was `solo-lu`, `solo-lu`, `pair-lu+lu`) |
| T3 | `-pair lu,streamcluster -list` → labels unchanged | **PASS** — `diff` against output captured **before** editing is empty for `lu,streamcluster`, the 10-05 reference config (commands `./lu_ncb -p1 -n512 -b64`, `./streamcluster 2 3 16 43008 2048 200 none output.txt 1`), `lu,canneal`, `lu,nop` |
| T4 | `-pair lu,lu -phases on` → still refused by lock 1.2 | **PASS, run on the board-free copy (F1)** — "REFUSING: 3 migrate=on windows", exit 2 |
| T5 | `-pair lu,lu -phases on -bench pair-lu+lu` → passes | **PASS, board-free copy (F1)** — `LOCK-PASSED`, exit 0 |
| T6 | `-pair radix,fft -list` → both render | **PASS** — `./radix -p1 -n65536 -r1024 -m524288`, `./fft -p1 -m16 -l4` |
| T7 | `-pair lu,fft -phases on,off -list` → reorder note, off then on | **PASS** — prints "reordered to 'off,on'". `-list` does not print the phase order itself, only the note |
| T8 | `-radix-n` renders into the command | **PASS** — `-radix-n 131072 -radix-r 512 -radix-m 1048576 -fft-m 14 -fft-l 6` render as `./radix -p1 -n131072 -r512 -m1048576` and `./fft -p1 -m14 -l6`; `-sc-threads 2` on `radix,fft` is unaffected |

Extra checks beyond TASK (all on the board-free copies; the fake-rows copy is `locktest/reportcopy.exp`):

| check | result |
|---|---|
| lock: mixed pair ON, 3 points | refused, exit 2 |
| lock: the §5.2 ON invocation (one label) | passes |
| lock: one label with `-repeat 2` | refused |
| lock: `-bench solo-lu-a,solo-lu-b` (2 ON windows) | refused |
| lock: OFF only, `-repeat 3` | passes |
| lock: `-phases on,off`, 3 points | refused (the reorder cannot sneak a plan past it) |
| `-pair lu,lu -bench solo-lu-a -list` | one point (before: the label ran twice) |
| `-pair lu,lu -bench solo-lu -list` | exit 2, lists the three valid labels |
| R1 report on a fake self-pair, rows for `solo-lu-a` ≠ `solo-lu-b` | A and B print **separately** (1400 vs 1900). 3900−1400−1900 = 600 ✓, ON 3600−1300−1760 = 540 ✓, repaired 10.0% ✓ |
| R3 report on the ON-only `-bench` path | no crash; prints "not all three of solo-lu / solo-streamcluster / pair-… completed" |
| R2 mixed-pair report, old code vs new, same fake rows | **byte-identical** |
| `git diff --cached` | 0 staged files; HEAD still `f77c328b`; no picocom/expect running; `scripts/logs/` untouched |

---

## R0 — reproduction gate (TASK §6)

| check | expected | measured | pass? |
|---|---|---|---|
| pair OFF memAcc | 12.76 M – 14.11 M | mean **13,357,254** (sd 766,397, ±5.7%); runs 12,492,877 / 13,953,770 / 13,625,115 | **mean: yes.** Run 1 is **2.1% below the 12.76 M floor** (see F6) |
| solo-lu OFF memAcc | 6,446,901 ± 5% | 6,375,555 (−1.11%); runs 6,200,701 / 6,217,275 / 6,708,690 | yes |
| solo-streamcluster OFF memAcc | 1,390,328 ± 10% | 1,475,484 (+6.12%); runs 1,334,381 / 1,575,421 / 1,516,650 | yes |
| accessA per label | within 1% of §3.1 | solo-lu −0.03%, solo-sc +0.06%, pair −0.13% | yes |
| all rows `OK` / `rc=0` | yes | 9 of 9 | yes |
| pair `overlap_frac` | ≥ 0.9 | 0.959 / 0.966 / 0.960 | yes |
| `nproc` | 1 | 1 (all rows) | yes |

Also matches §3.1: hit % 74.76 / 91.63 / 73.68 (ref 74.51 / 92.12 / 72.80), `migrate=off`, `policy=plru`,
`migrations=0`, `parked=0`, `secondaryHit=0` on every row. One FPGA programming, one Linux boot
(`smp: Brought up 1 node, 1 CPU`). The transcript has no `bail`, `MISSING`, `TIMEOUT`, `LOW_OVERLAP`
or `COUNTER_IDENTITY`. The `policy : random` lines in the transcript are the boot default read
before each window; every measured window sets `--policy=plru` (rule 7).

Read/write split, means (rule 8): solo-lu **3,592,917 reads / 2,782,639 writes**; solo-streamcluster
**1,276,608 / 198,876**; pair **8,722,892 / 4,634,362**. Pair interference 5,506,214 (+70.13% vs the sum
of the solos, 7,851,040) — the 10-05 figure was +74.17%.

Log: `chipyard/scripts/logs/clean-runs/solo-lu-b64/off/pair_session_20261007-171552.log` · CSV: `chipyard/scripts/logs/clean-runs/solo-lu-b64/off/pair_runs_20261007-171552.csv`
· launcher: scratchpad `launch_r0.sh` · tmux session `sbc015-r0` · boot # 1.

**Verdict:** **Reproduced on the mean; one caveat for the user to rule on.** Every band passes on the
three-run mean, and every counter identity is exact. But TASK §6 says "within the known spread" and
pair run 1 (12,492,877) sits 263 k below the 12.76 M lower edge. I read the band as applying to the
mean and stopped at point (c) rather than proceed on my own reading.
**User ruling (2026-10-07): run 1 is excluded from the OFF pool as "not an identical run" — see F9.**

---

## S1 — statistical hardening

Single-core `BIT1`, `-samecore`, `-lu-b 64`, `-policy plru`. Pair memAcc = memReads + memWrites. Welch is
OFF pool vs the cold-boot ON pool (n=4).

| arm | n | memAcc mean | sd | min..max | boot# |
|---|---:|---:|---:|---|---|
| pair OFF, all 6 | 6 | 13,704,817 | 618,044 (4.5%) | 12,492,877..14,133,408 | 1 (R0 then S1) |
| **pair OFF, R0 run 1 excluded (F9)** | **5** | **13,947,205** | 191,921 (1.4%) | 13,625,115..14,133,408 | 1 |
| pair OFF, S1 only (settled) | 3 | 14,052,379 | 71,988 (0.5%) | 13,995,803..14,133,408 | 1 |
| pair ON, cold boot, 10-05 | 2 | 11,933,076 | 16,156 (0.14%) | 11,921,652..11,944,500 | two 10-05 boots |
| pair ON, cold boot, 2026-10-07 | 2 | 11,887,836 | 52,984 (0.45%) | 11,850,371..11,925,302 | 2, 3 |
| **pair ON, cold boot, all 4** | **4** | **11,910,456** | 41,291 (0.35%) | 11,850,371..11,944,500 | 10-05 ×2, 2, 3 |
| pair ON after OFF, **same boot** (kept separate) | 1 | 14,196,809 | — | — | 1 (after all OFF) |

Per-repeat OFF, run order, boot 1: **12,492,877 (excluded)** / 13,953,770 / 13,625,115 / 14,133,408 /
14,027,927 / 13,995,803. Per-repeat ON: 11,944,500 / 11,921,652 (10-05) · 11,925,302 (boot 2) ·
11,850,371 (boot 3). Solo OFF means, R0 → S1: solo-lu 6,375,555 → 7,491,159; solo-streamcluster
1,475,484 → 2,087,762 (F8).

Welch (OFF pool vs cold ON n=4):

| OFF pool | OFF mean | ON change | t | df | **p** |
|---|---:|---:|---:|---:|---:|
| **n=5, R0 run 1 excluded (primary)** | 13,947,205 | **−14.60%** | 23.07 | 4.46 | **8.3e-6** |
| n=6, all | 13,704,817 | −13.09% | 7.09 | 5.07 | 8.2e-4 |
| n=3, S1 only | 14,052,379 | −15.24% | 46.15 | 2.99 | 2.3e-5 |
| same day only: n=5 vs ON today n=2 | 13,947,205 | −14.77% | 21.99 | 4.95 | 4.0e-6 |

The 10-05 figure was −12.58%, t=3.85, df=2.00, p≈0.061. **The p-value problem is solved by n; the
confound is not** (see F8, F9): every ON window is the first after a boot and every OFF window is not.

Read/write split (rule 8), means, OFF n=5 vs cold ON n=4: **memReads 9,128,247 → 7,818,558 (−14.35%)**,
**memWrites 4,818,957 → 4,091,899 (−15.09%)**, hit rate 72.50% → 76.40%, accessA 33,190,742 → 33,129,658
(−0.18%, limit 1%). Both fall; SBC did not trade one for the other on a cold boot. On the aged boot it did:
ON-same vs S1 OFF reads **+3.26%**, writes **−3.19%**, total **+1.03%** (13,996 k..14,133 k OFF range; ON-same
is 0.45% above the highest OFF run), hit 71.55 vs 72.29, accessA +0.55%. n=1 — a hint, not a result.

ON-run activity: migrations 274,316 / 256,518 (10-05) · 260,258 · 250,105 · **373,727 (same boot)**; secondary
hits 826,032 / 743,993 · 760,275 · 715,561 · 1,329,326; attempted ≈ 583–586 k on every cold run, 755,698
same boot; aborted 312–334 k cold, 381,996 same boot; parked at window end 3–11.

Logs and CSVs: see the table at the end. S1 OFF repeats: `pair_session_20261007-181011.log`.

---

## S2 — aggressor dose-response (the deciding measurement)

Per-point settings actually used:

| # | `-sc-dim` | `-sc-chunk` | `-sc-n` | solo sec | solo hit % | footprint |
|---|---:|---:|---:|---:|---:|---:|
| I1 | 16 | 1024 | | | | |
| I2 | 16 | 2048 | | | | |
| I3 | 32 | 2048 | | | | |
| I4 | 16 | 4096 | | | | |
| I5 | 64 | 8192 | | | | |

The curve:

| # | A_off | B_off | pair_off | pair_on | I_off | Δ% | **repaired %** | overlap |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| I1 | | | | | | | | |
| I2 | | | | | | | | |
| I3 | | | | | | | | |
| I4 | | | | | | | | |
| I5 | | | | | | | | |

Read/write split (rule 8 — never report only the sum):

| # | memReads off | memWrites off | memReads on | memWrites on |
|---|---:|---:|---:|---:|
| | | | | |

**Does repaired % rise with I_off?** _(yes / no / flat — and what that means per TASK §7 S2)_

Logs:

---

## S3 — self-pairs

| pair | pair_off | pair_on | Δ% | repaired % | accessA ≈ 2x solo? |
|---|---:|---:|---:|---:|---|
| `lu,lu` | | | | | |
| `streamcluster,streamcluster` | | | | | |

**Pressure or diversity?** _(one line)_

Logs:

---

## S4 — second victim `lu_cb`

| arm | memAcc off | memAcc on | Δ% | hit% off | hit% on |
|---|---:|---:|---:|---:|---:|
| `solo-lu_cb` | | | | | |
| `pair-lu_cb+streamcluster` | | | | | |

**Does the 10-05 result survive a different block layout?** _(one line)_
**Does solo-lu_cb ON reproduce the open −17.66% solo-LU-ON reading?** _(one line)_

Logs:

---

## S5 — `bodytrack`

Input tree staged (`sequenceB_1/CALIB` present): yes / no

| arm | sec | accessA | memAcc off | memAcc on | Δ% | overlap |
|---|---:|---:|---:|---:|---:|---:|
| `solo-bodytrack` | | | | | | |
| `pair-lu+bodytrack` | | | | | | |

### S5b — `barnes` (optional)

| nbody | sec | hit % | usable as co-runner? |
|---|---:|---:|---|
| 1024 (staged) | 1.47 | 97.36 | no — reference only |
| | | | |

Wrapper needed (P3) applied: yes / no / not reached

Logs:

---

## S6 — `radix` and `fft` (needs task 014)

Task 014 complete and both binaries static: yes / no

| pair | config | pair_off | pair_on | Δ% | memReads off/on | memWrites off/on |
|---|---|---:|---:|---:|---|---|
| `lu,radix` | `-radix-n 65536` | | | | | |
| `lu,fft` | `-fft-m 16 -fft-l 4` | | | | | |
| `lu,fft` | `-fft-m 16 -fft-l 6` | | | | | |

**On fft:** report the `-l4` vs `-l6` difference as a row-stride change (4112 vs 4160 B), **not**
as aliasing on/off. See TASK §7 S6.

Logs:

---

## S7 — dual core, per-application attribution

**Stop point (d) cleared by the user:** _(quote)_

Bitstream: `BIT2` · `-lu-b 128` · `-cpus 0,1` · `nproc` read as:

| arm | phase | wall_a (LU) | wall_b (sc) | overlap | memAcc | per-app harm |
|---|---|---:|---:|---:|---:|---|
| solo-lu | off | | — | — | | — |
| solo-streamcluster | off | | — | — | | — |
| pair | off | | | | | LU +5.31% / sc +5.50% (10-05) |
| solo-lu | on | | — | — | | |
| solo-streamcluster | on | | — | — | | |
| pair | on | | | | | |

Deadlock recurred at `b=128`? yes / no. If yes: log path, and **one** retry only.

Logs:

---

## LU single-core sweep, fresh-vs-fresh (user request 2026-10-08; not a TASK §7 stage)

**Status: DONE** (2026-10-08 00:31 – 10:25, 18 of 18 stages valid, tmux `sbc015-lusweep`). Everything for it lives in
`chipyard/scripts/logs/clean-runs/lu-sweep-single-core-20261008/`: `PLAN.md` (method, fixed before the first
run), `chain_status.txt` (provenance shas, every stage and check), `off/` `on/` `failed/`, `results/` (written
at the end), `tools/` (launcher, checker, table script as used).

- **Points (9):** `lu_ncb -p1` at n=512 b=128 (centre), n ∈ {256, 384, 768, 1024} at b=128, b ∈ {16, 32, 64, 256}
  at n=512. Single-core bitstream `…candidate013…` (sha256 `5ace41250e7c5141…`), PLRU set on every window.
- **Method:** every window is the first and only window after a full reprogram, in both arms; all 9 OFF stages,
  then all 9 ON stages; 1 run per arm, so no spread and no p-values. OFF and ON go through identical harness
  steps (code read 2026-10-08, `run_parsec_session.exp` lines 594–823).
- **Gate:** `tools/check_lu_stage.py` judges validity only (one reprogram of the right file, one boot, 1 CPU,
  one window and the right command, rc=0, hardware read-back of the switch and policy, counter identities).
  A void run is kept under `failed/` and the stage is retried on a fresh boot; the chain stops when the last
  attempt is void too (2 attempts per stage at launch, **3 with a 150 s settle pause from 02:50**, see the
  events). Self-tested before launch on one synthetic-valid and eight broken cases; the test found and fixed a
  bug (the board's tty echo wraps the command line at ~80 columns). **Checker v2 (03:22, prospective)** no
  longer treats `BUG: workqueue lockup` as a crash when it is printed before the measured command (events).
- **Why:** the old LU sweep (72 windows, 0 reprograms) put every ON window after 36 OFF windows plus earlier ON
  windows on one boot, so it cannot separate SBC from board age (F8, F12). The old `n512 b128 p1` row, −4.01%,
  therefore has an unknown error of unknown sign.
- **Events (chain_status.txt is the full log):**
  - 2026-10-08 02:20: stage 4 (OFF, `lu-p1-n768-b128-plru`) **attempt 1 VOID**. The zmodem transfer of `lu_ncb`
    to the board broke off during staging (`Transfer incomplete`, `exit status: 128`), the harness exited 1 before
    any workload ran, and the CSV holds a header and no rows. Infrastructure failure, no measurement affected.
    Files kept in `failed/off-lu-p1-n768-b128-plru-attempt1-022003/`; the retry started automatically on a
    fresh boot (02:20:03). Stages 1–3 were valid on their first attempt.
  - 02:20–02:39: stage 4 **attempt 2 VOID**, again infrastructure: the FPGA programmed, then the board printed **no
    console output for 17 minutes** (a healthy boot prints the loader spinner at once). Killed by hand; 2 void
    attempts, so the chain aborted as designed. A read-only JTAG query (02:37) showed the fabric configured, DDR4
    calibration passed, 25 °C, so not memory or power. A manual reprogram + passive serial listener (02:40) booted
    normally and was never used for a measurement. Resuming the chain at 02:44 reprogrammed again and the board
    went silent again (stopped by hand after 3.5 min, kept in `failed/…-attempt1-024422-stopped-by-hand`); a
    relaunch at 02:49 booted normally. User instruction: "just retry … no need to debug that issue for now", so
    the cause is **not investigated**; unproven hypothesis in `diagnostics-20261008-0237/README.txt` (a reset that
    interrupts SD-card writes leaves the card unresponsive for a while). Mitigation: launcher v2, 3 attempts per
    stage and a 150 s settle pause before a retry (retry rule still blind to values).
  - 02:49–03:18: stage 4 attempt 1 of the relaunched chain ran to the end. **Checker v1 voided it only because the
    transcript has two `BUG: workqueue lockup … stuck for 30s` lines** (kernel time 308 s, printed while the
    staging `sync` to the SD card was blocked; before the measured command). Every other check passed. The same
    lines are in earlier sessions whose results were accepted (`grep -a "workqueue lockup"`: the pair-campaign
    logs under `clean-runs/`, `lu_session_20261004-045053.log`, …), always before the first `[SBC-WINDOW]`, so v1's
    rule was a false alarm, not a fault of the run. **Checker v2** (03:22, v1 kept as `check_lu_stage.py.v1`)
    judges that message by position (before the measured command: warning; while the window is open: VOID); any
    other `BUG:`, `Oops:`, `Kernel panic` is still VOID. **Prospective**: attempt 1 keeps its VOID verdict and its
    files stay in `failed/` with a NOTE; `recheck_with_checker_v2.txt` there is for information only. Decided from
    the log text, not from a counter value. Details: `PLAN.md`, second addendum.
  - 03:20–03:49: stage 4 **attempt 2 VALID** (checker v2, no warnings): memAcc 31,887,029 (reads 17,197,189 + writes
    14,689,840), accessA 42,248,146, hit 59.29%, window 76.4 s. Attempt 1 read 32,162,792 (−0.86%, same work): a free
    replicate of fresh-boot OFF at n=768, shown but not averaged in. Chain moved on to stage 5 (OFF n=1024) at 03:49.
  - 03:30–03:52, user concern about timing ("n=1024 takes way more" / "boot to run takes more than 30 min"). Checked
    against logs and code, not estimated (`PLAN.md`, third addendum): a stage takes 27.5–28.8 min of which **21 min is the
    boot ROM loading the payload from the SD card** (exactly 951 spinner steps in every complete boot), then Linux, staging
    (the `sync` took 1 min 45 s) and the window; the old sweep's n=1024 windows ran 196–198 s, so an n=1024 stage is
    about 31 min. No timer covers a whole stage; `WORK_TMO` 1800 s covers only the measured command and `BOOT_TMO`
    1800 s the SD-card spinner (about 9 min of slack; not changed). A read-only observer now writes `timeline.txt`.
  - 03:46: user rule "if it stalls again, drop it and continue". Launcher v3 (prepared, not running) drops a stage
    whose every attempt ended before any measured window; a stage that measured but failed validation still stops the
    chain; an OFF drop skips its ON stage; two drops in a row stop the chain. Applied only if the running v2 stops.
    **Superseded at 04:05** by the user's refinement, below.
  - 04:05–04:15, user policy: "if a run failed, drop it and continue to the next run; after sweeping through all
    configs, rerun the failed runs and populate the table." **Launcher v4** replaced v2 at 04:15 (v3 never ran): pass 1 =
    one attempt per stage, a failure is deferred and the chain goes on; pass 2 after the whole sweep = up to 2 retries
    per deferred stage (OFF first, then ON); a stage still void is given up and listed; 3 failed attempts in a row stop
    the chain. **Exception to the all-OFF-before-any-ON rule, by the user's instruction:** an OFF stage repaired in
    pass 2 runs after the ON stages (flagged `retry_pass.txt`, shown in the table; matched history is kept because
    every window is the first after a reprogram). The swap stopped only the old launcher; stage 5's harness kept
    running and v4 adopted it (`PLAN.md`, fourth addendum). Stages valid so far: OFF n=512, 256, 384, 768 (b=128).
  - 04:20 stage 5 (OFF n=1024) valid, window 188.5 s, stage 30.6 min (adopted by v4 without trouble). Stages 6–9
    (OFF b=16, 32, 64, 256 at n=512) each valid on their first attempt, no warnings, nothing deferred: **the OFF half
    is complete, 9 of 9 valid, at 06:11**, the retry pass has not been needed yet. The ON half began at 06:11:41
    (first ON stage = the centre point n=512 b=128).
  - 06:39 first ON stage valid (centre n=512 b=128, one run per arm, both on fresh boots): memAcc 12,609,697 → 12,156,767
    = **−3.59%** (reads −6.06%, writes −0.77%), hit 47.83% → 51.17% (+3.34 pp), secondary hits 7.53% of accesses,
    204,880 migrations, accessA +0.37% (same work), window length −0.11%. The old aged-board sweep said −4.01% for this
    point: 0.4 pp apart, inside the noise seen so far (a fresh OFF replicate at n=768 differed by 0.86%), which fits the
    finding that LU at b=128 shows no board-age effect. n=1 per arm: no p-value.
  - 08:34 **n axis (b=128) complete, 5 pairs, all valid on the first attempt, no warnings** (fresh boot per window).
    memAcc OFF→ON: n=256 −4.66%, n=384 −3.23%, n=512 −3.59%, n=768 −3.21%, n=1024 −3.76%. accessA within ±0.4% at
    every point (same work). Reads −2.8% to −6.5%, writes −0.65% to −3.9%. Hit rate +0.2 to +3.5 pp. Migrations 3,315
    (n=256) to 1,880,537 (n=1024); secondary hits 0.5–8.3% of accesses. At n=1024 the saving is 3.84 M lines
    (about 246 MB) of 102.2 M. The percentage is nearly flat in n while the absolute traffic spans 200x; n=256 and n=384
    have only about 0.55 M lines each in 3–9 s windows, so their percentages carry more noise. n=1 per arm: no p-values.
    The b axis (b=16, 32, 64, 256 at n=512) is next, ON stages 15–18, due about 10:30.
- **Results** (`results/table.md`, `results/results.csv`; one run per arm, both on fresh boots, so **no p-values**; the only fresh
  repeat available, OFF at n=768, differed by 0.86%). memAcc = memReads + memWrites; every pair did the same work (accessA within ±0.4%
  everywhere; window lengths within 0.3% except n=512 b=64 at −1.07%; no row is flagged).

  | n | b | memAcc OFF | memAcc ON | Δ | reads Δ | writes Δ | hit % OFF → ON | L2 cycles OFF | L2 cycles ON | cycles Δ | migrations |
  |--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
  | 256 | 128 | 582,914 | 555,765 | −4.66% | −5.35% | −3.80% | 81.5 → 82.5 | 146,692,013 | 146,384,072 | −0.21% | 3,315 |
  | 384 | 128 | 544,962 | 527,363 | −3.23% | −2.83% | −3.88% | 93.2 → 93.4 | 433,455,773 | 433,037,070 | −0.10% | 4,633 |
  | 512 | 16 | 1,988,864 | 1,983,799 | −0.25% | −0.33% | −0.08% | 93.0 → 93.0 | 1,296,589,808 | 1,296,955,837 | +0.03% | 12,387 |
  | 512 | 32 | 1,180,359 | 1,184,677 | +0.37% | +0.55% | −0.12% | 94.4 → 94.4 | 1,129,934,512 | 1,130,106,374 | +0.02% | 9,995 |
  | 512 | 64 | 5,917,758 | 5,155,984 | **−12.87%** | −13.56% | −12.00% | 75.4 → 78.7 | 1,131,756,946 | 1,119,594,667 | −1.07% | 17,080 |
  | 512 | 128 | 12,609,697 | 12,156,767 | −3.59% | −6.06% | −0.77% | 47.8 → 51.2 | 1,205,683,656 | 1,204,344,438 | −0.11% | 204,880 |
  | 512 | 256 | 11,986,991 | 11,829,873 | −1.31% | −2.29% | −0.27% | 51.3 → 52.4 | 1,173,803,542 | 1,177,333,736 | +0.30% | 91,108 |
  | 768 | 128 | 31,887,029 | 30,863,581 | −3.21% | −5.40% | −0.65% | 59.3 → 61.5 | 3,818,474,149 | 3,814,508,771 | −0.10% | 625,221 |
  | 1024 | 128 | 102,157,067 | 98,314,880 | −3.76% | −6.45% | −0.66% | 45.7 → 49.2 | 9,425,680,220 | 9,400,646,349 | −0.27% | 1,880,537 |

  - **Where SBC pays:** the biggest gain is n=512 b=64 (−12.87%, hit rate 75% → 79%; reads −13.6% and writes −12.0%); the earlier
    fresh-vs-fresh `solo-lu` measurement of the same point through `pair.sh` read −14.2%, within 1.4 points (a different path that adds about 0.86 M accesses per run). At b=128 the
    gain is a steady −3.2% to −4.7% from n=256 to n=1024, mostly reads (−2.8% to −6.5%). At b=16 and b=32 the hit rate is already
    93–94%, there is nothing to save, and the result is zero within the noise (−0.25%, +0.37%). b=256 gives −1.31%.
  - **Run time (L2 cycles, the L2 clock at 50 MHz over the measured window):** the cycle counts barely move while the traffic does.
    b=128 gives −0.10% to −0.27% at every n (traffic −3.2% to −4.7%); b=16 and b=32 are +0.03% and +0.02%;
    b=256 is +0.30%. Only **n=512 b=64 stands out: −1.07%** (−12.2 M cycles = 0.24 s of 22.6 s; traffic −12.87%). Noise references: the one fresh-boot
    repeat in this sweep (OFF n=768, a void attempt kept under `failed/`) differs by 0.14% in cycles and 0.86% in traffic; the earlier fresh solo-LU pair
    at the same b=64 point (through `pair.sh`, accessA about 14.2 M, 2 OFF + 1 ON windows, `clean-runs/solo-lu-b64/`) read −1.17% in cycles
    (OFF 1,176,677,749 and 1,178,802,029; ON 1,163,959,469) next to −14.24% traffic. Two independent pairs agree on about −1.1% at b=64, which supports a
    small real gain there; every other cycle difference is at most 0.30% (b=256, where ON is the slower arm) and cannot be told apart from noise with one run per arm.
    At every point the relative change in traffic is larger than the relative change in cycles. The L2 cycle counts were checked against the raw
    `[SBC-WINDOW]` lines of all 18 transcripts (no mismatch).
  - **Why the cycles barely move here, and what the traffic cut would be worth on a faster chip** (raised by the user
    2026-10-08; **a first-order model, not a measurement**; `tools/project_dram_latency.py` → `results/dram_latency_projection.txt`).
    On the VCU118 the core, L2 and buses run at 50 MHz (`WithFPGAFreq50MHz`) and the DDR4 controller at 800 MHz (DDR4-1600),
    so a DRAM read costs only **about 38 L2 cycles** beyond an L2 hit: 38.0 cycles per read from a fit over the 80 calib A/B
    points of 2026-09-25 (R² = 0.99), and 38 × the extra reads of the two fresh-boot OFF n=768 runs predicts their extra
    cycles (5.33 M against 5.24 M measured; their 135,570 extra write-backs cost about nothing). The L1 D-cache is blocking
    (`WithNBigCores`: `nMSHRs = 0`), so every miss stalls the core. Split that way, the board's cycle change is the reads
    saved (−0.9% to −1.5% of run time at b=128 n ≥ 512 and at b=64) minus an on-chip cost that tracks the second searches:
    at the five points with ≥ 0.97 M second searches the remainder is 2.1–5.0 cycles per second search (2.6–3.0 at b=128).
    At n=1024 there are 12 second searches per saved read, so they cost about 31 of the 38 cycles each saved read is worth,
    and the net is −0.27%. Those on-chip costs keep their cycle count at a higher clock; a DRAM read does not. Keeping
    everything else at its board cycle count and charging a DRAM read LR cycles instead of 38
    (LR = 100 / 200 / 300 ≈ a 1 / 2 / 3 GHz chip about 100 ns from DRAM):

    | n | b | reads Δ | cycles Δ, board (measured) | LR = 100 | LR = 200 | LR = 300 | DRAM share of OFF run time at LR = 200 |
    |--:|--:|--:|--:|--:|--:|--:|--:|
    | 256 | 128 | −5.35% | −0.21% | −0.83% | −1.56% | −2.09% | 32% |
    | 384 | 128 | −2.83% | −0.10% | −0.22% | −0.40% | −0.56% | 14% |
    | 512 | 16 | −0.33% | +0.03% | +0.01% | −0.02% | −0.05% | 18% |
    | 512 | 32 | +0.55% | +0.02% | +0.04% | +0.07% | +0.10% | 13% |
    | 512 | 64 | −13.56% | −1.07% | −2.99% | **−5.08%** | −6.48% | 40% |
    | 512 | 128 | −6.06% | −0.11% | −1.64% | −2.93% | −3.64% | 59% |
    | 512 | 256 | −2.29% | +0.30% | −0.34% | −0.89% | −1.20% | 57% |
    | 768 | 128 | −5.40% | −0.10% | −1.26% | −2.34% | −2.97% | 52% |
    | 1024 | 128 | −6.45% | −0.27% | −1.90% | **−3.26%** | −4.00% | 60% |

    The board cost of a read hardly matters: 30 or 46 cycles instead of 38 moves the LR = 200 column by at most 0.15 points.
    The assumptions all favour the larger numbers: every read miss stalls the core (true for this in-order Rocket with a
    blocking L1D and no prefetcher), the 256 KB L2 is the last-level cache, and write-backs stay off the critical path. An
    out-of-order core, a prefetcher (LU's accesses are regular) or an L3 behind the L2 would make the gain smaller. b=16 and
    b=32 stay at zero (their reads move less than the 0.8% read difference between the two OFF n=768 fresh boots); n=256 and
    n=384 gain little because DRAM is a small share of their run time. Measuring this instead of modelling it needs a new
    build: a fixed delay in front of the DDR4 controller, or FireSim with a DRAM timing model. One run per arm, as everywhere
    in this sweep.
  - **Not a rule yet:** the saving does not follow the OFF hit rate monotonically (b=128 at 48% and b=64 at 75% differ four-fold in the
    saving), and the p=1 single-core, one-run-per-arm design cannot separate noise below about 1% from an effect.
  - **Old one-boot sweep vs this one** (`lu_session_20261002-010911`, 36 OFF windows then 36 ON windows on one aged boot, mostly p=2, so the
    comparison is indicative, not like for like): n=256 +22.75% (now −4.66%), n=384 +10.06% (−3.23%), b=16 +4.17% (−0.25%), b=32 **+60.79%**
    (+0.37%), b=64 −0.63% (**−12.87%**), b=256 −0.70% (−1.31%); the larger points agreed: n=512 b=128 p1 −4.01% (−3.59%), n=768 −3.54%
    (−3.21%), n=1024 −4.34% (−3.76%). The old numbers cannot be trusted at the small points (n=256, n=384, b=16, b=32) and missed b=64.
    The most likely cause is the board-age effect of F8 acting on the ON half of a one-boot sweep (the ON windows ran last, on the oldest
    boot); that was not tested here, and p=2 against p=1 may contribute at some points. The "about 4%" the user asked about was right for
    b=128 and understated the b=64 point by a factor of three.
  - **Provenance:** 22 valid-or-void attempts: 18 valid, 4 void, all four on stage 4 (zmodem failure; silent boot ×2; one good run voided
    by a checker false alarm that v2 fixed). Stages 1–3 and 5–18 were valid on the first attempt. Nothing was deferred after the policy
    change, so the retry pass found nothing to do. Launchers: v1 → v2 → v4 (v3 never ran); checker v1 → v2 (one prospective change).
    The harness and its libraries were not edited. Nothing is committed.

## LU pair on one core, fresh-vs-fresh (user request 2026-10-08; not a TASK §7 stage)

**Status: run 1 DONE** (2026-10-08 14:58 – 15:55, 2 of 2 stages valid, tmux `sbc015-lupair`); **run 2 (repeat) DONE** 17:06 – 18:03, 2 of 2 valid, folder `r2/` (F14). Folder `chipyard/scripts/logs/clean-runs/lu-pair-single-core-20261008/`: `PLAN.md` (method, the predictions fixed
before the first window, two addenda with the checks against them), `results/table.md`, `chain_status.txt`, `off/` `on/`, `tools/`, `dryrun/`. Nothing is committed.

- **Question (user):** an application that uses the L2 well alone shares the core with a cache thrasher and loses cache performance. Does SBC win some of it back? The streaming co-runner was dropped (it sweeps every set, so no set has room to park lines);
  the same program with another block size was used instead.
- **What ran:** `lu_ncb -p1 -n512 -b32` (victim: hit 94.4% alone, SBC does nothing alone) and `lu_ncb -p1 -n512 -b128` (thrasher: 47.8% alone), started together on the one core, the OS time-slices them; the window closes when both have exited
  (run to completion: the two arms then do the same work; overlap measured 0.953 / 0.952). BIT1, PLRU, one reprogram + fresh boot + staging + one window per arm, OFF stage first. Solos = the LU sweep's windows (same steps, same bitstream).
- **Harness:** `tools/run_parsec_session.exp`, a copy of the sweep's `run_parsec_session.exp` with one new plan (`-plan lupair`); the board sees the sweep's steps in the sweep's order and only the measured command differs
  (diff: `tools/harness_changes.diff`, original untouched, sha256 `b13d93e7…`). `run_pair_session.exp` was not used: it stages `pair.sh` and its window holds about 10 extra program starts (~874 K accesses, measured 10-05).
- **Checks before the board:** the pair command on the board's busybox 1.36.0 `ash` (qemu-user, real `lu_ncb`); checker 27 of 27 and launcher 15 of 15 on fixtures; a 36 s board dry run. All passed.

| (n = 1 per arm) | pair OFF | pair ON | change | sum of the two solos, OFF |
|---|--:|--:|--:|--:|
| memReads | 8,289,633 | 7,680,874 | −7.34% | 7,577,893 |
| memWrites | 6,613,133 | 6,528,557 | −1.28% | 6,212,163 |
| **memAcc** | **14,902,766** | **14,209,431** | **−4.65% (−693,335)** | 13,790,056 |
| accessA | 32,248,825 | 32,273,411 | +0.08% | 28,105,303 |
| hit rate | 74.29% | 76.20% | +1.91 pp | |
| migrations / secondary hits | 0 / 0 | 215,015 / 2,261,449 | | |
| L2 cycles (50 MHz) | 2,495,830,380 | 2,491,078,322 | −0.19% | 2,335,618,168 |

  - **Interference (SBC off):** pair OFF is +1,112,710 accesses (+8.07%) over the two alone; reads +9.4%, writes +6.5%; window cycles +6.9%; each program takes ~2.0x its solo time (46.4 s and 48.7 s against 22.6 s and 24.1 s).
    Part of it is the OS cost of the context switches (the pair does 14.7% more L2 accesses than the two alone, which L1 refill cannot explain; page-table walks and kernel code are the probable cause, not measured).
  - **What SBC wins back:** the TASK-definition repaired fraction is **62.3%** (reads 85.5%, writes 21.1%), but it includes the 448,612 accesses SBC saves for b=128 alone (−4,318 for b=32, 452,930 for b=128).
    Beyond that, **excess = 244,723 accesses = 22.0% of the interference** (reads 29.0%, writes 9.7%, cycles 2.2%). Resolution about +/-12 pp (the one fresh-boot replicate, 0.865% of memAcc, is 129 K accesses),
    so roughly 10% to 34%. By the bands fixed before the run (below 10% no, 10-25% weak and repeat, 25% or more yes) this is **weak: repeat before believing.** The subtraction assumes SBC's gain on each program is the same together as alone, which one pair cannot test.
  - **What the counters do not show:** they are L2-wide, so a secondary hit cannot be attributed to the victim or the thrasher. Migrations are within 5% of b=128 alone; secondary hits are 2.3x, in line with 2.5x the accesses (7.0% vs 7.5% of accesses).
    "SBC repaired the victim" is not shown, only that the pair's traffic fell by more than the sum of the solo gains.
  - **Run time:** flat on this board (−0.19%; each program's own time −0.34% / −0.23%) because a DRAM read costs ~38 L2 cycles here. Model, not a measurement (the sweep's method): −1.41% / −2.69% / −3.52% at 100 / 200 / 300 cycles per DRAM read.
  - **Predictions vs outcome (PLAN §7):** window length, overlap, migrations count and same-work held. Wrong: the pair's accessA (+14.7%, predicted <= +5%), the SBC pair gain (693 K, predicted 270-630 K) and the secondary-hit count (2.26 M, predicted 0.8-1.2 M).
    The most likely reading I wrote down (no repair, below 10%) did not hold; I was wrong in the direction of SBC doing more in the pair, not less.
  - **Repeat (run 2, 17:06-18:03):** SBC-off pair 14.923 M, SBC-on 14.343 M; interference +1.13 M to +1.00 M; recovered 12%. Mean of the two runs: 17% (F14). Weak band, not settled.
  - **Next:** a pair with larger interference, chosen by a model: section "LU pair search" below.

## LU pair search: which pair hurts most (user request 2026-10-08; not a TASK §7 stage)

**Status: DONE** (2026-10-08 18:04-22:14, tmux `sbc015-lupair`; 5 SBC-off + 3 SBC-on windows, 8 of 8 valid, no void attempt). Folder `chipyard/scripts/logs/clean-runs/lu-pair-search-20261008/`: `PLAN.md` (logic, model checks, predictions and pass/fail checks fixed before the first window),
`RESULTS.md` (results log, regenerated by `tools/make_results.py`), `model/` (cache model and its outputs), `tools/launch_pairs.sh` (any list of pairs, all SBC-off before any SBC-on; 18 of 18 host tests). Nothing is committed.

- **Question (user):** find the combination that hurts each other most; build a logic first, test it with a few windows instead of sweeping.
- **Logic:** at each context switch the other program evicts the block the victim re-uses. LU re-uses its b x b block (b=32: 8 KB, b=64: 32 KB, b=128: 128 KB). At n=512 a block lands on b sets with b/8 lines each, so b=64 needs all 8 ways of its sets (knife edge).
  Two programs that both need all ways of the same sets hurt each other most; a thrasher (b=128/256) hurts a b=64 victim but loses nothing itself.
- **Model** (`model/lusim.c`): the real lu_ncb loop nest through the real L1D (8 KB, 4-way, random) and L2 (256 KB, 8-way PLRU, inclusive), SBC off, two programs time-sliced. Not modelled: TLB, instruction fetch, kernel, SBC. Page colours unknown, fitted.
  Solo memory accesses, model / board at n=512: b16 0.89, b32 0.83, b64 0.95, b128 0.93, b256 0.97 (n=768: 0.64). Pair b32+b128: model +0.60 M (0.30-1.22), board +1.11 M: the gap is probably OS cost at the switches.
- **Predictions (interference, SBC off, median and range, M accesses):** b64+b64 4.5 (3.0-8.1); b64+b256 2.1 (1.2-3.8); b32+b32 1.95 (1.4-3.0, +83% of its baseline, the largest relative); b64+b128 1.1 (0.5-2.5); b128+b128 0.9 (0.4-1.6).
  The model gets the order right under every knob tried and the size to about a factor 2 (slice length). A pair containing b=64 carries about +/-1 M of page-colour noise. Wider scan: identical pairs at b=48-56 are even worse but have no board baseline.
- **Windows (one fresh boot each, ~28 min):** 1 b64+b64, 2 b32+b32, 3 b64+b256, 4 b64+b128, 5 b128+b128; then SBC-on for the large ones. Checks P1-P5 are in PLAN.md.
- **Result so far (window 1, 18:04-18:32, valid):** b64+b64 pair SBC off = 20.33 M accesses (reads 11.21 M, writes 9.12 M, hit 64.7%), baseline 11.84 M, so **interference +8.50 M = +72%**. Predicted 4.5 M (3.0-8.1): above the range, so the model under-predicts the size here as for b32+b128; P1 (largest, at least 3.0 M) holds so far, the others wait for their windows. Each program ran 49.0 s (alone 22.6 s). 
- **Window 2 (18:32-19:00, valid):** b32+b32 pair SBC off = 5.35 M (reads 3.29 M, writes 2.06 M, hit 90.5%), baseline 2.36 M, so **interference +2.99 M = +126%**. Predicted 1.95 M (1.4-3.0): at the top edge of the range; largest in % of baseline so far (P3 pending the other windows). Each program ran 46.6 s (alone 22.6 s). 
- **Window 3 (19:00-19:29, valid):** b64+b256 = 21.11 M, baseline 17.90 M, **interference +3.21 M = +18%** (predicted 2.06, range 1.2-3.8: inside). **Window 4 (19:29-19:57, valid):** b64+b128 = 20.87 M, baseline 18.53 M, **+2.34 M = +13%** (predicted 1.13, range 0.5-2.5: inside, near the top). 
- **Window 5 (19:57-20:26, valid):** b128+b128 = 26.72 M, baseline 25.22 M, **+1.50 M = +6%** (predicted 0.92, range 0.4-1.6: inside, at the top). **Chain finished 5 of 5 valid, no void attempt.**
- **Checks P1-P5: all pass; "wrong if" not triggered.** Measured order = predicted order: b64+b64 +8.50 M (+72%) > b64+b256 +3.21 (+18%) > b32+b32 +2.99 (+126%) > b64+b128 +2.34 (+13%) > b128+b128 +1.50 (+6%).
- **Size:** measured / model median = b64+b64 1.9, b64+b256 1.6, b32+b32 1.5, b64+b128 2.1, b128+b128 1.6 (b32+b128 earlier: 1.9). The model is low by a roughly constant factor 1.5-2: the shape is right, the size is not; cause not isolated (OS cost at the switches, or a shorter real slice than 200 K cycles).
- **Limits:** one window per pair; b64 pairs carry about +/-1 M of page-colour noise (solo and pair windows get different pages); the b64+b64 baseline is one solo window used twice. 
- **SBC-on windows (user go-ahead 20:49; one fresh boot each, order b64+b64, b64+b256, b32+b32):** valid 20:49-21:17, 21:17-21:45, 21:45-22:14.
  Recovered 6% (b64+b64, 0.48 M of +8.50 M), -3% (b64+b256, -0.10 M of +3.21 M), 2% (b32+b32, 0.06 M of +2.99 M): all below the 10% band. Table and caveats in F15.

## Counter cross-checks (TASK §8)

| check | rows checked | exact? | worst deviation |
|---|---:|---|---|
| `accessA == primary+secondary+dataMiss+upgradeMiss` (±64) | 21 (R0, S1, 3 ON) | yes, gap 0 on all | 0 |
| `memReads == dataMiss` | 21 | yes, exactly | 0 |
| `memReads − memWrites − memRelClean == 0` (OFF rows) | 18 (`memAcqPerm` = 0) | yes, exactly | 0 |
| ON rows: evictionless fills vs migrations | 3 | **does not match TASK §8's "1–4,459"** — F10; inside the harness bound | 246–368 k = 98.4–98.6% of migrations |
| `accessA` OFF vs ON within 1% | pair, OFF n=5 vs ON n=4 | yes | −0.18% (ON-same +0.55%) |

---

## All log and CSV paths

Moved 2026-10-08 into `chipyard/scripts/logs/clean-runs/` (map: `clean-runs/MOVED.tsv`). Rows for runs that were
not moved (S1 OFF, ON-after-OFF) still name `scripts/logs/`. A bare `clean-runs/…` path is relative to
`chipyard/scripts/logs/`. Windows #2–#9 of the two solo-LU OFF sessions are aged-board windows, not clean.

| stage | transcript | CSV |
|---|---|---|
| R0 (boot 1, BIT1, OFF, `-repeat 3`) | `chipyard/scripts/logs/clean-runs/solo-lu-b64/off/pair_session_20261007-171552.log` | `chipyard/scripts/logs/clean-runs/solo-lu-b64/off/pair_runs_20261007-171552.csv` |
| S1 OFF (boot 1, no `-prog`, `-skippush`, `-repeat 3`) | `chipyard/scripts/logs/pair_session_20261007-181011.log` | `chipyard/scripts/logs/pair_runs_20261007-181011.csv` |
| ON after OFF, **same boot 1** (no `-prog`) | `chipyard/scripts/logs/pair_session_20261007-181601.log` | `chipyard/scripts/logs/pair_runs_20261007-181601.csv` |
| ON, cold boot 2 (`-prog BIT1`) | `chipyard/scripts/logs/clean-runs/pair-lu-streamcluster-b64/on/pair_session_20261007-181722.log` | `chipyard/scripts/logs/clean-runs/pair-lu-streamcluster-b64/on/pair_runs_20261007-181722.csv` |
| ON, cold boot 3 (`-prog BIT1`) | `chipyard/scripts/logs/clean-runs/pair-lu-streamcluster-b64/on/pair_session_20261007-185013.log` | `chipyard/scripts/logs/clean-runs/pair-lu-streamcluster-b64/on/pair_runs_20261007-185013.csv` |
| fresh OFF, boot 4 (`-prog BIT1`, pair only) | `chipyard/scripts/logs/clean-runs/pair-lu-streamcluster-b64/off/pair_session_20261007-212426.log` | `chipyard/scripts/logs/clean-runs/pair-lu-streamcluster-b64/off/pair_runs_20261007-212426.csv` |
| fresh OFF, boot 5 | `chipyard/scripts/logs/clean-runs/pair-lu-streamcluster-b64/off/pair_session_20261007-215826.log` | `chipyard/scripts/logs/clean-runs/pair-lu-streamcluster-b64/off/pair_runs_20261007-215826.csv` |
| fresh OFF, boot 6 | `chipyard/scripts/logs/clean-runs/pair-lu-streamcluster-b64/off/pair_session_20261007-223246.log` | `chipyard/scripts/logs/clean-runs/pair-lu-streamcluster-b64/off/pair_runs_20261007-223246.csv` |
| 10-05 solo-LU ON, reused (fresh boot) | `clean-runs/solo-lu-b64/on/pair_session_20261005-160348.log` | `clean-runs/solo-lu-b64/on/pair_runs_20261005-160348.csv` |
| 10-05 ON, reused (cold boots) | `clean-runs/pair-lu-streamcluster-b64/on/pair_session_20261005-125128.log`, `…-144834.log` | `clean-runs/pair-lu-streamcluster-b64/on/pair_runs_20261005-125128.csv`, `…-144834.csv` |
| chain launcher, status, checker | scratchpad `launch_chain.sh`, `chain_status.txt`, `stage_check.py`, `s1_analysis.py`, `welch.py` | — |

---

## Verdict — the §1 question

_(One paragraph, plain words. Does SBC's benefit come from applications interfering with each
other, or from a workload sitting at a low hit rate? Name the evidence that decides it, and say
explicitly which of the two framings the data supports. If it is the second — "SBC helps
low-hit-rate workloads, and co-running is one way to create one" — say so plainly; that is a good
result and the current evidence slightly favours it.)_

### Interim reading, 2026-10-09 (not the final verdict: S2 to S7 not run)

- **For "low hit rate / working-set overflow":**
  - LU alone, fresh-vs-fresh sweep (18 boots): SBC cuts memory accesses 3-5% where the working set overflows, 12.9% at n=512 b=64, nothing at b=16 and b=32 (hit rate 93-94%)
- **Against "SBC repairs interference":**
  - Three LU+LU pairs with +3.0 to +8.5 M extra accesses: SBC recovers 6% (b64+b64), 2% (b32+b32), -3% (b64+b256); the pair gains what each program gains alone (F15)
  - Earlier b32+b128 pair: 17% (mean of two runs, 22% / 12%), weak band (F14)
- **Neither framing fits cleanly:**
  - LU + streamcluster, fresh-vs-fresh: -1.99% (p = 0.073) against -14.24% for LU alone, at similar hit rates (75.9% vs 75.4%) (F11)
- **Reading so far:** the data lean to the second framing of TASK section 1 (SBC helps a program whose own working set overflows; co-running is one way to cause that), not to the first
- **Open:**
  - b64 pairs carry about +/-1 M page-colour noise; b64+b64 and b64+b256 are not conclusive
  - n = 1 per pair; no per-program attribution (S7)
  - S2 dose-response (partner footprint) not run
