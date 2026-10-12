# Coder task 016 — bodytrack, SBC off vs on, and what each parameter does to the effect

**Opened 2026-10-09.** Board work on the VCU118, same single-core image as the LU sweep. Builds on
[task 015](../015-pair-verification-campaign/TASK.md) (parked at stop (c); 015 is not touched by this task).
Method = the LU single-core sweep of 2026-10-08
([RESULTS](../../../../../scripts/logs/clean-runs/lu-sweep-single-core-20261008/RESULTS.md),
[015 REPORT, section "LU single-core sweep"](../015-pair-verification-campaign/REPORT.md)).

**This file is written for a coding model.** Commands are spelled out. Find code by the quoted text, never by
line number. If the code does not look like what this file quotes, **stop and ask.**

**No bitstream builds.** The image exists. **Nothing is committed** (user: "do not add any commits till we finalize anything").

---

## 0. Rules — read before anything else

1. **Plan first.** Read this whole file, then reply with a plan of at most 10 lines plus the questions in §11. Touch
   nothing until the user says go. Simple English, point form.
2. **Stop points:**
   - (a) after your plan;
   - (b) after §6 is built and host-tested, **before the board is used at all**;
   - (c) after the §7 calibration boot, **before** you write the predictions and before stage 1;
   - (d) after Phase 1 is complete (all OFF, all ON, replicates), before Phase 2;
   - (e) after Phase 2: final report.
   Any failed check is also a stop (rule 10).
3. **Matched board history.** Every measured window is the **first and only measured window after a full FPGA
   reprogram**, in both arms, with the same steps before it. OFF traffic rises with board age with migration never
   on (015 F8, F11), so an ON window compared with an OFF window of a different age measures the age. One window per boot. Never reuse an aged-board window as a result.
4. **Phase order:** every `migrate=off` stage before any `migrate=on` stage (inside Phase 1, inside Phase 2, inside the
   replicate block). Per-point off→on was approved for the LU+FFT grid only — ask before assuming it here (§11 Q2).
5. **Never** pass `-allow-dirty-on`. **Never** use `sbc_read --reset-all` (not functional). The policy resets to random
   on every reprogram: every window sets `--policy=plru` and the harness reads it back.
6. **Same image, cite the sha, not the filename:** `5ace41250e7c5141…` (§3).
7. **Report `memReads` and `memWrites` separately** in every table, as well as the sum. Hit rate =
   `(primaryHit + secondaryHit) / accessA`; `probedHit` is a subset, never added. Words per
   [cache-terminology.md](../../guides/cache-terminology.md).
8. **`rc=0` proves nothing for bodytrack.** `main()` returns 0 on every path, including failed ones (§2.2). Validity is
   read from the program's own output (§6 C2).
9. **Same-work checks:** `accessA` OFF vs ON within 1% (flag it, never a retry trigger) and **identical `poses.txt` md5**
   between the two arms of a point. A different md5 between arms is a finding about data integrity: stop (rule 10).
10. **A failing check is a finding.** Write it in `REPORT.md`, stop, ask. Never edit the harness or the checker to make
    a check pass. Never "fix" something else you notice — report it.
11. **Do not edit** `scripts/ssbc_scripts/run_parsec_session.exp`, `lib/*.exp`, or any file of an earlier campaign.
    Work in copies inside this campaign's `tools/`. The one allowed edit outside it is the additive
    `results_common.py` step in §8.3, with a backup.
12. **Git:** commit nothing. Never `git checkout <file>`, `git restore`, `git reset --hard`, `git clean`, `git stash drop`.
13. **Give the user every log path the moment a run starts** (repo convention): tmux session, `chain_status.txt`,
    the session log and CSV.
14. **Session logs hold binary bytes:** use `grep -a`, `tail`, or parse the CSV **by column name**. Never `cat` a log.
15. **Never kill a launcher while its window runs.** The tmux session closes and takes the harness with it (a boot was lost this way, 2026-10-09).
16. **Dry-run every new command shape on the board before the chain.** `-list` and fixtures do not exercise the board
    side. The §7 calibration boot is that dry run.
17. **Timing claims come from logs or code.** A stage is about 28 min, of which 21 min is the boot ROM loading the
    payload from the SD card (LU sweep logs, `PLAN.md` third addendum). bodytrack stages more bytes (about 2.6 MB against about 1.3 MB for LU, over zmodem at 115200 baud): **+2–3 min is an estimate** until the calibration timeline shows it.
