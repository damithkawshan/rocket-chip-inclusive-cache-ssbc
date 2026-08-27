> ⚠️ **RETIRED / MERGED (2026-06-30).** Folded into [../phase-2.md](../phase-2.md) and
> [../bug-fix-log.md](../bug-fix-log.md). Phase 2b is **COMPLETE**. Kept for history only — do not
> treat as current.

# SBC Phase 2b — Migrate-on-Eviction with Clean-Destination Eviction — Handoff

**Status: RESOLVED (2026-06-25).** Phase 2a and 2b are complete and verified. 1 migration
committed, data correct, `s_verify` quiet (mismatch=0). Two bugs were confirmed and fixed:
**Bug A** (preferEvictable not wired for dread path) and **Bug B** (copy_wsafe WaR race).
This document is preserved as the historical debug record. For current state see
[phase-2.md](phase-2.md) and [diagram.md](diagram.md).

**Audience:** a fresh agent (the "coder") working with the "thinker" agent that makes design
rulings. Read this top-to-bottom before touching the migrate datapath. Cross-reference
[phase-2.md](phase-2.md), [phase-1.md](phase-1.md), [SBC_integration_plan.md](SBC_integration_plan.md).

---

## 1. Where we are (one paragraph)

Migration now lives **inside the demand-miss eviction**. On a miss to a *hot* set, the demand MSHR
migrates its clean, client-free, non-displaced victim to a *cold* set instead of releasing it, then
refills the freed way with the demanded line. **2a** (migrate only into an INVALID/free destination
way) elaborated, ran, and produced correct data — but the cold set is structurally always full, so
it logged **0 commits / 13 ABORT-DST**. **2b** widens the destination acceptance to also evict a
**clean** destination way (silent overwrite, no writeback/probe), which is what finally exercises
the full datapath (copy → dir-write #1 → refill → dir-write #2 → retire) **for the first time**.
That first real run **deadlocks**: CPU wedges mid-migration, no `EVICT-DST`/commit printf, hangs
during startup at `DIR-EVICT set=1 evictableAvail=1`.

---

## 2. The migrate datapath (what a successful 2b migration must do)

For a demand A-channel miss to a hot set with a clean victim at `(request.set, migSrcWay)`:

1. **1st dir-read (demand eviction read).** Scheduler sets `preferEvictable` so the directory
   picks a clean victim way. Result lands in the MSHR request-setup; the A-channel branch detects
   "miss + valid victim + hot advice + eligible victim" → sets `migrating := true`,
   `migDstSet := advice`, `migSrcWay := victim way`, clears `s_dread`/`w_dread`, pulses `migAttempt`.
2. **2nd dir-read of the destination set (`dread`).** MSHR drives `dread` (set = `migDstSet`,
   `preferInvalid=true`, `preferEvictable=true`). Result handled by the
   `when (io.directory.valid && migrating && !w_dread)` block:
   - **Accept** (dst way INVALID **or** clean/client-free/non-displaced): latch `migDstWay`, clear
     `s_copy`, `w_copy`, `s_dmeta`, `s_writeback` → arm copy + both dir-writes. Print `EVICT-DST`
     if the accepted way was non-free.
   - **Abort** (dst way is dirty or has clients or displaced): `migrating := false`, pulse
     `migAbort`, clear `s_release`/`w_releaseack` → fall back to a *normal* eviction. Print `ABORT-DST`.
3. **Copy.** `copy.valid := migrating && !s_copy` starts the SCU, which moves the block from
   `(srcSet, migSrcWay)` to `(migDstSet, migDstWay)`. SCU FSM:
   `s_idle → s_read → s_write → s_verify → s_done`. `s_done` pulses `io.done` → MSHR `w_copy := true`.
4. **dir-write #1 (`mig_dir1 = migrating && !s_dmeta && w_copy`).** Installs the **displaced** entry
   at `(migDstSet, migDstWay)` (`displaced := true`). Sets `s_dmeta`.
5. **Acquire + refill.** The demand Acquire fetches the new line; grant refills
   `(request.set, migSrcWay)`. The **A2 interlock** is supposed to hold the Acquire until the copy
   read is done so the refill can't clobber the victim before the SCU reads it.
6. **dir-write #2 (`!s_writeback && no_wait && mig_ready`).** The ordinary demand refill rewrites
   the freed home way `(request.set, migSrcWay)` with the new line, then the MSHR **retires** and
   pulses `migCommit` → SBU `commit{MIGRATE}` → AT update + migration counter.

---

## 3. Files touched & exact code state

All paths under `design/craft/inclusivecache/src/`.

### MSHR.scala — protocol FSM (has the deadlock)
- **Migrate scoreboard regs** (~L163-176): `s_writeback`, `s_dmeta` (dir-write #1), `s_copy`,
  `w_copy`, `migrating`, `migDstSet`/`migDstWay`/`migSrcWay`, `s_dread`, `w_dread`,
  `migAdviceValidReg`, `migAdviceDstReg`. Plus pulse outputs `migAttempt`/`migAbort`/`migCommit`.
- **Schedule gates** (~L233-260):
  - `no_wait = w_rprobeacklast && w_releaseack && w_grantlast && w_pprobeacklast && w_grantack && w_copy`
  - `mig_dir1 = migrating && !s_dmeta && w_copy`
  - `mig_ready = !migrating || (s_dmeta && w_dread)`
  - `a.valid := !s_acquire && s_release && s_pprobe && (!migrating || w_copy)`  ← **A2 interlock, see BUG-A below**
  - `dir.valid := (!s_release && w_rprobeackfirst) || (!s_writeback && no_wait && mig_ready) || mig_dir1`
  - `copy.valid := migrating && !s_copy`; copy.bits src=`(request.set, migSrcWay)`, dst=`(migDstSet, migDstWay)`
  - `dread.valid := migrating && !s_dread`; `dread.bits.set := migDstSet`, `preferInvalid := true`,
    `preferEvictable := true`  ← **but Scheduler does NOT forward preferEvictable, see BUG-B below**
- **Schedule completions** (~L270-295): `when (io.schedule.ready)` advances **all** eligible bits in
  one shot: `when (s_release && s_pprobe) { s_acquire := true }`, `when (migrating && !s_dread) { s_dread := true }`,
  `when (migrating && !s_copy) { s_copy := true }`, `when (mig_dir1) { s_dmeta := true }`,
  `when (no_wait && mig_ready) { s_writeback := true }`, retire `when (no_wait && mig_ready) { request_valid:=false; meta_valid:=false; migCommit:=migrating; migrating:=false }`.
- **dir-write mux** (~L408-413): `set := Mux(mig_dir1, migDstSet, request.set)`,
  `way := Mux(mig_dir1, migDstWay, meta.way)`,
  `data := Mux(mig_dir1, displacedEntry, Mux(!s_release, invalid, final_meta_writeback))`.
- **copy_done handler** (~L606): `when (io.copy_done) { w_copy := true }`.
- **2nd dir-result accept/abort handler** (~L647-674): the `dstFree || dstEvictable` block (2b).
- **A-channel migrate gate** (~L749-760): inside the request-setup `.otherwise` (A) branch, under
  `when (!new_meta.hit && new_meta.state =/= INVALID)`: `migEligible = !dirty && !clients && !displaced`;
  when `enableSetBalancing && migAdviceValidReg && migEligible` → start migrate; else normal eviction.
- **s_acquire clearing** (~L772): the normal acquire block `when (!new_meta.hit || (BRANCH && needT))`
  sets `s_acquire := false` etc. The migrate path also flows through this (it's a miss) so
  `s_acquire`/`s_writeback` are cleared — confirmed.
- **WATCHDOG-MSHR** (just added, before module close, gated on `sbcDebug`): heartbeat dump of all
  `s_*`/`w_*` bits while `migrating`, every 4096 cycles.

### Scheduler.scala — arbitration
- `mshr_request` (~L118-128): gates schedule firing on resource readiness (sourceA..X ready,
  `directory.io.write.ready`, `setCopyUnit.io.idle`). **No explicit term for the `dread` directory
  READ port** (read port assumed always ready). `m.io.schedule.ready := sel` (selected MSHR).
- `m.io.copy_done := setCopyUnit.io.done && setCopyUnit.io.doneId === i.U`.
- SCU start wiring (~L388-394): `setCopyUnit.io.start.valid := schedule.copy.valid`, bits from
  `schedule.copy`, `mshrId := mshr_select`. BankedStore SCU ports `sourceCopy_radr/rdat/wadr/wdat`.
- `directory.io.read.bits.preferInvalid := mshr_uses_directory_for_dread`
- `directory.io.read.bits.preferEvictable := alloc_uses_directory && adviceMigrate`
  ← **does not forward the dread's own preferEvictable (BUG-B).**
- Q1 gate (~L187-196 + ~L434-461): `dstSetConflict` (incoming request whose set matches a live
  `dstSet` *or* the pending token set), `dstSelfCollision`, `dstSetOwned`, one-migration token
  `migTokenPending`/`migTokenDstSet`/`migPendCtr` (bounded fence, releases on owner-dstValid or
  timeout). `adviceMigrate := isDemandA && migrateResp.migrate && !anyMigrating && !migTokenPending && !dstSelfCollision && !dstSetOwned`.
- `commit{MIGRATE}` via `Mux1H(migCommit, ...)`.
- **WATCHDOG-SCHED** (just added, gated on `sbcDebug`): heartbeat dump of
  `migTokenPending/migTokenDstSet/migPendCtr/anyMigrating/pendingOwnerLive` while a token or
  migration is live.

### SetCopyUnit.scala — data mover
- FSM `s_idle::s_read::s_write::s_verify::s_done`. `rdat_valid = RegNext(RegNext(io.bs_radr.fire))`
  (BankedStore 2-stage read pipeline).
- `s_read`: `io.bs_radr.valid := io.copy_safe` (RaW gate), reads `(srcSet,srcWay)` beats into `blockBuf`.
- `s_write`: `io.bs_wadr.valid := io.copy_wsafe` (WaR gate), writes `blockBuf` to `(dstSet,dstWay)`.
- `s_verify` (**new in 2b**): re-reads `(dstSet,dstWay)` with `io.bs_radr.valid := true.B`
  **unconditionally (no copy_safe gate)**, asserts `bs_rdat.data === blockBuf(vrDatBeat)`.
- `s_done`: pulses `io.done`, prints `SetCopyUnit: copy done ...`, → `s_idle`.
- **WATCHDOG-SCU** (just added, gated on `sbcDebug`): heartbeat dump of `state`, all beat counters,
  `copy_safe`/`copy_wsafe`, `bs_radr.ready`/`bs_wadr.ready` while off-idle.

### SourceD.scala — interlocks (NOT edited)
- `io.copy_safe` (~L397-401): false iff a SourceD pipeline stage (s1-s4) matches `copy_req.set/way`
  (= srcSet/srcWay). Combinational hazard vs SourceD's own pipeline only — **not** vs SinkD refill.
- `io.copy_wsafe` (~L405-409): false iff a stage matches `copy_wreq.set/way` (= dstSet/dstWay).

### Directory.scala — primitives (NOT edited)
- `evictableOH = Cat(ways.map(w => w.state =/= INVALID && !w.displaced && !w.dirty && !w.clients.orR))`
- `victimWayOH = Mux(preferInvalid && invalidWayOH.orR, PriorityEncoderOH(invalidWayOH),
   Mux(preferEvictable && evictableOH.orR, PriorityEncoderOH(evictableOH),
   Mux(lfsrVictimOH.orR, lfsrVictimOH, PriorityEncoderOH(nonDisplacedOH))))`
- `displaced` bit excludes migrated-out lines from hit detection (~L163).
- `DIR-EVICT` printf (~L181-185) fires only on `ren2 && preferEvictable`.

### set_migration.c — test
- `HOT_SET=5, COLD_SET=0, NHOT=6, NCOLD=4, BURST=1500`. Fills COLD_SET with 4 clean lines (no
  INVALID way → forces 2b evictable path), hammers HOT_SET, every 16th iter touches COLD_SET to
  keep it cool. Helpers `set_addr(s,t)`, `hot_addr(t)`, `cold_addr(t)`. **RE-READ before editing —
  it was hand-edited after the last agent change.**

---

## 4. The deadlock — hypotheses and resolution ✅

> **RESOLVED.** The two bugs below (BUG-B and the copy_wsafe race) were confirmed and fixed.
> BUG-A (A2 interlock hypothesis) was NOT the root cause. Suspects C–E were not reached.

### BUG-A (HYPOTHESIS — NOT THE ROOT CAUSE) — A2 interlock is not actually armed
`w_copy` is `RegInit(true.B)`. The acquire gate is `(!migrating || w_copy)`. Between the 1st
dir-result and the 2nd, `w_copy` is still its default `true`, so **the Acquire fires early — in the
same schedule as the dread** — before the SCU reads the victim. In 2a this was harmless (always
aborted, no copy ran). In 2b the memory refill of `(request.set, migSrcWay)` can land **before**
the SCU copy-read → the SCU copies the *new* line, not the victim (silent corruption), and the
ordering assumptions behind the copy/refill interlock break.
- **Confirm via WATCHDOG-MSHR:** `s_acquire=1` appears *before* `w_dread=1`.
- **Candidate fix:** change the gate to `(!migrating || (w_dread && w_copy))` so the Acquire is held
  for the entire migrate setup (through the dread *and* the copy), released only once
  `w_copy` rises after the copy completes. **Caveat:** the completion
  `when (s_release && s_pprobe) { s_acquire := true }` fires on **any** `schedule.ready`,
  regardless of `a.valid`. If the gate truly holds `a.valid` low while the MSHR fires *other* lanes
  (dread/copy/dir1), this completion will mark `s_acquire := true` **without SourceA ever sending
  the Acquire** → a *different* deadlock (grant never arrives). So the fix must **also** guard that
  completion, e.g. `when (io.schedule.bits.a.valid) { ... s_acquire := true }` (or gate the
  `s_acquire` set on the actual a-lane fire). Verify against the baseline (non-migrate) path that
  this still advances `s_acquire` exactly when the Acquire is sent.

### BUG-B — ✅ CONFIRMED + FIXED — Scheduler ignores the dread's `preferEvictable`
MSHR sets `dread.bits.preferEvictable := true`, but Scheduler hardwires
`preferEvictable := alloc_uses_directory && adviceMigrate`, which is **false** during a dread. So
the destination read uses `preferInvalid` only; with the cold set full it returns an **LFSR** victim
among non-displaced ways. The 2b accept then treats it as evictable iff it happens to be clean.
This is non-deterministic and can pick a dirty/client way → spurious aborts even when a clean way
exists elsewhere in the set.
- **Fix:** forward the dread's preference:
  `preferEvictable := Mux(mshr_uses_directory_for_dread, schedule.dread.bits.preferEvictable, alloc_uses_directory && adviceMigrate)`.
- Not necessarily the *hang*, but required for 2b to select the destination way deterministically.

### ✅ ACTUAL DEADLOCK ROOT CAUSE — WaR `copy_wsafe` stuck false (confirmed as #2 in phase-2.md)

An MSHR that retired from set `d` may have SourceD still draining GrantData beats from
`(dstSet, dstWay)` after `status.valid=false`. The Q1 guard passes (valid=false → not owned),
migration fires, SCU enters `s_write` for that same slot — but `copy_wsafe=false`. SCU stuck
forever → `w_copy` never pulses → CPU hangs. **Fix:** SCU pre-checks `copy_wsafe` before
entering `s_write`. SourceD.scala:405 checks all 4 pipeline stages (s1–s4). Once all clear,
write proceeds. Confirmed working: WSAFE-STALL=0 in the fixed run.

### Suspect-C (NOT REACHED) — downstream guard keyed on `state===INVALID`/`dstFree`
A guard that only handles the free-destination case while the widened accept also allows the
evictable case → the evictable path lands in a state with no exit. The accept handler itself treats
`dstFree`/`dstEvictable` identically, so if this exists it is **downstream** (dir mux, mig_dir1,
no_wait). Audit every `when (... === INVALID)` / `dstFree` after the accept point.

### Suspect-D — `s_verify` starved on the lowest-priority BankedStore read port
`s_verify` issues reads with no `copy_safe` gate but still depends on `bs_radr.ready` (copy read
port is added last → lowest arbitration priority). If starved, `io.done` never pulses → `w_copy`
never set → `mig_dir1` never fires → never retires.
- **Confirm via WATCHDOG-SCU:** stuck in `state=s_verify` with `radr_ready=0`.
- **Possible mitigation:** if verify proves problematic, gate it behind a separate debug flag or
  remove it once copy correctness is established (it was always intended as a temporary self-check).

### Suspect-E — interlock circular wait / fence on own writes
- Confirm the A2 refill-write isn't circularly waiting on a port the refill holds (BUG-A fix should
  resolve ordering).
- Confirm `dstSetConflict`/`pendingDstValid` does **not** block the migrate MSHR's *own* SCU write /
  dir-write to `migDstSet`. The fence only gates incoming `request.ready` (Sink requests); the SCU
  write goes through the BankedStore copy port and the dir-writes go through the MSHR schedule —
  neither routes through `request` — so it *should* be safe, but verify.
- Confirm `migPendCtr` releases on the **evict** path (proven on the hit path, the evict path differs).

---

## 5. Watchdog instrumentation (just added — how to read it)

All three are heartbeat-style (fire every 4096 cycles while their condition holds) and gated behind
`params.micro.sbcDebug` (zero hardware when off).

- **WATCHDOG-MSHR** ([MSHR.scala]) — fires while `migrating`. Dumps every `s_*`/`w_*` bit +
  `migDstSet/migDstWay/migSrcWay`. **Read order:** is `s_acquire` set before `w_dread`? (→ BUG-A).
  Is `w_copy` ever set? (→ copy never finished). Is `s_dmeta`/`s_writeback` stuck? (→ dir-write
  never fired).
- **WATCHDOG-SCU** ([SetCopyUnit.scala]) — fires while off-idle. Dumps `state` + beat counters +
  `copy_safe`/`copy_wsafe` + `radr_ready`/`wadr_ready`. **Decision tree:**
  - stuck `s_read`, `copy_safe=0` → SourceD hazard (BUG-A early-acquire fallout)
  - stuck `s_read`/`s_verify`, `radr_ready=0` → BankedStore port starvation (Suspect-D)
  - stuck `s_write`, `copy_wsafe=0` → WaR hazard on dstSet
  - stuck `s_verify` → Suspect-D
- **WATCHDOG-SCHED** ([Scheduler.scala]) — fires while a token/migration is live. Dumps
  `migTokenPending/migTokenDstSet/migPendCtr/anyMigrating/pendingOwnerLive`. `migTokenPending=1`
  with no owner and `migPendCtr` not draining → Suspect-E fence stuck.

**The first watchdog triplet pins the stuck stage.** Fix the indicated bug, re-elaborate, re-run.

---

## 6. Build / run / inspect

```bash
cd ~/Research/repos/chipyard_performance_eval/chipyard && source env.sh
bash generators/rocket-chip-inclusive-cache/sw/compile_app.sh set_migration
cd sims/verilator
make -j20 run-binary-debug \
  BINARY=../generators/rocket-chip-inclusive-cache/sw/build/set_migration.riscv \
  CONFIG=VerilatorRocket8KL116KL2Config \
  VERILATOR_THREADS=18 "SIM_FLAGS=+max-cycles=100000000"
```
Config geometry: L2 = 8 sets × 4 ways × 64 B, 1 bank, `sbcAutoMigrate=ON`, `sbcDebug=ON`.
L1 D$ = 2 sets × 2 ways × 64 B. Logs land under `sw/verilator_logs/phase_002_2b/`.

Grep the log for: `WATCHDOG-`, `EVICT-DST`, `ABORT-DST`, `DIR-EVICT`, `SetCopyUnit: copy done`,
`copy verify mismatch`, `assert`.

---

## 7. Success criteria for 2b (definition of done)

1. No deadlock — CPU completes the test.
2. `EVICT-DST` + `SetCopyUnit: copy done` + commit printfs appear; `SBC_Migrations > 0` (MMIO 0x328).
3. `s_verify` quiet (no "copy verify mismatch" assert) → copy data correct.
4. Test data check PASSES (both hot and cold regions verify).
5. No BUG-2 illegal-inner-`D` asserts (removed by construction in Phase 2 — migration is the demand
   MSHR's own work, no second requester).
6. A trailing `ABORT-DST` once COLD_SET fills with **displaced** lines is **expected** (Q4 ceiling).

---

## 8. Explicitly deferred (do NOT implement now — thinker rulings)

- **Q4** displaced-line recycle / evict-a-displaced-line to make room → Phase 3/4.
- **Q5** DSS occupancy accounting → later.
- **Full/dirty destination eviction** (writeback or probe on the destination) → Phase 3/4. 2b is
  **clean-only** by design.
- The Phase-1 standalone injection path (SBU `migrateReq`, SinkX inject, `migInFlight` throttle) is
  **dead code to delete** — migration is now the demand MSHR's own work. (BUG-2 "drop on busy" patch
  was rejected; do not implement it.)

---

## 9. Design rationale (why it's built this way)

- **Migration folded into the demand-miss eviction (Phase 2 core).** Phase 1's standalone injected
  migrate trigger created a *second* requester that could nest into a live demand MSHR and emit an
  illegal inner `D` (BUG-2). Making migration the demand MSHR's **own** work removes the second
  requester by construction — no nesting, no extra coherence actor.
- **Displaced bit + dual dir-write.** The victim must remain *findable for coherence* at its new
  home but *invisible to demand hits* (its address-derived set no longer matches its physical set).
  The `displaced` bit excludes it from hit detection; dir-write #1 installs it at the destination,
  dir-write #2 frees the home way. Invariant preserved: **physical set == address-derived set** for
  all *non-displaced* lines; displaced lines are the controlled exception, reconstructed via
  `expandAddress(tag, physicalSet)`.
- **Clean-only destination eviction (2b).** Evicting a *clean, client-free, non-displaced* dst way
  is coherence-identical to a normal silent clean-victim eviction (no writeback, no probe), so it
  needs **no** new coherence machinery — the cheapest way to make room and exercise the datapath.
  Dirty/clients/displaced destinations are deferred precisely because they *would* need writeback /
  probe / recycle logic.
- **One-migration token + dst fence (Q1 gate).** Guarantees a **single writer per directory set**
  for the whole migration window: `anyMigrating` (≤1 live reservation) + `migTokenPending` covers
  the allocate→dstValid handoff gap + `dstSetConflict` stalls any incoming request to a reserved
  set. `dstSelfCollision`/`dstSetOwned` prevent pathological self/aliased destinations. The
  `migPendCtr` bound prevents a stuck fence on the demand **hit** path (advice latched but no
  eviction → `dstValid` never rises).
- **A2 copy↔refill interlock.** The home way is read by the SCU (victim) and written by the refill
  (new line). The Acquire must be held until the copy read completes so the refill can't clobber
  the victim first. (Currently mis-armed — see BUG-A.)
- **`s_verify`.** A temporary self-check that the copied block actually landed correctly in the new
  way; intended to be removed/flagged once correctness is established.
- **Everything gated behind `enableSetBalancing`/`sbcDebug`.** Hard project constraint: baseline
  (SBC off) must be **bit-exact** with upstream — no synthesizable state and no printfs when off.

---

## 10. Immediate next action for the resuming agent

1. Confirm the three watchdogs compiled (they do — `get_errors` clean) and the user has run the
   deadlock case. Read the **first** `WATCHDOG-*` triplet in the log.
2. Map it to §4/§5 → identify the stuck stage.
3. If BUG-A: apply the gate fix **and** guard the `s_acquire` completion (see the caveat — do not
   regress the baseline acquire path).
4. Apply BUG-B (forward the dread `preferEvictable`) regardless — it's needed for deterministic dst
   selection.
5. Re-elaborate (errors clean), have the user re-run, check §7 success criteria.
6. Once 2b commits cleanly: turn the `s_verify` self-check from a hang risk into a quiet check
   (or remove), then proceed to 2b dst-eviction edge cases and Step 7 (AT commit) per
   [phase-2.md](phase-2.md).
