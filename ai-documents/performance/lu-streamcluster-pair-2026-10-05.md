# Performance Evaluation Report: Two Applications Sharing One L2
## SPLASH-2 LU (`lu_ncb`) + PARSEC `streamcluster`, 256 KB 8-Way L2 (SBC + PLRU), VCU118

**Date:** 2026-10-05
**Platform:** AMD Xilinx VCU118, Big Rocket @ 50 MHz, 2 GB DDR4
**L2:** 256 KB, 8 ways, 512 sets, 64 B blocks · **L1:** 8 KB · **Policy:** SBC + PLRU
**Driver:** `scripts/ssbc_scripts/run_pair_session.exp` (new) + `software/parsec-benchmark/staged/pair.sh` (new)
**Archived data:** `ai-documents/performance/data/2026-10-05-lu-pair/`, `.../data/2026-10-05-partner-calibration/`
**Companion report:** `lu-single-app-2026-10-02.md` (the single-application baseline this is measured against)

> ⚠️ **Correction, 2026-10-07/08. Do not quote the headline below.** The SBC-on window ran on a freshly reprogrammed board; the SBC-off windows ran on an aged one, and SBC-off traffic rises with board age.
> Fresh-vs-fresh (SBC off n=3, SBC on n=4, each the first run after a boot): **−1.99%** (p = 0.073); about nine-tenths of the −12.5% was board age (015 F11).
> Later, with LU+LU pairs on one core: SBC recovers 6%, 2% and −3% of the interference in the three large pairs (015 F15). The text below is left as written.
> [015 REPORT](../coder/015-pair-verification-campaign/REPORT.md)

---

## 1. Headline Result

**With two applications sharing one L2, SBC cut memory traffic by 12.5% — about three times the
best it ever achieved on a single application.**

| | memAcc | hit rate |
|---|---:|---:|
| pair, migrate=OFF (mean of 3) | 13,650,286 ± 773,063 (±5.7%) | 72.80% |
| pair, migrate=ON (1 run, fresh reprogram) | **11,944,500** | **76.30%** |
| **Δ** | **−12.50%** | +3.50 pp |

- Same-work check (`accessA`): **−0.03%** — the two halves did identical work. This is a traffic
  reduction, not a measurement artefact.
- −12.50% sits well outside the ±5.7% OFF spread, so the effect is real.
- Configuration: `lu_ncb -p1 -n512 -b64` + `streamcluster 2 3 16 43008 2048 200 none output.txt 1`,
  **both pinned to cpu0** on the single-core bitstream, overlap 0.967 of the window.

Put beside the single-application sweep:

| setting | baseline hit | SBC effect on memAcc |
|---|---:|---:|
| LU b=32 alone | 94% | **+60.8%** (much worse) |
| LU b=128 alone, n=768 dual-core, cold | 67% | −3.77% (mean of 3 boots) |
| **LU b=64 + streamcluster, shared L2** | **72.8%** | **−12.50%** |

A co-runner creates exactly the set-pressure imbalance SBC exists to exploit — one application's
hot sets beside another's cold ones — and SBC recovers roughly a sixth of the penalty that sharing
imposes.

---

## 2. Why The Counters Force This Experiment Design

Every L2 counter is a single cache-wide register at `0x2010000`, and nothing in
`PerfCounters.scala` carries a source or client id. **There is no per-application attribution in
hardware.** So the pair must be measured as one unit, and the only way to say anything about
interference is to measure each application alone as well:

```
interference = memAcc(pair) − memAcc(A alone) − memAcc(B alone)      at a fixed migrate setting
repaired     = [interference(off) − interference(on)] / interference(off)
```

`run_pair_session.exp` therefore always emits the two solo points alongside the pair, and runs each
application solo on the *same* cpu it occupies in the pair, so the only difference between a solo
row and the pair row is the presence of the other application.

---

## 3. Co-Running Penalty (migrate=off), single-core, 3 repeats

Source: `data/2026-10-05-lu-pair/pair_runs_20261005-121456.csv`

| row | memAcc mean | sd | ±% | hit% | sec |
|---|---:|---:|---:|---:|---:|
| solo-lu (b=64) | 6,446,901 | 317,951 | 4.9% | 74.51 | 23.7 |
| solo-streamcluster | 1,390,328 | 35,329 | 2.5% | 92.12 | 22.0 |
| **pair** | **13,650,286** | 773,063 | 5.7% | **72.80** | 50.6 |

**Interference: +5,813,058 — +74.2%.** Time-sharing one core's L2 costs the two applications
three quarters of their memory traffic again. Each gets the whole cache for a few milliseconds,
then the other wipes it.