18. **Reporting.** `RESULTS.md` = results only, regenerated in place. Every question the user asks is answered in chat, not in the
    file. Use standard terms (baseline, interference, memory accesses, percentage points). Bullets and compact tables, short lines.
19. **Board hygiene:** busybox 1.36.0 has `taskset`, **no `timeout`**. If a run wedges, `WORK_TMO` (1800 s) is the only
    timeout that exists — which is why §2.1 forbids the values that hang.

---

## 1. Goal

**Question:** does SBC change bodytrack's memory traffic, by how much, and which parameter moves that?

- Step 1: the **centre point** — `bodytrack` as 015 S5 specified it — SBC off vs on, fresh boot per window. **The two
  arms are identical in every way except `--migrate=off|on`.**
- Step 2: change **one parameter at a time** and repeat. Result: a table of "parameter → effect on SBC".
- Output: a report in the shape of the LU sweep (§8).

What is already known (do not re-derive; do not bend the campaign toward it):

- SBC saved memory accesses on LU only where the program's own working set overflowed unevenly: b=64 (hit 75%) **−12.9%**,
  b=128 (48–60%) **−3 to −5%**, b=256 (51%) −1.3%, b=16/b=32 (hit 93–94%) **about 0**.
- bodytrack, aged board, 2026-10-01 (`parsec_session_20261001-112941.log`): 14.96 s, `accessA` 5,811,698, `memReads` 920,680,
  `memWrites` 415,061, hit 84.16%. Its SBC A/B (cycles +0.1%, `memReads` +2.35%) was **one boot, OFF before ON** — contaminated by board age, **do not cite it.**
- bodytrack has never been measured fresh-vs-fresh. **A null result is a result.**

---

## 2. What was verified on the host (2026-10-09, qemu-user on the real `staged/bodytrack`)

Binary: `chipyard/software/parsec-benchmark/staged/bodytrack` (static RISC-V, built `--enable-threads --disable-openmp --disable-tbb`).
Input: `staged/sequenceB_1.tar.gz` (8 BMP images of 308,278 B + calibration files). Host runs used a **scratch copy** — `staged/` was not written to.

### 2.1 The command and its knobs

```
./bodytrack sequenceB_1 <cameras> <frames> <particles> <layers> <model> <threads> <outputBMP>
```

The harness today runs `./bodytrack sequenceB_1 4 1 100 3 0 $NTHREADS` (7 args; the 8th defaults to "write BMP").
Passing `1` as the 8th argument is the same program behaviour (checked: same md5), so this task always passes all 8.

