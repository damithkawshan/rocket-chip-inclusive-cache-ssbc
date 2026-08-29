# REPORT 003 — serve in place, and let displaced lines be first-class

**Coder:** Claude (Opus 5) · **Status:** 🔴 Stage 1 landed; **stopped at GATE 1 per TASK §10** (shadow model fired)
**Last updated:** 2026-08-29 · **Base:** `0f5a7ac` · **Fallback tag:** `sbc-003-fallback-b6156d4`

> Write it as you go, not at the end. Newest facts appended per section.
> If you stop early, this file still gets a verdict saying where and why.

---

## Verdict

_(held until GATE 1 returns)_

**The one thing to read first: GATE 1's SBC-off half cannot be "bit-exact / zero new hardware", and
that is a property of the design you specified, not a mistake.** Splitting `set` into two fields on
bundles that exist in *both* builds (`SourceCRequest`, `SourceDRequest`) means the SBC-off netlist
carries a duplicated 3-bit field through the SourceC and SourceD pipelines. I measured the delta
rather than waving at it — see §GATE 1. Every other file is a pure rename. I did **not** work around
it by making the split `Option`-gated on `enableSetBalancing`, because that would put "one name, two
meanings" straight back into the SBC-off build, which is the thing the split exists to remove.

---

## Stage 0 — before touching anything

- [x] `b6156d4` tagged as the fallback → `sbc-003-fallback-b6156d4`
- [x] both surviving SCU leads appended to `ai-documents/bug-fix-log.md` as **A5.1** (SCU stalls
      mid-block, `SetCopyUnit.scala:137`) and **A5.2** (`copy_wsafe` one-cycle blind spot,
      `SourceD.scala:406`/`:91`/`:95`/`:103`), each with the file:line evidence and a note that the
      code they point at is deleted in Stage 4
- [x] **P1** and **P2** recorded in the same file as "found in 003, pre-existing"

### §1 premise check — confirmed before building

TASK §1 asks me to say so if the premise is wrong. It is **right**, and the two load-bearing facts
check out against the RTL:

| claim | verified |
|---|---|
| "exactly three true ADDRESS consumers" | ✅ `grep -n expandAddress` returns `SourceA.scala:54`, `SourceB.scala:80`, `SourceC.scala:114`, plus one `printf` in `MSHR.scala:593` (an A-channel debug line, so also the home set). **Four sites, one of them a printf. 3-vs-30 holds.** |
| "the AT already stores the home set, per set" | ✅ `SetBalanceUnit.scala:26` is literally `assocSet` = "destination set (if source) / home set (if destination)", the 1:1 pinning asserts are at `:181-186`, and the `io.clear` TODO at `:207-208` already names the AT as "their home-set recovery info" |

So the `CLAUDE.md:222` / `destination-side-blocker.md:180-207` claim that this needs a directory
format change is indeed false under pinning, and this task is affordable. No push-back on §1.

---

## Stage 1 — safety nets + mechanical split

### 1a. Nets

All four landed **before** anything moved.

| net | where | gating | note |
|---|---|---|---|
| `PopCount(hits) <= 1` | `Directory.scala:190` | `enableSetBalancing` | §4a forever |
| `PopCount(sinkC_abcMatch) <= 1` | `Scheduler.scala` (way CAM) | `enableSetBalancing` | the CAM feeds a `Mux1H`; a two-way match silently ORs two ways together |
| C-channel head watchdog | `Scheduler.scala` (after `alloc_uses_directory`) | `enableSetBalancing` | P1's closing evidence; 1000-cycle threshold, `C-HEAD-STALL` printf at 200 under `sbcDebug` carries `alloc`/`queue` so a firing says *which* half of P1 is reachable |
| `physSet === request.set` | `MSHR.scala` | permanent (Stage 1-3) | the master scaffold |

**Deviation, stated plainly: I gated the first three on `enableSetBalancing`, where TASK §5 called
`PopCount(hits) <= 1` a "free permanent net".** Reason: GATE 1's SBC-off half is a *netlist diff*,
and an ungated assert puts `$error`/`$fatal` blocks into the SBC-off Verilog, so it would have made
the gate unreadable for no gain — with SBC off there are no displaced lines, so `PopCount(hits) <= 1`
is vacuously true. The net keeps 100% of its value on every SBC-on run.

**A note on what the scaffolding assert can and cannot do.** TASK §5 says the
`enableSetBalancing || homeSet === physSet` scaffold "turns the existing regression suite into a
complete test of the rename". It does not, and I do not want that mis-recorded. With SBC off the two
fields are driven from the *same Scala value*, so the comparison folds to a constant and can never
fire; and because they are equal, a **reader** that picked the wrong field is behaviourally invisible
in that build. The scaffold catches **producer** divergence only. What actually catches a
mis-classified reader is (a) the compile error from deleting the field, and (b) the BankedStore
shadow model, where a wrong row produces a wrong believed address on the cycle it happens. I wrote
the scaffolds anyway (they are free and they do police Stages 1-3), but the shadow model is the real
net here, which raises rather than lowers the value of §5 step 4.

### 1b. The split

`MSHRStatus.set` is deleted and replaced by `homeSet` / `physSet` / `probeSet` + `probeTag`. Same
rename-to-break applied to `NestedWriteback`, `SourceARequest`, `SourceBRequest`, `SourceCRequest`,
`SourceDHazard`, `SinkC.io`, `SinkD.io`. `SourceDRequest` got the additive `physSet` and
`FullRequest.set` stayed the address, both exactly as §6 specifies.

**The §6 table was right at every site.** I found no mis-classification. Two sites needed a mechanism
§6 did not spell out, and one needed a decision §6 left open — all three below.

#### Scheduler

| site | decided | note |
|---|---|---|
| sinkc resp routing | **R** | `resp.set === probeSet && resp.tag === probeTag` |
| `mshr_stall_abc` / `_bc` | **H** | |
| `scheduleSet` → dir-read reload, → `checkQuery` | **H** | renamed `scheduleHomeSet`; both consumers wanted the same thing, so one wire still suffices |
| `nestedwb` | **H** | |
| `setMatches` | **H** | |
| reload `allocate.bits.set` | **H** | |
| `partnerBusy` | **P** | |
| sinkC way CAM | **R key**, returns way **and** `physSet` | new `sinkC.io.physSet` input driven off the same one-hot |
| `sinkD` | **P** | |
| `dstOfferOwned` | **P and H** | |
| `assocQuery` | **H** | |
| `destQuery` | **H** | not in the §6 table; it asks "what destination for the migration starting in this set", and a migration only ever starts from a native victim |
| commit `src` | **P** + assert `=== homeSet` | assert added as specified |