### 3.1 SBC mechanism counters on the ON run

```
migrations 274,316   secHits 835,687   -> 3.05 secondary hits per migration
attempted  586,702   aborted 312,412   -> 53.2% abort rate
parked 6  (at end)
dstAbortDirty 0   dstAbortHeld 257,077   dstAbortBoth 55,309
```

Every abort is a destination-probe refusal because a **client still holds the line**
(`dstAbortHeld` + `dstAbortBoth`), never because the destination was dirty. With both applications
resident, candidate destination sets are far more likely to be live in some L1.

---

## 4. Choosing The Operating Point — Interference Depends Strongly On Sizing

Three pair configurations were measured OFF before committing any ON window. This is the most
transferable lesson in the report.

| configuration | footprints | hit alone | hit together | **interference** |
|---|---|---:|---:|---:|
| dual-core, LU b=128 + sc d16/c32768 | 2 MB + 3 MB | 51.5 / 49.6% | 45.57% | **+2.40%** |
| dual-core, LU b=32 + sc d16/c2048 | 8 KB blk + 236 KB | 91.8 / 86.3% | 85.24% | +23.96% |
| dual-core, LU b=64 + sc d16/c2048 | 32 KB blk + 236 KB | 68.7 / 89.8% | 63.61% | +40.5% |
| **single-core, LU b=64 + sc d16/c2048** | as above, time-shared | 74.5 / 92.1% | **72.80%** | **+74.2%** |

**Interference is largest when each application nearly fits alone and the two together do not.**
The first configuration generated almost no interference *because both applications were already
thrashing on their own* — adding a competitor to a cache that is already useless to you changes
little. Sizing both to roughly cache capacity produced a 10x larger effect.

### 4.1 Two superseded figures

- An earlier single-shot measurement of the dual-core b=64 pair gave **+52.2%**. With three
  repeats the mean is **+40.5%**. The single shot rode on a `solo-streamcluster` baseline of
  1,877,507 against a 3-run mean of 3,277,686 — a low outlier.
- `run_pair_session.exp`'s own report printed **+43.2%** for the same data, because `find_row`
  returned only the first repeat. Fixed 2026-10-05: it now averages all OK repeats and prints the
  repeat count.

---

## 5. Measurement Hazards Found And Fixed

### 5.1 `memAcc` run-to-run variance is large at high hit rates

`accessA` is stable to ~0.2–0.8%, but `memAcc` is the small residual at a high hit rate and swings
badly:

| config | hit | memAcc spread |
|---|---:|---|
| LU b=32 | 93% | ±9% within a session |
| streamcluster d16/c2048 | 86–92% | ±8.9% within a session, **1.88 M → 3.28 M (74%) across sessions** |
| LU b=64 | 69–74% | ±4.9% |
| pair b=64 | 73% | ±5.7% |

**Consequence:** single-shot deltas at hit rates above ~85% are not trustworthy. The b=64 operating
point was chosen partly because its signal is 4x larger, shrinking relative noise. Between-session
variance exceeds within-session variance, which matters because an ON window is necessarily a
different session (it requires a reprogram) — so within-session repeats understate the error bar
on a cross-phase comparison.

### 5.2 The harness has its own footprint, now measured not assumed

`pair.sh` forks busybox several times per run (`rm`, `cat` x6, `tail`, and `taskset` itself), and
busybox is a 1.5 MB static binary whose pages fault in on each exec. Measured with a `nop`
workload (`-pair <X>,nop`, running `/bin/busybox true`):

```
nop solo                = fixed + 1 branch = 1,083,972 accesses / 284,670 memAcc
pair-lu+nop − solo-lu   = 1 branch         =   246,327 accesses / 299,432 memAcc
=> pair.sh fixed cost                      =   837,645 accesses / ~0 memAcc
```

- **accessA:** the sum of two solos pays `fixed` twice, the pair once, so the sum is inflated by
  ~838 K. `accessA(pair) == accessA(A) + accessA(B)` is therefore **not** a valid same-work check
  for a pair; it read −7.31% when first tried, which was the check being wrong, not the data.
  (`accessA` is also L1 misses, and under an inclusive L2 a co-runner changes L1 behaviour through
  back-invalidation, so it was never going to be exactly additive.)
- **memAcc:** `fixed` costs essentially nothing in memory traffic, and both the sum and the pair
  carry two branches. **No correction is needed for memAcc**, which is what the headline uses.
- `pair-lu+nop` also serves as the harness smoke test: overlap 0.007, pair ≈ solo-lu, interference
  +0.75% — i.e. pairing with a do-nothing partner correctly yields nothing.

### 5.3 Overlap must be measured, not assumed

