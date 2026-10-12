# SBC on vs off, fresh boot per window: LU alone, LU + LU, LU + FFT, LU + streamcluster

> ## ⭐ The most important set of experiments so far
>
> These are the first SBC on/off results where **the SBC switch is the only difference** between the two arms: every
> measured window ran on its own fresh FPGA reprogram and Linux boot, with the same steps before it in both arms.
> They replace the numbers of [lu-single-app-2026-10-02.md](lu-single-app-2026-10-02.md) and
> [lu-streamcluster-pair-2026-10-05.md](lu-streamcluster-pair-2026-10-05.md), which compared a fresh board with an aged one.

**Dates:** 2026-10-05 to 2026-10-09 · **Status:** complete (the LU + FFT grid finished 2026-10-09 16:33, 24 of 24 windows valid)
**Board:** VCU118, single-core Rocket, 8 KB L1, **256 KB 8-way L2 (512 sets), PLRU**, 2 GB DDR4, Linux 6.6.0
**Bitstream:** sha256 `5ace41250e7c5141…` (C3 RTL `fed7227`), the same file for every window
**Data:** [data/2026-10-09-fresh-boot/windows.csv](data/2026-10-09-fresh-boot/windows.csv) (every valid window, all
counters) · raw transcripts in `chipyard/scripts/logs/clean-runs/` (section 8)

---

## Summary

- **Interference depends on the victim, not the partner.** LU b64 (its block needs all 8 ways) loses the most with
  every partner: +2.3 to +8.5 M memory accesses. LU b32 and b128 lose 0.6–1.5 M (except b32 + b32, +3.0 M)
- **The partner's size barely matters.** FFT at 0.8×, 12× and 192× the L2 costs LU b64 +3.6, +3.7 and +4.1 M
- **SBC's gain on a pair ≈ the sum of the two programs' gains alone.** The values at 10% or more are all in the
  "weak, repeat" band:
  <!-- table:share_count -->
10 of 14 recovered values are below 10%. At 10% or more: LU b32 + b128 run 1 (22%), LU b32 + b128 run 2 (12%), LU b128 + FFT m16 ×10 (23%), LU b128 + FFT m20 (22%).
<!-- /table:share_count -->
- **One exception worth a repeat: LU b128 as the program that suffers.** 4 of its 5 pairs recovered 12–23% (each in
  the "weak, repeat" band, one window per arm); the 5th (FFT m12 ×84) recovered 5%. The amounts are small: 0.13–0.34 M
- **SBC's gain alone comes from one program:** LU b64 −12.9%. LU b128 −3.6%, b256 −1.3%, b16/b32 ≈ 0, FFT ≈ 0 at
  every size
- **Whether that gain survives a partner depends on the partner.** With LU or FFT the pair keeps LU b64's gain
  alone (or a little more). With streamcluster the pair keeps about 27% of it (−0.24 M of −0.88 M)
- **With a partner, SBC migrates more and gains less per migration:** LU b64 alone saves 45 accesses per migration;
  pairs with b64 save 6–10; LU + streamcluster saves under 1
- **Run time barely moves:** L2 cycles change by at most about 1.2% (a DRAM read costs ~38 L2 cycles on this board),
  so memory accesses are the measured effect
- **Fresh boots were essential:** the old LU + streamcluster headline (−12.6%) was about nine-tenths board age;
  fresh-vs-fresh it is −2.0%

### SBC on vs off at a glance (added 2026-10-12)

Change in memory accesses (reads + writes), SBC on vs off, per setting; mean of the windows in `windows.csv` (most
settings one window per arm). Negative = fewer accesses with SBC on. bodytrack is from task 016 (section 2.7).

