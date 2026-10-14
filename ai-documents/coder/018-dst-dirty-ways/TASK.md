# Coder task 018 — Destination setting: let a migration overwrite a dirty way that no L1 holds

**⭐ VERY IMPORTANT — the next task after task 017's board chain ends.**
**Filed:** 2026-10-12 · **Status:** open, not started
**This file:** `chipyard/generators/rocket-chip-inclusive-cache/ai-documents/coder/018-dst-dirty-ways/TASK.md` (the work order; do not edit it)
**Your report:** `REPORT.md` next to this file. Create it from the template in §15 and fill it in as you go.

This file is written so that a Sonnet session can run the whole task from it alone. Follow it literally.
If this file and your own judgment disagree, follow this file and write the disagreement in `REPORT.md`.
If a case is not covered here: stop and ask. Never guess at the board or at the RTL.

```bash
CY=/home/damith/Research/repos/chipyard_performance_eval/chipyard
L2=$CY/generators/rocket-chip-inclusive-cache
SRC=$L2/design/craft/inclusivecache/src
SWD=$L2/sw
OLD=$CY/scripts/logs/clean-runs/lu-sweep-single-core-512k-20261012   # task 017's campaign: READ ONLY
```

---

## 0. Rules — read before anything else

1. **Plan first.** Read this whole file. Reply with a plan of at most 10 lines plus the questions in §13. Touch
   nothing until the user says go. Simple English, point form.
2. **Stop points** (wait for the user at each one):
   - (a) after your plan (§6);
   - (b) after the RTL and software changes and the simulation gate (§8), before any commit or build;
   - (c) after the build and its checks (§9), before any board use;
   - (d) after the calibration boot, the tool dry run and the predictions (§10), before the chain;
   - (e) when the chain ends or aborts (§11);
   - (f) after the results and the report (§12).
   Any failed check is also a stop (rule 14).
3. **The board may still be busy with task 017.** Its driver runs in tmux `lu512drv` and was planned to end about
   05:00 on 2026-10-13. **Steps 1–3 (§6–§8) never touch the board, so they may run while it is busy.** Steps 4–7
   (build and board) start only when task 017 has ended:
   ```bash
   tail -1 $OLD/driver_status.txt            # must contain "ALL ROUNDS DONE" or "STOP:"
   ps -eo args | grep -aE "^/usr/bin/expect|^expect" | grep -a run_     # must print nothing
   ```
   If either check fails, wait, or ask the user. The tmux sessions `lu512drv` / `lu512` stay open after the end
   (`exec bash`): an open session alone does not mean the chain runs. **Never kill them.**
4. **Never overwrite `$SWD/build/sbc_read`.** Task 017's chain pushes that file to the board on every boot. Your
   new build goes to `$SWD/build/sbc_read_018`. Check `sha256sum $SWD/build/sbc_read` before and after your work:
   it must stay `8241eb8b8b7e910b…`.
5. **Never edit:** `scripts/ssbc_scripts/run_parsec_session.exp`, `scripts/ssbc_scripts/lib/*`, anything in an
   earlier campaign folder (including `$OLD`), task 017's `TASK.md` / `REPORT.md`, or any **existing** config class.
   You **add** new config classes; you never change old ones.
6. **RTL rules** (from tasks 005, 009, 012 and `CLAUDE.md`):
   - All new hardware sits behind a new compile-time flag `dstModes` (default `false`). With it `false`, the build
     must behave exactly as today, and the two new registers read 0.
   - **Setting 0 must behave exactly as today.** You prove it in simulation with identical counters and cycle counts (§8).
   - The new behaviour is gated on the **live** `L2_Replacement` register (`usePlru`), never on the compile-time
     flag alone. That was task 009's F1 bug.
   - **No destination probe.** The new settings must never offer a way that has any L1 bit set
     (`clients =/= 0`). A held way stays an abort. A probe in the destination path is what hung the board in task 009.
   - **Do not change the MSHR destination decision** (`MSHR.scala`, the `DREAD-RESULT` block, about lines
     1610–1690). It already handles every way the new settings offer (§2). Do not touch the destination fence, the
     migration token, `s_wsafe`, any `w.displaced` test, the probe/release registers, or `status.way` / `status.physSet`.
   - Counters: add pulses, never OR them (task 005 TASK §2.2). The new counter has one source (the Directory), so
     +1 per pulse is right.
   - Match the Chisel idioms of the file you edit. Scala is compiled with `-Xsource:2.11`. Prefer plain nested
     `Mux` over `MuxLookup` (its signature differs between Chisel versions).
7. **Simulate only through `sw/scripts/run_sbc.sh`** (it calls `make run-binary`, which passes `+dramsim`). Never
   call the simulator binary directly: without `+dramsim` the shadow checker raises a false alarm at cycle 27141.
8. **Build only with `scripts/ssbc_scripts/build_bitstream.sh`.** Never `make clean` in `fpga/`. Cite every image by
   its sha256, never by its file name alone.
9. **Git.**
   - RTL repo (`$L2`): work on a new branch `sbc-018-dst-dirty-ways`, cut from `sbc-013-sat-counter` (§7).
   - Commit only at stop (b), only with the user's OK, and only the files listed in §5. Use `git add <path>` for
     each file, never `git add -A` or `git add .`.
   - The working tree holds other people's uncommitted edits (`CLAUDE.md`, `ai-documents/…`): never commit them.
   - Chipyard repo: commit nothing (the 512 KB config is uncommitted there too; the build archives a `.config.diff`).
   - Never `git checkout <file>`, `git restore`, `git reset --hard`, `git clean`, `git stash`.
10. **Board method (same as task 017).** One fresh boot (reprogram + boot + stage) gives ONE measured window. Every
    arm of every point gets its own fresh boot. Results come only from the launcher (§10.2), never from one harness
    session over several windows. The calibration boot is a smoke test, never a result.
