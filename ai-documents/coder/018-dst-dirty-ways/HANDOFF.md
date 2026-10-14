# Task 018 — HANDOFF (rewritten 2026-10-15 02:01, for a fresh session)

> **TASK CLOSED 2026-10-15.** Nothing in 018 is pending. What follows is the record; the follow-ups at the end are optional and the user's call.

Read this first, then `REPORT.md` (details, evidence paths), then `TASK.md` (the work order; never edit it).
Paths: `CY=/home/damith/Research/repos/chipyard_performance_eval/chipyard`, `L2=$CY/generators/rocket-chip-inclusive-cache`,
`C1=$CY/scripts/logs/clean-runs/lu-dstmode-single-core-512k-20261013` (first campaign, stopped),
`C2=$CY/scripts/logs/clean-runs/lu-sweep-dstmode1-single-core-512k-20261013` (second campaign, running), `SWD=$L2/sw`.

## What this task is, in one paragraph
Add a runtime register `SBC_DstMode` (0x4B0) so a migration may overwrite a **dirty way that no L1 holds** (0 = today,
1 = clean first then dirty, 2 = least-recent of either; held ways are never offered, no probe) plus a counter
`SBC_DstRescue` (0x4B8). Measured on the 512 KB 8-way L2 + 32 KB 8-way L1 image with LU. **User decision 2026-10-13: setting 1
is the setting for our tests.**

