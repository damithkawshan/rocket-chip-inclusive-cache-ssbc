# Performance Evaluation Report: SPLASH-2 LU (`lu_ncb`), Single Application
## 256 KB 8-Way L2 (SBC + PLRU) on VCU118 FPGA — single-core and dual-core

> ⚠️ **Correction, 2026-10-08.** This sweep ran 36 SBC-off windows and then 36 SBC-on windows on one boot, so board age and carried-over parked lines are mixed into every OFF-vs-ON number.
> Re-run fresh-vs-fresh (reprogram and boot per window, 18 boots, one run per arm): SBC cuts memory accesses **3.2–4.7% at every n (b=128)**, **12.9% at n=512 b=64**, 1.3% at b=256, and nothing (±0.4%) at b=16 and b=32, where the hit rate is already 93–94%.
> The "+4% to +61% markedly worse" row below is an artefact of the method. Read [015 REPORT, LU single-core sweep](../coder/015-pair-verification-campaign/REPORT.md). The text below is left as written.

**Date of runs:** 2026-10-01 → 2026-10-04 (written up 2026-10-05)
**Platform:** AMD Xilinx VCU118, Big Rocket @ 50 MHz, 2 GB DDR4
**L2:** 256 KB, 8 ways, 512 sets, 64 B blocks
**L1:** 8 KB (the `L18K` configs)
**Policy:** SBC + PLRU, set explicitly on every run (`L2_Replacement` resets to `random` on every reprogram)

**Bitstreams**
- single-core: `fpga/bitstream_storage/FPGASingleRocketVCU118L18K256K8WL2ConfigSBCPLRU-256KB-8way-candidate013-2026-10-01-2026-10-01.bit`
- dual-core: `fpga/bitstream_storage/FPGADualRocketVCU118L18K256K8WL2ConfigSBCPLRU-dual-256KB-8way-2026-10-02.bit`

**Driver:** `scripts/ssbc_scripts/run_parsec_session.exp -plan lu`
**Archived data:** `ai-documents/performance/data/2026-10-02-lu-single-app/`, `.../data/2026-10-03_04-lu-n768-boots/`

---

## 1. Executive Summary

LU was swept one knob at a time around the centre point that first showed a clear SBC effect
(`./lu_ncb -p2 -n512 -b128`, `policy=plru`). 72 runs, 3 repeats per point per phase.

**The single finding that matters: SBC's effect on LU is governed by the baseline hit rate.**

| baseline hit rate | SBC effect on memory traffic |
|---|---|
| ≈ 94% (working set fits) | **+4% to +61% — markedly worse** |
| 70–82% | −0.6% to −7% |
| 48–67% (working set overflows) | **−3.4% to −4.3% — consistently better** |

SBC never moved L2 cycles by more than ~1% in either direction on this geometry (one apparent
exception is a contaminated row, noted below). The gain is in DRAM traffic, not in time — expected,
since two cores at ~300 K misses/s is ~38 MB/s, far below DDR4 bandwidth, so the memory system is
not the bottleneck at 50 MHz.

---

## 2. Why the hit rate tracks the block size: the set geometry

This is the mechanism behind the whole table, and it is exact rather than hand-waved.

The L2 set index is `PA[14:6]`. Page offset bits 6–11 select 64 sets within a 4 KB page; bits
12–14 come from the physical frame, giving 8 page groups. For `n=512`, a matrix row is
`512 x 8 B = 4096 B` — **exactly one page**. So a `b`-column block occupies `b/4` of the 64
within-page set positions, and across 8 page groups it lands on `~16 x (b/4)` of the 512 sets.

| b | block | lines/set vs 8 ways | predicted | measured hit (p1, dual-core) |
|---|---|---|---|---|
| 16 | 2 KB | 2 | fits, 6 ways spare | 92.64% |
| 32 | 8 KB | 4 | fits, 4 ways spare | 93.64% |
| 64 | 32 KB | **8** | **exactly full** | 70.40% |
| 128 | 128 KB | 16 | 2x oversubscribed | 49.46% |
| 256 | 512 KB | 32 | 4x oversubscribed | 52.73% (p2) |