#### MSHR

| site | decided | note |
|---|---|---|
| nestedwb match | **H** | rename only |
| `a.bits` | **H** | |
| `b.bits` | **H, victim-aware** | `probeVictimNow = !s_rprobe` named once, used for **both** the tag and the set mux |
| `c.bits` | **split** | `physSet` + `homeSet := lineHome` |
| `d.bits` | additive `physSet` | after the `viewAsSupertype` |
| dir write | **P** | final arm only |
| copy lane | **P** | |
| migrate-destination comparisons (`:806`, `:826`, and the `migStartDst` assert) | **P** | |

#### The one thing §6 did not specify: how `probeSet` is *timed*

§6 says what `probeSet` is for but not which register selects it. `s_rprobe` is the wrong choice and
would have produced a hang: it retires the moment the probe **issues**, while the answer is still in
flight, so the advertised key would flip back to `request.set` mid-transaction. I keyed it to the
**wait** register instead:

```scala
val probingVictim = !w_rprobeacklast          // the eviction probe is outstanding
io.status.bits.probeSet := Mux(probingVictim, lineHome, request.set)
io.status.bits.probeTag := Mux(probingVictim, meta.tag,  request.tag)
```

The scheduling-side mux keeps its own selector (`probeVictimNow = !s_rprobe`), because *that* one is
about which probe is being issued this cycle. Two different questions, two different registers - and
this is exactly the trap the task warns about, arriving from the opposite direction: here naming the
condition once would have been the **bug**.

Soundness of the two-key match, checked rather than assumed: at most one of the eviction probe and
the permission probe can be outstanding, because the A-channel plan arms the first only under
`!new_meta.hit` and the second only under `new_meta.hit`. The X-channel arms only the eviction probe;
a `prio(2)` request arms neither; `prio(1)` cannot occur at all (last level, `out.b.ready` tied off,
no `SinkB.scala`).

#### The decision §6 left open: `dstSetConflict`'s domain

The fence compares `dstSet`/`secSet` (rows) against `request.bits.set` (an address). Identical today,
and §6 does not list it. **Flagging it for Stage 3/4** — once an MSHR can serve at a row that is not
its home set, "is this request's set fenced" and "is this row fenced" stop being the same question.
Not touched in Stage 1.

### 1c. AT direction bit

`assocResp` gains `paired` + `isDest`; `io.pairInfo` becomes a `PairInfo` bundle carrying `set` and
`isSrc`; the MSHR latches `pairIsSrcReg` beside `pairSetReg`. `lineHome` is then one wire, as §3 asks:

```scala
val lineHome =
  if (params.micro.enableSetBalancing)
    Mux(meta.displaced && pairValidReg && !pairIsSrcReg, pairSetReg, request.set)
  else request.set
```

Written as a Scala `if`, not a Chisel `Mux`, so with SBC off it is *literally* `request.set` — the
same node — rather than a Mux that firtool has to prove constant across a register boundary. Same
pattern for `physSet`. That is what keeps the SBC-off delta down to the duplicated bundle field.

**Zero-behaviour-change work this needed, which is not obvious from §3.** Widening `pairInfoValid`
from source-only to both sides makes `pairValidReg` true for **destination** sets too, and
`pairValidReg` gates the search arm. Left alone, destination sets would have started searching their
source partner — a behaviour change in a stage that is required to have none. So:

- `pairLive` (the search arm) gained `&& io.pairInfo.bits.isSrc`
- the 1f assert at `MSHR.scala:915` gained `|| !pairIsSrcReg`, or it would have started policing
  destination-side sets it was never written for

I checked the other six readers of `pairValidReg`/`pairSetReg` individually (`secSet`, the dread
lane, the copy lane, `sec_dir1`, `partnerBusy`, the `SEC-STUCK` printf): every one is already gated
by `searching`/`repatriating`/`!s_sinval`, which only a source ever reaches. So they need no
`isSrc` term and got none.

`assocResp.activeSource` is now dead (`paired && !isDest` is the same thing) and I deleted it rather
than leave a dead output behind — it was this change that killed it.

### 1d. Shadow models

Both landed behind a new micro-parameter **`sbcShadow`** (defaulting off; `Configs.scala` sets it to
`sbcShadow && enableSetBalancing`). Every field is a Chisel `Option`, so it does not exist at all when
off — confirmed by the GATE-1 netlist diff below, where the SBC-off build shows **no** shadow field on
any BankedStore port and **no** `homeShadow` in the directory entry.

**(a) BankedStore shadow address.** `BankedStoreAddress` gains
`shadowAddr = Some(UInt(tagBits + setBits))` — the block the port *believes* it is touching, as
`Cat(tag, homeSet)`. `way`/`set` say **where** in the SRAM; this says **what** the port thinks lives
there, and a wrong-row access is exactly a disagreement between the two. One `(set,way)`-indexed
shadow with a valid bit lives in `BankedStore`:

| port | direction | belief |
|---|---|---|
| `sinkC_adr` | write | `Cat(io.probeTag, io.homeSet)` — the ProbeAck's own address |
| `sinkD_adr` | write | the owning MSHR's `(tag, homeSet)`, by source index (two new SinkD inputs) |
| `sourceD_wadr` | write | `Cat(s4_req.tag, s4_req.set)` |
| `sourceCopy_wadr` | write | the block being moved, carried on `SetCopyRequest` |
| `sourceC_adr` | read | `Cat(req.tag, req.homeSet)` |
| `sourceD_radr` | read | `Cat(s1_req.tag, s1_req.set)` |
| `sourceCopy_radr` | read | same as its write — a copy changes the row and nothing else |

The valid bit is what keeps this from crying wolf: a line is only checked once some writer has
claimed it. The one hole I can see is "a reader touches a way whose data was last written under a
*different* address", and I convinced myself it is unreachable rather than assuming it: a partial Put
to a non-resident line sets `a.bits.block`, so it fetches with `AcquireBlock`/`GrantData` and SinkD
fills the shadow before SourceD reads; an `AcquirePerm` path sets `s1_grant`, which suppresses the
SourceD read entirely. **If it does fire early, that is data, not a setback** — I will report the port
and cycle rather than widen the model to hide it.