## State right now (2026-10-15 02:01)
| item | state |
|---|---|
| RTL + sw + configs + gate script | **COMMITTED 2026-10-15** (user: "commit them appropriately"). L2 repo: RTL `494ab5e`, sw `766a0fb`, `l2_miss_calib` `6b4ee42`, docs (the commit the tag points at); `sbc-018-dst-dirty-ways` merged fast-forward into its parent `sbc-013-sat-counter` (branch kept); annotated tag `sbc-018-dstmode-512k-n4096-b128-best-2026-10-15`. Chipyard repo (`eval_ssbc`): the config classes, the submodule pointer and the campaign tools committed, same tag. Not pushed (no remote action asked) |
| sim gate | G0, g1–g6b all PASS. I1–I4 PASS. **I5 fails for g5 (F4)**: setting 2 had 30 held aborts vs 5 for setting 0 in the stock stress test. **Not seen on the board** (m2 held aborts below m0's at all three points) |
| image | `fpga/bitstream_storage/FPGASingleRocketVCU118L132K512K8WL2ConfigSBCPLRUDst-512KB-8way-L1-32K8W-dst018-494ab5e-best-n4096-b128-2026-10-13.bit` (renamed 2026-10-15 from `…-dst018-2026-10-13`; marked best for 512 KB n=4096 b=128), sha256 `502649e23f77839b…` (full in REPORT), WNS +0.526 ns |
| first campaign `C1` | **STOPPED by the user 2026-10-13 08:39:24** (staging of stage 13). 12 valid windows: n512 b128, n1024 b64, n384 b128 × off/m0/m1/m2. Partial attempt in `C1/aborted/`. Results: `C1/RESULTS.md`, REPORT "Results" |
| calib | run 1 unusable (F5). The corrected sweep (tmux `dst018c2`) was cancelled with the first campaign and is **not run**. Needs the user's go; method in `C1/tools/drive_018_calib2.sh` (handover + `run_calib_sweep_018.exp`; needs the board free) |
| second campaign `C2` | **FINISHED 2026-10-13 18:44, 18 of 18 valid, none void** (tmux `dst1sweep`, now an idle shell). LU b 64/128/256 × n 512/1024/2048, off vs `--dstmode=1`. Setting 1 pays at b128 only: **−5.7 / −4.6 / −10.7%** at n512 / n1024 / n2048; b64 +0.0..−1.4%; b256 −0.3..−0.5%. Tables: `C2/RESULTS.md`; grading in REPORT |
| extra rounds (user, 21:49) | **FINISHED 2026-10-14 05:20:55, rc=0, 4 of 4 valid** (tmux `dst1extra`, idle shell; `C2/tools/drive_rounds.sh`, launcher `launch_dst_sweep_v2.sh`). **r2** n2048 b128 (in `C2/r2/`): **−9.68%** (round 1 −10.75%). **n4096 b128** (in the `C2` root): **−20.88%** memory accesses (reads −21.04%, writes −20.68%), hit 72.9 → 78.6%, held share 50.3%, windows 9,369 / 9,245 s, L2 cycles −1.33%. Graded in REPORT |
| n4096 b128 replicate (user, 11:05: "please replicate") | **FINISHED 2026-10-14 17:07:22, rc=0, 2 of 2 valid** (tmux `dst1rep`, idle; `C2/tools/drive_rep4096.sh`; folder `C2/r2/`). **−21.11%** (round 1 −20.88%), off +0.05%, on −0.24% vs round 1; all 5 frozen predictions right. No run is active; board is free |
| n3072 b128 pair (user, 22:57: "let's do the n=3072") | **FINISHED 2026-10-15 01:59:36, rc=0, 2 of 2 valid** (tmux `dst1n3072`, idle; `C2/tools/drive_n3072.sh`, launcher `launch_dst_sweep_v3.sh` = v2 + `-tmo 10800` for 3072 ≤ n < 4096; root folder). **−15.74%** memory accesses (reads −15.94%, writes −15.51%), hit 75.2 → 79.1%, held share 45.6%, windows 3,935 / 3,899 s, L2 cycles −0.90%. 6 of 6 frozen predictions right; reading rule: size reading supported, stride-aliasing guess rejected. No run is active; board is free |

## Monitoring
- Driver: `C2/driver_status.txt`. Chains: `C2/r2/chain_status.txt` (replicate round) then `C2/chain_status.txt` (STAGE-START/LOG/OK/VOID/DEFERRED/RETRY/GAVE-UP, CHAIN FINISHED).
  Each stage: session log `scripts/logs/lu_session_*.log`, CSV `scripts/logs/lu_runs_*.csv`; valid results move to
  `C2/<off|on>/<label>/` (stage_result.json, stage_check.txt); void attempts to `C2/failed/`.
- Session logs hold binary bytes: `grep -a`, `tr -d '\000'`, never `cat`.
- **Never kill a tmux session while a window runs** (the harness dies, the boot is lost). If asked to stop: during a boot or
  staging; method = `kill -TERM -<driver pgid>`, then check `fuser /dev/ttyUSB2`; file the partial files under `C2/aborted/` with a NOTE.txt.
  Never run another harness, programFPGA, or picocom while the chain runs.
- n=2048 windows take ≈ 19–22 min of measurement (LU work ∝ n³, n1024 = 146 s); n=4096 ≈ 2.6 h. The launcher passes `-tmo 3600` (n ≥ 2048) and `-tmo 21600` (n ≥ 4096, v2).
- A `STAGE-VOID` is retried by the launcher itself (pass 2). Three failures in a row = `CHAIN ABORT`: stop, read `stage_check.txt`, ask.

## What was done when the extra rounds ended (kept as the checklist)
1. `python3 $C2/tools/make_results.py $C2` (the driver runs it at the end) → `C2/RESULTS.md` (now with a "Replicate rounds" table), `C2/results/*`. RESULTS.md = results only.
2. Grade the PLAN.md addendum 21:48 predictions (frozen; never edit them): r2 within ±2.5 pp of −10.7%, n4096 b128 band −16..−4%, held share, window length. The first 9-point grading is already in REPORT (8 of 9 bands, 7 of 7 sweep-level; n2048 b128 wrong, better). r2 is the independent estimate for n2048 b128 (winner's curse).
3. Hit balance is an identity: at equal work Δreads = −(Δprimary hits + secondary hits); say which term moved.
4. Memory accesses = memReads + memWrites, reads and writes **separately**; board cycles understate DRAM (~38 L2 cycles per read).
5. The windows at n512 b128 and n1024 b64 repeat the first campaign's off and m1 arms: report the gaps (table in RESULTS.md); never retry.
6. Verdict 5–8 plain lines (REPORT "Verdict"): where setting 1 pays, at what write cost, what one window per arm does not establish.
7. Ask the user about: the corrected calib sweep, the LU data check, n4096 b64 / b256 (commit done 2026-10-15).

## Rules that bit me / user preferences
- Simple English, point form, compact tables. RESULTS.md = results only; answer questions in chat.
- Never overwrite `$SWD/build/sbc_read` (sha must stay `8241eb8b8b7e910b…`); 018 binaries are `$SWD/build/018/{sbc_read,l2_miss_calib}`
  (copies of `sbc_read_018` `b103216d…` and `l2_miss_calib_018` `35854641…`).
- Never edit the original harness/lib/earlier campaign folders/TASK.md. Never `git checkout/restore/reset --hard/clean/stash`.
- Never pass `-allow-*`, never `--reset-all` in a window. Never retry to get a nicer number.
- Dry-run any new command shape (`LU_DRYRUN=1`, `-list`); do not `source` the harness in expect with no args (near-miss in REPORT).
- Long jobs run in tmux; give the user the log paths the moment a run starts.
- Test-binary shas: whole-file sha is not reproducible (random temp object name); compare `objcopy -O binary` bytes
  (stress `0c10d0f9…`, switch `682bfca1…`) — F1.
- The user's request said "16 combinations" for a 3 × 3 grid: I ran all 9 points (18 windows) and said so in PLAN.md. n4096 b256 was offered and the user did not want it ("that's it").
- `C1/on/` is a folder of m1 copies made at 08:28 by something other than my tools; my tools ignore it. Leave it.
- Task 017's folder is READ ONLY; its tmux sessions `lu512`, `lu512drv` stay open (never kill).

## What is left (nothing in 018 itself; follow-ups, the user's call — full table in REPORT "What is left to do")
1. `lu_ncb -t` data check with SBC on (setting 1) at n1024 or n2048 — LU output is not checked in any window (exit code only)
2. Setting 0 (and 2) at n4096 b128, one window each (~3 h each) — "best" is against SBC off; this attributes the gain to setting 1
3. Per-set profile of n4096 b128, off and on (`sw/sat_snap.c`, `SBC_SetSel` / `SBC_SetSat`) — tests the "uneven sets" explanation
4. n4096 b64 and b256 pairs (~6 h per pair); n3072 b64 / b256 if the n4096 ones are interesting
5. Corrected `l2_miss_calib` sweep (`-l1sets 64 -l1ways 8`, ~1 h) — predictions 6 and 7 of the first campaign are ungraded
6. Setting 1 on other workloads (bodytrack 1600 / 4000, LU + FFT, LU pairs, dual core)
7. Push the L2 branch and the tag `sbc-018-dstmode-512k-n4096-b128-best-2026-10-15` (nothing is pushed; `origin/sbc-013-sat-counter` is still at `b46a8ac`)
- Bodytrack 1600 on this image: propose in the report only (TASK §13 q4); not in scope
- Replicates (done): n512 b128 and n1024 b64 within 0.6%, n2048 b128 1.1 pp, n4096 b128 0.23 pp — the 2.7 pp "spread" was between tasks and images
- New changes after tag `sbc-018-dstmode-512k-n4096-b128-best-2026-10-15` are uncommitted until the user says
