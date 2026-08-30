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

---

## Amendment 1 — DO NOT FIX P5. DELETE IT. GO STRAIGHT TO SERVE-IN-PLACE. (2026-08-29)

**Decision: neither of the two options you offered. Do not fix `doSecCopy`, and do not leave the run
halted either. Delete the repatriation path now and build serve-in-place as the next thing you do.**

You were right to stop and ask — the two instructions did conflict. The resolution is that one of
them expired.

### Why the staging is withdrawn

**Stage 4 was an experiment to *infer* P5. You found P5 directly.** §9 kept repatriation alive through
three stages so that deleting it in Stage 4 would tell us whether the corruption lived there. The
shadow model answered that question on its first run, with a cycle-accurate trace and a named missing
term. There is nothing left for the experiment to establish, so there is no reason to keep the code.

**And the staged control was weaker than §5 claimed anyway.** Your §1a note is the key one: with SBC
off, `homeSet` and `physSet` are driven from the same Scala value. But that is true in *every* build
until `inPlace` exists — which is Stage 4. So Stages 1-3 could never detect a **reader** that picked
the wrong field, which is the principal risk of the split. The control could not catch the thing it
was controlling. Keeping repatriation alive to run it was buying nothing and costing a blocked task.

**P5 is deleted, not hidden.** It is a collision between `doSecCopy` and `doMigCopy`. Serve-in-place
removes `doSecCopy` entirely — no copy, nothing moves. `doMigCopy` survives with one caller and
cannot collide with itself. The mechanism is genuinely gone, not merely unreachable.

### Revised stage list

Old Stages 2/3/4 are re-ordered. The eviction deferral and the way-lock were never independent of
serve-in-place — the deferral exists *because* serve-in-place needs no home victim — so they fold in.

| stage | what | status |
|---|---|---|
| 1 | split + nets + AT direction bit + shadow models | ✅ landed |
| **2 (was 4+3)** | **serve in place.** Delete `doSecCopy`/`s_scopy`/`w_scopy` and the SCU write into a live set. Build `inPlace`, `secDefer`, `busyWays`, `(probeSet, probeTag)` routing, the pprobe arm, C/X search | ⬅ **next** |
| 3 (was 2) | dirty-capable displaced lines | after |

Dirty and serve-in-place are independent — both depend only on the split. Serve-in-place goes first
because it is what unblocks the halted run.

### GATE 1 is restated, and closed

Your two corrections are accepted; both of my gate definitions were wrong.

- **G0 becomes:** *"SBC-off delta is renames + the duplicated bundle field + sim-only asserts, with no
  control-logic change."* Your measured 9-module accounting satisfies it. "Bit-exact / zero new
  hardware" was unachievable the moment the split touched bundles present in both builds, and
  `Option`-gating to dodge it would have reintroduced the exact ambiguity the split removes. You made
  the right call and evidenced it.
- **The SBC-on "pass/fail set unchanged" half is void** — the tree no longer has a stable pass/fail
  set to preserve, and per the above it was not testing what I said it was. Drop it.
- **The SBC-off regression is still required** and is now the whole of GATE 1. It validates the
  mechanical rename's producer side. The reader side is validated by the shadow model in Stage 2,
  once `homeSet =/= physSet` for the first time.

### Carry into Stage 2

- **`dstSetConflict`'s domain** — you flagged it as open at the end of §1b. It lands now: once an MSHR
  serves at a row that is not its home set, "is this request's set fenced" and "is this row fenced"
  stop being the same question. Resolve it as part of the way-lock work.
- **P1's other half.** Your trace is right and my §7 fix only covers the case where an MSHR already
  owns the fenced set. Do **not** patch the fence separately — the way-lock replaces the set fence for
  victim protection, so re-derive P1 against the new structure rather than the old one. Keep the
  watchdog and its `alloc`/`queue` fields.
- **P5 and P6 stay in the register as found-in-003**, marked *closed by deletion* and *open* 
  respectively. P5 is the explanation for `case_reaccess_migrated`; that claim is confirmed if the
  case passes once serve-in-place lands, and if it does **not**, say so loudly — it means P5 was real
  but not the whole story, and the shadow model is now in place to find the rest.
- **Your `probeSet` timing finding is the more interesting one for the future.** "Name the condition
  once" is this repo's standing rule (it is what `707445c` taught) and you correctly identified the
  one place it is *wrong* advice, because scheduling and waiting are different questions. Keep both
  selectors distinct and leave the comment explaining why.

### One thing not to lose

The headline of this task changed. It is no longer "serve in place and see if the corruption goes
away". It is **"the shadow model found, in one run, a bug that survived two full tasks of hunting"** —
and it did so from gate runs you had to do anyway. Make sure `REPORT.md`'s verdict says that plainly.

---

## Amendment 2 — GATE 1 IS CLOSED. PROCEED TO SERVE-IN-PLACE. (2026-08-29)

You wrote your report before Amendment 1 landed, so it asks a question Amendment 1 already answered.
Restating it here with the new evidence, plus the one argument of yours that needed a real reply.

### GATE 1 — CLOSED ✅

Under the restated G0 (Amendment 1), the SBC-off regression **is** the whole gate, and it is green:
**7/7 PASS, exit 0, 0 asserts.** That covers both intended behavioural changes — the two-key ProbeAck
match and the `prio(2)` exemption — in the build where they are supposed to be inert. Your netlist
accounting closes the elaboration half. **The SBC-on "pass/fail set unchanged" half is withdrawn**
(Amendment 1); do not try to report a number for it.

