# BUG: the dual-core freeze is a deadlock on a core's write-back of a parked line

**Written:** 2026-10-15 ~20:30, for the record and for restarting work after a context compaction.
**Status:** root cause found and reproduced in simulation. **Not fixed.** Fix plan: `PLAN-DRAFT.md` in this folder, waiting for
the user's decisions (its §10).
**RTL:** the L2 generator at `494ab5e` (`design/` byte-identical to the board images). Nothing in `design/` was changed.

Words used here:

| Word | Meaning |
|---|---|
| write-back | TileLink `Release` / `ReleaseData`: a core gives a line back to the L2 |
| write-back acknowledgement | `ReleaseAck` |
| probe, probe answer | `Probe` (channel B): the L2 asks a core to give up or downgrade a line; `ProbeAck` is the answer |
| request slot | an MSHR. Slot 6 (`c_mshr`) is reserved for write-backs, slot 5 (`bc_mshr`) for B or C work, slots 0 to 4 are normal |
| home row S, partner row P | the set a line's address maps to, and the set where SBC parks it |
| parked line | a displaced line: it sits in row P, but its address belongs to row S |
| serve in place | the L2 answers a request for a parked line straight from row P (task 003, stage 2e) |

## 1. In one paragraph

When one core writes back a **parked** line, the L2 must look for the line in the partner row before it can acknowledge the
write-back. That search waits while any other request slot is working in the partner row (`partnerBusy`). On a two-core chip
the slot working in the partner row can be the very slot the write-back interrupted. Either the scheduler freezes it,
because the reserved write-back slot is busy on the same set (Form A), or it is also waiting for a probe answer that the
releasing core may not send before it gets its acknowledgement (Form B). Each side waits for the other. Then both cores stop
on that set and the whole chip freezes. The TileLink spec forbids exactly this: the L2 must merge a write-back into the
transaction it collides with, never make it wait.

## 2. What the board showed (task 019)

- **Symptom.** The dual-core 512 KB image (`f5e3608f…`) freezes the whole board with SBC on. The console stops echoing and
  Linux prints no warning, because no hung-task, soft-lockup or RCU-stall message appears.
- **LU, 2048 x 2048 matrix, 128 x 128 blocks, SBC on:** froze 4 times in 5 attempts (2 of 2 with two threads, 2 of 3 with
  one thread). With SBC off: 0 freezes in 2 runs (676 s and 1,169 s).
- **Single-core image (`502649e2…`) with SBC on:** never froze, in about 30 LU runs plus 300 s of the freezing pattern
  (142 million migrations).
- **Hunt with our own program (`sw/l2_miss_calib_dual.c`):** two threads on the two cores, storing to lines that share L2
  sets, froze in under 2 s (boot 1) and 4.6 s (boot 7) with setting 1, and in 6.3 s with setting 0 (boot 8). Both threads on
  one core froze after 63 s and 40 s. Reads only froze after 21 s. One thread alone did not freeze in about 360 s.
- **Logs:** `scripts/logs/clean-runs/dual-hang-hunt-20261015/runs/`, `…/lu-sweep-dstmode1-dual-core-512k-20261015/`. The full
  history is in `coder/019-dual-core-lu-sweep/REPORT.md`.

## 3. Simulation reproduction

**Setup** (task 019 decision D21; new files only):

- Config `VerilatorRocket8KL116KL2SipTestDualDstConfig` (`RocketConfigs.scala`): two Rocket cores, a tiny D$ (2 sets x
  2 ways, so lines leave the L1 often), the default I$, and a 4 KB L2 (8 sets x 8 ways) with SBC, PLRU and `dstModes`. The
  pairing is pinned 5<->6 (`sbcForceDstSet = 6`), and the shadow checkers are on.
- Config `…SipTestDualDstNoStopConfig`: the same hardware, built with `+define+STOP_COND=0` so that assertions print but do
  not stop, as on the board, which has none. Run it with `+verilator+error+limit+20000`; never use 1e9, see the memory note
  `verilator-nostop-sim`.