| knob | range that works | what it does | trap |
|---|---|---|---|
| cameras | 1–4 | images processed per frame: footprint and work, linear (0.14 / 0.20 / 0.28 / 0.33 host-s for 1/2/3/4) | **5 segfaults** (`Camera5.cal` missing; host exit 139). On the board `sbc_read` reports a signal-killed child as exit 1 (015 TASK §3.2) — inferred, not run there |
| frames | **1 only** | — | only frame 0 is staged. `frames=2` prints `Unable to load image: …image0001.bmp` and still **exits 0**. **Not a usable knob. Do not offer it.** |
| particles | **≥ 25 in this task** (5 works) | particle-filter work, linear (slope about 0.00044 host-s per particle) | **1, 2, 3, 4 hang** (>600 s CPU at 1; killed at 25–60 s at 2/3/4). A hung window burns `WORK_TMO` 1800 s |
| layers | ≥ 1 (1–10 tested) | annealing layers per frame, linear in work | — |
| model (arg 6) | **0** (auto = pthreads here) or **4** (serial) | 0 → `Threading with Posix Threads`; 4 → `Running Single Threaded` (a different code path; qemu's resident size was about 2 MB higher — indicative only) | **1 (TBB) and 3 (OpenMP) are not compiled in**: prints `Not compiled with …`, does nothing, **exits 0** |
| threads (arg 7) | ≥ 1 (1, 2, 4 tested) | pthread work pool; on a 1-CPU board they time-share | blocking primitives only (condvar, barrier), no spin-waits; pose output unchanged |
| outputBMP (arg 8) | 0 / 1 | writes `Result0000.bmp`; `0` skips the overlay render | `poses.txt` is written either way. At the centre the render is about 21% of the instructions |

### 2.2 Silent-failure list (the checker must catch all of these)

- `rc=0` with `Unable to load image` (frames ≥ 2) · `rc=0` with `Not compiled with` · `rc=0` with `Error loading initialization data` / `Error loading observation data`.
- A half-extracted `sequenceB_1` (015 TASK §7 S5): the harness already checks `CALIB/Camera1.cal` and bails.

### 2.3 Where the time goes — the centre mostly measures image filtering

Host CPU seconds under qemu-user (an **instruction-count proxy, not cycles**). Fit: about 0.286 fixed + 0.00044 per particle (at 3 layers).

| point | share of instructions in the particle filter |
|---|---:|
| P=100 (centre) | about 13% |
| P=1600 | about 71% |
| P=4000 | about 86% |

- So at the centre bodytrack is mostly **fixed per-frame image preprocessing**. The particle axis is how the run moves toward the actual tracker.
- The proxy relates to the board by one point: centre = 0.33 host-s ↔ **14.96 s on the board** (2026-10-01), factor about 45. **Board seconds in §4 are estimates** until §7.

### 2.4 The output is deterministic, and that gives a free oracle

- `sequenceB_1/poses.txt` is **bit-identical** across thread counts (1, 2, 4), the serial model, and the BMP flag; it depends only on cameras, particles, layers.
- The host md5s in §4 are the **independent oracle**. Board `poses.txt` should equal them. Between the OFF and ON arms of a point it **must** be equal.
- Rule: until the calibration shows the board matches the host for **every** point, a board-vs-host mismatch is a **warning**; after that it is **VOID**. An OFF-vs-ON mismatch is never retried (rule 9).

---

## 3. Platform and invariants

| thing | value |
|---|---|
| Board | VCU118, core + L2 at 50 MHz, 2 GiB DDR4, **1 CPU** |
| L2 | inclusive, **256 KB, 8-way, 512 sets**, 64 B blocks; L1 I/D 8 KB each |
| Bitstream | `CY=/home/damith/Research/repos/chipyard_performance_eval/chipyard`<br>`BIT1=$CY/fpga/bitstream_storage/FPGASingleRocketVCU118L18K256K8WL2ConfigSBCPLRU-256KB-8way-candidate013-2026-10-01-2026-10-01.bit`<br>sha256 `5ace41250e7c514142536feedccf5d12138406aaf1d1787f7565f4f78b9f9108` (check the first 16 hex before every programming) |
| SBC off | the same bitstream with migrations gated off (`--migrate=off`). No SBC-free twin exists |
| Serial | `/dev/ttyUSB2`, 115200, login `root` / `fpga`, prompt `"# "` |
| Board dirs | workloads in `/root/parsec`, `sbc_read` at `/root/sbc_read` |
| Flow of one window | [fresh-boot-campaign-handoff.md](../../guides/fresh-boot-campaign-handoff.md) §4 — reprogram → boot (about 21 of 28 min) → stage → `sync`, sleep 15 → set policy and switch, read back → **one** `sbc_read --zero -- <cmd>` → `--migrate=off` |

- **Single-core limit:** no per-application counters; one process here, so this task does not need them.
- **Board cycles understate DRAM cost** (a DRAM read is about 38 L2 cycles here). Memory accesses are the result; cycles are reported as measured; any projection to a faster core is a **labelled model** (reuse `project_dram_latency.py`).

---

## 4. The points

Label = `bt-c<cams>-p<particles>-l<layers>-m<model>-t<threads>-o<bmp>-plru`. Command = `./bodytrack sequenceB_1 <c> 1 <p> <l> <m> <t> <o>`.
"Host s" = qemu-user CPU seconds. "Board est." = host × 45, **an estimate**. md5 = first 12 hex of the host `poses.txt`.

### Phase 1 — centre, cameras, particles (8 points, 16 windows)

| # | token | changed from centre | host s | board est. | poses md5 (host) |
|--:|---|---|--:|--:|---|
| 1 | `c4p100l3m0t1o1` | **centre** (015 S5 reference) | 0.33 | 15 s | `9418d47d8861` |
| 2 | `c1p100l3m0t1o1` | cameras 1 | 0.14 | 6 s | `80da025e01e3` |
| 3 | `c2p100l3m0t1o1` | cameras 2 | 0.20 | 9 s | `a6eedcfefefd` |
| 4 | `c3p100l3m0t1o1` | cameras 3 | 0.28 | 13 s | `1e6396a6da02` |
| 5 | `c4p25l3m0t1o1` | particles 25 | 0.32 | 14 s | `c5713610a67c` |
| 6 | `c4p400l3m0t1o1` | particles 400 | 0.48 | 22 s | `b7bd89111c07` |
| 7 | `c4p1600l3m0t1o1` | particles 1600 (**C1**) | 0.99 | 45 s | `abbbd630fb9b` |
| 8 | `c4p4000l3m0t1o1` | particles 4000 | 2.04 | 92 s | `ad022a9e9132` |

### Phase 1 replicates (4 windows, user may drop — §11 Q3)

Second window per arm at points 1 and 7, in folder `r2/`, OFF before ON. Purpose: a **measured noise figure for this workload**. The LU sweep had none by design (one fresh-boot replicate by accident: 0.86% in memory accesses, 0.14% in cycles).

### Phase 2 — the other knobs, around C1 = particles 1600 (7 points, 14 windows)

Run only after stop (d). C1 is **proposed**, not fixed — confirm at stop (d) (§11 Q1). Reference for every row = point 7.

| # | token | changed from C1 | host s | board est. | poses md5 (host) |
|--:|---|---|--:|--:|---|
| 9 | `c4p1600l1m0t1o1` | layers 1 | 0.64 | 29 s | `7ca630f1e0c9` |
| 10 | `c4p1600l5m0t1o1` | layers 5 | 1.34 | 60 s | `ccaec8517956` |
| 11 | `c4p1600l10m0t1o1` | layers 10 | 2.22 | 100 s | `1ef3bf0c446a` |
| 12 | `c4p1600l3m0t2o1` | threads 2 (time-shared on 1 CPU) | 1.05 | 47 s | `abbbd630fb9b` |
| 13 | `c4p1600l3m0t4o1` | threads 4 | 1.13 | 51 s | `abbbd630fb9b` |
| 14 | `c4p1600l3m4t1o1` | serial model | 1.06 | 48 s | `abbbd630fb9b` |
| 15 | `c4p1600l3m0t1o0` | no BMP output | 0.93 | 42 s | `abbbd630fb9b` |

- Rows 12–15 share point 7's `poses.txt` md5 by construction: a free cross-point integrity check.
- **Not offered:** `frames`; `particles` below 25; `model` 1 or 3; `cameras` 5.

### Budget (about 28 min per stage, +2–3 min est. for bodytrack staging)

| block | boots | est. hours |
|---|--:|--:|
| §7 calibration | 1 | 0.8 |
| Phase 1 | 16 | 8 |
| replicates | 4 | 2 |
| Phase 2 | 14 | 7 |
| **total** | **35** | **about 18** |

Order inside each block: all OFF, then all ON. Stage order inside OFF = the table order.

---

## 5. Terms and bands (fixed now)

- **Memory accesses** = `memReads + memWrites` (64 B each). **Change** = ON minus OFF; negative = fewer with SBC on.
- Bands for one window per arm (noise seen so far: 0.5–0.9% in memory accesses, 0.14% in cycles):
  - |change| **< 1%**: none, inside noise;
  - **1–3%**: small, repeat before believing;
  - **> 3%**: clear.
- When the replicates exist, replace the 1% line by **2× the measured replicate spread** and say so in the report.
- No p-values with n = 1 per arm; do not print one.

---

## 6. Build (host only) — then stop (b)

Campaign folder (create it, nothing outside it except §8.3):
`chipyard/scripts/logs/clean-runs/bodytrack-sweep-single-core-<YYYYMMDD>/` with `PLAN.md`, `chain_status.txt`, `off/ on/ failed/ dropped/ results/ tools/ vivado/ calibration/ dryrun/`, later `RESULTS.md`, `r2/`. Copy the LU sweep's `tools/` as the starting point.

### Harness copy — `tools/run_parsec_session.exp` (diff against `run_parsec_session.exp.orig`)

- **B1** `-plan bt` and `-bt-points <token,token,…>`; label from the token as in §4. `point` keeps `p n b` empty (those are LU knobs).
- **B2** a new `bt_cmdline c p l m t o` returning the 8-arg command. **Leave `bench_bodytrack_cmd` and `plan_parsec` untouched.**
- **B3** `bt_validate`, called at plan time, **exit 2 with a reason** for: cameras ∉ 1–4, particles < 25, layers < 1, model ∉ {0, 4}, threads < 1, bmp ∉ {0, 1}, any `frames` token. (§2.1 traps.)
- **B4** in `measure`, for bodytrack points only, append to the board command line, **after** `tail -5 wl.err`:
  `; md5sum sequenceB_1/poses.txt`. It runs after the window is printed, so it cannot change the counters, and it is the same in both arms.
- **B5** `-plan` accepts `bt`. Nothing else changes.

### Checker — `tools/check_bodytrack_stage.py` (copy of `check_lu_stage.py`)

Keep **every** check the LU checker makes (one reprogram of the right file, one boot, 1 CPU, one window, rc=0, read-back of switch and policy, counter identities, kernel-crash rules including the v2 `workqueue lockup` position rule, tty-echo wrap handling). Change the label regex to §4's, and add:

- **C1** the exact command for the label appears in the transcript (after un-wrapping the 80-column echo).
- **C2** read the workload's **stdout between the command line and `workload rc=`**. Require: `Processing frame 0`, `<P> particles with <L> annealing layers`; for model 0 `Number of threads : <T>`, for model 4 `Running Single Threaded`. **VOID** if any of: `Unable to load image`, `Unable to open`, `Error loading`, `Not compiled with`, `Invalid thread model`, `Segmentation`, `unhandled signal`.
- **C3** the `md5sum` line is present. Compare with §4's oracle: warning or VOID per §2.4.
- **C4** the checker never looks at the size of the effect.

### Launcher — `tools/launch_bt_sweep.sh` (copy of `launch_lu_sweep_v4.sh`)

- `LABELS` = §4 order; `HARNESS_ARGS` = `-plan bt -bt-points … -bit "$BIT1" -policy plru`; `tmux` session `sbc016-bt`.
- Failure policy = the LU sweep's v4 (user's, 2026-10-08): pass 1 one attempt per stage and a failure is deferred; pass 2 up to 2 retries per deferred stage; 3 failed attempts in a row stop the chain; 150 s settle before a reprogram that follows a failed attempt. **Ask before changing it.**
- Never passes `-allow-*`. Re-running resumes: a stage with a valid `stage_result.json` is skipped.