11. Never pass any `-allow-*` option. Never use `-resetall` or `sbc_read --reset-all`.
12. **Never kill the launcher or its tmux session while a window runs.** The harness dies with it and the boot is
    lost. If the user asks you to stop, stop only during a boot or a settle pause, and say which.
13. **Give the user every log path the moment a run starts** (repo convention): tmux session name, status file,
    session log, CSV, Verilator log folder, build driver log.
14. **A failing check is a finding.** Write it under "Findings" in `REPORT.md`, stop, ask. Never edit a checker to
    make it pass. Never judge a window by the size of its effect. Never retry to get a nicer number.
15. **Session logs hold binary bytes.** Use `grep -a`, `tail`, or read the CSV by column name. Never `cat` a log.
16. **Timing claims come from logs, not memory.** A board stage is about 28 min (about 21 min of it is the boot ROM
    loading Linux from the SD card). The 512 KB image build took 39 min.
17. **The result is memory accesses** (`memReads + memWrites`), reads and writes always shown separately. Board
    cycles understate the cost of memory: a DRAM read costs only about 38 L2 cycles at 50 MHz.
18. **Hit balance is an identity, not a test.** At equal work, the change in reads is exactly minus
    (Δprimary hits + secondary hits). Say which term moved; never write "the rule holds".
