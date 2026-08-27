# SBC — Implementation Challenges & Risk Register

Companion to [SBC_integration_plan.md](SBC_integration_plan.md), [SetBalanceUnit_design.md](SetBalanceUnit_design.md),
and [phase-1.md](phase-1.md). This document captures the **things that can bite us** during
implementation — the silent-corruption / deadlock / coherence pitfalls — and the mitigation each
phase must bake in. Read this before touching the migrate / secondary-search datapath.

> **Phase-2 status (2026-06-30).** The Phase-1/2 risks below are **handled** — A1, A2, A3, B1 are
> implemented and verified; A5's hazard is why displaced victims are *silently dropped* (no release).
> One Phase-2 risk that bit us is recorded as new entry **A6 (displaced accumulation)** — also fixed.
> A4 (displaced XOR native) remains a **Phase-3 correctness requirement**. See [phase-2.md](phase-2.md)
> and [bug-fix-log.md](bug-fix-log.md).

---

## 0. The keystone invariant (root of ~half the risks)

> **At most one MSHR is ever active on a given set, and it owns the entire set (all ways, all data
> banks) for the duration of its execution plan.**

Stated in the RTL itself ([BankedStore.scala:364](../design/craft/inclusivecache/src/BankedStore.scala#L364)):
*"...their operation is fully contained within an execution plan of an MSHR. That MSHR owns the entire
set, so there is no way for a data race."* Enforced by the Scheduler's `setMatches` / `alloc` logic
([Scheduler.scala:172](../design/craft/inclusivecache/src/Scheduler.scala#L172)).

**Migration touches two sets (`s` and `d`). Nothing in the stock machine contemplates a single
operation spanning two sets.** Every cross-set data race and deadlock below is a corollary. The
mitigation is structural and non-negotiable: **the migrating MSHR must own *both* `s` and `d`, and
only one migration is in flight per bank.** Get this right and most of the rest is tractable.

---

## 1. Data corruption

| # | Risk | Cause | Mitigation | Phase |
|---|------|-------|------------|-------|
| A1 ✅ | **Cross-set data race** — migrate writes data/dir into `d` while another MSHR owns `d` | No-race proof assumes one MSHR/set; migration spans `s`+`d` | Reserve both sets: publish `dstSet`/`migrating` in `MSHRStatus`; `dstSetConflict` fences set `d`. **✅ DONE (P2).** Hardened by the **allocation-side fence** (`allocReady = alloc && !dstSetConflict`) after the dst-collision bug — gates both alloc points, not just acceptance. | 1 |
| A2 ✅ | **Copy ↔ refill overwrite** — refill into `(s,vWay)` clobbers victim before copy reads it | Refill (SinkD) and copy-read both hit `(s,vWay)`; only `evict_safe`/`grant_safe` exist today | Hazard interlock: copy gated by `copy_safe` (RaW) / `copy_wsafe` (WaR) on SourceD; outer Acquire held until copy-read-done (A2). **✅ DONE (P2)** — `copy_wsafe` race was Bug B, fixed. | 2 |
| A3 ✅ | **False hit on a displaced line** — a native lookup in `d` matches a displaced tag, returns wrong block | Directory hit is pure `tag===w.tag && state=/=INVALID`; displaced line carries its *home* tag | Native hit is displaced-aware: `hits(i) := tagMatch && state=/=INVALID && !ways(i).displaced`. **✅ DONE (P1/P2)** ([Directory.scala:163](../design/craft/inclusivecache/src/Directory.scala#L163)). Displaced population stays *dark* until secondary search. | 1 |
| A4 🔴 | **Duplicate / stale copy** — same address resident native in `s` *and* displaced in `d` | A miss in `s` goes to memory while the displaced copy still lives in `d`; later one is invalidated, the other survives stale | Maintain invariant **displaced XOR native, never both**. Secondary search MUST be consulted on every miss in an associated source set *before* a memory acquire. This makes secondary search a **correctness** requirement, not a perf feature. **🔴 Phase 3.** | 3 |
| A5 ⚠️ | **Wrong-address writeback/release** — evicting a displaced line writes back/releases to its physical-set address | `expandAddress(tag, physSet, off)` rebuilds from the physical set; a displaced line sits in `d` but its address maps to `s` | **P2 mitigation:** a reclaimed displaced victim is **silently dropped — never released** (it's clean+client-free, so no writeback/probe needed); the MSHR's release path is skipped for displaced victims and the assert enforces "no release of a displaced victim". Phase 3/4: if a displaced line ever needs a *real* writeback, reconstruct `homeSet = AT[d].assocSet`. **✅ avoided in P2 by silent drop.** | 4 |
| A6 ✅ | **Displaced accumulation → bricked set** — displaced ways pile up until a set has no victim | Displaced ways were excluded from hits AND every victim tier → immortal; `nonDisplacedOH=0` → `victimWayOH=0` → `Directory.scala:156` assert | **Last-resort displaced-reclaim victim tier** (`displacedOH = ~nonDisplacedOH`, lowest priority) + MSHR silent-drop. Safe: displaced ⇒ clean+client-free. **✅ DONE & VERIFIED (P2).** Caveat: a 7/8-displaced set recycles its one native way until Phase-3 spreading. | 2 |

---

## 2. Deadlock

| # | Risk | Cause | Mitigation |
|---|------|-------|------------|
| B1 ✅ | **Two-set ownership cycle** — MSHR owning `s` waits on `d`, while an MSHR on `d` waits back on `s` | Block/nest/queue discipline guarantees bounded waits *per set*; a two-set hold can form a cycle it never anticipated | One migration per bank; the migration runs entirely on the owning MSHR's own ports, so a *held* demand can never block it — the demand waits one-directionally for the migration to retire (bounded, acyclic). **✅ DONE & VERIFIED (P2)** via the allocation-side fence. |
| B2 | **Destination-eviction recursion** — evicting `d`'s victim triggers another migration | Unbounded migrate-begets-migrate | Phase-1 rule "migrate into free way else abort" sidesteps it; when real displaced-eviction lands (Phase 4) it is a plain bounded `SourceC` release — **migration never begets migration**. |
| B3 | **Second-read starvation / comb loop** — secondary-search read deadlocks vs existing read arbitration | Single directory read port, already multiplexed ([Scheduler.scala:265](../design/craft/inclusivecache/src/Scheduler.scala#L265)); note the existing flow-Q warning ([Directory.scala:75-76](../design/craft/inclusivecache/src/Directory.scala#L75)) | Sequence the 2nd read as an explicit lower-priority state; never hold the request channel while waiting. |
| B4 | **Outer-channel backpressure** — extra Release for `d`'s victim can't drain | MSHR now sources more outer C traffic | Don't gate `d`'s-victim `s_release` behind migration completion; verify ReleaseAck path always progresses. |

---

## 3. Coherence / protocol

- **Inclusivity (the subtle one).** An inclusive L2 must hold every line any L1 holds, findable *via
  its address set*. Displacing a client-held line breaks probe-reachability → cross-core coherence
  bug. This is why **client-free is near-mandatory**, not just a simplification (relaxing it would
  require a displaced-aware B-channel probe path).
- **Nested transactions mid-migration.** An outer Probe / inner Release for set `s` can nest
  (`bc_mshr`/`c_mshr`, [Scheduler.scala:287-301](../design/craft/inclusivecache/src/Scheduler.scala#L287))
  while the copy is half-done and the directory half-updated. **Make the dir transition atomic from
  the coherence view** — the migrating MSHR owns both sets across the whole `dir-write #1 / #2`
  window, so no intermediate state is coherence-visible. Note migration needs **two** dir writes but
  an MSHR emits one per step ([MSHR.scala:190](../design/craft/inclusivecache/src/MSHR.scala#L190)) →
  extra scoreboard bits, sequenced.
- **`repeat`/reload fast-path staleness.** A retiring MSHR can reload a same-set request without
  re-reading the directory if tags match ([Scheduler.scala:235](../design/craft/inclusivecache/src/Scheduler.scala#L235)),
  reusing cached meta. A migration that altered the set invalidates that assumption → flush the
  repeat path after a migration.

---

## 4. Stalling / livelock (perf-correctness)

- **Migration ping-pong** (`s→d→s→…`): add a hysteresis dead-band (`migrationThreshold` vs
  `migrationClearThreshold`); stop forming associations once `sat[s] < clearThreshold`.
- **BankedStore occupancy**: the copy engine holds data ports for N beats. Give it **lowest** arbiter
  priority ([BankedStore.scala:155](../design/craft/inclusivecache/src/BankedStore.scala#L155)) and
  bound its occupancy so it can't starve demand hits/refills.
- **Secondary-read latency tax**: gate strictly on `AT[k].valid && !AT[k].sd` so non-participating
  sets pay zero.

---

## 5. Policy-scope trade-off: "clean + client-free" displacement

Splitting the restriction (revisited during design):
- **Client-free** — effectively *forced* by inclusivity (see §3). Low value to relax, high cost.
- **Clean** — a *real* coverage cap (dirty reuse won't be recovered as secondary hits), and
  workload-dependent. Relaxing it at last-level is feasible for client-free lines **but escalates the
  failure mode**: with clean lines a secondary-search bug is "wasted capacity"; with dirty lines the
  displaced copy is the *only* correct copy, so the same bug returns **stale data from memory = silent
  corruption** (A4). Decision: keep clean+client-free for v1; treat "dirty-capable (client-free)" as a
  data-gated optional later phase, hardened secondary search required. (User has already gathered the
  observation data informing this.)

---

## 6. SetBalanceUnit signal visibility (forward-looking)

Probe-aware counters are on the roadmap, so the SBU taps the coherence channels **now** (observation
only — cheap, avoids re-plumbing the Scheduler bundle later):
- `dirEvent` ← `directory.io.result` + read set → saturation counter (miss++ / hit--), migration
  eligibility snapshot (`dirty`/`clients`/`displaced`).
- `probeOut` ← `sourceB.io.req.fire` (set) → future per-set probe-pressure counter.
- `probeResp` ← `sinkC.io.resp.fire` with `isToN(param)` → probe-invalidation events.

**Why probe pressure matters for policy:** it separates *capacity* pressure (conflict eviction → good
migration source) from *coherence* pressure (a set whose lines keep getting probe-invalidated → bad
source — you'd migrate lines about to die). Probe-pressure can later bias the saturation counter or
veto `migrate_eligible`. Keep all of this *inside* the SBU; the MSHR only sends events and reads
single-bit answers (`migrate_eligible[]`, `secondary_enable[]`) — this is what preserves the
simplicity-first goal and keeps the protocol FSM nearly unchanged.

---

## Quick rules of thumb
1. If an operation touches two sets, ask: *does one MSHR own both for the whole window?* If not, stop.
2. Never let a memory acquire fire for an associated source set without consulting secondary search.
3. A displaced entry must **always** satisfy `dirty=0 && clients=0 && displaced=1` — assert it at every install.
4. New BankedStore traffic = new hazard interlock. There is no free data movement.
5. Verify baseline parity by diffing the pristine baseline branch — not by gating hardware.