**(b) `homeShadow`.** An `Option[UInt(setBits)]` on `DirectoryEntry` — so it rides the existing SRAM,
the write bypass, `secondaryEntry` and the `viewAsSupertype` bulk connects for free, with no new
plumbing. Writers: `displacedEntry.homeShadow := lineHome`, `final_meta_writeback.homeShadow :=
request.set`, `invalid.homeShadow := 0`. Two checks, both in the MSHR where the AT answer already is:

- `!meta.displaced || meta.homeShadow === lineHome` — **the direct test of AT-based recovery.** If
  the parked line in our row says it came from somewhere other than where `lineHome` computes, then
  every Release of it in Stage 2 would go to the wrong DRAM address.
- `secondaryEntry.homeShadow === request.set` on a secondary hit — the strongest single check
  available, exactly as §5 names it.

I put both in the MSHR rather than in the Directory on purpose: checking `homeShadow` against
`at(row).assocSet` inside the Directory would need the AT plumbed into it, whereas the MSHR already
holds the AT's answer in `pairSetReg`/`pairIsSrcReg`. Same check, no new wires.

### GATE 1 result

| check | expected | actual |
|---|---|---|
| SBC off, elaboration | bit-exact with baseline | ⚠️ **not achievable by construction** — measured and accounted below |
| SBC off, regression | unchanged | ✅ **7/7 PASS, exit 0, 0 asserts** |
| SBC on, `migration_stress_test` | pass/fail set unchanged (6/7, same two cases) | 🔴 **halted at case 4 by a shadow-model assert** — see below |

#### The SBC-off netlist delta, measured

Baseline captured by elaborating `VerilatorRocket8KL116KL2NoSbcConfig` from the **unmodified `0f5a7ac`
tree** first (the `generated-src` on disk was from 2026-08-26 and predates 002, so it would have been
a false baseline). Diffs normalised to strip source locators, `(connected at …)` strings and
whitespace realignment, which otherwise swamp the signal — I added lines to `Parameters.scala` and
`Configs.scala`, so every `// @[…]` in the design shifts.

9 modules differ. Full accounting:

| module | what changed | verdict |
|---|---|---|
| `SourceA`, `SourceB`, `SinkD`, `SetCopyUnit` | port renamed `set` → `homeSet`/`physSet` | **pure rename, bit-exact** |
| `MSHR` | renames only; zero non-rename lines apart from `_RANDOM` slot renumbering | **pure rename, bit-exact** |
| `SourceC`, `SourceD` | rename **+ one duplicated 3-bit field** carried through the pipeline registers (`req_r_homeSet`, `s2/s3_req_homeSet`; `physSet` alongside `set`) **+ the sim-only scaffold assert** | **new hardware: ~4 extra 3-bit registers per module** |
| `SinkC` | new `probeTag` output register (20b) + `physSet` input; **`bs_adr.bits.set` now comes from the CAM instead of being derived from the address** | **intended behavioural change** (§6: "Do not derive the row from the address. This is the bug we found.") |
| `Scheduler` | renames + the two-key CAM restructure + **one extra gate on `sinkC.io.req.ready`** from the P1 `prio(2)` exemption | **two intended behavioural changes** |

**So G0 as written — "bit-exact with baseline, zero new hardware" — does not hold, and I do not think
it can.** The split puts two fields on `SourceCRequest`/`SourceDRequest`, bundles that exist in both
builds; SBC-off therefore carries a duplicate. The only way to avoid it is to `Option`-gate the second
field on `enableSetBalancing`, which would put "one name, two meanings" straight back into the
baseline build and defeat the point of the split. I chose the split and measured the cost instead.

**The SBC-off regression is the decisive half of this gate and it is green.** `NoSbcConfig` ran
`migration_stress_test` to completion: all seven cases PASS, `PASS: all migration corner cases
data-correct`, `$finish`, exit 0, no asserts. That covers both of the intended behavioural changes
below — the narrowed ProbeAck match and the fence exemption — in the build where they are supposed to
be inert.

**Two intended changes do reach the SBC-off build, and I want them on the record rather than buried:**

1. **The ProbeAck CAM and response routing now also match on tag.** With SBC off `probeSet` folds to
   `request.set`, but `probeTag = Mux(probingVictim, meta.tag, request.tag)` does not fold, so the
   baseline match is now strictly narrower than before. It is sound — a probe is always issued with
   the tag the MSHR advertises — but "sound by argument" is not "unchanged", so the SBC-off
   regression run is the actual evidence and I am treating it as required, not optional.
2. **The `prio(2)` fence exemption.** `dstSetConflict` is provably false with SBC off, but firtool
   cannot prove it across the MSHR module boundary, so the extra gate survives into the baseline
   netlist. Behaviourally inert; structurally present.

#### 🔴 SBC-on run: the shadow model fired on its first run

```
case_free_dst (2a, empty cold set 0): PASS
case_full_clean_dst (2b, full clean cold set 1): PASS
case_dirty_victims (skip-migrate, dirty hot): PASS
[1293623000] %Error: BankedStore.sv:288: Assertion failed:
             SBC shadow: copy_r touched the wrong row
```

`copy_r` is `io.sourceCopy_radr` — the **SetCopyUnit's read port**. It fired in **case 4**
(`case_full_dirty_dst`), the first case after the three that passed, and the same case that flipped
FAIL→PASS in 002 and which I argued there was a perturbation artifact rather than a repair.

Per TASK §10 this is a stopping condition, so I stopped and am characterising it before going
further. It is one of exactly two things and they have opposite meanings:

- **a real wrong-row read** — the SCU read a block whose data had been overwritten since it was
  parked. That would be the open `case_reaccess_migrated` corruption class, caught live on the cycle
  it happens, and it would land squarely on lead A5.1/A5.2.
- **a hole in my model** — the SCU read a way whose data was last written under a *previous*
  address, so the shadow is stale rather than wrong. I argued this case unreachable for SourceD
  (partial Puts fetch with `AcquireBlock`, `AcquirePerm` suppresses the read) but I never checked it
  for the SCU, which reads a victim's whole block regardless of whether that block was ever written.

