# SBC Phase 1 — Handoff (continue here)

**Date:** 2026-06-18
**Branch:** `set_migration_refactored`
**Status:** Steps 0–5 implemented (not yet elaborated/run). Step 6 (SCU self-check) and step 7
(AT commit) remain. **Next action: elaborate through Chipyard sbt before starting step 6.**

This doc consolidates the implementation summary (was `tmp-step5.md`) + the design decisions we
locked during review. Authoritative plan: [discussion-phase1.md](discussion-phase1.md).

---

## Where we are — the full migrate chain is wired end-to-end

Arm a hot set → SBU emits a migrate request → it's injected on the control port → an MSHR allocates
on the source set → does a 2nd dir-read of the destination → **proceeds** (copy + install displaced +
invalidate home) **or cleanly aborts**. All three counters track it.

```
SW writes SBC_BalanceSet(s)        [Control.scala  0x340]
  → SBU armed[s]=1
  → set s gets hot (sat ≥ T_hi)
  → SBU migrateReq{srcSet=s, dstSet=DSS coldest}   (throttled ≤1 in flight)
  → flush-priority arbiter injects via SinkX
  → MSHR allocates on srcSet, reads dir → srcWay (victim)
     ├─ src not clean/free/non-displaced → ABORT (aborted++)
     └─ else 2nd dir-read of dstSet (preferInvalid)
          ├─ no invalid way (set full) → ABORT (aborted++)
          └─ invalid way found → PROCEED:
               s_copy → w_copy → install displaced @ (dstSet,dstWay) → invalidate home (srcSet,srcWay)
               → commit{MIGRATE} (migrations++)
```

---

## What's done, by step

| Step | Status | Files | Notes |
|---|---|---|---|
| 0 — blockers | ✅ | SinkC, MSHR | `dstSet` driven; MSHR:329 assert guarded with `!mig_dir1 \|\|` |
| 1 — MMIO arm + counters | ✅ | Control, InclusiveCache | `SBC_BalanceSet` (W, 0x340) pulses arm; `SBC_Attempted` (0x348), `SBC_Aborted` (0x350) |
| 2 — arm-and-fire | ✅ | SetBalanceUnit | sticky `armed[]`, self-clears below `T_lo`; fires lowest `armed && sat≥T_hi`; dst=DSS coldest; gated on `coldestValid && dst≠src && !anyMigrating` |
| 3 — injection | ✅ | SinkX, Scheduler, InclusiveCache | `SinkXRequest.{migrate,set,dstSet}`; gated 2-input arbiter (flush priority); `migInFlight` one-shot |
| 4 — MSHR migReq | ✅ | MSHR | `migReq` status bit covers alloc..retire incl. abort; `anyMigrating` = OR of migReq |
| 5 — real dstWay + counters + abort-ack | ✅ | Directory, MSHR, Scheduler, SourceX, InclusiveCache, SetBalanceUnit | see below |
| 6 — SCU self-check | ⬜ | SetCopyUnit | add `s_verify`: re-read `(dstSet,dstWay)`, assert `== blockBuf` |
| 7 — AT commit | ⬜ | SetBalanceUnit, Scheduler | replace `assert(!commit.valid)` with `AT[s]=d; AT[d]=s`; occupancy check at setup |

### Step 5 detail
- **2nd dir-read (Option 1, LOCKED):** MSHR `s_dread`/`w_dread` scoreboard bits sequence before
  `s_copy`; a `dread` lane is arbitrated into `directory.io.read` and the result routed back. Keeps
  the common allocate path bit-exact.
