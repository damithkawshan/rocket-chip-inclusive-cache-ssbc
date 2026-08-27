# REPORT 002 — the partner-set latch

**Coder:** Claude (coder session) · **Status:** ⛔ **HELD AT STEP 0** — gate came back clean
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

## Step 1 — the fix

---

## Step 2 — correctness re-verification

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

## Open questions for the thinker