19. **Reporting style (the user's):** `RESULTS.md` holds results only. Answer questions in chat. Standard terms.
    Bullets and compact tables, short sentences.
20. Board hygiene: busybox has `taskset` but **no `timeout`**. picocom must not hold `/dev/ttyUSB2` while the harness runs.

---

## 1. Goal, in plain words

When SBC moves a line into a partner (destination) set, it must overwrite one of that set's 8 ways. Today the RTL
offers a way in this order:

1. an empty way (never happens on the board: the L2 is always full);
2. the least-recent **clean** way that **no L1 holds**: overwritten silently;
3. otherwise the plain least-recent way: if it is dirty with no L1 bit, it is written back first (task 012); if an
   L1 holds it, **the migration aborts**.

On the 512 KB image, **82–83% of all migration attempts abort in step 3** (task 017). Step 3 never looks at the
other ways. A dirty way that no L1 holds can be overwritten safely: it needs only a write to memory, not a probe.
Task 012 built that write-back path, and it passed its board gate (4 of 4), but today it is used only when that
way happens to be the least-recent one.

This task adds a runtime setting, register `SBC_DstMode`:

| setting | way offered to a migration |
|---|---|
| **0** | today, unchanged |
| **1** | the least-recent clean way with no L1 bit; **if there is none, the least-recent dirty way with no L1 bit**; else today's step 3 |
| **2** | the least-recent way with no L1 bit, **clean or dirty**; else today's step 3 |
| 3 | same as 0 (reserved) |

Plus one counter, `SBC_DstRescue`: destination reads where today's rule aborts on a held way while a dirty way with
no L1 bit was free.

Then measure on the 512 KB setup, LU at two points, SBC off and settings 0, 1, 2. **Questions for the verdict:**

1. How many held aborts does each setting remove?
2. What happens to memory accesses (reads and writes separately) at n512 b128 (SBC's best point on this image,
   −4.68%) and at n1024 b64 (no effect, −0.14%)?
3. Does any setting make things worse?

Why it is safe, in one line: **dirty and held are different properties.** A dirty line with no L1 bit is in no L1
(the L1 wrote it back, which cleared its bit), so overwriting it waits only on memory, which always answers. A held
line needs a probe of the L1, and that is the wait that hung task 009. See task 012 `TASK.md` §3, "Why C2 cannot
deadlock, and C3 can".

---

## 2. Facts verified on 2026-10-12 — check them in §6, do not re-derive them

**RTL** (line numbers at `sbc-013-sat-counter` HEAD `b46a8ac`; `design/` is identical to `fed7227`, the RTL of the
512 KB image `37923e6b…`):

| what | where |
|---|---|
| Destination mask today: valid, `(!displaced \|\| allowDisplacedVictim)`, `!dirty`, no clients | `Directory.scala:210-211` (`evictableOH`) |
| `freeWays`, `evictOH = evictableOH & freeWays`, `recency.io.evictMask := evictOH`, `evictPick` (masked PLRU walk), victim mux (invalid → evictPick → policy way → …) | `Directory.scala:218-232` |
| Assert tying `evictPick` to `evictOH`; sim-only printfs (`DIR-EVICT`, tier) | `Directory.scala:240`, `:337-358` |
| `maskedWay`: the least-recent allowed way (with every way allowed it equals the plain PLRU way) | `Recency.scala:53-66` |
| `preferEvictable` is raised **only** by the migration destination read, so the mask changes nothing else | `Scheduler.scala:469-484`, `MSHR.scala:673` |
| The decision on the offered way: `dstFree` / `dstEvictable` (clean, no clients: silent overwrite) / `dstReleasable` (`usePlru` and dirty and no clients: write back, then overwrite) / abort with reason counters. `dstReleasable` takes **any** dirty way with no L1 bit; nothing ties it to the PLRU way | `MSHR.scala:1610-1690` |
| 012 asserts that must stay quiet: "a client-held destination way was taken", "a clean destination way took the write-back path" | `MSHR.scala:1344-1360` |
| `usePlru` wiring (copy this pattern): register → `io.l2_replacement` → InclusiveCache → Scheduler → Directory and MSHRs | `Control.scala:61, 186, 188` → `InclusiveCache.scala:155, 205` → `Scheduler.scala:55, 118, 210` → `Directory.scala:117`, `MSHR.scala:173` |
| Counter wiring (copy this pattern): `dstAbortHeld` | `Scheduler.scala:848` → `PerfCounters.scala:42, 74, 227, 243, 264, 281` → `Control.scala:175`, regmap `:301-304` |
| Micro parameters: `plruReplacement` is the last field | `Parameters.scala:117-148` |
| `WithInclusiveCache` parameters / building the micro parameters | `Configs.scala:49-73` / `:116-133` |
| MMIO offsets in use near the end: `0x438`, `0x490`, `0x498`, `0x4A0`, `0x4A8`. Reserved for task 005: `0x470`–`0x480`. **Planned: `0x4B0` `SBC_DstMode` (R/W, 2 bits), `0x4B8` `SBC_DstRescue` (R, 64 bits)** | `Control.scala` regmap |

**Software:**

| what | where |
|---|---|
| MMIO defines; the `L2_POLICY` sim helper (`sbc_set_policy()`, a volatile in `.sdata` so values 0/1 change one data byte only) | `sw/sbc_mmio.h:65-69`, `:79-87` |
| Tests that call `sbc_set_policy()` | `sw/migration_stress_test.c`, `sw/sbc_migrate_switch_test.c`, `sw/dirty_guest_evict_test.c` |
| `sbc_read`: build line; register table ("appended, never inserted"); `show()`; options; policy write + read-back | `sw/sbc_read.c:8-9`, `:76-89`, `:147-151`, `:236-251`, `:298-307` |
| Sim gates to copy: `run_013_gate.sh` (`SBC_CONFIGS`, `SBC_TESTS`, `SBC_LABEL`, `SBC_CLEAN`, `EXTRA_CFLAGS`), `run_012_gate.sh` | `sw/scripts/` |
| Sim output: `sw/verilator_logs/<test>_<config>_<label>/` = `<test>.log`, `<test>.out`, `sbc.log` (the `[SBC]` printfs), `sbc_stats.txt` (PASS/FAIL, asserts, counters, cross-checks) | `sw/scripts/run_sbc.sh` |
| Sim configs | chipyard `RocketConfigs.scala:376` `VerilatorRocket8KL116KL2Config` (SBC + PLRU), `:387` `VerilatorRocket8KL116KL2NoSbcConfig` |

**Board side:**

| what | where |
|---|---|
| Board config (uncommitted in chipyard) | `RocketConfigs.scala:302` `SingleRocketVCU118L132K512K8WL2ConfigSBCPLRU`; FPGA wrapper `fpga/src/main/scala/vcu118/Configs.scala:188` |
| Harness (copy it; never edit it), sha256 starts `b13d93e768b04559` | `scripts/ssbc_scripts/run_parsec_session.exp`: `set LIB` `:97`; board path `/root/sbc_read` `:111`; CSV columns `:472-479`; **the one `sbc_read` call per window `:533`**; host `sbc_read` path in `stage_sbc_read` `:731-745`; `-noprog` `:161` |
| A harness copy in another folder must set `LIB` to an absolute path | example: `clean-runs/bodytrack-sweep-single-core-20261009/tools/run_parsec_session.exp:152` |
| Launcher to copy: `$OLD/tools/launch_lu_sweep_v6.sh` | image pin `:47-48`; `plan_args` (one-point plan per label) `:76-81`; checker call `:99`; harness call `:143`; stage list `:205-209`; rival check `:276`; `LU_NOEXEC` `:316` |
| Checker to copy: `$OLD/tools/check_lu_stage.py` (v2) | writes `stage_check.txt` and `stage_result.json` (all counters, including `memRelClean` from the transcript) |

**Task 017 data** (512 KB L2 + 32 KB L1, fresh boot, one window per arm):

| point | memory accesses off → on | change | migrations | attempted | held aborts (share of attempted) |
|---|--:|--:|--:|--:|--:|
| n512 b128 | 2,440,698 → 2,326,535 | −4.68% | 5,732 | 38,352 | 31,875 (83.1%) |
| n1024 b64 | 4,278,214 → 4,272,304 | −0.14% | 20,805 | 118,325 | 97,299 (82.2%) |

- No empty L2 way in any off window: `memReads + memAcqPerm = memWrites + memRelClean`, exactly.
- `memRelClean` is in the transcript's `[SBC-WINDOW]` line and in `stage_result.json`, **not in the CSV**.
- Among the migrations that succeeded, 7–24% already wrote back a dirty destination (the least-recent-way case).

---

## 3. The design — implement exactly this

### 3.1 Compile-time flag

- `Parameters.scala`, `InclusiveCacheMicroParameters`: add `dstModes: Boolean = false` after `plruReplacement`,
  with the comment `// 018: SBC_DstMode (0x4B0) + SBC_DstRescue (0x4B8). false = not built, both read 0.`
  Add `require (!dstModes || (enableSetBalancing && plruReplacement), "018: dstModes needs SBC and PLRU")`.
- `Configs.scala`, `WithInclusiveCache`: add the parameter `dstModes: Boolean = false` after `plruReplacement`, and
  pass `dstModes = dstModes` where the micro parameters are built.

### 3.2 The register `SBC_DstMode` (`Control.scala`)

- A new `Option` output `dst_mode: UInt(2.W)`, built only when `dstModes` (same pattern as `l2_replacement`).
- A R/W `RegField` of 2 bits at **`0x4B0`**, reset 0. Description: `"SBC_DstMode"`,
  `"Destination way: 0 = clean only (today), 1 = clean then dirty, 2 = least-recent of clean or dirty; held ways never"`.
- Not cleared by `SBC_Reset` or `SBC_StatsReset`, not held by `L2_StatsHold` (like `L2_Replacement`).

### 3.3 Wiring

`Control` → `InclusiveCache` (next to `:205`; tie to 0 where `:155` ties `usePlru`) → `Scheduler` (new
`Option` input `dstMode`) → `Directory` (new `Option` input `dstMode`). The MSHR does not need it.

### 3.4 The Directory — the only behaviour change

Replace the one line `val evictOH = evictableOH & freeWays` by this (a sketch: adapt names to the file). Keep the
Scala-level `if`: with `dstModes = false` the elaborated expression stays exactly today's.

```scala
// 018: the destination mask, chosen at run time by SBC_DstMode. Held ways (any clients bit) are never in it:
// overwriting one needs a probe, the hold-and-wait shape that hung task 009. The MSHR's dstReleasable (012 C2)
// already writes back any dirty, client-free way it is offered.
val evictOH = if (!params.micro.dstModes) evictableOH & freeWays else {
  val dirtyFreeOH = Cat(ways.map(w => w.state =/= INVALID && (!w.displaced || allowDisplacedVictim) &&
                                      w.dirty && !w.clients.orR).reverse)
  val cleanOH = evictableOH & freeWays
  val dirtyOH = dirtyFreeOH & freeWays
  val mode    = Mux(io.usePlru.get, io.dstMode.get, 0.U)        // gate on the LIVE policy register (009 F1)
  Mux(mode === 1.U, Mux(cleanOH.orR, cleanOH, dirtyOH),
  Mux(mode === 2.U, cleanOH | dirtyOH,
                    cleanOH))
}
```

Everything downstream (`recency.io.evictMask`, `evictPick`, the victim mux, the assert at `:240`) keeps using
`evictOH` unchanged. Keep the sim-only printfs at `:337-358` working; it is fine to print `mode` in them.

### 3.5 The counter `SBC_DstRescue` (`0x4B8`, 64 bits, read only)

**Definition:** count one per migration destination read when **all** of these hold, at the same stage the victim
mux uses (the stage of the `when (ren2 && preferEvictable)` printf at `:340`):

- it is a destination read (`preferEvictable`) and `usePlru` is 1;
- no free invalid way would be taken (`!(preferInvalid && (invalidWayOH & freeWays).orR)`);
- no clean way with no L1 bit is free (`!cleanOH.orR`);
- at least one dirty way with no L1 bit is free (`dirtyOH.orR`);
- the way **setting 0** would offer has an L1 bit. With no clean candidate, setting 0 offers
  `v0 = Mux((policyOH & freeWays).orR, policyOH & freeWays, PriorityEncoderOH(freeWays))`, so the test is
  `Mux1H(v0, ways.map(_.clients.orR))`.

The count does not depend on the setting. Under setting 0 it counts aborts that settings 1/2 would avoid; under
settings 1/2 it counts aborts that were avoided.

- Wire it like `dstAbortHeld`: Directory (new `Option` output `dstRescue: Bool`) → Scheduler → `PerfCounters`
  (new input; a 64-bit register, +1 per pulse; held by `L2_StatsHold`; cleared by `SBC_StatsReset` and
  `SBC_Reset`) → `Control` `RegField.r(64, …)` at **`0x4B8`**, built only when `dstModes` (else drive the input
  with `false.B` and add no regmap entry, so the offset reads 0).
- Add a sim-only printf under `params.micro.sbcDebug` when the pulse fires:
  `[SBC] DST-RESCUE set=<set> mode=<mode> v0=<way>`. The simulation gate counts these lines.

### 3.6 Software

- `sw/sbc_mmio.h`: after `SBC_DSTABORT_BOTH` add
  `#define SBC_DST_MODE (L2_CTRL_BASE + 0x4B0)` and `#define SBC_DST_RESCUE (L2_CTRL_BASE + 0x4B8)`, with
  comments in the existing style.
- `sw/sbc_mmio.h`: inside `sbc_set_policy()`, add a block compiled only when `SBC_DSTMODE_SET` is defined. Use the
  same volatile-in-`.sdata` trick as `L2_POLICY`, so values 0/1/2 change one data byte only:
  ```c
  #ifdef SBC_DSTMODE_SET
  static volatile uint64_t sbc_dst_mode __attribute__((section(".sdata"))) = SBC_DSTMODE_SET;
  #endif
  ...
  #ifdef SBC_DSTMODE_SET
      sbc_wr(SBC_DST_MODE, sbc_dst_mode);
      printf("[SBC] dstmode=%llu\n", (unsigned long long)sbc_rd(SBC_DST_MODE));
  #endif
  ```
  **Do not edit the three tests' existing code.** Without `-DSBC_DSTMODE_SET` the test binaries must stay byte for
  byte what they are today: §8 compares counters, and a layout change alone changes them (task 007 F4).
- `sw/sbc_read.c`:
  - append `{ "dstRescue", 0x4B8 }` at the **end** of the register table (after `dstAbortBoth`), so it prints in
    every window line;
  - a new option `--dstmode=0|1|2`: write `0x4B0` right after the policy write; read it back; on a mismatch, print
    `sbc_read: SBC_DstMode read back X after writing Y - dstModes is not built into this bitstream. Aborting` and
    exit 1 (same shape as the policy check);
  - in `show()`, print ` dstMode=<n>` (a number, read live from `0x4B0`) before ` policy=…`, so the harness's
    key=number parser picks it up;
  - update the usage line and the header comment.
- Build the new binary **to a new file only** (rule 4), from the chipyard root:
  ```bash
  cd $CY && source env.sh && riscv64-unknown-linux-gnu-gcc -O2 -static -o $SWD/build/sbc_read_018 $SWD/sbc_read.c
  sha256sum $SWD/build/sbc_read        # must still start 8241eb8b8b7e910b
  ```

### 3.7 Configs (chipyard repo; add classes, never edit existing ones)

- `generators/chipyard/src/main/scala/config/RocketConfigs.scala`:
  - `VerilatorRocket8KL116KL2DstConfig`: a copy of `VerilatorRocket8KL116KL2Config` with `dstModes = true` added
    to its `WithInclusiveCache(…)`.
  - `SingleRocketVCU118L132K512K8WL2ConfigSBCPLRUDst`: a copy of `SingleRocketVCU118L132K512K8WL2ConfigSBCPLRU`
    with `dstModes = true`.
- `fpga/src/main/scala/vcu118/Configs.scala`: `FPGASingleRocketVCU118L132K512K8WL2ConfigSBCPLRUDst`, a copy of
  `FPGASingleRocketVCU118L132K512K8WL2ConfigSBCPLRU` that points at the new class.

---

## 4. Steps and stops (overview)

| step | what | board? | ends at |
|---|---|---|---|
| 1 | Preflight (§6) | no | stop (a) |
| 2 | Simulation baseline G0, then the RTL and software changes (§7) | no | — |
| 3 | Simulation gate G1–G6 (§8) | no | stop (b): results; ask to commit |
| 4 | Commit (user's OK), build one image, check it (§9) | no (Vivado only), and only after task 017 ended | stop (c) |
| 5 | Campaign folder, tool copies, dry run, calibration boot, predictions (§10) | yes | stop (d) |
| 6 | The chain: 2 points × 4 arms = 8 windows, about 4 h (§11) | yes | stop (e) |
| 7 | Results and report (§12) | no | stop (f) |

---

## 5. Files you may change — nothing else

RTL repo `$L2` (branch `sbc-018-dst-dirty-ways`):
- `design/craft/inclusivecache/src/{Parameters,Configs,Control,InclusiveCache,Scheduler,Directory,PerfCounters}.scala`
- `sw/sbc_mmio.h`, `sw/sbc_read.c`, `sw/migration_stress_test.c` (only the new case of §8.3, inside
  `#ifdef SBC_DSTMODE_SET`), `sw/scripts/run_018_gate.sh` (new)
- `ai-documents/coder/018-dst-dirty-ways/REPORT.md` (new)

Chipyard repo (uncommitted, like the 512 KB config):
- `generators/chipyard/src/main/scala/config/RocketConfigs.scala` (two new classes)
- `fpga/src/main/scala/vcu118/Configs.scala` (one new class)
- the new campaign folder `scripts/logs/clean-runs/lu-dstmode-single-core-512k-<YYYYMMDD>/` (date of its first board use)

---

## 6. Step 1 — preflight, no changes → stop (a)

```bash
cd $L2
git branch --show-current                   # expect sbc-013-sat-counter
git log --oneline -1                        # expect b46a8ac, or a later docs-only commit
git diff --stat fed7227 HEAD -- design/     # expect no output: design/ = the 512 KB image's RTL
git status --short design/ sw/*.c sw/*.h    # expect no output: no uncommitted RTL or sw source edits
grep -n "0x4B0\|0x4B8" $SRC/Control.scala $SWD/sbc_mmio.h $SWD/sbc_read.c    # expect no output: offsets free
sed -n 205,240p $SRC/Directory.scala        # matches §2
sed -n 1615,1660p $SRC/MSHR.scala           # matches §2 (dstFree / dstEvictable / dstReleasable)
grep -ln "sbc_set_policy" $SWD/*.c          # the three tests of §2
sha256sum $SWD/build/sbc_read | cut -c1-16  # 8241eb8b8b7e910b
tail -1 $OLD/driver_status.txt              # is task 017 still running? (rule 3)
```

Write the results in `REPORT.md` ("Preflight"). If anything differs from §2, write it there and say so in your plan.
Reply with your plan and the §13 questions. **Stop (a).**

---

## 7. Step 2 — simulation baseline G0, then the changes

After the user's go:

1. **Branch** (no file changes): `cd $L2 && git switch -c sbc-018-dst-dirty-ways`
2. **Run G0 before editing anything.** It is the baseline that §8 compares against:
   ```bash
   cd $SWD/scripts
   SBC_CONFIGS="VerilatorRocket8KL116KL2Config VerilatorRocket8KL116KL2NoSbcConfig" \
   SBC_TESTS="sbc_migrate_switch_test migration_stress_test" SBC_LABEL="018-g0" SBC_CLEAN=1 \
   EXTRA_CFLAGS="-DL2_POLICY=1" ./run_sbc.sh 2>&1 | tee $SWD/verilator_logs/018-g0-console.txt
   ```
   (Or write `run_018_gate.sh` first, §8.1, and run its `g0` stage: the same command.) Give the user the log folders
   `$SWD/verilator_logs/*_018-g0/`. Every `sbc_stats.txt` must say `result : PASS` and `crash/asserts : NONE`.
   If G0 fails, stop: the baseline itself is broken.
3. **Edit** in this order: §3.1 flag → §3.2 register → §3.3 wiring → §3.4 Directory mask → §3.5 counter → §3.6
   software → §3.7 configs.
4. **Build `sbc_read_018`** (§3.6) and confirm the old binary's sha is unchanged.
5. Fill in "RTL changes" in `REPORT.md`: one line per file (what changed and why).

---

## 8. Step 3 — the simulation gate → stop (b)

### 8.1 The gate script

Write `sw/scripts/run_018_gate.sh <stage>`, modelled on `run_013_gate.sh` / `run_012_gate.sh`. One stage per call.
Use `SBC_CLEAN=1` only on the **first** run after an RTL edit (it runs `make clean` and wipes every simulator build);
otherwise `SBC_CLEAN=0`. Never run two gate stages at the same time.

| stage | RTL | config(s) | `EXTRA_CFLAGS` | tests |
|---|---|---|---|---|
| `g0` | **before any edit** | `…L2Config`, `…L2NoSbcConfig` | `-DL2_POLICY=1` | switch + stress |
| `g1` | new | `…L2Config`, `…L2NoSbcConfig` (flag off) | `-DL2_POLICY=1` | switch + stress |
| `g2` | new | `…L2DstConfig` (flag on, register untouched = setting 0) | `-DL2_POLICY=1` | switch + stress |
| `g3` | new | `…L2DstConfig` | `-DL2_POLICY=1 -DSBC_DSTMODE_SET=0` | switch + stress (with the §8.3 case) |
| `g4` | new | `…L2DstConfig` | `-DL2_POLICY=1 -DSBC_DSTMODE_SET=1` | switch + stress (with the §8.3 case) |
| `g5` | new | `…L2DstConfig` | `-DL2_POLICY=1 -DSBC_DSTMODE_SET=2` | switch + stress (with the §8.3 case) |
| `g6a` / `g6b` | new | `…L2DstConfig` | `-DL2_POLICY=0 -DSBC_DSTMODE_SET=0` / `=2` (random policy) | switch + stress |

(`switch` = `sbc_migrate_switch_test`, `stress` = `migration_stress_test`; `SBC_LABEL="018-<stage>"`.)

### 8.2 Pass rules

For every run: `sbc_stats.txt` shows `result : PASS` and `crash/asserts : NONE`;
`grep -a "SBC shadow" <dir>/*.out` prints nothing; every per-case line says PASS.

**First confirm that the test binaries did not change.** `run_sbc.sh` recompiles each test on every run. After
`g0`, `g1` and `g2` (same flags), record `sha256sum $SWD/build/{sbc_migrate_switch_test,migration_stress_test}.riscv`.
The values must be the same in all three. A different binary changes every counter by itself (task 007 F4), so
fix that first (§8.3 says where the new code goes) before you compare counters.

**Identity checks** (byte-identical counters and cycle counts). Use this comparison for each test and config:

```bash
cmpl() { diff <(grep -aE '^\[SBC-|PASS|FAIL|Completed after' "$1") <(grep -aE '^\[SBC-|PASS|FAIL|Completed after' "$2"); }
LG=$SWD/verilator_logs
cmpl $LG/migration_stress_test_VerilatorRocket8KL116KL2Config_018-g0/migration_stress_test.log \
     $LG/migration_stress_test_VerilatorRocket8KL116KL2Config_018-g1/migration_stress_test.log   # must print nothing
```

| check | compare | must be |
|---|---|---|
| I1: flag off = today | `g1` vs `g0`, both tests, both configs | identical |
| I2: flag on, setting 0 = today | `g2` (`…DstConfig`) vs `g0` (`…L2Config`), both tests | identical |
| I3: random policy ignores the setting | `g6a` vs `g6b`, both tests | identical except the `[SBC] dstmode=` line |
| I4: settings 1/2 are safe | `g4`, `g5` | PASS, no asserts, no shadow alarm, the §8.3 case PASS |
| I5: settings 1/2 do something | `DST-RESCUE` lines in `g3`'s `sbc.log` ≥ 1; held aborts (`[SBC-DSTABORT] … held=`) in `g4` and `g5` below `g3` | true |

If I1, I2 or I3 shows any difference: stop. Write the first differing lines in `REPORT.md`. Do not "explain it away".

### 8.3 The directed case (it proves the new path deterministically)

The stock tests rarely reach this case: in task 013's gate, 132 of 64,315 attempts aborted on a held way. So add one
case to `migration_stress_test.c`, **compiled only when `SBC_DSTMODE_SET` is defined** (so `g0`–`g2` binaries do not
change). Name: `case_held_lru_dirty_alt`. Model it on the existing `case_full_dirty_dst`.
Put the new function at the very end of the file, and its call as late in `main` as possible, both inside
`#ifdef SBC_DSTMODE_SET`. Then confirm the binary check of §8.2.

State to build in the destination set D before a migration into it:
- every way valid;
- **the least-recent way H has an L1 bit set.** Read a line once: the tiny sim L1 drops it later without telling
  the L2, so the bit stays set;
- **at least one other way is dirty with no L1 bit.** Write a line, then push it out of the tiny L1 by touching other
  addresses in *other* L2 sets, so the L1 writes it back;
- no clean way with no L1 bit.

PLRU only learns from grants (each L1 fetch), not from write-backs. It is a tree, so the touch order matters: read
H first, then touch the other seven ways so that H's sibling is touched before the far half. For H = way 0, use
the order 1, then 2–3, then 4–7. Check the result in `sbc.log` (`PLRU-VICTIM`, `DREAD-RESULT` lines).

Expected, and the case must PASS in all three settings (read `SBC_DST_MODE` in the test to know which outcome to expect):
- **setting 0:** the migration aborts with reason held (`[SBC] ABORT-DST … held=1`), and one `DST-RESCUE` line appears;
- **settings 1 and 2:** the migration commits, a `DST-RELEASE` line shows the dirty way written back, and the data check passes.

If you cannot build this state in 2 attempts, stop (b) and report what `sbc.log` shows. Do not weaken the expected outcomes.

### 8.4 Stop (b)

Show the user: one table (stage × test: PASS, asserts, `DST-RESCUE` count, held aborts, migrations, cycles), the
results of I1–I5, the `git diff --stat`, and the `sbc_read_018` sha. Ask:
**"May I commit these files on `sbc-018-dst-dirty-ways`?"** List the files. Commit only after a yes.

---

## 9. Step 4 — one image → stop (c)

Only after the commit **and** after task 017 has ended (rule 3), or when the user says to build anyway.

```bash
cd $CY
bash scripts/ssbc_scripts/build_bitstream.sh --dry-run FPGASingleRocketVCU118L132K512K8WL2ConfigSBCPLRUDst 512KB-8way-L1-32K8W-dst018
# read the header: config found, L2 RTL commit = your commit, no "uncommitted design/" warning. Then:
tmux new -d -s b018 "bash scripts/ssbc_scripts/build_bitstream.sh FPGASingleRocketVCU118L132K512K8WL2ConfigSBCPLRUDst 512KB-8way-L1-32K8W-dst018; exec bash"
```

- Tell the user: tmux `b018` and the driver log `fpga/build-logs/driver-FPGASingleRocketVCU118L132K512K8WL2ConfigSBCPLRUDst-<stamp>.log`.
- About 40 min. Wait with a watcher on the driver log; never with `pgrep` on a pattern (it can match itself).
- **Checks** (each a line in `REPORT.md`):
  - sha256 of the new `.bit`;
  - timing met (worst slack ≥ 0) and no DRC errors, from the archived `.reports/`. Compare with `37923e6b…`:
    worst slack +0.24 ns, 54 DRC warnings. A failed timing is a stop;
  - device tree (`.dts`): 1 core; L1 I and D 32 KB, 8-way; L2 512 KB, 8-way, 1024 sets;
  - `.provenance.txt`: every RTL file's sha equals your commit.
- **Stop (c).**

---

## 10. Step 5 — campaign, tools, dry run, calibration, predictions → stop (d)

### 10.1 Folder

```bash
NEW=$CY/scripts/logs/clean-runs/lu-dstmode-single-core-512k-$(date +%Y%m%d)
mkdir -p $NEW/tools $NEW/vivado $NEW/calibration
BIT=<the new .bit from §9>
```

### 10.2 Tool copies (in `$NEW/tools/`; originals untouched)

1. **Harness** `run_parsec_session.exp`, copied from `scripts/ssbc_scripts/run_parsec_session.exp`. Record the
   original's sha256 (starts `b13d93e768b04559`). Change only these, and save `diff -u` as `tools/harness_changes.diff`:
   - `LIB` → the absolute path `/home/damith/Research/repos/chipyard_performance_eval/chipyard/scripts/ssbc_scripts/lib`;
   - new option `-dstmode N` (N = 0, 1 or 2; when absent, nothing changes);
   - when given, add ` --dstmode=N` to the `sbc_read` call (`:533`);
   - host `sbc_read` path in `stage_sbc_read` → `$SW_DIR/build/sbc_read_018` (the board path stays `/root/sbc_read`);
   - append `dstRescue dstMode` at the **end** of `CSV_COLS`.
2. **Launcher** `launch_dst_v1.sh`, copied from `$OLD/tools/launch_lu_sweep_v6.sh`:
   - `BIT1` / `BIT1_SHA` → the new image (first 16 hex);
   - the campaign root → `$NEW`; the harness → `$NEW/tools/run_parsec_session.exp` (record its sha256 in the
     `PROVENANCE` lines, plus the sha256 of `sbc_read_018`);
   - **arms instead of phases.** A stage is `arm|label`, with arm one of `off m0 m1 m2`. `off` runs the harness with
     `-phases off -dstmode 0`; `mN` runs `-phases on -dstmode N`. Results go to `$NEW/<arm>/<label>/`. The
     `is_valid`, `gave_up`, `nfailed`, deferral and summary loops go over the 4 arms (where v6 loops over `off on`);
   - labels: `lu-p1-n512-b128-plru lu-p1-n1024-b64-plru`; order per label: `off`, `m0`, `m1`, `m2`;
   - the checker call passes `--phase off|on --dstmode N`;
   - keep v6's failure policy unchanged: one attempt in pass 1; 2 retries per deferred stage in pass 2; 3 failures
     in a row stop the chain; 150 s settle after a failed attempt;
   - adoption of a running harness (`ADOPT_*`): it must also recover `-dstmode`. If that is not simple, switch adoption
     off and say so in `PLAN.md`.
3. **Checker** `check_dst_stage.py`, copied from `$OLD/tools/check_lu_stage.py` (v2). Add `--dstmode N` and two
   checks. Change nothing else:
   - the window line has `dstMode=` equal to N (every arm, off included);
   - the window line has `dstRescue=`; in the off arm it must be 0 (no migration → no destination read).
   Add `dstRescue` and `dstMode` to the counters it saves in `stage_result.json`.
4. **Tables** `dst_table.py` (new, small): read every `$NEW/<arm>/<label>/stage_result.json` and print the §12 tables.

### 10.3 Dry run (no board)

```bash
cd $NEW && bash -n tools/launch_dst_v1.sh && echo syntax-ok
```

Then print the 8 stages and the exact harness command of each stage **without running them**. If the copy has no such
mode, add an `LU_DRYRUN=1` switch that prints and exits. Check: 8 stages, the right `-phases` / `-dstmode` per arm,
the new image path. Also `-list` through the harness copy must show the two labels. Put the output in `PLAN.md`.

### 10.4 Calibration boot (the new image's first boot; a smoke test, never a result)

One session, one point, setting 2, off then on, on one boot. This is the same shape as task 017's calibration (§7 there):

```bash
H2=$NEW/tools/run_parsec_session.exp
tmux new -d -s dstcal "cd $NEW/vivado && $H2 -plan lu -bit $BIT -policy plru -lu-centre 1,512,128 -lu-p 1 \
  -lu-policy plru -lu-n 512 -lu-b 128 -bench lu-p1-n512-b128-plru -phases off,on -dstmode 2 -repeat 1 2>&1 \
  | tee $NEW/calibration/console.txt; echo HARNESS-DONE >> $NEW/calibration/console.txt; exec bash"
```

- Note the newest `scripts/logs/lu_runs_*.csv` before you start. When `HARNESS-DONE` appears, move the new CSV and
  session log into `$NEW/calibration/` (the launcher looks for the newest CSV in `scripts/logs/`), then
  `tmux kill-session -t dstcal`.
- Checks: Linux boots (`acdfim`, `1 node, 1 CPU`); no `Kernel panic`, `Oops` or `Unable to handle kernel`; both rows
  rc 0; the off row has 0 migrations and `dstRescue=0`; **both rows print `dstMode=2`**; the on row has migrations > 0.
- Silent boot (no new output 10 min after programming): wait 150 s and retry once; a second one is a stop.
- Write `$NEW/calibration/CALIBRATION.md`, labelled **"one session — planning only, never a result"**.

### 10.5 Predictions — write `$NEW/PLAN.md` before the first measured window; never edit them afterwards

Method, provenance (image sha, harness / launcher / checker / `sbc_read_018` sha256s), the dry-run output, and:
- per point and arm (`m0`, `m1`, `m2`): the held-abort share of attempts, and the change in memory accesses vs
  `off` as a numeric band. One-line reason each; say "guess" where it is one;
- 3–5 sweep-level predictions, each with a **"wrong if"** line. Cover at least:
  - how much settings 1 and 2 cut held aborts at each point;
  - whether n512 b128 saves more with settings 1/2 than with 0;
  - whether n1024 b64 changes at all;
  - whether memory writes rise with settings 1/2 (more destination write-backs);
  - setting 1 vs setting 2.

**Stop (d):** show the calibration checks, the dry-run output and the predictions. Wait for go.

---

## 11. Step 6 — the chain (8 windows, about 4 h) → stop (e)

After the user's go, and only if rule 3's checks still pass:

```bash
cd $NEW
tmux has-session -t dst018 2>/dev/null && echo "dst018 exists - stop and ask"
tmux new -d -s dst018 "gnome-session-inhibit --inhibit suspend:idle --reason 'SBC 018 dst modes' bash tools/launch_dst_v1.sh"
```

- Tell the user: tmux `dst018`, `$NEW/chain_status.txt`, and each `STAGE-LOG` line (session log and CSV paths).
- Wait with the watcher of task 017 (TASK §9 there), pointed at `$NEW/chain_status.txt`.
- `STAGE-VOID`: read that attempt's `stage_check.txt`, tell the user in two lines, start the watcher again. The
  launcher retries by itself.
- `CHAIN ABORT` or `CHAIN FINISHED`: **stop (e)**. Report the valid stages (out of 8) and anything dropped.
- Never run another harness, programFPGA or picocom while the chain runs.

---

## 12. Step 7 — results and the report → stop (f)

`python3 $NEW/tools/dst_table.py $NEW` prints, and you copy into `RESULTS.md` (results only) and `REPORT.md`:

1. **Per point × arm:** memory accesses (reads, writes); change vs `off` (total, reads, writes); hit %; L2 cycles
   change; migrations; attempted; held aborts (`dstAbortHeld + dstAbortBoth`, and the share of attempted);
   `dstRescue`; **destination write-backs** = migrations − (memReads + memAcqPerm − memWrites − memRelClean) (the L2 is
   full, so a fill with no announced eviction is a silent overwrite; the rest wrote back a dirty destination);
   secondary hits.
2. **Per migration vs `off`** (task 017 TASK §5): Δprimary and secondary per migration. Say which term moved.
3. **Cross-check, not a result:** `off` and `m0` against task 017's off and on windows at the same point (different
   image, same RTL behaviour). Flag a gap above 1.5 percentage points; never retry because of it.
4. **Predictions graded:** right or wrong per row, with the measured number.
5. **Verdict:** 5–8 plain lines. Does setting 1 or 2 convert held aborts into useful migrations? At which point? At
   what cost in writes? What is not established with one window per arm?
6. **All log paths:** one row per valid window (transcript, CSV), plus `failed/` and `dropped/`.

Checks (one line each in `REPORT.md`): zero SBC activity in every `off` window; `accessA` within 0.5% of `off` in
every arm (flag only); the four outcomes sum to `accessA` within 64; `memReads + memAcqPerm == dataMiss + upgradeMiss`.

**Stop (f):** a short chat summary (bullets, one compact table, the paths). Suggest doc updates (`CLAUDE.md` box,
`ai-documents/README.md` trackers, `coder/README.md`) in `REPORT.md`; make them only when the user says.

---

## 13. Questions for your plan reply (my defaults in brackets)

1. Branch `sbc-018-dst-dirty-ways` cut from `sbc-013-sat-counter`? [yes]
2. Register offsets `0x4B0` (setting) and `0x4B8` (counter)? [yes, if §6 shows them free]
3. Commit the RTL at stop (b), on the new branch, after your OK? [yes; the image must be built from committed RTL]
4. Points: n512 b128 and n1024 b64 only? [yes. bodytrack 1600 needs its own harness on this image: propose it in the report]
5. Order per point `off → m0 → m1 → m2`, each its own fresh boot? [yes]
6. Build while task 017's chain still runs? [no: wait for it to end, unless you say so]
7. One window per arm, no replicates? [yes; you decide repeats after stop (f)]

---

## 14. Not in scope

Probing a held destination way (task 012 C3), migrating dirty source lines, any change to the MSHR destination
decision, the adaptive throttle, bodytrack, dual core, edits to task 017's campaign or the original harness and lib,
edits to `CLAUDE.md` / READMEs (suggest them in `REPORT.md`), and any commit in the chipyard repo.

---

## 15. `REPORT.md` template (create it next to this file)

```markdown
# Coder report 018 — destination setting (dirty ways with no L1 bit)

**Started:** <date> · **Status:** <one line>

## ▶ RESUME HERE
- <where things stand, what runs where (tmux, status files), what is next>

## Findings
- <anything unexpected, each with the evidence path; then stop and ask>

## Preflight (§6)
| check | result |

## RTL and software changes (§7)
| file | what changed | why |
Branch, commit hash (after stop (b)), `sbc_read_018` sha256, old `sbc_read` sha256 unchanged: yes/no

## Simulation gate (§8)
| stage | test | config | PASS | asserts | shadow | DST-RESCUE | held aborts | migrations | cycles |
I1–I5: <result each> · the directed case per setting: <result>

## Build (§9)
sha256 · timing (worst slack) · DRC · device tree · provenance vs commit

## Campaign tools (§10)
Folder · shas of harness copy, launcher, checker, dst_table.py · diffs · dry-run output · calibration checks

## Predictions (§10.5)
<copied from PLAN.md, graded in Results>

## Chain (§11)
| # | arm | label | started | verdict | attempt |

## Results (§12)
<tables 1–4>

## Verdict
<5–8 lines>

## All log and CSV paths
| # | arm | label | verdict | transcript | CSV |
```

---

## 16. Where to look things up

- `generators/rocket-chip-inclusive-cache/CLAUDE.md`: terminology, the MMIO map, build-phase leftovers, recurring bug patterns
- Task 012 (`ai-documents/coder/012-reland-destination-eviction/`): the dirty-destination write-back, and why it needs no probe
- Task 010 `REPORT.md` §13: the analysis of the task 009 hang
- Task 017 (`ai-documents/coder/017-lu-sweep-512k/`): the board method, the tools, and the 512 KB data
- `ai-documents/guides/fresh-boot-campaign-handoff.md`: the fresh-boot method and tools
- `ai-documents/guides/fpga-linux-run.md` §1: the build recipe