### Host tests (all before the board)

| # | test | must do |
|--:|---|---|
| H1 | `info complete` on the harness copy | parses |
| H2 | `-plan bt -bt-points <all 15> -list` | 15 labels and commands exactly as §4 |
| H3 | `-plan parsec -list` and `-plan lu -list` | **diff against the original harness's output is empty** |
| H4 | `-bt-points` with `c5`, `c0`, `p4`, `p24`, `l0`, `m1`, `m3`, `t0`, `o2`, and a `f2` (frames) token | each refused, exit 2, reason printed |
| H5 | checker on fixtures: valid OFF; valid ON; **rc=0 + `Unable to load image`**; missing `Processing frame 0`; wrong particle/thread text; `Not compiled with`; md5 mismatch; `unhandled signal`; two windows; wrong switch read-back | VALID only for the first two; each other case VOID with the right reason |
| H6 | launcher against fixtures | preflight rejects a wrong bitstream sha; resume skips a valid stage; an ON stage needs its OFF stage |
| H7 | oracle script: rerun the §2 host runs from a **scratch copy** of `staged/sequenceB_1` | reproduces every md5 in §4 (save as `calibration/oracle_host.tsv`) |
| H8 | `make_results.py` (§8.3) on fixtures, incl. a setting with one arm | `not run` shown for the missing arm |