- **LU alone:** b64 **−12.9%** · b128 −3.2 to −4.7% at every n (256 … 1024) · b256 −1.3% · b16 −0.3% · b32 +0.4%
- **LU + LU:** b64+b64 **−9.9%** · b32+b128 −4.3% (2 windows per arm) · b64+b256 −3.9% · b32+b32 −1.0%
- **LU + FFT:** with LU b64 −5.0 to −8.8% · with b128 −3.0 to −4.0% · with b32 0.0 to +0.9% · FFT alone −0.2 to +1.0%
- **LU b64 + streamcluster:** −2.0% (3 OFF, 4 ON windows) · LU b64 alone through `pair.sh` −14.2% (1 ON window)
- **bodytrack:** centre −0.4% · −1.2 to −1.9% around 400 particles · **+4.1% / +3.7% at 1600 / 4000 particles** ·
  +1.1 to +2.4% at 25–50 particles, the serial model and 4 threads · cameras ≈ 0
- In one line: SBC cuts memory accesses only when LU b64 or b128 runs; everything else is ≈ 0 or slightly worse

---

## 1. Method

**Apple to apple (one window per boot)**
- Each measured window: reprogram the FPGA → boot Linux → copy the programs over the serial line → one window
- SBC off and SBC on go through exactly the same steps; only `sbc_read --migrate=off|on` differs
- Why: on this board SBC-off memory traffic rises with board age (one boot, six windows: pair +13%, LU alone +17%,
  streamcluster alone +41%; [015 REPORT](../coder/015-pair-verification-campaign/REPORT.md) F8, F11)
