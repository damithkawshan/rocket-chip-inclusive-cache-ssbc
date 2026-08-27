# Spec — Phase 3 prerequisite: pinned 1:1 association (+ shared building blocks for the swap)

**Author:** thinker/eval. **Implementer:** coder. **Status:** ready to implement.
**Working order, traps and how to run:** [handover-phase3-part1.md](handover-phase3-part1.md) — read that first.
**Supersedes:** the detect-only "secondary-search detector" spec (deleted) — Phase 3 pivoted to the
always-use design, see [phase-3.md](phase-3.md).

**Revision 2026-08-24 — Part 1 rewritten.** The original Part 1 (2026-07-05) was written before late
destination binding (`f40fbd4`) and probe-then-migrate (`cbb3837`). It assumed the destination is
selected per source set. It no longer is. Applying it unchanged would have sent most migrations to
another set's partner. Full picture with diagrams:
[diagram-stale-destination-query.md](diagram-stale-destination-query.md). The old text is preserved
in the appendix at the bottom.

**Revision 2026-07-05:** the "flush-sees-partner" fix was **dropped** — this platform never supports /
uses the MMIO flush registers (`Flush64`/`Flush32`). The hole cannot be triggered if flush is never
used. Documented platform constraint, see the note near the bottom.

---

## What this spec builds, in plain English

1. **Fix — Pinning (the one correctness prerequisite):** all of a source set's parked lines must live
   in exactly ONE partner set, so a search always knows where to look. Today each migration takes
   whatever cold set the DSS is offering, and the association table records only the last one — lines
   scatter, a Phase-3 search looks in the wrong set, refills from memory, and a duplicate copy is
   created that can later go stale.
2. **Fix — the destination query must follow the migrant.** Pinning is meaningless if the question
   "who is S's partner?" is asked on behalf of the wrong set. It currently is. See Part 1a.
3. **Two small shared building blocks** the swap spec will consume next: the directory's
   displaced-tag match (`secondarySearch`) and the MSHR's partner-info latch (`pairInfo`).
4. **Fix — a live Phase-2 defect found on the way.** The migration destination probe currently feeds
   the saturation counters, so every probe makes the destination set look *hotter*. Same flag that
   Phase 3 needs fixes it. See the finding note in Part 2a(e).

Everything is gated by `enableSetBalancing` (baseline bit-exact when off).

---

## Background — why Part 1 changed

Read this before touching the code; the rest of Part 1 will not make sense otherwise.