Report each as PASS or FAIL in `REPORT.md`. Then **stop (b)**.

---

## 7. Calibration boot — the board dry run (not a measurement)

One boot, `-prog $BIT1`, `-phases off`, `-repeat 1`, `-push always`, `-bt-points` = all 15, in `calibration/`.
This also exercises staging (tarball, extraction, the `CALIB` check) and the new command forms (rule 16).

Purpose, in order:

1. Does every point run to completion with rc=0 **and** the C2 markers, with no hang?
2. Board seconds per point (`L2_Cycles / 50e6`) against §4's estimates; the real factor. Every window must be far below `WORK_TMO`.
3. Does the board `poses.txt` md5 equal the host's, for **all** 15? (§2.4)
4. The OFF levels (`accessA`, reads, writes, hit %) per point, to write predictions from.
5. The stage timeline: how long does bodytrack staging take (rule 17)?

- It is an **aged-board** series: **never** use its numbers as results.
- Write `calibration/CALIBRATION.md` (table: point · board s · host s · factor · md5 host/board · accessA · reads · writes · hit %).
- Then **stop (c)**.

After stop (c): write `PLAN.md` §Expectations (§9) with **numeric** bands, then start stage 1.

---

## 8. Running, and the report

### 8.1 Running

- Phase 1 main block: 8 OFF stages, then 8 ON stages. Then the replicate block. Then stop (d).
- One window per boot. The launcher judges each attempt with the checker; a void attempt is kept under `failed/`.
- Update `RESULTS.md` after every valid stage (copy `lu-fft-search-20261009/tools/autoupdate_results.sh`; the LU sweep itself had no such script).

