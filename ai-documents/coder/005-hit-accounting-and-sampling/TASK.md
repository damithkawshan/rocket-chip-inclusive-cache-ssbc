# Coder task 005 — counters that follow the cache terminology, all counters in one place, interval sampling

**Date:** filed 2026-09-09 · rewritten 2026-09-14 · **Author:** thinker session
**Branch:** `sbc-sampling` (local only). Start from `e4c5d53` — task 006 is closed.
**Status:** open, not started.

> **This file is the whole work order.** It replaces the 2026-09-09 version (in git at `61505c1`) and the
> three amendments added on 2026-09-14. Nothing from those applies unless it is repeated here.

---

## 0. Before you start

Read, in this order:
1. `CLAUDE.md` — "Cache terminology", "Build-phase leftovers", the MMIO register table, and "ALWAYS
   simulate through `make run-binary` / `run_sbc.sh`".
2. `ai-documents/coder/README.md` — how TASK and REPORT work. You write only `REPORT.md`.
3. `ai-documents/guides/cache-terminology.md` — every new counter counts exactly one term from its table.
4. This file, all of it. Then `REPORT.md`, the template you fill in as you go.

Working rules:
- **Plan first.** Before editing any `.scala` file for a commit, give Damith a short plan: what changes,
  which files, how it affects the architecture, open questions. Wait for his go. Repeat for every commit.
- **Line numbers** below were checked on `e4c5d53`. Re-check each one before you edit. If the code does not
  match this file, stop, write it under "Where the work order is wrong" in REPORT, and tell Damith.
- **A failing check is a finding.** Report it. Never change RTL or a test to make it pass mid-run.
- **Never `git checkout` or revert a file you did not change** — some configs live as uncommitted work.
- **Stage files by name** — never `git add -A` or `git add .`. The tree was clean at the start (`c318092`).
  Put the doc edits §9.3 lists and your `REPORT.md` updates in the commit that lands each change.
- **The tests are functional checks.** Counter values need not match older runs. What must hold are the
  identities inside one run (§10).
- Simple, readable RTL. Comments 1–3 lines; long reasons go in REPORT or the commit message.
- Simulate only through `sw/scripts/run_sbc.sh` or `make run-binary` (they pass `+dramsim`). For driving
  `run_sbc.sh` with environment variables, see `sw/scripts/run_006_closeout.sh` (untracked). Label logs
  `005-c0`, `005-c1`, …; they land in `sw/verilator_logs/`.

---

## 1. Why

1. **The hit and miss words are fixed** (`cache-terminology.md`): an access is one inner-A request; a hit
   sends no outer A message; a miss sends one. No hit counter we have follows that:
   - `L2_Accesses` / `L2_Hits` count directory lookups on every channel. An L1 write-back (inner C) or an
     MMIO flush counts as an access; a write-back is always a hit; an upgrade (a BRANCH line that needs
     TRUNK, outer A `BtoT`) counts as a hit; a secondary hit counts as a miss; a repeat allocation (no
     directory read) is not counted at all.
   - `SBC_SecHits` includes write-backs and upgrades.

   So no hit rate we have is exact. Only the miss count is (every outer A message is one miss).
2. **Three counting mistakes** in the SBC counters (§6).
3. **Area.** Damith wants the area number with and without the measurement hardware by flipping one flag.
   Today the counters live in three modules and the read-back muxes are always built.
4. **No history.** We can read one set's heat now, but not how heat, parked lines, hits and misses change
   during a run.

---

## 2. Rules that bind every commit

### 2.1 Scope

- Only the **monitoring** counters that software reads over MMIO move or change. Everything the cache uses
  to operate stays exactly where and how it is: `sat`, `armed`, `at`, `parkCount`, the DSS, and the pulses
  the SetBalanceUnit acts on (`commit`, `dispRelease`, `dispDrop`, `dispHome`, with their OR and `Mux1H`).
- **Cache behaviour must not change.** Everything in this task only observes.
- **Do not change what feeds the heat counters (`sat`).** That is a separate open question.
- `L2_Accesses` and `L2_Hits` keep their exact condition and value (legacy — kept so old numbers stay
  comparable). They change module, nothing else.
- **No existing register address changes.** New registers use the addresses in §8.

### 2.2 Timing and sampling — urgent

A counter that counts at the wrong moment, twice, or not at all gives a wrong number that looks right.
These rules come from a check of the RTL.

