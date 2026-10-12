# Coder report 016 — bodytrack, SBC off vs on, and what each parameter does to the effect

**Started:** 2026-10-09 (in the same chat that wrote the TASK; Amendment 1 A1.7) · **Status:** ONE SWEEP DONE 2026-10-10 21:22 — Phase 1 + Phase 2, 36 of 36 windows valid (18 settings, SBC off and on each), no replicates yet; waiting for the user's decision on repeats (A1.3). Verdict at the end

> Fill this in as you go, not at the end. A stage with an empty row is not done. Cite log paths — never paste logs.
> Anything unexpected goes under "Findings" and you stop and ask. Results live in the campaign's `RESULTS.md`;
> this file holds the narrative and the evidence trail.

## ▶ RESUME HERE

- 2026-10-10 21:22: one full sweep done (Phase 1 + Phase 2, 36 valid windows). Board idle; nothing running
- Next: the user decides the repeats (A1.3). Suggested: the 400-particle group (base, layers 5, threads 2, threads 4) and particles 1600 / 4000, to measure bodytrack's own noise first. Step 6 (extra settings toward the optimum) is not started
- Campaign folder: `chipyard/scripts/logs/clean-runs/bodytrack-sweep-single-core-20261009/` (`PLAN.md` = method, search rules, choices, incidents; `RESULTS.md` = all windows; `results/report_tables.md` = the tables below, from `tools/report_tables.py`)
- A new chain: `STAGES="off:<tok> on:<tok> …" MD5_MODE=strict tools/launch_bt.sh` in tmux `sbc016-bt` (kill the idle session first); every new token needs a host-oracle row first (`tools/oracle_host.sh <workdir> <token>`)

## Findings

**F1 — the harness's bodytrack staging could not work on a fresh boot (fixed in the campaign copy, 2026-10-10).** Calibration
attempt 1 (`clean-runs/bodytrack-sweep-single-core-20261009/calibration/failed-attempt1/`) bailed with "sequenceB_1 did not
extract". The extraction (`gunzip -c | tar xf -`) ran under `board_run`'s default 60 s while the SD-card writeback of the staging
copies (3.3 MB to `/mnt`) held the single 50 MHz CPU — the kernel printed `mmc_rescan` blocked > 243 s, a workqueue lockup and an
RCU stall. The original harness has the same code (`run_parsec_session.exp`, `bench_bodytrack_stage`); on 2026-10-01 the tree was
extracted by hand, so this path had probably never run on a fresh boot. Fixed in the campaign's copy only (the original is
untouched): `sync` before the extraction, 600 s for it, 120 s for the CALIB probe, and `-plan bt` skips the 5 MB copy of the
staging directory to the SD card. All before the window and identical in both arms. The checker now judges RCU-stall and
hung-task reports by position (prospective, before any measured window). Not a hardware change.
**Note for anyone running `-plan parsec` with bodytrack from the original harness: the same bail is likely.**

**F2 — the same SD writeback also broke a later staging step; staging now writes nothing to the SD card (2026-10-10).**
The first ON stage (particles 1600) bailed with "bodytrack is the wrong size on the board" although the board had answered
rc=0: the shared lib's `stage_one` copies every pushed file to the SD card as a cache, the writeback held the CPU, and the
kernel's hung-task trace landed inside the board's echo of the size check, so `board_cmd` never matched its echo. 2 of 5 boots
failed in staging this way. Fixed in the campaign's harness copy (`2df816e13265`): for `-plan bt` the cache copies go to RAM;
every other plan keeps `/mnt` (the lib is untouched). The chain was stopped in a settle pause (no boot lost); the one valid
window staged the old way (OFF particles 1600) is superseded and re-run, so both arms of every setting share the same staging.

**F3 — my checker v1 voided a valid window (fixed, prospective).** It required the harness's "sequenceB_1 extracted" line,
which is a `puts` and never reaches the transcript. v2 reads the board's own output (gunzip|tar, then the CALIB probe = 0).
The voided attempt (OFF centre) stays VOID and is re-run; v2 judges it VALID (information only). The host fixtures had
inserted that line by hand, which is why the tests did not catch it.

---

## Build (TASK §6)

