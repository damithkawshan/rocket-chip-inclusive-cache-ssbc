# FPGA A/B baseline data — VCU118, 520.omnetpp_r

Measured board data for the SBC-off (and matching SBC-on) halves, recovered from the session
transcripts in `chipyard/scripts/logs/`. Recorded 2026-09-11. **§4 added 2026-09-14 — the fixed-work
results. Read §4 first.**

Board: VCU118, `FPGASingleRocketVCU118L18K256K16WL2ConfigSBCResetEnabled`, 50 MHz, 2 GB DDR4,
1 Rocket core, 8 KB L1s, 256 KB 16-way L2 (256 sets). One bitstream, migration toggled at
runtime via `SBC_MigrateEnable` (0x3C0), so both halves are the same silicon and routing.

---

## 1. First A/B — fixed TIME window (not valid as a result; see §4)

Session `board_session_20260910-234644.log`, 2026-09-11 00:43–01:05.
Workload: `520.omnetpp_r` **ref** input, `-c General -r 0`, no sim-time override.
Method: `run_sbc_window.sh` — 60 s warm-up, **600 s fixed window**, then the benchmark is killed.

| | migrate OFF | migrate ON |
|---|---:|---:|
| window | 00:44:23 → 00:54:23 | 00:55:29 → 01:05:29 |
| **L2_Accesses** | **853,446,927** | **682,103,636** |
| L2_Hits (primary) | 769,730,166 | 395,309,230 |
| primary hit rate | **90.19%** | **57.95%** |
| total hit rate (primary+secondary) | 90.19% | 62.05% |
| migrations | 0 | 98,663 |
| secHits | 0 | 27,930,278 |
| secMiss | 0 | 18,599,244 |
| attempted / aborted | 0 / 0 | 434,557 / 336,975 |
| dispDrop / dispRelease | 0 / 0 | 96,809 / 15 |
| secC | 0 | 17,268 |
| parked (level, end of window) | 0 | 1,845 |
| hits per park | — | **283.09** (break-even ≈ 1.0) |

### ⚠ This comparison is NOT valid as a performance result

Both halves ran for the same 600 **seconds**, not the same **work**. The SBC half completed
853 M → 682 M L2 accesses, i.e. **~20% less of the program**, so every "fewer accesses" reading
is confounded with "did less". This is the measurement that prompted the "same work" warning in
CLAUDE.md. Use it as evidence that the mechanism *engages* — not as a gain or a loss.

What it does establish, and is worth keeping:

- **Migration fires at scale on real hardware**: 98,663 commits in 600 s, 1,845 lines parked.
- **`hits per park` = 283** against a break-even of ~1.0 — each parked line is served many times
  before it is torn down, which is the whole premise of serve-in-place.
- **Abort rate is high**: 336,975 of 434,557 attempts (77.5%) aborted.
- **`dispRelease` = 15 vs `dispDrop` = 96,809** — dirty parked-line writeback is still almost
  never exercised, so it remains the untested correctness path it was in simulation.
- The primary hit rate falling 90.19% → 57.95% is expected in part (a parked line is excluded
  from primary hit detection and shows up under `secHits` instead), which is why the *total*
  rate, 62.05%, is the one to read. It is still well below the 90.19% baseline.

⚠ This bitstream/`sbc_read` predates the outer-port counters, so the headline metric
(`L2_MemReads` + `L2_MemWrites`) and `L2_Cycles` are absent from these readings. Any repeat
must capture them.

---

## 2. SBC-off free-running counters at boot (2026-09-11 14:03 session)

Not a workload window — cumulative since power-on, covering Linux boot and the file copy. Kept
because it is the first reading that includes the outer-port counters.

```
migrate          : OFF
primary hit rate : 96,194,809 / 103,032,961 = 93.36%
total hit rate   : 96,194,809 / 103,032,961 = 93.36%
MEMORY ACCESSES  : 10,832,960   (reads 6,838,153 + writes 3,994,807)
bytes moved      : 693,309,440  (x64 B/block)
L2 cycles        : 74,364,874,354      (= 1,487 s at 50 MHz)
mem accesses/kcyc: 0.146
```

---

## 3. Run-to-completion attempts on 2026-09-11 — both stalled (smaller limits later finished, see §4)

Switching from the fixed window to `sbc_read --zero -- <cmd>` (fixed **work**, the method
CLAUDE.md asks for) was tried twice and neither finished:

| session | sim-time-limit | launched | outcome |
|---|---|---|---|
| `board_session_20260911-111516.log` | 0.8 s | ~11:50 | silent, abandoned |
| `board_session_20260911-140357.log` | 0.2 s | 14:39:18 | silent >2 h 37 m, abandoned |

Neither crashed: `sbc_read` blocks in `waitpid`, so a dead child would have printed its
`[SBC-WINDOW]` dump immediately. They were still computing.

**Why the earlier windowed runs looked fast and these do not:** the windowed runs never completed
the benchmark. `run_sbc_window.sh` kills it after 60 s warm-up + 600 s window — 11 minutes of wall
clock regardless of how much of omnetpp is left. These runs try to actually finish it.

**Why lowering the limit barely helped:** all three input sets (test/train/ref) build the *same*
network — diffing their `omnetpp.ini` shows they differ only in `seed-set` and `sim-time-limit`.
That network is ~300 LANs × ~24 hosts, i.e. thousands of hosts and tens of thousands of modules.
**Building** it is a fixed cost that `--sim-time-limit` does not reduce at all, and at 50 MHz on an
in-order Rocket with an 8 KB L1 it dominates a sub-second simulation. 0.8 s → 0.2 s changed only
the part after the build.