| # | Rule |
|---|---|
| T1 | **Add, never OR.** A counter fed by pulses from several MSHRs adds `PopCount` of them. OR turns two same-cycle pulses into one count (§2.3 shows where that happens). The SetBalanceUnit's own inputs (§2.1) keep their OR |
| T2 | **One pulse per event.** Set a pulse only inside a `when` that is true for exactly one cycle per event: a directory result, a request fire, a port fire, a ProbeAck arrival, a retire. Every existing pulse does this (each `when` clears its own trigger: `w_ssearch`, `w_dread`, `migDeferred`, retire). Name the trigger in a one-line comment |
| T3 | **Count in the event's own cycle,** from the signal that causes it: outer A at `io.out.a.fire`; outer C at `sourceC.io.req.fire`; lookups at `directory.io.tap`; accesses at `request.valid && request.ready`. Never register one input and not the others |
| T4 | **A repeat uses the real hit bit** (`new_meta.hit`). On a repeat `new_meta` is `final_meta_writeback`, and `MSHR.scala:660` clears `hit` after a flush — a repeat after a flush is a miss |
| T5 | **Requests in progress.** An access is counted when the Scheduler accepts the request. Its outcome is counted later: a hit when the directory or the second search answers, a miss when outer A fires. In between, the request is in progress — in the request queue behind an MSHR already working on the same set (`queue`, `Scheduler.scala:295`), or inside an MSHR. A requests use only the `mshrs − 2` general MSHRs (the last two serve B and C — `prioFilter`, `:292`); the queue holds `secondary` entries (`ListBuffer(…, queues = 3·mshrs, entries = secondary)`, `:91`; `Parameters.scala:188-189`). So `L2_AccessA − (hits + misses)` is between 0 and `(mshrs − 2) + secondary`. No config sets `outerLatencyCycles` or `dirReg`, so with 64 B blocks of 8 B beats: `mshrs = 7`, `secondary = 33`, **bound 38**. After an `SBC_StatsReset` or a hold, between −38 and +38. Print `mshrs` and `secondary` at elaboration |
| T6 | **A clear wins its cycle.** `SBC_StatsReset` drops that cycle's events for every counter at once. Keep that |
| T7 | **One instant for all registers:** `L2_StatsHold` (§5.3). Reading ~40 registers one by one takes milliseconds while the cache keeps counting (`sbc_read` itself makes L2 traffic), so without a hold exact checks miss by hundreds |
| T8 | **Read every 64-bit register with one 8-byte access.** The control bus is 8 bytes (rocket-chip `ControlBusKey` default; no config changes it; `Configs.scala:104`), so one 8-byte read is one TileLink Get and the whole value comes from one cycle. Hardware cannot split a counter: rocket-chip's register mapper refuses, at elaboration, any field wider than a bus word (`generators/rocket-chip/src/main/scala/regmapper/RegMapper.scala:110-114`). The risk is software: a 32-bit read (`devmem … 32`, a `uint32_t` pointer) returns only the low half, and reading the halves separately samples them in different cycles — wrong by 4,294,967,296 if the low half wraps in between (`L2_Cycles`' low half wraps every ~86 s at 50 MHz). All current code, scripts and docs read counters as 64-bit. Keep it that way |
| T9 | **Sampler: one cycle per snapshot.** The freeze register captures the whole payload in the same cycle. Streaming it into the RAM afterwards is fine |
| T10 | **One clock.** Every counter and the sampler run on the Scheduler's clock |

### 2.3 Which pulses can fire in two MSHRs in the same cycle (checked on `e4c5d53`)

| Pulse | Raised when (`MSHR.scala`) | Two MSHRs in one cycle? |
|---|---|---|
| `secHit`, `secMiss`, `secPerm`, `secWrite`, `secC`, `secProbe` | search result, `io.directory.valid && searching` (`:1350-1456`) | **No.** One directory result per cycle, to one MSHR (`directoryFanout`, `Scheduler.scala:507-511`) |
| `homeBranch` | plan block, `io.directory.valid` only (`:1492`) | **No**, same reason |
| `migAttempt` | `:1073` fast/resume start, `:1146` deferred start | **No.** Every start is a destination claim (`migStartNow`, `:332`), asserted ≤ 1 per cycle (`Scheduler.scala:371`) |
| `migCommit` | retire of the migrating MSHR (`:635`) | **No.** One migration at a time |
| `dispRelease`, `dispDrop` | eviction of a parked line (`:1109`) | **No** — asserted (`Scheduler.scala:702`) |
| `migAbort` | `:1133` and `:1482` on a directory result; `:1161` on an **inner C ProbeAck** | **Yes.** A ProbeAck to one MSHR and a directory result to another can land in one cycle. `SBC_Aborted` can undercount today |
| new `migDecline` | `:1133` and `:1161` | **Yes**, same reason |
| new `primaryHit`, `secondSearch`, `probedHit` | plan block, which also runs on a **repeat** allocation with no directory read (`:1488`) | **Yes.** A repeat to the selected MSHR (`Scheduler.scala:345-346`) and a directory result to another MSHR can land in one cycle |
| new `secondaryHit`, `secondaryMiss` | search result | No — follow T1 anyway |
| `L2_AccessA` | `request.valid && request.ready` | One request per cycle |
| `L2_DataMiss`, `L2_UpgradeMiss`, the memory counters | one outer A port, one `sourceC.io.req` | One per cycle |

