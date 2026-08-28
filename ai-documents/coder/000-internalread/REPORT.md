# Coder report — commit 1 (`internalRead`) hit its stop condition

**Date:** 2026-08-24 · **Author:** coder session · **Status:** blocked, awaiting a decision
**Work order:** [TASK.md](TASK.md) · **Spec:** [spec-sbc-phase3-prereqs.md](../../spec-sbc-phase3-prereqs.md)

---

## Summary

`internalRead` works and is correct. It also **cut committed migrations from 424 to 14**, which is the
stop condition the handover names, so nothing after it has been built.

The cause is now understood and is not a bug in the fix. **The defect being removed was load-bearing.**
Every destination probe used to count a phantom miss on the destination set. That heat pushed the set
out of the "coldest" slot and made the DSS rotate to another set. With the phantom heat gone, the DSS
locks onto one set forever and every migration piles into it.

The DSS has no rotation mechanism of its own. It had been borrowing one from a bug.

**Decision needed before commit 3.** My recommendation is at the bottom.

---

## What is committed

| Commit | What |
|---|---|
| `23509d8` | `sbc_stats.py` breaks `DREAD-RESULT` down by reject cause |

**Not committed:** the `internalRead` change (`Directory.scala`, `Scheduler.scala`, `MSHR.scala`). It is
in the working tree and saved as a patch. The handover's pass criteria were not met, so I held it.

Commits 3 and 4 are written and staged as apply-scripts, but not applied.

---

## Deviations from the work order

Two, both deliberate.

1. **The parser (commit 2) was done first, not second.** It has to exist before the baseline run for the
   split to be measured on both sides. It is retroactive — it re-parses any saved log — so nothing was
   lost by moving it.
2. **Commit 1 also touches `MSHR.scala`.** Adding a field to `DirectoryRead` leaves the MSHR's `dread`
   bundle incomplete, so `internalRead := true.B` there is required for elaboration, not optional. That
   line is spec'd in Part 2g; I pulled it forward.

---

## Spec issues found during review

Both were raised before any code was written, and both are now resolved in the spec.

**1. Part 1a had a hole on the fast path.** The original `migrantOH` is built from
`status.dstValid || status.migPending`. `migPending` is the deferred path only, and `dstValid` comes from
`migrating`, a register — high only from the cycle *after* the decision. So in the exact cycle a
fast-path MSHR takes its destination, `migrantOH` is all zero and the query falls back to the queue
head, which by then is a different set. Fast-path migrations would have parked in another set's
partner — the same Diagram C bug, on the other path.

Resolved: the thinker split the query into two ports with two keys (`migrateQuery` for advice, keyed to
the allocating set; `destQuery` keyed to `decidingOH = Mux(anyMigrating, migrantOH, directoryFanout)`).
That is better than either option I proposed — advice latched for the wrong set creates a *spurious
pairing*, which pins two sets out of the pool until teardown, and is worse than a wasted migration.

**2. `internalRead` did not fully stop tag matching.** Gating only `hits` leaves the same-cycle write
bypass term live, so a directory write with tag 0 could still make the probe report a hit. Resolved:
gate `tagMatch` instead, which covers all three consumers. `wayMatch` stays live — forwarding fresh
metadata for the victim way is what the probe wants.

---

## Runs

Both on `VerilatorRocket8KL116KL2Config`, `migration_stress_test`, all seven cases.

| | Baseline | With `internalRead` |
|---|---|---|
| Label | `_baseline-707445c` | `_c1-internalread` |
| Result | **7/7 PASS**, exit 0, 0 asserts | **7/7 PASS**, exit 0, 0 asserts |

Note: nothing at HEAD had ever been built. The existing simulator binary predated `707445c` by 26
minutes, and the newest log in `sw/verilator_logs/` was from `cbb3837` and had ended in the
`assert (new_meta.hit)` failure `f40fbd4` fixed. The baseline above is the first clean run this code
has had.

---

## Results

### Migration outcomes