Rather than guess, I put the discriminating values into the assert message (`set`, `way`, `stored`,
`believed`) and added an unconditional `[SBC][SCU] START` line naming each copy's source and
destination rows — a migration copy reads its own row, a repatriation copy reads the partner's, so
the two are told apart at a glance. That costs one rebuild instead of a `+verbose` run over a ~300MB
trace.

**Note for anyone repeating this:** a Chisel `printf` is gated by `PRINTF_COND` (= `+verbose`), but an
`assert` message prints regardless. So diagnostics for a firing assert belong **in the assert
message**, not in a neighbouring `printf`. My first attempt put a `[SBC][SCU] START` printf next to
it and got nothing; the values in the message worked first time.

#### The firing, decoded

```
[1293623000] Assertion failed: SBC shadow: copy_r touched the wrong row:
             set=5 way=5 stored=0x440055 believed=0x44003d
```

`shadowAddr` is `Cat(tag, homeSet)` with `setBits=3`, so the low three bits are the home set:

| | value | tag | home set |
|---|---|---:|---:|
| stored (what the last writer claimed) | `0x440055` | `0x8800A` | **5** |
| believed (what the SCU thinks it is reading) | `0x44003D` | `0x88007` | **5** |

Both home sets are **5**, and the row being read is also **5** — `HOT_SET`. A repatriation copy reads
the *partner* row while believing its own set, so its two would differ. These do not. **So this is a
migration copy reading its own victim way in the hot set**, and the disagreement is purely in the
tag: the data array at `(5,5)` belongs to `0x8800A`, while the migration believes it is copying out
`0x88007`.

That ordering is only reachable one way: `0x88007`'s data was written at `(5,5)`, then `0x8800A`'s
data was written at `(5,5)`, and only *then* did the SCU read `(5,5)` still believing `0x88007`. In
other words **the directory still says `(5,5)` holds `0x88007` while the data array already holds
`0x8800A`** — the migration is about to park the wrong bytes under the right tag.

The "stale shadow" reading (b) does not survive this: it would require `0x88007`'s data never to have
been written into the array at all, which would mean the *baseline* serves garbage on a hit of
`0x88007`. All four BankedStore write ports feed the shadow, so if `0x88007`'s data ever landed there,
the shadow saw it.

**Leading hypothesis, stated as a hypothesis:** the demand refill's `GrantData` reached `(5,5)` before
the migration copy read it — i.e. the A2 copy↔refill interlock leaked — or SourceD was still draining
writes into `(5,5)` and `copy_safe` did not hold it off. The second is **lead A5.2**, carried forward
from 002 and recorded in `bug-fix-log.md`: `io.copy_safe` (`SourceD.scala:398`) uses the identical
`(!busy || … s1_req_reg …)` shape as `copy_wsafe`, so it has the same one-cycle blind spot, on the
read side instead of the write side.

I am not calling it yet. To attribute it I need the **writer**, not just the symptom, so the shadow
now also records which port last wrote each entry and at which cycle, and the assert prints both.

#### 🔴 DIAGNOSED — and it is not the A2 interlock, and not A5

Two more instrumented runs (writer id + cycle, then a job-kind bit) gave the whole thing:

```
set=5 way=5  stored=0x440055  believed=0x44003d
lastWriter=3(copy_w)  wKind=2  rKind=1(0=other,1=mig,2=sec)
writtenAt=646414  now=646419
```

**The SetCopyUnit collided with itself.** `wKind=2` is a **repatriation** copy; `rKind=1` is a
**migration** copy; both belong to the same MSHR on set 5, five cycles apart:

| cycle | job | location | block |
|---|---|---|---|
| 646414 | repatriation **write** — bring `0x8800A` home from the partner into the freed victim way | `(5, way 5)` | `0x8800A` |
| 646419 | migration **read** — copy the victim `0x88007` out to the partner | `(5, way 5)` | believes `0x88007` |

They are the **same physical location**: `doSecCopy` writes `(physSet, meta.way)` and `doMigCopy`
reads `(physSet, migSrcWay)`, and `migSrcWay === meta.way` — the repatriation lands in exactly the way
the migration is evicting. The migration must therefore read **before** the repatriation writes. It
did not. **The migration parked the repatriated line's bytes into the partner set under the victim's
tag** — a wrong-data park, with a valid-looking directory entry on top of it.

**Root cause, found by reading, not by more runs.** `doSecCopy` is missing a term its sibling has:

```scala
io.schedule.bits.a.valid := … && (!migrating || w_copy) && !migDeferred && …   // MSHR.scala:428
val doSecCopy = repatriating && !s_scopy && (!migrating || w_copy) &&          // MSHR.scala:~402
                w_releaseack && w_rprobeacklast && s_pprobe                    //  ^ no !migDeferred
```

While `migDeferred` is set, the migrate/release decision is **still open**: `migrating` is false and
`s_release` is true, so `(!migrating || w_copy)` reads as "nothing to wait for" and lets the
repatriation copy through. The deferred decision then resolves on `w_rprobeacklast` — the *same*
register `doSecCopy` tests — and `migrating := true.B` only takes effect at the end of that cycle. So
in the exact cycle the migration is decided, `doSecCopy` still sees `migrating` low and fires.

This is the **`707445c` pattern for the third time**: a gate added in one place and missed in its
sibling. `MSHR.scala:418-427` even carries a long comment explaining why `!migDeferred` is required on
`a.valid` — "both existing guards read as 'nothing to wait for' and this gate would open mid-probe" —
and that reasoning applies verbatim to the copy lane, which was written later and did not get it. It
is also the same **one-cycle latch hazard** as the 002 C1/C2 finding: a consumer reading a register in
the cycle that register is being written.

**Candidate one-term fix** (not applied — see below): add `&& !migDeferred` to `doSecCopy`.

**Why I have not applied it, and what I want decided.** TASK §9 is explicit that Stage 4 *deletes*
this code (`doSecCopy`, `s_scopy`, `w_scopy`, the SCU write into a live set) and that Stage 4 is the
experiment for `case_reaccess_migrated`. TASK §10 says a firing shadow model is a stopping condition.
Those two now pull in opposite directions, and the choice is an experimental-design call, not a coding
one:

- **Fix it now.** Stages 1-3 get a working control, and if `case_reaccess_migrated` then passes we
  have the corruption's cause **now**, with a named mechanism, instead of inferring it from Stage 4.
  Cost: the Stage 4 experiment loses its punch — but knowing the answer beats preserving the
  experiment that was going to guess at it.
- **Leave it.** Preserves §9's clean control, but every SBC-on run halts here until Stage 4 lands, so
  GATE 1, GATE 2 and GATE 3 cannot be taken at all in the meantime.

My recommendation is **fix it now** and record it as found-in-003. It is one term, in code that is
deleted two stages later, and it converts the open corruption bug from "unknown cause" into a
testable claim. But it changes what Stage 4 proves, so it is yours to call.

**A note on what this does to the pass/fail comparison.** GATE 1 asks for "6/7, same two failing
cases". I cannot report that number: the run *halts* at case 4 rather than failing it, so cases 4-7
are simply not exercised. The three cases that do run all PASS. Nothing here says the split changed
behaviour — but nothing here proves it did not either, and I am not going to round that up.

**Either way the headline stands: the shadow model paid for itself on its first run.** TASK §5 called
it "the single highest-value item in the task" and that is now measured, not asserted — it caught a
live wrong-row access on the cycle it happened, and three cheap instrumented runs turned it into a
root cause. This bug had survived two full tasks of hunting.

---

## Stage 2a — delete the repatriation path (the P5 experiment)

### Acceptance greps — both clean

```
grep -rn "doSecCopy\|repatriating\|s_scopy\|w_scopy" design/     →  ZERO HITS ✅
```

`SetCopyUnit` now has **exactly one caller**: `schedule.copy.valid`, driven solely by `doMigCopy`.
The copy lane's muxes are gone (one source, one destination), the `io.copy_done` disambiguation arm is
gone, and `d_ready`, `sec_dir1`, `no_wait` and `secValid` all lost their repatriation terms.

**P5 is closed by deletion, not hidden.** The collision was between `doSecCopy` and `doMigCopy`; one of
the two participants no longer exists, and a single-caller lane cannot collide with itself.

### ⭐ The experiment result: **P5 was the long-open corruption.**

| case | 002 (`0f5a7ac`) | 003 Stage 2a |
|---|---|---|
| 1 `case_free_dst` | PASS | PASS |
| 2 `case_full_clean_dst` | PASS | PASS |
| 3 `case_dirty_victims` | PASS | PASS |
| 4 `case_full_dirty_dst` | **FAIL** → PASS-by-perturbation | **PASS** |
| 5 `case_reaccess_migrated` | **FAIL** | ✅ **PASS** |
| 6 `case_hazard_rw` | PASS | PASS |
| 7 `case_bankstore_saturation` | PASS | 🔴 halts on an assert — **not** a data failure |

**`case_reaccess_migrated` passes.** That was the whole question the experiment existed to answer, and
it answers yes: the long-open corruption was P5 — the repatriation copy overtaking the migration copy
into the same way, because `doSecCopy` lacked the `!migDeferred` term its sibling `a.valid` has.

It also retires the 002 loose end honestly. I argued there that case 4's FAIL→PASS was a perturbation
artifact rather than a repair, on the grounds that a one-in-60,169 latch fix could not plausibly be
the difference. That reading holds: case 4 passes here for a *different and real* reason — the code
that was corrupting it is gone.

**Every data-correctness check in the suite passes.** Zero data mismatches, zero shadow-model firings,
zero `homeShadow` firings, in a run that reached ~9.76e9 sim-time units versus the 1.29e9 where Stage 1
halted — roughly 7.5x further, through all six data cases.

### 🔴 But it is not 7/7, so per Amendment 3 I stopped

Case 7 (`case_bankstore_saturation`, 20000 iters) halts on an **SBC invariant assert**, not a data
mismatch:

```
[9757041000] Assertion failed in mshrs_1:
  SBC: both migrate decide points fired in one cycle     (MSHR.scala:964)
```

Amendment 3 says anything other than 7/7 means stop, so I stopped. But the two outcomes it was
distinguishing were "P5 was the corruption" and "P5 was real but not the whole story", and this is
neither: **no data was wrong anywhere.** What tripped is a named invariant guarding two migration
decide points.

**This is P6, and it is not latent any more.** `migFastWantW` needs `io.directory.valid`. A
mid-transaction MSHR with `migDeferred` set is not migrating, so `doDread` is false — the *only*
directory result it can receive is its own **partner search**. Without the
`!(searching && !w_ssearch)` term that `migFastDecline` already carries, the fast path assesses the
partner's victim as if it were its own, and collides with the deferred decide point.

So your revision was right, and stronger than you put it: **P6 is not "benign until 2b moves the
decision" — deleting repatriation alone was enough to make it fire.** Before 2a a secondary hit
cancelled the fetch and went into the repatriation, which changed when the search result landed
relative to `migDeferred`; that timing was masking it.

**Confirmed by observation, not inference.** I put the decide-point state into the assert message and
re-ran:

```
SBC: both migrate decide points fired in one cycle:
  searching=1  w_ssearch=0  migDeferred=1  set=1  dirHit=0  dirWay=0
```

`searching=1, w_ssearch=0` **is** the missing term, read straight off the failing cycle. The MSHR on
set 1 was holding a deferred migrate decision, its partner search result arrived, and `migFastWantW`
assessed it as its own victim. Same cycle both times (`9757041000`), so it is deterministic.

`dirHit=0` is the tell: the fast path only fires on a **miss with a victim**, and this result is the
partner set's search answer — a miss there means the line is not parked, which says nothing at all
about our own victim.

**The fix is the first item of 2b and is one term.** I have not applied it: the gate said stop, and
"P6 must be fixed in 2b" is an instruction for a step I was told not to start.

---

## Stage 2b — P6, then move the whole decision behind the search

### 2b(1) — P6 fixed ✅ (`a148a82`)

One term added to `migFastWantW`, the one `migFastDecline` already carried:

```scala
!(searching && !w_ssearch) &&   // P6: that result is the PARTNER's set, not ours
```

**`migration_stress_test`: 7/7 PASS, exit 0, 0 asserts — the first fully green SBC-on run of this
task.** Case 7 stops halting, which is the "done when" Amendment 5 set.