### Consequence for method

A valid fixed-work A/B on this board needs a workload whose **total** cost — build included — fits
the session. Either measure the build floor first (`--sim-time-limit=0.001s`) and pick a limit from
the observed `simsec/sec`, or use a workload that completes, e.g. the `memtest` already staged at
`test_dir/memtest`.

---

## 4. Fixed-work A/B runs that finished (2026-09-11 night → 2026-09-13)

Added 2026-09-14 from the session logs. Every run used `sbc_read --zero -- <omnetpp>` with a
`--sim-time-limit`, so the benchmark **ran to the end**. Both halves of each pair reached the **same
event count**, so they did the same work. This is the valid A/B that §1 was not.

Method: one bitstream, one boot, migrate-OFF half first, then `--reset-all --migrate=on`.

### 4.1 Headline — SBC is slower and moves more data, in every pair

| pair | L2 | sim-time | events | cycles, ON vs OFF | memory accesses, ON vs OFF |
|---|---|---|---:|---:|---:|
| A | 256 KB | 0.001 s | 28,270 | **+31.9%** | **6.96×** |
| B | 256 KB | 0.1 s | 35,128,553 | **+46.0%** | **3.56×** |
| C | 64 KB | 0.02 s | 9,347,248 | **+51.2%** | **2.45×** |

### 4.2 Full numbers

**Pair A** — `board_session_20260911-215010.log`, `…L18K256K16WL2ConfigSBCResetEnabled`

| | migrate OFF | migrate ON | ON / OFF |
|---|---:|---:|---:|
| `L2_Cycles` | 21,699,257,295 | 28,621,018,860 | 1.32× |
| `L2_MemReads` | 24,173,405 | 191,537,857 | 7.92× |
| `L2_MemWrites` | 8,676,146 | 37,143,501 | 4.28× |
| memory accesses (reads + writes) | 32,849,551 | 228,681,358 | 6.96× |
| total hit rate | 95.47% | 68.34% | |
| migrations / parked at end | 0 / 0 | 88,892 / 1,780 | |

**Pair B** — `board_session_20260912-021811.log`, same bitstream as A

| | migrate OFF | migrate ON | ON / OFF |
|---|---:|---:|---:|
| `L2_Cycles` | 606,430,476,198 | 885,140,998,271 | 1.46× |
| `L2_MemReads` | 2,322,681,628 | 8,983,608,947 | 3.87× |
| `L2_MemWrites` | 775,830,225 | 2,040,240,733 | 2.63× |
| memory accesses (reads + writes) | 3,098,511,853 | 11,023,849,680 | 3.56× |
| total hit rate | 86.81% | 55.54% | |
| migrations / parked at end | 0 / 0 | 8,323,681 / 1,903 | |
| `secHits` | 0 | 996,051,875 | |
| wall time at 50 MHz | ~3.4 h | ~4.9 h | |

**Pair C** — `board_session_20260913-185905.log` (clean copy: `SBC-dual_log_64KB.log`),
`…L18K64K16WL2ConfigSBC` (64 KB, 16-way L2)

| | migrate OFF | migrate ON | ON / OFF |
|---|---:|---:|---:|
| `L2_Cycles` | 214,361,396,378 | 324,118,459,931 | 1.51× |
| `L2_MemReads` | 1,608,360,059 | 3,976,237,323 | 2.47× |
| `L2_MemWrites` | 375,102,721 | 891,009,893 | 2.38× |
| memory accesses (reads + writes) | 1,983,462,780 | 4,867,247,216 | 2.45× |
| total hit rate | 69.69% | 39.81% | |
| migrations / parked at end | 0 / 0 | 1,547,988 / 480 | |
| hits per park | — | 180.6 | |

### 4.3 What this shows

- The loss in §1 was **not** a fixed-time artifact. With equal work, SBC is still clearly worse.
- **Reads and writes both go up.** Reads go up more.
- Pair A is too short to trust on its own — 0.001 s of simulation is mostly network setup.
- `L2_Accesses` is higher in every ON half even though the work is the same. It is not a work
  measure (see task 006 REPORT, G3). Use the event count for "same work".

### 4.4 Limits — read before quoting

- **One run per pair.** No repeats yet (workplan Step 1.4).
- Pair C is a different bitstream (64 KB). It is not a repeat of A or B.
- The ON half runs after the OFF half in the same boot. `--reset-all` clears SBC state, not the cache contents.
- `L2_Cycles` is the L2/uncore clock. Whether it equals CPU cycles is still unconfirmed (task 006). The ON/OFF ratio holds either way.
- Hit rates still count L1 write-backs as hits (task 005). Quote memory accesses and cycles, not hit rate.
- The SBC event counters are 32-bit in these bitstreams. Nothing wrapped here (largest: `secHits`
  996 M in pair B), but a 1.0 s run would.

### 4.5 Runs that did not finish

All on the 256 KB bitstream. Each log ends after the OFF half was launched; there is no result.

| session | sim-time |
|---|---|
| `board_session_20260912-001108` | 0.1 s |
| `board_session_20260912-181154` | 1.0 s |
| `board_session_20260913-030303` | 1.0 s |
| `board_session_20260913-134117` | 0.5 s |
| `board_session_20260913-175855` | 0.1 s |
