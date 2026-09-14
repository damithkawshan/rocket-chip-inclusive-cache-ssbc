# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

This is a **fork of SiFive's `block-inclusivecache`** — a Chisel RTL generator for a coherent,
last-level, inclusive L2 cache (TileLink 1.8.1, full-map directory, invalidation-based coherence).
It is vendored as a Chipyard generator at
`chipyard/generators/rocket-chip-inclusive-cache` and depends on `rocket-chip` (see
`wit-manifest.json`).

This fork implements the **Set-Balancing Cache (SBC)** — a PhD research project. The design docs
live in `ai-documents/`.

## Current status (updated 2026-09-14)

**Start here: [ai-documents/README.md](ai-documents/README.md)** — every doc grouped and labelled
(live / record / superseded), with the same status in point form. Keep the two in step.

> **⚠️ VERIFY THIS WEEK (due 2026-09-18) — do not build a fix on either until it is checked:**
> 1. **L8 — pairings never end.** Nothing in the RTL clears an AT pairing except `SBC_Reset`. Confirm on
>    the board: after an SBC run, sweep `SBC_SetSel` over all sets — is every set paired? Does
>    `SBC_Parked` only ever rise? (tracker M12, M13)
> 2. **M11 — heat counter on a partner hit.** RTL: home set +1, partner unchanged (the search is
>    `internalRead`). Paper §3.3 + Fig 2: the partner's counter goes **down**; the home-set rule is
>    unclear. Settle it from a longer version of the paper or the authors before changing the RTL.
>
> Details and how to check: `ai-documents/README.md` (box at the top).

- **Main branch: `sbc-paper-aligned`** (tracks `origin/sbc-paper-aligned`) — treat it as the project's main line.
- **Testing branch: `sbc-sampling`** — local only, cut from `sbc-paper-aligned` at `d8671cf`.
  **Plan: merge `sbc-sampling` back into `sbc-paper-aligned`** (not done yet).
- **Built and correct:** Phases 0–2, probe-then-migrate, and Phase 3R serve-in-place (GATE 4 green
  2026-08-30, real-workload clean 2026-08-31). GATE 5 in coder/003 moved to the last phase (2026-09-14).
- **Phase: measurement and optimization on the FPGA** (VCU118 + Linux). All build phases are done;
  leftovers are in the status tracker in `ai-documents/README.md`.
- **Result (fixed work, 2026-09-11 → 09-13):** SBC is **slower** on omnetpp — **+32% to +51% cycles**
  and **2.5× to 7× more main-memory accesses** in 3 A/B pairs (256 KB and 64 KB L2). One run each, no
  repeats yet. Data: [fpga-ab-baseline-2026-09-11.md](ai-documents/performance/fpga-ab-baseline-2026-09-11.md) §4.
- **Active task, then the merge** (on `sbc-sampling`):
  1. [coder/005](ai-documents/coder/005-hit-accounting-and-sampling) — **ready to start** (TASK rewritten
     2026-09-14 as one clean work order for a fresh coder chat). Move every monitoring counter into
     `PerfCounters` behind one flag; one counter per terminology term; three counter fixes; the interval
     sampler. **Binding timing and sampling rules** (TASK §2.2): counters add MSHR pulses with `PopCount`, never OR.
  2. Merge `sbc-sampling` into `sbc-paper-aligned`, then **one** bitstream rebuild (recommended — not one per task).
- **Task [coder/006](ai-documents/coder/006-migrate-switch-and-memory-traffic) closed 2026-09-14** (`e4c5d53`).
- **Terminology is fixed (2026-09-14)** — see "Cache terminology" below. Use it for every hit/miss number.
- **Before debugging or tuning anything, read "Build-phase leftovers" below.**
- **Active plan:** [ai-documents/performance/workplan-parked-occupancy-2026-09-11.md](ai-documents/performance/workplan-parked-occupancy-2026-09-11.md). Step 3 is under review.
- **Start here on 2026-09-15:** [ai-documents/performance/why-sbc-loses-2026-09-15.md](ai-documents/performance/why-sbc-loses-2026-09-15.md)
  — unverified analysis: a fixed point in our eviction rules parks ~47% of the cache at any size. Run its
  §6 board checks first (tracker M12, M13).
- The Phase 1 / Phase 2 sections further down are **history** — right for their phase, not the current status.

### Remote