---

## 3. The monitoring counters when this task is done

All 64-bit. All cleared by `SBC_StatsReset` (`0x3B8`) except `SBC_Parked`. `SBC_Reset` (`0x358`) clears
only the SBC events group, except `SBC_Parked` — as today.

| Address | Register | Counts | Counted from | Group in `PerfCounters` |
|---|---|---|---|---|
| `0x328` | `SBC_Migrations` | migrations committed | `migCommit` | SBC events |
| `0x330` | `SBC_SecHits` | serves from the partner set, any channel (legacy meaning) | `secHit` | SBC events |
| `0x338` | `SBC_SecMiss` | partner searched, line not there, any channel | `secMiss` | SBC events |
| `0x348` | `SBC_Attempted` | migrations started | `migAttempt` | SBC events |
| `0x350` | `SBC_Aborted` | aborted after start, or declined before start | `migAbort` | SBC events |
| `0x360` | `SBC_SecPerm` | partner serves that had to acquire permission | `secPerm` | SBC events |
| `0x368` | `SBC_SecWrite` | partner serves where the requester needed T | `secWrite` | SBC events |
| `0x370` | `SBC_SecProbe` | partner serves that probed L1 first | `secProbe` | SBC events |
| `0x378` | `SBC_DispRelease` | dirty parked lines written back | `dispRelease` | SBC events |
| `0x380` | `SBC_DispDrop` | clean parked lines released | `dispDrop` | SBC events |
| `0x388` | `SBC_SecC` | partner serves raised by a C-channel Release | `secC` | SBC events |
| `0x390` | `SBC_HomeBranch` | directory results that found the home line in BRANCH, any channel | `homeBranch` | SBC events |
| `0x3A0` | `SBC_Parked` | **a level:** parked lines resident now. Never cleared, never held | commit, `dispRelease` / `dispDrop` | SBC events |
| `0x3A8` | `L2_Accesses` | legacy: directory lookups, every channel, internal reads excluded | `directory.io.tap.valid` | legacy lookups |
| `0x3B0` | `L2_Hits` | legacy: those lookups that hit | `tap.valid && tap.bits.hit` | legacy lookups |
| `0x3C8` | `L2_MemReads` | outer `AcquireBlock` | outer A fire, opcode | outer port |
| `0x3D0` | `L2_MemWrites` | outer `ReleaseData` | `sourceC.io.req` fire, dirty | outer port |
| `0x3D8` | `L2_MemAcqPerm` (was `L2_MemUpgrades`) | outer `AcquirePerm` | outer A fire, opcode | outer port |
| `0x3E0` | `L2_MemRelClean` | outer `Release` | `sourceC.io.req` fire, not dirty | outer port |
| `0x3E8` | `L2_Cycles` | L2 clock cycles | every cycle | outer port |
| `0x3F0` | `L2_AccessA` | **access** | Scheduler | outcomes |
| `0x3F8` | `L2_PrimaryHit` | **primary hit** | MSHR plan block | outcomes |
| `0x400` | `L2_SecondaryHit` | **secondary hit** | MSHR search result | outcomes |
| `0x408` | `L2_ProbedHit` | **probed hit** | MSHR plan block / search result | outcomes |
| `0x410` | `L2_DataMiss` | **data miss** | outer A fire, param ≠ `BtoT` | outcomes |
| `0x418` | `L2_UpgradeMiss` | **upgrade miss** | outer A fire, param = `BtoT` | outcomes |
| `0x420` | `L2_SecondSearch` | **second search** | MSHR plan block | outcomes |
| `0x428` | `L2_SecondaryMiss` | **secondary miss** | MSHR search result | outcomes |
| `0x430` | `SBC_Declined` | moves turned down before they started | `migDecline` | SBC events |