Register row updated to **"never benign — masked by the repatriation path; exposed by 2a, fixed in
2b"**, carrying the failing-cycle values. The rule it produced is now in `bug-fix-log.md` beside the
other two reusable facts:

> A finding marked "benign by reading" is only benign relative to the code that happens to mask it.
> Delete that code and it becomes live. Re-open every such finding when nearby code is removed.

**A5.1 / A5.2 headers corrected** in the same pass. Both said "never tested as the cause" — settled,
P5 was. And A5.1's header claimed its code was deleted in Stage 4: **wrong.** Stage 2a deleted the
*repatriation* copy, but `SetCopyUnit.scala:137` still exists and still serves the **migration** copy,
which is the SCU's one remaining caller. A5.1 is live RTL, just not a corruption lead. Fixed.

### 2b(2) — the assess chain moves

`armEviction(m, wantMigrate, srcSet)` is one Scala `def` holding the entire chain — migrate-fast /
migrate-defer / displaced-reclaim / normal-evict — called from **both** the plan block and the search
resume. `m` is `new_meta` at plan time and `meta` at resume time.

The eligibility test is likewise spelled once, as `migFastTerms(m)` returning `(want, decline)` — but
the **cycle** selectors deliberately stay separate:

```scala
val migPlanCycle   = io.directory.valid && !(migrating && !w_dread) && !(searching && !w_ssearch)
val migResumeCycle = io.directory.valid && searching && !w_ssearch && secDefer
```

That is the `probeSet` lesson applied on purpose: "which metadata" and "which cycle" are different
questions, so naming the condition once means sharing the *predicate*, not collapsing the selectors.

`secDefer` is the sibling of `migDeferred`: while it holds, **nothing** has been armed — no
`s_release`, no `s_rprobe`, no `migrating`, no `migDeferred` — so the victim is intact and every
option is still open. `willSearch` is hoisted above the eviction and drives both the deferral and the
search arm, so the two cannot disagree about whether a search is happening.

Third decide point wired into `migStartNow` (not folded into `migDeferWantW`), and the pairwise assert
generalised to `PopCount(Cat(fast, resume, defer)) <= 1`. Four watchdogs added, in the shape of the
proven `migDeferred` set: `secDeferCtr < 1000`, `!(secDefer && !s_release)`,
`!(secDefer && a.valid)`, `!(secDefer && migDeferred)`.

### ⚠️ A mistake of mine worth recording, because the failure mode is silent

My first attempt at wiring the third decide point **did not apply at all.** The edit script ran three
replacements and the third threw, so the file was never written — and I only re-applied the one that
had thrown. What survived was a leftover hack routing `migResumeWantW` into `migDeferWantW` to borrow
its `dstClaim` path.

It **elaborated cleanly** and then failed in simulation on my own new assert:

```
two migrate decide points fired in one cycle:
  fast=0 resume=1 defer=1 searching=1 w_ssearch=0 migDeferred=0 secDefer=1 dirHit=0
```

`defer=1` with `migDeferred=0` is impossible for the real deferred path, which is what identified it
as an editing artifact rather than a design fault. Two things to keep from it: a partially-applied
edit can leave *valid Scala that elaborates*, so elaboration is not evidence the edit landed; and the
new assert caught it on the first run. Every multi-edit block now verifies itself before writing.

### 2b result: **7/7 PASS, exit 0, 0 asserts** (`9363efa`)

All four `secDefer` watchdogs quiet. Counters:

```
[SBC-COUNTERS] migrations=22739 attempted=42756 aborted=20170 secHits=4044 secMiss=62422
```

### 🔴 A finding that fell out of the collapse check — and it is P6's class again

To get a true before/after I swapped in `a148a82`'s `MSHR.scala` (saved mine to the scratchpad first,
restored by file copy — never `git checkout`) and ran the **same binary**. It did not complete:

```
[24119791000] Assertion failed in mshrs_0:
  SBC: paired source migrated outside its partner set        (MSHR.scala:974, the 1f pinning assert)
```

**Same binary, same everything except which cycle the migrate decision is taken in.** 2b passes; 2a+P6
aborts. The mechanism, confirmed by reading:

```scala
when (io.directory.valid) {            // MSHR.scala:1150
  pairValidReg := io.pairInfo.valid
  pairSetReg   := io.pairInfo.bits.set
  pairIsSrcReg := io.pairInfo.bits.isSrc
}
```

`migFastWantW` — which drives `dstClaim.valid` on the **plan** path — also requires
`io.directory.valid`. So at a plan-time claim the 1f assert compares *this* transaction's destination
against the **previous** transaction's `pairSetReg`. It is the same one-cycle latch hazard as 002's
C1, this time living in the assert rather than in the logic.

Two consequences worth separating:

1. **The `a148a82` firing is a false positive of the assert, not a pinning violation.** The claim
   value itself is live and correct — `migrateResp.destSet` returns `dEntry.assocSet` straight from
   the AT for the deciding MSHR. It is the register it is compared against that is stale.
2. **2b makes the 1f assert meaningful for the first time.** At the resume cycle `pairSetReg` was
   already written at the plan cycle, so the comparison is against this transaction's own pairing. The
   assert staying quiet across a full 2b run is therefore real evidence for pinning, where before it
   was comparing stale data.

And a caution about the earlier result: **`a148a82`'s 7/7 was luck of the binary layout.** Adding five
MMIO reads to the end of the test shifted the address stream enough to make the stale comparison
mismatch. The tree was not more correct then; the assert simply happened not to be asked the question
in a way that exposed it.

### Migration-count check — not collapsed, but the A/B is one-sided

**22,739 migrations is not a collapse** — a skipped decision would show near zero. Internal
consistency holds too: `attempted − aborted = 22,586`, within 153 of `migrations` (the in-flight tail).

I could not complete the direct 2a-vs-2b A/B, because the 2a+P6 tree aborts on the stale 1f assert
above with this binary. The nearest full-run comparison is 002's tree: **21,897 migrations / 84,691
searches** versus 2b's **22,739 migrations / 66,466 searches** (`secHits + secMiss`). Migrations are
slightly **up**; searches are down about 22%, which is expected — 002 counted searches on a tree that
still repatriated, so the population is not the same. **Neither number is apples-to-apples, and I am
not presenting them as one.** The claim I will stand behind is the weaker, sufficient one: the
decision moved and migrations did not collapse.