| item | done | note |
|---|---|---|
| Harness copy `tools/run_parsec_session.exp` (B1–B5), diff vs `.orig` | yes, 2026-10-09 | base = the **LU + fft copy** (`lu-fft-search-20261009/tools/run_parsec_session.exp`, sha `f84377082331…`, exactly as its chain ran), not the original: it carries the `post_window` hook and the `test -x` fix. 74-line diff (`tools/harness_changes.diff`). B4 is done through `post_window` (`cat bt.out bt.err ; md5sum … ; rm -f …`) instead of editing `measure`. bodytrack's stdout goes to `bt.out` (like fft's `b.out`), so nothing is printed on the console inside the window |
| Checker `tools/check_bt_stage.py` (C1–C4 + all LU checks) | yes | copy of `check_lufft_stage.py` (named for this campaign, not `check_bodytrack_stage.py`). v1 required the `sequenceB_1 extracted (CALIB present)` staging line (F3); **v2** (`3d14d34b20de`, every valid window) proves the extraction from the board's own output. `--md5-mode strict` for every measured window (the calibration showed board = host) |
| Launcher `tools/launch_bt.sh` (policy = LU sweep v4) | yes | copy of `launch_lufft.sh` (per-setting OFF→ON, A1.2) + the v4 passes (A1.4); preflight also refuses a setting with no host-oracle row |
| `results_common.py` additive step (backup `.v2`) | yes | `name_of` + `sort_key` branches for `bt-…` only. Instead of regenerating the other campaigns' `RESULTS.md` (that would rewrite their files), their table sections were computed before and after the edit: **6 of 6 byte-identical** |

Harness `61df12937b45…` as built → `f653972c9c1b…` after fix 1 (F1) → **`2df816e13265…` after fix 2 (F2), every valid window** · base `f84377082331…` · original `b13d93e768b0…` (unchanged) · full shas in `chain_status.txt` at each chain start.

### Host tests

| # | test | result |
|--:|---|---|
| H1 | harness parses | **PASS** — `info complete` → 1 |
| H2 | `-plan bt … -list`, 15 labels and commands as TASK §4 | **PASS** — 15 labels, commands `./bodytrack sequenceB_1 <c> 1 <p> <l> <m> <t> <o> >bt.out 2>bt.err ; RB=$? ; echo BT-RC b=$RB ; test $RB -eq 0` |
| H3 | old plans identical to the original | **PASS** — `-list` output of `parsec`, `lu`, `lupair`, `fft`, `lufft`, `cal`, `sccal` byte-identical between `.orig` and the copy |
| H4 | invalid tokens refused (exit 2, reason) | **PASS** — 12 of 12: c5, c0, p4, p24, l0, m1, m3, t0, o2, a frames token, a leading zero, a short token; and `-plan bt` with no points |
| H5 | checker fixtures | **PASS 17 of 17** (v1; **20 of 20** after fix 3 and checker v2: RCU stall before / in the window, no CALIB probe) — built from the lu-fft campaign's real OFF and ON fft-alone transcripts with the bodytrack parts substituted (`tools/tests/make_fixtures.py`). VALID: OFF, ON, strict mode, md5 mismatch in warn mode. VOID: rc=0 + `Unable to load image`, no frame 0, wrong particles, wrong threads, `Not compiled with`, md5 mismatch strict, no md5, `unhandled signal`, two windows, wrong read-back, no CALIB line, ON judged as OFF, serial text for model 0. First run 16 of 17: the `wrong_readback` fixture flipped the status read before the window, not the window's read-back — a fixture bug, fixed |
| H6 | launcher | **PASS** — preflight refuses 7 of 7 bad stage lists (ON without OFF, duplicate, OFF after ON, no oracle row ×2, bad token, empty); pass logic 5 of 5 on stubs (`tools/tests/test_launcher.sh`: all valid; void OFF deferred with its ON; ON valid on the 3rd attempt; 3 void in a row abort; OFF given up drops its ON) |
| H7 | oracle reproduces every md5 of TASK §4 | **PASS** — 15 of 15 (`tools/oracle_host.tsv`); `staged/` untouched |
| H8 | `make_results.py` | **PASS** — fixture-built campaign: both-arm row, one-arm row shows `not run`, integrity table |

**Stop (b):** not a wait (A1.7); the calibration boot is the board dry run.

---

## Calibration boot (TASK §7) — aged board, **not a measurement**

Attempt 1 void (F1). Attempt 2, 2026-10-10 00:21-01:08, harness `f653972c9c1b`: transcript and CSV in
`clean-runs/bodytrack-sweep-single-core-20261009/calibration/` (`bt_session_20261010-002147.log`, `bt_runs_20261010-002147.csv`);
full table in `calibration/CALIBRATION.md` (made by `tools/cal_table.py`).

| point | board s | memory accesses | reads / writes | hit % |
|---|--:|--:|--:|--:|
| centre `c4p100l3m0t1o1` | 15.0 | 1,334,827 | 916,947 / 417,880 | 84.49 |
| cameras 1 / 2 / 3 | 4.6 / 8.0 / 11.6 | 0.42 / 0.69 / 1.04 M | | 86.40 / 86.05 / 84.87 |
| particles 25 / 400 / 1600 / 4000 | 13.1 / 22.5 / 53.2 / 114.1 | 1.27 / 1.69 / 3.20 / 6.28 M | | 82.24 / 87.22 / 89.74 / 90.46 |
| at 1600: layers 1 / 5 / 10 | 33.0 / 73.8 / 122.8 | 2.34 / 4.86 / 5.35 M | | 88.03 / 88.63 / 92.57 |
| at 1600: threads 2 / 4, serial, no BMP | 55.9 / 56.9 / 57.3 / 49.8 | 3.39 / 4.22 / 3.82 / 2.53 M | | 90.60 / 88.88 / 88.47 / 90.96 |

