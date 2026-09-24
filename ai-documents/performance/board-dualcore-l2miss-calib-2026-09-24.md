# Board A/B — DualRocket 64 KB L2, l2_miss_calib Calibration & SBC Win (2026-09-24)

First board measurement demonstrating a decisive **SBC + PLRU performance win** over the baseline L2 on the DualRocket FPGA platform.

| | |
|---|---|
| Date | 2026-09-24 |
| Platform | VCU118 FPGA, 50 MHz, 2 GB DDR4 |
| Bitstream | `chipyard/fpga/bitstream_storage/FPGADualRocketVCU118L18K64K16WL2ConfigSBCPLRU-2026-09-18.bit` |
| Config | `FPGADualRocketVCU118L18K64K16WL2ConfigSBCPLRU` |
| Geometry | **2 Rocket cores**, 8 KB L1 D/I-caches per core, **64 KB 16-way L2 = 64 sets × 16 ways × 64 B** |
| Policy | PLRU replacement (`L2_Replacement = 1`), Option B destination probe workaround |
| Workload | `sw/l2_miss_calib.c` (`-s 100000 -h 32 -m 32`, sweep over `-p`) |

---

## 1. Executive Summary

1. **First Decisive Board Win for SBC + PLRU**:
   * **Cycles:** **−13.46%** (383.9M → 332.2M, −51.67M cycles)
   * **Memory Traffic (Reads):** **−29.52%** (4.79M → 3.37M, −1.41M reads saved from DRAM)
   * **Miss Rate:** **−16.61 percentage points** (60.63% → 44.02%)
   * **Secondary Hits:** **305,278 hits** (up from 0 in baseline, and 5,457 in the broken `-p 48` run)
   * **Hits per Park:** **0.36** (up from 0.00 at `-p 48`)
2. **Resolution of the "Secondary Hits Not Enough" Mystery**:
   * Running `l2_miss_calib` with `-p 48` failed because 48 lines stream cyclically into an effective combined capacity of only **20 ways** (16 source ways + 4 spare partner ways). Under cyclic streaming where working set > capacity, every line is evicted before reuse. Furthermore, 2.75M migrations flooded partner sets and evicted resident hit lines, worsening overall miss rate to 69.4%.
   * Sizing `-p 19` fits the 3 overflowing lines into the 4 spare partner ways, achieving 19.15% hit rate on partner searches without disturbing hit lines.
3. **Dual-Core Observations**:
   * Core 1 activity adds ~1.26M background accesses to `L2_AccessA` and accounts for `dstAbortHeld = 22,225` (L1 client residency under Option B) and `secProbe = 45` (cross-core coherence probes).
   * Pinned execution via `taskset -c 0` is recommended for single-core calibration.

---

## 2. Parameter Mechanics in `l2_miss_calib.c`

`l2_miss_calib` generates a synthetic workload with a designed L2 miss rate ($\frac{m}{h + m} = 50\%$ by default):
* **Hit lines ($h=32$, sets $0 \dots 31$):** Run across $HP = 12$ pages ($\frac{3}{4} \times \text{ways}$). 12 lines fit into 16 ways with 4 ways of headroom for OS noise.
* **Miss lines ($m=32$, sets $32 \dots 63$):** Run across $MP$ pages (configured by `-p`). Each step touches page `s % mp`.

### The Analytical Model of Paired Set Capacity

When SBC migration is enabled:
* Miss sets $32 \dots 63$ heat up to `satMax` and become **sources**.
* Hit sets $0 \dots 31$ cool down to 0 and become **destinations (partners)**.
* Destination sets already hold **12 resident hit lines** ($HP = 12$).
* Available spare ways in the partner set: $16 - 12 = \mathbf{4\text{ ways}}$.
* Total combined capacity for miss lines:
  $$C_{\text{effective}} = 16\text{ (source ways)} + 4\text{ (partner spare ways)} = \mathbf{20\text{ ways}}$$

---

## 3. The Experiment: Why `-p 48` Failed vs Why `-p 19` Won

### A. The Broken Run (`-p 48`)

```bash
./l2_miss_calib -s 100000 -p 48 -h 32 -m 32
```

* **Outcome:**
  * `migrations = 2,756,335`
  * `secondSearch = 3,214,938`
  * `secondaryHit = 5,430` (only 0.17% of partner searches hit)
  * `secMiss = 3,209,508`
  * `primaryHit = 2,532,157` (down from 3,200,000 designed — lost ~668k hits)
  * `dataMiss = 5,637,625` (miss rate degraded to **68.96%**, far worse than the 50% designed)
  * `hits per park = 0.00`
