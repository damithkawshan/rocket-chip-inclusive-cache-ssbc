# SBC Phase 2 — Migrate-on-Eviction (single source of truth)

**Branch:** `set_migration_refactored`
**Created:** 2026-06-24 · **Consolidated:** 2026-06-30 · **Signed off:** 2026-08-17
**Status: COMPLETE — SIGNED OFF ON CORRECTNESS.** Datapath built, all known bugs fixed, and the
owed **stock-config regression now PASSES (7/7 corner cases, 0 asserts, destinations spread)**. Two
items carry forward as **gates on Phase 3**, not Phase-2 defects: copies are unverified (`s_verify`
disabled) and **migration fires far too rarely to pay off** (§7).

This is the **one** Phase-2 document. It absorbs the former `phase-2-2b-handoff.md` and
`phase-2-dst-collision.md` (now retired to `obsolete_files_do_not_refer/`). For the blow-by-blow bug
record see [bug-fix-log.md](bug-fix-log.md); for every arbitration/priority order see
[priority-orders.md](priority-orders.md); for the visual flow see [diagram.md](diagram.md).

---

## 1. What Phase 2 does (in one minute)

- On a demand miss to a **hot** set `s`, instead of throwing the clean victim away, the cache **moves
  it to a cold set `d`** and refills the freed way in `s` with the demanded line.
- This spreads load off hot sets. The moved copy is parked as a **displaced** line in `d`; it is
  *dark* in Phase 2 (not yet reused) — a later access to it simply misses and refetches from memory
  (safe, because only clean lines migrate).
- **Phase 1 proved the primitives; Phase 2 wires them to the real eviction path under real traffic.**

### The pivot (why Phase 2 looks different from Phase 1)
- Phase 1 fired migration as a **separate, injected request** — a *second* owner of the set. That
  second owner is the entire cause of **BUG-2** (it nested into a live demand MSHR and corrupted its
  grant).
- **Phase 2 deletes the separate requester.** Migration becomes the **demand MSHR's own eviction
  work**. One MSHR owns the set the whole time.
- Consequences: BUG-2 cannot occur on the source side (nothing to nest into); the victim is eligible
  by construction (it's the clean victim of a real miss); the Phase-1 injection path is **dead code**.

---

## 2. The migrate datapath (what one successful migration does)

For a demand A-channel miss to a hot set with a clean victim at `(s, vWay)`:

1. **1st dir-read (demand eviction read).** The Scheduler sets `preferEvictable` so the directory
   picks a clean victim way. The A-channel branch detects "miss + valid victim + hot advice + eligible
   victim" → sets `migrating := true`, `migDstSet := advice`, `migSrcWay := vWay`, arms the 2nd
   dir-read.
2. **Reserve the destination.** Raising `dstValid`/`dstSet` fences the cold set — see §5.
3. **2nd dir-read of `d`** (`preferInvalid=true`, `preferEvictable=true`) → pick a parking way `dWay`:
   - **Accept** if `dWay` is INVALID (free) **or** clean + client-free + non-displaced (silently
     overwritable). Arm the copy and both dir-writes.
   - **Abort (ABORT-DST)** if every way is dirty / client-held / displaced → fall back to a normal
     eviction. Safe, no migration.
4. **Copy** `(s,vWay) → (d,dWay)` via the SetCopyUnit, beat by beat, behind the RaW/WaR hazard gates
   (§4). Pulses `copy_done`.
5. **dir-write #1** — install the **displaced** entry at `(d,dWay)` (`displaced=1, dirty=0, clients=0`).
6. **Outer Acquire + refill.** Fetch the demanded line; the **A2 interlock** holds the Acquire until
   the copy read is done so the refill can't clobber the victim before the copy reads it.
7. **dir-write #2** — the ordinary demand refill rewrites `(s,vWay)` with the demanded line. The MSHR
   retires and pulses `migCommit` → SBU records the association and bumps the migration counter.

**Key coupling:** dir-write #2 *is* the demand refill (Phase 1 invalidated the home way; Phase 2
rewrites it). **Migrating ⇒ no `s_release`** of the victim — it is copied, not released.

---

## 3. What we implemented + where it lives