The b=64 knife edge is the useful operating point: no spare ways, but not yet thrashing.

---

## 3. Single-core sweep, 3 repeats per point per phase

Source: `data/2026-10-02-lu-single-app/lu_runs_20261002-010911_recovered.csv`
Transcript: `data/2026-10-02-lu-single-app/lu_session_20261002-010911.log`

| point | hit OFF | memAcc OFF | memAcc ON | **memAcc Δ** | L2 cyc Δ | secHits/migration | abort% |
|---|---:|---:|---:|---:|---:|---:|---:|
| p2 n512 b16 plru | 94.13% | 1,859,004 | 1,936,436 | **+4.17%** | +0.51% | 2.76 | 63.4% |
| p2 n512 b32 plru | 94.30% | 1,427,884 | 2,295,903 | **+60.79%** | +1.75% | 24.56 | 64.0% |
| p2 n512 b64 plru | 70.02% | 8,257,360 | 8,205,455 | −0.63% | +0.17% | 10.55 | 70.7% |
| p1 n512 b128 plru | 48.12% | 12,578,322 | 12,074,483 | **−4.01%** | −0.28% | 5.28 | 42.6% |
| p2 n256 b128 plru | 77.82% | 741,455 | 910,125 | **+22.75%** | +2.18% | 6.36 | 53.7% |
| p2 n384 b128 plru | 79.65% | 2,097,747 | 2,308,738 | +10.06% | +0.98% | 6.30 | 40.2% |
| p2 n512 b128 plru | 81.74% | 13,074,940 | 12,167,116 | −6.94% | −61.24% ⚠ | 8.96 | 40.3% |
| p2 n512 b256 plru | 52.73% | 12,155,234 | 12,070,424 | −0.70% | +0.49% | 8.85 | 23.8% |
| p2 n768 b128 plru | 67.11% | 28,750,323 | 27,732,372 | −3.54% | −0.46% | 5.28 | 29.6% |
| p4 n512 b128 plru | 52.45% | 12,906,644 | 12,384,000 | −4.05% | −0.49% | 6.16 | 32.2% |
| p2 n1024 b128 plru | 51.55% | 102,534,015 | 98,087,978 | **−4.34%** | −0.70% | 9.47 | 39.2% |
| p2 n512 b128 **random** | 58.10% | 10,965,039 | 10,530,148 | −3.97% | −0.73% | 6.36 | 72.4% |

⚠ **The `p2 n512 b128 plru` row is contaminated.** Its OFF half was the first point measured in the
session, while the SD card was still flushing the files staging had just pushed. Two of its three
OFF runs took 70.6 s and 94.3 s against an ~19 s workload, with accessA 5x normal at a 90%+ hit
rate — a kernel working set, not LU's. Its hit rate (81.74%) and the −61% cycle delta are both
artefacts. `run_parsec_session.exp` now does `sync` + `sleep 15` after staging to prevent this.
Use the `p1 n512 b128` row (48.12%, −4.01%) as the clean centre point.

### 3.1 `secHits/migration` is a misleading validity metric

The b=32 point has the **highest** hits-per-migration in the whole sweep (24.56) and the **worst**
outcome (+60.79% traffic). Each migration was reused often, yet the scheme still lost badly: at a
94% hit rate the lines SBC displaces were going to hit in their home set anyway, so a "successful"
secondary hit is work that need not have happened, plus a displacement and an eventual writeback.
Judge SBC on `memAcc`, never on migration reuse.

### 3.2 Random replacement is a cheaper baseline than PLRU

At the same point, random's OFF baseline is 10,965,039 against PLRU's 12,578,322 — random is
~13% cheaper before SBC is switched on at all. SBC's *relative* gain is about the same either way
(−3.97% vs −4.01%).

---

## 4. Dual-core n=768: the warm-state carry-over, and the correct cold figure

Source: `data/2026-10-03_04-lu-n768-boots/` (5 session CSVs + 3 transcripts)

