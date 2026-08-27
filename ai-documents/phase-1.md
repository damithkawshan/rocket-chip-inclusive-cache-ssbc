# SBC Phase 1 — Migration Mechanism (CLOSED — see Phase 2)

**Branch:** `set_migration_refactored`
**Updated:** 2026-06-24
**Status: CLOSED.** Phase 1 proved the migration *primitives*. The standalone-injected trigger it
used is **superseded by the demand-coupled flow in [phase-2.md](phase-2.md)** — do active work there.

> This doc is the consolidated Phase-1 plan + status + bugs, kept as the historical record. Cross-phase
> risk detail lives in [SBC_implementation_challenges.md](SBC_implementation_challenges.md) and
> [SetBalanceUnit_design.md](SetBalanceUnit_design.md).

---

## Status (read first)

**Phase 1 is closed.** What it delivered, and what it deliberately did *not*:

- ✅ **Primitives built and running in sim:** copy engine (SetCopyUnit), two-set ownership, `displaced`
  bit + displaced-aware hit, dual directory write, 2nd dir-read of the dst set, counters + MMIO.
  These **carry over to Phase 2 unchanged** — they are the value Phase 1 produced.
- ✅ **BUG-1 (outer `AcquirePerm`) RESOLVED** — root cause was a **premature migrate retire**, fixed by
  `mig_ready = !migrating || (s_dmeta && w_dread)`. The displaced-as-INVALID guards were applied too, as
  hardening, but were *not* the cause. `matmult_float` passes with auto-migrate on. Details below.
- ⚠️ **The Phase-1 trigger was a scaffold (now superseded).** Phase 1 fired migration as a **separate,
  injected request** (SBU `migrateReq` → SinkX inject → a *new* MSHR just to copy). That extra requester
  is the root cause of BUG-2 and is **thrown away in Phase 2** (migration becomes the demand MSHR's own
  eviction work — no second requester).
- 🐞 **BUG-2 — migrate nests into a live demand MSHR → illegal inner `D` grant → sim aborts.** Real, but
  **NOT patched in Phase 1.** It is an artifact of the standalone-injection scaffold and **disappears by
  construction in Phase 2** (no separate migrate requester ⇒ nothing to nest into). Details below.
- ⚠️ **The copy is UNVERIFIED.** It is dark in Phase 1 (never read back), so byte-correctness was never
  proven and we never observed a single fully-correct commit. **Phase 2 task 0 = turn on the deferred
  `s_verify` check** before the copy becomes load-bearing.
- ⏭️ **Step 7 (AT commit)** — carried into Phase 2 (still needed; `assert(!io.commit.valid)` to replace).
- **Abort rate was ~99.98%** (1 commit / 5,664 attempts) — clean no-ops, an artifact of the standalone
  trigger picking a random victim in a hot set. Phase 2 only migrates the *already-eligible* victim of a
  real miss, so this goes away. See "Abort-rate findings".

---

## What Phase 1 is

- Build + prove the **migration mechanism**: copy one line from a hot set `s` to a cold set `d`,
  install a *displaced* directory entry at `d`, invalidate the home entry at `s`.
- The two dangerous primitives being proven: **two-set ownership** + a **set-to-set data copy**.
- **Phase 1 is mechanism-only, NOT a perf win — by design.**
  - The displaced copy in `d` is **dark** (no secondary search until Phase 3).
  - A re-access to a migrated line: misses in `s` → fetches from memory → re-installs native in `s`
    → leaves a dead duplicate in `d`. Safe only because lines are clean (both equal memory).
  - So success = migrations fire + data correct + asserts quiet. **Hit-rate may dip — expected.**

---

## Locked design decisions

1. **Dedicated copy datapath.** `SetCopyUnit` (SCU) has its own BankedStore read+write ports
   (lowest arbiter priority) + internal `blockBuf` + `IDLE→READ→WRITE→DONE` FSM.
2. **Full migrate.** Phase 1 installs the displaced dir entry at `(d,dWay)` + does the dual dir-write
   (install displaced, invalidate home) — not just a data move.
3. **Displaced-aware hit (mandatory once real displaced entries exist).** Native hit excludes displaced
   ways, so a demand lookup in `d` can't false-hit a displaced tag.
