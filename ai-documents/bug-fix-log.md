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

## Cross-references
- Phase-2 single source of truth: [phase-2.md](phase-2.md)
- All arbitration/priority orders: [priority-orders.md](priority-orders.md)
- Visual flow: [diagram.md](diagram.md)
- Phase-1 historical: [phase-1.md](phase-1.md)
- Risk register: [SBC_implementation_challenges.md](SBC_implementation_challenges.md)