- 15 of 15 windows OK, rc=0, every output check passes, no SBC activity in any window
- **Board md5 = host oracle for all 15** (poses.txt and, where written, Result0000.bmp) -> measured chain runs `MD5_MODE=strict`
- Board / host time 30-56; longest window 123 s, far below `WORK_TMO` 1800 s
- Centre vs 2026-10-01 (aged board): 15.0 s vs 14.96 s, 1.33 M vs 1.34 M memory accesses, 84.5% vs 84.2% hit
- Staging to the first window took 34 min (the SD-card `sync` before the extraction), so a stage is about 35 min + its window
- The hit rate rises with particles and with layers: TASK §9's E3 premise (SBC pays at high particle counts) looks wrong before any ON window

**Stop (c):** report line only (A1.7); nothing failed.

---

## Predictions (TASK §9)

Fixed in `PLAN.md` on 2026-10-10 01:1x, after the calibration and before the first measured window. Copied here; graded below.
Two later sets were written before their windows (batch 2, 04:3x; Phase 2, 14:3x) and are graded the same way.

| point | OFF hit % (calibration) | predicted change in memory accesses (band) | graded after the run |
|---|--:|---|---|
| centre | 84.5 | inside ±1% (−3% .. +1%) | **right**: −0.41% |
| particles 25 | 82.2 | inside ±1% (−3% .. +1%) | **wrong**: +1.29% (above the band) |
| particles 400 | 87.2 | inside ±1% (−2% .. +1%) | **wrong** on "inside ±1%": −1.15%; inside the band |
| particles 1600 | 89.7 | inside ±1% (−2% .. +1%) | **wrong**: +4.06% |
| particles 4000 | 90.5 | inside ±1% (−2% .. +1%) | **wrong**: +3.73% |
| cameras 1, 2, 3 | 84.9–86.4 | inside ±1% (−2% .. +1%) | **right**: +0.17%, +0.55%, −0.33% |
| Phase 2 (all at C1) | 88.0–92.6 | inside ±1% (−2% .. +1%) | **wrong in 5 of 7**: serial +1.62%, threads 4 +2.38% (above the band); layers 5 −1.89%, threads 2 −1.91%, layers 10 −1.68% (inside the band, outside ±1%). Right: layers 1 +0.14%, no BMP +0.53% |

- "The saving, if any, grows as the OFF hit rate falls (largest at particles 25 or the centre)": **wrong**. The four settings with the lowest OFF hit rate (79–83%) all lost 1.1–1.6%; every saving above 1% came at 87.8–91.4%
- "Reads change more than writes": **right in 17 of 18 settings**; the exception is particles 4000 (writes +6.41%, reads +3.31%)
- "Wrong if: any setting at −3% or better, or +1% or worse": **fired** — 8 settings at +1% or worse (SBC hurting), none at −3% or better
- TASK §9 priors: E1 (centre inside noise) **right** · E2 (cameras 1–3 inside noise) **right** · E3 ("if SBC pays anywhere it is at 1600 / 4000, 0 to −3%") **wrong, opposite sign**: 1600 / 4000 are the only clear changes and both are losses · E4 (layers, threads, serial, BMP inside noise) **wrong for 5 of 7**
- Batch 2 (04:3x, "SBC pays, if at all, at the serial corner, −5% .. +1%; 82–83% settings inside ±1%"): **wrong** — the serial corner +1.56% / +1.23%, the 82–83% settings +1.29% / +1.06%. Its own "wrong if" (the serial corner under 1% → hit rate is not the driver) fired
- Phase 2 (14:3x, "knobs that raise traffic should not move the result by more than 1 point; wrong if any setting at −3% or better or +3% or worse"): the "wrong if" did **not** fire (range −1.91% .. +2.38%); the 1-point claim is **wrong** for serial (2.8 points) and threads 4 (3.5 points)

---

## Phase 1 — centre, cameras, particles (and the hit-rate-first additions)

**Status:** done 2026-10-10 14:16 · 22 of 22 windows valid (11 settings, both arms) · no replicates (A1.3) · tmux `sbc016-bt` · `chain_status.txt`