- Test `sw/dual_release_deadlock_test.c`, built by `sw/compile_dual_test.sh`. The test defines `thread_entry()`, because the
  stock `syscalls.c` parks hart 1. It pairs set 5 with set 6, then both harts race. Each step does one of: load a set-5 line
  (keeps set 5 hot so clean victims migrate), store a set-5 line (a parked line becomes dirty in that core's L1), push lines
  out of the L1 with scrub loads (sends the write-backs), or load and store row-6 lines. At the end every written word must
  read back. Options: `-DSHARE`, `-DMIGRATE`, `-DHART1`, `-DDSTMODE`, `-DPOLICY`, `-DITERS`, `-DSEED`.
- A step takes about 156 L2 cycles. `+max-cycles` counts testbench cycles (1 ns); the L2 runs at half that rate.
  Trace times `[t]` are in ps: L2 cycle = t / 2000 − 389. A freeze starts 20,000 cycles before the first watchdog message.

**Results:**

| Run (log in `scripts/logs/clean-runs/dual-hang-hunt-20261015/sim/`) | Cores racing | Migration | Setting | Lines shared | Result |
|---|---|---|---|---|---|
| `nostop_noshare_dst1.log` | 2 | on | 1 | no | **froze** at 4.31 M L2 cycles, after about 28,000 steps |
| `det_noshare_dst1_t6.log` (6-thread build) | 2 | on | 1 | no | **froze at the identical cycle** |
| `rep_noshare_dst1_seed2.log` | 2 | on | 1 | no | **froze** at 6.93 M cycles |
| `rep_noshare_dst0.log` | 2 | on | 0 | no | **froze** at 3.18 M cycles |
| `rep_share_dst1.log` | 2 | on | 1 | yes | **froze** 0.22 M cycles after the race started |
| `ctl_noshare_mig0.log` | 2 | off | 1 | no | finished: 100,000 + 111,872 steps, 10.6 M cycles, data correct |
| `ctl_noshare_h1idle.log` | 1 | on | 1 | no | finished: 100,000 steps, 8.6 M cycles, data correct, 1,847 parked-line write-backs |

Before the first freeze (seed 1, 25,000 steps) the run had made 7,641 migrations, served 2,523 writes from parked lines and
handled 1,281 parked-line write-backs. After the freeze, neither core retired another instruction. The only activity left was
the simulation host polling for program output (a set-0 grant every 235 cycles).

**False alarms seen on the way** (simulation-only checks; plan item F3):

- `MSHR.scala:1725` "migration dropped at assess-reset" and `:1735` "destination write-back dropped". A request for the same
  line reloads (`repeat`) in the same cycle as a migration retires, so the check reads `migrating` before the retire clears
  it. Needs two cores on one line (the shared-lines run fired both in one cycle).
- `MSHR.scala:951/966` "State transition from S_INVALID to S_TIP_CD". The stock check runs on any schedule step with
  `!s_writeback && no_wait`. A write-back of a parked line takes its partner-search step in that state, before its metadata
  points at the parked entry, so the check judges the home victim. Once the reset offset is removed, it fired in the very
  cycle of the search (`trace_drd_noshare_dst1.out`).

## 4. The deadlock, step by step

**Form A** (no shared lines; from `deadlock_excerpt_noshare_dst1.txt`, the +verbose trace of seed 1). Tags:
`558065` = hart 0's set-5 line k=9 (address `0x8107E340`); `0x883f6` = `558070` = hart 1's set-5 line k=14 (`0x8107ED40`).

1. Cycle 4,312,753: slot 0 takes hart 0's store to its **parked** line k=9. Set 5 misses, so the slot defers its eviction,
   arms a memory fetch and searches row 6 (`SEC-DEFER`). While the fetch is armed, `w_grantfirst` is false, so slot 0
   advertises `nestC` (`MSHR.scala:495`).
2. Hart 1's L1 evicts its dirty parked line k=14 and sends `ReleaseData` (home set 5). Slot 0 says `nestC`, so the scheduler
   gives the write-back to the reserved slot 6 (`Scheduler.scala:312, :537-543`).
3. Slot 0's search hits (row 6, way 1). It now serves in place, so its physical row becomes 6 (`SEC-SERVE`,
   `MSHR.scala:456, :1541`).
4. Cycle 4,312,756: slot 6 reads set 5 and misses (the line is parked). It must search row 6, but `doSearch` requires
   `!partnerBusy` (`MSHR.scala:667`), and slot 0 now works in row 6 (`Scheduler.scala:387-389`). The acknowledgement waits for
   the search (`d_ready … sec_ready`, `MSHR.scala:638`).
5. While slot 6 is valid on set 5, `mshr_stall_abc` stops every normal slot of set 5 from scheduling
   (`Scheduler.scala:124-127`). So slot 0 never sends its grant and never retires. Its `PLRU-TOUCH` for the grant never comes.
6. 20,000 cycles later the watchdog reports slot 6: `MSHR wedged ... set=5 searching=1 s_ssearch=0 partnerBusy=1
   s_execute=0 prio=4 tag=883f6`. Hart 0 waits for its grant, and hart 1's next set-5 access queues forever. Both cores stop.

**Form B** (shared lines, `rep_share_dst1.log`): slot 1 is serving parked line `0x883fa` in place from row 6 and waits for
hart 1's probe answer (`w_ppa=0`). Hart 1 is writing back that same line, which sits in slot 6 waiting for `partnerBusy`.
Rocket does not answer a probe for a line whose write-back acknowledgement is still due (`DCache.scala:767-770, :810`; the
TileLink master rule). Three parties wait in a circle. This is the cycle predicted from the code reading before the simulation.

