> ⚠️ **RETIRED / MERGED (2026-06-30).** Folded into [../phase-2.md](../phase-2.md) and
> [../bug-fix-log.md](../bug-fix-log.md). The bug it describes is **FIXED**. Kept for history only —
> do not treat as current.

# SBC Phase 2 — Destination-Set Collision Bug (illegal inner-D) + s_verify / priority findings

**Branch:** `set_migration_refactored`
**Logged:** 2026-06-26 (week wrap-up)
**Status: FIXED (2026-06-30) — allocation-side fence SHIPPED; forced repro `dst_collision_repro`
PASSES (8 migrations committed, data correct). Stock-config regression still owed. A *new* blocker —
displaced-line accumulation — surfaced once runs survived this bug. See §9.**
(Historical status below: OPEN — mechanism CONFIRMED, fix DECIDED but HELD pending a deterministic repro.)

This is the running record for the work done after Phase 2a/2b landed. Three things happened this
week, in order:

1. **`s_verify` was disabled** (it deadlocked).
2. We asked whether the copy port's **low BankedStore priority** could deadlock the *other* copy
   states (s_read / s_write) too — call this **Q3**. We stress-tested it. **Q3 = rejected.**
3. The stress test surfaced a **new, real bug**: an **illegal inner-D ("D acknowledged for nothing
   inflight")** when a migration's destination set is under concurrent CPU demand. Mechanism now
   **confirmed**.

Single source of truth for Phase 2 overall is still [phase-2.md](phase-2.md); this file is the deep
record for the above. [phase-2-2b-handoff.md](phase-2-2b-handoff.md) is the older (2a/2b) historical
record.

---

## 1. `s_verify` disabled — what and why

**What:** `SetCopyUnit` no longer re-reads the destination to self-check the copy. The `s_verify`
state now falls straight through to `s_done`
([SetCopyUnit.scala:217](../design/craft/inclusivecache/src/SetCopyUnit.scala)).

**Why it had to go (the deadlock, plain English):** after writing the copied block to the
destination way, `s_verify` issued a **re-read** of that same destination to compare it against the
buffer. That read uses the **lowest-priority** BankedStore port (`sourceCopy_rreq`, last in `reqs`
at [BankedStore.scala:163](../design/craft/inclusivecache/src/BankedStore.scala)). Under load it
never got its turn → the read never returned → `s_verify` never finished → `io.done`/`copy_done`
never pulsed → the MSHR's `w_copy` never set → `migrating`/`dstValid` never cleared → the
destination fence (`dstSetConflict`) never opened → new traffic to that set blocked forever →
TLMonitor eventually fired.

**Why disabling is safe:** the copy already committed through the shared BankedStore (write-through
semantics). `s_verify` only *re-checked* correctness; it was never load-bearing for correctness.
Data still verifies PASS in software. `s_verify` returns as **Phase 3** work, behind a proper
high-priority (or dedicated) read path so it can't be starved.

---

## 2. Q3 — can the copy port's low priority deadlock s_read / s_write too? → **REJECTED**

### The worry
`sourceCopy_rreq` / `sourceCopy_wreq` are last in the BankedStore priority list (zero fairness — pure
combinational priority, [BankedStore.scala:167-181](../design/craft/inclusivecache/src/BankedStore.scala)).
If the disabled `s_verify` starved, maybe `s_read` (reads the *unfenced* source set — maximum
contention) and `s_write` could starve too.

### The experiment (bank-saturation stress test)
Added a **stall classifier** in `SetCopyUnit` (sbcDebug-gated, zero hardware off) that separates:
- **ARB-STALL**: copy port `valid && !ready` (true arbitration starvation),
- **HAZARD-STALL**: copy port held low by `copy_safe`/`copy_wsafe`,
- **DEGENERATE**: copy port never asserts in a copy state.

Counters log on crossing 64 / 256 / 1024 / 4096 cycles. A counter that **grows unbounded** =
deadlock; one that **resets each beat** = bounded delay. Then ran a saturation workload (hot set
missing hard + concurrent miss streams to other sets + stores, to keep all sub-banks busy across the
copy window).

### The result — decisive
| Metric | Count |
|---|---|
| MIG-START (migrations attempted) | 442 |
| MIG-COMMIT (fully committed) | 9 |
| ABORT-DST (dst had no evictable way) | 433 |
| EVICT-NORMAL | 127,787 |
| **ARB-STALL ≥ 64 cyc** | **0** |
| HAZARD-STALL / DEGENERATE | 0 / 0 |

The classifier stayed **completely silent**. Under maximum bank contention the copy read+write never
stalled even 64 cycles — copies complete in consecutive cycles. **H1 (copy-port starvation) is
rejected.** Low priority causes *bounded delay*, not deadlock.

### The standing conclusion (do NOT relitigate)
**Do not reorder the BankedStore priorities.** Two reasons:
- It buys nothing (0 stalls measured), and
- The ordering `sinkC > sourceC > sinkD > sourceDw > sourceDr` is **load-bearing for protocol
  deadlock-freedom** ([BankedStore.scala:97-107](../design/craft/inclusivecache/src/BankedStore.scala)).
  Promoting the copy port above `sourceD_*` could starve demand Grants → a *real* coherence
  deadlock, strictly worse than the migration livelock it would "fix."

If copy-port robustness is ever wanted, the correct tool is **bounded anti-starvation on the copy
port only** (it wins its bank after N blocked cycles), preserving the protocol order — a Phase 3
item, not needed now.

> Note: the *original* `s_verify` hang was on the **fenced** destination set, which should have had
> *less* contention than the source set `s_read` traverses fine. So the "low priority got starved"
> story never fully fit `s_verify` either. We sidestepped it (disable) rather than root-cause it;
> Run B (re-enable `s_verify` under the classifier) was not done. Status: **sidestepped, not
> root-caused** — fine for now, honest to note.

---

## 3. NEW BUG — illegal inner-D when a migration's destination set is under demand

### Symptom
Sim aborts with the inner (L2↔L1) TLMonitor assertion:
```
Assertion failed: 'D' channel acknowledged for nothing inflight
```
i.e. the L2 sent the CPU a response that matches no outstanding request. It dies on the **9th
migration retiring to `dstSet=0`** — a set the workload was actively hammering — with `DST-FENCE
blocked reqSet=0` logged at retirement.

### Diagnostic (mechanism 1 vs mechanism 2)
Two candidate mechanisms:
- **Mechanism 1** — an **independent in-flight MSHR** on the destination set (the fence missed it).
- **Mechanism 2** — the **migrant itself double-grants** (coincidental dst correlation).

Two sbcDebug-gated probes were added in `Scheduler.scala`:
- **DST-COLLIDE** — edge-detect: a *second* MSHR is resident on a migrant's `dstSet`.
- **INNER-D** — log every CPU-facing D beat during a migration + 15-cycle retire shadow, tagged with
  opcode / source / sink (= MSHR id).

Tail of the failing run:
```
DST-COLLIDE migMshr=0 dstSet=0 otherMshr=1
INNER-D opcode=5 (GrantData)     source=32 sink=0   ← migrant MSHR0, legit set-5 refill grant
INNER-D opcode=1 (AccessAckData) source=0  sink=1   ← OFFENDER, from MSHR1 (the set-0 resident)
```
The offender's `sink=1` ≠ migrant's `sink=0` → **not a double-grant. Mechanism 2 ruled out.**
DST-COLLIDE independently shows MSHR1 resident on the migrant's destination set. **Mechanism 1
confirmed.**

### Root cause (proven)
`dstValid := migrating` rises only at the **eviction gate**
([MSHR.scala:221](../design/craft/inclusivecache/src/MSHR.scala)), which is ~2–3 cycles **after**
the MSHR allocates and latches its migrate advice. The fence `dstSetConflict` keys off `dstValid`
([Scheduler.scala:198](../design/craft/inclusivecache/src/Scheduler.scala)), so the
**[advice → gate] window is unfenced.** Sequence:

```
migrant latches advice dst=0 (set 0 currently unowned)
   │   ← window: fence not up yet
   ▼
a demand request allocates MSHR1 on set 0   ← slips in, legal at the time
   │
   ▼
migrant reaches gate: raises dstValid (fence engages now — too late)
   │   later: dir-write #1 installs displaced @(0, dWay) while MSHR1 is mid-transaction
   ▼
MSHR1's bookkeeping is corrupted → it emits AccessAckData(source=0) with nothing inflight
   ▼
inner TLMonitor asserts
```
`DST-FENCE blocked reqSet=0` confirms the fence engaged only **after** MSHR1 was already in.

It is a **timing race**: migrations targeted contended sets several times, but only the 9th lined up
the allocate-inside-the-window timing and tripped it. **Data stayed correct (PASS)** — this is a
protocol-legality bug, not corruption.

### The CLAUDE.md / BUG-2 invariant gap (now confirmed)
CLAUDE.md claims BUG-2 ("illegal inner-D") is "removed by construction in Phase 2 — migration is the
demand MSHR's own work, no second requester." That reasoning **only covers the migration *source*.**
An independent demand to the migration's **destination** set is a real second requester. The
Scheduler's Q1 reasoning only ever blocked a *second migration*, never a *demand to `dstSet`*. The
invariant needs amending once the fix lands.

---

## 4. Fix direction — DECIDED, held until repro exists

**Decision: conservative gate-time abort. Migration yields to demand. Do NOT tighten the fence.**

- Migration is **optional** (best-effort optimization); a CPU demand is **mandatory**. So migration
  must always yield.
- **At the eviction gate, abort the migration** (reuse the existing `ABORT-DST` fallback → normal
  eviction) **if the destination set is owned by — or being allocated into by — another MSHR this
  cycle.** The gate is *before* the copy and the displaced-install, so aborting there is clean and
  leaves no half-done state.
- For the 1-cycle gate↔allocate race: cover it in the **abort check**, not the fence — abort if
  `dstSet` is owned by another MSHR **OR** a request is being accepted into `dstSet` on the same
  cycle. Demand always wins; migration steps aside.
- **Do NOT** make the fence stall demand to wait on a migration — that re-introduces exactly the
  hang the original code comment feared, and is the wrong direction.

Why not just "tighten the fence to fire one cycle earlier": the window is inherent to advice
preceding the gate; closing it by fencing earlier means fencing on an advice that **might abort
anyway**, stalling demand for nothing. Yielding is simpler and always correct.

---

## 5. Next step — deterministic repro BEFORE the fix

The bug fired **once** in a whole run (nondeterministic). Without a reliable trigger, "the assert
didn't fire after my fix" could be luck, not proof. So the repro is the fix's test oracle.

**To build (off by default, zero hardware when off):**
1. `sbcForceDstSet` debug knob — force the DSS/advice destination to a **known** set.
2. A directed C case that drives **continuous CPU demand to that exact set** while the hot set keeps
   migrating into it. With every migration racing the demand stream, the [advice→gate] window
   collision should reproduce **reliably**.
3. Confirm the repro fires the assert every run. **Then** apply the §4 fix. Confirm: assert gone,
   data still PASS, and the DST-COLLIDE/INNER-D probes show collisions now ending in a clean
   **abort** instead of an illegal reply.

Keep both probes in (sbcDebug-gated) — proven useful.

---

## 6. Side tickets (separate from the fix — do not bundle)

- **DSS picks contended sets as "cold."** Migrations kept choosing hammered sets (0, 3) instead of
  the genuinely idle set 7. This both (a) drove the 433/442 abort rate and (b) is what steered
  migrations into contention and surfaced the bug. Real defect in the coldness metric (or the test
  defeats it). **Performance ticket** — fixing it would *hide* the race, not remove it. Do the §4
  correctness fix regardless.
- **`s_verify` return** — re-enable behind a non-starvable read path (Phase 3).
- **CLAUDE.md BUG-2 invariant** — amend once the fix lands (see §3).

---

## 7. One-paragraph status for next week

Phase 2a/2b data path is correct and committing under load (9 commits in the stress run). The
copy-port-starvation worry (Q3) is **disproven** — leave BankedStore priorities alone. `s_verify` is
**disabled** (starvation deadlock; returns in Phase 3 with a proper read path). One **open protocol
bug**: a migration whose destination set is hit by independent CPU demand during the unfenced
[advice→gate] window corrupts that demand MSHR and emits an illegal inner-D — **mechanism confirmed
(mechanism 1)**, **fix decided (gate-time yield-to-demand abort)**, **held** until the deterministic
`sbcForceDstSet` repro exists to validate it. Next action: build that repro, then apply the fix.

---

## 8. UPDATE — two snapshot fixes FAILED; root cause is a blind spot; real fix = allocation-side fence

The `sbcForceDstSet` repro was built and the bug now fires deterministically (forced dst=0, hammer
set 0). Two abort-based fixes were then tried and **both failed**. This section records why, because
the failure mode is the important lesson.

### What was tried (and why each missed)
1. **Single-sample check at dread-result** (`io.dstCollide` sampled once). Missed: the colliding MSHR
   wasn't `status.valid` yet at the decision — it became valid *during the copy*, after dread-result.
2. **Acceptance-watch latch** (`dstCollisionSeen`, armed on `migAdviceValidReg`, watching
   `acceptedSet === migAdviceDstReg`). Also missed — and the run proved it inert (`ABORT-DST=0`,
   crash unchanged, 2nd migration srcSet=2→dstSet=0, ~426µs).

### The blind spot (root cause — confirmed)
The colliding request was **accepted ~13 cycles before the migrant was even allocated**, sat queued
in the pipeline, and **allocated on dstSet *after* the migrant had already passed dread-result**
(during the copy). So at the migrant's decision moment it was: (a) not yet a valid MSHR, and (b) its
acceptance pulse fired *before the migrant existed*, when the latch wasn't armed. Confirmed:
`io.status.valid := request_valid` is set at allocate (MSHR ~L226/680) — `status.valid` does **not**
lag allocation; the lag is **acceptance → allocation** (~13 cyc, pipelined/queued), which is *longer
than the migrant's entire allocate→dread-result window*. The existing fence (`dstSetConflict`) blocks
**new acceptances** but does **not** stop an **already-in-flight request from allocating** on dstSet.

**No snapshot or migrant-lifetime latch can ever see this** — the collider's window opened before the
migrant was born and closed after it committed. The whole "detect the collision" family is dead.

### The hard constraint that kills "abort later"
The crash's dst way was **evictable** (valid+clean, 2b path). The copy overwrites its data during
`s_write` while its directory entry is unchanged → aborting **any time after the copy starts** would
corrupt that line. `dread-result` is the *last* safe abort point for the evictable path, but the
collision only becomes visible *after* it. Too early to see, too late to cancel.

### Decision — allocation-side fence (the cache's own serialization), NOT detection
**Root mistake:** we kept bolting on a *detector* instead of using the cache's existing
**one-owner-per-set serialization** (which already holds a conflicting request *at allocation* until
the set is free). The migration's dst ownership was tracked off to the side (`dstSetConflict`) and
that side-fence only gated the **front door** (`request.ready`), not a request already inside walking
toward dstSet.

**Fix:** make `dstSetConflict` gate **MSHR allocation**, not just acceptance. A request whose `set`
matches a live migration's `dstSet` (`dstValid` up) is **held at the allocation step** — stays queued
until the migration retires. In this bug the collider allocated *after* the migrant's gate, so an
allocation-time check (with `dstValid` already up) catches it. This is **demand waiting for the
current owner of a set** — exactly what the stock cache already does for two demands to one set:
bounded, acyclic, deadlock-free (the migration makes independent forward progress and retires in tens
of cycles). It is the **opposite** of the rejected "Option C" (there the *migration* waited).

**Rejected — INVALID-dst-only migration:** abort-after-copy becomes harmless, but it guts the 2b
evictable path; stress runs show most migrations use evictable → migration rate collapses. Fallback
only.

**Residual sub-window (don't pre-build):** the migrant reserves dstSet only at its *gate* (`dstValid`),
a few cycles after it is born; a request allocating in the tiny `[born→gate]` window could still slip.
This bug's collider allocated *after* the gate, so the allocation-fence catches it. Only if a
`[born→gate]` slip is actually reproduced do we reserve from the latched advice (bounded by the
existing migToken timeout).

**Alternatives evaluated & rejected** (different-way / different-set / wait-for-intruder): different
way = same-set conflict; different set = race just moves + heavy FSM; wait = couples a CPU demand to
optional work + deadlock surface. On collision the migration **aborts to normal eviction** (zero added
demand latency) and the system **self-retries on the next miss** (contended set heats up → DSS picks a
colder one). The real performance lever is fixing DSS coldness so collisions are rare.

### Cleanup that ships with this fix
Remove the dead bug-hunt scaffolding: the failed `dstCollisionSeen` latch + `acceptedValid/acceptedSet`
plumbing; the `DST-COLLIDE` / `INNER-D` (`migShadow` D-beat shadow) / `DST-FENCE` diagnostic probes;
and the multi-paragraph investigation comments. Keep the repro knobs (`sbcForceDstSet`,
`sbcGateStallCycles`) and the routine operational counters (`MIG-START/MIG-COMMIT/ABORT-DST`).

---

## 9. UPDATE (2026-06-30) — fence SHIPPED & repro PASSES; a new blocker surfaced

**The allocation-side fence is in.** `allocReady = alloc && !dstSetConflict`, routed into **both**
allocation points — the alloc dir-read ([Scheduler.scala:297](../design/craft/inclusivecache/src/Scheduler.scala#L297))
and the MSHR allocate ([Scheduler.scala:337](../design/craft/inclusivecache/src/Scheduler.scala#L337)).
RTL re-read confirms it gates *allocation*, not just acceptance — a genuine fence, not a snapshot hack.

**Result:** the forced repro `dst_collision_repro` (`VerilatorRocket8KL116KL2DstCollisionConfig`,
`sbcForceDstSet=0`, [sw/dst_collision_repro.c](../sw/dst_collision_repro.c)) **now PASSES** — it
crashed on every prior run. **8 migrations committed, data correct.**

**Still owed (do not skip):** the **stock-config regression** — `migration_stress_test` on the
ordinary `VerilatorRocket8KL116KL2Config` (DSS spreading, not the forced single-set config) — has
**not** been run. Until it passes, "the fence is regression-clean under normal traffic" is
**provisional**, not proven.

**New blocker exposed — displaced-line accumulation (🔴 OPEN).** Surviving the collision bug lets runs
go long enough to hit it. Migration converts `{invalid | evictable}` dst ways → `displaced`; displaced
ways are excluded from hits **and** all four victim tiers, so they are immortal in Phase 2. When every
way of a set is displaced → `victimWayOH = 0` →
[Directory.scala:156](../design/craft/inclusivecache/src/Directory.scala#L156) assert → bricked set
(~8 migrations under the forced config). This is **not** caused by the fence — it is a pre-existing
Phase-2 design gap (no reclaim path). Fix direction: add a **last-resort displaced-reclaim victim
tier** (safe — displaced ⇒ clean+client-free by the [MSHR.scala:384](../design/craft/inclusivecache/src/MSHR.scala#L384)
invariant), which is a forward-compatible slice of Phase 3. Full record:
[bug-fix-log.md](bug-fix-log.md) (open section) and [phase-2.md](phase-2.md).
