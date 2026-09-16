# Experiment — does the source-side eviction bias cost primary hits?

**Opened 2026-09-16.** Starting point: tag `sbc-007-c2-breakeven-2026-09-16` (`4274bac`), task 007
commits 0-2 landed. Board result at that tag: SBC ties the plain L2 (cycles +0.85%, memory traffic
1.004x) after the old +53%/2.73x loss was fixed — see
[why-sbc-loses-2026-09-15.md](why-sbc-loses-2026-09-15.md) and the 007 REPORT's "Board run after
commit 2" section. Not a task-007 commit — this touches Phase 2 code, outside C1-C4.

## 1. Why this experiment

With the 47%-parked fixed point gone, SBC and the plain L2 now differ in exactly two ways on a hot-set
miss: **which line is evicted**, and **where it goes**. This experiment removes the first difference
and measures what happens.

## 2. What is wrong today, in one paragraph

When a source set is hot, `Scheduler.scala` sets `preferEvictable` on the demand-miss directory read
([Scheduler.scala:460](../../design/craft/inclusivecache/src/Scheduler.scala#L460), the
`alloc_uses_directory && adviceMigrate` term). This makes the directory's victim mux return the
**first** clean, client-free way in the set (`PriorityEncoderOH(evictableOH & freeWays)`,
[Directory.scala:212-213](../../design/craft/inclusivecache/src/Directory.scala#L212-L213)) instead of
a random one. The plain L2 never sets this term, so it always evicts randomly. Concretely: if way 0 is
clean and client-free, way 0 is evicted on **every** miss to that set for as long as it stays eligible
- concentrating all eviction pressure on one way instead of spreading it, and doing so only in the SBC
half.

## 3. Where it came from, and why it may no longer be needed

Added in Phase 2, when almost no victim was migration-eligible at all (the "low-p blocker": ~0.02% of
victims passed the `clients`-free test, because Rocket's 4-line D$ made the L2's `clients` bit stale
almost all the time). Steering toward an eligible line was how migration got to happen at all.

`cbb3837` (probe-then-migrate) fixed the same problem a different way: probe the victim first, clear
the stale bit, then decide. Eligibility went from 0.02% to 78.4%. The steering term may have been
load-bearing only for the problem `cbb3837` already solved.

One more fact worth recording: a guard that used to make this term rare (`&& dstOfferValid`, "keep the
hint as rare as it was before" per the comment at
[Scheduler.scala:454-459](../../design/craft/inclusivecache/src/Scheduler.scala#L454-L459)) was removed
during the Phase 2.5b/3 destination-binding rework. So today it fires on every miss to a hot set, not
only when a destination is actually on offer.

## 4. The hypothesis

Concentrating eviction on one way costs primary hits when that way is reused. Board evidence at the
tag: primary hits fell by 1,513,805 while secondary hits rose by only 649,606 - a **0.43 hit gained per
hit lost**, worse than break-even. Some unknown share of that 1.51M loss may be this bias rather than
migration itself (a random victim would sometimes have picked the same line anyway, so this cannot
explain all of it).

**Not claimed:** that removing this term will help. Evicting a clean, client-free line also avoids a
probe, so it may be cheaper regardless of hit rate. The experiment is to measure, not to confirm a
prediction.

## 5. The change

One line. Delete the first term of `preferEvictable`:

```scala
// before
directory.io.read.bits.preferEvictable := (alloc_uses_directory && adviceMigrate) ||
                                          (mshr_uses_directory_for_dread && schedule.dread.bits.preferEvictable)
// after
directory.io.read.bits.preferEvictable := mshr_uses_directory_for_dread && schedule.dread.bits.preferEvictable
```

The second term (the migration destination probe, [MSHR.scala:610](../../design/craft/inclusivecache/src/MSHR.scala#L610))
is untouched - the destination still needs to find a free or evictable way, and that is a different
question ("where does the moved line land") from this one ("which line does the source give up").

After this change, SBC evicts **exactly the line the plain L2 would have evicted** on every hot-set
miss. Migration becomes a pure rescue: "the baseline was throwing this line away - can we park it
instead?" This is the tightest form the A/B can take, since the only remaining difference between the
two halves is where a victim goes, never which one is picked.

**No new parameter, no gate.** This is a one-line experiment on top of task 007's tree, not a
task-007 commit and not a permanent knob - see decision in section 7.

## 6. What I expect the identity to look like

The same accounting done for the tag's board run:

```
net = secondary hits gained - primary hits lost
```

If the bias is a real cost, this experiment should reduce the primary-hit loss without necessarily
changing the secondary-hit gain much (migration eligibility, not victim identity, is what feeds
secondary hits) - moving the ratio above 0.43, ideally toward or past 1.0.

**A second effect rides along and must be separated when reading the result:** a random victim is more
often dirty than a hand-picked clean one, so migration attempts may **decline more often** (`SBC_Aborted`
/ declines should rise, `SBC_Migrations` may fall). Read `SBC_Migrations`, `SBC_Attempted` and
`SBC_Aborted` alongside the hit-rate numbers, not just the hit rate, so a smaller `dataMiss` count isn't
misread as "the bias mattered" when it was really "fewer migrations happened."

## 7. Scope and gate

- Verilator gate: `migration_stress_test` 7/7 on both configs, `sbc_migrate_switch_test` T1-T4, 0
  asserts, both shadow checkers quiet - same as every 007 gate. This change touches only victim
  selection on an already-tested path (`preferEvictable` on the demand-miss read); no new state, no new
  signal.
- Report the same five numbers as every 007 commit (`SBC_Parked` end, `SBC_Migrations`, primary hits,
  secondary hits, data misses, hit rate), plus `SBC_Attempted`/`SBC_Aborted` per section 6.
- Board: one bitstream rebuild (`FPGASingleRocketVCU118L18K64K16WL2ConfigSBC`), one `-ab` session,
  same omnetpp workload as the tag's run, so the result is directly comparable.
- **This is a throwaway experiment, not a commit to keep regardless of outcome.** If the board result is
  worse or flat, the change is reverted and this doc is updated to say so - it does not become a
  permanent behaviour change without a separate decision.

## 8. Result

_(filled in after the run)_