The counter window brackets the whole of `pair.sh`, so if the partner exits early the tail of the
window is one application running alone and the "pair" is partly fiction. `pair.sh` timestamps each
workload separately from `/proc/uptime` (10 ms resolution; `date` gives only 1 s, too coarse for a
24 s run) and prints a `[PAIR-TIME]` line. Each workload records **its own** start and end, because
`wait` returns in the order its PIDs are listed, not the order they finish — timing around `wait`
reports the wrong end time whenever the partner finishes first.

Every pair row in this report has overlap ≥ 0.916. The harness warns below 0.5.

---

## 6. Partner Selection: `canneal` Rejected, `streamcluster` Chosen

Source: `data/2026-10-05-partner-calibration/`

### 6.1 `canneal` has never completed a run on this board

Every `canneal` run exits non-zero and never prints "Final routing is" — including the two runs of
2026-10-01 whose counters were previously quoted as valid results. `sbc_read` reports a
signal-killed child as exit 1 (`WIFEXITED(st) ? WEXITSTATUS(st) : 1`), and the riscv kernel does not
log `unhandled signal` for `raise`/`abort` (only for fault-derived signals — `bodytrack`'s SIGSEGV
*is* logged in the same transcripts), so the failure is silent. Cause not yet identified; the
program's own two `exit(1)` calls are argument checks that run before the observed output.

### 6.2 Even working, `canneal` would be a weak co-runner

Loader cost isolated by running `NSWAPS=0` (zero moves), then differenced out:

| phase | accesses | hit rate | **memAcc per L2 access** |
|---|---:|---:|---:|
| LU b=128 (the target) | 13,388,861 | 49.20% | **0.949** |
| canneal 6k — annealing only | 18,849,028 | 85.83% | 0.155 |
| canneal 6k — loader only | 8,265,063 | 94.47% | 0.075 |
| canneal 30k — loader only | 43,028,632 | 92.74% | 0.092 |

**A large footprint is not a high miss rate.** canneal's netlist is ~1.6 MB against a 256 KB L2,
yet its annealing phase still hits 85.8%, because its per-move working set is small and heavily
reused (the `_locations` array is only 57 KB at 6k). Paired with LU it would have added ~13% to the
memory traffic. The 30k netlist is worse: its loader alone costs 58 s and 43 M accesses.

Also note `canneal` is built at **-O0** — its own Makefile appends `-O0` after the suite's `-O3`
and gcc honours the last `-O`. This is upstream PARSEC 3.0 behaviour, not a local edit.

### 6.3 `streamcluster` is a genuine peer

Footprint is `chunksize x dim x 4 B` of coordinates plus `chunksize x 32 B` of Point structs, and
**no input file is read when `n > 0`** — so unlike canneal there is no loader phase to dilute the
window. `streamCluster` loops over `n/chunksize` chunks through the *same* buffer, so **runtime and
footprint are independent knobs**. Compute is `ITER*kmax*log(kmax)` `pgain` calls per `pFL` round
with `pgain` costing O(n·dim), so a small `kmax` against a large `chunksize*dim` makes `pgain`
stream the whole array with almost no reuse.

| config | coords | sec | hit% | misses/s | vs LU |
|---|---|---:|---:|---:|---:|
| lu-ref (b=128) | — | 24.3 | 49.68 | 280,907 | 1.00x |
| sc d64/c2048 | 512 K | 4.3 | 50.89 | 285,991 | 1.02x |
| sc d64/c8192 | 2 M | 16.6 | 47.21 | 304,351 | 1.08x |
| sc d64/c32768 | 8 M | 69.4 | 45.31 | 312,910 | 1.11x |
| sc d128/c16384 | 8 M | 61.0 | 45.79 | 299,569 | 1.07x |
| sc d16/c32768 | 2 M | 22.4 | 47.87 | 336,951 | 1.20x |

**Dimension is irrelevant; total footprint is what counts.** The two 2 MB points agree at
mem/acc 0.543 vs 0.542 and the two 8 MB points at 0.554 vs 0.562, despite `dim` varying 8x.

**The right acceptance metric is demand misses per second**, not `memAcc/accessA`. The latter
conflates "competes for capacity" with "writes a lot": LU is a factorization that dirties its
working set so each miss costs a fill *and* a writeback (0.949), while streamcluster streams reads
(0.54). For a co-runner only the first matters. On misses/s streamcluster runs at 1.0–1.2x LU;
canneal's annealing phase manages 0.21x.

Small-footprint candidates (for the b=64 experiment, where both apps must fit alone):

