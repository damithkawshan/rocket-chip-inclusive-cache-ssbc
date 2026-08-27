# SBC Code Cleanup Tasks

**Branch:** `set_migration_refactored`
**Created:** 2026-06-30 · **Audited against source 2026-08-18** (after Phase-2 sign-off)
**Scope:** Remove dead artifacts, stale comments, and debug scaffolding accumulated across
Phase 0 → Phase 2. **No behavioral change intended** — every item below is either dead code,
a misleading comment, or `sbcDebug`/param-gated scaffolding. Baseline (SBC-off) must stay bit-exact.

> Verification after each batch: (a) elaboration/build succeeds, (b) Chisel `assert`s quiet in sim,
> (c) the migration stress workload still commits migrations with data correct. Do batches in the
> listed order (lowest risk first).

---

## Audit result — 2026-08-18

**Only Batch C shipped.** Batches A and B are still owed. Batches D and E were re-examined after the
Phase-2 sign-off and the low-`p` diagnosis, and the verdict on both **changed: do not do them.**
Nothing in D or E is dead code — each item is either a tool we still need or a design decision that
has not been made yet.

| Batch | What | Verdict |
|---|---|---|
| **A** | Delete `MSHR.scala.original`, delete commented DUMP block | 🔴 **DO — still owed.** Zero risk. |
| **B** | Fix 3 comments describing the deleted injection path | 🔴 **DO — still owed, and do it first.** Zero risk. |
| **C** | Excise dead `s_verify` + the stall classifier | ✅ **DONE** in commit `a2975d6`. |
| **D** | Retire the two debug repro knobs | ⛔ **KEEP BOTH.** Reversal of the original call — see below. |
| **E** | Guard consolidation, dead IO, printf trim | ⛔ **DO NOT.** E1 is now a Phase-3 design item, not cleanup. |

**Why B is the priority despite being cosmetic:** three comments actively describe hardware that no
longer exists. That is precisely the failure mode that let the `s_wsafe` fix be deleted during the
last cleanup pass — code whose stated reason is wrong or missing reads as cruft to the next person
tidying up. See [bug-fix-log.md](bug-fix-log.md) → Bug B → "REGRESSED AND RESTORED".

---

## Batch A — zero-risk deletions (do first) — 🔴 STILL OWED

### A1. Delete `MSHR.scala.original` — 🔴 NOT DONE
- **File:** `design/craft/inclusivecache/src/MSHR.scala.original`
- A full pre-SBC copy of `MSHR.scala` left as a scratch backup. sbt ignores `.scala.original`, so it
  elaborates nothing. Already preserved in git history.
- **Action:** delete the file.
- **2026-08-18:** the file is **still present**. It was added to `.gitignore` instead of being
  deleted. That is worse than either extreme — a stale copy of the most heavily edited file in the
  repo, now invisible to `git status`, sitting next to the real one. **Delete it.**

### A2. Remove the commented-out periodic DUMP block in `SetBalanceUnit.scala` — 🔴 NOT DONE
- **File:** `design/craft/inclusivecache/src/SetBalanceUnit.scala` (**~L204–L209**, was ~L185–L197)
- ~12 lines of commented-out `printf` DUMP scaffolding inside the `sbcDebug` guard.
- **Action:** delete the commented block.

---

## Batch B — fix misleading / contradictory comments (no code change) — 🔴 STILL OWED, DO FIRST

These comments describe the **Phase-1 injection mechanism that was deleted**. They contradict the
actual code and other nearby comments.

