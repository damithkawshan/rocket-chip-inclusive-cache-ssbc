# REPORT 002 — the partner-set latch

**Coder:** Claude (coder session) · **Status:** ⛔ **HELD AT STEP 0b** — second gate also came back clean
**Last updated:** 2026-08-28

> Write it as you go, not at the end. Newest facts appended per section.

---

## Verdict

⛔ **Step 0's gate came back the other way. The Amendment-2 diagnosis is not supported by the
evidence, and I have not written a line of Step-1 RTL.**

Every one of the **3,268** `SEC-HIT`s used its set's correct committed partner. So did every one of
the **59,715** `SEC-MISS`es. The distinct-pair lists are not merely overlapping — they are the
**identical set**, `{1→3, 5→7, 6→0}`, and only paired sources ever searched at all. There is no
sighting of a mis-latch in 62,983 searches.

Per TASK §0 and Amendment 2 B3, I stop here. **The SCU serve-path lead from 001 goes back to the top
of the list.** I am not implementing Step 1 on your say-so.

---

## Step 0 — the gate

**Source:** `sims/verilator/output/…VerilatorRocket8KL116KL2Config/migration_stress_test.{out,log}`,
2026-08-27 17:38 — the serve-ON commit-4 run. No re-run, as instructed.

**Integrity checked first** (trap 3): the `.log` holds exactly **one** run banner and **7** case
result lines, so this is a single run and not two interleaved ones. It is the serve-ON build —
`case_full_dirty_dst` and `case_reaccess_migrated` both **FAIL**, the other five PASS, which is the
exact failure signature 001 reported.

### List 1 — the TRUE pairings (`grep MIG-COMMIT`)

| src → dst | migrations |
|---|---:|
| 6 → 0 | 19,138 |
| 1 → 3 | 2,355 |
| 5 → 7 | 666 |

Distinct pairs: **{1→3, 5→7, 6→0}**. Sources `{1,5,6}` and destinations `{0,3,7}` are disjoint —
the 1:1 invariant holds, no set on both sides.

### List 2 — what the searches ACTUALLY used (`grep SEC-HIT`)

| set → partner | secondary hits |
|---|---:|
| 1 → 3 | 2,351 |
| 5 → 7 | 662 |
| 6 → 0 | 255 |

Distinct pairs: **{1→3, 5→7, 6→0}**.

### The comparison

**They agree exactly.** Not one `SEC-HIT` names a partner that is not that set's committed partner.

Three further checks, all of which would have caught a mis-latch the headline comparison could miss:

1. **Every search, hit or miss.** Taking `SEC-HIT` *and* `SEC-MISS` together — 62,983 searches — the
   distinct `(set, partner)` pairs are still exactly **{1→3, 5→7, 6→0}**.
2. **Who searched.** Only sets **5, 1 and 6** ever searched (23,686 / 19,657 / 19,640 searches).
   Those are precisely the three paired sources. **No unpaired set and no destination set ever ran a
   search.** Under the B1 cycle an MSHR on an unpaired or destination set would latch a foreign
   pairing and search with it; none did.
3. **Where the served bytes came from.** `SEC-COPY-DONE` says 2,351 blocks came from set 3 into set 1,
   662 from set 7 into set 5, 255 from set 0 into set 6. Every repatriated block was read out of the
   correct partner set.