Both `origin` and `myfork` point to the same GitHub fork:
`git@github.com:damithkawshan/rocket-chip-inclusive-cache-ssbc.git`.

- `sbc-paper-aligned` — **main branch** for SBC work; tracks `origin/sbc-paper-aligned`.
- `sbc-sampling` — testing branch, local only, cut from `sbc-paper-aligned`; to be merged back into it.
- `set_migration_refactored` — older SBC branch (to 2026-08-26), an ancestor of the active branch.
- `main`, `perf_counter`, `coherency_aware_replacement`, `TL_signal_analysis` — separate lines of work.

## Cache terminology — use these words (agreed 2026-09-14)

**Single source: [ai-documents/guides/cache-terminology.md](ai-documents/guides/cache-terminology.md)** —
the full table, the literature it comes from, and which counters follow it. This is a short copy:
**change both together.** Use these words in every report, counter name, TASK and REPORT.

| Term | In TileLink and our design |
|---|---|
| **Access** | One **inner-A** request from L1, counted once. Ends as exactly one of the four outcomes below |
| **Primary hit** | Home line (`displaced = 0`) with enough permission → **no outer A** |
| **Secondary hit** | Home set misses; the second search finds the parked line (`displaced = 1`) in the partner set with enough permission → **no outer A**, served in place |
| **Data miss** | Line not in the L2 → outer A `NtoB` / `NtoT` (after evicting or migrating a victim if the set is full) |
| **Upgrade miss** | Line in the L2 (home or parked) but only `BRANCH`, and the request needs `TRUNK` → outer A **`BtoT`** |
| **Probed hit** | A primary or secondary hit that first sends inner B `Probe` to another L1 client. **Still a hit** |
| **Secondary miss** | The second search ran and found nothing. Always a data miss, plus the cost of the search |
| **Not accesses** | Inner C `Release` / `ReleaseData` (write-back), inner B `Probe` (invalidation), X-channel flush, SBC's own directory reads |

- **Hit = no outer A message. Miss = an outer A message.** Nothing else decides it.
- **Hit rate = (primary + secondary hits) ÷ accesses.**
- ⚠️ **`L2_Accesses` / `L2_Hits` and `SBC_SecHits` are legacy and do not follow these words** (every
  channel; upgrade misses and write-backs count as hits). Until coder/005 lands, the only exact number
  is **misses = `L2_MemReads + L2_MemAcqPerm`** (was `L2_MemUpgrades`) — do not quote a hit rate.

## ⚠️ Build-phase leftovers — check these first

**Updated 2026-09-14.** The build phases are done, but they left unfinished or unproven pieces behind.
**These are the first suspects when SBC shows a bug or loses speed.** Their status lives in the tracker
(`ai-documents/README.md` §1d, same IDs). Who-goes-first rules: `ai-documents/guides/priority-orders.md`.

### Unfinished or unproven pieces

| # | Leftover | Why it matters | Where |
|---|---|---|---|
| L1 | The copy self-check (`s_verify`) was removed and never rebuilt | **Bug risk:** on the FPGA nothing checks that a migration copy landed correctly; only the sim shadow checker does. **Moved to the last phase** (2026-09-14) | `SetCopyUnit.scala` |
| L2 | Last-resort eviction of parked lines was proven only in a forced test | **Bug risk:** untested on real traffic | `Directory.scala:221` |
| L3 | One latent timing window (`[born→gate]`) | **Bug risk:** never reproduced; `sbcGateStallCycles` exists to hunt it | `bug-fix-log.md` |
| L4 | Task 003 GATE 5 never signed off | **Bug risk:** 6 of its 12 cases never proved their event; the two-core cases never ran. **Moved to the last phase** (2026-09-14) | `coder/003` |
| L5 | Only clean lines migrate — dirty-source migration was never built | **Speed gap:** most real victims are dirty, so SBC often cannot fire | `MSHR.scala:1058` |
| L6 | No adaptive yield throttle | **Speed gap:** a set keeps migrating even when its parked lines are never reused | not built |
| L7 | The "serve a parked line that needs write permission" path never ran | **Bug risk:** `secPerm = 0` in every run so far; needs a two-core config. **Moved to the last phase** | `MSHR.scala` |
| L8 | **Teardown was never built** — nothing clears a pairing except `SBC_Reset` (found 2026-09-14) | **Speed gap:** every pairing is permanent, so a set stays tied to a partner that may no longer be cold or useful. **⚠️ Verify this week** (due 2026-09-18) | `Directory.scala` computes `displacedOther`, but nothing reads it |