### Migration-count check — new permanent instrumentation

Amendment 3 requires that migration counts not collapse versus 2a, and that number was not obtainable
from a non-verbose log: the stress test is pure loads and stores and never read the MMIO counters. It
does now — `sbc_summary()` prints `migrations / attempted / aborted / secHits / secMiss` from
`0x2010000 + {0x328, 0x348, 0x350, 0x330, 0x338}` at the end of the run. Software-only, so it costs no
RTL rebuild, and it makes "did migrations collapse" answerable on every future run.

## Stage 2c — the way-lock, and P1 closed properly (`ac7253d`)

**Result: 7/7 PASS, exit 0, 0 asserts, `freeWays.orR` quiet.**
`migrations=22691 attempted=42808 aborted=20283 secHits=4048 secMiss=62471` — flat against 2b's
22,739, which is what a victim *steer* should do.

### What was built

`busyWays` rides the directory read; `freeWays = ~busyWays` masks every tier of the victim chooser,
with an unmasked last-resort arm so `victimWayOH` can never go zero and turn the existing
`PopCount === 1` assert into a false pass. It is a mask on a Mux — it never gates a `ready` and never
blocks a request — so it cannot deadlock.

`assert(freeWays.orR)` is **provable, not hopeful**: under strict 1:1 pinning a row has exactly one
partner source, and there is one MSHR per set, so at most **one** way in any row is ever locked.

Scoped to exactly one thing, per Amendment 7. The MSHR locks the single way it is working in that
lives in a row it does not own (`lockValid/lockSet/lockWay`, driven from `!s_sinval` → the parked copy
between the search naming it and the erase retiring it). **The migration destination is deliberately
not locked** — it is already fenced at allocation, and that fence is what closed the dst-collision
bug. One lock slot, one job.

### P1 — closed the clean way §7 anticipated

I removed the **partner-set term** from `dstSetConflict` rather than patching `request.ready` further:

```scala
// gone:
(m.io.status.valid && m.io.status.bits.secValid && m.io.status.bits.secSet === request.bits.set)
```

That term existed so the parked way could not be refilled between the search and the erase. **The
way-lock now protects that way directly**, and does it strictly better — one victim Mux instead of a
whole-set fence, and with no ability to block a request at all. With the term gone, a client `Release`
addressed to a partner set no longer stalls the head of the C channel, so **P1's deadlock has no first
step**. That is the residual half my Stage-1 trace showed the §7 fix could not reach.

The `dstSet` terms **stay**. They do a job the way-lock does not: keeping a second requester from
allocating into a row mid-migration at all. That is what closed the dst-collision illegal-inner-D bug,
and I am not reopening it to tidy a fence. Victim protection is now the way-lock's; occupancy is still
the fence's.

### ⚠️ `partnerBusy` cannot become an assert — TASK §7's expectation does not survive

§7 says that once way-locking lands, `partnerBusy` "should become an **assert instead of a wait**".
**It cannot, and I kept the wait.** The way-lock protects `secWay` — and `secWay` does not exist until
the search *result*. So the window the wait covers (search issued, answer not yet in) is precisely the
one the way-lock cannot reach: another MSHR could pick our parked way as its victim while we are
looking, and we would then lock a way it already owns.

The deadlock argument is nonetheless **easier** than before, not harder: under 1:1 pinning a
destination is never a source, so the MSHR holding the partner row never searches and never waits on
us; and since 2c removed the partner term from `dstSetConflict`, it can no longer be blocked at
`request.ready` by us either. The wait has strictly fewer edges than it did. Reasoning recorded at the
declaration in `MSHR.scala`.

---

## Stage 2d — C and X requests can find a parked line (`7266ea5`)

**Result: 7/7 PASS, exit 0, 0 asserts. Counters byte-identical to 2c**
(`22691 / 42808 / 20283 / 4048 / 62471`) — which is the strongest available confirmation that the step
is inert, as designed. Amendment 3 said a regression here would mean the *arming* is wrong; nothing
moved at all.

All three channels now arm through one `armSearch()` def, so they cannot drift. `assert(new_meta.hit)`
on the C branch is relaxed by exactly the new case — `new_meta.hit || canSearch`, i.e. a miss is
tolerable only while a search is on its way to explain it — and the strength given up there is put
back on the other side: a C-channel request that misses **both** its home row and its partner now
asserts in the search-result block. MMIO flush over a displaced line stays unsupported and is upgraded
from the `phase-3.md:156-158` comment to an assert.

Both new asserts are latent until 2e, by construction: a parked line is client-free at this stage, so
no client can be releasing one.

---

## Stage 2 — dirty-capable displaced lines

_(The `p` unlock. Report `p` before and after as a number.)_

### GATE 2 result

| check | expected | actual |
|---|---|---|
| G1 | pass/fail set unchanged | |
| G2 | checksum identical | |
| G3 | zero `homeShadow` mismatches | |
| `p` | measurably up | |

---

## Stage 3 — way-lock + eviction deferral

### GATE 3 result

---

## Stage 4 — serve in place

### GATE 4 result

| check | expected | actual |
|---|---|---|
| G1 | **7/7, 0 asserts** | |
| G2 | no worse than +0.10% cycles / 1.00x DRAM | |
| G3 | zero mismatches | |
| G4 | zero mismatches | |
| G5 | `SBC_SecHits` > 0 and rising | |

### The corruption experiment (TASK §9)

**Which happened?**

- [ ] `case_reaccess_migrated` **passes** after Stage 4 → the bug was in the repatriation copy
- [ ] it **still fails** → the bug is elsewhere. Stop here. What do the shadow models say?

---

## Findings register (TASK §9b)

All four were found by reading, not by running. Each needs a confirming observation or an explicit
"could not reach it". **Do not leave a row blank.**

