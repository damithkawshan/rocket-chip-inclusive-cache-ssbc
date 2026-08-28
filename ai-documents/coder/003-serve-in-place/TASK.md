# TASK 003 — serve in place, and let displaced lines be first-class

**Opened:** 2026-08-28 · **Branch:** `sbc-paper-aligned` · **Base:** `0f5a7ac` · **Predecessor:** `002-partner-latch-fix`

> **Note on file count.** This directory has a third file, `diagram.md`, by explicit request — it is
> the visual companion to this work order. The two-files rule still holds for *analysis*: your
> findings go in `REPORT.md`, not into new files.

---

## 0. What this task is, in one paragraph

We are reversing the Q1 decision. The paper (MICRO'09 §2.4) serves a displaced line **where it sits**
and leaves it there; we built **repatriation** instead — copy it home, evict a good line to make
room, erase the parked copy. Both reasons we gave for that override have turned out to be weak (§1).
Serve-in-place is cheaper per hit, it is what the paper measured, and — the part that actually
matters — it is the prerequisite for letting displaced lines be **dirty**, which is what has been
capping migration eligibility all along. In a real system most L2 victims are dirty, and
`displaced ⇒ clean` forbids migrating every one of them.

**Read `diagram.md` in this directory first.** It is seven diagrams and it will save you an hour.

---

## 1. Why the Q1 override no longer holds

Q1 (`001/REPORT.md:663-719`) chose serve-by-repatriation over serve-in-place for two reasons:

1. *"Serve-in-place breaks `displaced ⇒ clean + client-free`, which three sites lean on."* True, but
   the recovery mechanism **already exists and is already built**. `ATEntry.assocSet` is documented
   as *"destination set (if source) / **home set (if destination)**"* (`SetBalanceUnit.scala:26`),
   strict 1:1 pinning is implemented and asserted (`:181-186`), and `:207-208` already calls the AT
   *"their home-set recovery info"*. **The claim in `CLAUDE.md:222` and
   `destination-side-blocker.md:180-207` that this needs a directory format change (+log2(sets) bits
   per way) predates pinning and is false** — under 1:1 pinning the home set is one value **per set**,
   which is exactly what the AT stores. That correction is the reason this task is affordable.
2. *"Our L1 is 4 lines, so the served copy falls out of L1 immediately and we re-pay the search."* An
   artifact of the eval config (`VerilatorRocket8KL116KL2Config` is really 4KB L2 / 8 sets / **256B
   L1s**), not of the design. The paper's L1 is 32KB.

Your Q1 push-back was a good read of the RTL at the time. What changed is that the address-recovery
cost turned out to be a per-*set* table we already have, not a per-*way* directory field.

---

## 2. The whole vocabulary — nine names

Keep names short. Do not invent longer ones.

| name | in one line |
|---|---|
| `homeSet` | the set the **address** maps to. Today's `request.set`, unchanged meaning |
| `physSet` | the SRAM **row** actually being used |
| `probeSet` / `probeTag` | where our outstanding probe will be answered |
| `pairSetReg` | the associated set. **Already exists** (`MSHR.scala:1007-1010`) — no new register |
| `isSrc` | is my set the source side of the pairing (else the destination side) |
| `lineHome` | the home set of the line currently in `meta` |
| `inPlace` | this MSHR is serving from the partner row |
| `secDefer` | eviction held back until the search answers (sibling of the existing `migDeferred`) |
| `busyWays` / `freeWays` | ways locked by a live MSHR / the rest |

---

## 3. The core problem

`set` means two things that are identical today and diverge for a displaced line:

| | used by |
|---|---|
| **PHYSICAL — a row** | Directory row (`Directory.scala:141`), BankedStore row (`BankedStore.scala:131`, `Cat(way,set,beat)`), every hazard tuple in `SourceD.scala:382-409` |
| **ADDRESS — bits of an address** | `expandAddress(tag,set,offset)` (`Parameters.scala:243`) |

**There are exactly three true ADDRESS consumers in the entire design:** `SourceA.scala:54`,
`SourceB.scala:80`, `SourceC.scala:114`. Everything else is a physical row or a CAM key. That 3-vs-30
asymmetry is what makes this tractable — confirm it with `grep -n expandAddress` before you start.

`MSHRStatus.set` (`MSHR.scala:307`) is read as **both**. So is `SourceCRequest.set` — physical at
`SourceC.scala:74-81`, address at `:114`. *Same field, two meanings, one wire.*

### Naming decision: delete `set`, do not repurpose it

Census of `MSHRStatus.set`: **9 sites need ADDRESS, 4 need PHYSICAL, 2 need the probe key.**

Whichever meaning you keep, a **missed** site silently keeps working for native lines and breaks only
for displaced ones — the worst possible failure mode, and the one this project has already been
burned by twice. So: **delete the field named `set`.** All 13 readers become compile errors and each
gets an explicit decision.

```scala
class MSHRStatus(params) … {
  val homeSet  = UInt(setBits.W)
  val physSet  = UInt(setBits.W)
  val probeSet = UInt(setBits.W)
  val probeTag = UInt(tagBits.W)
}
```

Same rename-to-break at `SourceCRequest`, `NestedWriteback`, `SourceARequest`, `SourceBRequest`,
`SinkC.io`, `SinkD.io`, `SourceDHazard`.

**Two exceptions.** `FullRequest.set` **stays** as-is and keeps meaning ADDRESS — it is bulk-connected
via `viewAsSupertype` in five places and is genuinely the address at every producer (`SinkA.scala:83`,
`SinkC.scala:149`, `SinkX.scala:56`). `SourceDRequest` gets an *additive* `physSet` (there is no
address consumer inside SourceD), guarded by a sim assert instead of a compile error:

```scala
assert (!io.req.valid || params.micro.enableSetBalancing.B || io.req.bits.physSet === io.req.bits.set)
```

### Home-set recovery — no new query path, no new register

Serving in place does not move the address, only the row:

```scala
physSet = Mux(inPlace, pairSetReg, request.set)
```

and `pairSetReg` is **already latched**, correctly keyed, by the 002 C1 fix (`MSHR.scala:1005-1010`).

The **reclaim** case (we find a foreign parked line in our *own* row) needs the opposite direction,
and the value is already there: `assocResp.assocSet` (`SetBalanceUnit.scala:129`) is
direction-agnostic — only the `activeSource` gate (`:128`) hides it. Export a `paired` bit and an
`isDest` bit, carry `isSrc` on `io.pairInfo` (`Scheduler.scala:329-330`), latch it beside
`pairSetReg`. Then one wire:

```scala
lineHome = Mux(meta.displaced && !isSrc, pairSetReg, request.set)
```

- `displaced && isSrc` → our own line parked in the partner → home is `request.set`
- `displaced && !isSrc` → a foreign line parked in **our** row → home is `pairSetReg`
- `!displaced` → native → `request.set`

**One register, two readings, each guarded by an assert.** Do not create a second register holding
the same value — that only creates a way for them to disagree.

---

## 4. ⛔ THE `displaced` BIT IS THE DISCRIMINATOR. NEVER WEAKEN A TEST OF IT.

**Read this section twice. It is the one way this task can silently corrupt data.**

`displaced` is how the cache tells a **native** line from a **migrated** one. It is not a hint, not a
policy flag, and not an optimisation. Remove or weaken any test of it and two different addresses
become indistinguishable.

### 4a. Why — the tag does not encode the set

In row `d`, tag `T` can name **two different addresses** at the same time:

| way | `displaced` | its real address |
|---|---|---|
| a native line | `false` | `expandAddress(T, d)` |
| a line parked here from `s` | `true` | `expandAddress(T, s)` |

`displaced` is the **only** thing separating them. And this is **not hypothetical here**:
`set_addr(s,t)` in `sw/migration_stress_test.c` puts the tag entirely above the set-index bits, so
**the same tag exists in every set**. The collision is guaranteed on every run, not rare.

### 4b. Every site where the `displaced` test MUST STAY

An earlier reading of this work proposed dropping `&& !w.displaced` from `hits`. **That is a
corruption bug, not a fix** — `PopCount(hits)` becomes 2 and `Mux1H` at `Directory.scala:216`
returns garbage. Confirmed by the thinker as a hard rule. The full list:

| site | test | why it must stay |
|---|---|---|
| `Directory.scala:188` | `hits` excludes displaced | a normal lookup of `d` must find only the native line |
| `Directory.scala:217` | write-bypass hit, same term | same, for the in-flight write |
| `Directory.scala:196` | `secHits` **requires** displaced | the exact mirror — the search must find only parked lines |
| `Directory.scala:201` | `secBypassHit`, same term | same, for the in-flight write |
| `Directory.scala:167` | `evictableOH` excludes displaced | **prevents re-migrating a parked line** — see 4c |
| `MSHR.scala:1062` | `dstEvictable` excludes displaced | never overwrite a parked line to park another |
| `MSHR.scala:1178` | `migClean` excludes displaced | source side of 4c |
| `MSHR.scala:831`, `:844` | fast-path migrate want / decline | same |

### 4c. ⛔ A displaced line must NEVER be migrated again

The AT records **one hop**: `at(d).assocSet` says "lines parked in `d` came from `s`". If a line
already parked in `d` were migrated on to row `e`, then `at(e).assocSet = d` — so `lineHome` would
compute `d`, but the line's real home is `s`. **The Release then goes to the wrong DRAM address.**
Single-hop is what makes AT-based recovery sound. `!displaced` in `evictableOH` and `migClean` is
what enforces it.

### 4d. What Stage 2 relaxes, precisely

Stage 2 drops **`!dirty`** from the migrate eligibility tests. It does **not** touch `!displaced` in
the same expressions. Concretely at `MSHR.scala:1178`:

```scala
val migClean = !new_meta.dirty && !new_meta.displaced   // BEFORE
val migClean =                    !new_meta.displaced   // AFTER — only the dirty term goes
```

Same at `:831` and `:844`. Stage 2 also relaxes the *assert* at `Directory.scala:211-213` from
`displaced ⇒ clean + client-free` to `displaced ⇒ client-free`. **That is a change to what we assert
about displaced lines, not to how we detect them.** Keep the distinction straight.

### 4e. Do NOT hold `secValid`/`secSet` for the whole transaction

It is the obvious way to get exclusivity and it deadlocks — see §7.

---

## 5. Build order — four stages, gated

**Before you touch anything:** tag `b6156d4` as a fallback, and append both surviving SCU corruption
leads from `002/REPORT.md` §A5 into `ai-documents/bug-fix-log.md` with their file:line evidence. The
code they point at is deleted in Stage 4; do not let the evidence go with it.

### Stage 1 — safety nets, then the mechanical split (zero behaviour change)

1. **Nets first, before anything moves.**
   - `assert(!ren2 || PopCount(hits) <= 1.U)` at `Directory.scala:190` — free permanent net, catches
     §4a forever
   - `PopCount(<CAM match vector>) <= 1` at `Scheduler.scala:452`
   - a C-channel head-of-line watchdog (see §7, this catches a pre-existing bug)
   - the scaffolding assert `enableSetBalancing || homeSet === physSet` at every split site. With SBC
     off these must be identical, which turns the **existing** regression suite into a complete test
     of the rename — that is where most of the mechanical risk lives.
2. **The split.** Delete `MSHRStatus.set`; resolve all 13 compile errors using the table in §6. Split
   the other seven bundles. `physSet := request.set` everywhere for now.
3. **AT direction bit.** `paired`/`isDest` on `assocResp`, widen `io.pairInfo`, latch `isSrc`, derive
   `lineHome` (still `=== request.set` while the old invariant holds).
4. **Sim-only shadow models.** Both gated so nothing elaborates when off.
   - **(a) BankedStore shadow address.** Keep a shadow memory alongside `cc_banks`; every writer
     (`SinkC`, `SinkD`, `SourceD`, `SetCopyUnit`) supplies the full address it *believes* it is
     writing; every reader asserts it matches. **This is the single highest-value item in the task.**
     It catches every wrong-row bug on the cycle it happens instead of as a wrong DRAM value ten
     million cycles later. Chisel `Option[Data]` bundle fields elaborate to nothing when disabled.
   - **(b) `homeShadow`** on `DirectoryEntry`, written with the writer's `lineHome`, checked against
     `at(row).assocSet`. This is the direct test of the assumption the whole design rests on —
     `Directory.scala:192-194` currently only *asserts it in a comment*. Also check
     `secondaryEntry.homeShadow === <searching MSHR's homeSet>`, which is the strongest single check
     available.

**GATE 1:** SBC-off bit-exact. SBC-on pass/fail set **unchanged** — still 6/7, same two failing
cases. Do not proceed until both hold.

### Stage 2 — dirty-capable displaced lines ← *the `p` unlock*

- displaced victims **Release at `lineHome`** instead of being dropped: delete the assert at
  `MSHR.scala:577` and the silent-drop branch at `:1209-1218`
- `SourceC.scala:114` uses `homeSet`; `:79-80` keeps `physSet`
- relax `Directory.scala:211-213` to `displaced ⇒ client-free` **only** (keep the client half)
- drop `!dirty` from `migClean` (`MSHR.scala:831`, `:844`, `:1178`)
- guard `SetBalanceUnit.scala:204-215` (`io.clear`) so the AT cannot be wiped while displaced lines
  exist — the TODO at `:207-208` becomes reachable corruption once this stage lands

**GATE 2:** G1–G3 (§8), plus migration eligibility `p` measurably up. Report the number.

### Stage 3 — way-lock + eviction deferral (still repatriating)

- Directory `busyWays` mask (§7), `assert(freeWays.orR)`
- extend `dstOfferOwned` (`Scheduler.scala:566`) to test `physSet` **as well as** `homeSet` — missing
  the `physSet` term lets a migration park a victim into a row being served. Silent.
- fix `partnerBusy` (`Scheduler.scala:333`) to compare `physSet`
- **`secDefer`**: today `MSHR.scala:1223` arms `s_release` in the *same cycle* `:1245` arms the
  search, but serve-in-place needs no home way at all. Add `secDefer` as the sibling of the proven
  `migDeferred` (`:1196-1208`) — arm nothing, resume on the search result.
- **Factor the eviction arming into a shared Scala `def`.** The plan-time and resume-time paths must
  arm bit-identical state. A gate added in one place and missed in its sibling is exactly what
  `707445c` was, and the codebase names that lesson at `MSHR.scala:378-380`.
- watchdogs: `secDeferCtr < 1000`, plus `!(secDefer && !s_release)` and `!(secDefer && a.valid)`

**GATE 3:** behaviour unchanged, watchdogs quiet.

### Stage 4 — serve in place ← *and the corruption experiment*

- `inPlace`, the `physSet` mux, `meta` re-pointed at `(pairSetReg, secondaryWay)`
- `final_meta_writeback.displaced := inPlace` — a line served in place **stays** displaced
- `s_sinval` stays **true** on a serve — we keep the parked copy. That is the whole point.
- `(probeSet, probeTag)` routing at `Scheduler.scala:91` and `:449-453`
- **new pprobe arm** in the search-result block: the parked line may now be client-held, and there is
  no such logic today because the old invariant guaranteed it could not be
- then the **C/X secondary search** (§7) — required, not optional
- this deletes `doSecCopy`, `s_scopy`, `w_scopy`, `d_ready`'s `w_scopy` term, and the SCU write into
  a live set

**Trap — read twice.** `inPlace` must survive a `repeat` reload. `MSHR.scala:1080-1126` runs on
`io.directory.valid || (io.allocate.valid && repeat)`. Clearing `inPlace` in that shared block leaves
`meta` pointing at the partner row while `physSet` reverts to the home row — **every subsequent
access is off by a whole row, silently.** Clear it only under `io.directory.valid`.

**GATE 4:** G1–G5.

---

## 6. Per-consumer decision table

Resolve every compile error with this. `H` = `homeSet`, `P` = `physSet`, `R` = probe key.

### Scheduler

| site | today | needs | note |
|---|---|---|---|
| `:91` sinkc resp routing | `resp.bits.set === status.set` | **R** | `resp.set === probeSet && resp.tag === probeTag`. **The tag term is mandatory** — an MSHR evicting a displaced victim advertises a `probeSet` that is another MSHR's `homeSet`. Update the now-false comment at `MSHR.scala:782`. |
| `:104,105,108` mshr_stall | `set === set` | **H** | BC/C pre-emption interlock, about the address set |
| `:153` `scheduleSet` → `:372` | `Mux1H(sel, set)` | **H** | the reload re-reads the *home* row |
| `:153` `scheduleSet` → `:592` | | **H** | the AT is indexed by home set |
| `:190-191` nestedwb | `set`, `tag` | **H** | `(homeSet, tag)` names an address uniquely; `(physSet, tag)` aliases |
| `:207` `setMatches` | `set === request.bits.set` | **H** | still one MSHR per *address* set |
| `:313` reload | `allocate.bits.set := status.set` | **H** | relabelling with `physSet` corrupts the queued request |
| `:333` `partnerBusy` | `o.status.set === m.status.secSet` | **P** | "is anybody physically in that row" |
| `:449-453` sinkC way CAM | key on set, returns `way` | **R key**; return `way` **and** `physSet` | add `sinkC.io.physSet` driven by the same one-hot |
| `:454-455` sinkD | by source index | **P** | by MSHR id, not set — pure rename, no aliasing risk |
| `:566` `dstOfferOwned` | `set === coldDst` | **P and H** | `physSet === coldDst \|\| homeSet === coldDst`. Missing `physSet` is silent corruption. |
| `:586` `assocQuery` | `Mux1H(fanout, set)` | **H** | AT lookup key |
| `:603` commit src | `Mux1H(migCommit, set)` | **P** | assert `=== homeSet` here; migration only ever starts from a native victim |

### MSHR

| site | today | needs |
|---|---|---|
| `:297-303` nestedwb match | `nestedwb.set === request.set` | **H** — rename only, already correct |
| `:586` acquire a | `a.bits.set := request.set` | **H** — already correct |
| `:598-601` probe b | `b.bits.set := request.set` | **H, victim-aware**: `Mux(probingVictim, lineHome, request.set)`, paired with the existing tag mux at `:599`. **Factor the selector into one named wire** and use it for both so they cannot drift. |
| `:602-608` release c | `c.bits.set := request.set` | **split**: `physSet` + `homeSet := lineHome` |
| `:609-617` grant d | bulk connect + `way := meta.way` | add `d.bits.physSet := physSet` after the `viewAsSupertype` |
| `:620-621` dir write | `Mux(mig_dir1, …, request.set)` | **P** — replace the final arm with `physSet` |
| `:402-405` copy lane | from `request.set` | **P** + assert equality |
| `:806-807`, `:826-833` | `migOffer.bits =/= request.set` | **P** + assert equality |

### SinkC / SourceC / SourceB

| site | needs |
|---|---|
| `SinkC.scala:92` `io.set` | **H** — rename, derivation unchanged |
| `SinkC.scala:101-103` `bs_adr` | **P** — `bs_adr.bits.set := io.physSet`, a **new Flipped input driven exactly like `io.way`**. Do not derive the row from the address. This is the bug we found. |
| `SinkC.scala:109` `resp.bits.set` | **H** — it is the routing key |
| `SinkC.scala:149` `req.bits.set` | **H** — `FullRequest.set` stays address |
| `SourceC.scala:74`, `:80` | **P** |
| `SourceC.scala:114` | **H** — `expandAddress(s3_req.tag, s3_req.homeSet, 0.U)` |
| `SourceB.scala:73`, `:80` | **H** — rename only |
| `SourceA.scala:54` | **H** — rename only |

Add to each split bundle: `assert(enableSetBalancing || homeSet === physSet)`.

---

## 7. Exclusivity, and two pre-existing bugs found while tracing

### Way-lock, not a set fence

With serve-in-place, MSHR `X` (home `s`, serving at row `d`) coexists with MSHR `Y` (home `d`). The
only *structural* hazard is `Y`'s victim selection picking `X`'s way — `Directory.scala:173-177` has
no notion of ownership. Probe mis-routing and CAM aliasing are both fixed by the `(probeSet,
probeTag)` two-key match. BankedStore, SourceD hazards and per-way directory writes are already safe.

So the fix is **way-granular, not set-granular**: pass a `busyWays` mask alongside the directory read
and mask it into the victim `Mux`. It never blocks a request — it only steers a mux — so it cannot
deadlock. `assert(freeWays.orR)` is provable: at most two ways in any row are locked.

**Do not** hold `secValid`/`secSet` for the whole transaction instead. It gates `request.ready`
(`Scheduler.scala:366`); an MSHR serving in place would hold it for its entire life, and it can have
an outstanding pprobe. A blocked `Release` in front of that `ProbeAck` deadlocks the cache.

Once way-locking lands, `partnerBusy` (`Scheduler.scala:332-334` → `MSHR.scala:410`) can become an
**assert instead of a wait** — removing the last waiting edge makes the deadlock argument trivial.
Fix its domain to `physSet` first.

### Pre-existing bug A — C-channel head-of-line deadlock (exists today)

`secValid` folds into `dstSetConflict` (`Scheduler.scala:237`) → gates `request.ready` (`:366`). A
client `Release` addressed to a fenced partner set blocks the C head; if that client's `ProbeAck` is
queued behind it, deadlock. Rocket arbitrates its probe and writeback units onto one C channel, so it
is reachable. **Fix:** exempt `prio(2)` from the fence at `:366` only —

```scala
!(dstSetConflict && !request.bits.prio(2))
```

Safe because a `prio(2)` request never evicts (`MSHR.scala:1129-1144` has no eviction branch and
asserts `new_meta.hit` at `:1143`), so it creates no victim-selection hazard. Keep the full condition
on `allocReady` (`:239`).

### Pre-existing bug B — C/X requests for displaced lines

A client voluntarily releasing a parked line allocates on its home set, reads that row, misses, and
trips `assert(new_meta.hit)` (`MSHR.scala:1143`). Same for an MMIO flush, which is a **silent no-op**
today and becomes **data loss** once displaced lines are dirty. So:

- arm the secondary search for the **C-channel** (`MSHR.scala:1129-1144`) and **X-channel**
  (`:1146-1158`) plan branches, not only the A-channel branch at `:1245`
- MMIO flush is a documented unsupported constraint (`phase-3.md:156-158`) — **upgrade the comment to
  an assert** rather than building flush support

Record both in `bug-fix-log.md` as found-in-003, pre-existing.

### What the platform gives us free

`Parameters.scala:175` has `require(lastLevel)`; `Scheduler.scala:77` ties
`out.b.ready := true.B // disconnected`; there is no `SinkB.scala`. **There are no inbound outer
probes**, which is the hardest part of serve-in-place elsewhere. Assert it and record it as a
platform constraint.

---

## 8. Verification gates

| # | what | pass condition |
|---|---|---|
| G0 | elaborate with `enableSetBalancing=false` | bit-exact with baseline, zero new hardware |
| G1 | `migration_stress_test`, all 7 cases, SBC on | Stages 1-3: pass/fail set **unchanged**. Stage 4: **7/7, 0 asserts** |
| G2 | `matmult` (bringup-bench) SBC-on vs `NoSbcConfig` | identical checksum; no worse than `b6156d4`'s +0.10% cycles / 1.00x DRAM |
| G3 | `homeShadow` vs `at(row).assocSet` | zero mismatches |
| G4 | BankedStore shadow-address model | zero mismatches on every port |
| G5 | `SBC_SecHits` > 0 and rising | serve-in-place actually returns something — `b6156d4` could not |

Control config: `VerilatorRocket8KL116KL2NoSbcConfig`.

**Measurement caveat — do not skip this in the report.** The eval config is 8 sets / 256B L1. It is
adequate for correctness (G1/G3/G4) but **not** for a performance claim (G2/G5). Do not quote G2 as a
general result. Larger geometry is queued as task 004 and needs `sw/migration_stress_test.c`'s
hardcoded `L2_SETS 8` / `HOT_SET 5` / `set_addr()` rewritten first.

---

## 9. The open corruption bug — what to do with it

`case_reaccess_migrated` still fails and the cause is unknown. Two hypotheses were killed by gates in
002; the two surviving leads (`copy_wsafe`'s one-cycle blind spot at `SourceD.scala:405-409` + `:94`,
and the unfenced repatriation destination) are both about **the SCU block copy into a live home
set** — which **Stage 4 deletes**.

**Do not run the instrumentation pass from 002.** It spends a build+run cycle on code we are
removing. Instead:

1. Record both leads in `bug-fix-log.md` **before** Stage 4 (Stage 0 of §5).
2. Stages 1-3 must keep the pass/fail set **unchanged** — that is a real control, use it.
3. Stage 4 is the experiment. If `case_reaccess_migrated` passes, the bug was in the repatriation
   copy and it is gone.
4. **If it still fails after Stage 4, stop.** Do not call the stage done. You now have a much smaller
   serve path plus the Stage 1 shadow models, which should name the exact cycle and port.

---

## 9b. Findings register — track these, do not let them evaporate

Four things were found by reading during planning. **Confirmed by the thinker as real.** None was
found by a run, so each needs either a confirming observation or an explicit "could not reach it".
Carry this table into `REPORT.md` and fill the right-hand column.

| # | finding | status | what closes it |
|---|---|---|---|
| **P1** | **C-channel head-of-line deadlock** (exists today). `secValid` → `dstSetConflict` (`Scheduler.scala:237`) → `request.ready` (`:366`). A `Release` to a fenced partner set blocks the C head; a `ProbeAck` queued behind it in the same client channel deadlocks. Rocket arbitrates probe + writeback onto one C channel, so it is reachable. | 🔴 open, pre-existing | the `prio(2)` exemption in §7, **plus** the C-head watchdog firing zero times across a full run. Record in `bug-fix-log.md`. |
| **P2** | **C/X requests for displaced lines.** A voluntary `Release` of a parked line allocates on its home set, misses, and trips `assert(new_meta.hit)` (`MSHR.scala:1143`). MMIO flush is a silent no-op today and becomes **data loss** once displaced lines are dirty. | 🔴 open, pre-existing | secondary search armed for the C and X plan branches; flush constraint upgraded from comment to assert. Record in `bug-fix-log.md`. |
| **P3** | **`!w.displaced` must never be weakened** (§4). Two different addresses share a tag in the same row; this bit is the only discriminator. Includes the single-hop rule in §4c. | 🟡 policed | the `PopCount(hits) <= 1` assert at `Directory.scala:190` landing in Stage 1 and staying quiet. |
| **P4** | **`inPlace` must survive a `repeat` reload** (§5 Stage 4 trap). Clearing it in the shared reset block leaves `meta` on the partner row while `physSet` reverts home — every access off by a row, silently. | 🟡 known trap | the assert named in Stage 4, plus GATE 4 green. |

**If you cannot reach one of these in simulation, say so.** "Could not construct a case that triggers
P1" is a legitimate and useful report line. Silently dropping it is not — that is exactly how the
`s_wsafe` fix got deleted during a cleanup pass, and it is why this register exists.

---

## 10. Stopping conditions

Stop and report, do not push through, if:

- GATE 1 does not come back clean — a mechanical rename that changes behaviour means a site was
  mis-classified, and every later stage compounds it
- any shadow-model assert (G3/G4) fires — that is the AT-correctness assumption failing, and the
  whole design rests on it
- Stage 4 leaves `case_reaccess_migrated` failing (§9 item 4)
- you find that the premise in §1 is wrong — say so before building. Your Q1 push-back was right
  once already.

## 11. Acceptance

- G0-G5 green
- both pre-existing bugs (§7) recorded in `bug-fix-log.md`
- `p` before/after reported as a number, not an adjective
- `REPORT.md` states plainly which of the two corruption outcomes in §9 occurred