### 8.2 `RESULTS.md` — same shape as the LU sweep's

Results only, regenerated in place by `tools/make_results.py`:

- **Setup** (command, one core, 256 KB 8-way L2, PLRU, sha, "every window: fresh reprogram + boot", spread statement).
- **SBC off vs on, per setting** — memory accesses off / on / change / change % / migrations / secondary hits.
- Second table — L2 hit rate off / on / change (pp) · L2 cycles off / on · run-time change.
- **Windows** — setting · SBC · started · memory reads · memory writes · memory accesses · hit rate · window (s).
- **Void attempts** (kept under `failed/`).
- Setting names show **only the fields that differ from the centre**: `bodytrack centre (4 cams, 100 particles, 3 layers)`, `bodytrack cameras=2`, `bodytrack particles=1600 layers=5`.

### 8.3 `results_common.py` (shared by four campaigns)

- Needs `name_of` and `sort_key` branches for `bt-…` labels. **Additive only.** Back up first as `results_common.py.v2`.
- Then regenerate the other four campaigns' `RESULTS.md` and show that they differ **only in the "updated" timestamp**.

### 8.4 `REPORT.md` — the narrative, in the LU-sweep section's shape

Fill the template as you go. For each phase: Status · points · method · gate · events · results table (reads Δ and writes Δ separately, hit rate off→on, cycles, migrations) · **where SBC pays** · **run time** · a **labelled model** projection (only if a change is outside the band) · noise · provenance (attempts valid/void, launcher and checker versions).
Plus the per-knob summary table and the verdict paragraph (§12).

---

## 9. Expectations — write before stage 1, never edit afterwards

Mine, as **priors** (may be wrong; keep them as written for comparison):

- **E1 centre:** inside noise (|change| < 1%). The run is about 87% image preprocessing that streams through the cache; hit about 84% on the aged board; no hot set to relieve.
- **E2 cameras 1–3:** inside noise; fewer cameras means less data and a higher hit rate, so less to save.
- **E3 particles:** if SBC pays anywhere it is at 1600 / 4000, where random lookups into the edge and foreground maps dominate (71–86% of instructions). Expect 0 to −3%, reads falling more than writes (writes are about 31% of the traffic at the centre).
- **E4 layers, threads, serial, BMP (Phase 2):** inside noise — they change work or interleaving, not the data set.
- **Wrong if:** any change of −5% or better anywhere, or +1% or worse anywhere (SBC hurting). Both would be findings.

Your job after calibration: replace these with **numeric bands per point** using the OFF levels, in `PLAN.md`, dated, fixed. After the OFF half you may add a second set, labelled as written after the OFF half. Grade both in the report: right / wrong, no softening.

---

## 10. Acceptance gates for every window

Enforced by the checker (never loosen):