- **Points:** the 8 of TASK §4, plus 3 added by the user's direction "drive towards low hit rate first" (2026-10-10 03:4x): particles 50, particles 25 with the serial model, and particles 25 with 1 layer, the serial model and 4 threads. These were picked from a **screening boot** (SBC off, 11 points around particles 25, planning only: `screening/SCREENING.md`). Command `./bodytrack sequenceB_1 <c> 1 <p> <l> <m> <t> <o>`. Single-core bitstream sha256 `5ace41250e7c5141…`, PLRU set and read back on every window.
- **Method:** every window is the first and only one after a full reprogram and boot, in both arms. **Each setting SBC off, then SBC on, back to back** (A1.2). Order: centre first, then lowest OFF hit rate first (from 03:52). One run per arm: no spread, no p-values.
- **Gate:** `tools/check_bt_stage.py` v2 judges validity only, with `MD5_MODE=strict` (`poses.txt`, and `Result0000.bmp` where written, must equal the host oracle). Fixtures 20 of 20.
- **Events:**
  - 01:44 OFF centre attempt 1 VOID by checker v1 (F3)
  - 02:51 ON particles 1600 attempt 1 VOID in staging (F2)
  - 02:52 chain stopped in a settle pause; the valid OFF particles 1600 window (staged the old way) superseded
  - 02:53 relaunched under harness `2df816e13265`; from here 36 of 36 stages valid on the first attempt, no kernel watchdog lines
  - 03:52 chain stopped by hand during a boot (no window running) to reorder; 03:52–04:25 screening boot
  - Batches: 2 (04:27–08:20, 8 stages), 3 (08:21–14:16, 12 stages). About 29 min per stage

### Results (one run per arm, both on fresh boots; memory accesses = memReads + memWrites)

| setting | memory accesses OFF | memory accesses ON | change | reads change | writes change | hit % OFF → ON | L2 cycles OFF | L2 cycles ON | cycles change | migrations | secondary hits |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| centre (4 cams, 100 particles, 3 layers) | 1,358,282 | 1,352,680 | **-0.41%** | -0.55% | -0.09% | 84.09 → 84.15 | 752,042,156 | 751,352,084 | -0.09% | 46,980 | 10,982 |
| cameras=1 | 420,068 | 420,787 | **+0.17%** | +0.27% | -0.05% | 86.84 → 86.80 | 230,014,592 | 230,045,699 | +0.01% | 10,423 | 1,755 |
| cameras=2 | 695,778 | 699,632 | **+0.55%** | +0.92% | -0.25% | 86.24 → 86.17 | 404,573,742 | 405,116,947 | +0.13% | 21,058 | 3,952 |
| cameras=3 | 1,039,393 | 1,035,953 | **-0.33%** | -0.42% | -0.13% | 85.15 → 85.17 | 582,314,640 | 581,656,293 | -0.11% | 36,524 | 6,977 |
| particles=25 | 1,268,761 | 1,285,142 | **+1.29%** | +1.78% | +0.27% | 82.54 → 82.33 | 656,223,824 | 658,335,062 | +0.32% | 47,988 | 12,813 |
| particles=50 *(added, hit-rate-first)* | 1,295,234 | 1,308,907 | **+1.06%** | +1.50% | +0.12% | 83.23 → 82.98 | 688,228,172 | 688,859,386 | +0.09% | 48,070 | 12,619 |
| particles=400 | 1,633,960 | 1,615,126 | **-1.15%** | -1.53% | -0.12% | 87.84 → 87.97 | 1,127,449,963 | 1,125,551,487 | -0.17% | 51,942 | 21,113 |
| particles=1600 | 2,719,423 | 2,829,792 | **+4.06%** | +4.60% | +1.74% | 91.55 → 91.17 | 2,643,968,825 | 2,650,901,515 | +0.26% | 51,265 | 29,811 |
| particles=4000 | 5,001,829 | 5,188,556 | **+3.73%** | +3.31% | +6.41% | 92.53 → 92.30 | 5,660,373,638 | 5,669,577,711 | +0.16% | 59,576 | 66,248 |
| particles=25, serial *(added)* | 1,734,603 | 1,755,924 | **+1.23%** | +1.80% | +0.10% | 79.44 → 79.03 | 849,221,681 | 849,517,726 | +0.03% | 100,187 | 15,071 |
| particles=25, 1 layer, serial, 4 threads *(added)* | 1,716,335 | 1,743,151 | **+1.56%** | +2.22% | +0.27% | 79.02 → 78.55 | 832,406,804 | 833,599,238 | +0.14% | 99,874 | 19,419 |

