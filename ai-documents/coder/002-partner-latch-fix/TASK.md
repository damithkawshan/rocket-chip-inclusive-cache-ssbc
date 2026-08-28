# TASK 002 — fix the partner-set latch, then land commit 4

**Opened:** 2026-08-28 · **Branch:** `sbc-paper-aligned` · **Predecessor:** `001-phase3-reuse-loop`

**You inherit an uncommitted working tree.** `MSHR.scala`, `Scheduler.scala` and
`SetBalanceUnit.scala` hold the commit-4 search-and-repatriate work from 001. It is correct except
for one defect. Do not revert it, do not restart it.

**The full derivation is in `001-phase3-reuse-loop/TASK.md`, Amendment 2 §B1–B2, and the symptom
record is in `001/REPORT.md`.** This file does not repeat it. It says what to do.

---

## 0. The point of this task, in one paragraph

Commit 4 closed the reuse loop — 2,287 secondary hits, the first time a parked line ever returned
anything — but two stress cases fail with **silently wrong data** and your bisect isolated the serve
path. The cause is not the copy. **An MSHR can latch another set's partner and then search that
set.** The block copy moves the right bytes; it moves the **wrong line**. Fix the latch, and commit 4
becomes the first build in this project that can plausibly beat the SBC-off control.

**The defect in one line:** the pairing lookup is keyed to the request *waiting at the input port*,
broadcast to every MSHR, and latched by any MSHR whose allocate is not a tag-`repeat` — but `repeat`
is a **tag** comparison being used to decide whether the MSHR's **set** changed. On a reload the set
never changes.

Relevant sites: `Scheduler.scala:208, 313-314, 329-330, 352, 362, 584-585` · `MSHR.scala:994,
999-1000, 1071, 1237`.

---

## 1. Build order — five steps, report after each

| # | Step | Gate |
|---|---|---|
| 0 | **Confirm from an existing log.** No re-run. | ⛔ **HARD GATE** — if it comes back the other way, stop and say so |
| 1 | The fix | elaborates, SBC-off bit-exact |
| 2 | Correctness re-verification | stress 7/7, matmult 29824 |
| 3 | Is the partner fence necessary? | a measured answer either way |
| 4 | Deadlock audit + watchdog | a hang becomes a named assert |
| 5 | Numbers against the SBC-off control | reported, whichever way they fall |

---

## Step 0 — GATE. Confirm before writing a line of RTL.

From the commit-4 stress log you already have:

1. `grep MIG-COMMIT` → the distinct `(srcSet, dstSet)` pairs. These are the **true** pairings.
2. `grep SEC-HIT` → the distinct `(set, partner)` pairs. These are what the searches **actually used**.
3. Compare.

**Any `SEC-HIT` whose partner is not that set's committed partner is a direct sighting of the bug.**

Put both lists in `REPORT.md` either way.

- **They disagree** → the diagnosis holds. Go to Step 1.
- **Every SEC-HIT pair matches a committed pair** → ⛔ **the diagnosis is wrong.** Stop, say so
  plainly in `REPORT.md`, and the SCU serve-path lead from 001 goes back to the top of the list. **Do
  not implement Step 1 on my say-so.** You were four-times wrong on this bug and stopped guessing;
  do the same to me.

---

## Step 1 — the fix

### 1a. Take Option B. Option A is the fallback.

- **Option B — key the query to the asker.** Delete the broadcast latch. Key the pairing query to
  the MSHR the same way `destQuery` already is (`Scheduler.scala:540-541`), and latch `pairSetReg`
  at the moment `searching` is armed rather than at allocate. This makes the wrong-set class
  **unrepresentable** rather than fixed once. `status.bits.set` is already correct at the
  directory-result cycle, because `request` is latched at allocate.
- **Option A — fallback.** Add a `fresh` bit to the allocate bundle. The two paths are already
  distinct (`Scheduler.scala:416` fresh vs `313-314` reload). Latch only when `fresh`; **hold**
  otherwise.