| Piece | File | What |
|---|---|---|
| Migrate gate | `MSHR.scala` (A-channel eviction block) | Decide migrate vs normal eviction (gate below) |
| Two-set ownership | `MSHR.scala` `MSHRStatus.{dstValid,dstSet,dstWay}` | The MSHR reserves a 2nd set (the parking spot) |
| 2nd dir-read + accept/abort | `MSHR.scala` (`s_dread`/`w_dread`) | Find a parking way in `d`, else abort |
| Dual dir-write | `MSHR.scala` (`mig_dir1` + reused writeback) | Install displaced @ `d`; refill @ `s` |
| Copy engine | `SetCopyUnit.scala` + `BankedStore.scala` copy ports | Beat-by-beat block move |
| Copy hazards | `SourceD.scala` (`copy_safe` RaW, `copy_wsafe` WaR) | Don't read/write a block SourceD is touching |
| `displaced` bit + displaced-aware hit | `Directory.scala` | Moved line is findable for coherence but never a demand hit |
| Destination fence | `Scheduler.scala` (`dstSetConflict` → `allocReady`) | Keep the migration's set single-owner (§5) |
| Displaced reclaim | `Directory.scala` + `MSHR.scala` | Last-resort victim tier + silent drop (§6) |
| AT commit + counters | `SetBalanceUnit.scala` | Record `s↔d` pairing; `migrationCount++` |
| MMIO map | `Control.scala` | Read-back counters / status (base `0x2010000`) |

### The migrate gate (all must hold)
```
enableSetBalancing
&& (sbcAutoMigrate || armed[s])        // source-set mask
&& sat[s] >= T_hi                      // s is hot
&& victim is valid & clean & client-free & !displaced
&& DSS has a cold dest (coldestValid && coldestLevel < T_lo)
&& migration token free (<= 1 migration per bank)
```
Gate fails → normal eviction (bit-identical to baseline). Gate holds → migrate instead of release.

---

## 4. Design considerations (why it's built this way)

- **One owner per set (the keystone).** The stock cache guarantees one MSHR per set; migration spans
  two sets, so the demand MSHR owns **both** `s` and `d`, and only **one migration per bank** is in
  flight (token). Every cross-set race/deadlock risk is a corollary of this.
- **Only clean + client-free victims migrate.** Then the move needs **zero** outer/probe traffic — it
  is coherence-identical to a normal silent clean eviction. Client-free is effectively *forced* by
  inclusivity (a displaced client-held line would break probe-reachability).
- **Displaced bit + dual dir-write.** The victim must stay *findable for coherence* at its new home
  but *invisible to demand hits* — because its address maps to set `s`, not the set `d` it now sits
  in. The `displaced` bit excludes it from hit detection; the address is reconstructed with
  `expandAddress(tag, physicalSet)`. Invariant: **physical set == address-derived set** for all
  *non-displaced* lines; displaced lines are the one controlled exception.
- **No coherence-visible intermediate state.** The two dir-writes are sequenced under
  nesting-blocked-on-both-sets, so an outer probe / inner release can never observe a half-migrated
  set.
- **A2 copy↔refill interlock.** The home way is read by the copy (victim) and written by the refill
  (new line). The Acquire is held until the copy read completes, so the refill can't clobber the
  victim first.
- **Copy ports are lowest priority in BankedStore.** Real protocol traffic always wins; the copy
  engine cannot perturb protocol deadlock-freedom. (This is *load-bearing* — see
  [priority-orders.md](priority-orders.md) **F**.)
- **Baseline-exact when SBC is off.** Gated by `enableSetBalancing`; with it off, no entry is ever
  `displaced`, so every SBC branch is unreachable and the RTL is bit-identical to upstream. No printf
  elaborates when `sbcDebug=false`.

---

## 5. The destination fence (single-owner across two sets)

A migration reserves `d` so no one else writes it mid-migration. `dstSetConflict` is true for any
request whose set equals a live migration's `dstSet`. The fence is applied at **allocation**:

```
allocReady = alloc && !dstSetConflict      // gates BOTH the alloc dir-read and the MSHR allocate
```