- Order varied: 2.1 and 2.5 ran all SBC-off windows first; 2.4 ran off then on per repeat; in 2.3 the SBC-on
  windows came first; 2.6 ran each combination off then on from 2026-10-09 03:31 (user's choice, to steer the grid).
  Every window is its own boot, so the order does not change any window's history

**Common to every window**
- Same bitstream file (sha256 checked before every programming); PLRU set and read back on every window
- One core; two programs share it by Linux time slicing (`lu_ncb -p1`, `fft -p1`)
- Every window runs to completion (`sbc_read --zero -- <command>`), so both arms do the same work (accessA within 0.5%)
- From 2.1 on, a checker judges every window for validity only: one programming of the right file, one boot, one
  CPU, no kernel crash, the exact command, exit code 0, the counter identities, the read-back of the switch and
  policy, and for pairs an overlap of the two programs ≥ 0.5. It never looks at the size of the effect. A void
  window is kept and retried on a fresh boot (section 7)

**Terms**
- Memory accesses = DRAM reads + writes at the L2 (`memReads + memWrites`), one 64 B block each
- Baseline = the programs alone, same SBC setting; interference = pair − baseline
- Recovered = interference (SBC off) − interference (SBC on); share = recovered / interference (SBC off)
- Bands, fixed before the first pair window: below 10% = no real recovery; 10–25% = weak, repeat before believing;
  25% or more = real recovery

**Noise and run time**
- Same setting, two fresh boots: 0.6% alone (LU n=768), 0.5% pair (LU b32 + b128: SBC off 0.14%, SBC on 0.93%)
- LU b64 also carries page-colour noise: a pair window gets other physical pages than the alone window, about
  ±0.5 M per b64 program (cache model, [lu-pair-search PLAN](../../../../scripts/logs/clean-runs/lu-pair-search-20261008/PLAN.md))
- Run time:
  <!-- table:cycles_max -->
largest run-time change (L2 cycles, SBC on vs off, same setting) over all 27 settings: -1.22% (lupair-p1-n512-b64+b64-plru); the next largest is -1.17%.
<!-- /table:cycles_max -->
- A DRAM read costs ~38 L2 cycles on the VCU118 (memory is fast next to the 50 MHz core), so the board understates
  what fewer accesses would save on a GHz core;
  [dram_latency_projection](../../../../scripts/logs/clean-runs/lu-sweep-single-core-20261008/results/dram_latency_projection.txt)
  has a labelled model

---

## 2. The experiments

### 2.1 LU alone, 9-point sweep (18 windows, 2026-10-08)

- `lu_ncb -p1`, run by the harness directly; n = 256–1024 at b = 128, and b = 16–256 at n = 512; one window per arm
- 18 of 18 valid; 4 void attempts, all on n = 768 (section 7)

<!-- table:lu_alone -->
| n | b | memory accesses, SBC off | SBC on | change | L2 hit rate off → on | migrations | L2 cycles change |
|--:|--:|--:|--:|--:|--:|--:|--:|
| 256 | 128 | 582,914 | 555,765 | **-4.7%** | 81.5% → 82.5% | 3,315 | -0.21% |
| 384 | 128 | 544,962 | 527,363 | **-3.2%** | 93.2% → 93.4% | 4,633 | -0.10% |
| 768 | 128 | 31,887,029 | 30,863,581 | **-3.2%** | 59.3% → 61.5% | 625,221 | -0.10% |
| 1024 | 128 | 102,157,067 | 98,314,880 | **-3.8%** | 45.7% → 49.2% | 1,880,537 | -0.27% |
| 512 | 16 | 1,988,864 | 1,983,799 | **-0.3%** | 93.0% → 93.0% | 12,387 | +0.03% |
| 512 | 32 | 1,180,359 | 1,184,677 | **+0.4%** | 94.4% → 94.4% | 9,995 | +0.02% |
| 512 | 64 | 5,917,758 | 5,155,984 | **-12.9%** | 75.4% → 78.7% | 17,080 | -1.07% |
| 512 | 128 | 12,609,697 | 12,156,767 | **-3.6%** | 47.8% → 51.2% | 204,880 | -0.11% |
| 512 | 256 | 11,986,991 | 11,829,873 | **-1.3%** | 51.3% → 52.4% | 91,108 | +0.30% |
<!-- /table:lu_alone -->

- SBC pays where the working set just overflows: **b64 −12.9%** (its block needs all 8 ways of its sets)
- b = 128 at every n: −3.2 to −4.7%; nothing where the data fits (b16, b32: hit 93–94%)
- Run time −1.07% at b64, within ±0.3% elsewhere
- [table](../../../../scripts/logs/clean-runs/lu-sweep-single-core-20261008/results/table.md) ·
  [plan and incidents](../../../../scripts/logs/clean-runs/lu-sweep-single-core-20261008/PLAN.md)

### 2.2–2.3 LU b64 alone (3 windows) and LU b64 + streamcluster (7 windows), through `pair.sh`, 2026-10-05 / 10-07

- `pair.sh` with `busybox taskset`, both programs on one core: `./lu_ncb -p1 -n512 -b64` and
  `./streamcluster 2 3 16 43008 2048 200 none output.txt 1`
- `pair.sh` adds about 0.86 M accesses per run, so these absolute numbers differ from 2.1 (6.19 M vs 5.92 M for LU
  b64 alone). Compare within this group only
- Each window was the first after a fresh boot, selected from the sessions (015 F11); no checker existed yet

<!-- table:pairsh -->
| setting | SBC off: mean (n, spread) | SBC on: mean (n, spread) | change | migrations (on) |
|---|--:|--:|--:|--:|
| LU b64 alone | 6.19 M (n=2, sd 0.24%) | 5.31 M (n=1) | **-0.88 M (-14.2%)** | 15,001 |
| LU b64 + streamcluster | 12.15 M (n=3, sd 1.06%) | 11.91 M (n=4, sd 0.35%) | **-0.24 M (-2.0%)** | 260,299 |
<!-- /table:pairsh -->

- Pair: the SBC-off and SBC-on ranges do not overlap (off 12.01–12.25 M, on 11.85–11.94 M); Welch p = 0.073 (off n = 3)
- **Missing:** streamcluster alone on fresh boots in both arms (only one SBC-on window exists), so interference and
  recovered cannot be computed for this pair
- [015 REPORT](../coder/015-pair-verification-campaign/REPORT.md) F11

### 2.4 LU b32 + LU b128 on one core, two repeats (8 windows, 2026-10-08)

- `lu_ncb -n512 -b32` and `-b128` started together; baseline = the sweep's windows; run 2 repeats run 1 on new boots
- Recovered **22% and 12%** (mean 17%, band "weak, repeat"); the pair windows of the two runs differ by 0.14% (SBC
  off) and 0.93% (SBC on)
- [results](../../../../scripts/logs/clean-runs/lu-pair-single-core-20261008/RESULTS.md)

### 2.5 LU pair search: which LU pair hurts most (8 windows, 2026-10-08)

- Logic first: a cache model (`model/lusim.c`: the real loop nest through the real L1 and L2 geometry, two programs
  time sliced), checked on the sweep's alone windows (model ÷ board 0.83–0.97 at n = 512); predictions fixed before
  the windows
- 5 SBC-off pairs, then SBC on for the 3 with interference ≥ 1.5 M (b64 + b64, b32 + b32, b64 + b256)
- All five pre-registered checks passed: b64 + b64 hurts most (+8.50 M, ≥ 1.5× every other pair); b32 + b32 hurts
  most in % (+126%); b64 + b256 sits between b64 + b64 and b64 + b128; b128 + b128 is at most 1.7 M
- Recovered: **6%, 2%, −3%** (all "no real recovery"); numbers in section 3
- [plan](../../../../scripts/logs/clean-runs/lu-pair-search-20261008/PLAN.md) ·
  [results](../../../../scripts/logs/clean-runs/lu-pair-search-20261008/RESULTS.md)

### 2.6 LU + FFT grid (24 windows, 2026-10-09)

- SPLASH-2 `fft -p1 -m<M> -l4`: three arrays of 2^M complex doubles; m12 = 204 KB (0.8× L2), m16 = 3 MB (12×),
  m20 = 48 MB (192×)
- FFT runs K times back to back so FFT alone lasts about as long as LU alone (22.6 s): m12 ×84, m16 ×10, m20 ×1.
  K came from a one-boot calibration (not a measurement). At m12 most of FFT's traffic is process start/exit
- Grid: LU b32 / b64 / b128 × FFT m12 / m16 / m20, plus FFT alone at each size, both arms
- 24 of 24 windows valid (5 void attempts, section 7); FFT m20 pairs have overlap ~0.72 (the last ~20 s is FFT
  alone), m12 pairs ~0.81
- Expectations fixed before the windows:
  - E1 b64 has the largest interference in every column: **holds**
  - E2 b128 has the smallest: **fails** in M (b128 > b32 in every column), holds in % of baseline
  - E3 m16 and m20 recover under 10%: **fails** for b128 + m16 (23%) and b128 + m20 (22%)
  - E4 a cell at 10% or more, if any, is in m12: **fails**

FFT alone:

<!-- table:fft_alone -->
| FFT alone | runs back to back | memory accesses, SBC off | SBC on | change | L2 hit rate | window (s) |
|---|--:|--:|--:|--:|--:|--:|
| m12 | 84 | 2,570,836 | 2,597,369 | +1.0% | 83.0% | 16.3 |
| m16 | 10 | 3,639,817 | 3,642,339 | +0.1% | 82.1% | 22.3 |
| m20 | 1 | 6,377,127 | 6,365,354 | -0.2% | 85.8% | 42.1 |
<!-- /table:fft_alone -->

Interference, SBC off (pair − baseline, % of baseline):

<!-- table:fft_interference -->
| LU \ FFT | m12 ×84 | m16 ×10 | m20 |
|---|--:|--:|--:|
| b32 | +0.79 M (+21%) | +0.59 M (+12%) | +0.97 M (+13%) |
| b64 | +3.60 M (+42%) | +3.72 M (+39%) | +4.11 M (+33%) |
| b128 | +1.04 M (+7%) | +1.01 M (+6%) | +1.54 M (+8%) |
<!-- /table:fft_interference -->

Recovered by SBC (M, share of the interference):

<!-- table:fft_recovered -->
| LU \ FFT | m12 ×84 | m16 ×10 | m20 |
|---|--:|--:|--:|
| b32 | 0.03 M (4%) | -0.04 M (-7%) | -0.04 M (-4%) |
| b64 | 0.32 M (9%) | 0.18 M (5%) | 0.04 M (1%) |
| b128 | 0.06 M (5%) | 0.24 M (23%) | 0.34 M (22%) |
<!-- /table:fft_recovered -->

- [plan](../../../../scripts/logs/clean-runs/lu-fft-search-20261009/PLAN.md) ·
  [results](../../../../scripts/logs/clean-runs/lu-fft-search-20261009/RESULTS.md) ·
  [calibration](../../../../scripts/logs/clean-runs/lu-fft-search-20261009/calibration/CALIBRATION.md)

### 2.7 bodytrack, one parameter at a time (36 windows, 2026-10-10) — task 016, added 2026-10-12

Same image, same fresh-boot method, one window per arm, no repeats. Not in `windows.csv`, so not in the generated
tables below. Full results, counter decomposition and log paths: [016 REPORT](../coder/016-bodytrack-sbc-sweep/REPORT.md).

- The only knob with a clear effect is the particle count: **+4.1% / +3.7% at 1600 / 4000**, where each migration
  costs 2.5–3.4 primary hits and returns 0.6–1.1 secondary hits
- Small savings (−1.2 to −1.9%) only around 400 particles; small losses (+1.1 to +2.4%) at the low-hit corner
- A low OFF hit rate (79–83%) did not predict a saving

---

## 3. Across all experiments

Memory accesses, SBC off and on, with the reduction (LU n = 512; negative = fewer accesses with SBC on):

<!-- table:all_pairs_mem -->
| partner | LU + partner | pair, SBC off (M) | pair, SBC on (M) | pair, on vs off (M, %) | two alone (sum), SBC off (M) | two alone (sum), SBC on (M) | two alone, on vs off (M, %) |
|---|---|--:|--:|--:|--:|--:|--:|
| LU | b64 + b64 | 20.33 | 18.33 | **-2.00 M (-9.9%)** | 11.84 | 10.31 | -1.52 M (-12.9%) |
| LU | b32 + b32 | 5.35 | 5.29 | **-0.05 M (-1.0%)** | 2.36 | 2.37 | +0.01 M (+0.4%) |
| LU | b64 + b256 | 21.11 | 20.29 | **-0.82 M (-3.9%)** | 17.90 | 16.99 | -0.92 M (-5.1%) |
| LU | b64 + b128 | 20.87 | not run | not run | 18.53 | 17.31 | -1.21 M (-6.6%) |
| LU | b128 + b128 | 26.72 | not run | not run | 25.22 | 24.31 | -0.91 M (-3.6%) |
| LU | b32 + b128, run 1 | 14.90 | 14.21 | **-0.69 M (-4.7%)** | 13.79 | 13.34 | -0.45 M (-3.3%) |
| LU | b32 + b128, run 2 | 14.92 | 14.34 | **-0.58 M (-3.9%)** | 13.79 | 13.34 | -0.45 M (-3.3%) |
| FFT | b32 + m12 ×84 | 4.54 | 4.54 | **+0.00 M (+0.0%)** | 3.75 | 3.78 | +0.03 M (+0.8%) |
| FFT | b32 + m16 ×10 | 5.41 | 5.46 | **+0.05 M (+0.9%)** | 4.82 | 4.83 | +0.01 M (+0.1%) |
| FFT | b32 + m20 | 8.53 | 8.56 | **+0.03 M (+0.4%)** | 7.56 | 7.55 | -0.01 M (-0.1%) |
| FFT | b64 + m12 ×84 | 12.08 | 11.03 | **-1.06 M (-8.8%)** | 8.49 | 7.75 | -0.74 M (-8.7%) |
| FFT | b64 + m16 ×10 | 13.27 | 12.34 | **-0.94 M (-7.1%)** | 9.56 | 8.80 | -0.76 M (-7.9%) |
| FFT | b64 + m20 | 16.41 | 15.59 | **-0.82 M (-5.0%)** | 12.29 | 11.52 | -0.77 M (-6.3%) |
| FFT | b128 + m12 ×84 | 16.22 | 15.74 | **-0.48 M (-3.0%)** | 15.18 | 14.75 | -0.43 M (-2.8%) |
| FFT | b128 + m16 ×10 | 17.26 | 16.57 | **-0.69 M (-4.0%)** | 16.25 | 15.80 | -0.45 M (-2.8%) |
| FFT | b128 + m20 | 20.53 | 19.73 | **-0.80 M (-3.9%)** | 18.99 | 18.52 | -0.46 M (-2.4%) |
| streamcluster | b64 (through `pair.sh`) | 12.15 | 11.91 | **-0.24 M (-2.0%)** | 6.19* | 5.31* | -0.88 M (-14.2%)* |
<!-- /table:all_pairs_mem -->

- "Two alone (sum)" = the two programs' alone windows added (self-pairs use the one window twice); the pair's
  interference is the pair minus this
- FFT rows: the alone windows are the sweep's LU window plus the FFT-alone window of the grid
- \* Streamcluster alone was not measured on fresh boots in both arms: its row shows LU b64 alone only

Interference and what SBC recovers (SBC's change = on − off):

<!-- table:all_pairs -->
| partner | LU + partner | interference, SBC off | SBC's change on the pair | SBC's change on the two alone (sum) | recovered | share |
|---|---|--:|--:|--:|--:|--:|
| LU | b64 + b64 | +8.50 M (+72%) | -2.00 M | -1.52 M | 0.48 M | **6%** |
| LU | b32 + b32 | +2.99 M (+126%) | -0.05 M | +0.01 M | 0.06 M | **2%** |
| LU | b64 + b256 | +3.21 M (+18%) | -0.82 M | -0.92 M | -0.10 M | **-3%** |
| LU | b64 + b128 | +2.34 M (+13%) | not run | -1.21 M | - | - |
| LU | b128 + b128 | +1.50 M (+6%) | not run | -0.91 M | - | - |
| LU | b32 + b128, run 1 | +1.11 M (+8%) | -0.69 M | -0.45 M | 0.24 M | **22%** |
| LU | b32 + b128, run 2 | +1.13 M (+8%) | -0.58 M | -0.45 M | 0.13 M | **12%** |
| FFT | b32 + m12 ×84 | +0.79 M (+21%) | +0.00 M | +0.03 M | 0.03 M | **4%** |
| FFT | b32 + m16 ×10 | +0.59 M (+12%) | +0.05 M | +0.01 M | -0.04 M | **-7%** |
| FFT | b32 + m20 | +0.97 M (+13%) | +0.03 M | -0.01 M | -0.04 M | **-4%** |
| FFT | b64 + m12 ×84 | +3.60 M (+42%) | -1.06 M | -0.74 M | 0.32 M | **9%** |
| FFT | b64 + m16 ×10 | +3.72 M (+39%) | -0.94 M | -0.76 M | 0.18 M | **5%** |
| FFT | b64 + m20 | +4.11 M (+33%) | -0.82 M | -0.77 M | 0.04 M | **1%** |
| FFT | b128 + m12 ×84 | +1.04 M (+7%) | -0.48 M | -0.43 M | 0.06 M | **5%** |
| FFT | b128 + m16 ×10 | +1.01 M (+6%) | -0.69 M | -0.45 M | 0.24 M | **23%** |
| FFT | b128 + m20 | +1.54 M (+8%) | -0.80 M | -0.46 M | 0.34 M | **22%** |
| streamcluster | b64 (through `pair.sh`) | not measurable* | -0.24 M | LU alone -0.88 M; streamcluster alone not measured | - | - |
<!-- /table:all_pairs -->

\* Streamcluster alone was not measured on fresh boots in both arms.

<!-- table:share_count -->
10 of 14 recovered values are below 10%. At 10% or more: LU b32 + b128 run 1 (22%), LU b32 + b128 run 2 (12%), LU b128 + FFT m16 ×10 (23%), LU b128 + FFT m20 (22%).
<!-- /table:share_count -->

**O1. Interference depends on the victim's fit, not on the partner**
- LU b64 loses the most with every partner: +2.3 M (with b128) to +8.5 M (with itself)
- LU b128 (already misses alone, 48% hit) loses 1.0–1.5 M; LU b32 (94% hit) loses 0.6–1.1 M
- Exception: b32 + b32, +3.0 M (+126%): two identical programs on the same sets at the same time

**O2. The partner's data size barely matters**
- LU b64 with FFT m12 / m16 / m20: +3.60 / +3.72 / +4.11 M; b128: +1.04 / +1.01 / +1.54 M; b32: +0.79 / +0.59 / +0.97 M
- 240× more FFT data changes the interference by at most +50%: anything that fills the L2 between two time slices
  evicts the victim's block, and more data does not evict it twice

**O3. SBC's gain on a pair ≈ the sum of the gains alone**
- Most recovered values are 1–9% of the interference, or negative; with LU b64 or b32 every value is below 10%
- **The exception is LU b128:** b32 + b128 22% and 12%, b128 + FFT m16 23%, b128 + FFT m20 22%; only b128 + FFT m12
  ×84 (mostly process start/exit traffic) gave 5%. Each value is one window per arm and in the "weak, repeat" band,
  and the amounts are small (0.13–0.34 M), so this is a lead, not a result
- LU b128 is also the one setting where SBC pays alone although the block does not fit (−3.6%, 48% hit): it already
  misses alone, so a partner adds misses to sets SBC is already working on. A guess, not tested
- SBC works hardest there: 390–500 K migrations per b128 + FFT window, saving 1–2 accesses each (O6)

**O4. SBC's gain alone is concentrated**
- LU b64 −12.9% (sweep), −14.2% (`pair.sh`); LU b128 −3.6%; b256 −1.3%; b16 / b32 ≈ 0
- FFT alone: −0.2% to +1.0% at every size, although SBC migrates 3–6 K lines per window

**O5. Whether the victim's gain survives depends on the partner**
- LU and FFT partners: the pair keeps LU b64's gain alone (−0.76 M) or a bit more (pair −0.82 to −2.00 M)
- streamcluster: the pair keeps about 27% of it (−0.24 M of −0.88 M, `pair.sh`)

**O6. With a partner, SBC migrates more and gains less per migration**

<!-- table:migrations -->
| setting | migrations (SBC on) | memory accesses saved by SBC | saved per migration |
|---|--:|--:|--:|
| LU b64 alone (sweep) | 17,080 | 0.76 M | 44.6 |
| LU b64 alone (`pair.sh`) | 15,001 | 0.88 M | 58.8 |
| LU pair: b64 + b64 | 200,269 | 2.00 M | 10.0 |
| LU pair: b64 + b256 | 105,665 | 0.82 M | 7.8 |
| FFT pair: b64 + m12 ×84 | 180,704 | 1.06 M | 5.9 |
| FFT pair: b64 + m16 ×10 | 113,034 | 0.94 M | 8.3 |
| FFT pair: b64 + m20 | 141,827 | 0.82 M | 5.8 |
| streamcluster pair: b64 (`pair.sh`) | 260,299 | 0.24 M | 0.9 |
| LU b128 alone (sweep) | 204,880 | 0.45 M | 2.2 |
| LU pair: b32 + b128, run 1 | 215,015 | 0.69 M | 3.2 |
| LU pair: b32 + b128, run 2 | 165,616 | 0.58 M | 3.5 |
| FFT pair: b128 + m12 ×84 | 495,360 | 0.48 M | 1.0 |
| FFT pair: b128 + m16 ×10 | 392,568 | 0.69 M | 1.8 |
| FFT pair: b128 + m20 | 414,069 | 0.80 M | 1.9 |
<!-- /table:migrations -->

**O7. Run time barely moves** (section 1): the cut in memory accesses is what the board can measure

**O8. Fresh boots were essential**
- LU + streamcluster: the old one-boot headline −12.6% → fresh-vs-fresh −2.0%; about nine-tenths was board age
- The old one-boot LU sweep was off by +4 to +61% at the small points (section 6)

---

## 4. What it means for SBC

- SBC helps a program that sits right at the edge of fitting the cache (LU b64), alone or next to a partner
- It does not win back the interference a partner causes to that program. This fits the rule of
  [sbc-findings-2026-09-25.md](sbc-findings-2026-09-25.md) (SBC pays while a set's overflow fits the empty ways of
  its partner set): a co-running program fills those empty ways. That link is an interpretation, not tested
- For two programs: at best the pair keeps the victim's gain alone (LU and FFT partners); at worst it loses most of
  it (streamcluster)
- The one lead against this: pairs where LU b128 suffers recover 12–23% (section 3, O3). It needs a repeat before
  it changes any of the above

## 5. Open questions (next experiments, not started)

| question | experiment | cost |
|---|---|---|
| Is the LU b128 result (4 of 5 pairs at 12–23%) real? | repeat LU b128 + FFT m16 and b128 + FFT m20, off and on | 4 windows, ~2 h |
| Why does streamcluster erase LU's gain when FFT does not? | streamcluster alone, off and on, fresh boots (makes 2.3 comparable); then the per-set profiler (`SBC_SetSel` / `SBC_SetSat`) on both pairs | 2 windows + profiler |
| How large is LU b64's page-colour spread on the board? | LU b64 alone, off and on, repeated | 2–4 windows |
| What do fewer accesses save on a fast core? | the labelled DRAM-latency model of the sweep, extended to the pairs | no board time |

## 6. Older results that are not apple to apple

| result | why it is out | what replaces it |
|---|---|---|
| [lu-single-app-2026-10-02.md](lu-single-app-2026-10-02.md): 72 windows, one boot, all off then all on | the on half ran on an older board, after 27 earlier on windows | 2.1 (b128: −4.01% old vs −3.6% fresh; small points off by +4 to +61%) |
| [lu-streamcluster-pair-2026-10-05.md](lu-streamcluster-pair-2026-10-05.md): pair −12.5% | a fresh SBC-on window against SBC-off windows on an aged board | 2.3: −2.0% |
| LU n=768 pair, −3.50% / −3.44% (2026-10-03/04) | dual core; off was window 1 and on window 2 on the same boot | not re-measured |

## 7. Void windows (kept under each folder's `failed/`)

| experiment | void attempts | cause |
|---|--:|---|
| 2.1 LU alone sweep | 4 | serial file transfer broke off; board silent after programming (twice); one false alarm of checker v1 (fixed in v2) |
| 2.4, 2.5 LU pairs | 0 | |
| 2.6 LU + FFT | 5 | harness pre-check `test -x for` skipped the FFT ×K commands (2; fixed); one boot lost when the run order was changed; board silent after programming; serial file transfer broke off |
| 2.2, 2.3 `pair.sh` | n/a | no checker then; the first window after each boot was selected (015 F11) |

- No void attempt was judged on its measured values; every void had no window or a broken boot

## 8. Data and tools

| experiment | folder in `chipyard/scripts/logs/clean-runs/` | tools |
|---|---|---|
| 2.1 | `lu-sweep-single-core-20261008/` | `tools/launch_lu_sweep.sh`, `check_lu_stage.py` |
| 2.2 | `solo-lu-b64/` (window #1 of each session only) | `scripts/ssbc_scripts/run_pair_session.exp`, `pair.sh` |
| 2.3 | `pair-lu-streamcluster-b64/` | same |
| 2.4 | `lu-pair-single-core-20261008/` (`r2/` = run 2) | `tools/run_parsec_session.exp -plan lupair`, `check_lupair_stage.py` |
| 2.5 | `lu-pair-search-20261008/` | `tools/launch_pairs.sh`, `make_results.py`, `model/lusim.c` |
| 2.6 | `lu-fft-search-20261009/` | `tools/launch_lufft.sh`, `check_lufft_stage.py`, `make_results.py` |

- Every folder above has a `RESULTS.md` with the same off/on tables (`clean-runs/tools/results_common.py`; index in `clean-runs/README.md`)
- Copy in this folder: `data/2026-10-09-fresh-boot/windows.csv` (one row per valid window, all counters, source path)
- Rebuild: `build_windows_csv.py` (reads `clean-runs/`), then `make_tables.py` (rewrites every table in this report;
  it refuses to write if any text outside the table markers would change)
- Nothing here is committed yet