| Metric | Baseline | `internalRead` | Direction |
|---|---:|---:|---|
| MIG-START | 66,278 | 45,896 | −31% |
| **Committed** | **424** | **14** | **−97%** |
| ABORT-DST | 65,854 (99.36%) | 45,882 (99.97%) | worse |
| MIG-DECLINE | 684 | 0 | — |
| HOT events | 25,559 | 4,113 | −84% |
| COOL events | 2,380 | 834 | −65% |
| Copies started / done | 424 / 424 | 14 / 14 | matched |
| Displaced-reclaim fired | 380 | 0 | — |

Copies and commits match exactly in both runs. The old "copy that never commits" leak is gone at HEAD —
that was fixed by `707445c` and this is the first run to confirm it.

### Destination probe outcomes

| | Baseline | `internalRead` |
|---|---:|---:|
| Found a free way | 2 (0.00%) | 1 (0.00%) |
| Found a clean evictable way | 422 (0.64%) | 13 (0.03%) |
| Rejected | 65,854 (99.36%) | 45,882 (99.97%) |

Reject causes, share of rejects:

| Cause | Baseline | `internalRead` |
|---|---:|---:|
| `dirty` only | 44.70% | 24.75% |
| `dirty` + `clients` | 39.47% | 63.55% |
| `clients` only | 15.79% | 11.70% |
| `displaced` only | 0.05% | 0% |
| **`dirty` present** | **84.16%** | **88.30%** |
| `clients` present | 55.26% | 75.25% |

---

## Root cause of the regression

### The destination stopped rotating

Which set was offered as the migration destination:

| Offered destination | Baseline | `internalRead` |
|---|---:|---:|
| set 7 | 154,444 | **179,406** |
| set 3 | 47,082 | 19 |
| set 5 | 23,384 | 0 |
| set 2 | 18,663 | 12 |
| set 0 | 1,150 | 111 |
| set 1 | 239 | 10 |
| set 6 | 72 | 0 |
| set 4 | 49 | 0 |

Rejects by destination set tell the same story. Baseline spread them over eight sets
(7: 41,810 · 3: 12,703 · 5: 5,894 · 2: 5,337 · rest small). With `internalRead`, **every single one of
the 45,882 rejects is on set 7.**

### The mechanism

1. Every migration destination probe was a directory read that reached the observation tap.
2. The tap saw `hit = false`, so the SBU counted **+1 saturation on the destination set**.
3. The chosen set therefore heated up and stopped being the DSS's coldest candidate.
4. The DSS moved to a different set. Migrations spread across eight sets.
5. `internalRead` removes step 2. The coldest set now never changes.
6. Set 7 is full of dirty and client-held lines, so ~100% of migrations to it abort.

The phantom miss was acting as an accidental round-robin over destinations.

### Second effect — sets were being called hot that never were

HOT events by set:

| Set | Baseline | `internalRead` |
|---|---:|---:|
| 0 | **19,251** | **0** |
| 4 | 4,441 | 2,523 |
| 5 | 974 | 934 |
| 2 | 825 | 0 |
| 6 | 47 | 326 |
| 1 | 11 | 330 |
| 7 | 7 | 0 |
| 3 | 3 | 0 |

Set 0 produced 19,251 HOT crossings in the baseline and **zero** afterwards. It was never genuinely
hot — it was being heated entirely by the cache probing it, and was then eligible to act as a migration
*source* on that basis. Set 5 (`HOT_SET`, the set the benchmark actually hammers) barely moves: 974 → 934.

That is the clearest single statement of what the defect was doing: **84% of all "this set is hot"
events were manufactured by the cache observing itself.**

---

## Evidence that pinning is needed (unchanged by this)

Committed migrations by source→destination in the baseline:

```
5->0: 384    5->1: 6    5->3: 6    5->7: 5    5->2: 5
5->6: 4      5->4: 3    1->3: 3    1->2: 2    3->7: 1
3->1: 1      1->6: 1    1->0: 1    1->4: 1    7->6: 1
```

Two things pinning exists to prevent, both visible here:

- **Set 5 scatters across seven different destinations** (0, 1, 2, 3, 4, 6, 7). A Phase-3 search keyed on
  the association table would look in one of them and miss the rest.
- **Set 7 is both a source and a destination** (`7->6` commits, while set 7 receives 41,810 probes).
  That is the 1:1 violation.

So Part 1 is still the right thing to build. This report is about *when*, not *whether*.

---

## Analysis — what I think is going on

`internalRead` is correct and should stay. The saturation counters now measure real demand rather than
the cache's own machinery. Everything downstream of them — HOT/COOL, DSS candidacy, migrate advice — was
being driven by noise, and 84% of the "hot" signal was self-inflicted.

What it exposes is a real design gap that was previously hidden: **the DSS picks one coldest set and
stays on it.** Nothing in the current policy spreads destinations or gives up on a set that keeps
rejecting.

I believe commit 3 supplies exactly that mechanism, deliberately:

- `dssOK` requires `!at(dssPick).valid` — a set already in a pairing cannot be picked for a new one.
- 1e adds a DSS **remove** port fired on commit, so a set leaves the candidate list the moment it is paired.
- 1e also gates DSS updates with `!at(tapSet).valid`, so paired sets cannot re-enter.

Together those force the next pairing to a *different* unpaired cold set. That is the rotation that just
disappeared — restored by design instead of by accident.

**But this is a hypothesis, not a measurement.** There is a real chance it does not recover, because
pinning also removes the ability to re-pick: a source pinned to a full partner declines and skips, and
the current data says destination sets are essentially always full (2 free ways in 66,278 probes).

There is also a plainer possibility worth naming: with only ~32 sets, 8 ways, and a benchmark that keeps
almost everything resident, **there may be no genuinely cold destination in this workload at all.** The
baseline's 424 commits may have been mostly an artifact of the phantom-heat churn rather than real
opportunity. If so, the honest next step is a different workload, not a policy tweak.

---

## Options

| # | Option | Argument for | Argument against |
|---|---|---|---|
| 1 | **Apply commit 3 and measure 1+3 together** | The DSS remove port is the missing rotation. Judging commit 1 alone may be judging half a mechanism. | Two changes, one measurement. If it stays broken we will not know which one is at fault. |
| 2 | Keep commit 1, treat DSS rotation as its own work item | Cleanest attribution. Rotation is clearly a real gap regardless of Phase 3. | Delays Phase 3 for a policy question the paper may already answer. |
| 3 | Revert commit 1, keep the phantom misses | Restores 424 commits immediately. | Keeps a knowingly-wrong counter feeding every policy decision. 84% of HOT is noise. Not recommended. |
| 4 | Re-measure on a different workload first | The stress test may simply have no cold sets. `matmult_float` is already owed per CLAUDE.md. | Costs a run before any decision. |

**My recommendation: option 1, with a condition.** Apply commit 3, measure, and if commits do not
recover to at least the baseline's 424, stop again rather than continuing to commit 4. The two changes
are separable afterwards — commit 1 is a 71-line patch that can be reverted on its own if the split
attribution matters.

I would also queue option 4 regardless. Every number in this report comes from a correctness test, not a
performance workload, and CLAUDE.md already flags that as owed.

---

## Questions for the thinker

1. Which option above?
2. Was the DSS ever meant to rotate destinations, or is "one coldest set until it is paired away" the
   intended Phase-3 behaviour? The answer decides whether the regression is a bug or the design working
   as specified.
3. The reject data says `dirty` is present in 84–88% of destination rejects. A destination-side
   probe-then-migrate would only reach the `clients`-only slice (12–16%). Does that change the priority
   of the open `ABORT-DST` item?
4. Should the saturation counter treat a phantom-free destination probe as *anything* — for example a
   small decay — or is silence correct?

---

## Reproducing

```bash
# both logs are kept
sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2Config_baseline-707445c/
sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2Config_c1-internalread/

# re-parse either one
python3 sw/scripts/sbc_stats.py <run-dir>
```

The `internalRead` patch is in the working tree; a copy is saved outside the repo in the session
scratchpad as `commit1-internalRead.patch`.