### Known causes of the speed gap

| # | Cause | Effect | Where |
|---|---|---|---|
| G1 | Parked lines are the last choice for eviction | They pile up (825 → 1,804 in one board session) and crowd out home lines | `Directory.scala:219-221` (workplan Problem A) |
| G2 | When the random pick lands on a parked line, eviction takes the **first** home line | Eviction stops being random on ~44% of evictions | `Directory.scala:220` (workplan Problem B) |
| G3 | A parked line is found only by a second look, and only from its own home set | ~13× fewer hits per slot than a home line | design limit |
| G4 | One migration at a time | Throughput ceiling at millions of migrations | `Scheduler.scala:271-282` |
| G5 | ~70–80% of migration attempts abort on the board | Wasted probes and directory reads | stale `clients` bit; `acquireBeforeRelease = true` never tried |
| G6 | Random (LFSR) replacement, not LRU as in the paper; small 8 KB L1 | The paper's "a moved line gets a head start" became "a moved line is never evicted" | `Directory.scala` |
| G7 | SBU logic is 60 levels deep; its per-set tables are flip-flops | Timing and area cost | `SetBalanceUnit.scala`, `DSS.scala` |
| G8 | **Open question — ⚠️ verify this week (M11).** On a partner hit our RTL does home +1, partner unchanged. The paper lowers the partner's counter (§3.3, Fig 2) but is unclear on the home counter | Unknown until settled. Do **not** change the RTL on the current reading | `Directory.scala:313-315` |
| G9 | **Hypothesis (2026-09-15, unverified):** a move may only overwrite a *home* line in the destination (`dstEvictable` needs `!displaced`); destinations evict home lines first; pairs never end (L8) → each pair settles at 1 home + 15 moved lines | ~47% of the cache parked at any size (measured 480/1024 and 1903/4096); primary hit rate roughly halves | `ai-documents/performance/why-sbc-loses-2026-09-15.md`; `MSHR.scala:1469-1470`, `Directory.scala:217-226` |

### Bug patterns that keep coming back (details: `ai-documents/bugs/bug-fix-log.md`)

- **Reading a register in the same cycle it is written** gives the old value (002 C1, P6, P7). Check
  every new signal gated on `io.directory.valid`.
- **A guard added in one place but missed in its twin** (`707445c`, P5, P6). Diff against the pre-SBC
  file before instrumenting.
- **"Harmless by reading"** is only harmless while nearby code masks it. Re-check it when that code is deleted (P6).
- **Comments that describe deleted hardware** led to the `s_wsafe` fix being deleted.
- **Simulating without `+dramsim`** gives a false shadow-checker alarm (cycle 27141).
- **32-bit counters wrap** on long board runs — fixed by the 64-bit change (task 006).
- **OR-ing pulses from several MSHRs into one counter** loses events that land in the same cycle. Add them
  (`PopCount`). Rules for every counter: coder/005 TASK §2.2.

### Can be removed in the optimization phase (area)

Debug and measurement hardware we need now but may cut for the final area number. **Remove only after
the last measurement, and report area both with and without it.**

| Item | Costs FPGA area today? | Note |
|---|---|---|
| Every MMIO counter (`PerfCounters.scala`): 12 SBC events + parked count, `L2_Accesses`/`L2_Hits`, memory traffic + `L2_Cycles`; and the `SBC_SetSel`/`SetSat`/`AtAssoc`/`Status` bit 2 read-backs | yes, in both builds (~830 FF for the SBC events alone; `SetSat` and `AtAssoc` are 256-way selects) | measurement only. **One flag since task 005 commit 0:** `enablePerfCounters = false` removes all of it; the registers read 0. `ColdestSet`/`Level` stay — the cache uses those wires |
| `SBC_BalanceSet` register + per-set `armed[]` | `armed[]` is already removed by synthesis when `sbcAutoMigrate = true`; the register write still exists | remove together with `sbcAutoMigrate` |
| `SetCopyUnit` in the SBC-off build | yes, 240 LUT / 529 FF (measured) | guard it (tracker H1) — makes the baseline fair |
| `sbcShadow` shadow checkers | no — FPGA configs set it `false` (would be ~123k FF) | sim only; keep for Verilator |
| `sbcDebug` printfs | no — FPGA configs set it `false` | sim only |
| `sbcForceDstSet`, `sbcGateStallCycles` | no hardware at defaults | test knobs; keep until L2 and L3 are closed |
| `SBC_MigrateEnable` switch | 1 FF | **keep** — needed for the one-bitstream A/B |

