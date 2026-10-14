# Coder report 018 — destination setting (dirty ways with no L1 bit)

**Started:** 2026-10-12 · **Status: CLOSED 2026-10-15.** RTL, software, simulation gate, image, two board campaigns, report; committed (RTL `494ab5e`), merged into `sbc-013-sat-counter`, tagged `sbc-018-dstmode-512k-n4096-b128-best-2026-10-15`. Best result: n4096 b128 **−20.9%** (replicate −21.1%) memory accesses, setting 1 vs SBC off.

## ▶ RESUME HERE
- **Fresh session: read `HANDOFF.md` (same folder) first** — current state, tmux sessions, what to do when the chain ends.
- Branch `sbc-018-dst-dirty-ways` from `b46a8ac`. **Committed 2026-10-15** (user: "commit them appropriately"): RTL `494ab5e`, sw `766a0fb`,
  `l2_miss_calib` `6b4ee42`, docs in the following commit; merged fast-forward into `sbc-013-sat-counter`; tag `sbc-018-dstmode-512k-n4096-b128-best-2026-10-15`.
  The stored image was renamed `FPGASingleRocketVCU118L132K512K8WL2ConfigSBCPLRUDst-512KB-8way-L1-32K8W-dst018-494ab5e-best-n4096-b128-2026-10-13.*` (sha256 unchanged). See "Commit, merge, tag" below
- RTL, `sbc_read_018`, configs, gate script, directed case, image: done (see below). Gate: I1–I4 PASS, I5 g5 fails (F4).
- **First campaign** `scripts/logs/clean-runs/lu-dstmode-single-core-512k-20261013/` (arms off / m0 / m1 / m2, 3 points):
  **stopped by the user 2026-10-13 08:39:24** during the staging of stage 13 (off n768 b128; no window measured, kept under
  `aborted/`). 12 valid windows. Corrected calib sweep (tmux `dst018c2`) was cancelled with it and is **not run**
- **User decision 2026-10-13: "I think m=1 is good for our tests."** Setting 1 is the one used from here.
- **Second campaign — finished 2026-10-13 18:44, 18 of 18 valid, none void** (`CHAIN FINISHED ok=1`). Two more rounds queued by
  the user at 21:49 (tmux `dst1extra`, `tools/drive_rounds.sh`), **finished 2026-10-14 05:20:55, rc=0, 4 of 4 valid**: **r2** = n2048 b128
  off/on in `r2/` (−9.68%), then **n4096 b128** off/on in the root (`-tmo 21600`, launcher v2; −20.88%); predictions frozen in PLAN.md
  addendum 21:48. n4096 b256 was offered and not wanted.
  `scripts/logs/clean-runs/lu-sweep-dstmode1-single-core-512k-20261013/` (layout of task 017's folder:
  `off/ on/ failed/ dropped/ aborted/ results/ tools/ vivado/ PLAN.md RESULTS.md chain_status.txt`): LU `b` 64 / 128 / 256 ×
  `n` 512 / 1024 / 2048, SBC off vs setting 1, 9 points × 2 = 18 windows, pairs per point, fresh boot per window. tmux
  `dst1sweep` (`tools/drive_sweep.sh`), started 2026-10-13 08:42:34, expected end ≈ 19:00. PLAN.md (predictions frozen
  08:44) is there. The request said "16 combinations"; the lists give 9 points, so all 9 run
- Never kill `dst1sweep` while a window runs. Board: no other harness, programFPGA or picocom while it runs
- Still open: corrected `l2_miss_calib` sweep (the user chose "after the LU sweep"; needs a new go), replicate windows
  at n512 b128 (between-boot spread ≈ 2.7 pp), the commit, doc suggestions (done 2026-10-13 on the user's say: CLAUDE.md,
  `ai-documents/README.md`, `coder/README.md`)
- User wants long jobs in **tmux**; answers in simple English, point form, compact tables

## Closed (2026-10-15)
- **Delivered:** runtime `SBC_DstMode` / `SBC_DstRescue` in the RTL (commit `494ab5e`), `sbc_read --dstmode`, the simulation gate and the
  directed case, one 512 KB image (sha256 `502649e2…`, stored as `…-dst018-494ab5e-best-n4096-b128-2026-10-13.*`), two board campaigns
  (26 + 12 valid windows), this report
- **Answer to the task's question:** yes, setting 1 turns held aborts into useful migrations, at the b=128 points and growing with n
  (−5.7, −4.6, −10.7, −15.7, −20.9% at n 512 / 1024 / 2048 / 3072 / 4096), with no extra traffic; nothing at b=64 / b=256 below n = 2048
- **Where the work differs from the TASK (all on the user's instruction):** the image was built from uncommitted RTL (committed afterwards,
  all 26 blobs verified equal); the 4-arm chain was stopped at 12 of 32 windows and replaced by a setting-1-only sweep; the corrected
  `l2_miss_calib` sweep was cancelled and not run (the first run was unusable, F5); I5 fails for setting 2 in simulation (F4), not seen on the board