Rates: hit rate = (`L2_PrimaryHit` + `L2_SecondaryHit`) ÷ `L2_AccessA`; miss rate = (`L2_DataMiss` +
`L2_UpgradeMiss`) ÷ `L2_AccessA`; second-search rate = `L2_SecondSearch` ÷ `L2_AccessA`; second-search hit
rate = `L2_SecondaryHit` ÷ `L2_SecondSearch`.

**Read-backs that stay where they are, gated by the same flag (§4.4):** `SBC_SetSel` (`0x300`),
`SBC_SetSat` (`0x308`), `SBC_Status` bit 2 (`0x320`), `SBC_AtAssoc` (`0x398`).
**Always built:** `SBC_Status` bits 0–1, `SBC_ColdestSet` / `SBC_ColdestLevel` (DSS wires the cache already
uses), `SBC_BalanceSet`, `SBC_Reset`, `SBC_StatsReset`, `SBC_MigrateEnable`, the flush registers.

---

## 4. Commit 0 — move every monitoring counter into `PerfCounters`; one flag; rename

**No new counter, no fix, no `PopCount` yet.** What each counter counts must not change.

### 4.1 What moves

| Counters | From | In `PerfCounters`, count from |
|---|---|---|
| `L2_Accesses`, `L2_Hits` | `Directory.scala:318-333` | `directory.io.tap`: `tap.valid` is the same `ren2 && !internalRead` (`:313`) and `tap.bits.hit` is `io.result.bits.hit` (`:315`), so the values cannot change. Delete the Directory counters and their IO (`l2Accesses`, `l2Hits`, `clearStats`, `:103-106`) and the Scheduler wiring (`Scheduler.scala:744-747`). Keep the "why `!internalRead`" note as one line |
| The 12 SBC event counters | `SetBalanceUnit.scala:209-234`, `:264`, and their clears `:297-326` | The MSHR pulses the Scheduler feeds the SBU today (`Scheduler.scala:619-620`, `:691-704`) and `migCommit` (`:724-725`) |
| `SBC_Parked` and its `SBC_Reset` assert | `SetBalanceUnit.scala:238`, `:272-273`, `:293` | The commit and `dispRelease \|\| dispDrop`, same rule: a commit and an erase in one cycle cancel; never below 0 |

Then delete the SetBalanceUnit inputs that only fed counters — `migAttempt`, `migAbort`, `secHit`,
`secMiss`, `secPerm`, `secWrite`, `secProbe`, `secC`, `homeBranch`, and `clearStats` — and the counter
fields of `SBCStats`, which keeps only the read-backs. Keep `dispRelease`, `dispDrop`, `dispHome`, `commit`
and `clear`: `parkCount`, the AT, `sat` and the DSS use them. `Control.scala` and `InclusiveCache.scala`
read every counter from `PerfCounterStats`.

### 4.2 Inside `PerfCounters`

- Four groups, each with a one-line comment: outer port (006), legacy lookups (004), outcomes (added in
  commit 1), SBC events.
- The SBC events group is built only when `enableSetBalancing` (Scala `if`). In a NoSbc build those
  registers read 0, as today.
- **Inputs fed by MSHR pulses** are `log2Ceil(mshrs + 1)` bits wide and are added (`n := n + in`). In this
  commit feed each one the old OR (0 or 1). Commit 1 then changes only the Scheduler side to `PopCount`.
- Resets as today (T6): `SBC_StatsReset` clears every event counter; `SBC_Reset` clears only the SBC event
  counters; nothing clears `SBC_Parked`. Clears come after the increments.
- `SBC_Parked` is `log2Ceil(sets * ways + 1)` bits, zero-extended to 64 at the register.

### 4.3 The flag

`enablePerfCounters = false` removes all monitoring hardware: `PerfCounters`, the read-back muxes (§4.4),
`L2_StatsHold` (commit 1) and the sampler (commit 3). Those registers stay in the map and read 0 — drive
them from `0.U`. Change the one-line flag comments at `Parameters.scala:145` and `Configs.scala:71` to say
the flag covers all measurement hardware.

### 4.4 Read-backs behind the same flag

They read `sat` and `at`, so they stay where they are. Gate them in place with a Scala
`if (enablePerfCounters)`, else `0.U`:
- `SetBalanceUnit.scala:332-333`, `:346-347`: `sat(io.satReadSet)` and `at(io.satReadSet)` (`valid`,
  `assocSet`, `sd`) — the two 256-way muxes.
- `Control.scala:106-107`: the `SBC_SetSel` register (reads 0 when off).
- Leave `coldestSet`, `coldestLevel`, `coldestValid` — the cache already uses those wires.

### 4.5 Rename `L2_MemUpgrades` → `L2_MemAcqPerm`