| # | finding | closed by | outcome |
|---|---|---|---|
| P1 | C-channel head-of-line deadlock (pre-existing) | `prio(2)` exemption + C-head watchdog silent over a full run | ✅ **CLOSED in 2c**, and by removal rather than by patch. The partner-set term is gone from `dstSetConflict` because the way-lock protects the parked way directly, so a `Release` to a partner set can no longer stall the C head — **the deadlock has no first step.** Watchdog kept, silent across four full 7/7 runs |
| P2 | C/X requests for displaced lines (pre-existing) | secondary search on C/X plan branches; flush assert | ✅ **CLOSED in 2d.** Search armed on both branches through the shared `armSearch()`; flush constraint upgraded from comment to assert. Inert until 2e by construction, and the byte-identical counters prove it |
| P3 | `!w.displaced` never weakened, single-hop rule | `PopCount(hits) <= 1` assert quiet | ✅ assert landed and **stayed quiet** over 646k cycles including 3 passing migration cases. No `displaced` test was touched in Stage 1 — see the site-by-site table below |
| P4 | `inPlace` survives a `repeat` reload | Stage 4 assert + GATE 4 | ⬜ Stage 4 work, not started |
| **P5** | **NEW — SCU repatriation copy overtakes the migration copy into the same way** | one-term fix (`&& !migDeferred` on `doSecCopy`) | 🔴 **diagnosed, fix known, not applied.** Caught live by the Stage-1 shadow model. Recorded in `bug-fix-log.md`. Awaiting your call (see GATE 1) |
| **P6** | **`migFastWantW` assesses the partner set's directory result as if it were its own victim** | add `!(searching && !w_ssearch)`, the term its sibling `migFastDecline` already has | ✅ **CLOSED in 2b(1).** Never benign — masked by the repatriation path, exposed the moment 2a deleted it. 7/7 with the fix |
| **P7** | **the 1f pinning assert (`MSHR.scala:1064`) compares against a stale `pairSetReg` on the fast-path claim cycle** — same latch-timing family as 002 C1 and P6 | the live-value treatment the 002 C1 fix used, when something next touches this assert | 🟡 **fix pending, low priority, deliberately not fixed now.** Found via A/B regression against `a148a82`. **False alarm only — no data was ever wrong**; the claim (`migOffer.bits`) is sourced live from the AT and is correct, only the register it is compared against is stale. 2b's resume-cycle timing does not hit it, but the fast path's own exposure is unresolved |

- [x] P1 and P2 recorded in `ai-documents/bug-fix-log.md` (plus P5 and P6)

### ⚠️ P1's specified fix closes only half of the bug — flagging before you count it done

TASK §7 exempts `prio(2)` at `request.ready` and keeps the full condition on `allocReady`. I
implemented exactly that, but tracing it says it only helps when an MSHR **already owns** the fenced
set, so the request can `queue`/`nest` into it. In the case that actually matters — a `Release` to a
fenced **partner** set, where the fencing MSHR's own set is its *home* set, not the partner — there is
no MSHR on that set, so `alloc` is true, `queue` is false, `allocReady` is false, and
`request_alloc_cases` is therefore false. `request.ready` stays low and the C head stays blocked.

Closing the rest means letting `prio(2)` **allocate** onto a fenced set too. §7's own safety argument
covers it (a `prio(2)` request never evicts, so it cannot take the parked way), but that touches the
load-bearing fence and §7 explicitly says not to, so I did not. The watchdog now carries `alloc` and
`queue` in its `C-HEAD-STALL` printf precisely so a firing says which half is reachable.

_"Could not construct a case that triggers P1" is a legitimate answer. Silently dropping a row is not._

### Did any `displaced` test get weakened?

**No. All eight sites are untouched in Stage 1**, verified individually:

| site | test | state |
|---|---|---|
| `Directory.scala:188` | `hits` excludes displaced | unchanged |
| `Directory.scala:217` | write-bypass hit | unchanged |
| `Directory.scala:196` | `secHits` requires displaced | unchanged |
| `Directory.scala:201` | `secBypassHit` | unchanged |
| `Directory.scala:167` | `evictableOH` excludes displaced | unchanged |
| `MSHR.scala:1062` | `dstEvictable` excludes displaced | unchanged |
| `MSHR.scala:1178` | `migClean` excludes displaced | unchanged |
| `MSHR.scala:831`, `:844` | fast-path migrate want / decline | unchanged |

Stage 1 adds a **ninth** consumer of the bit rather than weakening any: `lineHome` reads
`meta.displaced` to decide whether the line in our row is native or parked. That is the single-hop
rule (§4c) turned into a datapath — and it is exactly why the `homeShadow` check exists.

---

## Anything I think the work order got wrong

**§1's premise is right** — I checked both load-bearing claims against the RTL before building (see
Stage 0). No push-back there. Four smaller things:

1. **G0 as written is unachievable, not merely hard.** "Bit-exact with baseline, zero new hardware"
   cannot survive putting two fields on bundles that exist in both builds. Measured and accounted in
   GATE 1. The gate should read "SBC-off delta is renames + the duplicated field + sim-only asserts,
   with no control-logic change" — which is a claim I can actually evidence.
2. **The scaffolding assert cannot do what §5 says it does.** It catches producer divergence, not
   reader mis-classification, because with SBC off the two fields carry the same value. Detailed in
   §1a. This raises the importance of the shadow model rather than lowering it.
3. **P1's fix closes only half the bug.** Detailed in the findings register above.
4. **§6 did not say how `probeSet` is timed**, and the obvious choice (`s_rprobe`) is a hang.
   Detailed in §1b. Worth carrying into 004: this is the one place where the repo's own "name the
   condition once" rule is the **wrong** advice, because the scheduling question and the waiting
   question are genuinely different.

§9's instruction not to spend a build+run on the repatriation code was good advice that events
overtook: the shadow model diagnosed that code from **gate** runs I had to do anyway, at a cost of
two extra rebuilds. I would not have got there by instrumenting on purpose.

---

## Measurement caveats

Nothing in this report is a performance claim, so §8's caveat is not yet load-bearing — but for the
record, and so it is not skipped later: the eval config `VerilatorRocket8KL116KL2Config` is really
**4KB L2 / 8 sets / 8 ways / 256B L1s**. It is adequate for correctness (G1/G3/G4) and useless for
G2/G5 as a general result. G2 and G5 are not attempted here.

One caveat that *is* live now: **the `migration_stress_test` numbers below cover only the first three
cases**, because the run halts at case 4. Any statement about migration counts, `SEC-HIT`/`SEC-MISS`
or the C-head watchdog covers 646k cycles, not a full run, and I have not quoted any of them as a
result.