`./lu_ncb -p2 -n768 -b128`, one OFF/ON pair per boot:

| session | boot condition | memAcc OFF | memAcc ON | **memAcc Δ** | work Δ | abort% |
|---|---|---:|---:|---:|---:|---:|
| 20261002-035925 | WARM: 3x n=512 ON ran first | 32,655,540 | 29,241,663 | −10.45% | −0.73% | 24.8% |
| 20261002-060333 | WARM: 3x n=512 ON ran first | 34,016,383 | 31,319,447 | −7.93% | −0.13% | 34.0% |
| 20261003-135712 | COLD: fresh reprogram | 34,950,530 | 33,421,081 | −4.38% | −0.69% | 38.1% |
| 20261003-203825 | COLD: fresh reprogram | 35,014,688 | 33,788,015 | −3.50% | +0.15% | 38.7% |
| 20261004-065147 | COLD: fresh reprogram | 34,945,444 | 33,742,642 | −3.44% | −0.53% | 36.3% |

**COLD mean −3.77% (n=3, spread 0.94 pp). WARM mean −9.19% (n=2).**

**Prior migrate=on activity roughly doubles the apparent benefit.** The two warm sessions had three
`n=512` ON runs before the measured `n=768` pair. Zeroing the counters does not undo that: the
earlier runs leave migrated lines resident and an Association Table already built, so the next run
starts from a different cache geometry. Abort rate drops too (25–34% warm vs 36–39% cold),
consistent with destinations already being paired up.

**Only the COLD figure is a valid cold-start result.** `-3.77%` is the number to quote for
n=768 dual-core.

### 4.1 Methodological rule this established

- A reboot is **not** enough. `sbc_read --reset-all` refuses while lines are parked, and clearing
  the Association Table while lines are displaced would orphan them
  (`SetBalanceUnit.scala`, the phase-3 TODO). A **full FPGA reprogram** is the only true SBC reset,
  and the three COLD sessions above used one (`three_boots.sh` passes `-bit` without `-noprog`).
- Therefore **one migrate=on window per reprogram**, one point at a time.
- All migrate=off runs before any migrate=on run, for the whole plan.

---

## 5. Open blocker: dual-core migrate=on deadlock

The dual-core bitstream hangs under `migrate=on`. It does not happen with `migrate=off`, and the
single-core bitstream was 36/36 clean on the ON side.

| evidence | result |
|---|---|
| n=512 ON, dual-core | 6/6 completed |
| n=768 ON, dual-core | 3 of 7 attempts hung |
| LU b=64 + streamcluster pair ON, dual-core | hung on the first attempt (2026-10-05) |
| all migrate=on runs recorded across CSVs | 93 OK, 7 hung — every hang on dual-core |

Signature: LU prints its banner, then the board goes silent. In the 2026-10-05 case the board
stopped echoing the serial console entirely — a hard deadlock, not a slow run (verified by
attaching picocom afterwards and getting no response to a carriage return).
Transcript: `data/2026-10-05-lu-pair/pair_session_20261005-110920.log`.

**This is why the co-running experiment moved to the single-core bitstream.** See
`lu-streamcluster-pair-2026-10-05.md`.

---

## 6. Reproducing

```bash
cd scripts/ssbc_scripts
# the single-core sweep of section 3
./run_parsec_session.exp -plan lu -repeat 3 \
    -bit .../FPGASingleRocketVCU118L18K256K8WL2ConfigSBCPLRU-256KB-8way-candidate013-2026-10-01-2026-10-01.bit

# one cold OFF/ON pair at n=768 (repeat per boot; see three_boots.sh)
./run_parsec_session.exp -plan lu -repeat 1 -lu-centre 2,768,128 \
    -lu-n 768 -lu-b 128 -lu-p 2 -lu-policy plru \
    -bit .../FPGADualRocketVCU118L18K256K8WL2ConfigSBCPLRU-dual-256KB-8way-2026-10-02.bit

# inspect any plan without touching the board
./run_parsec_session.exp -plan lu -list
```