It counts outer `AcquirePerm` — permission with no data, because the requester overwrites the whole
block. That can be a data miss or an upgrade miss, so "upgrade" was wrong. The address stays `0x3D8`. In
this commit rename the `PerfCounterStats` field (`memAcqPerm`), the `RegFieldDesc` and the `sbc_read.c`
label, `sw/sbc_mmio.h` (`SBC_L2_MEMACQPERM`) and the `[SBC-MEM]` line in `sw/migration_stress_test.c`. Old
logs say `memUpgrades=`.

---

## 5. Commit 1 — outcome counters, `PopCount`, hold

### 5.1 The eight outcome counters

In `PerfCounters`, not gated by `enableSetBalancing` (in a NoSbc build the three second-search counters
stay 0).

| Register | Count one when | Source (checked on `e4c5d53`) |
|---|---|---|
| `L2_AccessA` | an inner-A request is accepted | `Scheduler.scala`: `request.valid && request.ready && request.bits.prio(0) && !request.bits.control` — a new wire outside the `enableSetBalancing` block (the existing `isDemandA`, `:635`, is inside it) |
| `L2_PrimaryHit` | the plan finds the home line with enough permission | `MSHR.scala` plan block, A branch (`.otherwise`, `:1607`): `new_meta.hit && !(new_meta.state === BRANCH && new_needT)` — exactly the negation of the acquire test at `:1631` |
| `L2_SecondSearch` | the plan starts a second search | same branch: `willSearch` (`:1612`) |
| `L2_SecondaryHit` | the parked line is served in place with enough permission | search-result block, inside `when (willServe)` (`:1377`): `request.prio(0) && !request.control && !secNeedPerm` |
| `L2_SecondaryMiss` | the second search found nothing | search-result block, the outer `.otherwise` (`:1447`): `request.prio(0) && !request.control` |
| `L2_ProbedHit` | a primary or secondary hit also arms the inner-B probe | plan block: the primary-hit term AND the probe test (`:1645-1647`); search-result block: the secondary-hit term AND the probe test (`:1429-1431`). OR the two — they are exclusive branches of one MSHR |
| `L2_DataMiss` | outer A fires with param `NtoB` or `NtoT` | `PerfCounters`: `aFire && aParam =/= BtoT` — a new input `aParam` from `io.out.a.bits.param` |
| `L2_UpgradeMiss` | outer A fires with param `BtoT` | `PerfCounters`: `aFire && aParam === BtoT` |

- In the plan block's A branch, also gate the flags with `new_request.prio(0) && !new_request.control`.
- Wiring: one small MSHR output bundle (for example `io.acct`) of `Bool`s, each `WireInit(false.B)`, set
  only at the lines above, with a one-line comment naming the trigger (T2). The Scheduler adds them with
  `PopCount` (T1).

**Why these hooks are exact:**
- The plan block's A branch runs once per inner-A request (fresh, reload or repeat), exclusive with the
  search-result and destination-read branches (one `when` / `.elsewhen` chain from `:1350`).
- Outer A is armed only on the A path (`:1631`). The serve block cancels it unless `secNeedPerm`
  (`:1408-1414`). So every outer A is exactly one data miss or one upgrade miss.
- The outer A param (`:749`) is `BtoT` exactly when the L2 holds the line (`meta.hit`, which the serve block
  also sets) and the request needs T. `SourceA.scala:50` only ever sends `AcquireBlock` or `AcquirePerm`.

### 5.2 `PopCount` for every counter (T1)

Change every MSHR-fed counter input in the Scheduler from `reduce(_ || _)` to `PopCount`. Leave the
SetBalanceUnit's own inputs alone. `SBC_Aborted` may now read higher than before (§2.3) — that
is the fix, not a bug.

Also add a sim-only `assert (PopCount(…) <= 1.U)` for each pulse §2.3 marks "No" that is not asserted yet:
`secHit`, `secMiss`, `secPerm`, `secWrite`, `secC`, `secProbe`, `homeBranch`, `migAttempt`, `migCommit`. If
one fires, the analysis in §2.3 is wrong — report it (check C4).

### 5.3 `L2_StatsHold` (`0x438`, R/W, 1 bit, default 0)

- While 1, every event counter — including `L2_Cycles` — keeps its value. Clears still work.
- `SBC_Parked` is a level: **never hold it.** Dropping a +1 or −1 would make it wrong for good.
- Removed when `enablePerfCounters = false`.
- Do not hold while the sampler is recording.

### 5.4 `sbc_read.c`

- Append the new registers to `REGS[]` (append, never insert) with named `I_*` indices.
- Every read: set hold, read every register, clear hold — on every exit path. At start, if hold is already
  1, warn and clear it. Print the hold state in every dump.
