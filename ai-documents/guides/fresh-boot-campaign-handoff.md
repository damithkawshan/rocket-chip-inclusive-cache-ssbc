# Fresh-boot SBC campaign: handoff (config, flow, measurements, state)

**Status:** 🟢 use now · **Written:** 2026-10-09, end of the LU + FFT grid · **For:** a new chat that continues task 015
(multi-application verification) · **Nothing is committed** (user: "do not add any commits till we finalize anything. use existing bitfiles")

> **Update 2026-10-12:**
> - The L2 repo's docs are committed (user's request)
> - **Next config (user decision): 512 KB 8-way L2 + 32 KB 8-way L1, single core, C3 RTL.** Image **built 2026-10-12
>   14:01**, sha256 `37923e6bb32606e6e082bb6ba65ba2a4533ca5d48c72744a23a2ae3f7282d3ee`, archived in `fpga/bitstream_storage/`
>   (`FPGASingleRocketVCU118L132K512K8WL2ConfigSBCPLRU-512KB-8way-L1-32K8W-c3-2026-10-12.*`). Not on the board yet.
>   Section 2 describes the 256 KB image of every result so far. A campaign on the new image needs its launcher's
>   `BIT1` / `BIT1_SHA` changed
> - The 1 MB runs (task 013's A/B) are set aside
> - bodytrack (task 016, 36 windows) used this same method and image: section 6, row 2.7

## 0. Read in this order

1. This file (setup, flow, rules, state)
2. [fresh-boot-experiments-2026-10-09.md](../performance/fresh-boot-experiments-2026-10-09.md) ⭐ all results, 64 valid windows, observations O1–O8
3. `chipyard/scripts/logs/clean-runs/README.md` (what "clean" means, folder index, `RESULTS.md` makers)
4. The `PLAN.md` of the campaign you extend (grid, expectations fixed before the windows, incidents)
5. [015 TASK](../coder/015-pair-verification-campaign/TASK.md) (the original question, S1–S7 stages; S2 onward not run)
6. Memory rules (section 8) — they are also in `~/.claude/projects/…/memory/`

## 1. The question and the answer so far

- Question (015 TASK §1): does SBC's benefit come from applications interfering, or from one program sitting at a low hit rate?
- Answer so far: the second. SBC helps a program whose own working set overflows the L2; a partner adds little
- Evidence, all fresh-boot apple to apple (details in the ⭐ report):
  - LU alone: −12.9% memory accesses at n=512 b=64, −3.6% at b=128, ~0 at b=16/32 (hit rate 93–94%)
  - Pairs: 10 of 14 recovered values are below 10% of the interference; the ones at 10% or more are LU b32+b128 (22%, 12%) and LU b128 + FFT m16 (23%) / m20 (22%)
  - LU + streamcluster keeps ~27% of LU's gain alone (−0.24 M of −0.88 M); LU + FFT keeps it (or a little more)
- Old numbers that are wrong (board age): pair −12.58%, one-boot LU sweeps. Do not quote them

## 2. Hardware and build config

| item | value |
|---|---|
| board | VCU118 (xcvu9p), 2 GB DDR4, core + L2 at 50 MHz (`WithFPGAFreq50MHz`) |
| core | 1 Rocket big core (`WithNBigCores(1)`) |
| L1 | I and D, 8 KB each (32 sets × 4 ways × 64 B) |
| L2 | inclusive, **256 KB, 8-way, 512 sets**, 64 B lines |
| SBC | built in: `sbcAutoMigrate = true`, `sbcShadow = false`, `sbcDebug = false`; migrations gated at run time by `sbc_read --migrate=off|on` (`SBC_MigrateEnable`, 0x3C0) |
| replacement | `plruReplacement = true` compiled in; policy is **random after every reprogram**, so every window sets `--policy=plru` and reads it back |
| OS | Linux 6.6.0 from the SD card, login `root` / `fpga`, 1 CPU, busybox 1.36.0 (has `taskset`, no `timeout`) |
| serial | `/dev/ttyUSB2`, 115200, files by zmodem (`sz`/`rz`) |

**Config class chain**
- `FPGASingleRocketVCU118L18K256K8WL2ConfigSBCPLRU` ([Configs.scala:148](../../../../fpga/src/main/scala/vcu118/Configs.scala)) = `WithFPGAFreq50MHz ++ WithVCU118Tweaks ++ chipyard.SingleRocketVCU118L18K256K8WL2ConfigSBCPLRU`
- `chipyard.SingleRocketVCU118L18K256K8WL2ConfigSBCPLRU` ([RocketConfigs.scala:234](../../../../generators/chipyard/src/main/scala/config/RocketConfigs.scala)) = `WithInclusiveCache(nWays = 8, capacityKB = 256, sbcAutoMigrate = true, sbcShadow = false, sbcDebug = false, plruReplacement = true) ++ WithL1DCacheSets(32) ++ WithL1ICacheSets(32) ++ WithNBigCores(1) ++ AbstractConfig`

**The bitstream used by every window (BIT1)**
- File: `chipyard/fpga/bitstream_storage/FPGASingleRocketVCU118L18K256K8WL2ConfigSBCPLRU-256KB-8way-candidate013-2026-10-01-2026-10-01.bit`
- sha256 `5ace41250e7c514142536feedccf5d12138406aaf1d1787f7565f4f78b9f9108` (launchers check the first 16 hex before every programming; cite the sha, never the filename)
- Built 2026-10-01 09:20 (AEST) by `scripts/ssbc_scripts/build_256kb_8w.sh`; provenance (per-file sha of the RTL) in the `.provenance.txt` next to it
- RTL = task 013 C3 `fed7227`; chipyard HEAD `4b250e5`, L2 HEAD `73508c5` on branch `sbc-013-sat-counter`; `git diff fed7227 HEAD -- design` is empty, so later commits are docs only
- **SBC off = the same bitstream with migrations gated off.** There is no SBC-free twin of this geometry; the off arm is a plain PLRU L2 with the SBC hardware idle (zero migrations, checked on every off window)
- Dual-core twin (not used by the fresh-boot campaign): `…DualRocketVCU118L18K256K8WL2ConfigSBCPLRU-dual-256KB-8way-2026-10-02.bit`, sha `71fa886e…e418`. Its Scala configs are **uncommitted** edits in `RocketConfigs.scala` and `Configs.scala`
- Rebuild: since 2026-10-12 use `scripts/ssbc_scripts/build_bitstream.sh <FPGAConfig> <tag>`. It replaces `build_256kb_8w.sh`, archives the `.bit` with its provenance, and refuses to start while another build runs. Never `make clean` in `fpga/`. Recipe: [fpga-linux-run.md](fpga-linux-run.md) section 1

## 3. Programs on the board

All binaries live in `chipyard/software/parsec-benchmark/staged/` and are pushed over zmodem on every boot.

| program | command in the windows | notes |
|---|---|---|
| `lu_ncb` (SPLASH-2 LU, non-contiguous blocks) | `./lu_ncb -p1 -n512 -b<B>` | matrix n·n·8 B; b64 needs all 8 ways (knife edge); no `-t` (its self-check fails on the board, off and on alike) |
| `fft` (SPLASH-2, task 014) | `./fft -p1 -m<M> -l4` | 3 arrays of 2^M complex doubles; `-m 12/16/20` = 0.8× / 12× / 192× the L2; `MxK` runs it K times back to back |
| `streamcluster` | `./streamcluster 2 3 16 43008 2048 200 none output.txt 1` | only through `pair.sh` (2.2/2.3 of the report) |
| `sbc_read` | wraps every window | built from `generators/rocket-chip-inclusive-cache/sw/sbc_read.c` |

- A pair = two processes started in the background of one `sh -c` on the one core (Linux time slicing); the checker needs overlap ≥ 0.5
- `pair.sh` (LU + streamcluster, 2.2/2.3) pins with `busybox taskset -c`; it adds ~0.86 M accesses per run, so compare only within its group

## 4. Flow of one measured window (the unit of every campaign)

1. Reprogram BIT1 (`scripts/ssbc_scripts/programFPGA.sh`, Vivado batch over JTAG; sha256 checked first)
2. Bootrom loads Linux from the SD card (about 21 of the ~28 min of a stage), kernel boots, harness logs in
3. Mount the SD card, push `sbc_read` and the programs over zmodem, `sync`, sleep 15
4. `sbc_read` status; set `--policy=plru` and `--migrate=<off|on>`; read both back
5. **ONE window:** `sbc_read --zero -- <command>` → zero the hardware counters, run the command to completion, print absolute counters (one hold bracket, so all registers come from the same cycle)
6. `sbc_read --migrate=off`
7. Checker writes `stage_result.json` (VALID or VOID) and copies the counters; a VOID boot is kept in `failed/` and retried on a fresh boot after a 150 s settle

- SBC off and on follow exactly these steps; only `--migrate=` differs. One window per boot, in both arms
- Why: with migration never on, off-arm memory traffic rose with board age (pair +13% over six windows, LU alone +17%, streamcluster alone +41%). Fresh-vs-aged compares board age as well as SBC ([015 REPORT](../coder/015-pair-verification-campaign/REPORT.md) F8, F11)
- Budget boots, not runs: ~28 min per window, ~12 h for a 24-window grid
- Order inside a campaign: all off before any on is the default rule; per-combination off then on (each on after its own off) was approved for the LU + FFT grid only. Ask before assuming it again

## 5. What is measured

Counters come from the L2 control block (MMIO, read by `sbc_read`; offsets in [devmem-register-map.md](devmem-register-map.md)); words follow [cache-terminology.md](cache-terminology.md).

| counter | meaning |
|---|---|
| `memReads` | blocks read from DRAM (identity: `memReads + memAcqPerm = dataMiss + upgradeMiss`) |
| `memWrites` | dirty blocks written to DRAM |
| **memory accesses** | `memReads + memWrites`: the headline number, 64 B each |
| `accessA` | L2 accesses (inner-A requests); the same-work check, within 0.5% between arms |
| `primaryHit`, `secondaryHit`, `dataMiss`, `upgradeMiss` | the four outcomes of an access; hit rate = (primary + secondary) / accessA |
| `L2_Cycles` | 50 MHz L2 clock over the window: run time = cycles / 50e6 s |
| `migrations`, `secHits`, `secMiss` | SBC activity (zero in every off window) |

**Derived quantities (all against the matching baseline, same SBC setting)**
- Baseline = the programs alone; pair = together; **interference = pair − baseline**
- **Recovered = interference (off) − interference (on)**; share = recovered / interference (off)
- Bands fixed before the first pair window: below 10% = no real recovery; 10–25% = weak, repeat before believing; 25% or more = real recovery
- Noise: 0.5–0.6% per window; LU b64 also carries about ±0.5 M page-colour noise per program
- Run time barely moves (largest L2-cycle change over 27 settings: −1.22%). A DRAM read costs only ~38 L2 cycles on this board, so memory accesses, not cycles, are the result; any cycle projection to a fast core is a labelled model (`lu-sweep-single-core-20261008/results/dram_latency_projection.txt`)

**Validity checkers (judge validity only, never the size of the effect)**
- One programming of the right file, one boot, one CPU, no kernel crash, the exact command, exit code 0, read-back of switch and policy
- Counter identities: four outcomes sum to `accessA` within 64; `memReads + memAcqPerm == dataMiss + upgradeMiss`; off: zero SBC activity
- Pairs: overlap ≥ 0.5 (warning below 0.9)

## 6. Experiments done (all fresh boot per window; folder = `chipyard/scripts/logs/clean-runs/…`)

| # | experiment | windows | folder | result in one line |
|---|---|--:|---|---|
| 2.1 | LU alone, 9 points (n 256–1024 at b128; b 16–256 at n512) | 18 | `lu-sweep-single-core-20261008` | −3.2 to −4.7% at b128; −12.9% at b64; ~0 at b16/b32; −1.3% at b256 |
| 2.2 | LU b64 alone via `pair.sh` | 3 | `solo-lu-b64` | −14.2% (window #1 of each session only) |
| 2.3 | LU b64 + streamcluster via `pair.sh` | 7 | `pair-lu-streamcluster-b64` | −2.0% (p = 0.073); old −12.58% was board age |
| 2.4 | LU b32 + LU b128, two runs | 8 | `lu-pair-single-core-20261008` (`r2/` = run 2) | recovered 22% and 12% |
| 2.5 | LU pair search (b64+b64, b64+b256, b32+b32 on and off; two more off) | 8 | `lu-pair-search-20261008` | recovered 6%, −3%, 2%; cache model `model/lusim.c` |
| 2.6 | LU (b32/b64/b128) × FFT (m12 ×84, m16 ×10, m20) grid + FFT alone | 24 | `lu-fft-search-20261009` | recovered below 10% in 7 of 9 cells; b128 + m16 23%, b128 + m20 22% |
| 2.7 | bodytrack, one parameter at a time (task 016) | 36 | `bodytrack-sweep-single-core-20261009` | +4.1% / +3.7% at 1600 / 4000 particles; −1.2 to −1.9% around 400; centre −0.4%; no repeats |

- Each folder: `PLAN.md` (method, predictions fixed in advance, incidents), `RESULTS.md` (results only, regenerated), `chain_status.txt`, `off/` and `on/` (`<label>/stage_result.json`, `stage_check.txt`, CSV, session log), `failed/` (void attempts), `tools/`, `vivado/`
- Cross-experiment data: `ai-documents/performance/data/2026-10-09-fresh-boot/windows.csv`; rebuild with `build_windows_csv.py` then `make_tables.py` (it refuses to change text outside the table markers)

## 7. Tools and how to run something new

| tool | what it does |
|---|---|
| `scripts/ssbc_scripts/run_parsec_session.exp` | the harness (`expect`); shared serial/staging layer in `scripts/ssbc_scripts/lib/`; each campaign keeps its own **copy** in `tools/` (diff against `.orig`), plans `parsec lu cal sccal lupair fft lufft` |
| `tools/launch_*.sh` | launcher: `STAGES="off:lufft:128,16x10 on:lufft:128,16x10" tools/launch_lufft.sh`; preflight checks the bitstream and harness sha, an on stage needs its off stage; up to 3 attempts per stage, stops the chain if all 3 are void; re-running skips valid stages; log in `chain_status.txt` |
| `tools/check_*_stage.py` | the validity checker above |
| `tools/make_results.py`, `autoupdate_results.sh` | regenerate `RESULTS.md` (shared tables from `clean-runs/tools/results_common.py`; `make_pairsh_results.py` for the two `pair.sh` folders) |
| harness `-list` | prints the plan without touching the board |

**To run a new combination**
1. Copy the nearest campaign's `tools/` into a new dated folder; write `PLAN.md` first (grid, expectations, bands)
2. Add the command shape to the harness copy; `-list` it
3. **Dry run on a booted board** (`-noprog -skippush -tmo 300 -phases off`, files in `dryrun/`): `-list` and checker tests do not exercise the board-side `test -x` probe
4. Record the new harness sha in the launcher; start the chain in `tmux`
5. A repeat of an existing label needs its own output folder: `launch_lu_pair.sh` v2 does it with `PAIR_RUN=r2` (`OUT=$PAIR${PAIR_RUN:+/$PAIR_RUN}`); `launch_lufft.sh` has no such switch yet and would skip a valid stage. `results_common.py` already reads `rN/` folders

## 8. Standing rules (user's, from this campaign)

- **Do not commit** anything until the user says so; use the existing bit files (no rebuilds). *2026-10-12: the user
  asked for the L2 repo's docs to be committed and for a new 512 KB image; still ask before any other commit or build*
- `RESULTS.md` = results only, updated in place; answer every question in chat; standard terms (baseline, interference, recovered, percentage points), bullets and compact tables, short lines
- A/B: matched board history, one fresh-boot window per arm, same steps; ask before changing the order
- Never kill a launcher while its window is running: the tmux session closes and the harness dies with it (lost a boot, 2026-10-09)
- A silent boot or a broken zmodem transfer is a void attempt: retry on a fresh boot (the launcher does), say so, never judge a window by its values
- Check timing claims against logs or harness code before quoting them (a stage is ~28 min; `BOOT_TMO` is 1800 s)
- Board cycles understate DRAM cost: report memory accesses as the result, cycles as measured, any projection labelled as a model
- Before an FPGA build: delete that config's `generated-src` dir first; never `make clean` in `fpga/`
- Session logs hold binary bytes: `grep -a`, never plain `cat`

## 9. State at the end of 2026-10-09

- **Board idle**, no launcher or harness running. Idle tmux sessions from the campaigns remain (`sbc015-*`, `lufft-sweep`); they hold no running process
- All 6 experiments finished; every folder has a `RESULTS.md` with the same off/on tables
- Expectations of the LU + FFT grid: E1 holds; E2, E3, E4 fail (see its `PLAN.md` and the report, section 2.6)
- **Uncommitted** (chipyard repo): `fpga/src/main/scala/vcu118/Configs.scala` and `generators/chipyard/src/main/scala/config/RocketConfigs.scala` (dual 256 KB configs), `scripts/ssbc_scripts/run_board_session.exp`, `tests/CMakeLists.txt`, three campaigns' `make_results.py` / template edits, `fpga/bitstream_storage/`, and all of `scripts/logs/clean-runs/` except what an earlier commit holds
- **Uncommitted** (L2 submodule `generators/rocket-chip-inclusive-cache`): `ai-documents/README.md`, the ⭐ report and its `data/`, this file, `CLAUDE.md` pointer; also untracked and untouched by this work: `scratch/`, `tmp.md`, `sw/hugepage_setmap.c`, `sw/sat_snap.c`
- Backups of every edited generator sit next to it (`.v1`, `.before_common`, …)

## 10. What to do next (nothing is started; each needs the user's go)

> **2026-10-12:** the next config is 512 KB 8-way + 32 KB 8-way L1 (header note). The rows below were written for the
> 256 KB image; which of them to run on the new image is not decided yet. Also open: bodytrack repeats (016 REPORT).

| question | experiment | cost |
|---|---|---|
| Is the LU b128 result (4 of 5 pairs at 12–23%) real? | repeat LU b128 + FFT m16 ×10 and b128 + m20, off and on, in a new `rN` folder | 4 windows, ~2 h |
| Why does streamcluster erase LU's gain when FFT does not? | streamcluster alone, off and on, on fresh boots (makes 2.3 comparable); then the per-set profiler (`SBC_SetSel` 0x300 / `SBC_SetSat` 0x308) on both pairs | 2 windows + profiler |
| How large is LU b64's page-colour spread? | LU b64 alone, off and on, repeated | 2–4 windows |
| What do fewer accesses save on a fast core? | extend the labelled DRAM-latency model to the pairs | no board time |
| Still open from the 015 TASK | S2 dose-response (partner footprint), S3 self-pairs, S7 dual core with per-application attribution | see TASK §7 |

- Suggested first step for a new chat: confirm the board is idle (`ps` for `run_parsec_session`, `picocom`, `vivado`), re-read section 8, then ask the user which row of section 10 to run