**Why allocation, not acceptance:** an early version fenced only `request.ready` (acceptance). A
demand *accepted* before the migrant existed could sit queued and then *allocate* after the migrant
raised `dstValid` — slipping past the fence and becoming a second owner → **illegal inner-D**. Moving
the check to the allocation step (the step that actually creates the second owner) closes that hole.
This is just the cache's existing one-owner-per-set rule applied to migration: the late demand waits
in the queue until the migration retires. Bounded, acyclic, deadlock-free. (Full record:
[bug-fix-log.md](bug-fix-log.md) → "Dst-set collision".)

---

## 6. Displaced reclaim (so sets don't fill with immortal copies)

A displaced copy used to be excluded from hits **and** every victim tier → immortal → a set could
fill with them and brick (`Directory.scala:156` assert). Fixed with a **last-resort victim tier**:

- **Victim order:** invalid → evictable-native → LFSR-native → **displaced (last resort, new)**.
  A displaced way is dropped only when no native way exists.
- **Silent drop in MSHR:** a displaced victim is dropped with **no Release** — its address maps to a
  *different* set, so a Release would carry the wrong address. The demand refill still installs the
  new native line over it. (The displaced-victim assert was narrowed from "no release *or* writeback"
  to "**no release**" — the writeback is now exactly the reclaim.)
- Safe because displaced ⇒ clean + client-free; no outer probe targets it on this platform; a later
  access refetches from memory.
- **Accepted caveat:** a set at 7/8 displaced recycles its one native way (a hot, narrow set) until
  Phase-3 spreading/reclaim improves it — no crash, data correct.

---

## 7. Status & verification

**Verified (forced torture config `VerilatorRocket8KL116KL2DstCollisionConfig`, all migrations → set 0,
20000 iters):** PASS, **0 asserts**, **10 migrations committed**, displaced-reclaim fired 3×, data
correct. This is the *worst case* for both the dst-collision and displaced-accumulation bugs, and it
passes.

**How we verify:**
1. **Elaborate** through Chipyard sbt after each change.
2. **Directed bare-metal** (`sw/migration_stress_test.c`, `sw/dst_collision_repro.c`): drive misses to
   saturate a hot set ⇒ migrations commit; a load to a migrated address returns the **correct** value.
3. **Baseline parity** — separate baseline branch; `enableSetBalancing=false` matches upstream.
4. **Asserts are the net** — Chisel `assert`s fire in sim on any coherence/ordering violation.

### ✅ Stock-config regression — RUN & PASSED (2026-08-17)

`migration_stress_test` on the ordinary `VerilatorRocket8KL116KL2Config` (natural DSS pick, no
`sbcForceDstSet`), **all seven corner cases enabled**:

| Metric | Result |
|---|---|
| Cases | **7/7 PASS** (free-dst, full-clean-dst, dirty-victims, full-dirty-dst, reaccess-migrated, hazard-RaW/WaR, bankstore-saturation) |
| Asserts / crashes | **0** — no illegal inner-D, no `PopCount(victimWayOH)` trip |
| Migrations | 9 attempted, **6 committed**, 3 ABORT-DST (66.7% commit rate) |
| Destination spread | **sets 0, 3, 7** — commits `5→7`(3), `6→3`, `1→0`, `6→0`. No longer collapsing onto one set. |
| Displaced reclaim | did **not** fire (expected: 6 migrations over 3 sets never fills a set) |

This retires the last outstanding Phase-2 verification item. **Two defects were found on the way to
it** and both are recorded in [bug-fix-log.md](bug-fix-log.md): the Bug-B `s_wsafe` fix had been
silently deleted by an uncommitted debug cleanup, and six of the seven test cases in
`migration_stress_test.c` had been left commented out (so every prior "PASS" was 1/7 coverage).

**Stats-script fix — already done.** Re-verified against an archived log: the current
`sw/scripts/sbc_stats.py` reports `COMMIT: 10` correctly and parses the `[SBC][SCHED]` prefix. The
stale summaries in `sw/verilator_logs/` simply predate the fix. Nothing owed.

### 🔴 Finding from the run — migration almost never fires (gate on Phase 3)

The sharpest result, and it is **not** a Phase-2 correctness issue:

```
EVICT-ASSESS  129488     every eviction assessed
EVICT-NORMAL  129479     fell back to a normal eviction
ADVICE-MIG     49161     Scheduler advised "this set is hot, migrate"
MIG-START          9     actually migrated   → ~1 in 5500 advised evictions
```