Use A **only** if B fights the elaboration loop-freedom rules — see the LOOP FREEDOM note at
`MSHR.scala:249`, which cost a deadlocked elaboration once already. If you switch, say why.

### 1b. Fix `migAdviceValidReg` in the same change

`MSHR.scala:994` has the identical defect and has been live **since Phase 2** — a reloading MSHR
could latch another set's "this set is hot" and migrate a victim out of a set that was never hot.
Data-safe, which is why nothing ever caught it, but it is noise in every migration number we have.

### 1c. Add one identity assert

Every existing check tests **shape** (one-hot, clean, not-self). None tests **identity** — which is
exactly why this was silent. At search time, assert the partner really is this set's partner, read
**live from the AT**, not from the latch. Behind `enableSetBalancing`.

### 1d. Expect a second finding

`MSHR.scala:915` — `assert(!dstClaim.valid || !pairValidReg || dstClaim.bits === pairSetReg)` — is
skipped whenever `pairValidReg` is wrongly **false**, which is the common mis-latch outcome. It has
been passing partly by vacuity. **If it starts firing after the fix, that is a real finding, not a
regression.** Report it. Do not silence it.

### 1e. Also expect SecHits to rise

Both options fix a mirror-image loss: today a same-set reload with a new tag **clears** a valid
pairing, so the MSHR skips a search it should have done.

---

## Step 2 — correctness re-verification

- `migration_stress_test`: **7/7 PASS, 0 asserts**, specifically including `case_full_dirty_dst` and
  `case_reaccess_migrated`.
- `bringup_matmult`: checksum **29824**, 0 asserts.
- Every `SEC-HIT set=X partner=P` in the log has `P` equal to `X`'s committed partner (the Step-0
  check, re-run on the fixed build).
- SBC **off** unchanged — the fix must be inert when `enableSetBalancing` is false.

---

## Step 3 — is the partner fence necessary?

The fence (`secValid` / `secSet` in `MSHRStatus`, `partnerBusy`, and the `dstSetConflict` term) was
built on a diagnosis now known to be wrong. You said you would rather hand that decision over than
guess — right call. Now it can be decided by measurement.

- Re-run Step 2 with the fence **in** and with it **out**.
- If both pass: say so, and recommend. Unjustified mechanism in the fence path is not free — it also
  costs throughput by stalling an innocent set.
- If removing it fails: keep it, and say what failed. That is a genuine finding about the search
  window, not a retreat.

**Do this after the latch fix, never before.** While the partner is wrong the fence is *actively
harmful*, so a measurement taken now would be meaningless.

---

## Step 4 — deadlock audit

Your no-deadlock note reads: *"under 1:1 pinning a destination is never a source, so the MSHR
holding the partner never waits on us."* **A wrong partner voided that premise.** MSHR-A on set X
wrongly points at Z; MSHR-B on set Z wrongly points at X; each waits on `partnerBusy`. Neither can
retire — `sec_ready = !searching && s_sinval` gates **both** `reload` and the final writeback, and
`!searching` also blocks the outer Acquire.

1. **Re-check the commit-4 hang you attributed to the `d_ready` sibling gate.** That fix was clearly
   right on its own terms. The question is only whether it accounted for **every** hang you saw.
   If you have the log, say so; if you do not, say that instead.
2. **Add a watchdog assert on the `partnerBusy` wait**, same pattern as `migDeferCtr`
   (`MSHR.scala:918-920`). Today the only signal is a `sbcDebug`-gated `SEC-STUCK` printf, so a
   production run just stops making progress. Turn it into a named assert with a cycle count.
3. After the fix the premise is restored and the assert should never fire. That is the point —
   it protects the premise, it does not paper over it.

---

## Step 5 — numbers

Report, whichever way they fall:

| | before fix | after fix |
|---|---|---|
| `SEC-HIT` total | | |
| `SEC-MISS` total | | |
| `f` = hits / (hits + misses) | **do not quote the before value** | |
| matmult cycles | 15,699,736 | |
| matmult `OUTER-A` | 13,146 | |
| stress asserts | 0 | |

Control to beat: **SBC off = 15,683,316 cycles / 13,147 OUTER-A.**

Two notes:

- **`f` from the current build is void.** It is polluted three ways: searches that should have
  happened did not, searches ran against the wrong set (false SecMiss), and weak-permission rejects
  destroyed foreign parked lines. Report the raw counts, not the ratio, for the "before" column.
- **A null result is information.** matmult made only 11 migrations. If secondary hits do not move
  the cycle count, that is a real finding about the workload, not a failure of the fix. Say it
  plainly. Do not go looking for a benchmark that flatters the result — that is my job, not yours.

---

## Scope boundaries

- **Do not rebuild `s_verify`.** The copy moves the right bytes from the place it was told to read —
  a copy verifier would have **passed**. The `CLAUDE.md` note that made it look like the lead is
  about a different failure mode. It stays parked.
- **Do not touch destination eligibility**, or the `ABORT-DST` rise you correctly logged in 001 and
  correctly did not act on.
- **Do not build teardown.** That is task 003.
- **Do not build temporary protection for displaced lines.** Settled by your own measurement in 001.
- **Strip the `DIR-WRITE` printf** before anything lands — you flagged it as expensive. Keep
  `SEC-STUCK` and `STALL`.

---

## Traps carried forward from 001 — all four cost you a cycle already

1. **A gate added in one place and missed in its sibling.** `707445c` was this, and so was your own
   `d_ready` deadlock. Name the condition once, use the name at both sites.
2. **`+verbose` stderr is the full instruction trace.** Never pipe it through `awk`/`grep` live —
   ~100x slowdown that looks exactly like a hang. Post-process the `.out`.
3. **Never overlap two `make run-binary` runs for the same CONFIG.** They interleave into one file
   that looks like a real run. Kill the `make` PID and its children, not just the wrapper.
4. **Pass `TIMEOUT_CYCLES=` as a make variable**, not `SIM_FLAGS=+max-cycles=`.

---

## When to stop and ask

- **Step 0 comes back clean** → stop immediately. See the gate.
- **Option B fights loop-freedom and Option A also looks wrong** → stop, describe both, ask.
- **The fix passes but `SEC-HIT` collapses to near zero** → stop. That would mean the pairings
  themselves are wrong, which is a different and bigger problem than the latch.
- **The `MSHR.scala:915` assert fires and you cannot see why** → report it, do not silence it.

---

## Acceptance

1. Step 0's two pair lists, and which way they came out.
2. Stress **7/7, 0 asserts**; matmult checksum **29824**.
3. Every `SEC-HIT` partner equals that set's committed partner.
4. A measured answer on the fence (Step 3).
5. A named assert covering the `partnerBusy` wait (Step 4).
6. The Step-5 table filled in, including the honest cycle count against the control.
7. `REPORT.md` verdict filled in.

---

# Amendment 1 — 2026-08-28 — Step 0 came back clean. New hypothesis, new gate.

## A0. The gate result is accepted, and two of my claims are withdrawn

Your negative is strong: 62,983 searches, three independent cross-checks, and the printf reads
`pairSetReg` itself rather than inferring it. **The mis-latch is not producing wrong-partner
searches, so it is not the cause of the corruption.** Step 0 did exactly what it was for.

Two retractions, both mine:

- **The `6→0` asymmetry.** Your explanation beats mine and is evidence-backed: set 0 absorbed 19,138
  parked lines into 8 ways and reclaimed 18,881 of them, so set 6's searches correctly find nothing.
  Destination turnover, not lookup keying. Accepted.
- **The `partnerBusy` deadlock in Step 4 is OFF.** I argued a wrong partner voided your no-deadlock
  premise. The partner is not wrong, so **your premise holds** and the mutual-wait scenario cannot
  happen. Your `d_ready` attribution stands and does not need re-checking. Step 4 changes below.

