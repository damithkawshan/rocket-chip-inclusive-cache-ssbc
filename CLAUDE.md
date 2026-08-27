# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

This is a **fork of SiFive's `block-inclusivecache`** — a Chisel RTL generator for a coherent,
last-level, inclusive L2 cache (TileLink 1.8.1, full-map directory, invalidation-based coherence).
It is vendored as a Chipyard generator at
`chipyard/generators/rocket-chip-inclusive-cache` and depends on `rocket-chip` (see
`wit-manifest.json`).

The active development branch (`set_migration_refactored`) implements the **Set-Balancing Cache
(SBC)** — a PhD research project. Phase 0 (observation) and Phase 1 (migration primitives) are
**complete**; **Phase 2 (migrate-on-eviction) is active**. The design docs live in `ai-documents/`;

### Remote

Both `origin` and `myfork` point to the same GitHub fork:
`git@github.com:damithkawshan/rocket-chip-inclusive-cache-ssbc.git`. `set_migration_refactored` is
pushed there and tracks `origin/set_migration_refactored` (pushed 2026-08-17; previously tracked a
now-deleted `origin/rc-bump` with no remote counterpart — that stale tracking has been replaced).
Other remote branches (`main`, `perf_counter`, `coherency_aware_replacement`,
`TL_signal_analysis`) are separate lines of work, not ancestors of this branch.


[ai-documents/phase-2.md](ai-documents/phase-2.md) is the current single source of truth.

## Build, simulate, test

This generator has **no standalone build** — it is elaborated and simulated through the parent
Chipyard flow. The Chisel `src/` here is compiled by Chipyard's sbt build.

From the Chipyard root (`chipyard/`):

```bash
in chipyard/scripts/ssbc-script/verilator_sim.sh <workload>
```
can be used to run a workload, but we might need to optimize this as it is bit complex.

```bash
# Verilator sim of a config that instantiates this L2
make -C sims/verilator CONFIG=VerilatorRocket8KL116KL2Config

# Run a RISC-V binary on the built simulator
make -C sims/verilator CONFIG=VerilatorRocket8KL116KL2Config run-binary BINARY=<path/to.riscv>
```

Configs that wire in this cache live in
`chipyard/generators/chipyard/src/main/scala/config/RocketConfigs.scala` via
`WithInclusiveCache(nWays=…, capacityKB=…)`. FPGA/VCU118 bitstreams are built under `chipyard/fpga/`.

There are no Scala unit tests. Verification is: (a) elaboration/build succeeding, (b) Chisel
`assert`s firing in sim, and (c) the bare-metal microbenchmark at `sw/misshit_generator.c`.

## Architecture

### Stock inclusive cache (upstream, mostly unchanged)

A single L2 bank is `InclusiveCacheBankScheduler` ([Scheduler.scala](design/craft/inclusivecache/src/Scheduler.scala)),
which arbitrates a pool of **MSHRs** ([MSHR.scala](design/craft/inclusivecache/src/MSHR.scala),
the protocol FSM) over the shared **Directory** ([Directory.scala](design/craft/inclusivecache/src/Directory.scala),
full-map tag+state SRAM) and **BankedStore** ([BankedStore.scala](design/craft/inclusivecache/src/BankedStore.scala),
the sub-banked data array). TileLink channels are split into one module each:
`SinkA/B/C/D/E/X` (incoming) and `SourceA/B/C/D/E/X` (outgoing). `InclusiveCache`
([InclusiveCache.scala](design/craft/inclusivecache/src/InclusiveCache.scala)) is the LazyModule
that builds the diplomatic node, tabulates banks, and instantiates the MMIO control
([Control.scala](design/craft/inclusivecache/src/Control.scala)).

Key invariant the SBC plan revolves around: **physical set == address-derived set everywhere**.
`expandAddress(tag, set, offset)` in [Parameters.scala](design/craft/inclusivecache/src/Parameters.scala)
reconstructs the real memory address from `(tag, physicalSet)`.

### Configuration flow

