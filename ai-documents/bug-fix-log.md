# SBC — Bug-Fix Log (by phase)

**Branch:** `set_migration_refactored`
**Updated:** 2026-06-30
**Scope:** Every bug we have hit building SBC, grouped by the phase it was found in — cause, fix, and
status. This is the consolidated record so nobody has to re-read the phase docs to know "what did we
already fix?" Newest understanding wins.

Legend: ✅ FIXED & verified · 🟡 mitigated / disabled (known, deferred) · 🔴 OPEN.

Quick index:
- **Phase 0** — none (observation only).
- **Phase 1** — BUG-1 (premature retire), BUG-2 (second-requester nesting).
- **Phase 2** — Bug A (preferEvictable wiring), Bug B (copy_wsafe race), Dst-collision (illegal
  inner-D), Displaced accumulation (bricked set); plus `s_verify` disabled, Q3 rejected.
- **Open** — residual `[born→gate]` sub-window (latent, do not pre-build).

---

## Phase 0 — observation
No bugs. Saturation counters, DSS, and the MMIO read-back map were added with migration always
`false`; nothing in the datapath changed.

---

## Phase 1 — migration primitives

### ✅ BUG-1 — illegal outer `AcquirePerm` (premature migrate-retire)
- **Symptom:** the L2 emitted an illegal outer `AcquirePerm`; TLMonitor flagged it.
- **Cause:** the migrating MSHR retired before its 2nd directory read returned, letting the demand
  path issue an acquire in a state the protocol forbids.