- **Phase 2 asks:** "give me any cold set." The answer is a pure DSS output. It never reads the
  query set — see [SetBalanceUnit.scala:130-133](../design/craft/inclusivecache/src/SetBalanceUnit.scala#L130-L133).
- **Phase 3 asks:** "give me set S's partner." The answer depends entirely on who asked.
- **Today the question is asked on behalf of the request at the head of the queue**
  ([Scheduler.scala:487-488](../design/craft/inclusivecache/src/Scheduler.scala#L487-L488)), and the
  answer is broadcast on a single offer wire to every MSHR
  ([Scheduler.scala:297-298](../design/craft/inclusivecache/src/Scheduler.scala#L297-L298)).
- Those were the same set in Phase 2, because a migration decided at allocate.
  **Probe-then-migrate pulled them apart** — the deferred path decides many cycles later
  ([MSHR.scala:699-700](../design/craft/inclusivecache/src/MSHR.scala#L699-L700)), by which time the
  head has moved on. That deferred path is now the majority path.
- Harmless today (the answer ignores the asker). **Fatal the moment Part 1b lands**, because then the
  answer starts reading the asker.

---

## Part 1 — Pinned 1:1 association

### Rules

- A source already paired → its migrations go to its partner. Always. The DSS is not consulted, and
  the partner's coldness is deliberately ignored (paper-confirmed behavior).
- A source not yet paired → consult the DSS, but the pick must be a set that is in **no** pairing.
  The first commit creates the pairing.
- A set currently serving as someone's **destination** must never act as a source (one entry per set,
  strict 1:1).
- A partner that is full or busy → **decline and skip** this migration, plain eviction instead. Never
  re-pick, never stall. (Already the approved v1 policy; see phase-3.md.)
- Pairings dissolve only at teardown (Step 4, later — nothing to build here).

---

### 1a. Split the two questions, and key each to the right set

**Revised 2026-08-24 after coder review.** The first version of 1a muxed a single `migrateQuery`
between the queue head and the migrant. That is wrong on the fast path — see the correction note at
the end of this section. Do not implement the muxed version.

`migrateResp` currently bundles **two different questions** that need **two different keys**:

| Question | Field | Keyed to | Consumed |
|---|---|---|---|
| Is this set hot enough to migrate? | `migrate` | the **allocating** set | latched as advice at allocate |
| Where exactly does this migration go? | `destOk` / `destSet` | the **deciding** MSHR's set | many cycles later |

Phase 2 could merge them because the second answer was source-independent (pure DSS). Phase 3 cannot.

**Do not mux one query between the two.** Advice computed from a retargeted set would let an MSHR
latch advice meant for a different set — producing a migration from a set that is not hot, hence a
**spurious pairing** that pins two sets out of the pool until teardown. That is worse than a wasted
migration. Split the ports instead.

#### The advice query — unchanged

```scala
sbu.io.migrateQuery.valid := request.valid
sbu.io.migrateQuery.bits  := request.bits.set
```

#### The destination query — new port, keyed to whoever is deciding

**Note an existing duplication first:** `anyMigrating`
([:218-219](../design/craft/inclusivecache/src/Scheduler.scala#L218-L219)) and `migBusy`
([:293-294](../design/craft/inclusivecache/src/Scheduler.scala#L293-L294)) are already the *same*
per-MSHR expression. Define it once at the `anyMigrating` site:

```scala
// SBC Phase 3: one migration per bank, so this is one-hot.
val migrantOH    = VecInit(mshrs.map(m => m.io.status.valid &&
                                          (m.io.status.bits.dstValid || m.io.status.bits.migPending))).asUInt
val anyMigrating = migrantOH.orR
assert (PopCount(migrantOH) <= 1.U, "SBC: more than one migration in flight")
```

Delete the local `migBusy` at `:293` and use `migrantOH` in the offer fanout at `:297` — identical
expression, so this is a rename, not a behaviour change.

Then, **after `directoryFanout` is defined** (it is declared at
[:398](../design/craft/inclusivecache/src/Scheduler.scala#L398), so place this near the `migrateQuery`
drive at `:487`):

```scala
// Who takes a destination this cycle: a migration already in flight (deferred path), else the MSHR
// whose directory result lands now (fast path). Both selects are registered one-hot.
val decidingOH = Mux(anyMigrating, migrantOH, directoryFanout.asUInt)
sbu.io.destQuery.valid := decidingOH.orR
sbu.io.destQuery.bits  := Mux1H(decidingOH, mshrs.map(_.io.status.bits.set))
```

#### Why the two-term priority is exact

| Deciding path | Selected by | Why it is the right MSHR |
|---|---|---|
| Deferred (post-probe) | `migrantOH` | `migDeferred` sets `status.migPending` ([MSHR.scala:270](../design/craft/inclusivecache/src/MSHR.scala#L270)), so the MSHR is already flagged when it decides |
| Fast (victim already client-free) | `directoryFanout` | The decision fires on `io.directory.valid` ([MSHR.scala:719](../design/craft/inclusivecache/src/MSHR.scala#L719)), which *is* `directoryFanout(i)` |

The priority order is safe: while a migration is in flight, a second MSHR's offer is already masked
away per-MSHR at `:297`, so it cannot claim anyway.

**Consumption is aligned by construction** — a claim can only happen in a cycle where that MSHR is
the one `decidingOH` selected. Assert it, so it stays true:

```scala
val claimOH = VecInit(mshrs.map(_.io.dstClaim.valid)).asUInt
assert ((claimOH & ~decidingOH) === 0.U,
        "SBC: destination claimed by an MSHR the destination query was not made for")
```

**Loop freedom:** every term here is a register or register-derived — `status.*` are registers,
`directoryFanout` is `RegNext` ([:398](../design/craft/inclusivecache/src/Scheduler.scala#L398)).
Do not fold `io.allocate.bits.*` or `request.ready` into it. See the note at
[Scheduler.scala:505](../design/craft/inclusivecache/src/Scheduler.scala#L505).

#### `preferEvictable` gets simpler

With the split, `migrate` already answers "could this set migrate somewhere" for the allocating set
(see 1b), so the 2.5b `&& dstOfferValid` term at
[:352](../design/craft/inclusivecache/src/Scheduler.scala#L352) becomes wrong — it now refers to some
*other* MSHR's destination. Drop it:

```scala
directory.io.read.bits.preferEvictable := (alloc_uses_directory && adviceMigrate) ||
                                          (mshr_uses_directory_for_dread && schedule.dread.bits.preferEvictable)
```

This is a hint only; correctness never depends on it.

---

#### Correction note — why the first version of 1a was wrong

Recorded because it is the same class of mistake as the bug this spec exists to fix.

The original 1a claimed: *"no migration in flight → `request.bits.set` is safe, because the only MSHR
that can start one is the one allocating right now."* That is false for the **fast path**:

- `status.dstValid` comes from `migrating`, a `RegInit`
  ([MSHR.scala:194](../design/craft/inclusivecache/src/MSHR.scala#L194)) — it is only high from the
  cycle *after* the decision
- `status.migPending` is `migDeferred`, the deferred path only
  ([MSHR.scala:270](../design/craft/inclusivecache/src/MSHR.scala#L270))
- so in the exact cycle a fast-path MSHR takes its destination, `migrantOH` is **all zero**
- and the fast path decides on `io.directory.valid`, which is 2 cycles after allocate — by then the
  queue head has moved on

Result: fast-path migrations would have parked in another set's partner. The same Diagram C bug, on
the other path. Found by the coder during review, by checking when each status bit actually rises
rather than trusting the prose.

### 1b. `SetBalanceUnit.scala` — two answers, two keys

New IO port next to `migrateQuery`:

```scala
// SBC Phase 3: the destination question, keyed to the MSHR that is deciding right now.
val destQuery = Flipped(Valid(UInt(params.setBits.W)))
```

Replace lines 128-133 (`val qSet` through `destSet`), and refresh the stale comment above them at
124-127 — it still says "the Scheduler queries with the allocating set", which is only half true now:

```scala
// Advice: is the ALLOCATING set hot, and could it migrate somewhere at all.
val qSet      = io.migrateQuery.bits
val qEntry    = at(qSet)
val dssPick   = dss.io.coldestSet
// Fresh pairing only: the DSS pick must be genuinely cold AND not already in a pairing (1:1).
val dssOK     = dss.io.coldestValid && (dss.io.coldestLevel < tLo) && !at(dssPick).valid
val hotOK     = (params.micro.sbcAutoMigrate.B || armed(qSet)) && (sat(qSet) >= tHi)
io.migrateResp.migrate := hotOK && !(qEntry.valid && qEntry.sd) &&
                          Mux(qEntry.valid && !qEntry.sd, true.B, dssOK)

// Destination: where does the DECIDING MSHR's migration actually go.
val dSet      = io.destQuery.bits
val dEntry    = at(dSet)
val dIsSource = dEntry.valid && !dEntry.sd   // paired already -> pinned to its partner
val dIsDest   = dEntry.valid &&  dEntry.sd   // someone's destination -> must not source
io.migrateResp.destOk  := !dIsDest && Mux(dIsSource, true.B, dssOK)
io.migrateResp.destSet := Mux(dIsSource, dEntry.assocSet, dssPick)
```

Three things to be deliberate about:

- **A pinned source gets no coldness test** — `destOk` is unconditionally true for it. The partner is
  the partner regardless of temperature. This is the paper's behaviour, not an oversight.
- **`!at(dssPick).valid`** is what enforces 1:1 on the destination side.
- **`!dIsDest` / `!qEntry.sd`** stop a set being a source and a destination simultaneously.

`migrate` no longer ANDs in `destOk` — they answer different questions about different sets now. The
Scheduler already ANDs the two independently (`adviceMigrate` and `dstOfferValid`).

A full partner is *not* handled here — it falls out downstream at the existing dst-full abort
([MSHR.scala:888-893](../design/craft/inclusivecache/src/MSHR.scala#L888-L893)), which is the
decline-and-skip fallback and is already counted.

#### AT read ports — count them, and the Fmax escape hatch

After this spec the AT has four wide reads: `at(qSet)`, `at(dSet)`, `at(dssPick)`, `at(tapSet)`.
`assocQuery` (Part 2b) is also driven from `request.bits.set`, the same index as `qSet`, so it does
not add a fifth.

At 1024 sets on FPGA that is four ~1024:1 muxes over a ~12-bit entry. Expected to pass, but if Fmax
complains, **demote `at(dssPick)` first**: 1e already keeps paired sets out of the DSS entirely
(remove-on-commit plus the update gate), so the live `!at(dssPick).valid` guard is belt-and-braces.
Turn it into a sim-only assert and drop the mux. Do this only if measurements demand it.

---

### 1c. `Scheduler.scala` — "partner is busy": no new code

The existing filter at
[:509-511](../design/craft/inclusivecache/src/Scheduler.scala#L509-L511) already does the right job:

```scala
val dstOfferOwned = mshrs.map { m => m.io.status.valid && m.io.status.bits.set === coldDst }.reduce(_ || _)
dstOfferValid := sbu.io.migrateResp.destOk && !dstOfferOwned
```

With 1b, `coldDst` now carries the **pinned partner** instead of the DSS pick, so this same line
becomes "is my partner busy right now?" — and if it is, the offer drops and the MSHR falls back to a
plain eviction. That is decline-and-skip, reusing a proven path. Leave it alone.

⚠️ `sbcForceDstSet` overrides `coldDst` ([:493-494](../design/craft/inclusivecache/src/Scheduler.scala#L493-L494)).
Under pinning that forcibly breaks the 1:1 invariant, so the asserts in 1d **must** be suppressed when
`sbcForceDstSet >= 0` (Scala `if`, zero hardware). Keep the knob — it is the only way to exercise the
displaced-reclaim tier.

---

### 1d. `SetBalanceUnit.scala` — commit asserts

The AT writes stay as-is (they become idempotent re-writes for a pinned source). Add asserts inside
the existing commit block at [:144](../design/craft/inclusivecache/src/SetBalanceUnit.scala#L144) so
any scatter bug dies loudly in sim:

```scala
if (params.micro.sbcForceDstSet < 0) {   // the force knob deliberately breaks 1:1
  assert(!at(io.commit.bits.src).valid ||
         (!at(io.commit.bits.src).sd && at(io.commit.bits.src).assocSet === io.commit.bits.dst),
         "SBC: commit would re-pair an already-paired source (pinning broken)")
  assert(!at(io.commit.bits.dst).valid ||
         (at(io.commit.bits.dst).sd && at(io.commit.bits.dst).assocSet === io.commit.bits.src),
         "SBC: commit targets a destination already in another pairing (1:1 broken)")
}
```

---

### 1e. `DSS.scala` — keep paired sets out of the candidate list

Without this, a paired set can sit at "coldest" forever and block every new pairing (`dssOK` stays
false — the guard is correct but progress stalls).

**(a)** New remove port in the IO bundle at [:22-26](../design/craft/inclusivecache/src/DSS.scala#L22-L26):

```scala
val remove = Flipped(Valid(UInt(params.setBits.W)))
```

and after the `io.clear` block at [:65-67](../design/craft/inclusivecache/src/DSS.scala#L65-L67), so a
same-cycle update loses to the removal:

```scala
when (io.remove.valid) {
  (0 until d).foreach { i => when (valid(i) && setIdx(i) === io.remove.bits) { valid(i) := false.B } }
}
```

**(b)** `SetBalanceUnit.scala` — drive it on commit, and stop paired sets re-entering via the tap:

```scala
dss.io.remove.valid := io.commit.valid && io.commit.bits.kind === SBCCommitKind.MIGRATE
dss.io.remove.bits  := io.commit.bits.dst
// update gate (was: dss.io.update.valid := io.dirTap.valid)
dss.io.update.valid := io.dirTap.valid && !at(tapSet).valid
```

Teardown later clears `at` → the set re-enters the DSS naturally on its next tap. Nothing to do now.

---

### 1f. `MSHR.scala` — the self-policing assert

Once `pairInfo` is latched (Part 2b), the MSHR can check its own destination against its own pairing.
This converts a silent, thousands-of-cycles-later corruption into an immediate sim failure:

```scala
if (params.micro.enableSetBalancing && params.micro.sbcForceDstSet < 0) {
  assert(!io.dstClaim.valid || !pairValidReg || io.dstClaim.bits === pairSetReg,
         "SBC: paired source migrated outside its partner set")
}
```

---

## Part 2 — Shared building blocks for the swap (build now, consumed by the next spec)

### 2a. Directory "secondary search" (tag-match on displaced ways)

The swap will ask the directory: *"does set D hold a DISPLACED way with tag T?"*

**`Directory.scala`:**

**(a)** `DirectoryRead` — new request flag, next to `preferEvictable` at
[:64](../design/craft/inclusivecache/src/Directory.scala#L64):

```scala
// SBC Phase 3: match a DISPLACED way by tag — the mirror of the normal hit, which excludes them.
val secondarySearch = Bool()
```

**(b)** `DirectoryResult` at [:67-71](../design/craft/inclusivecache/src/Directory.scala#L67-L71) — new outputs:

```scala
val secondaryHit = Bool()                 // a displaced way matched `tag`
val secondaryWay = UInt(params.wayBits.W) // its way
```

**(c)** Pipeline the flag to result alignment, next to `preferInvalid` at
[:131](../design/craft/inclusivecache/src/Directory.scala#L131):

```scala
val secondarySearch = params.dirReg(RegEnable(io.read.bits.secondarySearch, ren), ren1)
```

**(d)** Compute the displaced match next to `hits` at
[:172-175](../design/craft/inclusivecache/src/Directory.scala#L172-L175). Note `displaced` is
**included** here and INVALID excluded — the exact mirror of `hits`:

```scala
val secHitsOH = Cat(ways.zipWithIndex.map { case (w, i) =>
  w.tag === tag && w.state =/= INVALID && w.displaced && (!setQuash || i.U =/= bypass.way)
}.reverse)
io.result.bits.secondaryHit := secHitsOH.orR
io.result.bits.secondaryWay := OHToUInt(secHitsOH)
```

**(e) THE TRAP — the dread lane must not be observed, and must not tag-match.** This is one flag with
two effects, and **it fixes a pre-existing Phase-2 defect at the same time.** See the finding note
below before implementing.

`DirectoryRead` — new flag next to `secondarySearch`:

```scala
// SBC: this read is cache-internal machinery (migrate probe / partner search), not a demand access.
// It must not tag-match and must not reach the observation tap.
val internalRead = Bool()
```

Pipeline it beside `secondarySearch` at [:131](../design/craft/inclusivecache/src/Directory.scala#L131),
then use it in two places:

```scala
// 1. suppress the array tag match, at :172
val hits = Cat(ways.zipWithIndex.map { case (w, i) =>
  !internalRead && w.tag === tag && w.state =/= INVALID && !w.displaced && (!setQuash || i.U =/= bypass.way)
}.reverse)

// 2. suppress the same-cycle write bypass tag match, at :169.
//    wayMatch stays live — forwarding fresh metadata for the victim way is what the probe wants.
val tagMatch = !internalRead && bypass.data.tag === tag

// 3. suppress the saturation tap, at :184
io.tap.valid := ren2 && !internalRead
```

Gating `tagMatch` rather than only `hits` matters: `tagMatch` has three consumers — `io.result.bits.hit`,
the metadata mux, and the way mux ([:178-180](../design/craft/inclusivecache/src/Directory.scala#L178-L180)).
Leaving it live would let a same-cycle directory write with tag 0 make the probe report a hit and
return the bypassed way, which is the exact defect the flag exists to kill.
*(Found by the coder during review, 2026-08-24.)*

`Scheduler.scala` — set it for every dread read, false everywhere else:

```scala
directory.io.read.bits.internalRead := mshr_uses_directory_for_dread
```

Baseline reads leave it false, so `hits` and the tap are bit-identical when SBC is off.

---

#### ⚠️ Finding — this is not new Phase-3 work, it is a live Phase-2 defect

Found while checking whether the dread lane was ready to reuse. Both effects are already happening
today, on every migration destination probe.

**Defect 1 — the destination probe heats the destination set.**
`io.tap.valid := ren2` at [Directory.scala:184](../design/craft/inclusivecache/src/Directory.scala#L184)
is ungated, and `directory.io.read.valid` includes `mshr_uses_directory_for_dread`
([Scheduler.scala:334](../design/craft/inclusivecache/src/Scheduler.scala#L334)). So every destination
probe taps the SBU with `set = destination`, `hit = false` — and
[SetBalanceUnit.scala:102](../design/craft/inclusivecache/src/SetBalanceUnit.scala#L102) counts a miss
as **+1 saturation**.

The loop is self-defeating: pick the coldest set → probe it → make it look hotter → it leaves the DSS
cold list and `destOk` starts failing. One phantom miss per migration that reaches the probe.
**Unmeasured, but a plausible contributor to the open ABORT-DST / low-yield picture.** Worth a tally
before and after.

**Defect 2 — a tag-0 collision overrides the victim choice.**
The probe sends `tag := 0.U` ([MSHR.scala:332](../design/craft/inclusivecache/src/MSHR.scala#L332)),
and the directory tag-matches every read. If the destination set happens to hold a valid,
non-displaced way with tag 0, `hit` goes high and
[Directory.scala:178-180](../design/craft/inclusivecache/src/Directory.scala#L178-L180) returns the
**hit way and its metadata** instead of the `preferInvalid` / `preferEvictable` victim.

The MSHR still safety-checks what comes back (`dstFree || dstEvictable`), so there is **no correctness
bug** — but it can overwrite a live clean line when a free way existed. Perf-only and rare.

Note that **no tag value avoids defect 2** — `0` and `request.tag` are equally arbitrary against a
different set. The read simply should not tag-match, which is why `internalRead` is the fix rather
than a better tag constant.

---

**(f)** `Scheduler.scala` — route the dread victim hints from the MSHR's bundle so a secondary search
can turn them off, at [:339](../design/craft/inclusivecache/src/Scheduler.scala#L339) and
[:352](../design/craft/inclusivecache/src/Scheduler.scala#L352):

```scala
// was: directory.io.read.bits.preferInvalid := mshr_uses_directory_for_dread
directory.io.read.bits.preferInvalid   := mshr_uses_directory_for_dread && schedule.dread.bits.preferInvalid
directory.io.read.bits.secondarySearch := mshr_uses_directory_for_dread && schedule.dread.bits.secondarySearch
// preferEvictable: keep the existing alloc-side term, qualify the dread side the same way
directory.io.read.bits.preferEvictable := (alloc_uses_directory && adviceMigrate && dstOfferValid) ||
                                          (mshr_uses_directory_for_dread && schedule.dread.bits.preferEvictable)
```

Give `secondarySearch` and `internalRead` a `false.B` default on the alloc-side read too.

---

**(g) The tag field, for the swap.** For the **secondary search** the correct tag *is* `request.tag`:
a displaced line keeps its **source** set's tag, so matching the request's tag against the partner's
ways is exactly the question being asked. The migrate probe keeps sending 0, which is now harmless
because `internalRead` suppresses the comparison.

Set the new fields on the MSHR's existing dread bundle at
[:331-334](../design/craft/inclusivecache/src/MSHR.scala#L331-L334):

```scala
io.schedule.bits.dread.bits.internalRead    := true.B    // both dread uses are internal
io.schedule.bits.dread.bits.secondarySearch := false.B   // migrate probe; the swap will drive this
// NOTE: tag is 0 for the migrate probe. The swap must send request.tag on a secondary search.
```

Baseline: `secondarySearch` and `internalRead` are false on every path when SBC is off →
`secondaryHit` is constant 0, `hits` and the tap unchanged — bit-exact.


---

### 2b. MSHR partner-info latch (`pairInfo`) — mirror the `migAdvice` pattern

The swap needs each allocating MSHR to know "is my set a paired source, and who is the partner?"

This is the **lookup side**, and it is deliberately built differently from the destination side:
staleness here is harmless. A search in the wrong set finds nothing and falls back to a normal memory
fetch, which is always correct. Latching once at allocate is therefore fine, and it avoids one wide AT
mux per MSHR near the dir-read path.

**`Scheduler.scala`:** drive the currently tied-off AT query
([:523-524](../design/craft/inclusivecache/src/Scheduler.scala#L523-L524)) with the allocating
request's set, and fan the answer to all MSHRs exactly like `migAdvice`:

```scala
// REVISED: only query on fresh allocate, not on reload. The reload path updates an existing MSHR's
// allocate.bits.set to its own prior set, which is not request.bits.set. Gate to alloc
// (line 188: !setMatches.orR, true only when set has no MSHR yet).
sbu.io.assocQuery.valid := request.valid && alloc
sbu.io.assocQuery.bits  := request.bits.set
// next to the migAdvice fanout at :296
m.io.pairInfo.valid := sbu.io.assocResp.activeSource   // this set is a paired source
m.io.pairInfo.bits  := sbu.io.assocResp.assocSet       // its partner
```

With this gate, `pairInfo.valid` only pulses on fresh allocations. On a reload (alloc=false)
the latch stays stable, holding the pair that was set when this MSHR was fresh-allocated to this set.
*(Found by the coder during review, 2026-08-24.)*

**`MSHR.scala`:** new IO next to `migAdvice` at
[:120](../design/craft/inclusivecache/src/MSHR.scala#L120), and a latch next to the `migAdviceValidReg`
latch at [:860](../design/craft/inclusivecache/src/MSHR.scala#L860):

```scala
val pairInfo = Flipped(Valid(UInt(params.setBits.W)))

// state, next to migAdviceValidReg at :207
val pairValidReg = RegInit(false.B)
val pairSetReg   = Reg(UInt(params.setBits.W))

// in the allocate block at :860, beside the migAdvice latch
pairValidReg := io.pairInfo.valid && !io.allocate.bits.repeat
pairSetReg   := io.pairInfo.bits
```

Consumed by the 1f assert now; the swap spec consumes it properly later.

---

## Platform constraint to document (same change)

Add to `CLAUDE.md` (MMIO section) and keep in [phase-3.md](phase-3.md):

> **MMIO flush (`Flush64`/`Flush32`) is unsupported on this platform and must not be used while
> `enableSetBalancing` is on.** A flush only searches the home set; a displaced copy in the partner
> set would survive it. Since flush is never used here, this is a documented constraint, not a bug.
> (Optional cheap guard: `assert(!request.control)` under `enableSetBalancing` in the MSHR, so any
> accidental flush use dies loudly in sim.)

---

## Verification checklist

1. **Elaborates** both ways. With `enableSetBalancing=false` the baseline is bit-exact:
   `secondarySearch` never set, tap unchanged, no new state driven.
2. **Query retargeting (sim, `sbcDebug`)** — the check that proves 1a. In the `MIG-START` printf,
   `srcSet` and the destination must belong to the same pairing on the **deferred** path, not just the
   fast path. Concretely: no `MIG-START` may report a `dstSet` that another set is already paired to.
3. **Pinning (sim, `sbcDebug`)** — run the Phase-2 stress config. Every `MIG-COMMIT` from the same
   `src` must report the same `dst` for the whole run (no teardown exists yet, so pairings are
   permanent). The two 1d asserts and the 1f assert stay silent. A set seen as `dst` never appears as
   `src`.
4. **DSS hygiene (sim)** — after a commit, `SBC_ColdestSet` never reports the just-paired destination
   again over a long run.
5. **Destination saturation (the 2a(e) defect)** — tally `SBC_SetSat` for sets used as destinations,
   before and after `internalRead`. The phantom-miss loop should disappear: a destination should stay
   in the DSS cold list instead of climbing out of it. Also re-tally `ABORT-DST` — if the loop was a
   real contributor, this is where the yield moves.
6. **Decline rate** — count `ABORT-DST` and the no-offer `MIG-DECLINE`s. Pinning can only raise them
   (a pinned source cannot re-pick). If the rate jumps sharply, stop and report before building the
   swap — the escape hatches are the optimization table in [phase-3.md](phase-3.md).
7. **Building blocks** — compile-only is acceptable for `secondarySearch`/`pairInfo` (no consumer
   yet); functional verification comes with the swap spec.
8. **Stock config** — one `migration_stress_test` run on `VerilatorRocket8KL116KL2Config` must stay
   **7/7 PASS, 0 asserts**. Control run on `VerilatorRocket8KL116KL2NoSbcConfig` must also stay 7/7.

---

## Open questions still owed by the thinker (not blocking Part 1)

1. **Secondary-hit accounting.** When a line is recovered by a swap, does the home set count it as a
   hit or a miss for its saturation counter? A hit keeps S looking satisfied; a miss pushes S to keep
   migrating.
2. **`s_verify` timing.** Rebuild it before the swap lands (safer — Phase 3 is the first time a parked
   copy is served to the CPU) or after (faster to a first result)?

---

## Appendix — the superseded Part 1 (2026-07-05)

Kept for the record. **Do not implement this.** It assumes `migrateQuery` is driven by the migrating
set, which stopped being true when probe-then-migrate made the deferred path the majority path.

```scala
// SUPERSEDED — see Part 1a/1b above
val qSet      = io.migrateQuery.bits
val qEntry    = at(qSet)
val qIsSource = qEntry.valid && !qEntry.sd
val qIsDest   = qEntry.valid &&  qEntry.sd
val hotOK     = (params.micro.sbcAutoMigrate.B || armed(qSet)) && (sat(qSet) >= tHi)
val dssPick   = dss.io.coldestSet
val dssOK     = dss.io.coldestValid && (dss.io.coldestLevel < tLo) && !at(dssPick).valid
io.migrateResp.migrate := hotOK && !qIsDest && (qIsSource || dssOK)
io.migrateResp.destSet := Mux(qIsSource, qEntry.assocSet, dssPick)
```

Two differences from the new version, beyond the missing 1a:

- It does not split `destOk` from `migrate`. `destOk` did not exist in July; `f40fbd4` added it so the
  Scheduler could build a live destination offer without re-deriving `T_lo`. Both must now be driven.
- It has no `sbcForceDstSet` carve-out on the asserts, which would make the forced-destination debug
  config assert immediately.