**Common root:** a write-back of a parked line waits for the partner row, and the partner row is held by the same-set
transaction that the write-back pre-empted. The original SiFive cache never lets the reserved write-back slot wait for another
slot. The SBC partner search added that wait (`MSHR.scala:236-248` explains it, and its deadlock argument covers waits inside
the L2 only).

## 5. Why it matches every board fact

| Board fact | Explanation |
|---|---|
| Dual-core image only | One Rocket core sends a dirty victim's write-back **before** its next request. So the write-back reaches set S first and the request queues behind it. The cycle needs a request whose search is already running, plus a write-back from **another** client |
| SBC off is clean | No parked lines, so no partner search |
| Setting 0 freezes too | The search and serve-in-place code is task 003's, not task 018's |
| Stores freeze in seconds, reads in tens of seconds | It needs dirty parked lines in an L1. A read pattern has only a few (counters, stack, kernel) |
| Both threads on one core freeze, but slower | The second core still runs kernel code that writes, so it can supply the write-back or the request, just less often |
| Sudden total freeze, Linux silent | Both cores wait on memory, so nothing runs and no timer interrupt is taken |

## 6. Which TileLink rules it breaks (spec 1.8.1)

- **Chapter 5, rule 3.** An agent must eventually accept and answer a received message, unless it has a **higher-priority**
  message of its own in flight or unanswered. For the L2, write-backs (C received from cores) rank above probes (B) and A. The
  write-back's answer here waits on a probe answer (Form B) and on a grant that is never sent (Form A). Neither is an
  allowed reason.
- **§9.2, concurrency.** "Once the Release is issued, the master should not issue ProbeAcks ... until it receives a ReleaseAck."
  And: "The slave cannot simply block the voluntary Release transaction until the Acquire transaction completes ... This
  scenario is an example of two transaction message flows being merged by the slave agent."

## 7. Related problems found by reading (not yet seen in simulation)

Listed as D2 to D6 in `PLAN-DRAFT.md` §4:

- **D2:** a write-back that needs a new slot is refused while a migration or search fence is up (breaks rule 3i).
- **D3:** a row-P slot evicting the parked line never learns it became dirty. Hidden behind this deadlock today; **a naive
  fix would turn it into data loss.**
- **D4:** two snapshot windows in the merge.
- **D5:** probe answers reach slots that are not waiting for them. Two cores only.
- **D6:** a data-carrying probe answer with no waiting slot is written to set 0, way 0.

## 8. What is not established

- That this is the board's only freeze path. It is the first one the simulation finds, in every run.
- That the fix removes the board freeze. That needs the plan's V6 step on the board.
- D2 to D6 come from reading the code. The plan's V3 step adds directed cases to show each one happening.

## 9. How to reproduce

```bash
CY=/home/damith/Research/repos/chipyard_performance_eval/chipyard
SW=$CY/generators/rocket-chip-inclusive-cache/sw
# non-stopping two-core simulator (about 1 minute; the current binary is the 6-thread build)
cd $CY && source env.sh && make -C sims/verilator CONFIG=VerilatorRocket8KL116KL2SipTestDualDstNoStopConfig \
  VERILATOR_THREADS=6 -j20 "VERILATOR_PREPROC_DEFINES=+define+VERILATOR +define+STOP_COND=0"
# test binary (board-like: no shared lines, setting 1, PLRU)
$SW/compile_dual_test.sh dual_release_deadlock_test drd_noshare_dst1 -DSHARE=0
# run (same plusargs as make run-binary-fast; error limit 20000; output de-duplicated)
$CY/scripts/logs/clean-runs/dual-hang-hunt-20261015/sim/run_nostop.sh drd_noshare_dst1 my_run
# expect: "[8666297000] ... mshrs_6: ... MSHR wedged ... tag=883f6" in sim/my_run.log
```

For a trace, run the simulator directly with `+verbose` and stderr sent to a file. Keep `+max-cycles` near the freeze, and
cut the file at the first `*** FAILED ***` line: the raised error limit also ignores the timeout stop.

## 10. Evidence files

In `scripts/logs/clean-runs/dual-hang-hunt-20261015/sim/`: `deadlock_excerpt_noshare_dst1.txt` (start here),
`trace_nostop_noshare.cut.stderr` (430 MB +verbose trace up to the freeze), `trace_nostop_noshare.stdout.dedup`,
`trace_drd_noshare_dst1.out` and `raw_noshare_dst1.*` (the false-alarm run), the seven run logs in the table above,
`build_*.log`, `run_nostop.sh`, `dedup.awk`.