**One flag covers the measurement row (task 005 commit 0):** `enablePerfCounters = false` removes every
counter and the read-back muxes (row 1), and after commit 3 the sampler; their registers read 0. Report
area with the flag on and off.

Not a removal but the biggest area lever: move the per-set tables (`sat`, `at`, `parkCount`, ~5,120 FF)
from flip-flops to LUTRAM (`ai-documents/daily-summary/2026-09-08.md`).

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

### ⚠️ ALWAYS simulate through `make run-binary` / `run_sbc.sh` — never a bare `$SIM` invocation

The `make … run-binary` recipe (and `sw/scripts/run_sbc.sh`, `scripts/ssbc-script/verilator_sim.sh`,
which wrap it) passes **`+dramsim +dramsim_ini_dir=…`** — the realistic DRAMSim2 memory model. A bare
direct invocation of the simulator binary

```bash
# ❌ WRONG for debugging — omits +dramsim, so it uses the FAST IDEAL-memory model
$SIM +permissive +max-cycles=N +permissive-off <bin>.riscv
```

**omits `+dramsim`**, which silently switches the memory system to the fast ideal model. That changes
L2 **eviction/refill timing**, and the sim-only `BankedStore` shadow checker is **beat-blind**
(`shIdx = Cat(way,set)` — one address per `(set,way)`, no beat/sub-bank). Under ideal-memory timing a
legitimate evict-read of the victim and refill-write of the new line land on the same `(set,way)` in
adjacent cycles, and the checker **false-positives**:

```
BankedStore.sv: SBC shadow: sourceC touched the wrong row: set=1 way=2
  stored=(tag=80009 set=1) believed=(tag=80008 set=1) ... writtenAt=27140 now=27141
```

This is a **FALSE POSITIVE**, not a data bug: under the real DRAMSim2 timing the refill and victim-read
are separated in time and it never fires, and the same test passes on `…NoSbcConfig` (checker not
elaborated) with correct golden data. **Do not conclude "data corruption" or "GATE not green" from a
bare-invocation abort at cycle 27141.**

If you must drive the sim directly, replicate make's plusargs — most importantly `+dramsim`:

```bash
$SIM +permissive +dramsim \
     +dramsim_ini_dir=$CY/generators/testchipip/src/main/resources/dramsim2_ini \
     +max-cycles=N +permissive-off <bin>.riscv
```

**Cost of ignoring this (2026-09-02):** a bare-`$SIM` "repro" that omitted `+dramsim` produced the
27141 abort, which was mistaken for a real corruption and led to a false "GATE 4 was never green"
conclusion plus hours of wrong commit-bisection. The actual difference was one missing flag. Verify a
suspected sim failure through `make run-binary` / `run_sbc.sh` before trusting it.

Configs that wire in this cache live in
`chipyard/generators/chipyard/src/main/scala/config/RocketConfigs.scala` via
`WithInclusiveCache(nWays=…, capacityKB=…)`. FPGA/VCU118 bitstreams are built under `chipyard/fpga/`.

There are no Scala unit tests. Verification is: (a) elaboration/build succeeding, (b) Chisel
`assert`s and the sim-only shadow checkers staying quiet, and (c) bare-metal tests in `sw/` —
mainly `migration_stress_test.c` (7 cases) and `sbc_migrate_switch_test.c` (the 006 switch).
Performance is measured on the FPGA, not in Verilator — see
[ai-documents/guides/fpga-linux-run.md](ai-documents/guides/fpga-linux-run.md).

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
| `sbcAutoMigrate` | `false` | Migrate without a SW arm (ignores `armed[]`, so `SBC_BalanceSet` does nothing) |
| `sbcForceDstSet` | `-1` | Debug: force every migration to this set (the only way to fill a set with parked lines) |
| `sbcGateStallCycles` | `0` | Debug: widen the dst-fence window to reproduce the open `[born→gate]` bug |
| `enablePerfCounters` | `true` | All measurement hardware (task 005): every MMIO counter (`PerfCounters.scala`) and the `SBC_SetSel`/`SetSat`/`Status` bit 2/`AtAssoc` read-backs. `false` = not built, those registers read 0. **Not** gated by `enableSetBalancing` |
| `sbcShadow` | `true` in `WithInclusiveCache` | Sim-only shadow checkers that catch data corruption; forced off when SBC is off |

