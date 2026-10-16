# Coder report 020 — SBC obeys the TileLink write-back rules

## ▶ SUMMARY FOR A HUMAN (kept current; the comprehensive record starts at "RESUME HERE" below)

**Last updated: 2026-10-16 17:00. TASK CLOSED** (user decision Q8). The final RTL is commit `e2a71b3` (C5) on branch
`sbc-020-tilelink-merge`, not pushed. F3, an older bug found on the way, moves to its own task.

**The problem.** On the two-core chip with SBC on, the board froze within seconds. A core's write-back of a *parked* line had to wait
for the partner row, while the cache slot holding that row was waiting for the same write-back (directly, or through a probe answer the
core holds back until its write-back is acknowledged). TileLink says a write-back must always be accepted, answered and merged.

**The fix.** Six commits, C0 to C5. C0 adds simulation-only checks and watchdogs. C1 to C3 make the merge correct: a probe answer reaches
only the slot waiting for it, a write-back merges into every slot that holds its line, and two same-cycle windows are closed. C4 removes
the two waits that froze the board. C5 fixes F5, a double grant that C4 itself introduced (the test matrix caught it). No SBC decision
changed.

**Results on the final RTL (measured).**

| Check | Result |
|---|---|
| Board: the freeze patterns from task 019, fresh boot | 4 of 4 rungs OK (7 of 7 earlier on the C4 image). They froze the old image in 2 to 6 s |
| Board: LU's output with SBC on (first data check ever) | Equal to the host's reference, through 7.0 million migrations |
| Board: single-core LU n2048 b128, SBC on vs off | **−9.5% memory accesses** (task 018: −10.7% / −9.7%). Performance did not drop |
| Board: two-thread LU n2048 b128, SBC on vs off | **−8.0% on average** over 4 on and 3 off windows; single pairs ranged −3% to −13% |
| Simulation: two-core test matrix (66 runs) | 65 pass with data OK; the 1 failure is F3 (below) |
| Simulation: the 4 programs that froze 5 of 5 times | All pass, 200,000 iterations, data OK |
| Simulation: coverage of the new code paths | All 8 fired; longest write-back wait 23 cycles (watchdog limit 4,000) |
| Simulation: single-core regression vs the pre-020 baseline | 24 of 24 identical in every cycle and counter, SBC on and off |
| FPGA images | dual `32ebbd03…` (+0.425 ns), single `0f02bc92…` (+0.646 ns); timing met, no combinational loop |

**Open, not part of 020.**
- **F3 (older than 020, moved to its own task).** During a migration whose destination way is dirty, the memory refill can overwrite
  the victim line before the copy reads it. That would silently corrupt one parked line. Seen twice in simulation, once with
  setting 1 (the board setting); it is also in the task 018 and 019 images. Candidate fix `021b2d9` passes both cases and costs about
  18 cycles per migration (about +0.35% run time on LU).
- **Two-thread LU varies from run to run.** One off/on pair can show anything from −3% to −13%; use three or more windows per arm.
  Why is not tested (my guess: where Linux places the pages decides how evenly the matrix spreads over the cache sets).
- B8-1 (watched, as asked): no sign of it in any of the 78 two-core simulation runs.

**Waiting for the user.** Commit the docs and this folder; the 020 status lines in `CLAUDE.md` and `ai-documents/coder/README.md`;
push and tag; delete the throwaway branch `sbc-020-debug-tlmon`. Task 019 may resume its SBC-on dual-core points on the C5 image.

## ▶ RESUME HERE (technical handoff, updated 2026-10-16 17:00, TASK CLOSED — a fresh session can continue from this file alone)

Paths: `$CY = /home/damith/Research/repos/chipyard_performance_eval/chipyard`, `$L2 = $CY/generators/rocket-chip-inclusive-cache`,
`$NEW = $CY/scripts/logs/clean-runs/tilelink-merge-020-20261015` (all runs, tools, simulators, board results of this task).

### Where the work stands