The printf reads `pairSetReg` directly ([MSHR.scala:1026](../../../design/craft/inclusivecache/src/MSHR.scala#L1026)),
so these lists are the latched value itself, not an inference from it.

### Why this is a real negative, not a weak one

With three pairings whose partners are all distinct (3, 7, 0), a mis-latch is almost impossible to
hide. If MSHR-A on set 5 latched set 1's answer, it would print `SEC-HIT set=5 partner=3` — a pair
that appears nowhere. The only invisible mis-latch is one that copies a pairing from an MSHR on the
*same* source set, which is harmless by definition.

### The one thing Step 0 cannot rule out

The **mirror-image loss** (TASK §1e / Amendment 2 B5): a same-set reload with a new tag clears a
valid pairing, so an MSHR **skips a search it should have done**. That leaves no wrong pair in the
log — it leaves a missing line. So the latch defect may well be real *as a lost-opportunity bug*.
What Step 0 rules out is the thing it was invoked to explain: **it is not producing wrong-partner
searches, so it cannot be the cause of the two data corruptions.** Those two claims are separable and
the evidence separates them.

### The `6→0` asymmetry has a different explanation, and the log gives it

Amendment 2 B2 read `6→0`'s 255 hits / 19,138 migrations against `1→3`'s near-1:1 as *"the signature
of a mis-keyed lookup."* With the keying now shown correct, the reclaim counts explain it directly:

| destination set | lines parked into it | `EVICT-DISPLACED-RECLAIM` out of it |
|---|---:|---:|
| 0 | 19,138 | **18,881** |
| 3 | 2,355 | 2 |
| 7 | 666 | 4 |

Set 0 absorbed 19,138 parked lines into **8 ways**. They evict each other. 98.7% were reclaimed
before set 6 ever came back for them, so the searches correctly find nothing (19,385 `SEC-MISS` on
`6→0`). Sets 3 and 7 are barely touched, their parked lines survive, and nearly every one is later
found. **The asymmetry is destination turnover, not lookup keying** — it is the same
capacity-collapse mechanism 001 measured on matmult, seen from the other side.

---

## Step 0b — the second gate (Amendment 1 A2)

**Also clean. The stale-twin hypothesis is dead — but not by the count comparison A2 specified, which
on its own would have sent me to Step 1.** I ran a direct test of the invariant instead, and it is the
direct test that decides. Same log, no re-run.

### What I counted, and why

**`OUTER-A` with `hit=0`.** `s_acquire` is armed on `!new_meta.hit || (BRANCH && new_needT)` — a full
miss *or* a BRANCH→TRUNK upgrade — but `searching` is armed only on `!new_meta.hit`
([MSHR.scala:1235-1239](../../../design/craft/inclusivecache/src/MSHR.scala#L1235)). An upgrade
therefore issues an Acquire and correctly runs no search, so counting all `OUTER-A` would manufacture
a gap. The printf carries `hit=`, so it is separable — and in this run **every one of the 145,174
`OUTER-A`s has `hit=0`**, so the upgrade path never fired and the distinction costs nothing here. I
did not use `EVICT-ASSESS`: it fires per eviction assessment, not per allocating miss.

A `SEC-HIT` cancels the Acquire, so demand misses on a paired source `S` = `OUTER-A(hit=0)` + `SEC-HIT`.

### The count comparison A2 asked for

| set | `OUTER-A` (hit=0) | `SEC-MISS` | `SEC-HIT` | demand misses | searches | **gap** |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 17,377 | 17,306 | 2,351 | 19,728 | 19,657 | **71** |
| 5 | 23,062 | 23,024 | 662 | 23,724 | 23,686 | **38** |
| 6 | 19,427 | 19,385 | 255 | 19,682 | 19,640 | **42** |

**Misses exceed searches by 151.** Taken at face value that is A2's "the gap is the finding" branch.
It is not, and the reason is a confound A2 does not mention.

### 150 of the 151 are before the set was ever paired

A set has no AT entry until its first `MIG-COMMIT`, so a miss before that point correctly runs no
search — and there is nothing parked yet for it to miss. Replaying the event stream in order and
splitting on each set's first commit:

| set | searched | unsearched **before** pairing | unsearched **after** pairing |
|---|---:|---:|---:|
| 1 | 17,306 | 71 | **0** |
| 5 | 23,024 | 37 | **1** |
| 6 | 19,385 | 42 | **0** |

First `MIG-COMMIT`: set 5 at ~cycle 12,025; sets 1 and 6 at ~877,630 and ~878,383. Sets 1 and 6 spent
their first ~878k cycles unpaired, which is where their 71 and 42 come from.

**One post-pairing sighting**, on set 5, raw from the log:

```
[SBC] EVICT-ASSESS srcSet=5 way=7 ... dirty=0 clients=1 displaced=0
[SBC] EVICT-NORMAL srcSet=5 srcWay=7
[SBC][SCHED] DIR-WRITE set=5 way=7 state=0 displaced=0 tag=      0
[SBC] OUTER-A addr=0x81000140 set=5 perm=0 param=1 hit=0 ctrl=0 mig=0 op=6 prio=1   <-- no SEC-MISS
```

That is one unsearched demand miss in **60,169** post-pairing misses across the three paired sources —
0.0017%. A1 predicts the opposite: *"on a hammered hot set, most misses take that path."* **99.998% of
post-pairing misses on paired sources did search.** The mirror-image loss is real as a mechanism but
is essentially never taken in this workload, so 1e will not move `SEC-HIT` the way A3 expects.

### The direct test — and this is the one that decides

The count comparison is a proxy. The thing A1 actually claims is a **twin**: a line fetched from DRAM
into its home set while a copy of it is still parked in the partner. That is directly testable without
any re-run, because `DIR-WRITE` is printed on **every** directory write
([Scheduler.scala:181](../../../design/craft/inclusivecache/src/Scheduler.scala#L181), unconditional on
`directory.io.write.valid`) and carries `set/way/state/displaced/tag`. So the full directory can be
shadow-modelled from the log — 1,667,711 `[SBC]` lines replayed, starting from all-INVALID as at reset.

A **twin** = the same tag simultaneously native (`displaced=0`) in a paired source `S` and displaced
(`displaced=1`) in `S`'s partner `D`. Under 1:1 pinning `D`'s parked lines belong to exactly one source,
so the pairing is unambiguous.

| | count |
|---|---:|
| **NATIVE-side twins** — a line installed natively in a paired source while a parked copy was **already live**. *This is A1's failure mode.* | **0** |
| PARKED-side twins — a displaced copy installed while the line was still native | 22,156 |

**Zero.** Never once in the run.

**The 22,156 are the migration's own two-write sequence, not a defect.** `mig_dir1` installs the
displaced copy in `D` while the source way in `S` has not yet been overwritten; the demand refill
overwrites it a few writes later. Raw, from the first one:

```
[SBC] COPY-DONE  srcSet=5 srcWay=4 dstSet=7 dstWay=7
[SBC][SCHED] DIR-WRITE set=7 way=7 state=3 displaced=1 tag= 557062   <-- parked copy installed
[SBC][SCHED] DIR-WRITE set=5 way=4 state=2 displaced=0 tag= 557056   <-- refill overwrites the source way
[SBC][SCHED] MIG-COMMIT srcSet=5 dstSet=7
```

Coexistence across all 22,156: **median 3 `[SBC]` log lines, max 11, 100% resolved within 10, and zero
still open at the end of the run.** Same cycle stamp in every case. That is the designed sequence.

### Why the zero is trustworthy

A detector that reports zero needs a positive control and an opportunity count. It has both:

- **Positive control:** the parked-side branch is the exact mirror of the native-side branch — same
  shadow directory, same tag matching, same pairing map — and it fired 22,156 times. The machinery
  works, in both directions, off the same state.
- **Opportunity count:** a line was installed natively into a paired source **49,452** times while that
  source's partner held at least one parked line (mean parked population **3.06 of 8 ways**). The
  native-side branch was evaluated 49,452 times with a non-empty parked set and never matched.

The test also does not depend on the assumption my ordered scan needed (one MSHR per set at a time) —
it reads directory state only.

Finally, chaining it through to the symptom: **0 of the 3,268 `SEC-HIT`s served a parked copy that had
ever been native-side twinned.** No served line has a stale-twin history.

### Verdict on Step 0b

**Dead.** No refill ever happened next to a live parked copy, so no parked copy can be a stale version,
so this is not the cause of `case_full_dirty_dst` and `case_reaccess_migrated`. Per A2 I stop and do
not implement Step 1.

The invariant at `diagram.md:204` — *"a refill only ever happens after d was searched and found empty
of L"* — **holds in this run**, 0 violations in 60,169 post-pairing misses on paired sources.

⚠️ **One thing this does not say.** It does not clear the latch defect *as a defect*. `pairValidReg`
is genuinely mis-gated and 1f is genuinely worth having. What Step 0b establishes is that it is not
firing often enough to be causing anything: the count gate found 1 event, and the direct test found 0
consequences. Both of my hypotheses about the latch (Step 0: wrong partner; Step 0b: skipped search →
stale twin) have now been tested against the log and neither is happening at a rate that could explain
two reproducible failures.

---

## Step 1 — the fix

**C1 and C3 landed. C2 deferred — as specified it is incorrect, not merely disruptive.**

### C1 — key the pairing query to the asker

Three edits, all inside the SBC-gated path:

```scala
// Scheduler — ask about the MSHR receiving a directory result, not the port
sbu.io.assocQuery.valid := directoryFanout.asUInt.orR
sbu.io.assocQuery.bits  := Mux1H(directoryFanout, mshrs.map(_.io.status.bits.set))

// MSHR — latch on the result, hold otherwise (replaces the latch in the allocate block)
when (io.directory.valid) {
  pairValidReg := io.pairInfo.valid
  pairSetReg   := io.pairInfo.bits
}

// MSHR — arm the search off the LIVE answer, not the register written this same cycle
val pairLive = io.directory.valid && io.pairInfo.valid
when (params.micro.enableSetBalancing.B && !new_meta.hit && pairLive) { searching := true.B; ... }
```

### The timing argument, in my own words

**What has to be true:** in the cycle a directory result lands in MSHR *i*,
`mshrs(i).io.status.bits.set` must already be the set that result is about.

1. `io.status.bits.set := request.set` ([MSHR.scala:301](../../../design/craft/inclusivecache/src/MSHR.scala#L301)) —
   `status.bits.set` is nothing but the `request` register.
2. `request := io.allocate.bits` under `when (io.allocate.valid)`, so `request` takes the new set at
   the clock edge **ending** the allocate cycle. From the next cycle onward `status.bits.set` is the
   new set.
3. **The allocate and the directory read are the same cycle.** The fresh allocate fires on
   `request.valid && allocReady && mshr_insertOH(i) && !mshr_uses_directory_assuming_no_bypass`
   ([Scheduler.scala:415](../../../design/craft/inclusivecache/src/Scheduler.scala#L415)), and
   `alloc_uses_directory = request.valid && request_alloc_cases`
   ([Scheduler.scala:367](../../../design/craft/inclusivecache/src/Scheduler.scala#L367)) whose first
   term is that same `allocReady && !mshr_uses_directory_assuming_no_bypass && mshr_free`. The read
   address is `request.bits.set` — the very value being latched.
4. The result cannot come back before the next cycle:
   `directoryFanout = params.dirReg(RegNext(...))`
   ([Scheduler.scala:441](../../../design/craft/inclusivecache/src/Scheduler.scala#L441)) is one
   register minimum, two with `dirReg`.

So: read issued at T off the port's set; `request` holds that set from T+1; result and
`directoryFanout` arrive at T+1 (T+2 with `dirReg`). At that cycle
`Mux1H(directoryFanout, status.bits.set)` returns exactly the set the result is about. **It is
aligned with a cycle of margin, and the margin only grows if `dirReg` is enabled.**

The reload path needs no argument at all:
`m.io.allocate.bits.set := m.io.status.bits.set` ([Scheduler.scala:313](../../../design/craft/inclusivecache/src/Scheduler.scala#L313)),
so a reload cannot change the set and `status.bits.set` is stable across the whole window. That is
also precisely why the old code was wrong: it used `repeat`, a **tag** test, to decide whether the
**set** had changed.

**One-hotness** (needed for `Mux1H`): `directoryFanout` is `RegNext` of
`Mux(mshr_uses_directory || dread, mshr_selectOH, Mux(alloc_uses_directory, dirTarget, 0.U))`.
`mshr_selectOH` is one-hot, `dirTarget` is `mshr_insertOH` (one-hot) or a single constant bit — so
one-hot-or-zero. Same property `decidingOH` already relies on
([Scheduler.scala:543](../../../design/craft/inclusivecache/src/Scheduler.scala#L543)).

**Loop freedom:** elaboration passed first try. As predicted this removes a dependency on
`io.allocate.bits.*` rather than adding one — `assocQuery.bits` is now driven from `status.bits.set`,
a register.

### ⚠️ Finding — C1 as written is incomplete, and the missing half would have made things worse

The amendment says to move the latch and stop there. **That alone is a regression.** `searching` is
armed in the plan block, which runs in the *same* cycle as `io.directory.valid`
([MSHR.scala:1242](../../../design/craft/inclusivecache/src/MSHR.scala#L1242)) — so if it kept
reading `pairValidReg`, it would read the value from the register's state *before* this cycle's
write, i.e. the pairing belonging to the **previous** directory result. Today's code at least latches
something for the current request; the half-applied fix would arm searches off the previous one.

Hence `pairLive`. The register is still needed, but only to carry `pairSetReg` forward to the dread,
the copy and the erase, all of which happen at least a cycle later.

**This is the same one-cycle hazard that sinks C2** — I found it on my own change first, which is
what sent me to check C2's consumers.

### C2 — DEFERRED, and the reason is a defect in the instruction

C2 says to latch `migAdviceValidReg` inside `when (io.directory.valid)`. **That cannot work.** Its
main consumer reads it combinationally in that same cycle:

```scala
migFastWantW := ... && migAdviceValidReg && io.directory.valid && ...   // MSHR.scala:819
```

A register written under `when (io.directory.valid)` does not hold the new value *during* that
cycle. `migFastWantW` would therefore only ever see advice left over from a previous directory
result — in practice the migrate fast path stops working. `migFastDecline`
([MSHR.scala:831](../../../design/craft/inclusivecache/src/MSHR.scala#L831)) has the same shape.

**The correct form is to delete the register, not move it.** Once the query is keyed to the asker,
`io.pairInfo.hot` is already valid in exactly the cycle the decision is made, so the advice wants to
be a live wire — the register only ever existed to carry the value across the allocate→result window
that C1 has just removed. That is the same "make it unrepresentable" move C1 makes.

**Why I am not doing it in this commit.** The live wire is not behaviour-neutral. The plan block also
runs on `.elsewhen (io.allocate.valid && io.allocate.bits.repeat)`
([MSHR.scala:1071](../../../design/craft/inclusivecache/src/MSHR.scala#L1071)) — a repeat allocate
with **no** directory result — and the `migProbe` branch
([MSHR.scala:1196](../../../design/craft/inclusivecache/src/MSHR.scala#L1196)) reads the advice
there. On that path a live wire reads false where the register today reads the value latched for the
same set, so `MIG-DEFER` behaviour changes. I checked whether that path is unreachable and it is
**not**: the assess block needs `!new_meta.hit && state =/= INVALID`, and on a repeat allocate
`new_meta = final_meta_writeback`, whose `hit` is set false on two paths
([MSHR.scala:508](../../../design/craft/inclusivecache/src/MSHR.scala#L508),
[537](../../../design/craft/inclusivecache/src/MSHR.scala#L537)) — one of which leaves `state`
untouched when `!meta.hit`, so a non-INVALID state with `hit=false` is representable.

Characterising that is a migration-behaviour change, which would contaminate C5's "essentially
unchanged" acceptance and cannot be bisected apart from C1 if bundled. Per C2's own escape hatch and
C6, **C1 lands alone.** The fix above is ready to write as a separate commit; it needs its own
before/after on `MIG-DEFER` / `MIG-START` / `MIG-COMMIT`.

Note also that C2's premise is now partly moot: the demand-A filter it wanted moved into the MSHR
(`prio(0) && !control`) is **already** applied inside `migFastWantW` and `migFastDecline` from the
MSHR's own `request`. Only the `hot` term is mis-keyed.

### C3 — the invariant assert

Placed in the **Scheduler**, not the MSHR, and that placement is what satisfies trap 1:

```scala
sbu.io.checkQuery := scheduleSet
when (schedule.a.valid && mshr_selectOH.orR && schedule.a.bits.param =/= TLPermissions.BtoT) {
  assert (!sbu.io.checkIsSource || Mux1H(mshr_selectOH, mshrs.map(_.io.status.bits.secSearched)),
          "SBC: paired source issued an outer Acquire without searching its partner")
}
```

- **Trap 1 (do not police the latch with the latch).** `checkIsSource` is a new assert-only read of
  the AT in the SBU (`at(io.checkQuery).valid && !at(io.checkQuery).sd`), keyed to the scheduling
  MSHR's own set. It never passes through `pairValidReg`, `pairInfo`, or `assocQuery`. If C1 were
  wrong, this assert would still fire. In the MSHR there is no independent source available, which
  is why it is not there.
- **Trap 2 (exclude the upgrade).** No new signal needed:
  `a.bits.param := Mux(req_needT, Mux(meta.hit, BtoT, NtoT), NtoB)`, and `s_acquire` is armed only on
  `!hit || (BRANCH && needT)` — so an Acquire with `meta.hit` implies `req_needT`, which means
  **`param === BtoT` ⟺ upgrade**, exactly. The assert excludes it by that term rather than by
  depending on the run happening to contain none.
- The "did I search" term is a new one-bit `searchedReg` (cleared at allocate, set when `searching`
  is armed) exported as `MSHRStatus.secSearched`. It feeds only the assert, so firtool strips it.

### SBC-off

`pairInfoValid` defaults to `false.B` when `enableSetBalancing` is false
([Scheduler.scala:221](../../../design/craft/inclusivecache/src/Scheduler.scala#L221)) and the whole
SBU block is inside `if (params.micro.enableSetBalancing)`, so `pairValidReg`, `pairLive` and
`searchedReg` all constant-fold and the assert does not exist. Verified by build below.

---

## Step 2 — correctness re-verification

### ⚠️ The surprise C0 asked to hear about immediately: `case_full_dirty_dst` now PASSES

```
case_free_dst            PASS      case_full_dirty_dst      PASS  <-- was FAIL
case_full_clean_dst      PASS      case_reaccess_migrated   FAIL  <-- still fails
case_dirty_victims       PASS      case_hazard_rw           PASS
case_bankstore_saturation PASS
```

**6/7, not the 5/7 C5 required.** 0 SBC asserts. C0 said this would be a surprise and asked to be
told at once, so: told.

**I do not believe the fix repaired it, and I think the PASS is a perturbation artifact.** The
evidence is the pairing map, which changed materially:

| | before | after |
|---|---|---|
| pairings | `1→3`, `5→7`, `6→0` | `4→3`, `5→7`, `6→0`, `1→2` |
| migrations into **set 3** | 2,355, sourced from **set 1** | 1,723, sourced from **set 4** |

`case_full_dirty_dst` is the "full dirty cold **set 3**" case. Between the two runs the set paired
into set 3 changed identity. The latch fix alters which MSHRs search and when, which shifts DSS
timing, which reshuffles the whole pairing map — and case 4 then stops exercising whatever corrupts.

That reading is reinforced by the acceptance number itself: the fix removed **one** unsearched miss
in 60,169. One event cannot plausibly be the difference between a reproducible data corruption and a
clean pass. **A PASS obtained by perturbation is worse than a FAIL — it hides the defect.** I would
not treat case 4 as fixed, and I would not let this become the reason the corruption hunt is
narrowed.

### The C5 acceptance table — the one direct measurement

From `sbc_latch_check.py` (in this folder), which reproduces every "before" figure exactly from the
preserved old log:

| | before | required after | **actual after** | |
|---|---:|---:|---:|---|
| unsearched **post**-pairing misses | 1 | **0** | **0** | ✅ |
| unsearched **pre**-pairing misses | 150 | ~150, unchanged | 234 | ✅ see note |
| wrong-partner searches | 0 | 0 | **0** | ✅ |
| native-side twins | 0 | 0 | **0** | ✅ |
| parked-side twins (positive control) | 22,156 | — | 21,893 | detector still live |
| native-side opportunities | 49,452 | — | 67,294 | branch still exercised |

**Note on the 234.** Per set: `1: 71→69`, `5: 37→37`, `6: 42→45` — unchanged. The extra 83 is
**set 4's own pre-pairing window**, which exists only because a fourth pairing formed. These are
correct by definition (nothing is parked before a set has a partner), so the requirement "~150,
unchanged" is met once the new pairing is accounted for.

### `MSHR.scala:915` — did not fire

Nor did the new C3 assert, nor any other SBC assert, across 21,897 migrations and 84,691 searches.
Reported as C7 item 7 asks. Since C1 makes `pairValidReg` correct more often, 915 should now be
running less vacuously than before — but I cannot prove it stopped being vacuous, only that it did
not fire.

### `SEC-HIT` / `SEC-MISS` — moved, but not comparably

| | before | after |
|---|---:|---:|
| SEC-HIT | 3,268 | 2,956 |
| SEC-MISS | 59,715 | 81,735 |

C0 retracted 1e and predicted no movement. `SEC-HIT` indeed did not rise — it fell slightly. But
these two runs have **different pairing maps**, so the numbers are not measuring the same thing and
I would not read either direction as an effect of the fix. The 1e retraction is consistent with the
data; the specific values are not evidence for anything.

### Still owed

`bringup_matmult` (checksum 29824) and the SBC-off bit-exactness check were still running when this
section was written. Both reported below.

---

## Step 3 — is the partner fence necessary?

---

## Step 4 — deadlock audit

---

## Step 5 — numbers

---

## Spec vs RTL

---

## Deviations from the work order

---

## A5 — the two SCU leads, checked against the RTL (no implementation)

Since Step 0b came back clean, A5 is where this goes. **Both of your details are literal facts about
the RTL — I confirmed each at the cited line.** I have not tested whether either causes the corruption.

**A5.1 — the SCU can stall mid-block. Confirmed.**
[SetCopyUnit.scala:137](../../../design/craft/inclusivecache/src/SetCopyUnit.scala#L137) is
`io.bs_wadr.valid := io.copy_wsafe` inside `s_write`, and `wrBeat` advances only on `io.bs_wadr.fire`.
So `copy_wsafe` is re-evaluated on **every write beat** and a drop mid-block stalls the write there,
with some beats new and some old. Nothing latches the safety decision for the duration of the block.

**A5.2 — the one-cycle blind spot. Confirmed, and it is structural.**
[SourceD.scala:406](../../../design/craft/inclusivecache/src/SourceD.scala#L406) guards the `s1`
comparison with `busy`, and `busy` is a `RegInit`
([SourceD.scala:91](../../../design/craft/inclusivecache/src/SourceD.scala#L91)). But
[SourceD.scala:95](../../../design/craft/inclusivecache/src/SourceD.scala#L95) is
`s1_req = Mux(!busy, io.req.bits, s1_req_reg)` and
[SourceD.scala:103,117](../../../design/craft/inclusivecache/src/SourceD.scala#L103) drive
`io.bs_radr.valid := (busy || io.req.valid) && s1_need_r && !s1_block_r`. So in the cycle `io.req`
fires, SourceD issues its first bank read from `io.req.bits` while `busy` is still false and
`s1_req_reg` still holds the previous request — and `copy_wsafe` reads `s1_req_reg`, not `s1_req`.
The comparison is one cycle behind the read it is meant to guard.

Worth noting for scoping: this blind spot is **upstream's**, not ours, and `evict_safe` and
`grant_safe` share it ([SourceD.scala:383,390](../../../design/craft/inclusivecache/src/SourceD.scala#L383)).
The comment at [SourceD.scala:378](../../../design/craft/inclusivecache/src/SourceD.scala#L378) —
*"We can at least compare to registers `s1_req_reg`"* — reads like an acknowledged approximation. So
the question is not "is it a hole" (it is) but "what closed it upstream, and does a repatriation still
satisfy that". Your framing — an upstream assumption used outside its conditions — is the right one.

**The open question I would want answered before building anything**, and I have not answered it:
*who* is reading `(request.set, meta.way)` while the SCU writes it. For a migration the destination is
a fenced cold set, so the answer is "nobody". For a repatriation the destination is this MSHR's own
live home set — but the MSHR's own Grant is already gated behind `w_scopy`, its release path behind
`w_releaseack && w_rprobeacklast`, and set exclusivity should keep other MSHRs off the set. If none of
those is the reader, the hazard may be unreachable and A5 is the wrong tree, so I would rather identify
the concrete reader first than gate against a hypothetical one. That is a targeted instrumented run,
which is Step-1-scale work — your call, not mine to start.

---

## Open questions for the thinker