Several of these exist only for debugging or measurement and may be removed in the optimization phase —
see "Can be removed in the optimization phase" under Build-phase leftovers.

⚠️ **Thresholds:** `WithInclusiveCache` defaults `satCounterBits`, `migrationThreshold` and
`migrationClearThreshold` to `-1`, which auto-derives the paper's rule: T\_hi = 2·nWays−1, T\_lo = nWays
— `Configs.scala:64-66, 128-130`. The
`3 / 4 / 2` values above are coverage values only; never use them for performance numbers.

When `enableSetBalancing = false`, `SetBalanceUnit` is not instantiated. The build is still **not**
bit-exact with upstream: `SetCopyUnit` is instantiated unguarded (`Scheduler.scala:90`), and the
always-on counters and MMIO registers (tasks 004/006) exist in every build.

### SBC instrumentation (this fork's additions)

#### `DirectoryEntry.displaced` bit ([Directory.scala](design/craft/inclusivecache/src/Directory.scala))

A single `displaced` bit has been added to `DirectoryEntry`. The hit-detection logic
(line ~143) excludes displaced ways from satisfying demand lookups:
```scala
w.tag === tag && w.state =/= INVALID && !w.displaced && (...)
```
This prevents a line that has been migrated out from being returned as a false hit.

#### `SetBalanceUnit` ([SetBalanceUnit.scala](design/craft/inclusivecache/src/SetBalanceUnit.scala))

Advisory and bookkeeping only — it has no data or SRAM ports. Owns:
- Per-set saturation counters (`+1` on miss, `−1` on hit, clamped to `[0, satMax]`)
- Association Table (AT) — source↔destination set pairing, written on migration commit (strict 1:1)
- The DSS, the SBC event counters, the live parked-line count, and the read-only `SBCStats` for the MMIO regmap

Receives directory events via the `DirectoryTap` from `directory.io.tap` (wired in Scheduler).

#### `DSS` ([DSS.scala](design/craft/inclusivecache/src/DSS.scala))

Destination Set Selector. Maintains `d` candidate slots (configurable via `dssEntries`).
On each saturation update, evicts the hottest candidate if a colder one arrives. Reports the
current coldest candidate set via `coldestSet / coldestLevel`.

#### `SetCopyUnit` ([SetCopyUnit.scala](design/craft/inclusivecache/src/SetCopyUnit.scala))