Your note that the run *halts* rather than fails, so cases 4-7 were never exercised, is exactly the
right call. Do not round that up.

### Your "fix it now" argument — answered, and it does not survive one check

You argued that fixing P5 gives Stages 1-3 "a working control". **It does not, and the reason is your
own §1a argument applied one step further.**

`MSHR.scala:330` today is:

```scala
val physSet = request.set        // literally request.set, unconditionally
```

So `homeSet === physSet` in **every** build — SBC off *and* SBC on — until `inPlace` introduces the
mux. You established that a mis-classified **reader** is invisible when the two fields carry the same
value. They carry the same value right now. **So there is no control to be had by fixing P5**: an
SBC-on stress run today cannot validate the split's readers any more than the SBC-off run could.

The split's reader correctness becomes testable for the first time in Stage 2, when `physSet` first
differs from `homeSet`. That is where the shadow model earns its keep a second time.

**So the fix buys one thing only: confirming P5 caused `case_reaccess_migrated`.** And we already have
a cycle-accurate trace of the collision plus the exact missing term — the mechanism is established.
What a run would add is "and it was the *only* cause", which Stage 2 answers anyway and for free.

**Decision stands: do not fix `doSecCopy`. Delete the path.** Not because the fix is wrong — your
diagnosis is right and the term is genuinely missing — but because we are removing the code, and a
one-term patch to code being deleted is the definition of throwaway work.

### Next: Stage 2, serve-in-place

Per the Amendment 1 stage list. Start by deleting the repatriation path, then build `inPlace`,
`secDefer`, `busyWays`, `(probeSet, probeTag)` routing, the pprobe arm, and the C/X search.

### New acceptance item — prove repatriation is actually gone

The concern behind deleting rather than rewinding is that fragments linger. Make it mechanical rather
than a matter of faith. **Both must hold before Stage 2 is called done:**

```
grep -rn "doSecCopy\|repatriating\|s_scopy\|w_scopy" design/     →  zero hits
```

and **`SetCopyUnit` must have exactly one caller** (`doMigCopy`) afterwards. Report both in
`REPORT.md`. (For the record: rewinding to a pre-repatriation commit was considered and rejected —
`b6156d4` would cost the whole Stage 1 split and both shadow models, i.e. the code that found P5, and
the earlier `7e45425` predates the directory secondary search entirely, so serve-in-place would have
nothing to find.)

### Carried forward, unchanged

- **P5** → status becomes *closed by deletion*. If `case_reaccess_migrated` passes once Stage 2 lands,
  P5 was the long-open corruption; if it does **not**, say so loudly — P5 was real but not the whole
  story, and the shadow model is in place to find the rest.
- **P6** stays open and recorded. Do not fix it in Stage 2 — `migFastWantW` is migration-side logic
  that Stage 2 does not touch, and mixing it in muddies the attribution.
- **P1's other half** — re-derive against the way-lock, not the old set fence (Amendment 1).
- **`dstSetConflict`'s domain** — lands in Stage 2 with the way-lock.

### Two things from your report worth keeping

1. **The `printf` vs `assert` note** (`PRINTF_COND` gates one, not the other, so diagnostics belong in
   the assert message) is a genuinely reusable debugging fact for this repo. It belongs in
   `bug-fix-log.md` or the daily summary, not only in a task report that will be closed.
2. **The `probeSet` timing finding** — that "name the condition once" is the *wrong* rule where
   scheduling and waiting are different questions — is a real correction to this repo's standing
   advice. Keep both selectors distinct and keep the comment explaining why.

You were right to stop and ask rather than guess. The conflict you identified was real; one side of
it had expired.

---

## Amendment 3 — STAGE 2 WORK ORDER (2026-08-29)

Stage 2 is the whole of serve-in-place plus the deferral and way-lock folded in (Amendment 1). It is
too big for one commit. **Five steps, each with its own check.** Do not skip a check to save a run —
step 2a is an experiment we want the result of, and 2b is the step most likely to bite.

Everything in §4 (the `displaced` discriminator, the single-hop rule) still binds unchanged.

---

### 2a — Delete the repatriation path ← *and this is the P5 experiment*

**Delete:**

- `repatriating` register, `s_scopy`, `w_scopy`
- `doSecCopy` (`MSHR.scala:~399-405`) and the repatriation arms of the copy-lane muxes (`:402-405`)
- the `(!repatriating || w_scopy)` term in `d_ready` (`:381`) and in `sec_dir1` (`:357`)
- the `.otherwise { w_scopy := true.B }` arm of `io.copy_done` (`:959-963`) — the copy lane now has
  exactly one job, so the done pulse needs no disambiguation
- `repatriating` from `secValid` (`:334`)
- the "migration parked into the way being repatriated" assert (`:429-431`) — it describes a race
  that cannot exist once there is one copy job
- the `SEC-COPY-DONE` printf

**Keep:** `searching`, `s_ssearch`/`w_ssearch`, `secWay`, `s_sinval` and `sec_dir1` (the erase),
`secHit`/`secMiss`.