4. **Migrate only into a FREE (invalid) way of `d`; else ABORT.** No displaced-eviction in Phase 1.
5. **One migration in flight per bank.** The migrating MSHR **owns both `s` and `d`** for the whole
   window (keeps the single-owner-per-set invariant).
6. **Migrated/displaced lines are clean + client-free, always.** Asserted at install.
7. **dstWay source = MSHR-driven 2nd dir-read (Option 1).** Rejected: two-read-allocate (taxes the
   common path) and DSS-tracked-free-way (stale).
8. **Counters:** committed → `commit{MIGRATE}`; attempted/aborted → MSHR pulses (an abort never commits,
   so `commit.kind` cannot drive them).

---

## Trigger model (request-driven — updated 2026-06-23)

Earlier version scanned **all** sets and picked the lowest index (`PriorityEncoder`). **Wrong** — it
ignored which set was actually requested. Now demand/request-driven:

- src set = the set the dir-tap just touched (`tapSet`).
- Fire `migrateReq.valid` when:
  ```
  io.dirTap.valid && (sbcAutoMigrate.B || armed(tapSet)) && (nxt >= tHi)
    && dss.io.coldestValid && (dss.io.coldestLevel < tLo) && !io.anyMigrating
  ```
- It's a 1-cycle pulse — fire-and-forget. If the injector is busy, it re-fires on the set's next access
  (no latch needed).
- `dstSet = DSS coldestSet`.

Two trigger rules that matter:

- **Arm-and-fire.** `SBC_BalanceSet(s)` (MMIO) sets a sticky `armed[s]`; migration fires only when `s`
  actually saturates (`sat >= T_hi`); the armed bit self-clears below `T_lo` (hysteresis).
- **`sbcAutoMigrate` param.** When true, eligibility ignores `armed` — any hot set qualifies. Used for
  stress runs without per-set arming.
- **Cold-destination gate (`coldestLevel < T_lo`).** The DSS admits **any** touched set (no level
  filter), so its "coldest" can be a hot set or the src itself. Without this gate, a workload that only
  hammers one set makes that set the only DSS entry → `coldestSet == srcSet` → nothing migrates
  (this was a real `attempted=0`). The gate guarantees the dst is genuinely cold and makes the old
  `coldestSet =/= srcSet` guard redundant.

---

## Migration flow (the chain)

```
SBC_BalanceSet(s) [0x340]  → SBU armed[s]=1   (or sbcAutoMigrate=1: any hot set)
  → set s gets hot (nxt >= T_hi) on a request
  → SBU migrateReq{srcSet=s, dstSet=DSS coldest}   (gated: coldValid, coldLevel<T_lo, !anyMigrating)
  → flush-priority arbiter injects via SinkX (migInFlight one-shot keeps ≤1)
  → MSHR allocates on srcSet, reads dir → srcWay
     ├─ src not (valid & clean & client-free & non-displaced) → ABORT (aborted++)
     └─ else 2nd dir-read of dstSet (preferInvalid)
          ├─ no invalid way (set full) → ABORT (aborted++)
          └─ invalid way → PROCEED:
               s_copy → w_copy → dir-write#1 install displaced @(dstSet,dstWay)
               → dir-write#2 invalidate home @(srcSet,srcWay) → commit{MIGRATE} (migrations++)
```

MSHR scoreboard sequence (mirrors `s_release`/`w_releaseack`):
`s_dread → w_dread → s_copy → w_copy → s_dmeta (dir#1) → s_writeback (dir#2) → commit`.

---

## Implementation status

**Foundation (done):**
- ✅ Copy datapath — `SetCopyUnit` + BankedStore `sourceCopy_r/wadr` ports (appended last) + Scheduler
  SCU↔BankedStore wiring (commit 87fb58a).
- ✅ Displaced-aware hit — `Directory.scala:143` `... && !ways(i).displaced`.
- ✅ MSHRStatus reservation — `dstValid/dstSet/dstWay`; `dstSetConflict` holds demand to an active dst.
- ✅ SourceD copy hazards — `copy_req/copy_safe` (RaW), `copy_wreq/copy_wsafe` (WaR).
- ✅ MSHR copy lane + `done`/`idle`.