The hot-set advice fires constantly; the **victim eligibility test** (`!dirty && !clients && !displaced`,
[MSHR.scala:785](../design/craft/inclusivecache/src/MSHR.scala#L785)) kills essentially all of it. In the
Phase-3 payoff model (`saving = p·(M−C)`, [phase-3.md](phase-3.md)) this is **risk #1 — low `p`** — and the
measured `p` here is ~0.0002. Phase 3 makes parked copies reusable, but at this rate there would be almost
nothing parked to reuse.

**Do not start Phase 3 until this is understood.** Instrument *why* victims are ineligible (dirty vs
client-held vs displaced) before building the secondary-search datapath.

**Also carried forward as a Phase-3 gate:** `s_verify` is disabled, so copies are unverified. Harmless
while copies are dark; the moment Phase 3 *serves* them, a silent copy corruption becomes wrong
architectural data with no assert to catch it (§8).

---

## 8. Known-deferred (intentionally not in Phase 2)

- **`s_verify`** (copy self-check) — DISABLED. It read on the lowest-priority BankedStore port and
  starved → deadlock; now falls through to `s_done`. Copy is correct by write-through. Returns in
  Phase 3 behind a non-starvable read path.
- **Q3 (copy-port starvation)** — stress-tested, **rejected**: low priority is bounded delay, not
  deadlock. **Do not reorder BankedStore priorities.**
- **DSS coldness** — currently picks contended sets as "cold" (perf, not correctness). Phase 3.
- **Displaced reuse (secondary search)** — make the displaced copy serve secondary hits via AT lookup;
  enforce **displaced XOR native**. Phase 3.
- **Dirty / client-held destination eviction** — needs a 2nd address + writeback/probe in one MSHR
  (BUG-2-class risk). Phase 4. Clean-only is the 80/20.
- **Residual `[born→gate]` sub-window** — a theoretical slip before `dstValid` rises; not reproduced,
  do **not** pre-build (see [bug-fix-log.md](bug-fix-log.md) open section).

---

## 9. Cleanup owed (dead Phase-1 scaffold + debug)

- ❌ SBU `migrateReq` autonomous trigger + the SinkX **injection path** (`SinkXRequest.{migrate,dstSet}`).
- ❌ `migInFlight` one-shot throttle; the drop-on-busy logic (never built — keep it that way).
- 🔄 `armed` / `SBC_BalanceSet` / `sbcAutoMigrate` now mean "is this set *allowed* to be a source",
  not "fire the migrate" (the trigger is the eviction event).
- ✅ **DONE (commit `a2975d6`)** — `SetCopyUnit.scala` debug scaffold stripped: stall classifier,
  STALL/RESUME printfs, `prev_copy_*` edge-detect regs, and the dead `s_verify` passthrough state.
  ⚠️ **`s_wsafe` was retained deliberately** and its rationale moved onto the state as a
  `DO NOT REMOVE` comment — an earlier pass at this same cleanup deleted it and thereby reverted the
  Bug-B fix. Any future cleanup here must preserve it.
- Remaining: heavy `sbcDebug` printfs in `MSHR.scala`/`Scheduler.scala` + repro knobs
  (`sbcForceDstSet`, `sbcGateStallCycles`); the `MSHR.scala.original` leftover (now gitignored).
  See `code-cleanup-suggestions.md`.

---

## 10. Roadmap

- **Phase 0 ✅** — observation (saturation counters + DSS + MMIO).
- **Phase 1 ✅ CLOSED** — migrate primitives proved (standalone trigger superseded; see
  [phase-1.md](phase-1.md)).
- **Phase 2 ✅ (this) — COMPLETE.** Migrate-on-eviction: 2a abort-if-dst-full, 2b clean dst eviction,
  dst-collision fence, displaced reclaim. Verified on the forced config; stock-config run owed.
- **Phase 3 🔵** — secondary search + swap-home (make `d` reusable; enforce displaced XOR native);
  re-enable `s_verify`; fix DSS coldness; AT-invalidate on displaced reclaim.
- **Phase 4 🔵** — dirty/client-held dst eviction; adaptive yield throttle + association teardown.