**Behaviour after 2a:** a secondary hit takes the path the permission-reject arm already takes — set
`s_sinval := false.B` to erase the parked copy, and fall through to the memory fetch. Collapse the
`(secTip || !req_needT)` branch at `:1019-1042` accordingly; every hit is temporarily a "found it,
drop it, fetch it" event. `secHit` still counts, so the search is still measured.

**Checks — all three:**

```
grep -rn "doSecCopy\|repatriating\|s_scopy\|w_scopy" design/     →  MUST be zero
```
- `SetCopyUnit` has **exactly one** caller (`doMigCopy`)
- `migration_stress_test`, SBC on

**⭐ This run is the experiment. Report the result prominently.**

- **7/7 PASS** → **P5 was the long-open corruption.** `case_reaccess_migrated` is closed, attributed,
  and the cause is on the record. Say so plainly in the verdict.
- **anything else** → **STOP.** P5 was real but not the whole story. Do not start 2b. The shadow
  models are in place and proven; use them.

The 001 bisect established that search-plus-erase passes, so 7/7 is the expected result, not a hope.

---

### 2b — Move the evict-or-migrate decision to after the search ← *the risky one*

Today `MSHR.scala:1223` arms the eviction in the **same cycle** `:1245` arms the search. Serve-in-place
needs no home way at all, so the decision must wait for the answer.

**Restructure the A-channel plan block** (`:1160-1250`): hoist the search decision above the eviction
chain. When the search will run, arm **nothing** — no `s_release`, no `s_rprobe`, no `migrating`, no
`migDeferred` — and set `secDefer`. Resume in the search-result block.

**⛔ The whole decision moves, not just the eviction.** A paired source that only ever resumed with a
plain eviction could never migrate again, and the parked pool would drain to nothing. Factor the
entire assess chain (migrate-fast / migrate-defer / reclaim / normal-evict) into **one Scala `def`**
called from both the plan block and the search resume. Bit-identical state from both call sites — a
gate added at one and missed at the other is `707445c`, three times over now.

The def must take its metadata as a parameter: `new_meta` at plan time, `meta` at resume time.

**🔴 P6 must be fixed here. It stops being benign at this step.** `migFastWantW` (`:826-833`) lacks the
`!(searching && !w_ssearch)` term that its sibling `migFastDecline` has (`:913`). At the search-resume
cycle `io.directory.valid` is true carrying **the partner's** result — so without that term the fast
path would assess the partner's victim as if it were ours. Add it. Update P6's row in the register
from "believed benign" to "fixed in 2b, and here is why it stopped being benign".

**Three decide points now exist**, chained and never concurrent: plan → search-resume → (maybe)
`migDeferred`-resume (`:925`). Generalise whatever bounds them to "at most one deferral outstanding".

**Watchdogs and asserts:**

```scala
assert (secDeferCtr < 1000.U,                  "SBC: secDefer stuck - the search never answered")
assert (!(secDefer && !s_release),              "SBC: eviction committed while the search was open")
assert (!(secDefer && io.schedule.bits.a.valid),"SBC: outer Acquire during a deferred search")
assert (!(secDefer && migDeferred),             "SBC: two deferrals outstanding")
```

**Check:** stress test still 7/7, watchdogs quiet, migration counts **not** collapsed versus 2a — if
migrations fall off a cliff, the decision did not really move, it just got skipped.

---

### 2c — Way-lock, and the two domain fixes

An MSHR is about to hold a way in a row it does not own for its whole life. Nothing today stops
another MSHR victimising that way.