**Migration flow (done):**
- ✅ MSHR migration scoreboard + dual dir-write (`s_copy`/`w_copy`/`s_dmeta`).
- ✅ 2nd dir-read for `dstWay` (`s_dread`/`w_dread`, Option 1) + `preferInvalid`.
- ✅ Injection path — `SinkXRequest.{migrate,set,dstSet}`, gated 2-input arbiter (flush priority),
  `migInFlight` one-shot.
- ✅ Request-driven trigger + cold-destination gate + `sbcAutoMigrate`.
- ✅ Counters: `0x328` Migrations (committed), `0x348` Attempted, `0x350` Aborted.
- ✅ Abort-ack tag (`SourceX.migrate`) so an abort retire isn't mistaken for a flush completion.

**Carried into Phase 2 (Phase 1 closed without these):**
- ✅ **BUG-1 (AcquirePerm) fixed** — premature-retire / `mig_ready` fix (see Open bugs).
- 🐞 **BUG-2 (migrate nesting → inner `D`)** — **not patched; removed by design in Phase 2** (no
  standalone migrate requester). The "drop on busy" idea was **rejected** as throwaway. See Open bugs.
- ⏭️ Step 6 — SCU self-check (`s_verify`): was deferred to Phase 3, **pulled forward to Phase-2 task 0**
  because Phase 2 makes the copy load-bearing. Re-read `(dstSet,dstWay)`, `assert(== blockBuf)`.
- ⬜ Step 7 — AT commit: replace `assert(!io.commit.valid)` with `AT[s]={src→d}`, `AT[d]={dst→s}`;
  move the AT-slot-occupied check to setup; commit becomes an unconditional write. **Carries to Phase 2.**

---

## Key mechanism detail

- **2nd dir-read (Option 1).** `s_dread`/`w_dread` sequence before `s_copy`; a read lane is arbitrated
  into `directory.io.read` and the result routed back. Keeps the common allocate path bit-exact.
- **prefer-invalid.** `directory.io.result` returns only one (LFSR) victim, so it can't tell "set full"
  from "random pick missed an invalid way". A gated `preferInvalid` flag returns an invalid way if one
  exists, else normal LFSR. Normal reads don't set it → baseline untouched. Makes
  `dstEligible = (result.state === INVALID)` a correct full/not-full test.
- **Abort split.** `srcEligible` known at allocate → abort immediately (skip the 2nd read).
  `dstEligible` known when the 2nd read returns → abort then.

---

## Open bugs

### ✅ BUG-1 (RESOLVED) — migration emitted an illegal outer `AcquirePerm`

- **Symptom (was):** TileLink **monitor** assert at the cork (L2→memory edge): *"'A' channel carries
  AcquirePerm type which is unexpected using diplomatic parameters."* Sim aborted.