| config | est. footprint | sec | hit% |
|---|---|---:|---:|
| d16/c1024/n32768 | 140 K | 14.7 | 96.18 |
| **d16/c2048/n32768** | **236 K** | 17.3 | **88.02** |
| d32/c2048/n32768 | 401 K | 31.6 | 59.41 |
| d16/c4096/n32768 | 428 K | 19.3 | 57.04 |

Hit rate crosses over between 236 K and 401 K — i.e. right at the 256 KB cache size, confirming the
footprint formula. `n` was then stretched to 43,008 (21 chunks) so the run lasts ~22 s, matching
LU's ~23 s for near-full overlap. Watch `centersize`: `copycenters` appends up to `kmax` centers
per chunk and the program `exit(1)`s with "no more space for centers" once they exceed it.

`nproc` is pinned to 1 throughout: `parsec_barrier.cpp:34` is a "not implemented" stub and only the
single-threaded path has ever completed on this board.

---

## 7. Why Single-Core: The Dual-Core migrate=on Deadlock

The intended experiment was two cores, one application each. **The dual-core bitstream deadlocks
under `migrate=on`** — see `lu-single-app-2026-10-02.md` §5. The pair ON window hung on its first
attempt; the board stopped echoing the serial console entirely.

Transcript: `data/2026-10-05-lu-pair/pair_session_20261005-110920.log` (status `TIMEOUT`).
The reprogram and boot were fine — board uptime read 34 s, confirming a genuine hardware reset —
and both binaries verified present before the run.

**What moving to one core changes:**
- Both applications still share the same 256 KB 8-way L2, so **capacity interference survives
  intact** — that is the quantity being measured.
- They no longer run simultaneously. They time-slice at scheduler granularity, so this is a
  *multiprogrammed* workload rather than concurrent sharing. Legitimate and well-studied, but not
  the same thing.
- Interference is *higher* time-shared (+74.2%) than concurrent (+40.5%).
- Safe because LU's PARMACS barrier blocks on a condition variable
  (`parmacs.pthreads.c.m4:4-22`) rather than spinning, so oversubscribing one core does not livelock.
- `busybox taskset` is used, not bare `taskset`: the applet exists in the board's busybox
  (v1.36.0, 281 applets) but neither firemarshal tree creates a symlink for it. Verified on the
  board: `busybox taskset -c 0 /bin/true` returns 0.

---

## 8. Caveats On The Headline

1. **One ON run.** The OFF side has three repeats; the ON side has one, because each ON window
   needs its own reprogram. −12.50% against a ±5.7% baseline spread is convincing but needs
   replication.
2. **No per-application attribution.** It is not yet known whether SBC helped LU, streamcluster, or
   both. The two remaining ON windows (solo-lu, solo-streamcluster) are what separate "SBC repairs
   interference" from "SBC simply helps a 73%-hit workload".
3. **Time-shared, not concurrent.** The concurrent version of this result is blocked on the
   dual-core deadlock.
4. **`repaired` is not yet computable** — it needs the solo ON arms from (2).

---

## 9. Reproducing

```bash
cd scripts/ssbc_scripts
SBIT=../../fpga/bitstream_storage/FPGASingleRocketVCU118L18K256K8WL2ConfigSBCPLRU-256KB-8way-candidate013-2026-10-01-2026-10-01.bit
SC="-sc-dim 16 -sc-chunk 2048 -sc-n 43008 -sc-cluster 200"

# inspect any plan without touching the board
./run_pair_session.exp -pair lu,streamcluster -lu-b 64 -samecore $SC -list

# harness baseline
./run_pair_session.exp -pair lu,nop -phases off -lu-b 64 -push cache

# OFF baseline, 3 repeats
./run_pair_session.exp -pair lu,streamcluster -phases off -lu-b 64 -samecore $SC \
    -prog "$SBIT" -policy plru -push cache -tmo 600 -repeat 3

# ONE ON window, one point, on a reprogram-fresh boot
./run_pair_session.exp -pair lu,streamcluster -phases on -lu-b 64 -samecore $SC \
    -bench pair-lu+streamcluster -prog "$SBIT" -policy plru -push cache -tmo 600 -repeat 1
```

Notes on the driver:
- `-phases` defaults to `off` only. An ON phase must be typed, and prints a warning banner.
- `-prog` is opt-in; the script attaches to a booted board by default.
- `-push cache` restores staged files from `/mnt` (the SD card survives a reprogram) with an md5
  check before the copy and another after, cutting ~28 min of zmodem per boot to seconds.
- `-list` prints the plan and exits before any board access.
- A rival-session guard refuses to start if another `run_*_session.exp` owns the board, because
  step 2 pkills every picocom on a ttyUSB.