- Same work: `accessA` OFF vs ON within ±0.60% at every setting (largest: particles 25); window lengths within 0.32%. No flagged rows
- `poses.txt` md5 OFF = ON = host oracle at every setting: **yes**
- **Where SBC pays:** nowhere clearly. The only saving outside ±1% is particles 400 (**−1.15%**, small band, reads −1.53%)
- **Where SBC hurts:** particles **1600 (+4.06%)** and **4000 (+3.73%)** — the only clear (> 3%) results of the campaign. At 4000 particles writes rise most (+6.41%)
- **Low hit rate did not help:** all four settings at 79–83% OFF hit lose 1.1–1.6% (reads +1.5% to +2.2%); the serial model doubles the migrations (about 100,000 against 48,000)
- **Cameras:** no effect (−0.33% .. +0.55%)
- **Run time (L2 cycles at 50 MHz):** within ±0.32% everywhere. A DRAM read is about 38 L2 cycles on this board, so cycles move far less than traffic
- **Model, not a measurement** (`tools/report_tables.py`, same model as the LU sweep's `project_dram_latency.py`; blocking L1D, one core, the L2 is the last-level cache, write-backs off the critical path): at 300 cycles per DRAM read, particles 1600 would be **+1.04%** slower, particles 4000 +0.69%, particles 400 −0.46% faster. Table in `results/report_tables.md`
- **Noise:** not measured. No replicates were run (A1.3: the user decides after this report). LU's fresh-boot noise was 0.5–0.9% in memory accesses; bodytrack's may differ (see Phase 2)
- **Predictions vs outcome:** above
- **Provenance:** all 22 valid windows ran harness `2df816e13265`, checker v2 `3d14d34b20de`, `MD5_MODE=strict`, bitstream `5ace41250e7c5141`, no warnings, no failures. 2 void attempts in `failed/`, 2 superseded in `superseded/` (each with a NOTE). Nothing edited outside the campaign folder except the `results_common.py` step (backup `.v2`)

**Stop (d):** not a wait (A1.7). C1 chosen by the rule fixed before the search (A1.1, PLAN.md step 4): the particle count with the largest saving outside 1% = **400** (2026-10-10 14:3x, written before any Phase 2 window).

---

## Phase 2 — layers, threads, serial model, BMP output (around C1 = particles 400)

**Status:** done 2026-10-10 21:22 · 14 of 14 windows valid, all on the first attempt · `batch4_stages.txt` · host oracle rows added first (7 of 7)

| setting | memory accesses OFF | memory accesses ON | change | reads change | writes change | hit % OFF → ON | L2 cycles OFF | L2 cycles ON | cycles change | migrations | secondary hits |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| C1 = particles 400 (reference, from Phase 1) | 1,633,960 | 1,615,126 | **-1.15%** | -1.53% | -0.12% | 87.84 → 87.97 | 1,127,449,963 | 1,125,551,487 | -0.17% | 51,942 | 21,113 |
| layers=1 | 1,445,615 | 1,447,584 | **+0.14%** | +0.31% | -0.29% | 85.83 → 85.79 | 875,780,740 | 876,167,825 | +0.04% | 48,197 | 11,199 |
| layers=5 | 1,838,539 | 1,803,771 | **-1.89%** | -2.02% | -1.51% | 88.92 → 89.14 | 1,377,280,845 | 1,376,214,361 | -0.08% | 51,457 | 11,929 |
| layers=10 | 2,122,514 | 2,086,895 | **-1.68%** | -1.73% | -1.49% | 91.41 → 91.56 | 1,997,150,063 | 1,996,684,427 | -0.02% | 48,337 | 29,148 |
| threads=2 | 1,731,260 | 1,698,270 | **-1.91%** | -2.15% | -1.24% | 88.76 → 89.00 | 1,180,154,630 | 1,178,815,930 | -0.11% | 48,095 | 23,701 |
| threads=4 | 1,891,206 | 1,936,165 | **+2.38%** | +2.95% | +0.82% | 88.08 → 87.76 | 1,195,643,317 | 1,197,986,573 | +0.20% | 44,353 | 14,528 |
| serial model | 2,074,617 | 2,108,315 | **+1.62%** | +2.19% | +0.26% | 86.40 → 86.03 | 1,328,694,797 | 1,328,173,123 | -0.04% | 97,768 | 34,485 |
| no BMP output | 1,044,091 | 1,049,607 | **+0.53%** | +0.59% | +0.33% | 90.23 → 90.18 | 971,623,267 | 972,394,510 | +0.08% | 22,060 | 5,816 |

- `poses.txt` md5 equal to C1's at threads 2, threads 4, serial, no BMP: **yes** (`b7bd89111c07…`, board OFF = ON = host)
- **Where SBC pays:** layers 5 (−1.89%), threads 2 (−1.91%) and layers 10 (−1.68%), each in the small band; at these three, writes also fall (−1.2% to −1.5%), the only write savings of the campaign
- **Where SBC hurts:** threads 4 (+2.38%) and the serial model (+1.62%), small band. Layers 1 and no BMP: no effect
- **Without BMP output** SBC moves half as many lines (22,060) and the OFF hit rate is the highest at 400 (90.2%)
- **Spread at one particle count:** −1.91% .. +2.38% across knobs, one window per arm each. Threads 1 / 2 / 4 give −1.15% / −1.91% / +2.38%, layers 1 / 3 / 5 / 10 give +0.14% / −1.15% / −1.89% / −1.68%: neither is monotonic. **Part of this spread may be bodytrack's own run-to-run noise**, which is not measured; no knob effect here is established
- **Run time:** within ±0.20%. Model at 300 cycles per read: threads 2 −0.56%, layers 5 −0.48%, threads 4 +0.84%
- **Provenance:** as Phase 1 (same harness, checker, mode and bitstream; no warnings, no failures, no void)

**Stop (e) reached:** 2026-10-10 21:22. Step 6 (extra settings toward the optimum) **not started**: the user asked for the results after Phase 2 to decide the repeats (A1.3), and every candidate for step 6 sits in the small band, where one window per arm cannot rank settings.

---

## Per-knob summary (what each parameter does to SBC)

| knob | values | OFF hit rate range | change in memory accesses | band | direction | one line |
|---|---|---|---|---|---|---|
| cameras | 1, 2, 3, 4 (100 particles) | 84.1–86.8% | +0.17, +0.55, −0.33, −0.41% | none | none | no effect |
| particles | 25, 50, 100, 400, 1600, 4000 | 82.5–92.5% | +1.29, +1.06, −0.41, −1.15, **+4.06, +3.73%** | small … clear | loss at both ends, small saving at 400 | **the only knob with a clear effect: SBC costs 4% at 1600–4000** |
| layers | 1, 3, 5, 10 (400 particles) | 85.8–91.4% | +0.14, −1.15, −1.89, −1.68% | none … small | more layers, small saving | small saving from 3 layers up; 1 layer none |
| threads | 1, 2, 4 (400 particles) | 87.8–88.8% | −1.15, −1.91, +2.38% | small | mixed | 2 threads saves, 4 threads loses; not monotonic |
| thread model | pthreads, serial (25 and 400 particles) | 79.4–87.8% | serial: +1.23% (25), +1.62% (400) | small | serial → loss | serial doubles the migrations and loses at both counts |
| BMP output | on, off (400 particles) | 87.8–90.2% | on −1.15%, off +0.53% | none … small | off → none | without the image writer, half the migrations and no effect |

---

## Where the change comes from (counter decomposition)

For equal work (ON scaled to OFF's `accessA`), the change in DRAM reads is exactly minus the change in hits:
**Δreads = −(Δ primary hits + secondary hits)**. Per migration:

| setting | change | primary hits per migration | secondary hits per migration |
|---|--:|--:|--:|
| particles 400, threads 2 | −1.91% | +0.06 | 0.49 |
| particles 400, layers 5 | −1.89% | +0.30 | 0.23 |
| particles 400, layers 10 | −1.68% | −0.03 | 0.60 |
| particles 400 | −1.15% | −0.17 | 0.41 |
| centre | −0.41% | −0.16 | 0.23 |
| particles 25 | +1.29% | −0.47 | 0.27 |
| particles 400, serial | +1.62% | −0.76 | 0.35 |
| particles 400, threads 4 | +2.38% | −1.16 | 0.33 |
| particles 1600 | +4.06% | **−2.49** | 0.58 |
| particles 4000 | +3.73% | **−3.39** | 1.11 |

- **This is bookkeeping, not a test:** it holds by construction in every window. Its use is to show which term moves
- **The losses are lost primary hits.** At 1600 / 4000 particles each migration costs 2.5–3.4 primary hits, against 0.6–1.1 secondary hits. That matches the project's measured rule (`performance/sbc-findings-2026-09-25.md`): outside "overflow ≤ the partner's free ways", migrations flood the partner and evict lines that were hitting there
- **Which sets lose the hits (source or partner) is not measured.** A per-set profile (`SBC_SetSel` / `SBC_SetSat`) would show it
- **The savings come mostly from secondary hits** (0.23–0.60 per migration) with primary hits about unchanged; only layers 5 gains primary hits clearly (+0.30 per migration, the overloaded set thrashing less)

---

## Counter cross-checks (TASK §10)

| check | rows checked | holds? | worst deviation |
|---|--:|---|---|
| `accessA == primary + secondary + dataMiss + upgradeMiss` (±64) | 36 | yes | +0 |
| `memReads + memAcqPerm == dataMiss + upgradeMiss` | 36 | yes, exact | +0 |
| OFF: `memReads − memWrites − memRelClean` (fills without an eviction) ±64 | 18 | yes | +0 |
| ON: fills without an eviction ÷ migrations (015 F10: about 98%) | 18 | 97.3% .. 98.8% | |
| OFF: no SBC activity (migrations, secondary hits) | 18 | yes | |
| `accessA` OFF vs ON within 1% | 18 | yes | +0.60% |
| `poses.txt` md5 OFF = ON = host oracle | 18 | yes | |

---

## All log and CSV paths

A bare `clean-runs/…` path is relative to `chipyard/scripts/logs/`. Each CSV sits next to its transcript.

| stage | transcript | CSV |
|---|---|---|
| calibration attempt 1 (VOID, F1) | `clean-runs/bodytrack-sweep-single-core-20261009/calibration/failed-attempt1/` | same folder |
| calibration attempt 2 | `clean-runs/bodytrack-sweep-single-core-20261009/calibration/bt_session_20261010-002147.log` | `bt_runs_20261010-002147.csv` |
| screening boot (planning only) | `clean-runs/bodytrack-sweep-single-core-20261009/screening/bt_session_20261010-035253.log` | `bt_runs_20261010-035253.csv` |
| void attempts | `clean-runs/bodytrack-sweep-single-core-20261009/failed/` (2, each with its logs) | |
| superseded | `clean-runs/bodytrack-sweep-single-core-20261009/superseded/` (2, each with a NOTE) | |

The 36 valid windows, in run order:

| # | SBC | setting | verdict | transcript | CSV |
|--:|---|---|---|---|---|
| 1 | off | 100 particles | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c4-p100-l3-m0-t1-o1-plru/bt_session_20261010-025353.log` | `bt_runs_20261010-025353.csv` |
| 2 | on | 100 particles | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c4-p100-l3-m0-t1-o1-plru/bt_session_20261010-032316.log` | `bt_runs_20261010-032316.csv` |
| 3 | off | 25 particles, 1 layer, serial, 4 threads | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c4-p25-l1-m4-t4-o1-plru/bt_session_20261010-042708.log` | `bt_runs_20261010-042708.csv` |
| 4 | on | 25 particles, 1 layer, serial, 4 threads | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c4-p25-l1-m4-t4-o1-plru/bt_session_20261010-045618.log` | `bt_runs_20261010-045618.csv` |
| 5 | off | 25 particles, serial | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c4-p25-l3-m4-t1-o1-plru/bt_session_20261010-052526.log` | `bt_runs_20261010-052526.csv` |
| 6 | on | 25 particles, serial | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c4-p25-l3-m4-t1-o1-plru/bt_session_20261010-055446.log` | `bt_runs_20261010-055446.csv` |
| 7 | off | 25 particles | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c4-p25-l3-m0-t1-o1-plru/bt_session_20261010-062415.log` | `bt_runs_20261010-062415.csv` |
| 8 | on | 25 particles | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c4-p25-l3-m0-t1-o1-plru/bt_session_20261010-065322.log` | `bt_runs_20261010-065322.csv` |
| 9 | off | 50 particles | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c4-p50-l3-m0-t1-o1-plru/bt_session_20261010-072230.log` | `bt_runs_20261010-072230.csv` |
| 10 | on | 50 particles | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c4-p50-l3-m0-t1-o1-plru/bt_session_20261010-075146.log` | `bt_runs_20261010-075146.csv` |
| 11 | off | 100 particles, 3 cameras | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c3-p100-l3-m0-t1-o1-plru/bt_session_20261010-082141.log` | `bt_runs_20261010-082141.csv` |
| 12 | on | 100 particles, 3 cameras | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c3-p100-l3-m0-t1-o1-plru/bt_session_20261010-085052.log` | `bt_runs_20261010-085052.csv` |
| 13 | off | 100 particles, 2 cameras | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c2-p100-l3-m0-t1-o1-plru/bt_session_20261010-091957.log` | `bt_runs_20261010-091957.csv` |
| 14 | on | 100 particles, 2 cameras | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c2-p100-l3-m0-t1-o1-plru/bt_session_20261010-094910.log` | `bt_runs_20261010-094910.csv` |
| 15 | off | 100 particles, 1 camera | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c1-p100-l3-m0-t1-o1-plru/bt_session_20261010-101812.log` | `bt_runs_20261010-101812.csv` |
| 16 | on | 100 particles, 1 camera | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c1-p100-l3-m0-t1-o1-plru/bt_session_20261010-104719.log` | `bt_runs_20261010-104719.csv` |
| 17 | off | 400 particles | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c4-p400-l3-m0-t1-o1-plru/bt_session_20261010-111626.log` | `bt_runs_20261010-111626.csv` |
| 18 | on | 400 particles | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c4-p400-l3-m0-t1-o1-plru/bt_session_20261010-114539.log` | `bt_runs_20261010-114539.csv` |
| 19 | off | 1600 particles | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c4-p1600-l3-m0-t1-o1-plru/bt_session_20261010-121508.log` | `bt_runs_20261010-121508.csv` |
| 20 | on | 1600 particles | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c4-p1600-l3-m0-t1-o1-plru/bt_session_20261010-124503.log` | `bt_runs_20261010-124503.csv` |
| 21 | off | 4000 particles | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c4-p4000-l3-m0-t1-o1-plru/bt_session_20261010-131500.log` | `bt_runs_20261010-131500.csv` |
| 22 | on | 4000 particles | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c4-p4000-l3-m0-t1-o1-plru/bt_session_20261010-134559.log` | `bt_runs_20261010-134559.csv` |
| 23 | off | 400 particles, serial | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c4-p400-l3-m4-t1-o1-plru/bt_session_20261010-143009.log` | `bt_runs_20261010-143009.csv` |
| 24 | on | 400 particles, serial | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c4-p400-l3-m4-t1-o1-plru/bt_session_20261010-145930.log` | `bt_runs_20261010-145930.csv` |
| 25 | off | 400 particles, 1 layer | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c4-p400-l1-m0-t1-o1-plru/bt_session_20261010-152848.log` | `bt_runs_20261010-152848.csv` |
| 26 | on | 400 particles, 1 layer | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c4-p400-l1-m0-t1-o1-plru/bt_session_20261010-155757.log` | `bt_runs_20261010-155757.csv` |
| 27 | off | 400 particles, 5 layers | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c4-p400-l5-m0-t1-o1-plru/bt_session_20261010-162707.log` | `bt_runs_20261010-162707.csv` |
| 28 | on | 400 particles, 5 layers | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c4-p400-l5-m0-t1-o1-plru/bt_session_20261010-165627.log` | `bt_runs_20261010-165627.csv` |
| 29 | off | 400 particles, 4 threads | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c4-p400-l3-m0-t4-o1-plru/bt_session_20261010-172606.log` | `bt_runs_20261010-172606.csv` |
| 30 | on | 400 particles, 4 threads | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c4-p400-l3-m0-t4-o1-plru/bt_session_20261010-175541.log` | `bt_runs_20261010-175541.csv` |
| 31 | off | 400 particles, 2 threads | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c4-p400-l3-m0-t2-o1-plru/bt_session_20261010-182506.log` | `bt_runs_20261010-182506.csv` |
| 32 | on | 400 particles, 2 threads | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c4-p400-l3-m0-t2-o1-plru/bt_session_20261010-185442.log` | `bt_runs_20261010-185442.csv` |
| 33 | off | 400 particles, no BMP | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c4-p400-l3-m0-t1-o0-plru/bt_session_20261010-192417.log` | `bt_runs_20261010-192417.csv` |
| 34 | on | 400 particles, no BMP | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c4-p400-l3-m0-t1-o0-plru/bt_session_20261010-195350.log` | `bt_runs_20261010-195350.csv` |
| 35 | off | 400 particles, 10 layers | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/off/bt-c4-p400-l10-m0-t1-o1-plru/bt_session_20261010-202312.log` | `bt_runs_20261010-202312.csv` |
| 36 | on | 400 particles, 10 layers | VALID | `clean-runs/bodytrack-sweep-single-core-20261009/on/bt-c4-p400-l10-m0-t1-o1-plru/bt_session_20261010-205255.log` | `bt_runs_20261010-205255.csv` |

---

## Verdict — the §1 question

**SBC does not reduce bodytrack's memory traffic at any setting tested; at the centre it changes nothing (−0.41%), and
at high particle counts it clearly makes it worse.** The one parameter with a clear effect is the particle count: at 1600
and 4000 particles SBC adds 4.1% and 3.7% to memory accesses, because each migration costs 2.5–3.4 primary hits and returns
0.6–1.1 secondary hits. Small savings of 1.2–1.9% appear only around 400 particles (with 3–10 layers or 2 threads), and
small losses of 1.1–2.4% at the low-hit end (25–50 particles, the serial model, 4 threads); each of these is a single window
per arm in the 1–3% band, and the spread across knobs at 400 particles (−1.9% .. +2.4%) is as large as the effects, so
none is established without repeats. Cameras, 1 layer and the BMP output do nothing. A low hit rate did not predict a
saving (79–83% hit lost; LU at 75% saved 12.9%): what matters is how the extra lines fit the partner sets, which the hit rate
does not show. This fits the project's rule that SBC pays only while a hot set's overflow fits the empty ways of its
partner: bodytrack's losses are lost primary hits, the signature of migrations flooding a partner. The data do not show
which sets lose those hits. Run time moved by 0.3% or less on the board. **Not tested:** repeats (bodytrack's own noise),
more than one frame, the other input sequences, multi-core, and any hardware change.