`WithInclusiveCache` (in [Configs.scala](design/craft/inclusivecache/src/Configs.scala)) sets
`InclusiveCacheKey` → `InclusiveCacheParams`, then replaces the Subsystem coherence manager with
this L2. `CacheParameters` is geometry; `InclusiveCacheMicroParameters`
([Parameters.scala](design/craft/inclusivecache/src/Parameters.scala) ~L117) holds the buffering
knobs and the SBC enable flags:

| Param | Default | Meaning |
|---|---|---|
| `enableSetBalancing` | `false` | Master gate — enables SBC migration behavior |
| `satCounterBits` | `3` | Per-set saturation counter width |
| `migrationThreshold` | `4` | T\_hi: migrate only from sets at/above this level |
| `migrationClearThreshold` | `2` | T\_lo: hysteresis lower bound |
| `dssEntries` | `8` | Candidate slots in the Destination Set Selector |
| `sbcDebug` | `false` | Sim-only SBC debug printfs (no hardware elaborated when off) |

When `enableSetBalancing = false`, `SetBalanceUnit` is not instantiated and only the tie-down
defaults are elaborated — the RTL is bit-exact with upstream.

### SBC instrumentation (this fork's additions)

#### `DirectoryEntry.displaced` bit ([Directory.scala](design/craft/inclusivecache/src/Directory.scala))

A single `displaced` bit has been added to `DirectoryEntry`. The hit-detection logic
(line ~143) excludes displaced ways from satisfying demand lookups:
```scala
w.tag === tag && w.state =/= INVALID && !w.displaced && (...)
```
This prevents a line that has been migrated out from being returned as a false hit.

#### `SetBalanceUnit` ([SetBalanceUnit.scala](design/craft/inclusivecache/src/SetBalanceUnit.scala))

Phase 0 module. Owns:
- Per-set saturation counters (`+1` on miss, `−1` on hit, clamped to `[0, satMax]`)
- Association Table (AT) — tracks source↔destination set pairings; inert in Phase 0
- Read-only `SBCStats` output plumbed to the MMIO regmap

Receives directory events via the `DirectoryTap` from `directory.io.tap` (wired in Scheduler).
Migration is always `false` in Phase 0; `assert(!io.commit.valid)` guards against accidental
commit calls.

#### `DSS` ([DSS.scala](design/craft/inclusivecache/src/DSS.scala))

Destination Set Selector. Maintains `d` candidate slots (configurable via `dssEntries`).
On each saturation update, evicts the hottest candidate if a colder one arrives. Reports the
current coldest candidate set via `coldestSet / coldestLevel`.

#### `SetCopyUnit` ([SetCopyUnit.scala](design/craft/inclusivecache/src/SetCopyUnit.scala))

Phase 1 data mover (implemented but **not yet connected** to BankedStore/SourceD). FSM:
`IDLE → READ → WRITE → DONE`. Reads a full block beat-by-beat from `(srcSet, srcWay)` into an
internal buffer, then writes to `(dstSet, dstWay)`. Exposes hazard IO (`copy_req/copy_safe` for
RaW, `copy_wreq/copy_wsafe` for WaR) for SourceD interlocking.

#### Phase 1 scaffolding already in place

- **`MSHR.scala`** — `MSHRStatus` carries `dstValid / dstSet / dstWay`; a migrating MSHR will
  set these to reserve its destination set.
- **`Scheduler.scala`** — `dstSetConflict` wire stalls any incoming request whose set matches a
  reserved `dstSet`, keeping the migration window coherent:
  ```scala
  request.ready := (request_alloc_cases || ...) && !dstSetConflict
  ```

The wiring pattern for adding new instrumentation: flag in micro params → optional IO on the
Scheduler bundle → tie-down defaults + regmap entries in `InclusiveCache`/`Control`.

### MMIO register map (host/SW contract)

Control block base: **`0x2010000`** (`InclusiveCacheParameters.L2ControlAddress`).
Authoritative layout: [Control.scala](design/craft/inclusivecache/src/Control.scala).

