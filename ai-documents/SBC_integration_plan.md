# Set-Balancing Cache (SBC) Integration into the Inclusive L2

> Companion: [SetBalanceUnit_design.md](SetBalanceUnit_design.md) holds the SBU IO sketch, the
> three sub-FSMs (migration / secondary-search / displaced-evict) as state diagrams, and the
> risk→mechanism→assert table. This file is the **plan**; that one is the **detailed design**.

## Context

The L2 inclusive cache uses a single physical set per address (`set = f(address)`) with **random**
way replacement and **no per-line reuse/recency state**. Hot sets thrash while cold sets idle, and
every conflict miss pays full memory latency. SBC migrates a would-be-evicted line from a *stressed*
set `s` into a *cold* set `d`; a later miss in `s` does a **secondary search** of `d` and recovers
the line on-chip. **Migration is a bet that only pays off on reuse** — so it must be reuse-gated
(below), or it just pollutes the cold set.

## Decisions locked with the user

1. **Functional RTL** (real data movement), not a model.
2. **Only clean, client-free lines may be displaced.** This keeps the probe/release/refill datapath
   untouched (a displaced line is never client-held or dirty), confining special-casing to
   migrate / secondary-hit / displaced-evict. **Trade-off acknowledged:** "clean" correlates with a
   dead population (streaming reads), so this restriction *must* be paired with the **adaptive
   yield throttle** (Phase 4) or streaming workloads will pollute `d`.
3. **SBU is advisory + bookkeeping only — it owns NO data/SRAM ports.** It holds the per-set
   counters, AT, and DSS, answers two queries, and takes commits. The migration *action* (copy,
   directory writes, the `s_migrate` flow) lives in **MSHR + Scheduler + BankedStore**, where the
   ownership/arbitration/hazard discipline already exists. SBU can give bad *advice* (observable,
   testable) but cannot corrupt data or deadlock.
4. **Migration executes inside an MSHR's owned two-set plan, one migration per bank.** The migrating
   MSHR reserves **both** `s` and `d`; a single per-bank token serializes cross-set moves. This is
   the keystone that preserves the cache's one-owner-per-set invariant.
5. **SBU is set-granular — it never tracks ways.** Destination way (migration) and hit way
   (secondary search) both come from a Directory read of `d`; one "second read" mechanism serves both.
6. **Reuse-gated migration.** The saturation counter is the *set-level* reuse predictor (only migrate
   from hot sets); an **adaptive secondary-hit-yield throttle** turns off sources whose migrations
   don't get reused. A per-line reuse predictor is a future upgrade that plugs into the same query.
7. **Secondary hit = swap-home** (bring L back to `s`), because granting a client a line requires it
   to live in its address set. Serve-in-place ("route all requests to `d`") is the faster but
   coherence-invasive alternative — documented as a future option, not v1.
8. **No in-RTL bit-exactness gating.** Add new state unconditionally; keep the RTL simple/readable.
   Verify baseline parity by **diffing a separate pristine branch** (`sbc-baseline`), not by eliding
   hardware when the flag is off. `enableSetBalancing` gates migration *behavior* for A/B runs.
   *(Update [CLAUDE.md](../../../CLAUDE.md) to relax the "bit-exact baseline" mandate accordingly.)*

## The core constraint (why this is invasive)