* **Root Cause:**
  1. **Working set (48 lines) $\gg$ Combined capacity (20 lines)**: In round-robin streaming, any line migrated to the partner set was evicted ~28 steps before its next touch.
  2. **Hit line eviction**: 2.75M migrations flooded destination sets and evicted the resident hit lines, destroying primary hits.

---

### B. The Winning Run (`-p 19`)

```bash
# Migrate ON:
./sbc_read --zero && ./l2_miss_calib -s 100000 -p 19 -h 32 -m 32 && ./sbc_read

# Migrate OFF:
./sbc_read --migrate=off
./sbc_read --zero && ./l2_miss_calib -s 100000 -p 19 -h 32 -m 32 && ./sbc_read
```

* **Detailed Side-by-Side Comparison:**

| Metric | Migrate OFF (Baseline) | Migrate ON (SBC + PLRU) | Delta |
| :--- | :--- | :--- | :--- |
| **Elapsed Time** | 7.68 s | 6.64 s | **−13.5%** |
| **L2 Cycles** | 383,905,384 | 332,236,028 | **−13.46% (−51.67 M)** |
| **Memory Reads (DRAM)** | 4,785,750 | 3,372,887 | **−29.52% (−1.41 M)** |
| **Memory Writes** | 183,959 | 161,670 | **−12.12%** |
| **Total Memory Accesses** | 4,969,709 | 3,534,557 | **−28.88%** |
| **L2 Miss Rate** | 60.63% | 44.02% | **−16.61 pts** |
| **L2 Hit Rate** | 39.37% | 55.98% | **+16.61 pts** |
| **Primary Hits** | 3,107,077 | 3,983,872 | **+876,795 (+28.2%)** |
| **Secondary Hits** | 0 | **305,171** | **+305,171** |
| **Second Searches** | 0 | 1,592,326 | 19.17% hit rate |
| **Migrations** | 0 | 842,237 | — |
| **Hits per Park** | 0.00 | **0.36** | Break-even ~1.0 |
| **Destination Aborts** | 0 | 25,005 (2.88%) | (2.3k dirty, 22.2k client-held) |

* **Why `-p 19` Works**:
  1. The 16 source ways hold 16 lines. The 3 overflow lines ($19 - 16 = 3$) fit comfortably into the 4 spare ways of the partner set.
  2. The lines survive in the partner set across the 19-step reuse distance, turning 305k DRAM accesses into secondary hits.
  3. Resident hit lines in destination sets are not displaced, keeping primary hit rate high.

---

## 4. Dual-Core Specific Insights

Running on `FPGADualRocketVCU118L18K64K16WL2ConfigSBCPLRU`:

1. **Access Inflation**:
   * Total measured `accessA` was ~7.66M to 7.89M (designed: 6.40M, +19.7% to +23.3%).
   * Core 1 runs the Linux idle loop (`wfi`), timer interrupts, and kernel housekeeping. Its L1 misses feed into the shared inclusive L2.
2. **Client-Held Aborts (`dstAbortHeld = 22,225`)**:
   * Each L2 line tracks client presence (`clients` bitmask for Core 0 and Core 1).
   * Under Option B, when PLRU selects a destination way that is held in either core's L1 cache, migration aborts to avoid probing/invalidating.
3. **Cross-Core Coherence Probing (`secProbe = 45`)**:
   * 45 secondary hits required an L1 probe before serving, confirming active coherence arbitration between cores.
4. **Recommendation for Single-Threaded Tests**:
   * Pin workload to a single core using `taskset -c 0` to prevent core hopping and minimize L1 client conflicts:
     ```bash
     sbc_read --zero && taskset -c 0 ./l2_miss_calib -s 100000 -p 19 -h 32 -m 32 && sbc_read
     ```

---

## 5. Next Steps & Tuning Recommendations

1. **Sweep `-p` between 17 and 20**:
   * `-p 18`: Leaves 2 spare ways in the partner set as safety margin.
   * Compare `hits per park` across $p \in \{17, 18, 19, 20\}$.
2. **Idle Partner Sets (`-h 0`)**:
   * By running `./l2_miss_calib -s 100000 -p 24 -h 0 -m 32`, sets $0 \dots 31$ have 0 hit traffic, leaving all 16 ways available.
   * Combined capacity is 32 ways; `-p 24` gives 8 overflow lines into 16 free ways, pushing `hits per park` toward 1.0.
3. **Multi-Core Asymmetric Co-running**:
   * Pin an aggressor thread on Core 0 (`-h 0 -m 32`, miss-heavy) and a sensitive thread on Core 1 (`-h 32 -m 0`, hit-heavy) to evaluate cross-core set balancing under realistic multi-tenant conditions.