| Offset | Field | Notes |
|---|---|---|
| `0x000` | Config word | banks / ways / lgSets / lgBlockBytes |
| `0x200` | Flush64 | Flush by 64-bit physical address |
| `0x240` | Flush32 | Flush by (32-bit value) << 4 |
| `0x300` | `SBC_SetSel` | R/W — set index to observe (selects saturation read-back) |
| `0x308` | `SBC_SetSat` | R — saturation counter of selected set |
| `0x310` | `SBC_ColdestSet` | R — DSS coldest candidate set |
| `0x318` | `SBC_ColdestLevel` | R — saturation of coldest candidate |
| `0x320` | `SBC_Status` | R — bit0=SBC enabled, bit1=coldestValid, bit2=AT[sel].valid |
| `0x328` | `SBC_Migrations` | R — migrations committed |
| `0x330` | `SBC_SecHits` | R — secondary hits (0 in Phase 0) |
| `0x338` | `SBC_SecMiss` | R — secondary misses (0 in Phase 0) |
| `0x340` | `SBC_BalanceSet` | W — arm migration for the written source-set index |
| `0x348` | `SBC_Attempted` | R — migrations attempted (setup reached) |
| `0x350` | `SBC_Aborted` | R — migrations aborted (ineligible src/dst) |
| `0x358` | `SBC_Reset` | W — write any value to zero all SBC counters/AT/DSS/event state |

**If you change any register offset in Control.scala, update the SW-side header in the same change.**

### SBC Phase 1 — CLOSED (primitives proved)

Phase 1 proved the migration **primitives** (two-set ownership, SCU copy, displaced install + dual
dir-write, displaced-aware hit, hazard interlocks). These **carry over to Phase 2 unchanged**. Two
things did **not** ship and moved forward:

- **BUG-1 (illegal outer `AcquirePerm`)** — fixed: root cause was a premature migrate-retire
  (`mig_ready` ignored the pending 2nd dir-read); fixed by `mig_ready := !migrating || (s_dmeta && w_dread)`.
- **BUG-2 (migrate nests into a live demand MSHR → illegal inner `D`)** — **not patched.** The
  Phase-1 *source-side* form (standalone injected trigger nesting into the source set) is **removed by
  construction in Phase 2** (migration is the demand MSHR's own work — no second requester on the
  source). The "drop on busy" patch was **rejected** as throwaway; do not implement it.
  **⚠️ Invariant gap confirmed (2026-06-26):** "removed by construction" covers only the migration
  *source*. An independent demand to the migration **destination** set *is* a real second requester —
  this re-surfaced as the destination-collision illegal-inner-D bug below (now **FIXED**). See
  [ai-documents/bug-fix-log.md](ai-documents/bug-fix-log.md).

### SBC Phase 2 — COMPLETE & SIGNED OFF (migrate-on-eviction)

**Sign-off run (2026-08-17):** `migration_stress_test` on the stock `VerilatorRocket8KL116KL2Config`
with all seven corner cases enabled — **7/7 PASS, 0 asserts, 6 migrations committed, destinations
spread across sets 0/3/7.** This retired the last owed Phase-2 item. Two defects were caught during
sign-off review: the Bug-B `s_wsafe` fix had been silently deleted by an uncommitted debug cleanup
(restored in `a2975d6`), and six of the seven test cases in `sw/migration_stress_test.c` had been left
commented out, so every prior "PASS" was 1/7 coverage. Both recorded in `ai-documents/bug-fix-log.md`.

**✅ Phase-3 gate CLEARED (2026-08-21). Stress test 7/7 PASS, exit 0, 0 asserts — first fully green
SBC run.** Three commits closed it; day log: `ai-documents/daily-summary/2026-08-21.md`.

| commit | what it fixed |
|---|---|
| `cbb3837` | **probe-then-migrate** — the low-`p` blocker. `p` 0.018% → 78.4% (the `clients` bit was stale 99.99% of the time; run the eviction probe we already pay for, then decide) |
| `f40fbd4` | **late destination binding** — pick the destination when the migration starts, not at allocate. Also fixed the long-open `assert (new_meta.hit)`. Deleted `migAdviceDstReg` / `migTokenPending` / `migTokenDstSet` / `migPendCtr` |
| `707445c` | **`reload := no_wait && mig_ready`** — Phase 2 added the migration gate to retire but missed reload, so MSHRs were reset mid-migration. Dropped migrations 94 → **0**; copies↔commits now 204 ↔ 204 |

