# Board A/B — 128 KB L2, omnetpp (2026-09-17)

First board run at 128 KB. Same method as the 64 KB runs in
[coder/007 REPORT](../coder/007-paper-aligned-eviction/REPORT.md): one bitstream, migrate OFF half, then
migrate ON half, same omnetpp fixed work in both.

| | |
|---|---|
| Session log | `chipyard/scripts/logs/board_session_20260917-033724.log` (03:37–04:43) |
| Build log | `chipyard/fpga/build-logs/FPGASingleRocketVCU118L18K128K16WL2ConfigSBC-20260917-031222.log` |
| Image (archived) | `chipyard/fpga/bitstream_storage/FPGASingleRocketVCU118L18K128K16WL2ConfigSBC-random-source-evict-2026-09-17.bit` |
| Config | `FPGASingleRocketVCU118L18K128K16WL2ConfigSBC` (added 2026-09-17). Its SBC-off twin `…128K16WL2ConfigNoSbc` is defined but not built |
| Geometry | 1 Rocket core, 8 KB L1s, **128 KB 16-way L2 = 128 sets × 16 ways × 64 B**, 50 MHz, 2 GB DDR4 |
| SBC params | `sbcAutoMigrate`, shadow and debug off, thresholds auto-derived (T_hi = 31, T_lo = 16) |
| Workload | `520.omnetpp_r` ref, `-c General -r 0 --sim-time-limit=0.002s`, run to completion under `sbc_read --zero --` |

---

## 1. Which RTL is in this image

- **inclusive-cache HEAD `55f6f3c`**: docs only, on top of `251c9d7` = tag `sbc-007-c2-breakeven-2026-09-16`
  (C0–C2 + random source victim).
- **Plus the uncommitted C3 teardown edits** that were in the working tree (snapshot
  `coder/007-paper-aligned-eviction/wip-2026-09-17/c3-uncommitted.patch`, 02:51). Evidence: the sbt step
  of this build recompiled only the `chipyard` and `fpga` subprojects (the new config). The inclusive-cache
  classes were already up to date with the tree, compiled by the C3 Verilator gate before 02:50.
- **Not the B7-1 fix** (built from 10:57). Without it teardown never fires (0 `TEARDOWN` lines in
  simulation), so this image **behaves like the tag**. It also carries C3 logic that never fires.

> ⚠️ `fpga/generated-src/…128K16WL2ConfigSBC/obj/VCU118FPGATestHarness.bit` **no longer holds this image.**
> A rebuild started at 11:23 (`…128K16WL2ConfigSBC-20260917-112348.log`) from a tree with the B7-1 fix + C3.
> To repeat this run, program the archived file (section 2, last block).

## 2. Commands

Run on the host, in tmux. `bistream_gen_vcu118.sh` deletes the config's `generated-src` dir before
every build (added 2026-09-17), so step 1 is a visible duplicate of that. **Never use `make clean` in
`fpga/`**: it deletes all of `generated-src`, including other configs' bitstreams that were never archived.

```bash
tmux new -s sbc128

CY=/home/damith/Research/repos/chipyard_performance_eval/chipyard
CFG=FPGASingleRocketVCU118L18K128K16WL2ConfigSBC
GEN=$CY/fpga/generated-src/chipyard.fpga.vcu118.VCU118FPGATestHarness.$CFG
BIT=$GEN/obj/VCU118FPGATestHarness.bit

# 1. clean the previous build of this config
rm -rf "$GEN"

# 2. build (~25 min; 03:12 -> 03:36 for this image)
bash $CY/scripts/ssbc_scripts/bistream_gen_vcu118.sh sbc_128l2

# 3. archive the new image (the next build of this config overwrites $BIT)
cp -p "$BIT" $CY/fpga/bitstream_storage/FPGASingleRocketVCU118L18K128K16WL2ConfigSBC-random-source-evict-2026-09-17.bit

# 4. board A/B: program, boot, push sbc_read, OFF half then ON half (~65 min)
cd $CY/scripts/ssbc_scripts && ./run_board_session.exp -bit "$BIT" -ab
```