- **`busyWays`**: a per-row mask of ways held by live MSHRs, passed alongside the directory read and
  masked into the victim chooser (`Directory.scala:173-177`). It steers a mux and never blocks a
  request, so it cannot deadlock. `assert(freeWays.orR)` — provable, since at most two ways in a row
  are locked (the row's own MSHR, and one serving in place from its partner).
- **`dstOfferOwned`** (`Scheduler.scala:566`): must test `physSet` **as well as** `homeSet`. Missing
  the `physSet` term lets a migration park a victim into a row being served. Silent.
- **`partnerBusy`** (`:333`): compare `physSet`.
- **`dstSetConflict`'s domain** — the item you flagged as open at the end of §1b. It lands here. Once
  an MSHR serves at a row that is not its home, "is this request's set fenced" and "is this row
  fenced" are different questions. Resolve it against the way-lock.
- **P1's other half** — re-derive against the way-lock, **not** the old set fence. Do not patch
  `request.ready` further. Keep the watchdog and its `alloc`/`queue` fields. If the way-lock makes the
  fence unnecessary for victim protection, say so — that is the cleanest close.

Once the way-lock is in, `partnerBusy` (`MSHR.scala:410`) should become an **assert instead of a
wait**. Removing the last waiting edge makes the deadlock argument trivial. Do that, and write the
argument down.

**Check:** stress test still 7/7. `freeWays.orR` quiet.

---

### 2d — Let C and X requests find a displaced line (inert on arrival)

Arm the secondary search on the **C-channel** (`:1129-1144`) and **X-channel** (`:1146-1158`) plan
branches, so a Release or flush whose line is parked can find it instead of tripping
`assert(new_meta.hit)` (`:1143`).

**This is deliberately built before 2e.** Nothing is client-held yet, so the C-path search can never
hit and the step is inert — which is exactly why it is safe to land first. 2e makes it live.

MMIO flush stays unsupported (`phase-3.md:156-158`): **upgrade the constraint from a comment to an
assert** rather than building flush support. A silent no-op over a displaced line becomes data loss in
Stage 3.

**Check:** stress test 7/7, unchanged. Close **P2** in the register.

---

### 2e — Serve in place

- `inPlace` register; `physSet = Mux(inPlace, pairSetReg, request.set)` — replacing today's
  unconditional `val physSet = request.set` (`:330`)
- on a secondary hit, re-point `meta` at `(pairSetReg, secondaryWay)` and **do not** arm any eviction
- `s_sinval` stays **true** — we keep the parked copy. That is the point.
- `final_meta_writeback.displaced := inPlace` — a line served in place **stays** displaced
- `(probeSet, probeTag)` routing goes live (already built in Stage 1)
- **new pprobe arm** in the search-result block: the parked line may now be client-held. No such logic
  exists today, because the old invariant guaranteed it could not be. Mirror the shape of the existing
  permission-probe arm at `:1252-1259`.
- relax `Directory.scala:211-213` to `displaced ⇒ clean` **only** — drop the client-free half, keep
  the clean half (Stage 3 drops that one)

**🔴 The trap, read twice.** `inPlace` must survive a `repeat` reload. `MSHR.scala:1080-1126` runs on
`io.directory.valid || (io.allocate.valid && repeat)`. Clearing `inPlace` in that shared block leaves
`meta` pointing at the partner row while `physSet` reverts to the home row — **every subsequent access
off by a whole row, silently.** Clear it only under `io.directory.valid`, and assert it.

**This is the first time `homeSet =/= physSet`.** Everything the split did becomes testable here and
not one moment earlier — a mis-classified *reader* has been invisible until now because both fields
carried the same value. **Expect the shadow model to be the thing that finds it.** If it fires, that is
the model working, not a setback.

**Check:** GATE 4 — G1 (7/7, 0 asserts), G3, G4, G5 (`SBC_SecHits` > 0 and rising). G2 if cheap.
Close **P4**.

---

### Order, and what each step buys

| step | what | if it fails |
|---|---|---|
| 2a | delete repatriation | **STOP** — P5 was not the whole story |
| 2b | decision moves after the search (+ P6 fix) | the deferral is wrong; nothing later can work |
| 2c | way-lock + domains | exclusivity is wrong |
| 2d | C/X search | inert; a failure here means the arming is wrong |
| 2e | serve in place | the first real test of the Stage-1 split |

Commit each step separately. If a later step regresses, we want to bisect it in one command.

### Acceptance for Stage 2

- the 2a grep returns zero, and `SetCopyUnit` has one caller
- the 2a experiment result stated plainly — P5 confirmed or not
- P2, P4, P6 closed in the register; P1 re-derived against the way-lock; P5 marked closed-by-deletion
- GATE 4 green
- `SBC_SecHits` > 0 — **the first time in this project that a parked line has ever returned anything**

---

## Amendment 4 — 2a GATE PASSED. P5 CONFIRMED. GO ON 2b. (2026-08-29)

### The gate is passed, and my wording was the thing at fault

I wrote two branches — "7/7 → P5 confirmed" and "anything else → STOP, P5 wasn't the whole story" —
and put a data-correctness question and a run-completion question into one test. **You were right to
refuse both labels.** The correct reading:

**P5 is confirmed as the long-open corruption, and `case_reaccess_migrated` is closed.** Every
data-correctness check passes, zero shadow firings, zero `homeShadow` firings, and the run reaches
~7.5× further than Stage 1 did. Cases 4 and 5 — the two that had been failing since 001 commit 4 —
both pass, and they pass with **no wrong data anywhere**, which is the claim that matters.

The case-7 halt is a **loud invariant assert with correct data**, which is the opposite of the failure
mode the STOP branch was written for. STOP meant "silent corruption survives". It does not.

**Record it as closed:** the corruption that survived tasks 001 and 002 was the SetCopyUnit colliding
with itself — `doSecCopy` writing `(physSet, meta.way)` while `doMigCopy` read the same location,
five cycles apart, because `doSecCopy` lacked the `!migDeferred` term its sibling `a.valid` has. Found
by the Stage-1 BankedStore shadow model on its first run.

It also settles 002 honestly: your reading that case 4's FAIL→PASS there was a perturbation artifact
**holds** — it passes here for a real reason instead.

### Your P6 correction is accepted, and it is the more important finding

I said P6 "stops being benign when 2b moves the decision". You showed that deleting repatriation alone
was enough, because a secondary hit used to cancel the fetch and enter the repatriation, shifting when
the search result landed relative to `migDeferred`. **It was never benign. It was masked — by code we
were deleting.**

That generalises, and it is worth more than the bug:

> **A finding marked "benign by reading" is only benign relative to the code that happens to mask it.
> When that code is deleted, re-open every such finding rather than carrying the label forward.**

P6 was the only one carrying that label, so nothing else needs re-opening — but the rule applies to
every future one. Put it in `bug-fix-log.md` next to the two facts you already recorded there.

### GO — start 2b, P6 first

The stop condition was "silent corruption survives", and it does not. Proceed.

Fix P6 as 2b's first item exactly as Amendment 3 specifies — add `!(searching && !w_ssearch)` to
`migFastWantW` (`:826-833`), the term `migFastDecline` already has (`:913`). Then the rest of 2b.

Your confirmation method — reading the missing term off the failing cycle (`searching=1`,
`w_ssearch=0`, `dirHit=0`) rather than inferring it — is the standard this project should hold. The
`dirHit=0` observation is the part to keep: a miss on the **partner's** search result says nothing
about our own victim, which is precisely why the fast path must not look at it.

Update P6's register row from "believed benign, recorded, not fixed" to **"never benign — masked by
the repatriation path; exposed by 2a, fixed in 2b"**, with the failing-cycle values.

### Housekeeping

- **Thank you for the `git add -A` correction.** My owed-list was wrong; `tmp.md` and
  `spec-sbc-phase3-prereqs.md` are committed in `0672f79`. Explicit paths from here on is the right
  call. **I have now `git rm`'d `tmp.md`** — its surviving lead is in `bug-fix-log.md` as A5.1/A5.2,
  so the scratch file has no reason to exist. Still owed as *content*, by me not you: `phase-3.md`,
  `CLAUDE.md`, `destination-side-blocker.md`.
- **A5.1 / A5.2 need a status pass.** Both say "never tested as the cause" — that is now settled: P5
  was the cause, so neither was. A5.1's header also says "code deleted in 003 Stage 4", but
  `SetCopyUnit.scala:137` still exists and still serves the migration copy, so A5.1 is **not** deleted
  and remains a live RTL fact. Correct both headers when you next touch that file.

---

## Amendment 5 — BUGS FIRST. The punch list, with owners. (2026-08-29)

Priority is explicit now: **close the open findings before adding capability.** No step counts as
done while a finding it was supposed to close is still open in the register. "Landed but not closed"
is how P6 spent two tasks marked benign.

### Yours, in this order

| # | bug | when | done when |
|---|---|---|---|
| **P6** | `migFastWantW` reads the partner's search result as its own victim | **now** — first thing in 2b | case 7 stops halting; register row updated to *"never benign — masked by repatriation, exposed by 2a, fixed in 2b"* with the failing-cycle values |
| **A5.1 / A5.2** | status is stale in `bug-fix-log.md` | **now** — a doc edit, do it alongside P6 | both say "never tested as the cause" → settled, P5 was. A5.1's header claims its code is deleted in Stage 4; `SetCopyUnit.scala:137` still exists and still serves the migration copy, so it is **live**, not deleted |
| **P1** | C-channel head-of-line, half-fixed | 2c | re-derived against the way-lock, **not** patched further at `request.ready`. If the way-lock makes the fence unnecessary for victim protection, say so — that is the clean close |
| **P2** | C/X requests cannot find a displaced line | 2d | search armed on both plan branches; flush constraint is an assert |
| **P4** | `inPlace` must survive a `repeat` reload | 2e | assert in place and GATE 4 green |

P3 and P5 are closed. Do not reopen them without evidence.

### The rule that comes out of P6

> **A finding marked "benign by reading" is only benign relative to the code that happens to mask it.
> Delete that code and it becomes live. Re-open every such finding when nearby code is removed.**

Put it in `bug-fix-log.md` beside the two facts you already recorded there. It is the most reusable
thing this task has produced so far.

### Mine, in parallel — the three documents that now contradict the RTL

Not your work, listed so you know it is not forgotten and so you do not trust them meanwhile:

- **`phase-3.md`** — still says serving in place is impossible, and its coherence audit rests on
  "a displaced line has no clients and can never gain one", which 2e deletes
- **`CLAUDE.md`** — the "needs a directory format change" claim (false under pinning, your Stage-0
  check confirmed it), and the stale `+42% / 9.29x` headline
- **`destination-side-blocker.md`** — same false directory-format claim

Until those are rewritten, **`ai-documents/coder/003-serve-in-place/` is the only current
description of this design.** If any of the three contradicts your TASK, the TASK wins — and tell me,
because that means I missed one.

---

## Amendment 6 — 2b ACCEPTED. PROCEED TO 2c. Record P7 first. (2026-08-29)

**2b is accepted as clean.** 7/7 PASS, exit 0, 0 asserts, all four watchdogs quiet, migration count
not collapsed. The shared `armEviction`/`migFastTerms` structure and the deliberate choice to keep
the two cycle-selectors separate (the `probeSet` lesson, applied on purpose this time) are exactly
right. The self-caught partial-edit mistake is a good practice, not a blemish — record it.

### Before starting 2c: record P7

**P7 — the 1f pinning assert (`MSHR.scala:1063`) reads a stale register in the same cycle it is
written.** Same family as the 002 C1 fix and P6: `pairSetReg` updates on `io.directory.valid`, and the
fast-path claim (`migFastWantW` → `dstClaim.valid`) is gated on the same signal — so at a plan-time
claim the assert compares this transaction's destination against the *previous* transaction's
partner. **This is a false-alarm bug in the watchdog, not a pinning violation** — the actual claim
(`migOffer.bits`, sourced live from the AT) is correct. 2b's new resume-cycle timing happens not to
hit it, because `pairSetReg` was already written at the plan cycle for that same transaction — but
the fast path's own exposure is unresolved.

Add to the findings register:

| P7 | 1f assert compares against a stale `pairSetReg` on the fast-path claim cycle — same latch-timing family as 002 C1 and P6 | fix pending, low priority | found via A/B regression against `a148a82`; false alarm only, no data ever wrong |

Also record in `bug-fix-log.md`, and add this line to the pattern note already there next to P6's
rule — it is the same lesson, third instance:

> Every one of this project's three worst bugs (002 C1, P6, P7) has been the same shape: something
> reads a register in the same cycle something else writes it, and gets the old value. Any new signal
> gated on `io.directory.valid` should be checked against this by default, not discovered by accident.

**Do not fix P7 now.** It costs nothing (no data is wrong, it only produces a false alarm under a
timing this project no longer normally exercises), and fixing it correctly means giving it the same
live-value treatment as the 002 C1 fix — that is worth doing carefully, not as a detour mid-stage.
Parked for the first future step that touches this assert.

### Then: proceed to 2c

Build 2c exactly as specified below Amendment 3 §2c — the way-lock (`busyWays`/`freeWays`), the two
domain fixes (`dstOfferOwned` gains `physSet`, `partnerBusy` compares `physSet`), `dstSetConflict`'s
domain resolved against the way-lock, and P1 re-derived against it rather than patched further at
`request.ready`. If the way-lock makes the old set fence unnecessary for victim protection, say so —
that is the clean close TASK §7 already anticipates.

Check: stress test still 7/7, `freeWays.orR` quiet.

---

## Amendment 7 — the run to 2e. Batching, and one thing 2c needs to know. (2026-08-29)

### Why 2c exists at all — the baseline does not have this problem, and that is the point

Worth stating before you build it, because the obvious question is "surely two MSHRs fighting over a
way is a pre-existing bug?" It is not. **The baseline prevents the situation rather than handling
it.** `Scheduler.scala:214-215`:

```scala
val setMatches = Cat(mshrs.map { m => m.io.status.valid && m.io.status.bits.homeSet === request.bits.set }.reverse)
val alloc = !setMatches.orR
```

One MSHR per set, enforced at allocate. A second request to a busy set queues or nests behind the
owner; it never gets its own MSHR. So victim selection is never contested — there is only ever one
party inside a set.

**Serve-in-place is the first thing that breaks the assumption underneath that rule.** The check is
keyed on `homeSet`. An MSHR serving in place has `homeSet = S` but is physically working in row `D`.
The Scheduler sees it as "on S", so a second MSHR is still free to allocate on `D` — and now two
independent MSHRs really are inside one physical row. That has never been possible before.

So 2c is **not** a missed guard being retrofitted. It is new protection for a new situation, and it
should be scoped to exactly that: protect the way being borrowed, change nothing else. Do not
generalise it into a broader locking scheme.

### Batching — do not stop between 2c and 2d

You have been stopping at every gate, which was right while the corruption was open. It is costing
round trips now. For the rest of this stage:

- **Record P7** (Amendment 6) — bookkeeping, no run needed.
- **Build 2c.** Check: stress test still 7/7, `freeWays.orR` quiet. If green, **do not stop** —
- **Build 2d** straight after. It is inert by construction (nothing is client-held yet), so a
  regression here means the arming is wrong, not the design. Check: still 7/7.
- **Then STOP and report**, before 2e.

**2e gets its own go-ahead.** It is the step where `homeSet =/= physSet` for the first time in this
project's history, so it is the first real test of everything Stage 1 built. It deserves a clean
start and your full attention, not a tail-end of a long session.

### One thing about 2e's numbers, so it is not misread later

After 2e, **`SBC_SecHits` changes meaning.** Today it counts a hit that is then erased and refetched.
After 2e it counts a hit that is actually *served*. Do not compare 2b's `secHits=4044` against 2e's
number as if they measure the same thing — say explicitly in the report that the metric changed.

Expect the *shape* to change too: parked lines are no longer destroyed on hit, so the pool stops
draining and the hit rate should climb over a run rather than staying flat. If it does not climb,
that is a finding worth reporting on its own.

### After 2e

**Stage 3 — dirty-capable displaced lines**, the migration-rate unlock. Specced in Amendment 1; it
gets its own instruction once 2e's numbers are in, because what 2e measures may change how it is
scoped. Do not start it speculatively.

---

## Amendment 8 — GO on 2e. One gap found by reading before you build it. (2026-08-30)

2c and 2d accepted. Closing P1 outright (not just re-deriving it) and keeping `partnerBusy` a wait
with the reasoning recorded at the declaration were both the right calls — better than what Amendment
3 asked for. 7/7 stable, migrations flat across all three steps, counters make sense.

**2e is authorised. This is its own commit — the payoff step, not a tail-end of 2c/2d's session.**

Build exactly what Amendment 1 §"Stage 4 — serve in place" and `diagram.md` Part 2/3 already specify,
against the current code:

- the search-result block is `MSHR.scala:1185-1224` now (moved since 2b/2c)
- do **not** call `armEviction` on the branch that will serve — only on the branch that still falls
  through to a fetch (weak permission, or a miss)
- `inPlace := true.B`, `physSet := pairSetReg` (or the equivalent mux), re-point `meta` at
  `(pairSetReg, secondaryWay)`
- `final_meta_writeback.displaced := inPlace`
- **do not set `s_sinval := false.B`** on the serve branch — today that line (`:1212`) is what arms
  the erase. Skipping it is what "keep the parked copy" means in practice, not a separate step.

### ⚠️ Found by reading, not yet built: the way-lock will not protect a served line

`io.status.bits.lockValid := !s_sinval` (`MSHR.scala:402`). The lock's whole window is defined as
*"from the moment the search names the way until the erase retires it"* — i.e. it is keyed to
`s_sinval` being false, which is exactly the register 2e must **not** set false.

**So as written, a served hit gets zero way-lock protection**, for the entire time this MSHR is
reading `(pairSetReg, secondaryWay)` out through SourceD and however long the line stays client-held
afterward. The row's own native MSHR could pick that same way as a victim mid-serve, or a subsequent
native miss could reclaim it while a probe is still outstanding on it — the way-lock's `assert
(freeWays.orR)` proof from 2c does not fail, but the *scope* it was built to prevent (another MSHR
touching the way we are actively using) is left uncovered for the one operation that matters most.

**Decide and implement one of these — do not skip it:**

1. Widen `lockValid` to also cover `inPlace` — e.g. `!s_sinval || inPlace` — so a served line stays
   protected for as long as this MSHR still holds it (until it retires or the way is legitimately
   reclaimed by design, not by accident).
2. Or make the case for why the exposure window is actually safe without it (e.g. if the grant read
   completes in a single, uninterruptible cycle sequence and nothing else can reach that way in that
   window) — but that argument has to survive the same scrutiny partnerBusy's did in 2c, not be
   assumed.

State which one you built and why in the report.

### One more thing to verify while you're in this code: `d_ready`'s timing

`d_ready = w_pprobeack && w_grant` (`MSHR.scala:462`) carries **no wait term** now — the old
`(!repatriating || w_scopy)` term was deleted with repatriation. For a serve-in-place hit, confirm
`inPlace`/`physSet`/`meta` are committed no later than the cycle `d.valid` can first fire, so SourceD
never reads before the MSHR has repointed. If there is a gap, it needs the same kind of gate `w_scopy`
used to provide — say so rather than patch around it silently.

### Then, as already specified

- new pprobe arm in the search-result block for a client-held parked line (§ Stage 4 / diagram Part 2
  step 4) — no such logic exists today because the old invariant guaranteed it was never needed
- `(probeSet, probeTag)` routing goes live for the first time
- relax `Directory.scala:211-213` to `displaced ⇒ clean` only
- **P4** — `inPlace` must survive a `repeat` reload (`MSHR.scala:1080-1126`); clear it only under
  `io.directory.valid`, with an assert

### Gate

GATE 4: `migration_stress_test` 7/7 PASS, 0 asserts; shadow models (G3/G4) clean; **`SBC_SecHits` > 0
and actually returning data** (G5) — the first time in this project a parked line has served anything.

**Remember the metric-meaning change from Amendment 7:** `secHits` before 2e counted "found and
erased"; after 2e it counts "found and served". Do not compare the two numbers as if they measure the
same thing — say explicitly that the metric changed. Report whether the hit rate climbs over a run
now that parked lines are no longer destroyed on hit (expected), and flag it if it does not.

**This is the first time `homeSet =/= physSet` in this project's history.** If the shadow model fires
here, characterise it the way you did for P5 before assuming it's a bug — but also don't assume it
isn't. Either way, this is the run the whole Stage 1 split was built to be tested by.

---

## Amendment 9 — 2e's open failure is DIAGNOSED. It is a design bug, not a mystery. (2026-08-30)

**Read this before you try a fifth hypothesis.** You stopped in the right place, and the
instrumentation you added is what made this findable. But the failure is not in the datapath — it is
in the serve/decline rule itself, and the evidence is already in the log you produced.

### 1. What the log shows

Four lines, 27 apart, at the end of `migration_stress_test.out`:

```
[SBC] SEC-SERVE  set=5 partner=7 way=5 state=3 clients=1 needT=1
[SBC][SCHED] DIR-WRITE set=7 way=5 state=2 displaced=1 tag=557057   <- the serve's own writeback
...
[SBC] SEC-WEAK   set=5 partner=7 state=2
[SBC][SCHED] DIR-WRITE set=7 way=5 state=0 displaced=0 tag=0        <- the SAME entry, erased
```

With `INVALID=0, BRANCH=1, TRUNK=2, TIP=3`:

* the serve found the parked line in **TIP**, granted it to a client with `needT`, and wrote the entry
  back as **TRUNK** — correct, and exactly what serving a writer must do;
* a later `needT` request found that entry in **TRUNK**, failed `willServe`, and took the erase branch,
  which wiped the directory entry **with no probe, no writeback, and no client accounting** while the
  client still held the line.

### 2. The defect

`MSHR.scala:991-993`:

```scala
val willServe = ... && (secondaryEntry.state === TIP || !req_needT) && !request.control
```

**The serve produces exactly the state its own re-entry path refuses.** Serve to a writer, the entry
becomes TRUNK, and the next `needT` access to that line is then *guaranteed* to take
`MSHR.scala:1355-1363`, which does `s_sinval := false` and nothing else.

Consequences, in order:

1. **Inclusion violation.** The L2 forgets a line a client holds exclusively and may have dirtied.
2. **The way is freed** and reallocated, so the client's copy becomes invisible.
3. The observed shadow mismatch at `(5,3)` is the *aftermath*, several transactions downstream.
4. **The parked pool drains by construction** — every served line is destroyed on its next write. Any
   `SBC_SecHits` number measured on this build flatters a system that is demolishing its own hits.

`MSHR.scala:1370` (`C-channel request for a line that is neither resident nor parked`) is the second
alarm for the same thing. The shadow model simply got there first.

**This is the twin of the bug you already found and fixed.** Your 2e item #1 — *"the displaced-reclaim
path must probe a client-held parked line before dropping it"* — is the general statement. It has two
consumers. You patched reclaim and missed erase.

### 3. Two corrections to the 2e report

* **The tag was decoded wrong.** `believed=0x4008bd >> 3` is **524567 (0x80117)**, not `0x80057`. That
  tag *is* in the trace — `DIR-WRITE set=5 way=3 state=2 tag=524567`, an ordinary install that was
  never touched again. "No writer ever put that data in it" and the whole *"a directory write went
  missing"* lead came from searching for a value that never existed. **Decode the shadow word in the
  printf itself** (`tag` and `set` as separate fields) so this cannot recur.
* **Hypothesis 3 was killed on nothing.** `rSrc`/`wSrc` are `mshr_select` (`Scheduler.scala:167-168`)
  and *every* print in this trace says `mshr=0`. Two events 277 cycles apart on slot 0 are two
  different transactions, so "same source id" carries no discriminating power. Worse,
  `c.bits.source` is forced to `0` for ProbeAckData, so a ProbeAck reads back as a false "MSHR 0".
  `shadowSrc` needs a per-transaction id (a wrapping counter latched at allocate) before it can
  eliminate anything.

### 4. What to build — 9a: a secondary hit is a hit. Never erase one.

**Delete the erase branch.** "Found, but too weak to serve" is repatriation-era thinking: it treats a
line we located as garbage. The correct rule is the one the design already uses for a home hit —
**treat a secondary hit exactly like a home hit, in the partner's row.**

Concretely, on `io.directory.bits.secondaryHit`, unconditionally:

* set `inPlace`, re-point `meta` at the parked entry, `meta.hit := true` — as you already do;
* then arm **exactly what the plan block would arm for a home hit carrying that metadata**:
  * clients present and (`req_needT` or `state === TRUNK`) → **pprobe** (you already have this at
    `MSHR.scala:1343-1351`; it simply never gets to run for the TRUNK case);
  * `state === BRANCH && req_needT` → **keep the fetch as an AcquirePerm** rather than cancelling it.
    This is the one case that genuinely cannot be served as-is, and the plan block already knows how
    to arm it (`MSHR.scala:1542`). Serve in place *and* acquire permission — do not erase and refetch.
  * otherwise (TIP, TRUNK, or `!req_needT`) → cancel the fetch, as you do today.

So `willServe` collapses to `secondaryHit && !request.control`, and the fetch-cancel becomes
conditional instead. `secTip` survives only where it is genuinely about permission.

**Why not "make the erase safe" instead:** an erase-then-fetch has to invalidate a line in row `d` and
install a different line in row `s` in one transaction — two rows, one `meta`, one `physSet`. The
probe response for the erased line would land in the wrong row. Always-serve makes that whole class of
problem not exist.

**Re-check these two, because "TIP means we own it" is encoded in more than one place:**
`gotT := secTip` (`MSHR.scala:1320`) and `req_promoteT` (`MSHR.scala:615`). With `meta.hit := true` the
latter reads the post-serve `meta`, so it should already be right — confirm, do not assume.

### 5. What to build — 9b: the consequence you cannot defer

**Serving a parked line to a writer makes parked lines dirty.** That is not optional and it is not
Stage 3's problem any more — it is the direct, immediate consequence of 9a:

* `Directory.scala:256` (`displaced way is dirty`) will fire. It must be relaxed.
* `armEviction`'s `.elsewhen (m.displaced)` branch (`MSHR.scala:1042-1061`) currently **drops** the
  line. Once it can be dirty, it must **Release it at `lineHome`** — `SourceC.bits.homeSet := lineHome`
  is already wired for exactly this (Stage 1).
* `Directory.scala:179` still says a displaced entry is *"clean + client-free by construction"*. Both
  halves are now false. Fix the comment in the same change.

**Do not** try to keep `displaced => clean` by writing the line to memory when the client releases it —
that pays a DRAM write to preserve an invariant we are deliberately retiring.

⚠️ **This is a scope increase to 2e and it is forced, not chosen.** Serve-in-place and
dirty-capable parked lines are not separable. Say so in the report if you disagree, with the
counter-design, before building.

### 6. Nets — these are required, not optional

1. `assert` that **no directory entry is ever written to INVALID while its `clients` mask is non-zero**,
   anywhere in the design. That single net catches this entire class on the cycle it happens instead of
   ~277 cycles downstream. It is the highest-value line in this amendment.
2. `assert` that a Release of a `displaced` entry never forms its address from `physSet`. Risk 2 in the
   plan; now live.
3. Decode `shadowAddr` into `tag=` and `set=` fields in the BankedStore printf (see §3).

### 7. GATE 4 — unchanged, plus one

`migration_stress_test` 7/7, exit 0, 0 asserts; both shadow models clean; `SBC_SecHits > 0 and rising`.
**New:** report the count of secondary hits that were **served** versus **declined-to-AcquirePerm**.
If declines dominate, say so — that is a real result about the workload, not a failure.

### 8. Standing note carried forward

The `SBC_SecHits` metric changes meaning at this step (from "found and erased" to "found and served").
Do not compare the numbers across the change as if they measure the same thing.