A check I should have run before Amendment 2 and did not: wrong-partner serving would have corrupted
the loads-only cases too, and cases 1 and 2 passed. The pass/fail pattern already contradicted me.

## A1. But do not go back to the SCU lead yet — your own report points somewhere first

You wrote, correctly, that Step 0 cannot rule out the **mirror-image loss**: a reload clears a valid
pairing, so an MSHR **skips a search it should have done**. You called it a lost-opportunity bug.

**I think it is a correctness hole**, and the case order says so.

### The rule the design depends on

`ai-documents/diagram.md:204` states the Phase-3 safety invariant:

> *Stale twins impossible: a refill only ever happens after d was searched and found empty of L.*

**Never fetch a line from memory without first checking whether it is already parked.** In a normal
cache this is free — the address decides the set, so there is only one place to look. SBC breaks
that on purpose: a displaced line sits in a set its address does not map to, so there are now two
places a line can be. The mandatory search is what makes that safe.

### How a skipped search turns into wrong data

Line **A** lives in set 5, partner set 7, value **100**.

| | | native in set 5 | parked in set 7 |
|---|---|---|---|
| T1 | A is migrated to set 7. Set 5's way is reused. | — | **100** |
| T2 | Miss on A in set 5. **Search skipped.** Fetch from DRAM, install natively. | **100** | 100 |
| T3 | CPU writes A = 999. Lands on the native copy. | **999** dirty | **100 — stale** |
| T4 | Native copy evicted, written back. DRAM correct. | — | **100 — stale** |
| T5 | Miss on A again. **This time the search runs**, finds set 7's copy, serves it. | | → CPU gets **100** |

Right line, **old version**. That is a different failure from the one Step 0 disproved (wrong line),
with the same visible symptom. And it is still serve-only: with serve off the stale copy is merely
erased, never read.

**No assert sees it.** `PopCount(secHits) <= 1` looks *inside one set*. A twin is one **native**
entry in set 5 and one **displaced** entry in set 7 — different sets, different entry kinds.

### Why a search gets skipped

An MSHR only learns "my set has a partner" on a **fresh allocate** off the input port — the only
path where the pairing lookup describes its set. A request for a set that is already busy is queued
behind the owning MSHR and later enters as a reload, picking up whatever the lookup happens to be
answering. Usually nothing. **On a hammered hot set — exactly where migration happens — most misses
take that path.**

### Why it fits the results

| case | | result | why |
|---|---|---|---|
| 1, 2 | loads only | PASS | both copies hold the same value — a twin is harmless |
| 3 | **first stores**, to the hot set | PASS | stores make lines dirty, dirty lines are never migrated, so nothing new is parked |
| **4, 5** | **read back values an earlier case wrote** | **FAIL** | a parked copy that predates the write is still there and gets served |
| 6, 7 | store every iteration | PASS | their lines are permanently dirty, so nothing is parked to go stale |

The two failures are exactly the first two cases that read back something written earlier.

---

## A2. Step 0b — the new gate. Still no re-run.

Same log. For each **paired source** set (1, 5, 6):

- **count its demand misses** — allocating misses on that set, and
- **count its searches** — `SEC-HIT` + `SEC-MISS` for that set. You already have these:
  19,657 / 23,686 / 19,640.

Pick whichever printf actually marks an allocating miss on a set (`EVICT-ASSESS`, `OUTER-A`, or the
allocate itself) and say which you used and why.

- **misses == searches** → the invariant holds, this hypothesis is dead. Say so. Then see A5.
- **misses > searches** → each unsearched miss could have created a twin. The gap size is the finding.

A direct sighting is also available and worth more than the counts: an `OUTER-A` for a paired source
with no `SEC-MISS` for that set immediately before it. **Every DRAM fetch on a paired source must be
preceded by a search that missed.** Any that is not is a twin being created, live in the log.