- **Fix:** hold retire until dir-write #1 is out **and** the 2nd dir-read is back —
  `mig_ready := !migrating || (s_dmeta && w_dread)`
  ([MSHR.scala:249](../design/craft/inclusivecache/src/MSHR.scala#L249)).
- **Status:** ✅ verified; carries into Phase 2 unchanged.

### ✅ BUG-2 — second requester nests into a live demand MSHR → illegal inner-D
- **Symptom:** the Phase-1 *standalone injected* migration allocated its own MSHR and nested into a
  busy set's live demand MSHR, corrupting its grant → illegal inner-D.
- **Cause:** Phase 1 fired migration as a **second requester** on the source set. Two owners of one
  set is the whole problem.
- **Fix:** **removed by construction in Phase 2** — migration became the demand MSHR's own eviction
  work, so there is nothing to nest into on the source side. (The "drop-on-busy" patch was rejected as
  throwaway; never implemented.)
- **Status:** ✅ closed for the migration *source*. ⚠️ The destination side is a genuine second
  requester — that re-surfaced as the Phase-2 dst-collision bug, now also fixed (below).

---

## Phase 2 — migrate-on-eviction

### ✅ Bug A — `preferEvictable` not wired for the 2nd dir-read (dread) path
- **Symptom:** clean evictable ways existed in the destination set, yet almost every migration hit
  ABORT-DST → ~0 commits.
- **Cause:** `preferEvictable` was driven only from the *alloc* dir-read condition, so during the
  migration's *2nd dir-read* it was forced false. The directory fell back to LFSR, usually returned a
  dirty/client-held way, and the MSHR aborted.
- **Fix:** OR the dread path into `preferEvictable` so the 2nd dir-read actually requests an evictable
  way. First real commits appeared after this.
- **Status:** ✅ verified.

### ✅ Bug B — `copy_wsafe` WaR race (recently-retired MSHR still draining SourceD)
- **Symptom:** CPU wedged mid-migration in the copy engine's `s_write`; no commit printf, no assert.
- **Cause:** an MSHR for `dstSet` had retired its slot (`status.valid=false`) but its SourceD
  GrantData pipeline was **still reading `(dstSet,dstWay)`**. The copy engine entered `s_write` to that
  same slot; `copy_wsafe` (WaR) stayed false forever → SCU stuck → A2 interlock kept the outer acquire
  gated → CPU never granted.
- **Fix:** the SCU **pre-checks `copy_wsafe` before entering `s_write`** (all SourceD pipeline stages)
  via the dedicated `s_wsafe` state. It waits for the drain instead of deadlocking.
- **Status:** ✅ verified — `WSAFE-STALL=0`.
- **⚠️ REGRESSED AND RESTORED (2026-08-17).** An uncommitted "remove Phase-2 debug scaffold" pass on
  `SetCopyUnit.scala` deleted the `s_wsafe` state along with the printfs it contained — silently
  reverting this fix. `s_write` still gated on `copy_wsafe`, so the FSM could enter `s_write` and stall
  there indefinitely while the owning MSHR's A2 interlock held the outer Acquire: the exact original
  deadlock. Caught during Phase-2 sign-off review, **before** the regression run. Restored in commit
  `a2975d6`, with the rationale moved onto the state itself as a `DO NOT REMOVE` comment.
  **Lesson:** the fix lived only as a state whose justification sat inside deleted debug code, so it
  read as debug cruft. Load-bearing states need their "why" attached to the state, not to the logging.

### ✅ Dst-set collision — illegal inner-D (independent demand lands on a migration's destination)
- **Symptom:** TLMonitor "D channel acknowledged for nothing inflight" — a demand allocated an MSHR on
  a set that was simultaneously a live migration's **destination**; dir-write #1 trampled the demand's
  directory entry → it emitted a protocol-illegal inner-D.
- **Cause (the blind spot):** the original fence checked only `request.ready` (acceptance — the "front
  door"). The collider was *accepted* ~13 cycles **before** the migrant existed, waited queued, and
  **allocated** *after* the migrant raised `dstValid` — but the code never re-checked at allocation. Two
  earlier abort-based patches (single dread-result sample; `dstCollisionSeen` acceptance-watch latch)
  both failed for the same reason: they watched the wrong moment.
- **Fix — allocation-side fence:** move the check to **allocation**, the step that actually creates the
  second owner. `allocReady = alloc && !dstSetConflict`, routed into **both** allocation points — the
  alloc dir-read ([Scheduler.scala:297](../design/craft/inclusivecache/src/Scheduler.scala#L297)) and
  the MSHR allocate ([Scheduler.scala:337](../design/craft/inclusivecache/src/Scheduler.scala#L337)).
  The collider re-passes the fence at allocation, by which time `dstValid` is up → held in the queue
  until the migration retires. Bounded, acyclic, deadlock-free — the cache's existing
  one-owner-per-set rule applied to migration. The **opposite** of the rejected "make the migration
  wait" idea.
- **Status:** ✅ **SHIPPED & VERIFIED.** Forced repro `dst_collision_repro` PASSES (crashed every run
  before; now data correct). **Stock-config regression PASSED 2026-08-17** — 0 asserts, no illegal
  inner-D, destinations spread across sets 0/3/7. Fully closed.

### ✅ Displaced-line accumulation → bricked set
- **Symptom:** [Directory.scala:156](../design/craft/inclusivecache/src/Directory.scala#L156)
  `assert(PopCount(victimWayOH) === 1)` fires; the set can no longer pick a victim. Under the forced
  config it reproduced in ~8 migrations.
- **Cause:** a migrated-out line is parked `displaced` in `d`. Displaced ways were excluded from
  **hits** *and* **all four victim tiers** → immortal in Phase 2 (no reclaim path). Migrations kept
  converting `{invalid|evictable}` ways → `displaced`; when all ways were displaced, `nonDisplacedOH=0`
  → `victimWayOH=0` → assert.
- **Key safe invariant:** every displaced way is **clean + client-free by construction**
  ([MSHR.scala:384](../design/craft/inclusivecache/src/MSHR.scala#L384)) → it can be **silently
  dropped** (no writeback, no probe).
- **Fix — last-resort reclaim (two minimal edits, two files):**
  - `Directory.scala` — a **5th, lowest-priority victim tier**: `displacedOH = ~nonDisplacedOH`, taken
    only when no native way exists. Victim order: invalid → evictable-native → LFSR-native →
    **displaced**.
  - `MSHR.scala` — (1) a displaced victim is **silently dropped** (`.elsewhen (new_meta.displaced)` in
    the eviction block: no Release, no probe) — a Release would carry the wrong address (displaced
    line's address maps to a different set); the demand refill still installs the new line.
    (2) the displaced-victim **assert was narrowed** from "no release *or* writeback" to "**no
    release**" — the writeback IS the reclaim.
  - **Why two files:** victim *selection* is in Directory; the *release-vs-drop* disposal is in MSHR.
  - **Baseline-exact, no config flag:** SBC off ⇒ nothing is ever `displaced` ⇒ both branches
    unreachable.
  - **Accepted caveat (user-approved):** a set at 7/8 displaced recycles its one native way (hot narrow
    set) until Phase-3 spreading/reclaim — no crash, data correct.
  - **Phase-3 seam:** on reclaim the AT entry for `(dstSet,dstWay)` goes stale; harmless in Phase 2 (AT
    not consumed for hits) but Phase 3 must invalidate it.
  - **Rejected alternative:** the "abort migration if dst would fill" throttle — stops the brick but
    leaves the set clogged and is throwaway.
- **Status:** ✅ **FIXED & VERIFIED.** Forced torture config (all migrations → set 0) runs 20000 iters,
  PASS, 0 asserts, 10 migrations committed, reclaim fired 3×, data correct.

### 🟡 `s_verify` disabled — copy self-check starves on the lowest-priority read port
- **What:** `s_verify` re-read `(dstSet,dstWay)` to assert the copy landed. It reads on the SBC copy
  *read* port — **rank 7 (dead last)** in BankedStore priority ([priority-orders.md](priority-orders.md)
  **F**). Under load it starved → deadlock.
- **Decision:** **disabled** — falls through to `s_done`. Copy correctness holds by write-through.
  Returns in Phase 3 behind a non-starvable read path.
- **Status:** 🟡 intentionally off. Not a correctness hole.

### ✅ Test-coverage defect — six of seven stress cases were disabled
- **Symptom:** every Phase-2 "PASS" (including the 20000-iter torture result) reported exactly one
  case: `case_bankstore_saturation`. Coverage was **1/7**, not 7/7.
- **Cause:** `sw/migration_stress_test.c` `main()` had six `ok &= case_*()` lines commented out —
  presumably narrowed during bug hunting and never restored. The pass banner still read
  "all migration corner cases data-correct", so the summaries looked complete.
- **Impact:** the paths *not* covered were exactly the ones sign-off cares about — free dst (2a), full
  clean dst (2b), dirty-victim skip, ABORT-DST, **displaced re-read**, and **RaW/WaR interlock** (the
  Bug-B area, i.e. the very fix that had also been reverted).
- **Fix:** all seven re-enabled; `case_bankstore_saturation` kept last (slowest). All seven now PASS.
- **Status:** ✅ closed 2026-08-17. **Lesson:** a self-reported "all cases pass" banner is not
  coverage — check which cases actually executed.

### 🟡 Q3 — "could the copy port's low priority deadlock the copy itself?" → REJECTED
- **Result:** stress-tested — **442 migration attempts, 0 arbitration stalls**. Low priority is
  bounded delay, not deadlock. **Do NOT reorder the BankedStore priorities** (load-bearing for protocol
  deadlock-freedom).
- **Status:** 🟡 closed — do not relitigate.

---

## 🔴 Open bugs (must not be forgotten)

### Residual `[born → gate]` sub-window (do NOT pre-build)
- **What:** the migrant reserves `dstSet` only at its *gate* (`dstValid`), a few cycles after the MSHR
  is born. A request allocating in that tiny window could in principle still slip past the fence. The
  shipped fix catches the observed bug (the collider allocated *after* the gate).
- **Decision:** **do not pre-build.** Only if a `[born→gate]` slip is actually reproduced do we reserve
  from the latched advice (bounded by the migration-token timeout).
- **Status:** 🔴 latent / unobserved. The passing repro suggests it isn't being hit.
- **⚠️ Keep the repro tooling (2026-08-18).** `sbcGateStallCycles` (`MSHR.scala` L221–L231) exists
  precisely to widen this window, so it is the only instrument that can reproduce this bug. Cleanup
  Batch D originally proposed deleting it on the grounds that "the dst-collision bug is closed" — that
  call is **reversed**; see [code-cleanup-suggestions.md](code-cleanup-suggestions.md) → Batch D.
  Exposure is about to rise sharply: if probe-then-migrate raises `p`, migration goes from 9 per run
  to thousands, and every migration opens a destination-fence window.

---

## 🔴 Found in 003 (pre-existing, not caused by this task)

### 🔴 P1 — C-channel head-of-line deadlock (exists today)
- **What:** `secValid` folds into `dstSetConflict` ([Scheduler.scala:237](../design/craft/inclusivecache/src/Scheduler.scala#L237))
  which gates `request.ready` ([Scheduler.scala:366](../design/craft/inclusivecache/src/Scheduler.scala#L366)).
  A client `Release` addressed to a fenced partner set blocks the head of the C channel. If that same
  client's `ProbeAck` is queued behind it, the cache deadlocks: the fence only lifts when the migration
  completes, and the migration waits on the `ProbeAck`.
- **Reachable?** Rocket arbitrates its probe unit and its writeback unit onto one C channel, so a
  `ProbeAck` can genuinely sit behind a `Release`.
- **Fix (003 Stage 1):** exempt `prio(2)` from the fence at `:366` only —
  `!(dstSetConflict && !request.bits.prio(2))`. Safe because a `prio(2)` request never evicts
  ([MSHR.scala:1129-1144](../design/craft/inclusivecache/src/MSHR.scala#L1129-L1144) has no eviction
  branch and asserts `new_meta.hit`), so it creates no victim-selection hazard. The full condition
  stays on `allocReady` (`:239`).
- **Status:** 🔴 open, pre-existing. Found by reading during 003 planning, not by a run.

### 🔴 P2 — C/X requests for displaced lines
- **What:** a client voluntarily releasing a parked line allocates on its *home* set, reads that row,
  misses (the line is not there — it is parked in the partner), and trips
  `assert(new_meta.hit)` ([MSHR.scala:1143](../design/craft/inclusivecache/src/MSHR.scala#L1143)).
  The same hole exists on the X channel: an MMIO flush of a displaced line is a **silent no-op** today,
  and becomes **data loss** once displaced lines are allowed to be dirty (003 Stage 2).
- **Fix (003 Stage 4):** arm the secondary search for the C-channel and X-channel plan branches, not
  only the A-channel branch. MMIO flush stays a documented unsupported constraint
  ([phase-3.md:156-158](phase-3.md)) — upgrade the comment to an assert rather than build flush support.
- **Status:** 🔴 open, pre-existing. Found by reading during 003 planning, not by a run.

### 🔴 P5 — the SetCopyUnit collides with itself: repatriation copy overtakes the migration copy
- **What:** for an MSHR that both migrates its victim out and repatriates the demanded line home, the
  two SCU jobs use the **same physical location** — `doSecCopy` writes `(physSet, meta.way)` and
  `doMigCopy` reads `(physSet, migSrcWay)`, and `migSrcWay === meta.way`. The migration must read
  first. It does not, so the migration parks the **repatriated line's bytes** into the partner set
  under the **victim's tag**, behind a valid-looking directory entry.
- **Root cause:** `doSecCopy` ([MSHR.scala](../design/craft/inclusivecache/src/MSHR.scala) ~L402) is
  missing the `!migDeferred` term that its sibling `io.schedule.bits.a.valid`
  ([MSHR.scala:428](../design/craft/inclusivecache/src/MSHR.scala#L428)) has. While `migDeferred` is
  set the migrate/release decision is still open, so `migrating` is false and `(!migrating || w_copy)`
  reads as "nothing to wait for". The decision then resolves on `w_rprobeacklast` — the same register
  `doSecCopy` tests — and `migrating := true.B` only lands at the end of that cycle, so `doSecCopy`
  fires in the very cycle the migration is being decided.
- **Evidence:** caught live by the 003 Stage-1 BankedStore shadow model on its **first run**,
  `migration_stress_test` case 4, cycle 646419:
  `set=5 way=5 stored=0x440055 believed=0x44003d lastWriter=copy_w wKind=2(sec) rKind=1(mig)
  writtenAt=646414 now=646419`.
- **Pattern:** this is `707445c` for the third time — a gate added in one place and missed in its
  sibling — and simultaneously the same one-cycle latch hazard as the 002 C1 finding (a consumer
  reading a register in the cycle it is written). `MSHR.scala:418-427` already documents exactly why
  `!migDeferred` is needed on `a.valid`; the copy lane was written later and never got it.
- **Fix:** add `&& !migDeferred` to `doSecCopy`. Not applied pending the thinker's call — TASK 003 §9
  makes Stage 4 (which deletes this whole path) the experiment for `case_reaccess_migrated`, and
  fixing it now changes what that experiment proves.
- **Resolution: CLOSED BY DELETION in 003 Stage 2a**, and **confirmed as the cause**. The whole
  repatriation path was removed rather than patched (the fix would have been throwaway work on code
  being deleted). `case_reaccess_migrated` — open since task 001 — **passes** for the first time, as do
  all six data-correctness cases in `migration_stress_test`, with zero shadow-model firings.
- **Status:** ✅ closed by deletion. Found in 003 Stage 1 by the BankedStore shadow model; introduced by
  the 001 commit-4 repatriation path.

### ✅ P6 — `migFastWantW` evaluates against the partner set's directory result — NEVER BENIGN
- **What:** `migFastDecline` ([MSHR.scala:~845](../design/craft/inclusivecache/src/MSHR.scala)) carries
  `!(searching && !w_ssearch)` with the comment "that result is the partner set, not ours". Its
  sibling `migFastWantW` ([MSHR.scala:~819](../design/craft/inclusivecache/src/MSHR.scala)) does not.
  So on the cycle the secondary-search result returns, `migFastWantW` assesses the **partner set's**
  directory entry as if it were its own victim and can raise `io.dstClaim.valid` spuriously.
- **Impact: NOT benign — observed live in 003 Stage 2a.** It was originally filed as latent (the plan
  block's search-result branch runs first, so `migrating` is never set from it). Deleting the
  repatriation path was enough to expose it: `migration_stress_test` case 7 halts deterministically at
  sim-time `9757041000` on
  `assert(!(migFastWantW && migDeferWantW))` ([MSHR.scala:964](../design/craft/inclusivecache/src/MSHR.scala#L964))
  with `searching=1 w_ssearch=0 migDeferred=1 set=1 dirHit=0` — the missing term, read off the failing
  cycle. Before 2a a secondary hit cancelled the fetch and entered the repatriation, which changed when
  the search result landed relative to `migDeferred` and masked the collision.
- **Pattern:** the same sibling-asymmetry as P5, in the same file, between two conditions written
  together.
- **Fix:** `!(searching && !w_ssearch)` added to `migFastWantW` in 003 Stage 2b — the term
  `migFastDecline` already carried. `dirHit=0` at the failing cycle is why it matters: a **miss** on
  the partner's search result means the line is not parked there, which says nothing whatever about
  our own victim, so the fast path must not look at that result at all.
- **Status:** ✅ **never benign — masked by the repatriation path; exposed by 2a, fixed in 2b.** It was
  carried for two tasks under the wrong label. See the rule below.

### 🟡 A5.1 — the SetCopyUnit can stall mid-block (LIVE RTL, not the corruption)
- **What:** [SetCopyUnit.scala:137](../design/craft/inclusivecache/src/SetCopyUnit.scala#L137) is
  `io.bs_wadr.valid := io.copy_wsafe` inside `s_write`, and `wrBeat` advances only on `io.bs_wadr.fire`.
  So `copy_wsafe` is re-evaluated on **every write beat**; a drop mid-block stalls the write there and
  can hand SourceD a half-new block. Nothing latches the safety decision for the duration of the block.
- **Status (corrected 2026-08-29):** **not the cause** — the `case_reaccess_migrated` corruption was
  **P5**, confirmed in 003 Stage 2a. A5.1 is therefore no longer a corruption lead.
- **⚠️ And it is NOT deleted.** The earlier header said "deleted in 003 Stage 4"; that was wrong.
  Stage 2a deleted the *repatriation* copy, but [SetCopyUnit.scala:137](../design/craft/inclusivecache/src/SetCopyUnit.scala#L137)
  still exists and still serves the **migration** copy, which is the SCU's one remaining caller. So
  this stays a live RTL fact: the copy can still stall mid-block on `copy_wsafe`.
- **Status:** 🟡 live, unexercised. Keep — it describes real hardware, just not the bug we were hunting.

### 🟡 A5.2 — `copy_wsafe`'s one-cycle blind spot (LIVE RTL, upstream, not the corruption)
- **What:** [SourceD.scala:406](../design/craft/inclusivecache/src/SourceD.scala#L406) guards the `s1`
  comparison with `busy`, a `RegInit` (`:91`), but `:95` is `s1_req = Mux(!busy, io.req.bits, s1_req_reg)`
  and `:103` drives `io.bs_radr.valid := (busy || io.req.valid) && …`. In the cycle `io.req` fires,
  SourceD issues its first bank read from `io.req.bits` while `busy` is still false and `s1_req_reg`
  still holds the *previous* request — and `copy_wsafe` compares `s1_req_reg`. The check is one cycle
  behind the read it guards.
- **Scope:** the blind spot is **upstream's**, not SBC's — `evict_safe` and `grant_safe` share it
  (`:383`, `:390`), and the comment at `:378` reads like an acknowledged approximation. The SBC question
  is what closed it upstream and whether a repatriation still satisfies that.
- **Status (corrected 2026-08-29):** **not the cause** — `case_reaccess_migrated` was **P5**, confirmed
  in 003 Stage 2a. Still a live upstream RTL fact (the `busy`-register blind spot is unchanged, and
  `evict_safe`/`grant_safe` share it), so it stays recorded — but it is no longer a corruption lead.

---

### ✅ P7 — the 1f pinning assert reads `pairSetReg` in the cycle it is written
- **What:** [MSHR.scala:974](../design/craft/inclusivecache/src/MSHR.scala#L974) compares
  `io.dstClaim.bits === pairSetReg`, but `pairSetReg`/`pairValidReg`/`pairIsSrcReg` are written under
  `when (io.directory.valid)` (`:1150`) — and `migFastWantW`, which drives `dstClaim.valid` on the
  **plan** path, also requires `io.directory.valid`. So at a plan-time claim the assert compares this
  transaction's destination against the **previous** transaction's pairing.
- **Class:** the one-cycle latch hazard from 002 C1, this time in an assert rather than in logic.
- **Evidence:** with the 2a+P6 tree (`a148a82`) and the counter-instrumented stress binary, it fires
  at sim-time `24119791000` in case 7. The 2b tree passes the same binary 7/7 — the only difference
  being which cycle the claim is taken in.
- **Not a pinning violation.** The claim value is live and correct (`migrateResp.destSet` returns
  `dEntry.assocSet` from the AT for the deciding MSHR); only the register it is compared against is
  stale.
- **Status:** ✅ **FIXED in 003 Stage 9** with the live-value treatment (the 002 C1 pattern): the assert
  reads the pairing from `io.pairInfo` on the cycle `io.directory.valid` writes the register, and from
  the register otherwise.
- **⚠️ "No longer reachable" was wrong, and the way it was wrong is the lesson.** After 2b I recorded
  this as unreachable because the decide point had moved to the search-resume cycle. That was true of
  2b's *timing* and false of the *defect*: the fast path's own exposure was never removed, only made
  rare. Stage 9a raised the fast-path claim rate and it fired again immediately. **A latch hazard is
  not closed by moving one of its consumers** - it is closed by reading the live value.
- **⚠️ Also note:** `a148a82`'s earlier 7/7 was luck of the binary layout, not evidence of correctness.

## Reusable debugging facts for this repo

These are not bugs. They are things that cost a build-and-run cycle to learn and would cost one again.

### `printf` needs `+verbose`; an `assert` message does not
A Chisel `printf` is emitted under `PRINTF_COND` (= `+verbose`), which in this flow is the same stream
as the full instruction trace — ~100x slowdown, and a `.out` in the hundreds of MB. An **`assert`
message prints regardless**. So diagnostics for a firing assert belong **inside the assert message**,
via `cf"…"` interpolation, not in a neighbouring `printf`. Learned in 003 Stage 1: a `[SBC][SCU] START`
printf next to a firing assert produced nothing; moving the same values into the message worked first
try and turned a symptom into a root cause in two rebuilds.

### "Benign by reading" is only benign relative to the code that masks it

> **A finding marked "benign by reading" is only benign relative to the code that happens to mask it.
> Delete that code and it becomes live. Re-open every such finding when nearby code is removed.**

P6 is the case that produced this rule. It was filed as latent on a sound-looking argument — the plan
block's search-result branch runs first in the if/elsif chain, so `migrating` is never set from it —
and it was true as far as it went. What it missed is that the label was resting on the *repatriation*
path: a secondary hit used to cancel the fetch and enter the repatriation, which shifted when the
search result landed relative to `migDeferred`. Deleting that path in 2a made P6 fire deterministically
on the very next run. It had never been benign; it had been masked, by code we were removing.

Practical form: when a step deletes or restructures code, walk the register and re-open every finding
whose "benign"/"unreachable" argument mentions the code being touched. P6 spent two tasks mislabelled.

### Read-in-the-write-cycle: this project's single most productive bug shape

> Every one of this project's three worst bugs (002 C1, P6, P7) has been the same shape: something
> reads a register in the same cycle something else writes it, and gets the old value. **Any new
> signal gated on `io.directory.valid` should be checked against this by default, not discovered by
> accident.**

The three instances, for pattern-matching:

| # | reader | register | who wrote it that cycle |
|---|---|---|---|
| 002 C1 | the search arm in the plan block | `pairValidReg` | `when (io.directory.valid)` |
| P6 | `migFastWantW` | — (read the *partner's* `io.directory.bits` as its own) | the search-result cycle |
| P7 | the 1f pinning assert | `pairSetReg` | `when (io.directory.valid)`, same signal gating the claim |

The checkable question for any new signal: *does anything I read here get written under the same
condition that makes me fire?* If yes, either read the live wire instead of the register (the 002 C1
`pairLive` fix), or move the consumer to a later cycle (what 2b did to the decide point).

### "Name the condition once" is the wrong rule when scheduling and waiting are different questions
`707445c` taught this repo to name a condition once and use the name at both sites. 003 Stage 1 found
the one place that advice is actively wrong. The ProbeAck routing key needs **two** selectors:

```scala
val probeVictimNow = !s_rprobe          // which probe am I ISSUING this cycle
val probingVictim  = !w_rprobeacklast   // which probe am I WAITING for
```

`s_rprobe` retires the moment the probe issues, while the answer is still in flight. Keying the
advertised `probeSet`/`probeTag` off it would flip the routing key mid-transaction and hang the MSHR.
Keep both selectors distinct, and keep the comment saying why.

---

## Cross-references
- Phase-2 single source of truth: [phase-2.md](phase-2.md)
- All arbitration/priority orders: [priority-orders.md](priority-orders.md)
- Visual flow: [diagram.md](diagram.md)
- Phase-1 historical: [phase-1.md](phase-1.md)
- Risk register: [SBC_implementation_challenges.md](SBC_implementation_challenges.md)