**The AT is populated correctly for the first time** — that was the hard Phase-3 prerequisite.
`SBC_Migrations` had been undercounting ~2× because the commit pulse was being lost.

**Destination side — analysed and fixed twice on 2026-08-24. SSOT:
[ai-documents/destination-side-blocker.md](ai-documents/destination-side-blocker.md).**

| fix | what it did |
|---|---|
| `internalRead` | the dst probe was reaching the directory tap, so **84% of all HOT events were the cache heating sets by probing them**. Correct — but commits fell 424 → 14 |
| DSS destination-reject block list | `internalRead` exposed the masked defect: **low miss-pressure ≠ has room**. A set whose lines always hit decays to level 0 and is permanently "coldest" while being completely full. A refused destination is now blocked until all candidates are tried. Commits **749** (7/7 PASS, 0 asserts, copies↔commits 749↔749) |

**Still open — but the next move is a config flag, not RTL.** 46% of the 43,311 aborts are a `clients`
bit that is *provably* false: the I$ is not a TL-C client (`ICache.scala:154`, no `supportsProbe`), so
every `clients` bit comes from a **4-line** D$ against 64 L2 lines → max 6.25% true, measured 52%. Root
cause is Rocket's `acquireBeforeRelease = false` default (`HellaCache.scala:42/54` → `silentDrop`), and
our L2 already handles voluntary Release correctly ([MSHR.scala:401-404](design/craft/inclusivecache/src/MSHR.scala#L401-L404)).
**Try the flag before building the destination probe.**

⛔ **Do not build dirty-destination eviction** (the other 53%). It pays a real memory write to avoid a
free one, and evicts a well-behaved line to preserve one we were discarding. Migrating *dirty source*
lines is Phase 4 — it breaks `displaced ⇒ clean` and needs a directory format change.

### ⚠️ MEASURED 2026-08-25: SBC is data-safe but **+42% cycles / 9.29x DRAM traffic**

First differential run (SBC on vs `NoSbcConfig`) on a real third-party benchmark — `matmult` from
bringup-bench, N=32. SSOT: [ai-documents/matmult-differential-2026-08-25.md](ai-documents/matmult-differential-2026-08-25.md).

| | SBC on | SBC off | |
|---|---:|---:|---|
| Checksum | 29824 | 29824 | ✅ identical, 0 asserts, 1,737 migrations |
| Cycles | 22,278,686 | 15,683,316 | 🔴 **+42.0%** |
| OUTER-A (DRAM fetches) | 122,144 | 13,147 | 🔴 **9.29x** |

Both runs `*** PASSED ***`, same binary. **In Phase 2 a displaced line can never serve a hit** (the
Directory excludes them from hit detection), so **100% of migrations are pure cost by construction** —
an internal copy plus a way that cannot hit, returning nothing until Phase 3 lands. Leading (unproven)
cause of the magnitude: sets fill with displaced lines, evictable only by the last-resort reclaim tier,
so effective associativity collapses. Magnitude is NOT accounted for — 1,737 parked lines cannot alone
cause 109k extra fetches, and **there is no L2-access counter, so any "miss rate" figure is arithmetic,
not instrumentation.** Add a displaced-occupancy counter before quoting the mechanism. The 42% is also amplified by the 64-line geometry and must not be quoted as
SBC's general overhead.

**Build/run bringup-bench:** `./sw/scripts/compile_bringup.sh <bench> [N]` (shim: `sw/bringup/libtarg_chipyard.c`).
Two gotchas: the checksum goes to `.log` (stdout), not the dasm'd `.out`; and `OUTER-A`/`EVICT-ASSESS`/
`EVICT-NORMAL` printfs are gated by `sbcDebug`, **not** `enableSetBalancing` — so an SBC-OFF run still
emits `[SBC]` lines. Check for `MIG-` to prove SBC is off.

⚠️ **Strategic risk to weigh if SBC shows no gain:** **2 free ways in 44,060 probes.** Every migration
displaces a resident line; this workload/geometry may have no spare capacity at all. Also note
**HOT events 4,113 → 27,334** once migrations completed — displaced lines can't serve hits, so
**migration without Phase 3 is a net negative.** A bigger-set config + write-heavy workload
(`matmult_float`) is now **required before any performance claim**, not optional.

**Control config:** `VerilatorRocket8KL116KL2NoSbcConfig` (`enableSetBalancing=false`) is **7/7 PASS**
— use it to prove any future failure is SBC's and not the benchmark's.

**Debug method that works here:** when SBC-era logic misbehaves, `git show <pre-SBC>:file` and compare
every consumer of the touched signal **before** instrumenting. The `707445c` bug was a Phase-2 gate
added in one place and missed in a sibling — a 30-second diff, found only after several 10-minute
instrumented sim cycles chasing a wrong theory.

**Authoritative status: [ai-documents/phase-2.md](ai-documents/phase-2.md).** Migration moves inside the
demand-miss eviction: on a miss to a hot set, the demand MSHR migrates its clean, client-free victim to a
cold set instead of releasing it, then refills the freed way. The Phase-1 standalone injection path
(SBU `migrateReq`, SinkX inject, `migInFlight` throttle) is **already gone from the RTL** (verified by
grep 2026-08-18) — but three **comments** still describe it, which is cleanup Batch B below.

| Task | File | Status | What |
|------|------|--------|------|
| 2a — migrate-on-eviction | `MSHR.scala` / `Scheduler.scala` | ✅ DONE | Gate `s_migrate` in place of `s_release` for a clean victim of a hot set; abort-if-dst-full; dir-write #2 = demand refill; copy↔refill (A2) interlock live. |
| `s_verify` (folded into 2a) | `SetCopyUnit.scala` | 🟡 REMOVED | Starved on the lowest-priority BankedStore read port → deadlock. Was a dead passthrough; deleted in `a2975d6`. Copy correct by write-through. **Rebuild in Phase 3 behind a non-starvable read path — now a hard prereq, since Phase 3 serves copies.** |
| `s_wsafe` (Bug-B fix) | `SetCopyUnit.scala` | ✅ RETAINED | ⚠️ **DO NOT DELETE.** Pre-checks `copy_wsafe` before the copy starts. Was accidentally reverted by a debug-cleanup pass; restored in `a2975d6` with the rationale attached to the state. |
| Step 7 — AT commit | `SetBalanceUnit.scala` | ✅ DONE | `AT[s]={src→d}`, `AT[d]={dst→s}` on commit. `migrationCount++`. |
| 2b — dst eviction | `MSHR.scala` | ✅ DONE | Evictable-way overwrite. Bug A (preferEvictable wiring on dread) + Bug B (copy_wsafe WaR race) fixed. **Stress run: 9 migrations committed, data correct.** |
| Q3 — copy-port priority starvation | `BankedStore.scala` | ✅ TESTED → REJECTED | Stress test: 0 arbitration stalls in 442 migration attempts. Low priority = bounded delay, not deadlock. **Do NOT reorder BankedStore priorities** (the order is load-bearing for protocol deadlock-freedom). |
| Dst-set collision — illegal inner-D | `Scheduler.scala` | ✅ FIXED (2026-06-30) | Fixed by the **allocation-side fence**: `allocReady = alloc && !dstSetConflict` gates *both* the alloc dir-read (`:297`) and the MSHR allocate (`:337`), not just acceptance. Forced repro `dst_collision_repro` PASSES (8 migrations, data correct). ✅ **Stock-config regression PASSED 2026-08-17** — 0 asserts, no illegal inner-D, destinations spread 0/3/7. Fully closed. |
| Displaced-line accumulation | `Directory.scala` / `MSHR.scala` | ✅ FIXED & VERIFIED (2026-06-30) | Was: displaced ways excluded from hits AND all victim tiers → immortal → `Directory.scala:156` assert → bricked. Fix = **last-resort displaced-reclaim victim tier** in `Directory.scala` (`displacedOH = ~nonDisplacedOH`, lowest priority) + `MSHR.scala` **silent-drops** a displaced victim (no Release — wrong address; assert narrowed to release-only). Baseline-exact (no flag). **Forced torture config: 20000 iters PASS, 0 asserts, 10 migrations committed, reclaim fired 3×.** ⚠️ Caveat: a 7/8-displaced set recycles its one native way until Phase-3 spreading. Stock-config run PASSED 2026-08-17, but the **reclaim tier did not fire** there (6 migrations over 3 sets never fills a set) — so reclaim remains verified under forcing only. |
| Phase 3 — secondary search | `Directory.scala` / `MSHR.scala` / `SetBalanceUnit.scala` | 🔵 NEXT — **but gated** | Make displaced copy reusable via AT lookup (secondary hits). Enforce displaced XOR native invariant. Also: rebuild `s_verify` (now a hard prereq), fix DSS coldness. ⚠️ **Cause of low `p` is now diagnosed (stale `clients` bit — see above); the gate is now "does probe-then-migrate actually raise `p`?"** Do not start the swap datapath before step 2 of `ai-documents/July18AfterBreakWorkplan.md` returns a number. Pinned 1:1 association spec is written but unimplemented (`ai-documents/spec-sbc-phase3-prereqs.md`). |

Full arbitration/priority map: `ai-documents/priority-orders.md`. Consolidated bug record (fixed +
open), by phase: `ai-documents/bug-fix-log.md`. Phase-2 single source of truth (absorbs the former
`phase-2-dst-collision.md` + `phase-2-2b-handoff.md`): `ai-documents/phase-2.md`. Read that, then
`ai-documents/SBC_integration_plan.md` and `ai-documents/phase-1.md`, before touching the migrate
datapath.

### Code cleanup status (audited 2026-08-18) — only 2 items left, and D/E are closed as "do not do"

Checklist: `ai-documents/code-cleanup-suggestions.md`. Ordered plan:
`ai-documents/July18AfterBreakWorkplan.md` §6.

| Batch | What | Verdict |
|---|---|---|
| A | Delete `MSHR.scala.original`; delete commented DUMP block (`SetBalanceUnit.scala` ~L204) | 🔴 **DO** — still owed, zero risk |
| B | Fix 3 comments describing the deleted injection path (`Scheduler.scala` ~L433, `SetBalanceUnit.scala` L8 + ~L111) | 🔴 **DO FIRST** — still owed, zero risk |
| C | Excise dead `s_verify` + stall classifier | ✅ DONE in `a2975d6` |
| D | Retire `sbcGateStallCycles` / `sbcForceDstSet` | ⛔ **KEEP BOTH** — verdict reversed |
| E | Guard consolidation / dead `assocQuery` IO / printf trim | ⛔ **DO NONE OF IT** |

- **Batch B is the priority even though it is only comments.** Comments that describe deleted hardware
  are exactly what let the `s_wsafe` fix be deleted in the last cleanup pass. Delete
  `MSHR.scala.original` rather than leaving it gitignored — an invisible stale copy of the most-edited
  file in the repo is the worst of both options.
- **`sbcGateStallCycles` stays:** it widens the `[born → gate]` window, which is the one **still-open**
  bug in `bug-fix-log.md`. **`sbcForceDstSet` stays:** it is the only way to fill a set with displaced
  lines, hence the only way to exercise the displaced-reclaim tier (whose evidence is synthetic).
  Both are compile-time gated → zero hardware at defaults.
- **E1 (six overlapping migration guards) is reclassified as Phase-3 design work, not cleanup.** Those
  guards *are* the "one migration in flight" token; at thousands of migrations that becomes a
  throughput ceiling, so the question is "allow more than one?", not "which are redundant?". Fold it
  into the pinned-pairing step. **E3 (`printf` trim) is rejected with evidence** — the low-`p` blocker
  was diagnosed with no RTL change and no re-run purely because the `EVICT-ASSESS` printf
  ([MSHR.scala:787](design/craft/inclusivecache/src/MSHR.scala#L787)) already carried
  `dirty`/`clients`/`displaced`.

## Conventions

- Package is `sifive.blocks.inclusivecache` (unchanged from upstream — keep it).
- Scala is compiled with `-Xsource:2.11`; match the existing Chisel 3 idioms in neighboring files.
- New synthesizable state must be **gated behind `enableSetBalancing` (or a new micro-parameter
  flag) and produce zero hardware when disabled** — baseline runs must stay bit-exact.
- `target/` is gitignored; `.metals/`, `.scala-build/`, `.bsp/` are editor/build caches.
- Large raw run outputs go to a gitignored `results/` directory at the generator root.
