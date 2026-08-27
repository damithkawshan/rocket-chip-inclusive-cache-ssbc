# REPORT 001 — Phase 3: close the reuse loop

**Coder:** Claude (coder session) · **Status:** `in progress`
**Last updated:** 2026-08-26

> Written as I go. Newest facts appended per section.

---

## Verdict

_(pending — filled in after commit 3)_

### ⚠️ Commit 1 result the thinker needs to see NOW (TASK §8, second bullet)

Pinning is **correct** (1:1 holds, checksum 29824, 0 asserts, 7/7) but on `matmult` it did not merely
reduce migration — it **stopped it**, and the mechanism is not the §1 four-pair freeze.

Time-aligned comparison, both runs cycles 0..7.9M, same binary, same config:

| event | commit 1 (pinned) | reference 2026-08-25 |
|---|---:|---:|
| ADVICE-MIG | **17** | 9,422 |
| MIG-START | 13 | 8,041 |
| MIG-COMMIT | **11** | 1,374 |
| ABORT-DST | 2 | 6,667 |
| OUTER-A | **64,543** | 96,506 |
| EVICT-NORMAL | 64,464 | 85,581 |

**Only 2 pairings formed: 5→0 and 7→6.** The reference's migration source distribution explains why:

```
ADVICE-MIG by source set, reference run
   5704  set 0     <-- the workload's dominant migration source
   1869  set 2
   1538  set 1
   1199  set 4
   1079  set 5
    632  set 7
     49  set 3
```

**The DSS handed out set 0 and set 6 as the first two destinations.** Under strict 1:1 a destination
may never source (`!dIsDest`), so the single hottest source in the workload — set 0, 61% of all advice
— was locked out permanently on the second migration of the run. Sets 2, 1, 4 then went quiet because
migration stopping made the cache healthy again (OUTER-A −33%), so saturation stopped pinning at T_hi.
It is a self-reinforcing lock-out, not a capacity freeze: 4 of 8 sets are still unpaired and free.

**Consequence for commit 3:** the parked pool is 11 lines. A secondary search will almost never find
anything, so `SecHits` will be near zero for reasons that have nothing to do with the swap datapath.

**Why I am continuing rather than stopping here:** teardown (commit 4) is the designed release valve —
it dissolves 5→0 once set 0 holds none of set 5's lines, which returns set 0 to the pool and lets it
become a source. That is in scope for this task, so the question "is pinning survivable at 8 sets" is
not answerable until commit 4 lands. If SecHits is still ~0 with teardown in, the finding is the
DSS-picks-the-future-hot-set problem above, and the fix is a policy decision (TASK §8).

**Incidental but notable:** OUTER-A fell 33% purely from migrating less. That is independent
confirmation of the 2026-08-25 conclusion that Phase-2 migration without reuse is the cost.

---

## Commits

| # | Commit | What | Landed? |
|---|---|---|---|
| 1 | `97b0d54` | Pinning (§1a/§1b/§1d/§1e/§1f) + §2b pulled forward | ✅ yes |
| 2 | | Building blocks (§2a directory secondary-search) | |
| 3 | | **Search + swap + replay** ⭐ | |
| 4 | | Teardown | |

---

## Spec vs RTL

Findings recorded as they are hit. Cited against the working tree at `7ad8020`.

### F1 — §2a(e) `internalRead` is ALREADY BUILT (spec is stale, not wrong)

`internalRead` exists in [Directory.scala:67,136,173,177,188](../../../design/craft/inclusivecache/src/Directory.scala#L67)
and is driven at [Scheduler.scala:341](../../../design/craft/inclusivecache/src/Scheduler.scala#L341).
It shipped in `7ad8020`. Nothing to do; commit 2 only owes the `secondarySearch` half of §2a.

### F2 — §1a and §2a(f) contradict each other on `preferEvictable`

- §1a says drop the `&& dstOfferValid` term: `(alloc_uses_directory && adviceMigrate) || (dread && ...)`.
- §2a(f) says keep it: `(alloc_uses_directory && adviceMigrate && dstOfferValid) || (dread && ...)`.

**Followed §1a.** After §1a the destination query is keyed to the *deciding* MSHR, so on the alloc-side
read `dstOfferValid` describes some *other* MSHR's destination — ANDing it into the allocating set's
victim hint is meaningless. §2a(f) predates the 2026-08-24 rewrite of §1a. It is a hint only; correctness
does not depend on either form.

### F3 — §1e removes only `dst` from the DSS. At this geometry that freezes the pool.

`dssEntries = 8` and the config has **8 sets**, so *every* set is permanently a DSS candidate and the
"hottest candidate is evicted" path at [DSS.scala:61](../../../design/craft/inclusivecache/src/DSS.scala#L61)
never fires. A paired **source** therefore stays in the candidate list with a stale level. `dssOK`
requires `!at(dssPick).valid`, so as soon as a paired source is the coldest eligible candidate, `dssOK`
is false forever and **no new pairing can ever form** — the exact failure §1e exists to prevent, on the
other half of the pairing.

**Deviation:** `DSS.io.remove` carries **both** halves (`src` and `dst`) and drops both on commit.

### F4 — TASK §3 puts §1f in commit 1 and §2b in commit 2, but §1f consumes `pairInfo` from §2b

**Deviation:** §2b (the `pairInfo` latch, ~8 lines) is pulled forward into commit 1 so the §1f assert
has something to check. Commit 2 is then §2a only.

---

## Deviations from the work order

See F2/F3/F4 above.

---

## Open questions for the thinker

_(none yet)_

---

## Numbers

_(pending)_

---

## Reproducing

_(pending)_