- exactly one FPGA programming of the file whose first 16 sha hex match; one Linux boot; `nproc` 1;
- one measured window; the exact command; rc=0; C2 markers present, forbidden strings absent;
- hardware read-back of `migrate` (the arm's value) and `policy plru`;
- `accessA = primaryHit + secondaryHit + dataMiss + upgradeMiss` (±64); `memReads + memAcqPerm = dataMiss + upgradeMiss`; OFF windows: `migrations`, `secondaryHit`, `secondSearch` all 0;
- no kernel crash text (`BUG:` / `Oops:` / `Kernel panic`) while the window is open.

Checked by you, in the report (flag, not retry):

- `accessA` OFF vs ON within 1%; window length OFF vs ON within 1%;
- `poses.txt` md5: OFF = ON for every point (stop if not), and equal across rows 7, 12–15;
- OFF rows: `memReads − memWrites − memRelClean == 0`; ON rows: record the shortfall (015 F10: about 98% of the migration count), do not explain it.

---

## 11. Questions to put to the user in your plan reply (my defaults in brackets)

1. **Q1** Phase-2 centre C1 = particles 1600? [yes; revisit at stop (d) with the Phase 1 result]
2. **Q2** Order = all OFF then all ON? [yes; per-point off→on is faster to read but needs the user's ruling]
3. **Q3** Keep the 4 replicate windows? [yes, about 2 h, gives a real noise figure]
4. **Q4** Failure policy = the LU sweep's v4? [yes]
5. **Q5** Centre keeps `outputBMP = 1` (the established command), `0` is a Phase-2 knob? [yes]

---

## 12. Deliverables

1. §6 built and host-tested (H1–H8 in `REPORT.md`); stop (b).
2. §7 calibration and `CALIBRATION.md`; stop (c). `PLAN.md` with numeric expectations.
3. Phase 1: `RESULTS.md` with 8 points + replicates; REPORT section; stop (d).
4. Phase 2 (after the user's go): same; stop (e).
5. **Per-knob summary table** in `REPORT.md`:
   `knob | values | OFF hit rate range | change in memory accesses | band | direction | one line`.
6. **One paragraph in plain words** answering §1: does SBC change bodytrack's traffic, by how much, which knob moves it, and whether it fits the rule that SBC pays only while a hot set's overflow fits the empty ways of its partner. Say it plainly if the answer is "no effect".
7. `REPORT.md` filled in as you go, with every log path. Nothing committed.

## 13. Not in scope

- `frames` (needs inputs that are not on this machine: only `sequenceB_1`, 1 frame).
- Pairs: bodytrack as a co-runner is 015 S5 (parked).
- Dual core; any bitstream build; `lu_cb`, `barnes`, `canneal`.
- Editing 015's files.

---

## Amendment 1 — 2026-10-09 evening: the user's answers to §11 (these win over §4, §8.1, §11 and stop (d))

User, verbatim: *"1. I cannot exactly tell about that. choose appropiate number. 2. no run on/off combinations and then
depending on the result decide how we change the parameters to get the optimum numbers. 3. No first complete one sweep
through configuration under phase 2, then tell me the results then I will tell you how many repeat runs we need. 4. yes.
5. I'm not sure whether this will work or not. you decide. and try to fix any bugs. but do not change the hardware."*

- **A1.1 (Q1) Phase-2 centre C1** is chosen from the Phase 1 data: the particle count with the largest saving that is
  outside the 1% band; if none is, particles 1600. Recorded in `PLAN.md` before the first Phase 2 window.
- **A1.2 (Q2) Order: each setting runs SBC off, then SBC on, back to back** (rule 4 and §4's "all OFF, then all ON" are
  replaced). Every window is still the first and only one after a fresh reprogram, so no window's history changes.
  **The search is adaptive:** after each setting, the next settings are chosen from the results so far, aiming at the
  settings where SBC saves most. Every chosen setting and its reason go in `PLAN.md` (dated) **before** its windows run.
  Every window is reported, not only the best; the best of many single runs is biased upward, so it is labelled as the
  best of N until repeats exist.
- **A1.3 (Q3) No replicates now.** The replicate block of §4 is dropped. One sweep through Phase 1 and Phase 2 (and the
  adaptive extras), then report to the user, who decides the repeats. Stop (d) is dropped: Phase 1 runs straight into Phase 2.
- **A1.4 (Q4)** Failure policy = the LU sweep's v4, applied per launcher batch: one attempt per stage; a void OFF defers
  its setting (OFF and ON), a void ON defers the ON; at the end of the batch up to 2 retries per deferred stage, OFF
  before its ON; 3 void attempts in a row stop the chain; 150 s settle after a void attempt.
- **A1.5 (Q5)** Decided: the centre keeps `outputBMP = 1` (the established 015 S5 command, same program behaviour as
  the 7-argument form). `outputBMP = 0` stays a Phase 2 setting.
- **A1.6** "Try to fix any bugs, but do not change the hardware": software bugs found on the way (harness copy, checker,
  launcher, scripts) are fixed in this campaign's copies and recorded with the fix; the bitstream and the RTL are not touched.
- **A1.7** The work is done in this chat (no separate coder hand-off). Stops kept: (c) only as a report line, not a wait,
  unless the calibration shows a problem; (e) = the end of the sweep, when the results go to the user.