- **prefer-invalid (LOCKED):** `Directory` gained a `preferInvalid` read flag —
  [Directory.scala:141](design/craft/inclusivecache/src/Directory.scala#L141): returns the first
  invalid way if one exists, else the normal LFSR victim. Normal reads don't set it → baseline
  untouched. Makes `dstEligible` a clean test: result `state === INVALID` ⇒ room (proceed); else
  set full ⇒ abort.
- **Counters (driven the corrected way):** `attempted++` via MSHR pulse at migrate setup;
  `aborted++` via MSHR pulse on the abort retire; `migrations++` on `commit{MIGRATE}`.
  **Not** routed through `commit.kind` (abort never commits).
- **Abort-ack fix:** `SourceX.migrate` tags an abort retire so InclusiveCache does **not** map it to
  `flush_resp`. Fixes the spurious flush-complete that an aborting migration used to signal.

---

## Locked design decisions (from review)

1. **dstWay source = MSHR-driven 2nd dir-read (Option 1).** Rejected: two-read-allocate (taxes the
   common path, baseline risk) and DSS-tracked-free-way (stale, still needs a recheck).
2. **prefer-invalid flag on the migrate read.** Needed because `directory.io.result` returns only
   one victim entry — can't distinguish "set full" from "random pick missed an invalid way" without it.
3. **Counters:** committed→`commit{MIGRATE}`; attempted/aborted→MSHR pulses (an abort never reaches commit).
4. **Phase 1 = mechanism proof, not a perf win.** The displaced copy is dark until Phase 3; a
   re-access misses, re-fills the source set, leaves a dead duplicate. Hit-rate may dip — expected.

---

## Open caveats / watch-items (track, mostly Phase-2)

- ⬜ **`migInFlight` has no timeout.** If an injected migrate never allocates (a permanently-blocked
  set), the one-shot latch sticks `true` and stops future migrations. Low risk for directed tests;
  revisit before demand-triggered Phase 2.
- ⬜ **`dstSetConflict` blocks C/X to `d`, not just A.** Bounded/safe here (debug-triggered, clean),
  but must become **nest-capable before Phase 2** or it's a latent deadlock.
- ⬜ **`victimWay` not displaced-aware** — fine until persistent displaced entries accumulate; a
  demand eviction of a displaced way needs a `DISP_EVICT` commit (Phase 2).
- ⬜ **Destination eviction not handled** — Phase 1 aborts if `dstSet` has no invalid way. Real
  dirty/valid-victim eviction (probe + writeback) is Phase 2 (`M_DEST_EVICT`, `DISP_EVICT`, FSM 3 —
  already designed in [SetBalanceUnit_design.md](SetBalanceUnit_design.md)).
- ⬜ **SCU `s_done` printf ungated** — gate behind `sbcDebug` (cosmetic).
- ⬜ **CLAUDE.md MMIO table** — already lists 0x340/0x348/0x350; keep in sync if a SW header is added.

---

## Next steps (tomorrow)

1. **Elaborate now** — 8 files touched incl. bundle/IO changes; catch Chisel errors cheaply before step 6.
2. **Step 6 — SCU self-check (SetCopyUnit.scala):** add `s_verify` after `s_write`: re-read
   `(dstSet,dstWay)`, `assert(== blockBuf)`; keep the `sbcDebug` beat printf. This is the **only**
   proof the dark destination copy is byte-correct (SW can't read it back).
3. **Step 7 — AT commit (SetBalanceUnit.scala + Scheduler):** replace `assert(!io.commit.valid)` with
   `AT[s]={src→d}; AT[d]={dst→s}`. Move the AT-slot-occupied check to **setup** (next to dstEligible);
   commit becomes an unconditional write (a completed migration can't be unwound). Scheduler drives
   `sbu.io.commit` from the migrating MSHR at its commit step.
4. **Verify (after 6+7):**
   - Elaborate.
   - Bare-metal directed test: prime `srcSet` with **loads** (clean + L1D-flushed ⇒ eligible) →
     `SBC_BalanceSet` → drive misses to saturate → expect `SBC_Migrations++`; a load to the migrated
     address misses but returns the **correct** value. Full `dstSet` → `SBC_Aborted++`.
   - SCU `s_verify` + invariant asserts (≤1 dstValid, ≤1 copy, displaced-install) stay quiet.
   - Baseline parity on a separate `sbc-baseline` branch (no arm = unchanged).

**Sign-off ≠ hit-rate.** Success = migrations fire, data correct, asserts quiet. Durable balancing is Phase 3.