What `-ab` typed on the board (copied from the session log). Both halves run the same workload line:

```bash
./sbc_read --migrate=off                       # OFF half
(cd test_dir/520.omnetpp_r_run_ref && /root/sbc_read --zero -- sh -c './omnetpp_r_base.riscv-64 -c General -r 0 --sim-time-limit=0.002s --cmdenv-status-frequency=0.001s --cmdenv-performance-display=true 2> wl.err' ; echo "workload rc=$?" ; tail -3 wl.err)
./sbc_read --reset-all --migrate=on            # ON half, then the same workload line again
```

Repeat this run on the same image, with no rebuild:

```bash
cd $CY/scripts/ssbc_scripts && ./run_board_session.exp -ab \
  -bit $CY/fpga/bitstream_storage/FPGASingleRocketVCU118L18K128K16WL2ConfigSBC-random-source-evict-2026-09-17.bit
```

## 3. Result

Both halves reported `workload rc=0` and stopped at `Simulation time limit reached`, event #1,185,546.
So both halves did the **same work**.

| | migrate OFF | migrate ON | ON vs OFF |
|---|---:|---:|---:|
| `L2_MemReads` | 163,236,584 | 163,323,122 | +0.053% |
| `L2_MemWrites` | 44,039,641 | 44,286,044 | +0.560% |
| reads + writes | 207,276,225 | 207,609,166 | **+0.161%** |
| `L2_Cycles` | 44,732,183,502 | 44,798,478,964 | **+0.148%** |
| `L2_AccessA` | 985,179,747 | 981,757,674 | −0.347% |
| `L2_PrimaryHit` | 821,943,163 | 818,411,411 | |
| `L2_SecondaryHit` | 0 | 23,141 | |
| hit rate | 83.43% | 83.36% | −0.07 pts |

ON half only:

| | |
|---|---:|
| `SBC_Migrations` | 130,982 |
| attempted / aborted | 155,808 / 24,843 |
| `L2_SecondSearch` | 77,949,702 (**7.94% of accesses**) |
| `L2_SecondaryHit` / `L2_SecondaryMiss` | 23,141 / 77,926,561 (**0.03%** of searches hit) |
| secondary hits per migration | 0.18 |
| `SBC_DispRelease` / `SBC_DispDrop` | 148 / 36,738 |
| `SBC_Parked` at end | 0 |
| migrations that reused a guest slot | 94,096 (71.8%) = migrations − dispRelease − dispDrop − parked |

The identities hold in both halves: accesses = primary + secondary + data misses, and second searches =
secondary hits + secondary misses.

## 4. Reading it

- **Break-even again, inside the noise.** +0.16% memory traffic and +0.15% cycles. The drift between the
  OFF halves of two 64 KB boots was +0.17% traffic and +0.27% cycles. One run, not repeated (tracker M2).
- **Next to the 64 KB run of the same tag** (`board_session_20260916-221525.log`):

  | | 64 KB | 128 KB |
  |---|---:|---:|
  | OFF hit rate | 67.27% | 83.43% |
  | `SBC_Migrations` | 3,353,354 | 130,982 |
  | second searches, share of accesses | 18.2% | 7.9% |
  | secondary hits per migration | 0.21 | 0.18 |
  | reads + writes, ON vs OFF | +0.072% | +0.161% |
  | `L2_Cycles`, ON vs OFF | +0.411% | +0.148% |

  Doubling the L2 cut migrations about 26×. The found-again rate per migration stayed about the same.
- **The cost is in second searches, not in migrations.** 7.9% of accesses search the partner set,
  99.97% of those searches miss, and `SBC_Parked` ends at 0. This matches the B7-1 symptom (`mayHold`
  stays true once a set has migrated), which this image does not fix. It is consistent with B7-1, not
  proof of it. The rebuild with the B7-1 fix is the test.
- **Writes rose more than reads** (+0.56% against +0.05%). Guest write-backs are not the reason
  (`SBC_DispRelease` = 148). Cause not investigated.