| Item | State |
|---|---|
| RTL | **Done.** Branch `sbc-020-tilelink-merge`, HEAD **`e2a71b3` (C5)**, `design/` clean. C0 `1392362`, C1 `c3320f1`, C2 `386112b`, C3 `a09155c`, C4 `af36949`, C5 `e2a71b3` |
| Freeze on the board | **Gone** on the af36949 images (V6: 7/7 hunt rungs, LU p2 n2048 b128 completed 3×, −7.7% / −8.1% memory accesses) |
| Single-core performance | **Unchanged** on the af36949 image (V7: −9.9% vs task 018's −10.7% / −9.7%) |
| F5 (020 bug, found by V2) | **Fixed in C5**, the failing run passes on C5 |
| F3 (older bug, not 020) | Open. Candidate fix on branch `sbc-021-copy-refill-gate-candidate` (`021b2d9`), passes the failing run, costs ~18 L2 cycles per migration. **User decision** |
| C5 images | **Built:** dual `32ebbd03…` (+0.425 ns), single `0f02bc92…` (+0.646 ns), no LUTLP; archived as `…-sbc-020-dualcore-tilelink-merge-512k-2026-10-16.*` (was `…-tl020c5-e2a71b3-2026-10-16.*`) |
| Board checks on C5 | **All done and passed:** A hunt 4/4 OK; B LU data check PASS (diff = host reference); C dual LU −13.25%, replicate −2.87% (mean −8.2%, = C4); D single LU −9.49% (V7 pass) |
| V2 on C5 | **Done: 65/66 PASS**, the 1 FAIL = F3 (same cycle as C4); 52 runs identical to C4 |
| V4b on C5 | **Done: 4/4 PASS**, 0 asserts, cycles identical to V2 |
| V3 on C5 | **Done:** 8/8 data OK, all 8 wanted paths fired; F3 seen again (setting 1, unshared) |
| V4 on C5 | **Done: 24/24 identical** to the 494ab5e baseline, 0 asserts |
| Task | **CLOSED 2026-10-16 17:00** (Q8). Nothing is running. F3 → its own task |

### Running at handoff — NOTHING (all finished by 16:54; my idle tmux sessions closed 17:00). Table kept as a record

| What | How to check | Notes |
|---|---|---|
| FPGA builds from C5, tmux `t020_fpga_c5` | `grep -a "^######## build" $NEW/gates/fpga_builds_c5.log`; `ls $CY/fpga/bitstream_storage \| grep sbc-020-dualcore` (was `tl020c5`) | dual started 10:09 (~60 min), then single (~45 min). Check timing (`<image>.reports/timing.txt`, WNS) and `grep -c LUTLP <image>.reports/drc.txt` = 0, as for the af36949 images |
| Board chain C5, tmux `board020c5`, script `$NEW/tools/board_chain_c5.sh` | `grep -a "^######## " $NEW/gates/board_chain_c5.log` | A hunt boot (console `$NEW/board/console_c5_boot1.txt`, summary in `$NEW/board/runs/`), B LU data check (`$NEW/board/lucheck_c5/console.txt`), C dual LU off/on (`$NEW/board/lu/c5/RESULTS.md`), D single LU off/on = V7 on C5 (`$NEW/board/lu1/c5/RESULTS.md`). About 4–5 h |
| (finished 16:15) Dual LU replicate on C5, tmux `board020c5r2`, script `$NEW/tools/board_chain_c5_r2.sh` | `grep -a "^######## " $NEW/gates/board_chain_c5r2.log`; `$NEW/board/lu/c5r2/chain_status.txt` | waits for `BOARD-CHAIN-C5-EXIT`, then dual LU off + on, fresh boot each (~75 min). Why: C5 pair −13.25% vs C4 −7.7/−8.1%, and B (same image) 8.6% above C's on window |
| Post-V2 chain, tmux `t020_post`, script `$NEW/tools/post_v2_chain_c5.sh` (started 10:27) | `grep -a "^######## " $NEW/gates/post_v2_chain_c5.log` | waits for V2 c5 to finish, then V4b (4 freeze programs, `$NEW/sim/v4b_c5_*.log`), V3 (`$NEW/sim/v3_c5_*.{log,tags}`), then waits for `BOTH-BUILDS-EXIT` and runs V4 = `run_v4_gates_c5.sh` (labels `013-020v4c5`, `020-v4c5-g*`; driver `$NEW/gates/v4_c5_driver.log`; compare `python3 $NEW/tools/v4_compare.py v4c5`) |
| V2 on C5, `v2_queue.sh c5` (setsid, `JOBS=7`, simulator `$NEW/bin-c5/simulator-stop-t3`) | `python3 $NEW/tools/v2_summary.py c5`; `$NEW/gates/v2_c5_driver.log`; per run `$NEW/sim/v2_c5_<name>.log` | order: PLRU seeds 1–3, random seed 1, PLRU seeds 4–5, random seeds 2–5, controls, freeze programs. ~5–6 h with Vivado running |

Finished tmux sessions that are only idle shells (do not need anything): `t020_base`, `t020_c0`…`t020_c4`, `t020_c4g3`, `t020_c4run`,
`t020_c5g3`, `t020_fpga`, `board020`, `hunt020_1`, `hunt020_2`. Do not kill tmux sessions of other tasks.

### Next steps (task closed 2026-10-16 17:00; only the user's items are left)

Everything planned for 020 ran and is recorded: V2 (65/66, the 1 = F3), V3, V4 (24/24 identical), V4b, both C5 images, board A, B, C,
the replicate R, and D. Nothing is running.

1. **User decisions, open:**
   - commit this task folder (`ai-documents/coder/020-tilelink-release-merge/`, untracked), `ai-documents/guides/priority-orders.md`
     and `ai-documents/bugs/bug-fix-log.md` (it also holds someone else's older uncommitted hunk about B7-2 at 512 KB; only the 020
     section and two index lines are mine);
   - the 020 status line in `CLAUDE.md` and in `ai-documents/coder/README.md` (TASK §13: ask before editing either);
   - push the branch `sbc-020-tilelink-merge` and tag the C5 images;
   - delete the throwaway branch `sbc-020-debug-tlmon` (debug prints only, F5 is closed).
2. **F3 as its own task** (user Q8). Start from `021b2d9` on `sbc-021-copy-refill-gate-candidate` (cut from C4): re-apply on `e2a71b3`,
   rebuild, rerun the matrix (both F3 programs must pass: `v2_s1_d2_p1_n4`, `v3_s0_d1_p1_n18`), images, board checks. Expect cycles to
   move (about 18 cycles per migration), so the single-core regression criterion becomes "all pass, counts close", not "identical".
3. **Optional (not started):** why two-thread LU varies run to run — three or more windows per arm, a physical-page record from
   `/proc/<pid>/pagemap`, or 2 MB huge pages for LU (see "R").

### Git state and rules

- Branches I made: `sbc-020-tilelink-merge` (the work), `sbc-021-copy-refill-gate-candidate` (`021b2d9`, from `af36949`, F4, not
  shipped), `sbc-020-debug-tlmon` (throwaway). Nothing pushed.
- Uncommitted by me: `ai-documents/guides/priority-orders.md`, `ai-documents/bugs/bug-fix-log.md`, this folder (untracked).
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Add files by name only. Never `git checkout <file>`,
  `restore`, `reset --hard`, `clean`, `stash`, `add -A` (TASK rule 5).

### Pitfalls learned tonight (do not repeat)

- **Never edit `design/` while a `make`-based gate (run_020_gate.sh, run_013_gate.sh, run_v4_gates.sh) runs:** the next stage rebuilds
  from the tree. If a file is touched by mistake, restore the content (`git show HEAD:<path> > <path>`) and its old mtime (`touch -r`).
  The two-core simulators used by the runs are copies in `$NEW/bin-*/` and are safe.
- **`pgrep -f` / `pkill -f` from the Bash tool match their own shell** (exit 144 killed my shell once): anchor the pattern, e.g.
  `pgrep -f "^$NEW/bin-c5/simulator"`.
- **`ps` under tmux truncates command lines** to the terminal width; use `pgrep -f` or `ps -eww`.
- **Killing a tmux session kills the runs inside it** (lost 8 V2 runs once). Stop a queue by killing its script PID, not its session.
- **6-thread simulators crawl when the machine is oversubscribed;** 3-thread builds are as fast alone (60 s vs 58 s for 16,000 steps)
  and identical in output, so 8 run at once.
- **The LU harness accepts only labels from its plan:** `-bench lu-p2-n2048-b128-plru` with `-lucheck`, not a new label.
- **A `$stop`-ing assert stops the simulator before some printf lines of the same cycle are flushed;** print the suspect condition
  itself (as the F5 debug did) rather than rely on the last line of a trace.

## Decisions (user answers to §7, and any decision I made without the user, numbered)

User answers, 2026-10-15 (all "yes" = my default):

| # | Question | Answer |
|---|---|---|
| Q1 | Scope: all of F1a–F1f, F2, F3 together | **yes** |
| Q2 | F1e as a one-cycle hold of the directory write | **yes, for now. Optimize later if possible.** (A later option is the merge-into-the-arriving-result form; do not build both now.) |
| Q3 | New branch `sbc-020-tilelink-merge`, one commit per step C0–C4, files added by name | **yes** |
| Q4 | V2 full matrix (60 runs, about 4 hours) | **yes** |
| Q5 | Board depth: V6 + V7 at n2048 b128, without n4096 | **yes** |
| Q6 | B8-1 stays out of 020 | **yes, but keep an eye on it.** Watch for it in the V2 runs and report any sign of it. |
| Q7 | (2026-10-16 ~15:45, after F3 was seen a second time with setting 1) Ship the F3 fix? | **yes, but only after the runs then in flight finish** (the replicate dual LU pair on the board and V4 on C5): "wait till current runs finishes. then do the f3 fix. otherwise this will complicate the process. let me know once all these runs finished". So the C5 results stay a clean record of C5, and the fix gets its own step (C6) and its own full round of checks. |
| Q8 | (2026-10-16 ~16:45, superseding Q7's "C6") Where does the F3 fix go? | **"wrap this task and fix f3 as a new task. so let's wait till regression finishes. then wrap this up."** Task 020 closes on C5 (`e2a71b3`); F3 becomes its own task. No C6 in 020. |

My own decisions (no user input needed; recorded so they can be questioned):

- **M1.** The task's step 9.5 says to run `run_013_gate.sh` once, but gives no label. The labels `base`, `c0`..`c3` already hold task
  013's own logs in `sw/verilator_logs/`, so I run it with the label `020base` (folders `*_013-020base`). Nothing old is overwritten.
- **M2.** `run_013_gate.sh` always runs `make clean && rm -rf output/*` first. Before that I copied the two two-core simulators
  to `$NEW/bin-494ab5e/` (step 9.4) and also the 14 MB `make run-binary` output folder of task 019
  (`$NEW/bin-494ab5e/make-output-DualDst-019`). The `.out` in it was already in the hunt folder at the same size.
- **M4.** (2026-10-16 ~00:20, user message) The user will be away overnight and asked: continue until there is a solution,
  document what and why, keep a human-readable summary at the top, and **once the simulation level passed, continue to board
  testing as well**. I read this as authority to pass stops (c), (d) and (e) when every check is green. A failed check is still a
  stop for that line of work (TASK rule 14); the board is used only when TASK rule 6's two commands print nothing.
- **M5.** C0 to C4 each ran both two-core simulators, the freeze programs and g3 before the next step (TASK §10). Per-step check
  instead of all at the end, because C1 to C3 are dormant until C4 and each commit had to be no worse than the one before.
- **M6.** V2 runs on a 3-thread simulator build, 8 at a time (measured: same speed as 6 threads, identical output).
- **M7.** The FPGA builds started while V2 runs (they do not depend on V2 unless V2 finds a bug), to save wall time.
- **M8.** (2026-10-16 05:18) V6 hunt boot A started on the board **before** V2 finished. Why: the user wants the board answer
  ("is the freeze gone on hardware") most, the board was free (TASK rule 6: no launcher, nothing on the console port) and may not
  be later, the dual image was built, and the simulation evidence that matters most was already in: the four programs that froze
  the simulator 5 of 5 times ran 200,000 iterations with data OK, and the first 12 matrix runs passed. What is still running
  in simulation (the rest of V2, V3, V4) guards against regressions elsewhere. If it finds a bug the board result is for a
  superseded image and is reported as such.
- **M9.** (2026-10-16 04:55) I killed a tmux session that still held 8 running V2 simulations and so lost about 20 minutes of V2
  work; the 8 completed runs are intact. Cause: I swapped the queue feeder without checking that tmux would take its children
  down. The first replacement queue also launched duplicates because `ps` under tmux truncates the command line and my
  "already running" test missed every run; `pgrep -f` replaced it. No result was affected: the logs of killed runs were rerun.
- **M10.** (2026-10-16 07:40) V4 (single-core regression) was **stopped after stages g0 and g1 and part of g2** (13 comparisons,
  all identical to the baseline), to free the machine (load average 45 on 24 cores) for the candidate-fix check below.
  Gate g3 had been identical after every step C0 to C4, and V4 must be re-run on whichever tree is final anyway.
- **M11.** (2026-10-16 07:50) The V2 failure F3 is a hole that predates 020 (inferred). I did **not** put a fix into the 020 commits
  or the 020 images. A candidate fix is on its own branch `sbc-021-copy-refill-gate-candidate` (commit `021b2d9`, cut from
  `af36949`, one file, seven lines) so its effect can be measured; the user decides whether it ships.
- **M3.** The task says "the 5 original freeze binaries". `$SWD/build/019/` holds **4 distinct** freeze binaries
  (`drd_noshare_dst1`, `drd_noshare_dst1_s2`, `drd_noshare_dst0`, `drd_share_dst1`). The fifth freeze run of the table (the
  6-thread build, `det_noshare_dst1_t6`) used `drd_noshare_dst1` again. I use these 4 wherever the task says 5, and the 2 control
  binaries (`drd_noshare_mig0`, `drd_noshare_h1idle`; the `_100k` copies are the same programs with 100,000 steps).

## Preflight and baselines (§9)

### Preflight (rules 4, 6 and 8, run 2026-10-15 ~21:30)

| Check | Result |
|---|---|
| `git diff --quiet 494ab5e -- design/` | same |
| `git status --porcelain design/` | empty |
| branch / HEAD before the switch | `sbc-013-sat-counter` / `e572ffe` (docs only above `494ab5e`) |
| `sw/build/sbc_read` sha256 starts | `8241eb8b8b7e910b4a9d` (expected `8241eb8b…`) |
| `sw/build/018/sbc_read` sha256 starts | `b103216dcc052789073c` (expected `b103216d…`) |
| `software/parsec-benchmark/staged/lu_ncb` sha256 starts | `cb5dadbc931ce83ae608` (expected `cb5dadbc…`) |
| board busy (rule 6) | **yes**: another session's FFT sweep (`fft-sweep-dstmode1-single-core-512k-20261015`) holds `/dev/ttyUSB2`. Nothing before stop (d) needs the board |

`git status --short` of the L2 repo showed only changes I did not make (bug-fix-log, guides, `.obsidian/`, `scratch/`, tasks 017/019 folders,
`sw/compile_dual_test.sh`, `sw/dual_release_deadlock_test.c`, `sw/l2_miss_calib_dual.c`, `tmp.md`, …). I leave all of them alone.

### Saved simulators (step 9.4)

| File in `$NEW/bin-494ab5e/` | sha256 starts | Built |
|---|---|---|
| `simulator-chipyard.harness-VerilatorRocket8KL116KL2SipTestDualDstConfig` (stopping) | `c30a69bbc37663fc07ed` | 2026-10-15 19:17 |
| `simulator-chipyard.harness-VerilatorRocket8KL116KL2SipTestDualDstNoStopConfig` (non-stopping) | `c3c327048df9942fd3e3` | 2026-10-15 19:54 |

### Two-core baseline: the freeze, again (step 9.6)

**Done 2026-10-15 21:37–21:39 (measured).** `drd_noshare_dst1` on the saved non-stopping simulator, runner `$NEW/tools/run_nostop.sh`,
log `$NEW/sim/base_noshare_dst1_rerun.log` (1 min 43 s of wall time).

| | Task 019 run (`nostop_noshare_dst1.log`) | This re-run |
|---|---|---|
| first "MSHR wedged" assert | `[8666297000]` ps, slot 6, set 5, `searching=1` | `[8666297000]` ps, slot 6, set 5, `searching=1` |
| false-alarm check at `MSHR.scala:951` (S_INVALID → S_TIP_CD), slot 0 / 1 / 6 | 254 / 39 / 32 | 254 / 39 / 32 |
| wedge assert count at the error limit | 1,653,890 (larger error limit then) | 19,674 (limit 20,000 now) |

The freeze starts at the identical cycle, so the environment still reproduces it. The only difference is how many times the
watchdog repeats before the error limit stops the run.

### Single-core gate baseline (step 9.5)

**Done 2026-10-15 21:39 → 2026-10-16 00:01 (2 h 22 min), RTL = `494ab5e`, all runs PASS, no assert, no crash (measured).**
Driver `$NEW/tools/run_base_gates.sh`, driver log `$NEW/gates/base_driver.log`, full table `$NEW/gates/baseline_table.md`
(made by `$NEW/tools/gate_table.py`, read only). Per-run folders: `sw/verilator_logs/<test>_<config>_<label>/` with labels
`013-020base` and `020-base-g0` … `020-base-g6b`. All runs use `-DL2_POLICY=1` (PLRU) except g6a/g6b (random).

The two tests are `migration_stress_test` (stress) and `sbc_migrate_switch_test` (switch). Cases = cases passed / total.
"Mig" = migrations committed, "Att" = attempted, "Ab" = aborted. L2 acc / L2 hit are the old whole-channel counters.

| Stage (what) | Stress cycles | Cases | Mig / Att / Ab | Sec hit / miss | L2 acc / hit | Switch cycles | Switch mig |
|---|--:|--:|--:|--:|--:|--:|--:|
| `…Config` (SBC, no Dst) = 013-020base = g0 = g1 | 26,359,206 | 8/8 | 64,183 / 64,315 / 134 | 2,998 / 67,967 | 435,035 / 303,417 | 1,402,206 | 1,490 |
| `…NoSbcConfig` = 013-020base = g0 = g1 | 26,134,936 | 7/7 | 0 / 0 / 0 | 0 / 0 | 434,101 / 303,042 | 505,366 | 0 |
| g2 `…DstConfig`, setting untouched | 26,359,206 | 8/8 | 64,183 / 64,315 / 134 | 2,998 / 67,967 | 435,035 / 303,417 | 1,402,206 | 1,490 |
| g3 Dst, PLRU, setting 0 (+1 directed case) | 24,944,926 | 9/9 | 45,533 / 45,538 / 5 | 1,753 / 72,019 | 378,293 / 246,049 | 1,584,906 | 1,491 |
| g4 Dst, PLRU, setting 1 (+ directed case) | 24,944,476 | 9/9 | 45,537 / 45,537 / 0 | 1,754 / 72,018 | 378,292 / 246,047 | 1,584,906 | 1,491 |
| g5 Dst, PLRU, setting 2 (+ directed case) | 24,759,006 | 9/9 | 43,144 / 43,174 / 35 | 4,104 / 69,396 | 377,896 / 245,909 | 1,458,386 | 9 |
| g6a Dst, random, setting 0 = g6b (setting 2) | 24,918,766 | 8/8 | 14,555 / 41,401 / 27,007 | 6,647 / 56,415 | 379,829 / 237,870 | 1,499,746 | 10 |

What the table shows (measured):

- **The baseline is repeatable.** `013-020base`, g0 and g1 are the same run three times, and g2 equals them. g6a equals g6b
  because the random policy ignores the destination setting (as the 018 script header says).
- **It matches the older logs.** Every counter and every cycle count equals task 018's own gate logs (`018-g1`, `g3`, `g4`, `g5`,
  `g6a`) and task 013's `013-c3`. So this baseline is the same hardware behaviour those tasks recorded.
- **Cycle counts are the sharpest check.** Any V4 difference in cycles or counters is a real change in behaviour.

Two small differences found while comparing (both explained, neither a hardware difference):

1. **`DST-MASK` tag.** `…DstConfig` prints one extra debug tag (`DST-MASK`, 64,315 lines) that `…Config` does not. It is the
   dstModes debug print, so g2 and g0 differ only in that line.
2. **Print order inside one clock cycle.** `sbc.log` of g0 and of task 018's g1 have the same size and the same sorted content
   (equal md5), but a few lines are in another order (for example `PLRU-VICTIM` before or after `EVICT-ASSESS` in the same
   cycle). A different Verilator build orders the prints of one cycle differently. **For V4, compare cycle counts and counters, or
   sorted logs, never raw byte order.** (See F1.)

Cost, for planning V4: the 013 gate took about 25 minutes (it rebuilds two configs); each 020 stage took 11.5 to 23 minutes. A full
V4 (013 gate + g0 to g6b) is therefore about 2 h 20 min again. After each step C0–C3 I run only g3 (about 12 minutes).

Environment after the baseline (checked 2026-10-16 00:05): `design/` still equals `494ab5e` and is clean; `sbc_read`
`8241eb8b…`, `018/sbc_read` `b103216d…`, `lu_ncb` `cb5dadbc…` unchanged. New files in the L2 repo: `sw/scripts/run_020_gate.sh` and
this folder. Branch `sbc-020-tilelink-merge`, no commit yet. `make clean` inside the gate rebuilt every single-core simulator
from this tree; the two-core simulators of 494ab5e live in `$NEW/bin-494ab5e/`, and `sims/verilator` no longer has the two-core ones
(they are rebuilt in V1).

## Changes (§6), one block per step C0–C5: what changed, where, why it is safe, the commit (if any)

### C0 — simulation-only check fixes, watchdogs, probe-answer counter (no hardware change)

**Why.** The stopping two-core simulator could not be used: two checks fire falsely on two-core traffic (D7) and stop the run before
the real freeze. Without a usable stopping simulator, V2 (60 runs) cannot be a pass/fail test, and the next steps could not be
told apart from the freeze. C0 also adds nets that turn a write-back that waits too long into a *named* assert.

| # | Change | Where (after C0) | Why it is safe |
|---|---|---|---|
| 1 | `assert (!migrating …)` → `assert (mig_ready …)` | `MSHR.scala` assess-reset block | `migrating` is still set in the cycle a migration retires, and a same-line `repeat` reload may land in that cycle. `mig_ready` (= not migrating, or dir-write #1 done) is the condition the reload itself waits for. A real drop still fires |
| 2 | `assert (!dstEvict && s_drelease && w_dreleaseack && !dstRelOut)` → drop the `!dstEvict` term | same block | `dstEvict` is cleared *by the retire*, so it is legitimately set in the reload cycle. What must hold is that W's Release was sent and acknowledged (`s_drelease`, `w_dreleaseack`, `!dstRelOut`); an unfinished write-back still fires |
| 3 | stock transition check runs on `!s_writeback && no_wait && mig_ready && sec_ready` | `MSHR.scala` | the same terms as the final directory write in `dir.valid`. A write-back's partner-search step has `!s_writeback && no_wait` true while `meta` is still the home victim, so the check judged the wrong entry. With SBC off `mig_ready` and `sec_ready` are true, so the expression is the stock one |
| 4 | write-back progress watchdog `wbCtr` (limit 4,000 cycles) + `WB-AGE-MAX` print | `MSHR.scala`, inside `if (enableSetBalancing)` | counts cycles a slot holds a write-back; resets on retire/reload. Sim only (feeds only an assert and a `printf`). The scoreboard text is now one named value shared with the "MSHR wedged" assert |
| 5 | C-entrance watchdog for **every** refusal (`cHeadAnyCtr`, limit 4,000) + `C-HEAD-WAIT-MAX` print | `Scheduler.scala`, inside `if (enableSetBalancing)` | the old counter (kept, limit 1,000) sees only `dstSetConflict`. The old one names its cause; the new one covers the rest |
| 6 | `PROBEACK-STRAY` counter and print | `Scheduler.scala`, under `sbcDebug` | counts probe answers that reach a slot that is not waiting for one (D5). Report only; C1 fixes the routing and turns it into an assert |

**Check (2026-10-16 02:00–02:07, measured).** Both two-core simulators rebuilt (about 1 minute each) from the C0 tree
(`$NEW/bin-c0/`, build logs `$NEW/sim/build_stop_c0.log`, `build_nostop_c0.log`):

| Run | Result |
|---|---|
| `drd_noshare_dst1`, stopping | stops at `[8634297000]` ps with **`SBC(020): write-back not answered in 4000 cycles`** (slot 6, set 5, `searching=1`). The old simulator stopped on a false alarm first |
| `drd_noshare_dst1`, non-stopping | same first assert at `[8634297000]`; the old 20,000-cycle "MSHR wedged" follows at `[8666297000]`, 16,000 L2 cycles later, as before. **0** false-alarm lines (old run: 254 + 39 + 32 transition checks) |
| `drd_share_dst1`, stopping | stops at `[1132657000]` with the same named assert (slot 6). The old simulator stopped at `[716065000]` on the `migrating` false alarm, so the run now goes 0.42 ms of simulated time further |
| `drd_share_dst1`, non-stopping | after it, slot 1 (`inPlace=1`, `physSet=6`, the Form B partner of the write-back) is reported wedged too |

So C0 does what the task asks: the freeze is reproduced as a **named** assert and the false alarms are gone. Logs:
`$NEW/sim/c0_stop_*.log`, `c0_nostop_*.log`; pipeline log `$NEW/gates/c0_driver.log`.
Gate g3 with the C0 tree: **identical** to the baseline (24,944,926 cycles, 45,533 migrations, 1,753 secondary hits, 9/9 and 3/3).
Commit `1392362` on `sbc-020-tilelink-merge` (MSHR.scala, Scheduler.scala, `sw/scripts/run_020_gate.sh`).

### C1 — a probe answer reaches only the slot that is waiting for it (D5, D6)

**Why.** The Scheduler delivered every probe answer to each slot whose `(probeSet, probeTag)` key matched, waiting or not.
A slot with no probe outstanding advertises `(request.set, request.tag)`, and a row-P slot that is probing a parked line advertises
`(lineHome, meta.tag)`, which can be the same pair. Both matched. The idle slot then collected stray `probes_done` / `probes_toN`
bits that are cleared only once per request, while SBC can start a probe later in the same request. That makes the later probe
finish early or wait forever (D5). A data answer that no waiting slot matched was written to set 0, way 0 (D6).

**What.** `Scheduler.scala`: `m.io.sinkc.valid` also requires `probeAckPending` when SBC is on (the constant `true` when SBC is off,
so the stock expression is unchanged). A new assert checks that every answer reaches **exactly one** waiting slot
(`SBC(020 O3)`). `PROBEACK-ALIAS` (sbcDebug) counts the answers whose key also matches an idle slot, so V3 can show the case happens.
The C0 name `PROBEACK-STRAY` is retired: with exact routing nothing can be "stray", and the assert is the net.

**Check (2026-10-16 02:20–02:39, measured).** Freeze tests: same named assert at the same instants as C0 (`[8634297000]`,
`[1132657000]`). Gate g3: identical to the baseline. The uniqueness assert did not fire. Commit `c3320f1`.

### C2 — the merge reaches every slot that holds the line, and a write-back's search sees locked ways (D3, D8)

**Why (D3).** The merge broadcast (`nestedwb`) came only from the two reserved slots and was matched against each consumer's own
set. A row-P slot that is evicting parked line X has `request.set = P`, but the broadcast names set S, so it never learned that the
core's write-back made X dirty. It would have written X to memory as clean and lost the data. This is hidden today only because
the write-back waits instead of merging.

**Why (D8).** The partner search ignores any way another slot has locked (`secHits & freeWays` in `Directory.scala`). Today the
write-back waits (the freeze), so its search never runs. Once the wait is gone (C4), the write-back would search while the same-set
slot is serving that very line in place (Form B), or while a row-P slot has chosen it as victim (D3). It would then **miss** its
own line and write the data into the home row's victim way. In simulation that trips the "neither resident nor parked" assert; on
the board it would corrupt two lines.

| # | Change | Where | Why it is safe |
|---|---|---|---|
| 1 | new status bit `isRelease` (= `request.prio(2)`) | `MSHRStatus`, `MSHR.scala` | one wire, read only by the Scheduler |
| 2 | producer: `c_set_dirty`, `homeSet`, `tag` come from **the selected slot** when it is a write-back (SBC on); the `b_*` fields and the SBC-off expressions are unchanged | `Scheduler.scala` | only one slot is selected per cycle, so the two sources cannot overlap. A normal-slot write-back previously had no consumer other than itself |
| 3 | consumer: match `io.nestedwb.homeSet === lineHome` (was `request.set`); the block moved below `lineHome` | `MSHR.scala` | `lineHome` is `request.set` for a native line and when SBC is off, `pairSetReg` for a parked line in the destination row. So the three cases are: row-S slot serving X in place (S), row-P slot whose victim is X (S), row-P slot with its own line (P). It also fixes a false match: a row-P slot with a parked line of tag T no longer matches a broadcast for P's own line of tag T |
| 4 | `ScheduleRequest.dreadMerge` (= `doSearch && request.prio(2)`) | `MSHR.scala` | a flag only |
| 5 | the way mask of a merge read keeps only the migration-destination lock (`lock2`) and leaves out the slot locks | `Scheduler.scala` | the locks steer victim choice. A merge must see its line. The evictor cannot read X out before the releasing core answers its probe, and Rocket answers only after the ReleaseAck. A held line is never offered as a destination way, so `lock2` can stay. If a merge still missed a `lock2` way, the "neither resident nor parked" assert (MSHR) and the Directory's scenario-10 assert fire |
| 6 | coverage prints `NESTWB-XROW`, `C-SEARCH-LOCKED` (sbcDebug) | both | sim only |

**Check (2026-10-16 02:40–02:59, measured).** Freeze tests: the same named assert at the same instants (`[8634297000]`,
`[1132657000]`), as expected, since the waits are still there. Gate g3 identical to the baseline (24,944,926 cycles, 45,533
migrations). So the new producer and consumer change nothing in single-core runs. Commit `386112b`.

**What C2 cannot show yet.** With the wait still in place the merge search rarely runs while a lock is held, so the new code is
mostly dormant until C4. V2 and V3 are where it is exercised.

### C3 — close the two same-cycle windows of the merge (D4)

**Why.** Even with C2, a merge could be lost in two one-cycle races, because two writes to `meta` land in the same cycle and the
later statement wins:
- **(a)** a write-back nests into the reserved slot in the cycle the same-set slot's partner-search answer is in flight. The answer
  then re-points `meta` at a snapshot of the parked entry that does not contain the merge.
- **(b)** a row-P slot takes its directory result in the very cycle the merge broadcast fires. `meta := new_meta` overrides the
  patch, and the Directory's write bypass covers only writes queued *before* that cycle.

| # | Change | Where | Why it is safe |
|---|---|---|---|
| F1d | `blockC` also covers the in-flight search, and `nestC` is lowered in the same window (new status bit `secInFlight`) | `MSHR.scala` | the write-back waits one or two cycles (the answer is bounded internal work, TileLink ch.5 rule 3). Lowering `nestC` keeps `assert (!nestC \|\| !blockC)` true (the draft would have tripped it). With SBC off the terms are the stock ones |
| F1e | a slot that is a write-back served in place (`physSet =/= homeSet`) is not selected in a cycle where a directory read of its row was issued in the previous cycle (two cycles with `dirReg`; ours is `false`) | `Scheduler.scala`, `mshr_request` | one-cycle hold, only for write-backs of parked lines. The user chose this form (Q2, "optimize later if possible"): the alternative is to apply the broadcast to the arriving result inside the slot |
| 3 | coverage prints `BLOCKC-SEARCH`, `DIRW-HOLD` (sbcDebug) | `Scheduler.scala` | sim only |

**Check (2026-10-16 02:59–03:19, measured).** Gate g3 identical to the baseline. The freeze still appears, as expected (C4 removes the
waits), but with a different interleaving: the write-back now sits in a **normal** slot (slot 1), not in the reserved one, first
assert at `[8679081000]` for `drd_noshare_dst1` and `[1888073000]` for `drd_share_dst1`. The scoreboard shows slot 0 working in the
partner row with `w_rpaFirst=0`, waiting for a probe answer for a *different, dirty* line of the same core, while the write-back
waits for that slot. It is the same D1 wait (a third shape of it), which C4 removes. Commit `a09155c`.

### C4 — remove the two waits (D1, D2). Done last on purpose (N4)

**Why last.** Every earlier step is a merge fix that sits behind the wait it will later need. Removing the wait first would have
exposed D3 (data loss), D8 (a write-back that misses its own line) and D4 (lost merge) at once.

| # | Change | Where | Why it is safe |
|---|---|---|---|
| F1a | `doSearch = searching && !s_ssearch && (!partnerBusy \|\| request.prio(2))` | `MSHR.scala`; comment at `partnerBusy` rewritten | the wait existed so another slot could not evict the parked line during an A request's search. A write-back's line is held by the releasing core, so it cannot be evicted before the acknowledgement; and the merge sees it even when a slot has locked its way (C2, F1f). A requests keep the wait |
| F1b | `allocReady = alloc && ((!dstSetConflict && !secSetConflict) \|\| request.bits.prio(2))` (stock expression with SBC off) | `Scheduler.scala`; comment at `request.ready` updated | a write-back neither evicts nor refills, so it creates no victim-choice hazard against a parked way. Its line is native to the fenced set (a destination is never a source), so it never searches there. A migration destination way is never a held line. No new edge feeds `dstClaim`, so the B7-2 loop risk is unchanged (V5 checks it) |
| 3 | coverage prints `C-SEARCH-BUSY`, `C-FENCE-PASS` (sbcDebug) | both | sim only |

**Check 1: the four freeze programs on the stopping simulator (2026-10-16 03:21–03:43, measured).** Each runs 200,000 iterations
(the freeze was at about 28,000 steps) with two cores racing. Pass = exit 0, `data OK`, no assert.

| Program | What it was | Result | L2 cycles | Migrations | Served writes | Write-backs served in place | Parked at end |
|---|---|---|--:|--:|--:|--:|--:|
| `drd_noshare_dst1` | Form A, setting 1, PLRU | **PASS, data OK** | 21,353,290 | 55,231 | 17,240 | 8,755 | 0 |
| `drd_noshare_dst1_s2` | Form A, seed 2 | **PASS, data OK** | 21,346,030 | 55,059 | 16,754 | 8,507 | 1 |
| `drd_noshare_dst0` | Form A, setting 0 | **PASS, data OK** | 21,361,651 | 35,913 | 13,166 | 6,680 | 1 |
| `drd_share_dst1` | Form B (shared lines) | **PASS, data OK** | 21,634,500 | 52,800 | 16,276 | 8,062 | 3 |

(Logs `$NEW/sim/v2_c4_*.log`; "served writes" = `secWrite`, "write-backs served in place" = `secC`.) So thousands of write-backs of
parked lines were served from the partner row on two racing cores, and every word read back correctly.

**Check 2: single-core gate g3.** Identical to the baseline (24,944,926 cycles; 45,533 migrations; 1,753 secondary hits; 9/9, 3/3).
Commit `af36949`. Simulator note: a 3-thread build runs as fast as the 6-thread one (60 s against 58 s for 16,000 steps; 1 thread
406 s, 2 threads 105 s) and prints identical output (same checksum), so V2 runs 8 at a time on the 3-thread build.

### C5 — the destination fence on every path that takes a request (fixes F5, a bug C4 introduced)

**Why.** Found by V2 (F5 below): `request.ready` refuses a non-write-back on a set fenced by a migration, but the bypass into a reloading
slot and the queue push did not apply that refusal. C4 lets a write-back hold a slot on a fenced set, so an Acquire could queue behind it
and be served without being accepted (one Acquire, two Grants).

**What (`Scheduler.scala`).** `fenceRefuse = dstSetConflict && !request.bits.prio(2)`, named once and used in `request.ready`, both
`bypass` definitions and `requests.io.push.valid`. New assert (SBC only): a request is bypassed or pushed only in a cycle it is accepted.
Coverage print `FENCE-HOLD-QUEUE` (sbcDebug). SBC off: `dstSetConflict` is false, stock expressions. No combinational loop: `dstClaim`
is built only from registers, the directory result and the offer, never from `io.allocate`; Verilator reported no loop.

**Check (2026-10-16 09:43–10:09, measured).** C5 two-core simulators `$NEW/bin-c5/` (both contain the new assert, no debug print).

| Program | C4 | C5 |
|---|---|---|
| `v2_s1_d1_p1_n5` (the F5 failure) | FAIL at ~78,000 iterations (TLMonitor ready check) | **PASS, data OK**, 200,000 iterations, 21,602,007 L2 cycles |
| `drd_share_dst1` (Form B) | PASS, 21,634,500 cycles | **PASS, data OK, 21,634,500 cycles (identical)** |
| `drd_noshare_dst1` (Form A) | PASS, 21,353,290 cycles | **PASS, data OK, 21,353,290 cycles (identical)** |
| gate g3 (single core) | identical to baseline | **identical to baseline** (24,944,926 cycles, 45,533 migrations) |

Identical cycle counts mean C5 changes nothing unless a request meets a slot on a fenced set. Commit `e2a71b3`. Images rebuilt from it
(see FPGA builds); the board checks are re-run on them.

## Simulation verification (§10): V1, V2 table, V3 coverage table, V4 counter comparison, V4b

**V1 (done).** Both two-core simulators built on every step C0–C4 (build logs `$NEW/sim/build_{stop,nostop}_c<N>.log`, binaries
`$NEW/bin-c<N>/`). For V2 a 3-thread build of the C4 stopping simulator is used (`$NEW/bin-c4/simulator-stop-t3`, same output as
6 threads, measured). The single-core configs elaborated and ran in every g3 and V4 stage.

**V2 on C5 (the final tree, 2026-10-16 10:10–14:36; simulator `$NEW/bin-c5/simulator-stop-t3`): 65 of 66 PASS, 1 FAIL = F3 (measured).**
Full table `$NEW/gates/v2_c5_table.md` (`python3 $NEW/tools/v2_summary.py c5`); per run `$NEW/sim/v2_c5_<name>.log`. All 60 matrix
runs (PLRU and random, seeds 1–5, settings 0–2, shared and unshared) plus the 2 controls and the 4 freeze programs, every one with
`data OK`.
- The one failure is `v2_s1_d2_p1_n4`: F3's shadow assert (`copy_r touched the wrong row`, set 5 way 0) at **the same cycle as on C4**
  (17,980,573,000). Expected: F3 is older than 020 and its candidate fix is not in C5.
- The F5 run `v2_s1_d1_p1_n5` passes (it failed on C4).
- **C4 vs C5, run by run: all 52 runs that passed on both give identical L2 cycle counts** (and identical migration counts). So C5's
  refusal acts only in the F5 run in the whole matrix; everywhere else it changes nothing. (12 random-policy and control runs exist only
  on C5, because C4's random runs were stopped to free the CPU.)

**V2 on C4 (stopped 09:55; 52 PASS, 2 FAIL, 8 random-policy runs killed to free the CPU).** State at 08:32: 40 PASS, 2 FAIL, 4 running. Failures: F3 (`v2_s1_d2_p1_n4`, migration copy after refill —
older hole) and F5 (`v2_s1_d1_p1_n5`, TLMonitor ready check — under investigation). Per-run table: `python3 $NEW/tools/v2_summary.py c4`.
The 4 freeze programs passed twice (6-thread and 3-thread builds). Watching for B8-1 (user Q6): its signature is the assert "paired
source migrated outside its partner set"; it has not appeared in any V2 log so far.

**V3 on C5 (done 2026-10-16 14:54–15:11, measured).** Eight verbose runs of 100,000 iterations on the C5 non-stopping simulator
(`$NEW/sim/v3_c5_<name>.log` and `.tags`), settings 0–2, shared and unshared, PLRU; 194,662 migrations committed in all. All eight
finished with `data OK`. **Every one of the eight wanted paths fired at least once:**

| Path (print tag) | What it shows | Times fired (8 runs) | Runs in which it fired |
|---|---|--:|--:|
| `PROBEACK-ALIAS` | a probe answer matched two slots and went only to the waiting one (C1) | 18,632 | 8 |
| `BLOCKC-SEARCH` | a write-back held back while a slot's partner-row search was in flight (C3) | 4,454 | 8 |
| `C-FENCE-PASS` | a write-back passed a destination or search fence (C4) | 4,354 | 8 |
| `C-SEARCH-BUSY` | a write-back's partner search went ahead although the partner row was busy (C4) | 2,234 | 8 |
| `C-SEARCH-LOCKED` | a write-back's search saw a way locked by another slot (C2) | 177 | 8 |
| `DIRW-HOLD` | a request held one cycle behind a directory write to its row (C3, F1e) | 6 | 4 |
| `NESTWB-XROW` | a write-back merged into a slot that holds its line in the other row (C2) | 2 | 1 |
| `FENCE-HOLD-QUEUE` | a non-write-back refused at a fenced set where the queue would have taken it (C5, the F5 case) | 2 | 1 |

Watchdog margins: the longest a write-back waited to retire was **23 cycles** (`WB-AGE-MAX`), and the longest the C-channel head
was refused was **3 cycles** (`C-HEAD-WAIT-MAX`); both limits are 4,000. The three rare paths fired 2–6 times each, which shows they
run but not that every timing around them is right; the directed test `sw/dual_merge_directed_test.c` was not written.

**F3 again, in V3 (measured).** `v3_s0_d1_p1_n18` (**setting 1, unshared lines**, PLRU, seed 18) printed F3's shadow assert twice
(non-stopping simulator): `copy_r touched the wrong row: set=5 way=1`, `lastWriter=1` (memory refill) at cycle 9,803,717, migration copy
read at 9,803,724 and 9,803,725. The run still ended `data OK`: the wrong copy was not read back before the program's check. So F3 is
**not limited to setting 2 or to shared lines** (its V2 failure was setting 2, shared); setting 1 is the board setting.
This binary on the F4 candidate simulator: see F4.

**V3 earlier (C4, partly).** One 16,000-step verbose run (`$NEW/sim/cov_c4_bench16k.tags`, setting 1, shared, PLRU, data OK): `PROBEACK-ALIAS`
478, `BLOCKC-SEARCH` 101, `C-FENCE-PASS` 93, `C-SEARCH-BUSY` 43, `C-SEARCH-LOCKED` 3; `NESTWB-XROW` and `DIRW-HOLD` 0 (rare races; the
eight 100,000-step coverage runs are built and not yet run). "Ack and write in one step" is shown by construction: for a write-back,
`d.valid` (`!s_execute && w_pprobeack && w_grant && sec_ready`) and the write-back term of `dir.valid` (`!s_writeback && no_wait &&
mig_ready && sec_ready`) become true in the same cycle, because a write-back has no probe, grant or migration to wait for, and the
schedule fires all of its lanes together; a trace print to show it empirically was not added (it would change `design/` after the
images were built).

**V4 on C5 (done 2026-10-16 15:11–16:54, measured): 24 of 24 IDENTICAL to the 494ab5e baseline, 0 asserts.** `run_v4_gates_c5.sh`
(labels `013-020v4c5`, `020-v4c5-g0…g6b`; driver `$NEW/gates/v4_c5_driver.log`; comparison `$NEW/gates/v4_c5_compare.txt`, from
`python3 $NEW/tools/v4_compare.py v4c5`). Every stage: the 013 gate and g0/g1 (SBC on and SBC off configs), g2 (DstConfig, register
untouched), g3/g4/g5 (settings 0/1/2, PLRU), g6a/g6b (random policy), both tests each. Result, cycles, cases, migrations, attempts,
aborts, secondary hits and misses, legacy accesses and hits, MIG-START, MIG-COMMIT, SEC-SERVE and victims are equal in every pair. So
**020 does not change single-core behaviour at all, with SBC on or off** (TASK rule: no-op with SBC off).
(On C4 the same check was paused after 13 identical comparisons; g3 was identical after each of C0–C5.)

**V4b (done 2026-10-16 14:36–14:54, measured): 4 of 4 PASS.** The four freeze programs on the C5 **non-stopping** simulator
(`$NEW/bin-c5/simulator-nostop-t3`, so every assert would print and the run go on; logs `$NEW/sim/v4b_c5_<program>.log`):

| Program | Result | Asserts printed | L2 cycles (non-stopping) | L2 cycles (V2, stopping sim) |
|---|---|--:|--:|--:|
| `drd_share_dst1` (Form B) | no hang, data OK, 200,000 iterations | 0 | 21,634,500 | 21,634,500 |
| `drd_noshare_dst0` | no hang, data OK | 0 | 21,361,651 | 21,361,651 |
| `drd_noshare_dst1` (Form A) | no hang, data OK | 0 | 21,353,290 | 21,353,290 |
| `drd_noshare_dst1_s2` | no hang, data OK | 0 | 21,346,030 | 21,346,030 |

Before 020 these four froze 5 of 5 times. The two 4,000-cycle watchdog asserts (`wbCtr`, `cHeadAnyCtr`) are live in every run and
did not fire. Their largest-value prints (`WB-AGE-MAX`, `C-HEAD-WAIT-MAX`) need `+verbose`, which V4b does not pass; V3 (verbose)
gives the margins.

## FPGA builds (§11)

Both images built from the committed RTL, L2 commit `af36949` (branch `sbc-020-tilelink-merge`), with `build_bitstream.sh`, the
config's `generated-src` folder deleted first, one build at a time. The provenance says "L2 RTL: clean at HEAD" (no "uncommitted
design/" warning). Archive folder: `chipyard/fpga/bitstream_storage/`. Driver log `$NEW/gates/fpga_builds.log`. Build started
2026-10-16 04:10 and ran next to the V2 simulations, which slowed it.

| Image | sha256 | Built | Wall time | Worst setup slack | Hold slack | Loop (LUTLP-1) | Config |
|---|---|---|--:|--:|--:|---|---|
| **dual core, 512 KB 8-way L2, 32 KB 8-way L1** | `e4129baa09af2fff258482f1dcf69a23d44a5418248436b5fd9450f5a5fc9536` | 2026-10-16 05:11 | 63 min | **+0.544 ns** (task 019 image: +0.548 ns) | +0.010 ns | none | `FPGADualRocketVCU118L132K512K8WL2ConfigSBCPLRUDst` |
| **single core, 512 KB 8-way L2, 32 KB 8-way L1** | `2f8fa21077157a5a2751005b15cf7c673d5bb8523ea6fcd639a5221cf13637a3` | 2026-10-16 05:58 | 45 min | **+0.524 ns** (task 018 image: +0.526 ns) | +0.010 ns | none | `FPGASingleRocketVCU118L132K512K8WL2ConfigSBCPLRUDst` |

Both: "All user specified timing constraints are met." The DRC report has no `LUTLP` entry, so the B7-2 combinational-loop error
did not return (Vivado 2021.1, the version the script uses). Files: `<config>-<tag>-2026-10-16.bit`, `.provenance.txt`, `.dts`,
`.config.diff`, `.reports/` (clocks, datasheet, drc, fanout, io, timing, utilization). Tags: `dual-512KB-8way-L1-32K8W-tl020-af36949`
and `512KB-8way-L1-32K8W-tl020-af36949`.

**C5 images (RTL `e2a71b3`, the final tree; same script and rules; driver log `$NEW/gates/fpga_builds_c5.log`).** Built with the tags
`dual-512KB-8way-L1-32K8W-tl020c5-e2a71b3` and `512KB-8way-L1-32K8W-tl020c5-e2a71b3`; **renamed 2026-10-16 ~17:30** (user request) so
the archive carries the git tag: `<config>-dual-512KB-8way-L1-32K8W-sbc-020-dualcore-tilelink-merge-512k-2026-10-16.*` and `<config>-512KB-8way-L1-32K8W-sbc-020-dualcore-tilelink-merge-512k-2026-10-16.*`, each with a
`.NOTE.txt` (identity, results, old name). Logs written before the rename name the old path; the sha256 is the identity. These replace
the af36949 images for every board check from now on.

| Image | sha256 | Built | Wall time | Worst setup slack | Hold slack | Loop (LUTLP-1) |
|---|---|---|--:|--:|--:|---|
| **dual core (C5)** | `32ebbd035a5a26c9a8cdf70620144692162b8301f1b1a06a7850bedf1822eab5` | 2026-10-16 10:58 | 50 min | **+0.425 ns** | +0.010 ns | none |
| **single core (C5)** | `0f02bc92e3929c9c46036c271f2fa5a24fe7fc8cee94b04863534ab5f41a341d` | 2026-10-16 12:11 | 71 min | **+0.646 ns** | +0.010 ns | none |

Both C5 images: "All user specified timing constraints are met"; no `LUTLP` in the DRC report; provenance "L2 RTL: clean at HEAD". Slack is 0.12 ns below the af36949 image
(+0.544 ns) and the single image's is 0.12 ns above its af36949 one (+0.524 ns): opposite moves of the same size, so placement noise
(inferred). Both pass.

The change in slack against the images before this task is 0.004 ns and 0.002 ns, which is noise. The two new FFs and the few
extra gates sit on non-critical paths.

## Board checks (§12)

### V6 part 1 — hunt boots on the dual image (`e4129baa…`)

Method: task 019's `hunt_session.exp` (copy `$NEW/tools/hunt_session.exp`, only the HUNT folder and the default image changed),
ladders `$NEW/board/ladders/v6_boot_a.txt` and `v6_boot_b.txt`, program `sw/build/l2_miss_calib_dual` (sha256 starts `a53a322f…`,
unchanged), `sbc_read` 018 build. Each boot: program the FPGA, boot Linux, run the rungs one after another. A rung HANGS when no byte
arrives for 120 s. Task 019 image on the same patterns: dead in 2 to 6 seconds, every time.

**Boot A (fresh boot, 2026-10-16 05:18–06:00; console `$NEW/board/console_boot1.txt`, summary
`$NEW/board/runs/hunt_summary_20261016-051828.tsv`): all 4 rungs OK, no hang.**

| Rung | Pattern | Length | Result | Migrations | Attempts | Steps (both cores) |
|---|---|--:|---|--:|--:|--:|
| a1 | setting 1, 2 threads on the **same** L2 sets, lines **stored** (the 019 fast hanger) | 600 s | **OK** | 85,422 | 176,089 | 4.1 million |
| a3 | setting 1, shared lines, stored | 120 s | **OK** | 3,759 | 3,760 | 0.87 million |
| a4 | setting 1, same sets, stored, second half | 120 s | **OK** | 15,925 | 15,925 | 0.75 million |
| a5 | setting 1, same sets, **reads** only | 120 s | **OK** | 68,126,186 | 69,472,938 | 0.93 million |

(measured; "steps" = the two threads' step counters at the last heartbeat; a1's DONE line: `seconds=600.19 steps=4098752`.)
The old image died within 2 to 5 seconds on a1.

**Boot B (a second fresh boot, 2026-10-16 06:01–06:41; console `$NEW/board/console_boot2.txt`, summary
`$NEW/board/runs/hunt_summary_20261016-060059.tsv`): all 3 rungs OK, no hang.**

| Rung | Pattern | Length | Result | Migrations | Attempts | Steps (both cores) |
|---|---|--:|---|--:|--:|--:|
| b1 | **setting 0**, 2 threads on the same sets, lines stored (task 019 boot 8: dead in 6.3 s) | 600 s | **OK** | 79,748 | 90,924 | 4.1 million |
| b2 | setting 2, same pattern | 120 s | **OK** | 18,171 | 30,511 | 0.84 million |
| b3 | setting 1, same pattern (control) | 120 s | **OK** | 12,478 | 12,490 | 0.84 million |

**V6 part 1 verdict (measured): 7 of 7 rungs completed on 2 fresh boots, 2,000 seconds of the pattern that killed the previous
image in 2 to 6 seconds, with settings 0, 1 and 2, shared and unshared lines, stores and reads. No hang.** Not shown by this:
whether a rarer freeze path exists (the LU runs below are the other probe), and that LU's data is right (the data check below).

### V6 part 2 — LU with two threads on the dual image (`e4129baa…`), n=2048, b=128, PLRU

Method: task 019's launcher `launch_dst_sweep_v4.sh` (copy `$NEW/board/lu/tools/launch_dst_sweep_v5_020.sh`, only the image and the
campaign root changed), task 019's harness copy and checker unchanged, one fresh boot per window, SBC off then SBC on (setting 1).
Round 1 results: `$NEW/board/lu/RESULTS.md`, per-stage folders `$NEW/board/lu/off/…`, `$NEW/board/lu/on/…`.
(The generated RESULTS.md header still names "task 018's `494ab5e`" and "task 019 image" — that text is hard-coded in the copied
table script. The image that ran is the 020 one: sha256 `e4129baa09af2fff…`, checked by the stage check "bitstream sha256 matches".)
This setting froze 4 of 5 times on the previous image.

| Window | Result | Memory accesses (DRAM reads + writes) | L2 cycles | Window time |
|---|---|--:|--:|--:|
| SBC off | **VALID, completed** | 340,244,370 | 33,659,397,767 | 673.2 s |
| SBC on (setting 1) | **VALID, completed** | 314,209,310 (**−7.7%**) | 33,437,808,376 (**−0.66%**) | 668.8 s |
| SBC on again (replicate, round r2) | **VALID, completed** | 312,750,155 (**−8.1%**) | 33,472,393,178 (**−0.56%**) | — |

Reads −8.40%, writes −6.79%. SBC-on counters: 6,433,209 migrations, 9,651,289 attempted, 6,508,340 secondary hits, held aborts
33.3% of attempts, 586,993 destination write-backs, `probedHit` 7,023,754 (the two cores do probe each other's lines). **The SBC-on
window ran 11 minutes with 6.4 million migrations and no freeze** (measured). There is no earlier two-thread n2048 point to compare
with, so −7.7% (replicate −8.1%, 6,766,192 migrations) is new data. The two windows agree within 0.4 points. The LU data check (STEP 3 of the chain) is still to come.

### V6 part 2, LU data check — not run (my error)

STEP 3 of the board chain refused at once (`ERROR: the plan is empty. -plan lu offers these labels: lu-p2-n2048-b128-plru`): I passed
`-bench lu-p2-n2048-b128-plru-lucheck`, a label the harness does not know. No board time was used. Since the 020 images must be rebuilt
for C5 anyway, the data check is to be run on the C5 image (with `-bench lu-p2-n2048-b128-plru -lucheck`).

### V7 — single-core image (`2f8fa210…`, RTL `af36949`), LU `-p1` n=2048 b=128, SBC off then setting 1, fresh boot each

Launcher copy `$NEW/board/lu1/tools/launch_dst_sweep_v3_020.sh` (task 018's v3, only image and campaign root changed); results
`$NEW/board/lu1/RESULTS.md`; both windows VALID (2026-10-16 08:30–10:00).

| | SBC off | SBC on (setting 1) | Change |
|---|--:|--:|--:|
| Memory accesses | 205,383,537 | 185,042,386 | **−9.9%** |
| Reads / writes | 111,510,114 / 93,873,423 | 100,178,977 / 84,863,409 | −10.16% / −9.60% |
| L2 hit rate | 77.63% | 79.90% | +2.27 points |
| L2 cycles | 58,127,346,349 | 57,850,041,470 | −0.48% |
| Migrations / secondary hits | — | 3,820,568 / 4,201,153 | |

**Pass (measured):** task 018 measured −10.7% (replicate −9.7%) at this point with the pre-020 RTL; −9.9% is inside both and within
the TASK's ±1.5-point band. One window per arm. So C0–C4 did not lower single-core performance at this point. (C5 changes only
requests queued behind a slot on a fenced set, which needs a write-back there; it is re-checked on the C5 image.)

### Board checks on the C5 images (RTL `e2a71b3`, dual `32ebbd03…`, single `0f02bc92…`) — board chain `$NEW/tools/board_chain_c5.sh`

Driver log `$NEW/gates/board_chain_c5.log`. Same method as V6/V7 above, one fresh boot per stage.

**A — hunt boot (fresh boot, 2026-10-16 11:00–11:43; console `$NEW/board/console_c5_boot1.txt`, summary
`$NEW/board/runs/hunt_summary_20261016-110043.tsv`): all 4 rungs OK, no hang (measured).**

| Rung | Pattern | Length | Result | Migrations | Attempts | Steps (both cores) |
|---|---|--:|---|--:|--:|--:|
| a1 | setting 1, 2 threads on the **same** L2 sets, lines **stored** (the 019 fast hanger) | 600 s | **OK** | 82,798 | 90,344 | 4.1 million |
| a3 | setting 1, shared lines, stored | 120 s | **OK** | 23,668 | 37,560 | 0.88 million |
| a4 | setting 1, same sets, stored, second half | 120 s | **OK** | 2,366 | 2,366 | 0.76 million |
| a5 | setting 1, same sets, **reads** only | 120 s | **OK** | 68,922,695 | 68,937,954 | 0.94 million |

Steps per rung match boot A on the af36949 image within 1% (a1 4,099,840 vs 4,098,752), so C5 did not slow the cores. Migration
counts on two store rungs differ from that boot (a3 23,668 vs 3,759; a4 2,366 vs 15,925), in opposite directions. Not explained
(inferred only: each rung follows a different amount of earlier traffic on its boot; one window each, so the spread between runs is
unknown). Not a pass criterion; a1 and a5, the long and the heavy rung, agree within 3% and 1.2%.

**B — LU data check (fresh boot, 2026-10-16 11:43–12:19; console `$NEW/board/lucheck_c5/console.txt`, session log
`chipyard/scripts/logs/lu_session_20261016-114303.log`): PASS (measured).** `lu_ncb -p2 -n2048 -b128 -t`, SBC on, setting 1, PLRU,
under `sbc_read --zero`. LU's own `-t` check prints `TEST FAILED` on every platform (task 019's finding, also on the host with no
board and no SBC), so the printed diff is the fingerprint:

| | Printed diff |
|---|---|
| Board, dual C5 image, SBC on | `-115065518155955623085056060089833064667021312.00000` |
| Host reference (qemu user mode, same `lu_ncb` sha256 `cb5dadbc…`; task 019 `calibration/host_reference.txt`) | `-115065518155955623085056060089833064667021312.00000` |

Identical to the last digit. SBC was busy during the run: 7,044,290 migrations, 6,706,113 secondary hits, 10,537,438 attempts,
38 lines parked at the end. **This is the first check of LU's output with SBC on** (task 018 and V6/V7 checked the exit code only).
What it shows: one LU result, computed through 7 million migrations, equals the host's. What it does not show: other inputs or
other workloads. The window's counts (325,584,338 memory accesses, 33,579,638,190 cycles) include the `-t` check phase, so they are
not comparable with the C and D windows.

**C — dual LU off/on (fresh boot each, 2026-10-16 12:19–13:32; `$NEW/board/lu/c5/{off,on}/lu-p2-n2048-b128-plru/`, session logs
`chipyard/scripts/logs/lu_session_20261016-121948.log` (off) and `…-125617.log` (on)): both windows VALID, image `32ebbd03…` (measured).**

| Window | Image | Memory accesses | Reads / writes | Primary hits | Secondary hits | Data misses | Migrations | L2 cycles |
|---|---|--:|--:|--:|--:|--:|--:|--:|
| C5 off | `32ebbd03…` | 345,460,738 | 184,578,147 / 160,882,591 | 324,994,162 | 0 | 184,578,147 | 0 | 33,683,508,649 |
| **C5 on** | `32ebbd03…` | **299,681,190 (−13.25%)** | 159,436,422 / 140,244,768 | 343,397,054 | 6,176,333 | 159,436,422 | 6,665,020 | 33,326,259,279 (−1.06%) |
| C5 on, window B (also ran LU's `-t` check) | `32ebbd03…` | 325,584,338 (−5.75% vs C5 off) | 172,766,498 / 152,817,840 | 329,432,821 | 6,706,113 | 172,766,498 | 7,044,290 | 33,579,638,190 |
| C4 off | `e4129baa…` | 340,244,370 | 181,888,095 / 158,356,275 | 327,644,062 | 0 | 181,888,095 | 0 | 33,659,397,767 |
| C4 on | `e4129baa…` | 314,209,310 (−7.7%) | 166,610,854 / 147,598,456 | 335,676,206 | 6,508,340 | 166,610,854 | 6,433,209 | 33,437,808,376 |
| C4 on, r2 | `e4129baa…` | 312,750,155 (−8.1%) | 166,106,587 / 146,643,568 | 336,010,557 | 6,405,369 | 166,106,587 | 6,766,192 | 33,472,393,178 |

Accesses (inner A) are 508.5–509.6 million in every window, B included, so all six did the same work; B's `-t` check adds no
measurable L2 traffic.

**Reading (measured vs inferred kept apart).**
- Measured: SBC on still saves memory traffic on C5; **no drop against C4** (the pass criterion). Writes fall with reads.
- Measured: the two SBC-off windows agree within 1.5% (345.5 M vs 340.2 M). With no migration the fence never acts, so C5 cannot
  change the off arm.
- Measured: the two SBC-on windows on the **same** C5 image (C and B) differ by 8.6% (299.7 M vs 325.6 M), with the same number of
  accesses and similar migrations (6.67 M vs 7.04 M). The C4 on windows lie between them.
- Inferred: the −13.25% vs −7.9% difference is dual-core run-to-run spread, not C5. C5 changes only requests that meet a slot on a
  fenced set, the same refusal the pre-020 RTL made at the door, and the simulation programs that do not meet that case give
  identical cycle counts on C4 and C5. Not proven by one pair; so a **replicate pair on C5 is queued** after D (`$NEW/tools/board_chain_c5_r2.sh`,
  tmux `board020c5r2`, results `$NEW/board/lu/c5r2/`, driver log `$NEW/gates/board_chain_c5r2.log`).
- Pooled so far (inferred, unequal arms): the four SBC-on windows average 313.1 M, the two off windows 342.9 M: −8.7%.

**R — replicate of C (fresh boot each, 2026-10-16 15:02–16:15; `$NEW/board/lu/c5r2/{off,on}/lu-p2-n2048-b128-plru/`, driver
`$NEW/gates/board_chain_c5r2.log`): both windows VALID, image `32ebbd03…` (measured).**

| Window | Memory accesses | Reads / writes | Primary hits | Secondary hits | Migrations | L2 cycles |
|---|--:|--:|--:|--:|--:|--:|
| C5 r2 off | 333,163,178 | 178,098,102 / 155,065,076 | 331,817,808 | 0 | 0 | 33,671,445,096 |
| C5 r2 on | 323,587,394 (**−2.87%**) | 171,433,854 / 152,153,540 | 330,728,038 | 6,513,956 | 6,778,070 | 33,533,447,580 (−0.41%) |

**All two-thread LU n2048 b128 windows so far (measured; every one VALID, 508.5–509.9 million accesses each):**

| | SBC off | SBC on |
|---|--:|--:|
| C4 image (`e4129baa…`) | 340,244,370 | 314,209,310 · 312,750,155 |
| C5 image (`32ebbd03…`) | 345,460,738 · 333,163,178 | 299,681,190 · 323,587,394 (and 325,584,338 in B, which also ran LU's check) |
| Mean | 339,622,762 (3 windows, spread 3.6%) | 312,557,012 (4 clean windows, spread 7.6%) |

- Measured: the **pairs** give −7.7%, −8.1% (C4), −13.25% and −2.87% (C5). Mean of all clean on windows vs mean of all off windows:
  **−7.97%**. C5 alone (2 on vs 2 off): −8.16%; C4 alone (2 on vs 1 off): −7.86%. So **C5 and C4 agree on average**, and C5 did not
  lower performance.
- Measured: the swing sits mostly in the on arm's **primary hits** (343.4 M vs 330.7 M on the same image), with migrations about equal
  (6.67 M vs 6.78 M).
- Inferred: two-thread LU on this board needs **at least three windows per arm**; one pair can land anywhere from about −3% to −13%.
  The single-core pairs repeat within 0.2–0.7% (D), so the spread comes from running two threads (which thread touches a set first,
  and so which sets pair up), not from the board. Not tested.

**D — V7 on C5: single-core LU `-p1` n2048 b128, SBC off then setting 1 (fresh boot each, 2026-10-16 13:32–15:02;
`$NEW/board/lu1/c5/{off,on}/lu-p1-n2048-b128-plru/`): both windows VALID, image `0f02bc92…`. PASS (measured).**

| | SBC off | SBC on (setting 1) | Change | Same point on the C4 image |
|---|--:|--:|--:|--:|
| Memory accesses | 203,995,024 | 184,629,792 | **−9.49%** | 205,383,537 → 185,042,386 (−9.90%) |
| Reads / writes | 110,803,684 / 93,191,340 | 99,947,050 / 84,682,742 | −9.80% / −9.13% | |
| L2 hit rate | 77.76% | 79.94% | +2.18 points | 77.63% → 79.90% |
| L2 cycles | 58,093,972,557 | 57,826,592,559 | −0.46% | −0.48% |
| Migrations / secondary hits | — | 3,800,934 / 4,190,480 | | 3,820,568 / 4,201,153 |

Pass criterion: within 1.5 points of task 018's −10.7% and −9.7%; −9.49% is 1.2 and 0.2 points away. Against the C4 image the off
windows differ by 0.7% and the on windows by 0.2%: single-core LU repeats closely, unlike two-thread LU (C above). One window per arm.

## Findings (anything new, numbered F1, F2, …, each with measured / inferred / unknown kept apart)

**F1. Print order inside one clock cycle is not stable between simulator builds.** Measured: g0 and task 018's g1 have byte-different
`sbc.log` files of equal size and equal sorted content (md5 `6d0aec38…` for both). Inferred: Verilator orders the prints of one
cycle by how it split the generated code, and a rebuild can move them. Not known: nothing further needed. Consequence for V4 and V3:
compare cycles and counters, or sorted logs. A tool that reads `sbc.log` in order (for example a "line N followed by line M" check)
must be written order-tolerant within one cycle.

**F2. The task says "5 original freeze binaries"; there are 4 distinct ones** (see decision M3). Measured by listing `sw/build/019/`.

### F3. V2 failure: `v2_s1_d2_p1_n4` (shared lines, setting 2, PLRU, seed 4) — a migration copy read after the memory refill of its source way

**Measured (2026-10-16 06:41, found 07:01).** The stopping simulator ends the run with rc 255 after about 76,000 iterations
(L2 cycle 8,989,894; log `$NEW/sim/v2_c4_v2_s1_d2_p1_n4.log`, trace tail `$NEW/sim/trace_s1_d2_p1_n4.tail`):

`BankedStore.sv:559 … SBC shadow: copy_r touched the wrong row: set=5 way=0 stored=(tag=883e9 set=5) believed=(tag=883fc set=5)
lastWriter=1 (sinkD) wKind=0 rKind=1 (mig) writtenAt=8989887 now=8989894 rSrc=1 wSrc=104`

In words: a migration's copy read of row (set 5, way 0) found the data of line `883e9`, written 7 cycles earlier by a memory refill
(sinkD, transaction 104), while the migration (transaction 1) believed the way held its victim `883fc`. **If the copy had
proceeded, the parked line `883fc` would have held `883e9`'s data (silent corruption).** The run stopped first, so no data check ran.

**What the trace shows (measured).** The failing migration, in order: `SEC-DEFER` (miss in set 5, search held back) →
`EVICT-ASSESS way=0 dirty=0 clients=2` → `MIG-DEFER` (clean victim, probe first) → `MIG-PROBE-CLEAR` → `MIG-START src=5 dst=6` →
`SCHED-FIRE … a_valid=1 dread=1 … w_copy=1` **and `OUTER-A addr=0x8107d340 mig=1`**: the demand's memory fetch leaves in the same
schedule step as the destination read, before any copy exists. Then `DST-MASK mode=2 clean=00000000 dirty=01000010 chosen=1`,
`DREAD-RESULT dstWay=1 dirty=1`, `DST-RELEASE` / `EVICT-DST release=1`: the only usable destination way is **dirty**, so it is
written back first (task 012 C2, setting 2), `SCU START src=(5,0) dst=(6,1)`, `H3-HOLD set=6 way=1`, then the failure. The memory
refill came back and was written into (5,0) before the delayed copy read it.

**Why (code reading; consistent with the trace).** `MSHR.scala` holds the Acquire with `(!migrating || w_copy)` (the "A2 interlock":
the refill of the victim's way must not precede the copy read). But `w_copy` is `true` from reset and becomes `false` only when the
destination read result arrives, so between `MIG-START` and that result the gate is open and the Acquire goes out. That was
harmless while the copy started a few cycles after the destination read, because a memory read takes longer. Task 012 C2 made
the copy wait for the destination way's write-back to be accepted by SourceC, and settings 1 and 2 (task 018) offer dirty ways, so
the copy can start late enough for the refill to overtake it.

**Is it caused by 020? Not by anything 020 changed (inferred; being tested).** C0 to C4 do not touch the Acquire gate, the copy
lane or the refill path. It shows up only now because the old RTL froze at about 28,000 steps. A test of the unchanged 494ab5e
RTL with one active core is running (`$NEW/sim/pre494_*.log`); result below when it finishes.

**Why it matters beyond 020 (inferred, not measured on the board).** The same RTL is in the task 018 and 019 images
(`502649e2…`, `f5e3608f…`). If the hazard occurs on the board, LU's data would be wrong without any error (the sweeps checked the exit code only).
The size of the exposure is unknown: it needs a dirty destination way *and* a late copy. In this simulation it happened once in about
76,000 steps, with thousands of destination write-backs before it.

**Not fixed in 020** (outside its scope: it changes the timing of a migrating miss). A candidate fix and its cost are in F4.

### F5. V2 failure: `v2_s1_d1_p1_n5` — the L2 granted an Acquire it had not accepted. **Caused by 020 (C4's F1b).** Fixed in C5

**Measured (2026-10-16 07:49).** Stopping simulator, rc 255, after about 78,000 iterations (L2 cycle about 10,647,060; log
`$NEW/sim/v2_c4_v2_s1_d1_p1_n5.log`): `TLMonitor_35.sv:1155 … coh_wrapper.l2.monitor: ready check` (the monitor on the L2's inner port).

**Diagnosis (measured with a throwaway sim-only print, branch `sbc-020-debug-tlmon`, trace `$NEW/sim/trace_tl2_s1_d1_p1_n5.tail`).**
The check that fired is the A-channel one (`Monitor.scala:697-699`), not the C-channel one with the same message. In the failing cycle:
`TLMON-A-READY d.source=32 d.op=5 (GrantData) d.sink=1 a.op=6 (AcquireBlock) a.addr=0x810faf80 (set 6) sinkC.req.valid=0 sinkA.req.ready=0`.
The L2 sends GrantData to source 32 while the A from source 32 is still not accepted. (The C-channel pairing — a ReleaseAck and a
same-source Release in one cycle — happened 458 times in the trace tail, always with the L2 ready, so it is normal traffic.)
The trace before it: a migration 5 → 6 is in flight (set 6 is fenced), `C-FENCE-PASS set=6` (a write-back took a fresh slot, MSHR 1, on
set 6 through the fence: C4's F1b), then `REPEAT set=6 hit=1` (MSHR 1 reloads) and the Grant.

**Cause (code reading, consistent with the trace).** `request.ready` refuses a non-write-back on a fenced set
(`!(dstSetConflict && !prio(2))`), but two paths that **take** the request did not apply that refusal: the **bypass** into a reloading
slot (`bypass = request.valid && queue && bypassMatches`, both copies) and the **queue push** (`push.valid = request.valid && queue &&
!bypassQueue`). Before 020 no slot could exist on a fenced destination set, so `queue` was never true there and the mismatch was
unreachable. C4 lets a write-back hold a slot on it; an Acquire to that set then queues behind the slot, is refused at `ready`, but
the bypass hands it to the slot anyway: **one Acquire served without being accepted, and accepted again later (two Grants)**.

**Fix C5 (`Scheduler.scala`, commit `e2a71b3`; checks in the C5 block under Changes: the failing run passes).** Name the refusal once, `fenceRefuse = dstSetConflict &&
!request.bits.prio(2)`, and apply it to `request.ready`, both `bypass` definitions and `push.valid`. New assert (SBC only): a request is
bypassed or pushed only in a cycle it is accepted. Coverage print `FENCE-HOLD-QUEUE` (sbcDebug). With SBC off `dstSetConflict` is false,
so the expressions are the stock ones. Loop check: `fenceRefuse` now reaches `io.allocate` through `bypass`; `dstClaim` is built only from
registers, the directory result and the offer, never from `io.allocate` (`MSHR.scala` 387-398, 1144-1153), so no loop is closed.
Build `$NEW/bin-c5/` (contains the assert, no debug print). Runs: `$NEW/sim/v2_c5_{v2_s1_d1_p1_n5,drd_share_dst1,drd_noshare_dst1}.log`.

**Consequence.** The two 020 images (`e4129baa…`, `2f8fa210…`) do **not** have C5. On the board, a double Grant needs a write-back on a
migration's destination set while the same core's Acquire to that set races it; no board run hung or failed, but board data was not
checked. The images must be rebuilt after C5 passes.

### F4. Candidate fix for F3 (branch `sbc-021-copy-refill-gate-candidate`, commit `021b2d9`; NOT in the 020 images)

**Change (7 lines, `MSHR.scala`).** `w_copy` is set to `false` in the cycle a migration starts (both start sites: the fast path in
`armEviction` and the deferred-probe path), so the existing Acquire gate `(!migrating || w_copy)` is closed from `MIG-START` until the
copy is done, and it is set back to `true` on a destination abort so the retire does not wait for a copy that will not happen.
This is the design intent of the A2 interlock ("hold the demand Acquire until the victim copy has been fully read"), made true.

**Cost (inferred).** A migrating miss's memory fetch now waits for the destination read and the copy, roughly 25 to 35 L2 cycles
more per migrating miss. Only migrating misses are affected (0.77% of accesses at LU n4096 b128). The memory-access counts
(the headline metric) cannot change except through the data fix itself. The run-time cost is being measured in simulation (below).

**Check (2026-10-16 07:50–08:55, measured).** Candidate two-core simulator `$NEW/bin-021c/simulator-stop-t3`, logs `$NEW/sim/v2_021c_*.log`.

| Program | C4 (`af36949`) | Candidate (`021b2d9`) | Change in L2 cycles | Migrations C4 / candidate | Cost per migration |
|---|---|---|--:|--:|--:|
| `drd_noshare_dst1` (setting 1) | PASS, 21,353,290 cycles | **PASS, data OK**, 22,325,866 cycles | **+4.55%** | 55,231 / 55,236 | about 17.6 L2 cycles |
| `v2_s0_d2_p1_n1` (setting 2) | PASS, 21,292,102 cycles | **PASS, data OK**, 22,214,929 cycles | **+4.33%** | 50,372 / 50,235 | about 18.4 L2 cycles |
| `v2_s1_d2_p1_n4` (the F3 failure) | **FAIL** at about 76,000 iterations | **PASS, data OK**, all 200,000 iterations, 22,457,302 cycles | — | — | — |

**What the cost means (inferred).** These programs migrate once every ~400 L2 cycles, far more often than a real workload. Scaled to
the dual-core LU window above (6.4 million migrations in 33.4 billion cycles), 18 cycles per migration is about +0.35% run time; the
measured SBC run-time gain there is −0.6%. Memory-access counts (the headline metric) do not depend on this timing. So the
candidate fixes the data hazard at a real but small run-time cost.

**Second F3 case on the candidate (2026-10-16 15:12–15:18, measured).** V3's `v3_s0_d1_p1_n18` (setting 1, unshared; F3 assert
twice on C5) run non-verbose on the candidate's **stopping** simulator `$NEW/bin-021c/simulator-stop-t3`: 100,000 iterations, data OK,
rc 0, no assert (`$NEW/sim/f3b_021c_v3_s0_d1_p1_n18.log`). L2 cycles 11,247,051 vs 10,746,341 on C5 (+4.7%, the same cost as above).
Caveat: `bin-021c` is C4 + the candidate, not C5 + the candidate; shipping it means re-applying `021b2d9` on `e2a71b3` and
re-running the matrix, images and board checks.

**Cheaper options, not built (for the user's decision).** (a) Hold the Acquire only until the destination read returns (about 6
cycles), and hold it to the end of the copy only when that read chose a dirty way that must be written back first (the case that
made the copy late). (b) Leave the Acquire alone and hold the *refill write* of the victim's way until the copy has read it. (b) is the
exact interlock but touches the refill path (SinkD), so it needs its own deadlock argument.

## Verdict

**Done (2026-10-16, RTL `e2a71b3`, branch `sbc-020-tilelink-merge`, not pushed).** SBC now obeys TileLink's write-back rules: a
client's write-back of a parked line is always accepted, never waits for the partner row, and is merged into every slot that holds the
line (D1–D8). No SBC decision changed.

Measured on the final RTL:
- **The freeze is gone.** Board: 4/4 freeze-pattern rungs on the C5 image (and 7/7 on the C4 image); the two-thread LU that froze
  before ran 7 full windows. Simulation: the 4 freeze programs (5/5 frozen before) pass with data OK on both simulators.
- **Correctness.** Two-core matrix 65/66 (the 1 = F3, older); coverage: every new path fired; LU's output with SBC on equals the host
  reference; single-core regression 24/24 identical to the pre-020 baseline.
- **Performance did not drop.** Single-core LU n2048 b128: −9.5% memory accesses (task 018: −10.7% / −9.7%). Two-thread LU: −8.0%
  on average over 4 on and 3 off windows, C5 equal to C4.
- **Timing.** Both images meet timing (dual +0.425 ns, single +0.646 ns), no LUTLP loop.

Not done here, by decision: **F3** (older copy-after-refill hole; seen twice in simulation, once with the board's setting 1) moves to
its own task (Q8), with candidate `021b2d9`. Not explained: the run-to-run spread of two-thread LU (one pair −3% to −13%).
Task 019 may resume its SBC-on dual-core points on the C5 image (`32ebbd03…`); that is the user's call. Note that the C5 image still
carries F3, so data checks there remain worthwhile.

## All log paths

- Task folder `$NEW = chipyard/scripts/logs/clean-runs/tilelink-merge-020-20261015/`:
  - `bin-494ab5e/`, `bin-c0` … `bin-c5/`, `bin-021c/` (F4 candidate), `bin-dbgtl/` (F5 debug): saved two-core simulators
  - `sim/`: every two-core run (`v2_c4_*`, `v2_c5_*`, `v2_021c_*`, `c<N>_stop_*`, `c<N>_nostop_*`, `trace_*`, `cov_*`, build logs)
  - `gates/`: drivers (`base_driver.log`, `c<N>_driver.log`, `c<N>_g3.log`, `v2_c4_driver.log`, `v2_c5_driver.log`, `v4_driver.log`,
    `fpga_builds.log`, `fpga_builds_c5.log`, `board_chain.log`, `board_chain_c5.log`), `baseline_table.md`
  - `tools/`: every script of this task (runners, queues, summaries, board chains, launcher copies are under `board/lu*/tools/`)
  - `board/`: hunt consoles and summaries (`console_boot1/2.txt`, `console_c5_boot1.txt`, `runs/`), `ladders/`, LU campaigns
    `lu/` (dual: round 1, `r2/`, `c5/`), `lu1/` (single V7: round 1, `c5/`), `lucheck/` (failed start), `lucheck_c5/`
- Single-core gate runs: `chipyard/generators/rocket-chip-inclusive-cache/sw/verilator_logs/*_{013-020base,020-base-g*,013-020fix,020-fix-g*,020-c<N>-g3}`
- Harness logs of board stages: `chipyard/scripts/logs/lu_session_<stamp>.log` (each stage folder's `stage_check.txt` names its own)
- FPGA: `chipyard/fpga/bitstream_storage/*-tl020-af36949-2026-10-16.*` (C4 images) and `*-sbc-020-dualcore-tilelink-merge-512k-2026-10-16.*` (C5 images, with `.NOTE.txt`; named
  `*-tl020c5-e2a71b3-2026-10-16.*` until 2026-10-16 ~17:30, the name in every log before then); build logs `chipyard/fpga/build-logs/`
