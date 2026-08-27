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