Migration data mover, wired in [Scheduler.scala:90](design/craft/inclusivecache/src/Scheduler.scala#L90). FSM:
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
| `0x328` | `SBC_Migrations` | R, 64 — migrations committed |
| `0x330` | `SBC_SecHits` | R, 64 — every serve from the partner set, **including** write-backs (`SecC`) and upgrade misses (`SecPerm`) — **not** the terminology's secondary hit |
| `0x338` | `SBC_SecMiss` | R, 64 — secondary misses (partner set searched, line not there) |
| `0x340` | `SBC_BalanceSet` | W — arm migration for the written source-set index |
| `0x348` | `SBC_Attempted` | R, 64 — migrations attempted (setup reached) |
| `0x350` | `SBC_Aborted` | R, 64 — migrations aborted after start (destination full) **plus declines before start** (no destination on offer) — why attempted < migrations + aborted. coder/005 adds `SBC_Declined`. Can also undercount: two declines/aborts in one cycle count once (OR fan-in) — coder/005 fixes |
| `0x358` | `SBC_Reset` | W — write any value to zero all SBC counters/AT/DSS/event state. **Unsafe while lines are parked** (wipes the AT); `sbc_read --reset-all` refuses |
| `0x360` | `SBC_SecPerm` | R, 64 — secondary hits that had to acquire permission (subset of SecHits) |
| `0x368` | `SBC_SecWrite` | R, 64 — serves where the requester needed T. 🐞 **Also counts C-channel write-backs:** `Release` / `ReleaseData` reuse opcodes 6 / 7 of `AcquireBlock` / `AcquirePerm`, so `needT()` is true for `TtoN` / `BtoN`. Counter only; fix in coder/005 |
| `0x370` | `SBC_SecProbe` | R, 64 — serves that probed a client off the parked line first |
| `0x378` | `SBC_DispRelease` | R, 64 — dirty parked lines written back |
| `0x380` | `SBC_DispDrop` | R, 64 — clean parked lines released with no data |
| `0x388` | `SBC_SecC` | R, 64 — serves raised by a C-channel Release |
| `0x390` | `SBC_HomeBranch` | R, 64 — requests that found their own HOME line in BRANCH |
| `0x398` | `SBC_AtAssoc` | R — AT[sel]: bits[7:0]=assocSet, bit8=sd |
| `0x3A0` | `SBC_Parked` | R, 64 — live parked (displaced) lines currently resident |
| `0x3A8` | `L2_Accesses` | R — **legacy:** directory lookups on every channel (includes write-backs and flushes, leaves out repeats) — **not** the terminology's access. Free-running, always active (SBC on or off), **not** reset by SBC_Reset |
| `0x3B0` | `L2_Hits` | R — **legacy:** directory hits on every channel (upgrade misses and write-backs count as hits, secondary hits as misses) — do not quote it as a hit rate |
| `0x3B8` | `SBC_StatsReset` | W — write any value to zero **only** the event/hit counters (the 12 SBC counters + `L2_Accesses`/`L2_Hits` + the five 006 counters below). Leaves `sat`/`armed`/AT/DSS/`parkCount`/`nParked` untouched, so the migration flow keeps running — the safe per-window reset (unlike `SBC_Reset`) |
| `0x3C0` | `SBC_MigrateEnable` | R/W, 1 bit, **default 0 (OFF)** — gates only the START of a new migration. Everything about an already-parked line (secondary search, serve-in-place, `dispRelease`/`dispDrop`, AT teardown) is unaffected, so flipping it off mid-run strands nothing. Saturation counters and the DSS keep running while off |
| `0x3C8` | `L2_MemReads` | R, 64 — outer `AcquireBlock`: blocks read from main memory |
| `0x3D0` | `L2_MemWrites` | R, 64 — outer `ReleaseData`: dirty blocks written to main memory |
| `0x3D8` | `L2_MemAcqPerm` | R, 64 — **was `L2_MemUpgrades` before task 005; old logs say `memUpgrades=`.** Outer `AcquirePerm` (the requester overwrites the whole block): moves no bytes. **Not** the same as an upgrade miss (param `BtoT`, usually sent as `AcquireBlock`) |
| `0x3E0` | `L2_MemRelClean` | R, 64 — outer `Release` without data: clean eviction, moves no bytes |
| `0x3E8` | `L2_Cycles` | R, 64 — free-running L2/uncore clock. In the `SBC_StatsReset` list, so `sbc_read --zero -- cmd` yields exactly the cycles the child ran for |

**The headline metric is `L2_MemReads + L2_MemWrites`** — measured at the outer port, so it does not
depend on anyone's definition of "an access". Report reads and writes **separately** in every table:
SBC can trade one for the other (a parked dirty line that would have been dropped now gets written
back) and a combined figure hides exactly that. Bytes moved = `(reads + writes) × blockBytes`.

**Miss count in the terminology's sense** (see "Cache terminology" above) = `L2_MemReads + L2_MemAcqPerm`:
every outer A message is exactly one data miss or upgrade miss. There is no exact access or hit count
until coder/005 lands.

⚠️ **The metric is only valid when both runs do the SAME WORK.** Fewer memory accesses over a fixed
*time* window can simply mean the machine did less. Measured on the board 2026-09-11: a 600 s window
gave 853 M L2 accesses with migration off against 682 M with it on — the SBC half got ~20% less of
the program done. Use a benchmark input that **runs to completion** (`sbc_read --zero -- <cmd>`), not
`run_sbc_window.sh`'s fixed duration. `rdinstret` and `perf_event_open` are both unavailable on this
board, so `L2_Cycles` is the only work/time normaliser there is.

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
  [ai-documents/bugs/bug-fix-log.md](ai-documents/bugs/bug-fix-log.md).

### SBC Phase 2 — COMPLETE & SIGNED OFF (migrate-on-eviction)

**Sign-off run (2026-08-17):** `migration_stress_test` on the stock `VerilatorRocket8KL116KL2Config`
with all seven corner cases enabled — **7/7 PASS, 0 asserts, 6 migrations committed, destinations
spread across sets 0/3/7.** This retired the last owed Phase-2 item. Two defects were caught during
sign-off review: the Bug-B `s_wsafe` fix had been silently deleted by an uncommitted debug cleanup
(restored in `a2975d6`), and six of the seven test cases in `sw/migration_stress_test.c` had been left
commented out, so every prior "PASS" was 1/7 coverage. Both recorded in `ai-documents/bugs/bug-fix-log.md`.

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
[ai-documents/performance/destination-side-blocker.md](ai-documents/performance/destination-side-blocker.md).**

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
free one, and evicts a well-behaved line to preserve one we were discarding.

⚠️ **CORRECTED 2026-08-29 — migrating *dirty source* lines does NOT need a directory format change.**
That assessment predates strict 1:1 pinning. Under pinning the home set is one value **per set**, and
`ATEntry.assocSet` already stores it (`SetBalanceUnit.scala:26`: *"home set (if destination)"*). The
`+log2(sets)` bits **per way** were never needed. Verified against the RTL in coder task 003 Stage 0.
Dirty source migration is now scheduled work — see `ai-documents/coder/003-serve-in-place/`.

### ⚠️ SUPERSEDED — the +42% / 9.29x figures below are stale

**Do not quote them.** They were measured before `522c540` (which let displaced ways compete for
eviction) and before the 003 corruption fix. The last verified differential is **+0.10% cycles /
1.00x DRAM** at `b6156d4`. Kept for the method, not the numbers.

### ⚠️ MEASURED 2026-08-25 (STALE): SBC is data-safe but **+42% cycles / 9.29x DRAM traffic**

First differential run (SBC on vs `NoSbcConfig`) on a real third-party benchmark — `matmult` from
bringup-bench, N=32. SSOT: [ai-documents/performance/matmult-differential-2026-08-25.md](ai-documents/performance/matmult-differential-2026-08-25.md).

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

**Phase-2 record: [ai-documents/tasks/phase-2.md](ai-documents/tasks/phase-2.md)** (history — the current status is at the top of this file). Migration moves inside the
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
| Phase 3R — **serve in place** | `MSHR.scala` / `Scheduler.scala` / `Directory.scala` | ✅ **GATE 4 GREEN (2026-08-30) — SSOT is `ai-documents/coder/003-serve-in-place/`** | A parked/displaced line can now be served to the CPU directly from its partner set — no repatriation copy — including as a write target, with the correct probe-back and dirty-writeback path. `migration_stress_test` 7/7 PASS, 0 asserts, both BankedStore/directory shadow models clean. First time a migrated line has served real data without corruption. Performance is now measured on the FPGA and is currently a **loss** — see Current status at the top. |

Consolidated bug record (fixed + open), by phase: `ai-documents/bugs/bug-fix-log.md`. Phase-2 record
(absorbs the former `phase-2-dst-collision.md` + `phase-2-2b-handoff.md`): `ai-documents/tasks/phase-2.md`.
Read that, then `ai-documents/design/SBC_integration_plan.md` and `ai-documents/tasks/phase-1.md`, before touching
the migrate datapath. Every arbitration / priority order is in `ai-documents/guides/priority-orders.md`
(written 2026-09-14).

### Code cleanup status (re-checked 2026-09-14) — 2 items left, D/E closed as "do not do". Low priority: do it during the optimization phase

Checklist: `ai-documents/tasks/code-cleanup-suggestions.md`. Ordered plan:
`ai-documents/tasks/July18AfterBreakWorkplan.md` §6.

| Batch | What | Verdict |
|---|---|---|
| A | ~~Delete `MSHR.scala.original`~~ (✅ gone); delete commented DUMP block (`SetBalanceUnit.scala` ~L380) | 🔴 **DO** — DUMP block still owed, zero risk |
| B | Fix comments describing the deleted injection path — still at `Scheduler.scala:602` and `SetBalanceUnit.scala:170` | 🔴 **DO FIRST** — still owed, zero risk |
| C | Excise dead `s_verify` + stall classifier | ✅ DONE in `a2975d6` |
| D | Retire `sbcGateStallCycles` / `sbcForceDstSet` | ⛔ **KEEP BOTH** — verdict reversed |
| E | Guard consolidation / dead `assocQuery` IO / printf trim | ⛔ **DO NONE OF IT** |

- **Batch B is the priority even though it is only comments.** Comments that describe deleted hardware
  are exactly what let the `s_wsafe` fix be deleted in the last cleanup pass.
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