This cache hard-wires *physical set = address set*. The directory and BankedStore are indexed by the
address set; `expandAddress(tag, set, offset)`
([Parameters.scala:226](src/Parameters.scala#L226)) rebuilds the real address from `(tag, physSet)`;
client-release matching and probes key off the address set. A line in a *foreign* set breaks address
reconstruction and coherence matching unless it is self-describing.

**Resolution:** a displaced line's home set is recovered from the AT, not stored per line. With a 1:1
association (`d ⇄ s`), a displaced line in `d` has `homeSet = AT[d].assocSet`. Per *line* we add one
bit (`displaced`); everything else is per-set in the AT. Because displaced lines are clean +
client-free, the only sites that must use `homeSet` are: migrate-out, swap-home, displaced-evict.

## Data structures (all in new `SetBalanceUnit`, gated by `micro.enableSetBalancing`)

> **Built fresh on this branch** — there is **no** monitor/`SaturationCounter.scala` infrastructure
> here (that lives on `TL_signal_analysis`). Do not reference or depend on it.

1. **Saturation counter** — `Vec(sets, UInt(satCounterBits.W))`, fed by a **directory-result tap**
   added to the Scheduler (hit → dec, miss → inc). Update is a **pluggable `satDelta(set)`** so the
   future probe-integration just adds weighted terms:
   - `probeTap` ← `schedule.b` fire ([MSHR.scala:286](src/MSHR.scala#L286)) — probe pressure (weight 0 in v1),
   - `relTap` ← `sinkC.io.resp` ([Scheduler.scala:79](src/Scheduler.scala#L79)) — ProbeAck\* vs Release\*, dirty (weight 0 in v1).
2. **Association Table (AT)** — `Vec(sets, {valid, sd /*0=src,1=dst*/, assocSet})`. `AT[s].valid &&
   !AT[s].sd` is the secondary-search-enable bit for `s`. Plus a **per-association displaced count**
   for safe teardown. Registers to start; SRAM fallback if set count makes flop area hurt.
3. **DSS** — fixed `D` entries `{valid, setIdx, satLevel}` + `minReg`/`maxReg`; provides the coldest
   candidate set in O(log D), independent of set count. New file `DSS.scala`.
4. **Reuse/yield counters** (per source set or small bank-global) — migrations issued vs secondary
   hits returned, driving the adaptive throttle (Phase 4).
5. **Directory entry bit** — add `displaced: Bool` to `DirectoryEntry`
   ([Directory.scala:29](src/Directory.scala#L29)), **unconditionally** (decision 8). Grows
   `codeBits` by 1 (assert stays `<= 256`).

**SBU interface** (see companion doc for the bundle): taps in; `migrateQuery(s)→{migrate,destSet}`;
`assocQuery(k)→{activeSource,assocSet}`; `commit{kind,src,dst}`; read-only MMIO stats.

## Control / MMIO (reuse existing flush-control wiring pattern)

Add to [Control.scala](src/Control.scala): `sbcEnable`, `migrationThreshold` (T_hi),
`migrationClearThreshold` (T_lo, hysteresis); `dssEntries` is compile-time. Read-only counters:
migrations, secondary hits, secondary misses, active associations, **per-source yield** (for the
throttle). Plumb through [InclusiveCache.scala](src/InclusiveCache.scala) like the existing control
block.

## Datapath changes (phased — sequenced to retire risk before adding value)

> **Progress (2026-06-30):** Phase 0 ✅, Phase 1 ✅ CLOSED, **Phase 2 ✅ COMPLETE & verified**. Two
> mechanisms were added beyond this original plan during Phase 2: the **allocation-side destination
> fence** (dst-collision fix) and the **last-resort displaced-reclaim victim tier** (anti-brick fix).
> Authoritative current state: [phase-2.md](phase-2.md) + [bug-fix-log.md](bug-fix-log.md).

### Phase 0 — Scaffolding & observation (no migration)  ✅ DONE
- Add params (decision 8) + `displaced` to `DirectoryEntry`, default `false` at every write site
  (the `invalid` wire [MSHR.scala:268](src/MSHR.scala#L268), `final_meta_writeback`, refill).
- Add the directory-result tap; build `SetBalanceUnit` (sat counter + AT + DSS), migration **off**.
- Exit: builds; counters track and DSS min is sane in sim; record the baseline-branch diff.

### Phase 1 — Two-set ownership + copy engine (mechanism, no policy)  ✅ CLOSED
Build and prove the dangerous primitives **before** any policy can fire them.
- Extend `MSHRStatus` ([MSHR.scala:42](src/MSHR.scala#L42)) with `migrating`/`dstSet`; fold `dstSet`
  into the Scheduler set-conflict logic (`setMatches`/`mshr_stall`, [Scheduler.scala:89](src/Scheduler.scala#L89)/[:172](src/Scheduler.scala#L172))
  so a migration reserves **both** sets. Enforce **one migration per bank** (token).
- Add a BankedStore set-to-set copy path with a **copy↔refill hazard guard** (refill write to
  `(s,vWay)` waits on copy-read-done), modeled on `evict_safe`/`grant_safe`
  ([SourceD.scala:375](src/SourceD.scala#L375)).
- Exit: directed test forces a copy, asserts identical data + correct serialization of demand
  traffic to `s` and `d`; assert ≤1 owner per set.

### Phase 2 — Migrate-on-eviction (write-path policy, reuse-gated)  ✅ COMPLETE & VERIFIED
- At [MSHR.scala:606](src/MSHR.scala#L606), gate `s_migrate` when **all** hold:
  `enableSetBalancing && sat[s] > T_hi && !meta.dirty && !meta.clients.orR && !meta.displaced &&
  DSS has a dest && token free`. Migrating ⇒ **no** `s_release`.
- `s_migrate`/`w_migrate*` mirror `s_release` ([MSHR.scala:184-212](src/MSHR.scala#L184)). Two
  directory writes (install displaced in `d`; refill rewrites `s`) sequenced so **no
  coherence-visible intermediate state**; nesting blocked on both sets across the window. If `dWay`
  occupied → real release first (nest displaced-evict if that victim is itself displaced).
- `commit{MIGRATE,s,d}` → `AT[s]→d`, `AT[d]→s`, `assocCount++`.
- Exit: hot/cold microbench shows migrations via MMIO; coherence regression passes; asserts hold.

### Phase 3 — Secondary search + swap-home (read path) — correctness-critical
- On A-miss in `s` with `assocResp.activeSource`: second directory read of `d = AT[s].assocSet`
  (serialize at the read arbiter [Scheduler.scala:265](src/Scheduler.scala#L265), strictly below
  demand reads). Hit detection is **displaced-aware** (native reads require `!displaced`).
- **Secondary hit ⇒ swap-home (migrate-back):** evict s-victim **V** (V cannot migrate, token busy
  ⇒ swap V into L's just-freed slot in `d` if clean+client-free, else drop/release V); copy
  `(d,wDisp)→(s,vWay')`; install L native (`displaced=0`), invalidate `(d,wDisp)`; grant via the
  clean refill-less path. **Invariant:** L is never resident in both `s` and `d` — assert it.
- **Secondary miss ⇒** normal memory acquire, unchanged.
- Exit: secondary-hit microbench shows hits + reduced miss latency; no duplicate-line assert; regression passes.

### Phase 4 — Displaced eviction, teardown & adaptive yield throttle
- Evicting a `displaced` way: reconstruct `homeSet = AT[d].assocSet`, Release at that address (clean
  ⇒ no data) — the only edit to the writeback address path. Invalidate `(d,wDisp)`.
- Teardown: keep an association until its displaced count drains; recycle AT only then. Stop forming
  associations once `sat[s] < T_lo` (hysteresis → no ping-pong).
- **Adaptive yield throttle:** track secondary-hit yield per source; if a source's migrations rarely
  get reused, stop migrating from it (handles the clean-vs-dead / streaming-pollution risk).
- Exit: long streaming + random run shows stable associations (no leak, no streaming pollution);
  baseline-branch diff unchanged from Phase 0.

## Critical files

| File | Change |
|------|--------|
| [Parameters.scala](src/Parameters.scala) | `enableSetBalancing`, `satCounterBits`, T_hi/T_lo, DSS params |
| [Directory.scala](src/Directory.scala) | `displaced` bit; displaced-aware hit; second read |
| `SetBalanceUnit.scala` (new) | fresh sat counter + AT + DSS + yield; queries/commit; **no data ports** |
| `DSS.scala` (new) | fixed-size coldest-set selector |
| [MSHR.scala](src/MSHR.scala) | `migrating`/`dstSet`; `s_migrate`/`w_migrate*`; swap-home; homeSet on displaced evict |
| [Scheduler.scala](src/Scheduler.scala) | dir/probe/release taps; two-set reservation + token; second-read sequencing; SBU wiring |
| [BankedStore.scala](src/BankedStore.scala) | set-to-set copy path + copy↔refill hazard guard |
| [Control.scala](src/Control.scala) / [InclusiveCache.scala](src/InclusiveCache.scala) | MMIO regs + plumbing |
| [CLAUDE.md](../../../CLAUDE.md) | relax bit-exact mandate; record baseline-branch + simplicity-first |

## Verification

1. **Build** through the Chipyard sbt flow after every phase.
2. **Invariants as Chisel `assert`s** (the silent-bug catchers): displaced ⇒ `!dirty && !clients`;
   an address is never native-in-`s` **and** displaced-in-`d`; AT symmetric; migrated line issues no
   memory writeback; ≤1 owner per set; ≤1 migration token live; displaced-evict release uses
   `homeSet`. (Full risk→assert map in the companion doc.)
3. **Baseline parity:** diff elaborated RTL vs the pristine `sbc-baseline` branch — delta confined to
   SBC additions; with `enableSetBalancing=false`, behavior matches baseline.
4. **Directed RISC-V microbenchmarks** in Verilator (hot+cold set; secondary-hit pattern; and a
   **streaming** pattern to prove the yield throttle prevents pollution). NOTE: `sw/` benches are
   **not on this branch** — port the generators from `TL_signal_analysis` or write minimal new ones;
   read results via MMIO counters.
5. **Coherence regression:** broader workloads / random TL traffic with SBC enabled.

## Risks / watch-list (risk → how this design handles it)

- **Cross-set data race / deadlock (🔴):** removed by construction — advisory SBU (no second master)
  + two-set ownership + one-migration token. Assert ≤1 owner per set.
- **Copy ↔ refill overwrite (🔴):** contained by the copy↔refill hazard guard + `w_migrate*`
  ordering. Assert refill never precedes copy-read-done.
- **False hit on displaced line / duplicate-stale copy (🔴):** displaced-aware directory hit +
  mandatory secondary-search-before-memory. Assert the native/displaced XOR invariant.
- **Nested txn mid-migration (🔴):** atomic directory transition under nesting blocked on both sets.
- **`repeat`/reload staleness:** clear the fast-path ([Scheduler.scala:235](src/Scheduler.scala#L235)) on migrate commit.
- **Streaming pollution / clean-vs-dead:** adaptive yield throttle (Phase 4) — the reason clean-only
  and the throttle are required *together*.
- **Swap-home thrash:** bringing L home re-loads the hot set; bounded by random victim + hysteresis.
  Watch in Phase-0/3 measurements; if it thrashes, that's the signal to revisit serve-in-place.
- **Second-read latency:** gate strictly on `assocResp.activeSource`; non-participating sets pay zero.
- **AT as flops:** SRAM fallback for high set counts.

## Documented alternative (future, not v1)

**Serve-in-place / route-to-`d`:** on a secondary hit, serve L from `d` and route all future requests
to `d` instead of migrating it home. Avoids the copy and keeps the hot set relieved, but L becomes
client-held in a foreign set, so the **C-channel (Release/ProbeAck) and conflicting-acquire matching
must become AT-aware** and displaced lines may be dirty+client-held — i.e. principle 4 is dropped.
The clean form is a **front-end AT address-remap** (translate address-set→physical-set before the
directory access) rather than scattered displaced-checks. Higher performance, materially more
coherence complexity.