- Print a terminology block:

  ```
  ACCESSES          : N
    primary hits    : N  (x.xx%)
    secondary hits  : N  (x.xx%)
    data misses     : N  (x.xx%)
    upgrade misses  : N  (x.xx%)
    hit rate        : x.xx%  (primary + secondary)
    probed hits     : N  (part of the hits)
    second searches : N  (x.xx% of accesses, x.xx% of them hit)
    in progress     : N  (accesses minus the four outcomes; 0..38 from reset, -38..38 after --zero)
  ```

- Relabel the two old hit-rate lines as legacy (for example `legacy lookup hit rate`). Board scripts only
  look for the `[SBC-WINDOW]` / `[SBC-DELTA]` tags (checked), so keep those tags and the `name=value` line.

---

## 6. Commit 2 — three counter fixes

### 6.1 `SBC_SecWrite` and `SBC_SecPerm` also count L1 write-backs

- **Cause.** TileLink reuses opcode numbers across channels: C `Release` = 6 = A `AcquireBlock`, and C
  `ReleaseData` = 7 = A `AcquirePerm`. `needT()` (`Parameters.scala:295`) cannot tell the channels apart,
  so `req_needT` (`MSHR.scala:644`) is true for a Release with param `TtoN` or `BtoN`.
- **Effect.** When a C-channel Release is served from a parked line, `secWritePulse := req_needT` (`:1390`)
  fires, and `secPerm := secNeedPerm` (`:1387`) fires if the parked entry is BRANCH.
- **The data path is not affected** (checked): the other uses of `req_needT` (`:663`, `:749`) are on A-path
  branches; on the C path the acquire is never armed, so `when (!secNeedPerm)` (`:1408`) cancels nothing;
  the serve-block probe (`:1429`) excludes `prio(2)`.
- **Fix (counting only):** gate those two pulse assignments with `request.prio(0)`. Leave the `secNeedPerm`
  wire unchanged.

### 6.2 `SBC_Aborted` also counts declines, so "attempted" never adds up

- `migAbort` pulses at `:1133` (decline: a migratable victim, no destination on offer), `:1161` (decline
  after the probe answer, `!meta.dirty`) and `:1482` (destination full after the migration started).
  `migAttempt` pulses only when a migration starts (`:1073`, `:1146`). So
  `attempted = migrations + aborted − declined`.
- **Fix:** add a `migDecline` pulse next to `migAbort` at `:1133` and `:1161` (keep `migAbort` there so
  `SBC_Aborted` keeps its meaning); count it into `SBC_Declined` (`0x430`) with `PopCount`; change
  `SBC_Aborted`'s `RegFieldDesc` to "aborted after start, or declined before start".
- Check that every path ending a started migration (`migrating := false.B`) pulses exactly one of commit
  or abort.

### 6.3 The parked-count assert can never fire

`SetBalanceUnit.scala:281-288`: the increment is clamped at `ways`, so
`assert (!parkInc || parkCount(incSet) <= ways)` always holds. It was meant to catch a commit arriving
when the count is already at `ways`. Change `<=` to `=/=`. If it then fires, `parkCount` (clamped) and
`SBC_Parked` (not clamped) drift apart — report it (check C16). Do not fix the drift in this task.

---

## 7. Commit 3 — expose `sat` and the interval sampler

### 7.1 Expose `sat`

Add a read-only output on `SetBalanceUnit` carrying the whole per-set `sat` vector. Nothing else in that
module changes.

### 7.2 `IntervalSampler` (new file)

- **Payload-agnostic.** In: a flat bundle of bits, `enable` and `interval`. It does not know what a heat
  counter is.
- **Payload:** the `sat` vector, `SBC_Parked`, the eight outcome counters, the four memory counters
  (`L2_MemReads`, `L2_MemWrites`, `L2_MemAcqPerm`, `L2_MemRelClean`) and `L2_Cycles`. On the FPGA config
  (256 sets × 5-bit heat) that is 20 words of `sat` + 14 words = 34 words per snapshot; 1,024 snapshots ≈
  2.2 Mbit of block RAM. Report the real numbers.
- **Every `interval` cycles:** latch the whole payload into a freeze register in one cycle (T9), then stream
  it into the snapshot RAM at 64 bits per cycle. Counters keep counting while it streams; the cache never
  stalls. Never sample the live counters word by word.
- **A snapshot that comes due while the previous one is still streaming is dropped and counted**
  (`SMP_Dropped`). Never overwrite silently.