Report it either way, and do not implement on my say-so — same discipline as Step 0, which was right.

---

## A3. Step 1 is UNCHANGED. This is the useful part.

**Option B fixes this too, for the same reason it fixed the other one.** Key the pairing query to the
MSHR's own set and latch at the directory-result cycle, and it no longer matters which door the
request came through — the MSHR asks about *its own* set at a moment when it definitely knows what
that set is. Every real miss does a directory read, so every real miss reaches the plan block with a
live, correctly-keyed answer. A reload with a *matching* tag does not read the directory, but that
is a hit, not a miss, so there is nothing to search for.

The second half of the rule is already in the RTL: `a.valid` carries `&& !searching`, so the fetch
cannot overtake the search.

**So Step 1 as written stands.** Sections 1a, 1b, 1d and 1e are unchanged. 1e stops being incidental
and becomes a headline: **expect `SEC-HIT` to rise, possibly a lot.**

### 1f — NEW. Assert the rule, not just the shape.

Every existing check tests shape. None tests the invariant that actually matters. Add:

> at the point the outer Acquire is issued — **if this set is a paired source, the search must have
> completed.**

Read the "is my set a paired source" term **live from the AT**, not from the latch, or the assert
inherits the bug it is checking. Behind `enableSetBalancing`.

This is the guard that would have caught the whole thing on run one, and it is the one I most want
in the tree regardless of how Step 0b comes out.

---

## A4. Step 4 is reduced

The `partnerBusy` deadlock argument is withdrawn (A0). What remains:

- **Drop** item 1 — no need to re-check the `d_ready` hang attribution.
- **Keep** item 2 — still add the watchdog assert on the `partnerBusy` wait. Cheap insurance, and it
  converts a class of hang from "the run stops" into a named assert. It should never fire.

## A4b. Step 2 gains one acceptance item

After the fix, **no twin may exist**: no address should be simultaneously native in its home set and
displaced in that set's partner. If it is cheap to check in sim, check it. If not, say so and rely on
1f instead.

---

## A5. If Step 0b also comes back clean — start here, and here is what I found

Then the corruption really is in the serve path and your original lead was right. The strongest
candidate I can see in the RTL, which is your own `copy_wsafe` argument with two details added:

1. **The SCU re-checks `copy_wsafe` on every write beat** ([SetCopyUnit.scala:137](../../../design/craft/inclusivecache/src/SetCopyUnit.scala#L137)),
   so it can stall **mid-block**. If SourceD begins reading the destination while the SCU is halfway
   through, SourceD reads a **half-new, half-old block**. Upstream never has this problem because its
   safety argument is "no new SourceD request to the destination can start once we pass `s_wsafe`" —
   true only because the destination is a **fenced cold set**. A repatriation's destination is
   `request.set`, a live set.
2. **`copy_wsafe` has a one-cycle blind spot.** It compares against `s1_req_reg` guarded by `busy`
   ([SourceD.scala:405-409](../../../design/craft/inclusivecache/src/SourceD.scala#L405)), and `busy`
   is a **register**. In the cycle a new SourceD request fires, `copy_wsafe` does not see it yet —
   but SourceD issues its first bank read **that same cycle** (`s1_req = Mux(!busy, io.req.bits, …)`).
   Upstream tolerates this because "the first cycle of SourceD falls within the occupancy of the
   MSHR's plan" — an assumption a repatriation breaks by writing into a set an MSHR is live on.

I have **not** proven either causes the corruption. They are the two places where a documented
upstream assumption is being used outside the conditions it was written for. Start there rather than
rebuilding `s_verify`.

---

# Amendment 2 — 2026-08-28 — Step 0b accepted. Build Step 1 anyway, as hygiene.

## C0. Where we stand, and why I am authorising Step 1 after two clean gates

**Step 0b is accepted in full.** The count gate found 151 unsearched misses and I would have read that
as confirmation. You found the confound I missed — 150 of them are *before* the set had a partner, so
correctly unsearched — and then did not stop at the proxy. Shadow-modelling the whole directory from
1.67M `DIR-WRITE` lines, with a positive control that fired 22,156 times and an opportunity count of
49,452, is a better test than the one I specified. **Zero native-side twins is a trustworthy zero.**

Both of my hypotheses are dead. Recorded, no argument.

**What survives is your own sentence:** *"It does not clear the latch defect as a defect."* It is
genuinely mis-gated. We are now fixing it **for its own sake**, not as the corruption fix.

⚠️ **Read this before you start, or you will chase a ghost:**

> **This change will NOT fix `case_full_dirty_dst` or `case_reaccess_migrated`. They must still fail
> after it.** If they pass, something unexpected happened and I want to know immediately — but do not
> aim for it, and do not treat continued failure as the fix not working.

**Retraction — 1e is withdrawn.** I said to expect `SEC-HIT` to rise, possibly a lot. Your data says
otherwise: **one** skipped post-pairing search in 60,169 misses. Expect `SEC-HIT` to move by roughly
that, i.e. not at all. If it moves a lot, that is a surprise worth reporting, not a success.

### Why fix a defect that fires once in 60,169

Because **it is rare for a geometry-dependent reason, not a structural one.** The harmful outcome
needs a paired source to be *unowned* at the moment of the mis-latch. At 8 sets under a hammer, paired
sources are almost always owned, so the mis-latch writes "no partner" instead of "wrong partner". More
sets means less contention means the harmful branch becomes reachable. Task 003 will move to a larger
geometry; I do not want this sitting in the RTL when it does.

Keep it as **one small, isolated commit** so it can be bisected out later.

---

## C1. The pairing latch — the main fix

### What is wrong

`pairInfo` answers about `request.bits.set` (the request at the input port,
[Scheduler.scala:584-585](../../../design/craft/inclusivecache/src/Scheduler.scala#L584)), is
broadcast to every MSHR ([Scheduler.scala:329-330](../../../design/craft/inclusivecache/src/Scheduler.scala#L329)),
and is latched at allocate by any MSHR whose allocate is not a tag-`repeat`
([MSHR.scala:999-1000](../../../design/craft/inclusivecache/src/MSHR.scala#L999)). `repeat` is a
**tag** comparison being used to decide whether the MSHR's **set** changed. On a reload the set never
changes.

### What to do — key it to the asker

Copy the pattern `destQuery` already uses
([Scheduler.scala:539-541](../../../design/craft/inclusivecache/src/Scheduler.scala#L539)):

```scala
// Scheduler — the pairing question is about the MSHR receiving a directory result,
// not about whatever is waiting at the port.
sbu.io.assocQuery.valid := directoryFanout.asUInt.orR
sbu.io.assocQuery.bits  := Mux1H(directoryFanout, mshrs.map(_.io.status.bits.set))
```

`directoryFanout` ([Scheduler.scala:440](../../../design/craft/inclusivecache/src/Scheduler.scala#L440))
is already one-hot — the directory is single-ported, so at most one MSHR gets a result per cycle.
Same property `decidingOH` relies on.

**Timing to satisfy yourself about, and state in the report:** at the result cycle,
`status.bits.set` must already be the MSHR's new set. `request` is latched on `io.allocate.valid`,
the directory read is issued in that same cycle, and the result lands one cycle later
(two with `dirReg`). So it is aligned. Confirm this rather than take my word for it.

### MSHR side — latch on the directory result, hold otherwise

Keep both registers. Only move **when** they are written:

```scala
// delete the pairing lines from the `when (io.allocate.valid)` block, and add:
when (io.directory.valid) {
  pairValidReg := io.pairInfo.valid
  pairSetReg   := io.pairInfo.bits
}
```

Why this is correct on every path:

| path | directory read? | behaviour |
|---|---|---|
| fresh allocate | yes | latches its own set's pairing ✓ |
| reload, tag mismatch | yes | latches **its own** set, not the port's ✓ — this is the bug |
| reload, tag match (`repeat`) | no | **holds** — set unchanged, so pairing unchanged ✓ |
| bypass, tag match | no | holds ✓ |
| search result / dread result | yes | re-latches the same value (same set) — harmless ✓ |

A plain `when (io.directory.valid)` is deliberate. Do **not** try to narrow it with extra terms —
every case above is either correct or a harmless rewrite, and each extra term is a place for the
next sibling-gate bug (trap 1).

### Loop freedom

This **removes** a dependency on `io.allocate.bits.*` rather than adding one, so it moves in the safe
direction relative to the note at
[MSHR.scala:249](../../../design/craft/inclusivecache/src/MSHR.scala#L249). `assocQuery.bits` is now
driven from `status.bits.set`, a register. Nothing on the offer → claim → fence path is touched.
If elaboration still complains, stop and report rather than working around it.

---

## C2. The migrate-advice latch — same class, but the keying is genuinely split

[MSHR.scala:994](../../../design/craft/inclusivecache/src/MSHR.scala#L994) has the identical defect.
But `adviceMigrate` has **two consumers that want two different keys**, and this is the part to get
right:

| consumer | correct key | status |
|---|---|---|
| `preferEvictable` hint on the alloc-side dir read ([Scheduler.scala:394](../../../design/craft/inclusivecache/src/Scheduler.scala#L394)) | `request.bits.set` — it is about the allocating request | **correct today. Do not change it.** |
| `migAdviceValidReg` latch ([MSHR.scala:994](../../../design/craft/inclusivecache/src/MSHR.scala#L994)) | the MSHR's own set | **wrong today** |

So this needs a second answer, not a re-key of the existing one.

**Do it with one extra field, not a new port.** Extend `assocResp`
([SetBalanceUnit.scala:124-125](../../../design/craft/inclusivecache/src/SetBalanceUnit.scala#L124))
with a `hot` bit carrying the same expression as `migrateResp.migrate`, evaluated at
`assocQuery.bits`. Then in the MSHR, latch alongside the pairing:

```scala
when (io.directory.valid) {
  migAdviceValidReg := io.pairInfo.hot && <this request is a demand A>
  ...
}
```

- `sbu.io.migrateQuery` and `adviceMigrate` stay exactly as they are, serving only the
  `preferEvictable` hint.
- The demand-A filter (`prio(0) && !control`) moves into the MSHR, where it can be taken from the
  MSHR's **own** request rather than the port's.
- The `!anyMigrating` term is a hint only and the per-MSHR offer mask
  ([Scheduler.scala:325-327](../../../design/craft/inclusivecache/src/Scheduler.scala#L325)) already
  enforces the real one-migration rule. Keep or drop it — say which and why.

**If this turns out to be more disruptive than it looks, split it into a second commit and land C1
first.** C1 is the one that matters. Do not bundle a struggle.

---

## C3. Assert the rule, not the shape (this is 1f, and it is the piece I most want kept)

Every existing check tests shape — one-hot, clean, not-self. None tests the invariant that actually
matters. Add:

> **At the point the outer Acquire is issued: if this set is a paired source, the search must have
> completed.**

Roughly `assert(!io.schedule.bits.a.valid || !pairedSrc || w_ssearch, ...)`, behind
`enableSetBalancing`.

⚠️ **Two ways to get this wrong:**

1. **Do not read the "am I a paired source" term from `pairValidReg`** — the assert would inherit the
   very register it is meant to police. Read it live, or from a register latched from the live query
   in the same cycle it is used.
2. **Exclude the upgrade path.** You established that `s_acquire` is armed on
   `!new_meta.hit || (BRANCH && new_needT)` while `searching` is armed only on `!new_meta.hit`
   ([MSHR.scala:1235-1239](../../../design/craft/inclusivecache/src/MSHR.scala#L1235)). A
   BRANCH→TRUNK upgrade correctly issues an Acquire with no search. It never fired in your run
   (all 145,174 `OUTER-A`s had `hit=0`), but the assert must not depend on that.

This is the guard that would have made the whole thing a crash on run one instead of two data
corruptions and four days of theories.

---

## C4. Do not "fix" these

- **`preferEvictable`'s keying** — correct as-is (C2 table). It is a hint about the allocating
  request.
- **`destQuery` / `migrateResp.destSet`** — already correctly keyed. It is the pattern, not a target.
- The partner fence, the SCU, destination eligibility, teardown, `s_verify` — all unchanged and out
  of scope.

---

## C5. Verification — what must be true, and what must NOT change

| check | expected |
|---|---|
| SBC **off** | **bit-exact** with the current baseline. Non-negotiable. |
| `migration_stress_test` | still **5/7** — `case_full_dirty_dst` and `case_reaccess_migrated` still FAIL |
| `bringup_matmult` | checksum **29824**, 0 asserts |
| new asserts | 0 firings |
| `SEC-HIT` / `SEC-MISS` | essentially unchanged (1e retracted) |
| matmult cycles / `OUTER-A` | report them; large movement is a surprise, not a goal |

**The one measurable acceptance for this fix** — re-run your Step-0b analysis on the new log:

| | before | required after |
|---|---:|---:|
| unsearched post-pairing misses | 1 | **0** |
| unsearched **pre**-pairing misses | 150 | **~150, unchanged** — these are correct |
| wrong-partner searches | 0 | 0 |
| native-side twins | 0 | 0 |

That is cheap — your replay tooling already does it — and it is the only direct evidence that the
change did what it claims.

**Also watch `MSHR.scala:915`.** It has been passing partly by vacuity (`!pairValidReg` skips it).
With the latch corrected it may start firing. **That would be a real finding, not a regression.**
Report it, do not silence it.

---

## C6. Scope and stopping

- **One commit for C1 (+C3), optionally a second for C2.** Keep them isolated and bisectable.
- **Do not start the corruption hunt in this task.** A5's open question — *who actually reads
  `(request.set, meta.way)` while the SCU writes it* — is the right next question and it is a
  separate instrumented run. It comes after this lands.
- **Stop and ask if:** the C1 timing argument does not hold; elaboration complains about loop
  freedom; C2 balloons; SBC-off is not bit-exact; or the two failing cases start **passing**.

## C7. Acceptance

1. C1 landed, with the timing argument stated in `REPORT.md` in your own words.
2. C2 landed or explicitly deferred with a reason.
3. C3 landed, with both traps in it addressed.
4. SBC-off bit-exact.
5. Stress still 5/7 with the same two failures; matmult checksum 29824.
6. The C5 before/after table, from your replay tooling.
7. Whether `MSHR.scala:915` fired.
8. `REPORT.md` verdict filled in.

---

## Amendment 3 — CLOSING THIS TASK (2026-08-28)

**Status: closed.** C1 + C3 landed as `0f5a7ac` and stand. C2 stays deferred — your hazard analysis
was right and my instruction was wrong.

**The instrumentation pass is WITHDRAWN.** Do not run it. Both surviving leads (the `copy_wsafe`
one-cycle blind spot, and the unfenced repatriation destination) are about the SetCopyUnit block copy
into a live home set — and **task 003 deletes that copy entirely**. Instrumenting it would spend a
build+run cycle on code we are removing.

**Before that code goes, its evidence must not.** Task 003 Stage 0 requires both leads to be appended
to `ai-documents/bug-fix-log.md` with their file:line evidence first.

**The corruption question is not dropped, it is re-aimed.** Task 003 Stage 4 is the experiment: if
`case_reaccess_migrated` passes once the repatriation copy is gone, the bug was there. If it still
fails, 003 stops and we search a much smaller serve path with the new shadow models pointing at the
exact cycle.

→ continues in `../003-serve-in-place/`