### B1. `Scheduler.scala` **~L433** — 🔴 NOT DONE
- Says: *"when a set is armed + hot, emits a migrate request that is injected via the control port."*
- **Wrong.** The control port carries only flushes now (see the correct SinkX wiring comment ~L74–L78).
- **Action:** rewrite to describe the demand-coupled migrate-on-eviction flow (advice latched at
  allocate; migration is the demand MSHR's own eviction work).

### B2. `SetBalanceUnit.scala` **~L111–L113** — 🔴 NOT DONE
- Says: *"emit a migrate request to the injection path; the throttle keeps at most one in flight."*
- **Wrong.** No injection path, no throttle. `armed` is now a **mask** (the Phase-2 comment ~4 lines
  below already says so — the two contradict each other, four lines apart).
- **Action:** delete the stale lines; keep the Phase-2 description.

### B3. `SetBalanceUnit.scala` header **L8** — 🔴 NOT DONE
- Says: *"Phase 0: pure observation. Migration is OFF (migrateResp.migrate == false), the AT is inert."*
- **Wrong.** `migrateResp.migrate` is live and the AT commits on migration.
- **Action:** update header to "Phase 2: live migrate advice + AT commit".

### B4. Re-tag stale `// SBC Phase 0:` / `// SBC Phase 1:` labels — 🔴 NOT DONE
- **2026-08-18:** several remain, e.g. `// SBC Phase 1: arm-and-fire migration trigger`
  (`SetBalanceUnit.scala` ~L111) and `// SBC Phase 1: SourceD <-> SetCopyUnit copy hazards`
  (`Scheduler.scala` ~L426) — both are live Phase-2 logic.
- Many tags sit on live Phase-2 logic (e.g. `s_copy`/`s_dread` in `MSHR.scala` ~L166–L174, the
  "Phase 1" migration counters, etc.).
- **Action:** retag to Phase 2 (or drop the phase tag). Cosmetic, but reduces reader confusion.

---

## Batch C — remove disabled / half-removed code — ✅ DONE (commit `a2975d6`)

> **Shipped.** `SetCopyUnit.scala` is now `Enum(5)` (`s_idle :: s_wsafe :: s_read :: s_write ::
> s_done`) with no `s_verify`, no commented verify implementation, no `vr*` regs, and no stall
> classifier. **Option A was taken** for C1 (full removal).
>
> ⚠️ **The one thing this pass got wrong:** it also deleted `s_wsafe`, which is the Bug-B WaR deadlock
> fix — not debug scaffolding. Caught in sign-off review and restored in the same commit, with the
> rationale now attached to the state as a `DO NOT REMOVE` comment. `s_verify` was correctly removed;
> it **must be rebuilt in Phase 3** behind a non-starvable read path, because Phase 3 serves the
> parked copies to the CPU.

### C1. Excise the disabled `s_verify` path in `SetCopyUnit.scala` — ✅ DONE (Option A)
- **File:** `design/craft/inclusivecache/src/SetCopyUnit.scala`
- Current state (in limbo):
  - `Enum(6)` declares `s_verify` (~L53), but `s_write` jumps straight to `s_done` (~L194) →
    the `is (s_verify)` block (~L199) is **unreachable**.
  - ~20-line commented-out verify implementation above it (~L199–L216).
  - `vrAdrBeat`/`vrDatBeat` regs exist only for the dead path but are still referenced by the debug
    `curBeat` in the stall classifier — must update C2 together or keep a stub.
- **Decision needed:** `s_verify` is slated to **return in Phase 3** behind a non-starvable read port.
  - **Option A (recommended now):** fully remove the dead state + commented block + `vr*` regs; leave
    a one-line `// Phase 3: re-add copy verify behind a dedicated high-priority read port` note.
  - **Option B:** keep but move the commented impl into the Phase-3 design doc, shrink enum to `Enum(5)`.
- **Action:** pick A or B; if A, coordinate with C2 (the classifier references `vrAdrBeat`).

### C2. Remove the copy-port stall classifier in `SetCopyUnit.scala` — ✅ DONE
- **File:** `design/craft/inclusivecache/src/SetCopyUnit.scala` (~L218–L271)
- ~50 lines of `sbcDebug`-gated ARB/HAZARD/DEGENERATE stall diagnostics, built to investigate the
  "Q3" copy-port priority-starvation question — **already tested and REJECTED** (do not reorder
  BankedStore priorities). Investigation closed → dead diagnostic weight.
- **Action:** remove the classifier block. (Also clears the `vr*` references from C1.)

---

## Batch D — retire debug repro knobs — ⛔ REVERSED 2026-08-18: **KEEP BOTH**

> **The original call was wrong.** It reasoned "the bug these knobs reproduced is closed, so retire
> them." But the bug is not entirely closed — its **residual `[born → gate]` sub-window is still on
> the open list** ([bug-fix-log.md](bug-fix-log.md) → Open bugs), and D1 is the only instrument that
> can widen it. Both knobs are compile-time Scala `if`s that elaborate **zero hardware** at their
> defaults, so keeping them costs nothing but a branch of clutter.
>
> **What changed the calculus:** the low-`p` diagnosis. If probe-then-migrate works, migration rate
> goes from **9 per run to thousands** ([July18AfterBreakWorkplan.md](July18AfterBreakWorkplan.md)
> §2). Every migration opens a destination-fence window. A latent race never hit in 9 attempts is a
> very different proposition at 9,000 — so the repro tooling becomes *more* valuable, not less.
> **Revisit only after the post-probe-then-migrate regression is clean.**

### D1. `sbcGateStallCycles` — ⛔ KEEP (was: remove)
- **Files:** `Parameters.scala` (**L134**, `require` L144), `Configs.scala` (L68, L125),
  `MSHR.scala` (**L221–L231**).
- Holds `dstValid` low for N cycles after `migrating` rises, deliberately widening the unfenced
  `[advice → gate]` window. Default `0` → zero hardware.
- **Keep because:** that window *is* the open `[born → gate]` bug. This knob is how we would
  reproduce it if it ever surfaces, and Phase 3 makes migration windows frequent.

### D2. `sbcForceDstSet` — ⛔ KEEP (was: remove)
- **Files:** `Parameters.scala` (**L133**), `Configs.scala` (L67, L124), `Scheduler.scala`
  (**L457–L461**).
- Pins every migration's destination to a fixed set. Default `-1` → normal DSS pick, zero hardware.
- **Keep because:** it is still the only way to fill one set with displaced lines, and therefore the
  only way to exercise the **displaced-reclaim tier** — which did *not* fire on the stock sign-off
  run, so its evidence remains entirely synthetic. Retire it only once reclaim has non-forced
  evidence.

---

## Batch E — structural simplification — ⛔ 2026-08-18: **DO NOT DO ANY OF IT**

### E1. Consolidate the overlapping migration guards — ⛔ NOT CLEANUP ANY MORE
- **File:** `Scheduler.scala` (**L198–L204**, **L447–L492**)
- The "≤1 migration + no dst-collision" guarantee is enforced by **six** partly-overlapping
  mechanisms: `anyMigrating`, `migTokenPending`, `migPendCtr` (4-bit timeout), `dstSetConflict`,
  `dstSetOwned`, `dstSelfCollision`.
- **Reclassified as a Phase-3 design item, for two reasons:**
  1. **Phase 3 rewrites this exact code.** Under pinned 1:1 association the destination comes from
     the AT, not the DSS — so `coldDst`, `dstSelfCollision` and `dstSetOwned` all change meaning.
     Deriving a minimal guard set now means doing it for logic about to be replaced.
  2. **These guards encode a policy that is about to become a bottleneck.** `anyMigrating` +
     `migTokenPending` + `migPendCtr` together *are* the "one migration in flight per bank" token.
     That is free at 9 migrations per run. At thousands it is a throughput ceiling — risk #7 in
     [phase-3.md](phase-3.md). So the real question is no longer "which of these six are redundant"
     but **"should more than one migration be allowed in flight at once?"** That is a design
     decision, not a tidy-up.
- **Action:** fold into the Phase-3 pinned-pairing step, where this code is being touched anyway.
  Do **not** do it as a standalone cleanup — that tangles two risky changes in one debug session.

### E2. Dead IO surface `assocQuery`/`assocResp` — ⛔ LEAVE AS IS
- **File:** `SetBalanceUnit.scala` (L55–L59, driven L108–L109), tied off in `Scheduler.scala`
  (**L495–L496**).
- Reserved for the Phase-3 secondary search, which is the **next thing being built** and wires it up
  directly. Deleting and re-adding is pure churn.

### E3. `printf` verbosity — ⛔ REJECTED, KEEP THE PRINTFS
- Heavy `[SBC]` debug printfs across `MSHR.scala` / `SetCopyUnit.scala` / `SetBalanceUnit.scala`.
  All `sbcDebug`-gated → zero hardware.
- **Rejected 2026-08-18, with evidence.** The low-`p` blocker — the single thing gating Phase 3 — was
  diagnosed **with no RTL change and no re-run**, purely by tallying an existing log. That was
  possible only because [MSHR.scala:787](../design/craft/inclusivecache/src/MSHR.scala#L787) already
  prints `eligible` / `dirty` / `clients` / `displaced` on every `EVICT-ASSESS`. These printfs just
  paid for themselves outright.
- Step 1 of the current plan **adds** one (`ProbeAck` vs `ProbeAckData`). The verbosity is the asset.

---

## What is already clean (no action)
- ✅ Phase-1 **injection path removed** — no `migrateReq`, no `migInFlight`, no SinkX `migrate`/
  `dstSet` fields anywhere in `src/` (verified by grep 2026-08-18). ⚠️ Only the **comments** describing
  it survive — that is Batch B, and it is why B matters.
- ✅ All synthesizable SBC state is gated behind `enableSetBalancing` → SBC-off builds are bit-exact.
- ✅ Debug code is consistently `sbcDebug`-gated.

---

## Revised order — 2026-08-18

The list is now **two items long**:

1. **Batch B** (comment fixes) — zero risk, do first. Wrong comments are what got a load-bearing fix
   deleted last time.
2. **Batch A** (delete `MSHR.scala.original` + the commented DUMP block) — zero risk.

Then stop. **Batch C is done. Batches D and E are closed as "do not do"** — D because both knobs are
still needed tooling, E because it is Phase-3 design work wearing a cleanup label.