- **Stop when the RAM is full.** Do not wrap.
- **Raw values**, no buckets in hardware.
- **Depth** 1,024 snapshots, a parameter.
- Its own micro-parameter (for example `enableSampler`), default `false`, plumbed through
  `WithInclusiveCache`. `require` that it is only on with `enablePerfCounters && enableSetBalancing`, with a
  clear message. Flag off → zero hardware.

---

## 8. New MMIO registers

| Offset | Register | Commit |
|---|---|---|
| `0x3F0`–`0x428` | the eight outcome counters (§3) | 1 |
| `0x430` | `SBC_Declined` | 2 |
| `0x438` | `L2_StatsHold` (R/W, 1 bit) | 1 |
| `0x440` | `SMP_Ctrl` — bit 0 enable, bit 1 reset (auto-clearing pulse) | 3 |
| `0x448` | `SMP_Interval` (cycles) | 3 |
| `0x450` | `SMP_SnapIdx` (W) | 3 |
| `0x458` | `SMP_WordIdx` (W) | 3 |
| `0x460` | `SMP_ReadData` (R, 64-bit) | 3 |
| `0x468` | `SMP_Status` — bit 0 full, bit 1 streaming | 3 |
| `0x470` | `SMP_WriteCount` | 3 |
| `0x478` | `SMP_Dropped` | 3 |
| `0x480` | `SMP_Geom` — sets, satBits, wordsPerSnap, depth | 3 |

- Everything stays below `0x1000`, which `sbc_read` already maps.
- A write pulse (the `SMP_Ctrl` reset bit) uses the fire-and-forget `RegWriteFn` form `SBC_Reset` uses:
  `ovalid` must not track `ivalid`, or the D beat never fires on real fabric (hangs on the FPGA, not in sim).

---

## 9. Software, tests and docs

### 9.1 `sbc_read`

- Commit 1: §5.4.
- Commit 3: `--smp-interval=N` (set and enable before the child runs) and `--smp-dump` (one CSV row per
  snapshot, one column per payload word). Add them to the argument loop, which rejects unknown options.
  Python decoding is not in this task.

### 9.2 Tests

- The tests are functional checks; counter values need not match older runs. For a quick sanity look, the
  last runs before this task are `sw/verilator_logs/*_006-closeout`.
- New in commit 1: a hold test (C11) and a repeat-after-flush test (C8). Copy the MMIO flush from
  `sw/serve_in_place_test.c:228` (Flush64 at `0x200`).
- Extend `migration_stress_test.c` to print the new counters and, at the end of each case, the in-progress
  value read under hold (C5).

### 9.3 Docs — in the commit that lands each register

- `CLAUDE.md`: register table rows (new registers; the rename, noting old logs say `memUpgrades=`;
  `SBC_Aborted`'s new description); the micro-parameter table (`enablePerfCounters` now means all
  measurement hardware; the sampler flag); "Can be removed in the optimization phase" — replace the
  counter and read-back rows with one row for `enablePerfCounters = false`.
- `ai-documents/guides/devmem-register-map.md`: a row per new register (width 64 for counters); the rename.
- `ai-documents/guides/cache-terminology.md` §4: mark the new counters landed and the fixes done.

---

## 10. Checks

Configs: `VerilatorRocket8KL116KL2Config` (SBC) and `VerilatorRocket8KL116KL2NoSbcConfig`. In REPORT give
each result, the command and the log path.