- **Confirmed ours:** `sbcAutoMigrate=false` → gone; on → fired during heavy migration.
- **Actual root cause:** a **premature migrate retire**. The retire condition (`no_wait && mig_ready`,
  [MSHR.scala:279](../design/craft/inclusivecache/src/MSHR.scala#L279)) was satisfied at setup because
  `no_wait` was already true (`w_copy` not cleared) and the old `mig_ready = s_dmeta` was already true.
  So the migrate MSHR retired the **same cycle it issued its 2nd dir-read** — before copying. The read
  result then landed on a dead/reused MSHR, leaving a stale "needs acquire" flag that later fired as the
  bogus outer `AcquirePerm`. (The Step-0 printf showed it: a low/bogus address with `ctrl=1 mig=1`, and
  the "LEAK into normal path" probe never fired → FSM corruption, not a routing leak.)
- **Fix (applied):** `mig_ready = !migrating || (s_dmeta && w_dread)` — retire now waits for the 2nd-read
  result (`w_dread`) *and* the displaced install (`s_dmeta`). Verified across all paths: dread-wait,
  copy, post-install, both aborts, and non-migration (bit-identical to baseline).
- **Side effect of the fix:** the earlier "17 committed migrations" were **fake** (premature retires
  that copied nothing). Real copies run only now → this is *why* Step 6 (SCU self-check) matters once
  the copy becomes observable in Phase 3.
- **Displaced-as-INVALID guards (A–D):** applied as hardening (exclude displaced from victim selection,
  write-bypass, + safety assert). **Not** the cause of BUG-1, but correct and **now genuinely exercised**
  (real displaced ghosts exist for the first time) → keep them.

### 🐞 BUG-2 — migrate nests into a live demand MSHR (NOT patched — resolved by design in Phase 2)

- **Symptom:** TileLink **monitor** assert on the L2 **inner** edge: *"'D' channel acknowledged for
  nothing inflight"* — the L2 sent the L1 a Grant it never asked for. Sim aborts.
- **Trigger:** the directed test hammers **one** set, so that set always has a **live demand MSHR**.
  When the standalone-injected migrate fires on that same busy set, the scheduler routes it down the
  normal secondary-miss path → it queues/bypasses into the demand MSHR. The migrate FSM isn't built to
  share an MSHR with a demand → it clobbers the demand's in-flight grant → spurious inner `D`.
- **Why matmult dodged it:** its accesses spread across sets → migrate and demand rarely collide on the
  same set. The directed test concentrates on one set → collision is guaranteed.
- **Root cause = the standalone-injected trigger itself.** The bug only exists because Phase 1 fires
  migration as a **second requester**. There is nothing to nest into if migration *is* the demand MSHR.

**Resolution: deleted, not patched.** We considered an in-Phase-1 fix ("drop on busy": make the migrate
non-nestable, drop it if the set is busy, re-fire later). It works but is **throwaway** — it props up the
standalone scaffold that Phase 2 removes anyway, and it carries two silent-failure traps (the
`migInFlight` throttle sticking on a drop; the directed test being unable to hammer continuously). The
decision (2026-06-24) was to **start Phase 2 instead**: migration moves inside the demand-miss eviction,
so there is no separate requester and **BUG-2 cannot occur**. See [phase-2.md](phase-2.md). The
drop-on-busy idea is recorded here only as a rejected alternative — **do not implement it.**

---

## Abort-rate findings (deferred — not a bug)

From a src/dst counter split: **5,664 attempts → 1 commit**, split ~**67% src-abort / 33% dst-abort**.

- **src-abort:** the random (LFSR) victim in the hot set is **dirty (~55%)** or **L1-held (~73%)**,
  never invalid/displaced. A hot set is hot *because* its lines are in active use → a random victim is
  almost always ineligible. Proper fix = "pick a clean, client-free victim" ≈ **Phase 2** (saturation→
  eviction policy).
- **dst-abort:** the DSS coldest set is usually **full** — *cold = stable = not evicted = no free way*.
  Proper fix = DSS occupancy-awareness or displaced-eviction = **Phase 4**.
- **Verdict:** aborts are clean no-ops; the mechanism is correct. Fixing either now is throwaway interim
  code that those phases replace → **defer.** To *exercise* the mechanism for sign-off, use a directed
  test that **forces** a few eligible (clean, client-free) victims rather than chasing the natural rate.

### ⚠️ Design tension to revisit in Phase 2 — "cold = full"

The dst-abort reason is not just "Phase 4 will fix it" — it's a structural tension in the SBC premise:
the coldest set by saturation tends to be the **fullest** (cold ⇒ not being evicted ⇒ no free ways). So
"migrate to the coldest set" fights "the coldest set has no room." Implication: the destination metric
may need to be **"cold AND has free ways"**, not cold alone — or eviction becomes *mandatory* (not
optional) for any useful migration rate. **Flag early — it may reshape the DSS.**

---

## Risks to keep in view (Phase-1 relevant; full register in challenges doc)

- **Keystone invariant:** one MSHR owns its set for its whole plan. Migration spans 2 sets → the
  migrating MSHR must own **both** `s` and `d`; only one migration per bank.
- **A1 — cross-set data race:** reserve both sets (`dstValid`/`dstSetConflict`). Done.
- **A3 — false hit on displaced:** displaced-aware hit. Done. (BUG-1 is the *flip side*: hiding it from
  hits, but still treating it as a live way elsewhere.)
- **A4 — duplicate/stale copy:** "displaced XOR native" is a Phase-3 correctness requirement (secondary
  search must run before any memory acquire on an associated source set). Phase 1 accepts the dead
  duplicate (clean only).
- **A5 — wrong-address writeback:** avoided in Phase 1 by "migrate into free way else abort". BUG-1
  shows a related leak — a displaced way being eviction-eligible writes back to the wrong address.
- **`dstSetConflict` blocks C/X to `d`, not just A:** bounded/safe for debug-triggered, clean Phase 1;
  must become nest-capable before Phase 2 (demand-triggered) or it's a latent deadlock.
- **`migInFlight` has no timeout:** if an injected migrate never allocates, the one-shot sticks and
  stops future migrations. Low risk for directed tests; revisit before Phase 2.

---

## Invariants (sim asserts)

- Displaced install ⇒ `dirty=0 && clients=0 && displaced=1`.
- ≤1 MSHR with `dstValid`; ≤1 copy in flight per bank.
- SCU self-check (step 6): after write, re-read `(dstSet,dstWay)`, assert `== blockBuf`.
- `w_copy` holds before dir-write #2 / any later write to the copied way.

---

## Verification plan

1. **Elaborate** through Chipyard sbt after each change.
2. **Bare-metal directed test** (Verilator, misshit-style):
   - Prime `srcSet` with **loads** (clean + L1D-flushed ⇒ eligible; stores ⇒ dirty ⇒ abort).
   - `SBC_BalanceSet` srcSet → drive misses to saturate ⇒ expect `SBC_Migrations++`; a load to the
     migrated address misses but returns the **correct** value. Full `dstSet` ⇒ `SBC_Aborted++`.
   - **Coverage boundary:** the displaced copy in `d` is dark — SW can't read it back. Byte-correctness
     is proven by the SCU `s_verify` assert + `sbcDebug` printf, not the bare-metal load.
3. **Concurrency/races** — multicore config: one core re-fires migrations on `s` while another streams
   loads to addresses mapping to `s` and `d`. The RTL asserts (≤1 owner/set, ≤1 migration,
   `dstSetConflict`) are the real net.
4. **Baseline parity** — separate `sbc-baseline` branch; with no arm and `sbcAutoMigrate=false`,
   behaviour matches upstream.

**Sign-off ≠ hit-rate.** Success = migrations fire, data correct, asserts quiet. Durable balancing is
Phase 3.

---

## SBC MMIO map (control base `0x2010000`)

| Offset | Field | R/W | Notes |
|---|---|---|---|
| `0x300` | SBC_SetSel | W | set to observe |
| `0x308` | SBC_SetSat | R | sat of selected set |
| `0x310` | SBC_ColdestSet | R | DSS coldest set |
| `0x318` | SBC_ColdestLevel | R | sat of coldest set |
| `0x320` | SBC_Status | R | b0=enabled, b1=coldestValid, b2=AT[sel].valid |
| `0x328` | SBC_Migrations | R | committed |
| `0x330` | SBC_SecHits | R | 0 in Phase 1 |
| `0x338` | SBC_SecMiss | R | 0 in Phase 1 |
| `0x340` | SBC_BalanceSet | W | arm migration for the written source set |
| `0x348` | SBC_Attempted | R | migrations attempted (setup reached) |
| `0x350` | SBC_Aborted | R | migrations aborted (ineligible src/dst) |

If any offset changes in `Control.scala`, update `sw/set_migration.c` in the same change.

---

## Roadmap

- **Phase 0 ✅** — observation: sat counters + DSS, MMIO read-back.
- **Phase 1 ✅ CLOSED** — proved the migrate *primitives* (two-set ownership, SCU copy, displaced install
  + dual dir-write, displaced-aware hit, hazard interlocks). The standalone-injected trigger it used is
  superseded; BUG-2 is an artifact of that scaffold and is removed (not patched) in Phase 2.
- **Phase 2 (active → [phase-2.md](phase-2.md))** — migration moves **inside the demand-miss eviction**:
  the demand MSHR migrates its clean victim instead of releasing it, then refills the freed slot. No
  separate requester ⇒ BUG-2 gone. dir-write #2 becomes the demand refill; copy↔refill interlock (A2)
  goes live. Task 0 = turn on `s_verify`.
- **Phase 3** — secondary search + swap-home: a 2nd dir read matches displaced entries → makes `d`
  observable; enforces displaced XOR native (fixes A4). **First task: add the deferred SCU `s_verify`
  copy-correctness check (Step 6) — the copy becomes observable here, so verify it before relying on it.**
- **Phase 4** — displaced eviction (`homeSet` reconstruction via `expandAddress`); removes
  abort-if-no-free-way.
</content>
</invoke>