- **Not part of 018 (follow-ups, the user's call):** corrected `l2_miss_calib` sweep; `lu_ncb -t` data check with SBC on; n4096 b64 / b256;
  setting 0 at n4096; bodytrack 1600 on this image (TASK §13 q4, proposal only); push to a remote
- **State at close:** board free, no run, no tmux job active (idle shells only); old `sbc_read` build unchanged (`8241eb8b…`)

## Summary — what we did, what we saw, why, what is left (2026-10-15)

### What we did
- **Built a runtime destination setting** (`SBC_DstMode`, 0x4B0) and a counter (`SBC_DstRescue`, 0x4B8). When an SBC migration needs a
  destination way, today's rule (setting 0) accepts only a clean way with no L1 bit. Setting 1 takes a clean way first, else a **dirty way
  no L1 holds** (the dirty line is written back); setting 2 takes the least-recent of either. Held ways stay an abort: no probe
- **Proved in simulation** that flag off and setting 0 behave exactly like the parent commit, and that settings 1 and 2 are safe
  (I1–I4 pass; I5 fails for setting 2 in the stock stress test, F4, and was not seen on the board)
- **Built one image** (512 KB 8-way L2 + 32 KB 8-way L1, 1 core; WNS +0.526 ns) and **measured LU alone on the board**, one fresh
  reprogram + boot per window (38 valid windows):
  - campaign 1: SBC off and settings 0 / 1 / 2 at 3 points (12 windows) → the user chose setting 1
  - campaign 2: SBC off vs setting 1 at b 64 / 128 / 256 × n 512 / 1024 / 2048 (18 windows), then a replicate of n2048 b128,
    n4096 b128 and its replicate, and n3072 b128 (8 windows)
- **Committed, merged and tagged** the RTL, software and configs (tag `sbc-018-dstmode-512k-n4096-b128-best-2026-10-15`)

### What we saw (setting 1 vs SBC off, memory accesses = DRAM reads + writes)

| n \ b | b=64 | b=128 | b=256 |
|---|--:|--:|--:|
| 512 | +0.0% | **−5.7%** | −0.3% |
| 1024 | −0.9% | **−4.6%** | −0.4% |
| 2048 | −1.4% | **−10.7%** (replicate −9.7%) | −0.5% |
| 3072 | not run | **−15.7%** | not run |
| 4096 | not run | **−20.9%** (replicate −21.1%) | not run |

- **Setting 1 beat setting 0 and setting 2** where they were compared (campaign 1): n512 b128 −4.97% vs −1.97% (setting 0) and −3.46%
  (setting 2); n1024 b64 −1.32% vs −0.37% and −1.35%; n384 b128 +0.47% vs +0.90% and +0.94% (nothing to win there). Setting 2 wrote
  back 1.7–3.6× more destination lines than setting 1
- **No point increased traffic** (largest rise +0.02%); writes fall with reads; destination write-backs are 0.25% of writes
- **Replicates agree:** n2048 b128 1.1 pp apart, n4096 b128 0.23 pp, the off arms within 0.4%; so the effects here are real
- **Run time on this board moves little** (L2 cycles −0.2% to −1.4% at the b128 points): a DRAM read costs only ~38 L2 cycles at 50 MHz.
  The labelled model projects −6.7% at n4096 b128 with 200 cycles per read (a model, not a measurement)
- **Prediction record:** 8 of 9 per-point bands and 7 of 7 sweep-level predictions right in campaign 2; the one miss (n2048 b128) and
  n4096 b128 were better than predicted

What the numbers look like inside (SBC on, one window per point; "Δreads per migration" = change in DRAM reads ÷ migrations):

| point | OFF hit | migrations per access | held share | Δreads per migration | primary hits | secondary hits | change |
|---|--:|--:|--:|--:|--:|--:|--:|
| n512 b64 | 95.2% | 0.08% | 48% | −0.0 | −12 k | 1 k | +0.0% |
| n1024 b64 | 95.5% | 0.06% | 36% | −0.6 | +37 k | 23 k | −0.9% |
| n2048 b64 | 95.7% | 0.04% | 34% | −1.4 | +141 k | 254 k | −1.4% |
| n512 b128 | 83.1% | 0.11% | 58% | −8.4 | +44 k | 17 k | −5.7% |
| n1024 b128 | 79.9% | 0.60% | 37% | −1.6 | +344 k | 268 k | −4.6% |
| n2048 b128 | 77.6% | 0.77% | 38% | −3.2 | +8.0 M | 4.2 M | −10.7% |
| n3072 b128 | 75.2% | 0.78% | 46% | −5.1 | +46.4 M | 19.9 M | −15.7% |
| n4096 b128 | 72.9% | 0.77% | 50% | −7.4 | +167.7 M | 58.6 M | −20.9% |
| n512 b256 | 29.0% | 1.16% | 12% | −0.3 | −38 k | 59 k | −0.3% |
| n1024 b256 | 25.4% | 1.54% | 17% | −0.3 | −386 k | 642 k | −0.4% |
| n2048 b256 | 23.7% | 1.65% | 24% | −0.4 | −2.8 M | 6.7 M | −0.5% |

### Why — the high-level explanation
**Measured (the mechanism):**
1. Setting 0 almost never found a slot: 80–90% of attempts hit a destination way held by an L1. Setting 1 uses a dirty way with no L1
   bit instead, so the held share falls to 12–58% and migrations rise 1.3–2.4×
2. A migration pays back in two ways (hit balance, exact): the moved line is found later in the partner set (**secondary hit**), and the
   way it left in the overloaded set keeps another line alive (**primary hits rise**). Reads saved = both terms
3. It pays only where the L2 is moderately loaded. At b64 the L2 already hits 95% (little to fix); at b256 it hits 24–29%
   and migrations are plentiful (1.2–1.6% of accesses) but **primary hits fall** while secondary hits rise: a moved line displaces
   about as many lines as it saves, net about zero
4. At b128 the migration rate is flat at 0.77% of accesses from n2048 up, while the reads saved **per migration grow in step with n/b**
   (−0.20, −0.20, −0.21, −0.23 per block step at n1024 … n4096). So the saving fraction grows with n

**My reading of why (consistent with the numbers, not separately tested):**
- A 128 × 128 block of `lu_ncb` is 128 KB: 4× the L1, a quarter of the L2. Its rows are n × 8 B apart, so its lines pile unevenly on
  the L2 sets: some sets overflow their 8 ways while others sit half empty. That is exactly the case SBC is built for: lend the empty
  ways of the partner set to the overloaded one
- The same overflow pattern is met again on every block step of the factorisation, and a lent line can be re-read once per step, so the
  payoff of one migration grows with the number of steps (n / b) while the number of migrations per access stays constant
- b64 (block 32 KB, about the L1) has little overflow; b256 (block 512 KB, the whole L2) overloads **every** set, so there is no spare
  way to lend and migration only reshuffles which lines miss
- **Rejected:** "it is power-of-two row-stride aliasing". n3072 has a 24 KB stride (8 offsets across the 64 KB L2 span, like n1024) and
  still saved −15.7%, on the trend line, so the saving follows the problem size

### What is not established
- One window per arm except n2048 b128 and n4096 b128 (two each); b64 and b256 not run at n3072 or n4096
- **LU output was not checked** (exit code only): with 30 M migrations at n4096 a data check with SBC on is the missing safety evidence
- "Best" is against SBC off: setting 0 and setting 2 were not run at n3072 / n4096, so the share of the gain due to setting 1 over
  today's SBC is measured only at the three campaign-1 points
- The corrected `l2_miss_calib` sweep did not run, so the controlled "why" model (MP ≤ 2·ways − HP) is unchecked on this image
- The geometry reading above is a hypothesis; a per-set profile (`SBC_SetSel` / `SBC_SetSat`, `sw/sat_snap.c`) of n4096 b128 would test it directly
- One core, one workload (LU), one image, PLRU. Other workloads (bodytrack lost 4% at 1600 particles with setting 0) were not run with setting 1

### What is left to do (all outside 018's scope, the user's call; nothing is queued)

| # | item | why | cost |
|---|---|---|---|
| 1 | `lu_ncb -t` data check with SBC on (setting 1) at n1024 or n2048 | makes the headline safe to quote; LU output is unchecked | about 1 h, one window |
| 2 | setting 0 (and 2) at n4096 b128, one window each | attributes the −21% to setting 1 against today's SBC | about 3 h per window |
| 3 | per-set profile of n4096 b128 (`sat_snap`, off and on) | tests the "uneven sets" reading directly | one boot each, short |
| 4 | n4096 b64 and b256 pairs | completes the grid; b64 crept up with n (0.0, −0.9, −1.4%) | about 6 h per pair |
| 5 | corrected `l2_miss_calib` sweep (`-l1sets 64 -l1ways 8`) | checks the migration-pays rule on a controlled workload | about 1 h |
| 6 | setting 1 on other workloads (bodytrack 1600 / 4000, LU + FFT, LU pairs, dual core) | does it help or hurt outside LU alone | days |
| 7 | push the L2 branch and tags | nothing is pushed; `origin/sbc-013-sat-counter` is 5 commits behind (still at `b46a8ac`) | minutes |

## User decisions (2026-10-12, answers to §13)
| # | §13 question | answer |
|---|---|---|
| 1 | branch from `sbc-013-sat-counter` | start from the latest commit. **Do not commit anything until the user says.** A new branch is fine |
| 2 | offsets 0x4B0 / 0x4B8 | OK if free (they are, Preflight) |
| 3 | commit RTL at stop (b) | yes, but only on the user's explicit say (see 1) |
| 4 | points | "verify in simulation first, then move into RTL after my approval". Read as: G0 baseline sim first; **RTL edits only after approval**. Points n512 b128 / n1024 b64 not discussed yet |
| 5 | order off → m0 → m1 → m2 | the user asked what each setting does; answered in chat |
| 6 | build while 017 runs | **yes, a build is allowed. Do not start any FPGA run.** Overrides §0 rule 3 for the build only. Board stays off-limits |
| 7 | one window per arm | yes for now |
| 8 | scope (user, after the setting explanation) | **Only clean and dirty ways with no L1 bit. Nothing new for held ways.** A held way stays today's abort: no probe, no new path, no MSHR change |
| 9 | held-related extras (user: "accept your recommendations") | **Keep** the `SBC_DstRescue` counter (0x4B8, observation only) and the directed case `case_held_lru_dirty_alt` (`#ifdef SBC_DSTMODE_SET` only). This is not approval to edit RTL: that is still to be given after G0 |

## Findings
- None blocking. Differences from §2 are line-number drift only (listed in Preflight).
- TASK §9 says build "only after the commit". The user has also said no commit until told. So the build waits for the commit order.
- **F1 — the §8.2 whole-file sha check cannot pass, even with no source change.** Each compile links `crt.S`
  through a random temp object, and its name lands in the ELF symbol table (`FILE … cc4sCeuq.o`). Two compiles of
  the same source with the same command give different file shas (`391d487d…` vs `6d2ebb23…`).
  The **loadable bytes** (`objcopy -O binary`) are identical: HEAD sources and the 018 sources both give
  `0c10d0f917cba42c…` for `migration_stress_test` with `-DL2_POLICY=1`. So the 018 edits do not change the g0–g2
  programs. The binary check is done on loadable bytes instead, and I1/I2 (identical counters and cycles) are the
  real proof. Evidence: scratchpad `cc/a/x.riscv` vs `cc/b/x.riscv`, `cc/a/o.bin`.
- **F2 — the directed case needs more than the TASK sketch (design note, not a failure).** On the sim (8 L2 sets,
  2×2 L1 I$ and D$) three things break the naive "read 7 lines, write 1" recipe:
  - teardown: 8 fresh D lines push every guest of S out, the pairing ends, and the next migration goes elsewhere;
  - the test's own instruction fetches: a code line in D is a clean way with no L1 bit (the I$ is not a TL-C client);
  - the stack: any spill after the sweep refills D.
  The case therefore keeps the guest that the last migration installed (tree PLRU replaces the other 7 ways
  first), and runs the critical part as a 140-byte asm block, in 8 copies on sets k..k+2, choosing a copy whose
  lines avoid S and D. Details in the code comment at the end of `migration_stress_test.c`.
- **F3 — the sim host polls `tohost`/`fromhost` through the L2 (directed case, attempt 1, setting 2).** Settings 0
  and 1 passed (S=0, D=7). Setting 2 failed in setup: "B3: C never migrated". `sbc.log` shows set 0 way 1 holds
  `0x80001000` (`tohost`) and takes an inner-A hit about every 250 cycles (`PLRU-TOUCH set=0 way=1 src=0`, no miss).
  C sat beside it in the PLRU tree, so S's victim cycled over 6 ways (6,2,4,7,3,5) and never reached C. Settings 0/1
  passed only because C landed elsewhere. Sim only (no such polling on the board). Fix (attempt 2): S and D are never
  the `tohost` set (0) or the `fromhost` set (1). Evidence: scratchpad `m2_sbc.log` (from
  `sims/verilator/output/…DstConfig/dst018_dev_m2.out`).
- **F4 — setting 2 raised held aborts in the stock stress test (I5 fails for g5).** g3 (setting 0) 5 held-type aborts,
  g4 (setting 1) 0, **g5 (setting 2) 30**. All 30 are into set 7, early in the run (sbc.log lines 207,747–208,836
  of 2.7 M), each with `DST-MASK clean=00000000 dirty=00000000` (every way held) and the offered way a **guest**
  (`displaced=1 clean clients=1`): parked lines that were served in place and then dropped silently by the L1. No
  setting can use an all-held set, and the mask never offered a held way (safety intact, I4). Setting 2 took dirty
  ways where 0/1 took clean ones (20 `DST-RELEASE` vs 3/4), so this 8-set cache followed another path and filled
  set 7 with served guests. Not verified beyond the log. It says setting 2 can leave more destinations fully held
  than setting 1; the board's m2 arm measures exactly this (PLAN prediction 1). Evidence:
  `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2DstConfig_018-g5/sbc.log`.
- **F5 — calib run 1 is not usable (my error).** The board's Linux has no `/sys/…/cpu0/cache` entries, so
  `l2_miss_calib` fell back to a 32 × 4 L1 (it said so on every run: `L1 D-cache : 32 sets x 4 ways (ASSUMED, not
  found in Linux), one-group layout`). On the 64-set L1 that is the old broken layout: the hit lines stayed in the
  L1. Result: −99% memory reads up to MP ≈ 16 and a cliff that ignores P (the miss sets took the hit sets' ways).
  Kept as `calib/012-018-calib-20261013-022729.log` and `calib/calib_table_run1_L1-assumed-32x4.md`, labelled.
  Fix: `run_calib_sweep_018.exp` passes `-l1sets 64 -l1ways 8` (and `-r 16`, 12,500 steps: same work, L1 survival at
  P=2 ≈ 1.2%); run-1 copy kept as `tools/run_calib_sweep_018.run1.exp`. **User (02:56): re-run after the LU sweep.**
  Queued in tmux `dst018c2` (`tools/drive_018_calib2.sh`): waits for `drive_018.sh` "ALL DONE", runs only if the LU
  chain ended rc=0. Boot smoke test of the image from run 1's boot: Linux 6.6.0, 1 CPU, no panic, binaries staged.
- **Calib app, two issues on the 512 KB image (user: "modify calib app to fix parameterize cache configs"):**
  - the app assumed an 8 KB 4-way L1 (L1 set = page bits 10:6). The 32 KB 8-way L1 indexes the whole page, so H
    lines never left the L1 and the L2 saw no H traffic. Fixed: L1 geometry from Linux cacheinfo or `-l1sets/-l1ways`;
    when the L1 index is wider than half the set span, M pages come from a second frame group at the H lines' offsets;
    `-r R` adds L1 fills; the run prints the chance an H line is still in the L1 (warns above 1%);
  - the old one-boot sweep drains with `l2_miss_calib -n`, which thrashes one 64-set page group: enough for the
    64-set L2 it was built on, not for 1024 sets. Added `-D MB` (read MB of fresh pages twice).
  Build: `sw/build/l2_miss_calib_018` = `sw/build/018/l2_miss_calib` (`3585464179ec632d…`); the old
  `sw/build/l2_miss_calib` (`56ab979a…`) is untouched. With no new options the old geometry behaves as before.

## Preflight (§6)
Run 2026-10-12 19:54. All read-only.

| check | result |
|---|---|
| RTL branch | `sbc-013-sat-counter` ✓ |
| HEAD | `b46a8ac` ✓ (docs only on top of `fed7227`) |
| `git diff --stat fed7227 HEAD -- design/` | empty ✓ — `design/` = RTL of the 512 KB image `37923e6b…` |
| uncommitted `design/`, `sw/*.c`, `sw/*.h` | none ✓. Working tree holds others' doc edits only (`CLAUDE.md`, `ai-documents/…`, `.obsidian/`, `scratch/`): never commit them |
| `0x4B0` / `0x4B8` free | grep empty in `Control.scala`, `sbc_mmio.h`, `sbc_read.c` ✓ |
| branch `sbc-018-dst-dirty-ways` | does not exist yet ✓ |
| `Directory.scala` mask | `evictableOH` `:210-211` ✓; `freeWays` `:218`; **`evictOH` is `:223`**; victim mux `:227-235`; assert `:240` ✓; `io.usePlru` is an `Option` at `:117` ✓; printfs `:337-358` ✓ |
| `MSHR.scala` destination decision | `DREAD-RESULT` block `:1610-1690` ✓; `dstFree` `:1621`, `dstEvictable` `:1624`, `dstReleasable` `:1631` (needs live `usePlru`, any dirty way with no clients) ✓ |
| `usePlru` wiring | `Control` `:61, :186, :188` ✓ → `InclusiveCache` `:155, :205` ✓ → `Scheduler` `:55, :118, :210` ✓ |
| `dstAbortHeld` wiring | `Scheduler` `:848-849` ✓ → `PerfCounters` `:42, :74, :227, :243, :264, :281` ✓ → `Control` `:175`, regmap `:301-304` ✓ |
| micro parameters | `plruReplacement` last field at `Parameters.scala:147` ✓; `Configs.scala` param `:73`, micro build `:134` (TASK says `:116-133`: off by one) |
| `sbc_mmio.h` | `:65-69` ✓, `sbc_set_policy` `:80-87` ✓ |
| `sbc_read.c` | table end `:88` ✓; `show()` policy print `:151` ✓; options `:236-251` ✓; policy write `:298-307` ✓ |
| tests calling `sbc_set_policy` | `migration_stress_test.c`, `sbc_migrate_switch_test.c`, `dirty_guest_evict_test.c` ✓ |
| gate scripts | `run_012_gate.sh`, `run_013_gate.sh`, `run_sbc.sh` present ✓ |
| chipyard configs | `RocketConfigs.scala` `:302`, `:376`, `:387` ✓; `vcu118/Configs.scala:188` ✓ |
| `sw/build/sbc_read` sha256 | `8241eb8b8b7e910b…` ✓ (record again after the work, rule 4) |
| harness sha256 | `b13d93e768b04559…` ✓; `LIB` `:97`, board path `:111`, `-noprog` `:161`, `CSV_COLS` `:472-479`, `sbc_read` call `:533`, `stage_sbc_read` `:731` (host path `:734`) ✓ |
| 017 launcher / checker | `launch_lu_sweep_v6.sh` (mtime 2026-10-12 17:33), `check_lu_stage.py` present. Copy them at §10, not before |
| does 017's chain rebuild `sbc_read` from source? | No. Harness, lib and launcher only read `$SWD/build/sbc_read`. Editing `sw/sbc_read.c` cannot disturb it |
| **task 017 state** | **Not ended.** `driver_status.txt` last line: `18:14:19 DRIVER ROUND sweep starts` (8 labels). An `expect … run_parsec_session.exp -plan lu` is running. tmux `lu512`, `lu512drv` open. Planned end ≈ 05:00 2026-10-13 (TASK). Steps 4–7 wait |

## RTL and software changes (§7)
| file | what changed | why |
|---|---|---|
| `design/craft/inclusivecache/src/Parameters.scala` | `dstModes` flag (default false; requires SBC + PLRU) | compile-time switch; flag off = parent commit |
| `.../Configs.scala` | `WithInclusiveCache(... dstModes)` argument | config access |
| `.../Control.scala` | `SBC_DstMode` 0x4B0 (R/W, 2 bits), `SBC_DstRescue` 0x4B8 (R, 64 bits) | the setting and its counter |
| `.../InclusiveCache.scala`, `.../Scheduler.scala` | wiring of the setting to the directory, the rescue pulse to the counter | plumbing |
| `.../Directory.scala` | destination eviction mask (modes 1, 2), gated on the live `L2_Replacement`; rescue pulse | the only behaviour change |
| `.../PerfCounters.scala` | 64-bit `dstRescue` counter | observation only |
| `sw/sbc_mmio.h`, `sw/sbc_read.c` | `--dstmode`, `dstRescue` column, `dstMode` in the window line | board and sim tool |
| `sw/migration_stress_test.c`, `sw/scripts/run_018_gate.sh` | directed case under `#ifdef SBC_DSTMODE_SET`; gate stages g0..g6b | simulation gate |
| `sw/l2_miss_calib.c` | L1 geometry options (user request) | calibration app |

Branch: — · commit: — · `sbc_read_018` sha256: — · old `sbc_read` sha256 unchanged: — (before: `8241eb8b8b7e910b`)

## Simulation gate (§8)
| stage | test | config | PASS | asserts | shadow | DST-RESCUE | held aborts | migrations | cycles |
|---|---|---|---|---|---|---|---|---|---|
| g0 | switch | `…L2Config` | PASS | NONE | 0 | n/a (no RTL yet) | 0 | 1,486 | 1,402,206 |
| g0 | stress | `…L2Config` | PASS | NONE | 0 | n/a | 132 (all `dirty=0 held=1`) | 64,183 (of 64,315 attempted) | 26,359,206 |
| g0 | switch | `…L2NoSbcConfig` | PASS | NONE | 0 | n/a | n/a | 0 | 505,366 |
| g0 | stress | `…L2NoSbcConfig` | PASS | NONE | 0 | n/a | n/a | 0 | 26,134,936 |

G0 ran 2026-10-12 20:07:50 → 20:33:30 (25 min 40 s, two simulator builds), `SBC_CLEAN=1`, `EXTRA_CFLAGS=-DL2_POLICY=1`, RTL = `b46a8ac`, no edits.
Console `sw/verilator_logs/018-g0-console.txt`; folders `sw/verilator_logs/{sbc_migrate_switch_test,migration_stress_test}_{VerilatorRocket8KL116KL2Config,VerilatorRocket8KL116KL2NoSbcConfig}_018-g0/`.

**Test binaries after G0** (§8.2: must be identical after g1 and g2):
- `sbc_migrate_switch_test.riscv` `4be8575c1e33a4df54e8b12582517b4a13aaa0b86c754c763c32db98f7a89532`
- `migration_stress_test.riscv` `ce1de2a4969a1f4fe1c8794d72ce9557a78391c65ecb90825b4fafa0da21c53a`
- the stress test's 132 held aborts match task 013's gate (132 of 64,315)

| g1 | switch | `…L2Config` (flag off) | PASS | NONE | 0 | n/a | 0 | 1,486 | 1,402,206 |
| g1 | stress | `…L2Config` (flag off) | PASS | NONE | 0 | n/a | 132 | 64,183 | 26,359,206 |
| g1 | switch | `…L2NoSbcConfig` | PASS | NONE | 0 | n/a | n/a | 0 | 505,366 |
| g1 | stress | `…L2NoSbcConfig` | PASS | NONE | 0 | n/a | n/a | 0 | 26,134,936 |

g1 ran 2026-10-13 ~23:50 → 00:17, `CLEAN=1`, console `sw/verilator_logs/018-g1-console.txt`.

**I1 (flag off = today): PASS.** For both tests and both configs, g1 vs g0:
- the whole `<test>.log` is identical (`diff -q`), so every `[SBC-…]` counter line, PASS line and cycle count matches;
- `sbc.log` (the RTL printfs) has the same lines in a different order within a cycle: equal line counts, and
  `diff <(sort g0) <(sort g1)` is empty. Verilator's same-cycle printf order follows the netlist build, not the design;
- test binaries: loadable bytes equal the HEAD-source build (`migration_stress_test` `0c10d0f9…`,
  `sbc_migrate_switch_test` `682bfca1…`). The whole-file sha differs by design of the toolchain (F1).

| g2 | switch | `…L2DstConfig` (setting 0, untouched) | PASS | NONE | 0 | 0 | 0 | 1,486 | 1,402,206 |
| g2 | stress | `…L2DstConfig` (setting 0, untouched) | PASS | NONE | 0 | **97** | 132 | 64,183 | 26,359,206 |

g2 ran 2026-10-13 00:18 → 00:31, `CLEAN=0`, console `sw/verilator_logs/018-g2-console.txt`.

**I2 (flag on, setting 0 = today): PASS.** g2 (`…DstConfig`) vs g0 (`…L2Config`), both tests:
- TASK `cmpl` (all `[SBC-…]`, PASS/FAIL, `Completed after` lines): 0 differing lines; the only `.log` difference is the
  config name inside the simulator's `$finish` path;
- `sbc.log` with the two new printfs (`DST-MASK`, `DST-RESCUE`) removed: sorted diff vs g0 is empty;
- test binaries: loadable bytes equal g0/g1 (`0c10d0f9…`, `682bfca1…`).

Under setting 0 the stock stress test logs **97 `DST-RESCUE` against 132 held aborts**: in about 3 of 4 held aborts a
dirty way with no L1 bit was free (all 132 are `dirty=0 held=1` on the offered way).

**The directed case alone** (scratchpad wrapper `dev/dst018_dev.c` = `migration_stress_test.c` with a `main` that runs
only the case, on `…DstConfig`, label `018-dev` / `018-dev2`):

| attempt | setting | S / D / P / copy | held abort | rescue | migrations | trigger in `sbc.log` | result |
|---|---|---|--:|--:|--:|---|---|
| 1 | 0 | 0 / 7 / 1 / 1 | +1 | +1 | +0 | `DST-MASK clean=00000000 dirty=01000000 chosen=5 v0=5`; `ABORT-DST dstWay=5 dirty=0 held=1`; `DST-RESCUE v0=5` | PASS |
| 1 | 1 | 0 / 7 / 1 / 1 | +0 | +1 | +1 | `DST-MASK … dirty=01000000 chosen=6 v0=5`; `DST-RELEASE dstWay=6` (N7) | PASS |
| 1 | 2 | 0 / 7 / 1 / 1 | — | — | — | setup failed: "B3: C never migrated" (F3, `tohost`) | FAIL |
| 2 | 2 | 2 / 7 / 1 / 3 | +0 | +1 | +1 | `DST-RESCUE v0=4`; `DST-RELEASE dstWay=6` (N7) | PASS |
| 2 | 0 | 2 / 7 / 1 / 3 | +1 | +1 | +0 | — | PASS |
| 2 | 1 | 2 / 7 / 1 / 3 | +0 | +1 | +1 | — | PASS |

In every run the one `TEARDOWN` comes after the trigger (the case's own data-check reads), so the pairing held
through the measurement. Logs: `sw/verilator_logs/dst018_dev_m{0,1,2}_VerilatorRocket8KL116KL2DstConfig_018-dev{,2}/`.

**g3–g6b** (tmux `s018gate`, 00:38 → 01:42, consoles `sw/verilator_logs/018-g{3,4,5,6a,6b}-console.txt`), all on `…DstConfig`:

| stage | test | PASS | asserts | shadow | DST-RESCUE | held aborts (`[SBC-DSTABORT]` held / both) | migrations | cycles | directed case |
|---|---|---|---|---|--:|---|--:|--:|---|
| g3 (set 0) | switch | PASS | NONE | 0 | 0 | — | — | 1,584,906 | — |
| g3 (set 0) | stress | PASS | NONE | 0 | 5 | 1 / 4 | 45,533 | 24,944,926 | PASS (S=4 D=2: held+1, rescue+1, mig+0) |
| g4 (set 1) | switch | PASS | NONE | 0 | 0 | — | — | 1,584,906 | — |
| g4 (set 1) | stress | PASS | NONE | 0 | 2 | 0 / 0 | 45,537 | 24,944,476 | PASS (held+0, rescue+1, mig+1) |
| g5 (set 2) | switch | PASS | NONE | 0 | 0 | — | — | 1,458,386 | — |
| g5 (set 2) | stress | PASS | NONE | 0 | 1 | **30 / 0** | 43,144 | 24,759,006 | PASS (held+0, rescue+1, mig+1) |
| g6a (random, set 0) | switch / stress | PASS / PASS | NONE | 0 | 0 | 18,087 / 0 | 14,555 | 1,499,746 / 24,918,766 | SKIP (random policy) |
| g6b (random, set 2) | switch / stress | PASS / PASS | NONE | 0 | 0 | 18,087 / 0 | 14,555 | 1,499,746 / 24,918,766 | SKIP (random policy) |

The g3–g5 binaries carry the case and the setting write, so their stock cases run a different layout from g0–g2
(45,533 migrations vs 64,183); they are compared with each other, not with g0.

- **I3 (random policy ignores the setting): PASS.** g6a vs g6b: `<test>.log` differs only in `[SBC] dstmode=0/2`;
  TASK `cmpl` 0 lines; identical counters, migrations, aborts and cycles. `sbc.log` sorted differs in 15
  `PLRU-TOUCH` + 2 `PLRU-VICTIM` lines whose `cyc=` stamps are 1 cycle later in a short stretch near cycle 481,000,
  then line up again (host-interface timing of the different printf digit, not the RTL).
- **I4 (settings 1/2 safe): PASS.** g4 and g5: PASS, no asserts, no shadow alarm, the directed case PASS.
- **I5 (settings 1/2 do something): PARTLY FAILS → F4.** `DST-RESCUE` in g3 = 5 (≥ 1: yes). Held aborts below g3's:
  g4 yes (0 < 5); **g5 no (30 > 5)**.

I1–I5: I1 PASS · I2 PASS · I3 PASS · I4 PASS · **I5 g4 PASS, g5 FAIL (F4)** · the directed case: PASS in settings 0/1/2,
alone (attempt 2) and inside g3/g4/g5

**Note for I5 (not a finding):** all 132 baseline aborts are *clean and held*. Settings 1/2 rescue only when no clean way is free and a dirty way with no L1 bit is. So the stock stress test may show few or no rescues. The directed case (§8.3) is what proves the path.

## Build (§9)
Built 2026-10-13 00:36:41 → 01:13:34 (37 min), tmux `b018`, from the **uncommitted** working tree (user: build now,
no commit until told). Driver log `fpga/build-logs/driver-FPGASingleRocketVCU118L132K512K8WL2ConfigSBCPLRUDst-20261013-003641.log`.

| check | result |
|---|---|
| image | `fpga/bitstream_storage/FPGASingleRocketVCU118L132K512K8WL2ConfigSBCPLRUDst-512KB-8way-L1-32K8W-dst018-494ab5e-best-n4096-b128-2026-10-13.bit` (renamed 2026-10-15; built as `…-dst018-2026-10-13`) |
| sha256 | **`502649e23f77839bcfee7edaa2c1494759d5305c59fadd8a5fb428279d644720`** |
| timing | WNS **+0.526 ns**, TNS 0, WHS +0.010 ns: met (37923e6b…: +0.24 ns) |
| DRC | 54 violations, all warnings (DPIP-2, DPOP-3 …), 0 errors (37923e6b…: 54 warnings) |
| device tree | 1 core; L1D and L1I 32 KB, 64 sets × 8 ways; L2 512 KB, 1024 sets × 8 ways |
| provenance | L2 HEAD `b46a8ac`, RTL marked UNCOMMITTED; every `.scala` sha in the provenance equals the working tree. **2026-10-15: committed as `494ab5e`; all 26 `.scala` blobs of that commit hash to the provenance values (0 mismatches)** |

## Commit, merge, tag (2026-10-15, user: "commit them appropriately … separate commit for RTL changes … merge … use a tag")

| step | result |
|---|---|
| L2 commit 1 (RTL, 7 files) | `494ab5e` — the commit that builds the stored image |
| L2 commit 2 (sw: `sbc_read`, `sbc_mmio.h`, stress test, gate script) | `766a0fb` |
| L2 commit 3 (`l2_miss_calib.c`) | `6b4ee42` |
| L2 commit 4 (docs: task 018 folder + the three tracker docs) | the following commit (tag points at it) |
| merge | `sbc-018-dst-dirty-ways` → `sbc-013-sat-counter` (its parent, still at `b46a8ac`): fast-forward, linear history, no conflicts; branch kept |
| tag | `sbc-018-dstmode-512k-n4096-b128-best-2026-10-15` (annotated), same name in the chipyard repo on the commit that carries the configs and the submodule pointer |
| bitstream rename | `…-dst018-2026-10-13.*` → `…-dst018-494ab5e-best-n4096-b128-2026-10-13.*` (`.bit`, `.dts`, `.config.diff`, `.provenance.txt`, `.reports`); sha256 `502649e2…` unchanged; `.NOTE.txt` added |
| chipyard repo (`eval_ssbc`) | configs (`RocketConfigs.scala`, `vcu118/Configs.scala`), submodule pointer, campaign `tools/` + `PLAN.md` / `RESULTS.md`; raw window logs stay untracked as for earlier campaigns |

Marked as **the bitstream behind the best result for 512 KB L2, LU n=4096 b=128: −20.9% memory accesses (replicate −21.1%) with
`SBC_DstMode` 1 against SBC off on this image**. The setting-0 arm was not run at n=4096, so "best" is against SBC off.

## Campaign tools (§10)
Folder `chipyard/scripts/logs/clean-runs/lu-dstmode-single-core-512k-20261013/` (created 2026-10-13 00:20; no board use yet).

| file | what | check |
|---|---|---|
| `tools/run_parsec_session.exp` | copy of the harness `b13d93e768b04559…`; `LIB` absolute; `-dstmode N` adds ` --dstmode=N` **before** `--migrate` (keeps the checker's command regex matching, and the echo prefix ends at column 74 < 80, so it does not wrap); host `sbc_read` = `sw/build/018/sbc_read`; SD cache `/mnt/sbc_read_018`; CSV + `dstRescue dstMode` | `tools/harness_changes.diff`; `-list` on both labels = one point each; `-dstmode 3` rejected |
| `sw/build/018/sbc_read` | a copy of `sbc_read_018` (`b103216d…`) under the board name. zmodem keeps the host basename, so a host file named `sbc_read_018` would land on the board under that name and `/root/sbc_read` would be missing | sha equal to `sbc_read_018`; the original `sw/build/sbc_read` stays `8241eb8b…` |
| `tools/check_dst_stage.py` | copy of `check_lu_stage.py` v2 + `--dstmode N`; window `dstMode == N`; `dstRescue` present and 0 in off; `dstmode` in the json | `tools/checker_changes.diff`; replayed on a 017 window: every old check passes, only the two new ones fail (old `sbc_read` prints no dstMode/dstRescue) |
| `tools/launch_dst_v1.sh` | copy of 017 `launch_lu_sweep_v6.sh`; arms `off m0 m1 m2` (`DST_ARMS`), per point in that order; image via `DST_BIT`/`DST_BIT_SHA` (placeholder until the build); adoption OFF (a running session stops the preflight); `LU_DRYRUN=1` | `bash -n` ok; dry run = 8 stages, right `-phases`/`-dstmode` per arm, writes nothing |
| `tools/dst_table.py` | §12 tables 1–3 + checks | run on 017's off/on as off/m0: reproduces −4.68% / −0.14%, 5,732 / 20,805 migrations |

Note: §2's "held aborts 31,875 (83.1%)" at n512 b128 is `dstAbortHeld` alone. §12 defines held aborts as
`dstAbortHeld + dstAbortBoth` = 32,620 (85.1%). The tables follow §12.

Near-miss (2026-10-13 00:20, my error): to syntax-check the harness copy I ran `expect -c "source <copy>"` with no
arguments, which could have started a default session. It exited at the argument loop (`argv` is undefined under
`expect -c`): no transcript, no CSV, no process, nothing on `/dev/ttyUSB2`, no Vivado journal. Probes since then use
`-list` only.

## Predictions (§10.5)
Frozen before the first window of each round in the campaigns' `PLAN.md` (first campaign: per point × arm and 7 sweep-level rows;
second: 9 per-point rows and 7 sweep-level rows, then dated addenda for r2, n4096 b128, its replicate and n3072 b128). Graded in the
Chain and Results sections: second campaign 8 of 9 per-point bands and 7 of 7 sweep-level right (n2048 b128 wrong, better); r2 3 of 3;
n4096 b128 4 of 5 (the change was better than predicted); n4096 replicate 5 of 5; n3072 b128 6 of 6.

## Chain (§11)
Started 2026-10-13 02:56:17, tmux `dst018`, status `scripts/logs/clean-runs/lu-dstmode-single-core-512k-20261013/chain_status.txt`
(rows are added as windows finish; the full table is generated at the end).

| # | arm | label | started | verdict | attempt |
|---|---|---|---|---|---|
| 1 | off | lu-p1-n512-b128-plru | 02:56:17 | VALID (all 35 checks ok; memAcc 2,489,210 = reads 1,338,271 + writes 1,150,939; hit 83.15%) | 1 |

| 2 | m0 | lu-p1-n512-b128-plru | 03:22:08 | VALID | 1 |
| 3 | m1 | lu-p1-n512-b128-plru | 03:48:04 | VALID | 1 |
| 4 | m2 | lu-p1-n512-b128-plru | 04:13:59 | VALID | 1 |

**Interim, point 1 (n512 b128), one window per arm** (the generated table comes at the end; this block is for the
reader of the report in the meantime):

| arm | mem total | vs off | reads | writes | migrations | attempted | held aborts (share) | dstRescue | dst write-backs | Δprimary / mig | secondary / mig | Δreads / mig |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| off | 2,489,210 | — | 1,338,271 | 1,150,939 | 0 | 0 | 0 | 0 | — | — | — | — |
| m0 | 2,440,125 | −1.97% | −1.73% | −2.26% | 6,742 | 45,011 | 38,269 (85.0%) | 15,759 | 1,106 | +2.94 | 1.73 | −3.43 |
| m1 | 2,365,544 | **−4.97%** | −4.87% | −5.08% | 9,695 | 24,162 | 14,467 (59.9%) | 144 | 2,018 | +4.68 | 2.37 | −6.72 |
| m2 | 2,403,149 | −3.46% | −3.32% | −3.62% | 9,058 | 24,519 | 15,461 (63.1%) | 126 | 3,426 | +3.23 | 2.49 | −4.90 |

- All four: accessA within 0.5% of off, four-outcome gap and port identity ok. The off arm vs 017's off: +1.99% (flag);
  m0 vs 017's on: +4.88% (flag). m0's held share (85.0%) equals 017's (85.1%). m0 saves −1.97% here against 017's −4.68%.
- Grading against PLAN.md (frozen), point 1: m0 held share right (85.0% in 80–90%); m0 change **wrong** (−1.97% vs −3.5
  to −6%); m1 held share **wrong** (59.9% vs 15–50%); m1 change **wrong by 0.03 pp** (−4.97% vs −5 to −11%); m2 held share
  **wrong** (63.1%); m2 change **wrong** (−3.46% vs −4 to −11%). Sweep-level 1 **wrong** at this point (m1 keeps 70% and
  m2 74% of m0's held share; by count the held aborts fell 62% and 60%, because attempts fell by half); 2 right
  (m1 3.0 pp better than m0); 4 **wrong** for m1 (1.82× m0's destination write-backs, m2 3.10×); 5 right at this point
  (m2 writes 1,109,302 > m1's 1,092,460).

| 5–8 | off, m0, m1, m2 | lu-p1-n1024-b64-plru | 04:39:56 → 06:32 | all four VALID | 1 |

**Interim, point 2 (n1024 b64), one window per arm** — the TASK's 8 windows are now complete (4 + 4):

| arm | mem total | vs off | reads | writes | migrations | attempted | held aborts (share) | dstRescue | dst write-backs | Δprimary / mig | secondary / mig | Δreads / mig |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| off | 4,288,847 | — | 3,069,197 | 1,219,650 | 0 | 0 | 0 | 0 | — | — | — | — |
| m0 | 4,272,937 | −0.37% | −0.42% | −0.25% | 16,467 | 118,601 | 102,134 (86.1%) | 93,284 | 1,548 | +1.34 | 0.91 | −0.78 |
| m1 | 4,232,268 | −1.32% | −1.36% | −1.23% | 39,256 | 61,267 | 22,011 (35.9%) | 801 | 2,667 | +1.34 | 0.61 | −1.06 |
| m2 | 4,230,796 | −1.35% | −1.45% | −1.11% | 36,953 | 44,841 | 7,888 (17.6%) | 730 | 7,189 | +1.62 | 0.65 | −1.20 |

- Cross-check: off +0.25% and m0 +0.01% against 017's off/on at this point: inside the 1.5 pp flag. m0's 4,272,937 vs 017's
  on 4,272,304.
- Hit balance (exact, all six arms of both points): Δreads = ΔaccessA − Δprimary − secondary, ΔaccessA ≤ 0.11% of
  off's. Here both terms moved; primary hits are the larger one (m1 +52,564 vs +23,988; m2 +59,995 vs +23,880).
- Grading, point 2: all six per-arm bands **right** (m0 86.1% / −0.37%; m1 35.9% / −1.32%; m2 17.6% / −1.35%).
  Sweep-level: 1 right at this point (m1 0.42×, m2 0.20× of m0's share), so **wrong overall** (point 1); 3 right
  (largest |change| 1.35% < 1.5%); 4 **wrong** for m1 at both points (1.82× and 1.72×); 5 right (m2 writes 1,206,072 >
  m1's 1,204,669; 0 of 2 points below).

Cross-check of window 1 vs task 017's off window at the same point (flag only, never a retry trigger): accessA +0.14%
(same work), memory reads +1.68%, writes +2.35%, **total +1.99% → above the 1.5 pp flag**; L2 cycles +0.11%. Different
image and boot; one window each. Stays in the table as flagged.

| 9–12 | off, m0, m1, m2 | lu-p1-n384-b128-plru | 06:32 → 08:15 | all four VALID | 1 |

**Interim, point 3 (n384 b128), one window per arm** — the point where SBC has almost nothing to do:

| arm | mem total | vs off | reads | writes | migrations | attempted | held aborts (share) | dstRescue | dst write-backs | Δprimary / mig | secondary / mig | Δreads / mig |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| off | 190,629 | — | 125,993 | 64,636 | 0 | 0 | 0 | 0 | — | — | — | — |
| m0 | 192,352 | +0.90% | +1.24% | +0.26% | 1,551 | 11,436 | 9,885 (86.4%) | 7,380 | 84 | −0.81 | 0.02 | +1.00 |
| m1 | 191,519 | +0.47% | +0.70% | +0.01% | 2,895 | 9,254 | 6,359 (68.7%) | 71 | 188 | +1.97 | 0.02 | +0.31 |
| m2 | 192,421 | +0.94% | +1.27% | +0.29% | 2,845 | 8,066 | 5,221 (64.7%) | 76 | 673 | +1.56 | 0.03 | +0.56 |

- All four: accessA within 0.5% of off (m1 +0.20%, m2 +0.19%: the reads rise partly comes from that), checks ok. Off +0.50% and
  m0 +0.88% against 017's off / on: inside the flag. No arm saves anything: all three are +0.5 to +0.9%, inside the none
  band. Held shares fall (86% → 69% / 65%) but there are few useful migrations to recover

### Stop (user request, 2026-10-13 08:39)
- User: "terminate current sweep. restart a new one with setting=1 with b=64 b=128 b=256 and varying n …"
- Stopped with SIGTERM to the driver's process group at 08:39:24, during the **staging** of stage 13 (off lu-p1-n768-b128-plru,
  started 08:15:16, 318,464 of 524,216 bytes of `lu_ncb` sent). No measured window had started. The orphaned `picocom` and
  `sz` ended on their own; `/dev/ttyUSB2` was free. Files: `aborted/off-lu-p1-n768-b128-plru-20261013-081516/` (NOTE.txt)
- The queued corrected calib sweep (tmux `dst018c2`, waiting for "ALL DONE") was stopped too; it had not started
- Valid at the stop: 12 of 32 (points n512 b128, n1024 b64, n384 b128; off, m0, m1, m2). Not run: points n768 b128,
  n1024 b128, n256 b128, n256 b256, n512 b256

### Second campaign (setting 1 only)
- Folder `scripts/logs/clean-runs/lu-sweep-dstmode1-single-core-512k-20261013/`; plan, provenance and frozen predictions
  in its `PLAN.md`; launcher `tools/launch_dst_sweep_v1.sh` (= `launch_dst_v1.sh` with arms off/on, `DST_ON_MODE` 1, 9
  labels, `-tmo 3600` for n=2048); same image, harness copy and checker as the first campaign
- Dry run (`LU_DRYRUN=1`): 18 stages, `-phases on -dstmode 1` for the on arm; `-list` through the harness shows each label
  as a one-point plan, incl. n2048 b256
- Its windows at n512 b128 and n1024 b64 are a second window of the same arm: shown beside the first campaign's, never merged

## Results (§12)
**Interim — first campaign only (12 valid windows, 3 points × off / m0 / m1 / m2; one window per arm).** The second
campaign's table is its `RESULTS.md`. Full per-window tables: first campaign `RESULTS.md`; reads and writes separate.

Memory accesses (reads + writes) vs off:

| point | m0 (today) | m1 (clean, then dirty) | m2 (least-recent of either) |
|---|--:|--:|--:|
| n512 b128 | −1.97% (reads −1.73, writes −2.26) | **−4.97%** (−4.87, −5.08) | −3.46% (−3.32, −3.62) |
| n1024 b64 | −0.37% (−0.42, −0.25) | −1.32% (−1.36, −1.23) | −1.35% (−1.45, −1.11) |
| n384 b128 | +0.90% (+1.24, +0.26) | +0.47% (+0.70, +0.01) | +0.94% (+1.27, +0.29) |

Held aborts, migrations, destination write-backs:

| point | held aborts m0 → m1 / m2 | migrations m0 → m1 / m2 | dst write-backs m0 / m1 / m2 |
|---|---|---|---|
| n512 b128 | 38,269 → 14,467 / 15,461 (−62% / −60%) | 6,742 → 9,695 / 9,058 | 1,106 / 2,018 / 3,426 |
| n1024 b64 | 102,134 → 22,011 / 7,888 (−78% / −92%) | 16,467 → 39,256 / 36,953 | 1,548 / 2,667 / 7,189 |
| n384 b128 | 9,885 → 6,359 / 5,221 (−36% / −47%) | 1,551 → 2,895 / 2,845 | 84 / 188 / 673 |

- Hit balance (identity, exact in all 12 windows): Δreads = ΔaccessA − Δprimary hits − secondary hits. At n512 b128 setting 1
  moved both terms (Δprimary +45,369, secondary 22,958); at n1024 b64 primary hits are the larger term (+52,564 vs 23,988)
- Total memory writes did not rise vs off at the two winning points (m1: −5.08%, −1.23%); destination write-backs are a few
  thousand against about 1.1–1.2 million writes
- Cross-check, not a result: off and m0 differ from task 017's off / on by +1.99% / +4.88% at n512 b128 (flag > 1.5 pp,
  never retried), +0.25% / +0.01% at n1024 b64, +0.50% / +0.88% at n384 b128
- F4 (setting 2 had 6× setting 0's held aborts in the simulator's stock stress test) is **not seen on the board**: m2's held
  aborts are below m0's at all three points (−60%, −92%, −47%); against m1 they are +7% at n512 b128, −64% at n1024 b64,
  −18% at n384 b128

### Second campaign — setting 1 vs off, 9 points, 18 of 18 windows valid (2026-10-13 08:45 → 18:44)
Folder `%s/`; tables in its `RESULTS.md`. One window per arm (the two points that repeat the first campaign agree within 0.6%).

Memory accesses (reads + writes), SBC on (setting 1) vs off:

%s

| point | OFF hit | memory accesses off → on | reads | writes | migrations | held share | L2 cycles |
|---|--:|--:|--:|--:|--:|--:|--:|
| n512 b64 | 95.2% | 598,577 → 598,716 | −0.01% | +0.11% | 6,905 | 47.9% | −0.02% |
| n512 b128 | 83.1% | 2,494,872 → 2,351,704 | −5.61% | −5.89% | 8,956 | 57.6% | −0.27% |
| n512 b256 | 29.0% | 10,839,078 → 10,807,086 | −0.46% | −0.13% | 89,885 | 12.3% | +0.16% |
| n1024 b64 | 95.5% | 4,277,378 → 4,240,311 | −0.77% | −1.11% | 41,108 | 36.4% | +0.00% |
| n1024 b128 | 79.9% | 23,180,210 → 22,110,883 | −4.85% | −4.33% | 372,934 | 37.0% | −0.21% |
| n1024 b256 | 25.4% | 89,840,176 → 89,500,211 | −0.62% | −0.12% | 945,360 | 16.9% | +0.27% |
| n2048 b64 | 95.7% | 32,186,765 → 31,737,579 | −1.39% | −1.42% | 232,537 | 33.5% | −0.01% |
| n2048 b128 | 77.6% | 205,860,543 → 183,732,832 | −10.98% | −10.47% | 3,837,351 | 38.1% | −0.55% |
| n2048 b256 | 23.7% | 732,820,625 → 729,085,064 | −0.87% | −0.13% | 8,094,351 | 24.4% | +0.40% |

- Hit balance (identity, exact): at n2048 b128 primary hits +8,012,435 and secondary hits 4,165,303 give reads −12,275,367
  (−3.20 per migration; both terms moved, primary is two thirds). At the b256 points primary hits **fall** (−38,354 / −385,651 /
  −2,811,454) while secondary hits rise (59,456 / 642,309 / 6,706,833): migrated lines displace about as many primary hits as they win
  back, so the net is under 1%
- Repeats of the first campaign (same arm): n512 b128 off +0.23%, on −0.59%; n1024 b64 off −0.27%, on +0.19%
- Window length at n=2048: 1,155 s (b64), 1,163 s (b128), about 1,305 s (b256); n1024 146–165 s. LU work ∝ n³ holds (8×)
- Run time on this board: L2 cycles move ≤ 0.55%; the labelled model (`results/dram_latency_projection.txt`) projects −3.0% for n2048 b128
  at 200 cycles per DRAM read (DRAM is 29% of OFF time at that cost there)
- Held share at setting 1 is 12–58% at every point (setting 0 was 80–90% at the LU-heavy points)

**Replicate round r2 (n2048 b128, fresh boots, 21:49 → 23:19, both windows valid):** off 204,995,559 (−0.42% vs round 1), on
185,161,819 (+0.78% vs round 1) → change **−9.68%** (reads −10.01%, writes −9.28%; round 1 −10.75%, gap 1.07 pp). Migrations
3,820,648 (round 1 3,837,351), held share 38.4% (38.1%), L2 cycles −0.46% (−0.55%); Δprimary +7,518,736, secondary 4,108,913.
Graded against PLAN.md addendum 21:48: change in −13% .. −8% **right**; each arm within 1.5% of round 1 **right**; L2 cycles
within ±1.5% **right**. The independent estimate for n2048 b128 is −9.7% (r2 alone; round 1 −10.7%).

**n4096 b128 (new point, 2026-10-13 23:19 → 2026-10-14 05:20, both windows valid, checks ok, no warnings):**

| measure | off | on | change |
|---|--:|--:|--:|
| memory accesses | 1,994,817,116 | 1,578,375,020 | **−20.88%** (−416.4 M) |
| reads | 1,085,507,963 | 857,098,406 | −21.04% |
| writes | 909,309,153 | 721,276,614 | −20.68% |
| L2 hit rate | 72.94% | 78.62% | +5.68 pp |
| window (L2 cycles / 50 MHz) | 9,369 s | 9,245 s | −1.33% |
| accessA | 4,008,887,723 | — | −0.055% |

- Migrations 30,816,604; attempted 62,067,482; held aborts 31,250,878 (**50.3%**); dstRescue 126,685; destination write-backs 1,780,039
  (against 721 M writes); secondary hits 58,554,081
- Hit balance (exact): primary hits **+167,663,363**, secondary hits 58,554,081, reads **−228,409,557** (−7.41 per migration). Both terms
  moved; primary hits are the larger (74%). Writes fell by 188 M, not rose
- Model (labelled, `results/dram_latency_projection.txt`): run time −1.33% on this board; −3.8% / **−6.7%** / −8.8% at 100 / 200 / 300
  cycles per DRAM read (DRAM is 34% of OFF time at 200)
- Trend at b128: n512 −5.7%, n1024 −4.6%, n2048 −10.7% / −9.7% (r2), **n4096 −20.9%**. The saving roughly doubles from n2048 to n4096;
  no model behind it (my row-stride arithmetic, labelled a guess in the plan addendum, is not tested by this alone)
- Graded (PLAN.md addendum 21:48): OFF hit 65–80% **right** (72.9%); change −16% .. −4% **wrong, better** (−20.9%); held share ≤ 70%
  **right** (50.3%); window length 8,000–11,000 s **right** (9,369 / 9,245 s; LU work ∝ n³ held); L2 cycles within ±1.5% **right**
  (−1.33%, close to the edge). So 4 of 5 bands right; the saving is again larger than predicted

**Replicate of n4096 b128 (`r2/`, 2026-10-14 11:05 → 17:07, both windows valid):** off 1,995,740,441 (+0.05% vs round 1), on
1,574,524,503 (−0.24%) → change **−21.11%** (reads −21.27%, writes −20.91%; round 1 −20.88%, gap 0.23 pp). Hit 72.92 → 78.64%,
migrations 30,971,455, held share 50.2%, dstRescue 127,544, dst write-backs 1,814,870, L2 cycles −1.39%, windows 9,370 / 9,240 s.
Δprimary +167,939,496, secondary 55,779,601, reads −230,936,991 (−7.46 per migration). Graded (PLAN.md addendum 11:05): change
−24% .. −18% **right**; each arm within 2% of round 1 **right**; held share 40–60% **right** (50.2%); window 8,500–10,500 s **right**;
L2 cycles −2.5% .. −0.5% **right** (−1.39%). **5 of 5 right.** n4096 b128 estimate: −21.1% (replicate), −20.9% (round 1).

**Per migration at b128 (round 1 windows):** reads saved per migration −1.63 (n1024), −3.20 (n2048), −7.41 (n4096); OFF hit
79.9 / 77.6 / 72.9% → ON 80.9 / 80.0 / 78.6%; held share 37 / 38 / 50%. The value of one migration doubles with each doubling of n.

**n3072 b128 (new point, 2026-10-14 22:57 → 2026-10-15 01:59, both windows valid, checks ok):** off 770,444,137 (reads
418,825,153; writes 351,618,984), on 649,145,249 → change **−15.74%** (reads −15.94%, writes −15.51%); hit 75.16 → 79.12%; windows
3,935 / 3,899 s; L2 cycles −0.90%; accessA −0.027%. Migrations 13,185,158; held share 45.6%; dstRescue 46,719; destination
write-backs 739,589; Δprimary +46,441,288, secondary 19,869,711; reads −5.06 per migration.
Graded (PLAN.md addendum 22:57): change −18% .. −9% **right**; OFF hit 72–78% **right** (75.2%); held share 35–55% **right** (45.6%);
reads per migration −3 .. −7 **right** (−5.06); window 3,400–4,500 s **right**; L2 cycles −1.8% .. −0.3% **right** (−0.90%).
**6 of 6 right.** Reading rule: −15.7% is ≤ −9%, so the **size reading is supported and the row-stride-aliasing guess is rejected**
(aliasing predicted ≥ −7%: n3072's rows sit 24 KB apart, 8 offsets mod 64 KB like n1024, and it saves more than n2048).

**The b128 series, one window per arm (n2048 and n4096 have a replicate):**

| n | OFF hit | ON hit | change | reads | writes | L2 cycles | migrations | held share | reads per migration |
|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| 512 | 83.12% | 84.04% | −5.74% | −5.61% | −5.89% | −0.27% | 8,956 | 57.6% | −8.40 |
| 1024 | 79.91% | 80.89% | −4.61% | −4.85% | −4.33% | −0.21% | 372,934 | 37.0% | −1.63 |
| 2048 | 77.57% | 80.03% | −10.75% (r2 −9.68%) | −10.98% | −10.47% | −0.55% | 3,837,351 | 38.1% | −3.20 |
| 3072 | 75.16% | 79.12% | **−15.74%** | −15.94% | −15.51% | −0.90% | 13,185,158 | 45.6% | −5.06 |
| 4096 | 72.94% | 78.62% | −20.88% (r2 −21.11%) | −21.04% | −20.68% | −1.33% | 30,816,604 | 50.3% | −7.41 |

- From n1024 up, the saving is close to linear in n (about −5 pp per 1,024 rows) and so is the reads saved per migration (about −1.9
  per 1,024). Four points, descriptive only, not a law
- OFF hit falls 7.0 pp between n1024 and n4096; with setting 1 it falls 2.3 pp (80.9 → 78.6%)

**Grading, second campaign's PLAN.md (frozen 08:44):** per point 8 of 9 bands right; **wrong: n2048 b128** (−10.7% vs −6% .. −1%,
better than predicted: the "diluted by a 32 MB matrix" reason was wrong). Sweep-level: 1 right (best −10.7%), 2 right (largest rise
+0.02%), 3 right (b64 points +0.0 / −0.9 / −1.4%), 4 right (four repeats within 0.59%), 5 right (held share ≤ 58%, n512 b64 47.9%),
6 right (largest |cycles| 0.55%), 7 right (n2048 windows 1,155 – 1,310 s). Replicate round r2 and n4096 b128 are graded below.

## Verdict
**Second campaign, 2026-10-13/14 (setting 1 vs off, 9 points + n2048 b128 replicate + n4096 b128 and its replicate; 24 windows, all valid):**
- Setting 1 pays at b=128 and nowhere else: −5.7% (n512), −4.6% (n1024), **−10.7% (n2048; replicate −9.7%)**, **−15.7% (n3072)**, **−20.9% (n4096)**;
  b=64 is −0.0 to −1.4%, b=256 −0.3 to −0.5%. At b128 the saving grows with n and doubles from n2048 to n4096; **the n4096 replicate
  gave −21.1% (round 1 −20.9%)**, so the effect is reproducible to about 0.25 pp there (n2048: 1.1 pp)
- The saving at n2048 b128 is the largest and came with fewer reads **and** fewer writes (−11.0% / −10.5%); the run-time change on
  this board is only −0.55% (model: −3.0% at 200 cycles per DRAM read)
- It costs nothing measurable anywhere: the largest rise in memory accesses is +0.02%, L2 cycles move ≤ 0.55%
- At b=256 (hit 24–29%) migrations are plentiful but displace as many primary hits as they win back, so the net is under 1%
- n3072 b128 (stride 24 KB, not a power of two, 8 offsets mod 64 KB) saves **−15.7%**, between n2048 and n4096: the saving follows the
  problem size, not power-of-two stride aliasing (the guess in PLAN.md addendum 22:57 is rejected)
- Not established: one window per arm except n2048 b128 and n4096 b128 (two windows each, 1.1 and 0.23 pp apart); the n-trend at b128
  (−5.7, −4.6, −10.7, −15.7, −20.9) has no model behind it; b64 and b256 were not run at n4096; the LU output was not checked (rc only); the corrected calib sweep was not run; run-time gain on this board is only
  −0.55% (n2048) and −1.33% (n4096), the −6.7% at n4096 is a model, not a measurement

**Interim (2026-10-13, after the first campaign's 12 windows; the second campaign adds 9 points for setting 1).**
- Settings 1 and 2 do turn held aborts into migrations: held aborts fall 36–92%, migrations rise 1.3–2.4×, at all three points
- The saving is real at n512 b128 (setting 1 −4.97%, setting 0 −1.97%) and small at n1024 b64 (−1.3% for both settings 1 and 2);
  n384 b128 has nothing to win (+0.5 to +0.9%, none band)
- Setting 1 matches or beats setting 2 at every point (−0.03 pp worse at n1024 b64) and needs 1.7–3.6× fewer destination
  write-backs. **User decision: setting 1 is the one used for tests**
- Cost: total memory writes went down, not up, at the winning points; run time (L2 cycles) moved ≤ 0.2%
- Not established: one window per arm. The between-boot spread at n512 b128 is about 2.7 pp, as large as setting 1's 3.0 pp
  advantage over setting 0 there; the first campaign's off and m0 windows differ from task 017's by +1.99% and +4.88%.
  A replicate of off / m0 / m1 at n512 b128 on fresh boots would settle it (the user's decision). The corrected calib sweep
  (predictions 6 and 7) was not run. 5 of 8 points were not measured by this campaign

## All log and CSV paths
| # | arm | label | verdict | transcript | CSV |
|---|---|---|---|---|---|
| 1 | off | lu-p1-n512-b128-plru | VALID | `off/lu-p1-n512-b128-plru/lu_session_20261013-025617.log` | `off/lu-p1-n512-b128-plru/lu_runs_20261013-025617.csv` |
| 2 | m0 | lu-p1-n512-b128-plru | VALID | `m0/lu-p1-n512-b128-plru/lu_session_20261013-032208.log` | `m0/lu-p1-n512-b128-plru/lu_runs_20261013-032208.csv` |
| 3 | m1 | lu-p1-n512-b128-plru | VALID | `m1/lu-p1-n512-b128-plru/lu_session_20261013-034804.log` | `m1/lu-p1-n512-b128-plru/lu_runs_20261013-034804.csv` |
| 4 | m2 | lu-p1-n512-b128-plru | VALID | `m2/lu-p1-n512-b128-plru/lu_session_20261013-041359.log` | `m2/lu-p1-n512-b128-plru/lu_runs_20261013-041359.csv` |
| 5 | off | lu-p1-n1024-b64-plru | VALID | `off/lu-p1-n1024-b64-plru/lu_session_20261013-043956.log` | `off/lu-p1-n1024-b64-plru/lu_runs_20261013-043956.csv` |
| 6 | m0 | lu-p1-n1024-b64-plru | VALID | `m0/lu-p1-n1024-b64-plru/lu_session_20261013-050801.log` | `m0/lu-p1-n1024-b64-plru/lu_runs_20261013-050801.csv` |
| 7 | m1 | lu-p1-n1024-b64-plru | VALID | `m1/lu-p1-n1024-b64-plru/lu_session_20261013-053605.log` | `m1/lu-p1-n1024-b64-plru/lu_runs_20261013-053605.csv` |
| 8 | m2 | lu-p1-n1024-b64-plru | VALID | `m2/lu-p1-n1024-b64-plru/lu_session_20261013-060409.log` | `m2/lu-p1-n1024-b64-plru/lu_runs_20261013-060409.csv` |
| 9 | off | lu-p1-n384-b128-plru | VALID | `off/lu-p1-n384-b128-plru/lu_session_20261013-063213.log` | `off/lu-p1-n384-b128-plru/lu_runs_20261013-063213.csv` |
| 10 | m0 | lu-p1-n384-b128-plru | VALID | `m0/lu-p1-n384-b128-plru/lu_session_20261013-065758.log` | `m0/lu-p1-n384-b128-plru/lu_runs_20261013-065758.csv` |
| 11 | m1 | lu-p1-n384-b128-plru | VALID | `m1/lu-p1-n384-b128-plru/lu_session_20261013-072344.log` | `m1/lu-p1-n384-b128-plru/lu_runs_20261013-072344.csv` |
| 12 | m2 | lu-p1-n384-b128-plru | VALID | `m2/lu-p1-n384-b128-plru/lu_session_20261013-074931.log` | `m2/lu-p1-n384-b128-plru/lu_runs_20261013-074931.csv` |

Paths are under `scripts/logs/clean-runs/lu-dstmode-single-core-512k-20261013/`. Also there: `aborted/off-lu-p1-n768-b128-plru-20261013-081516/` (stopped by hand, staging), `calib/` (run 1, not usable: F5), `chain_status.txt`, `driver_status.txt`, `PLAN.md`, `RESULTS.md`.
Second campaign: `scripts/logs/clean-runs/lu-sweep-dstmode1-single-core-512k-20261013/` (rows are in its `chain_status.txt`).