| # | Commit | Check | Pass condition |
|---|---|---|---|
| C1 | 0 | Move works | `migration_stress_test` 7/7, 0 asserts, both configs; `sbc_migrate_switch_test` 4/4. Every moved counter still counts: non-zero where the `006-closeout` logs show non-zero, zero where they show zero (for example every SBC counter on NoSbc). Values need not match |
| C2 | 0 | Flag off | `enablePerfCounters = false` on both configs: elaborates; the generated Verilog has no `PerfCounters` module and no counter or read-back registers; `migration_stress_test` 7/7, 0 asserts on the SBC config (its PASS checks data only, so counter lines reading 0 are expected). Do not run the switch test here — it decides PASS from counters |
| C3 | 1 | Regression | Stress test 7/7, 0 asserts, both configs; switch test 4/4 |
| C4 | 1 | One pulse per cycle | The new sim-only asserts for the "No" rows of §2.3 (§5.2) stay quiet in every run of this task. If one fires, report which |
| C5 | 1 | Access identity | At the end of each stress-test case, under hold: `L2_AccessA − (PrimaryHit + SecondaryHit + DataMiss + UpgradeMiss)` stays within 0..38 (T5). Both configs. Report min and max |
| C6 | 1 | Port identity | Under hold: `L2_DataMiss + L2_UpgradeMiss == L2_MemReads + L2_MemAcqPerm`, exactly |
| C7 | 1 | Second-search identity | `L2_SecondSearch − L2_SecondaryHit − L2_SecondaryMiss` ≥ 0 (second searches that ended as upgrade misses, plus any in progress). Report it next to `SBC_SecPerm` |
| C8 | 1 | Repeat after a flush | Directed test: flush a line over MMIO, then touch it so the MSHR takes the repeat path. `L2_DataMiss` rises; `L2_PrimaryHit` does not |
| C9 | 1 | Upgrade misses | Report `L2_UpgradeMiss` on the stress test and on bringup `matmult`. If 0, explain it with the `[SBC][elab] BRANCH reachability` line (`Scheduler.scala:611`). Do not force it |
| C10 | 1 | Probed hits | Report the value. It may be near 0 on one core. Do not force it |
| C11 | 1 | Hold | Set hold; read every counter; do 1,000 memory loads; read again: every event counter identical. Clear hold: they move again. `SBC_Parked` still tracks the real count |
| C12 | 1 | 64-bit reads | Every new counter read in `sbc_read.c`, the `sbc_mmio.h` helpers and the tests is a `uint64_t` access; every new counter row in `devmem-register-map.md` says width 64 |
| C13 | 1 | Same binary, SBC vs NoSbc | Report both `L2_AccessA` values. Expect close, not equal — SBC changes which lines are probed out of L1 |
| C14 | 2 | Write-back fix | `SBC_SecWrite` and `SBC_SecPerm` before and after the fix. After: `SBC_SecWrite ≤ SBC_SecHits − SBC_SecC` |
| C15 | 2 | Declines | Under hold: `SBC_Attempted − (SBC_Migrations + SBC_Aborted − SBC_Declined)` is 0 or 1 (one migration in flight) |
| C16 | 2 | Parked-count assert | Does the corrected assert fire? If yes, report the drift. Do not fix it |
| C17 | 3 | Sampler off | Sampler flag off: no sampler hardware in the generated Verilog |
| C18 | 3 | Sampler matches live | In a quiet moment (no traffic to that set), one set's `sat` in a snapshot equals `SBC_SetSat` read just before and just after |
| C19 | 3 | Snapshot is one instant | In every snapshot: `L2_DataMiss + L2_UpgradeMiss == L2_MemReads + L2_MemAcqPerm`, exactly |
| C20 | 3 | Sampler under pressure | A short interval gives `SMP_Dropped` > 0, and `SMP_WriteCount` stops at the depth |
| C21 | — | Area | If a synthesis report exists for the FPGA config: FF and LUT with `enablePerfCounters` on and off. If not, say so |

---

## 11. Commits

0. `SBC 005: move every monitoring counter into PerfCounters; one flag for all measurement hardware; rename L2_MemAcqPerm` — §4; C1–C2.
1. `SBC 005: outcome counters that follow the cache terminology; PopCount fan-in; L2_StatsHold` — §5, §9; C3–C13.
2. `SBC 005: counter fixes — SecWrite/SecPerm on the C channel, SBC_Declined, parked-count assert` — §6; C14–C16.
3. `SBC 005: expose sat + IntervalSampler` — §7; C17–C20.

Keep each software-header change in the same commit as its register. Fill in REPORT after each commit,
then plan the next one and wait for Damith's go.

---

## 12. Do not

- Change what feeds `sat`, or any other logic the cache uses to operate.
- Change the meaning of `L2_Accesses`, `L2_Hits` or any other existing counter — except the fixes in §6.
- Fan in counter pulses with OR after commit 0.
- Add a `lookupClass` field to `DirectoryRead`, or build a parallel `RegEnable` pipeline to carry the channel.
- Port `TLDirMonitor.scala`, `SaturationCounter.scala` or `PerfProbe.scala` from the `TL_signal_analysis`
  branch — their counters duplicate ours, and `TLDirMonitor` prints thousands of lines per cycle.
- Quantise into buckets in hardware.
- Add a heat-decay knob — it would change `sat`, which the cache runs on.
- Reorder `BankedStore` priorities.
- Add a per-core breakdown (out of scope; the payload-agnostic sampler can carry one later).
- Add `mcycle` / `minstret` reads — both trap on the board; `L2_Cycles` is the time base.
- Fix a failing check mid-run.

## 13. After this task

Merge `sbc-sampling` into `sbc-paper-aligned`, then build one FPGA image carrying tasks 006 and 005.
Damith decides when.
