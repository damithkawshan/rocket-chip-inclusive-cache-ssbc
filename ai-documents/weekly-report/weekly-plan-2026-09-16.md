# Weekly plan — 16 Sep 2026

**Goal for the week: find out whether set balancing loses because our rules are wrong, or because the
idea does not fit this cache.** By Friday we should be able to say which, with numbers from the board.

Audience: me and the supervisor. Technical detail lives in
[../performance/fix-plan-follow-the-paper-2026-09-16.md](../performance/fix-plan-follow-the-paper-2026-09-16.md)
and the work order [../coder/007-paper-aligned-eviction/TASK.md](../coder/007-paper-aligned-eviction/TASK.md).

**Starting point:** git tag `sbc-start-2026-09-16` (`8ed66ea`).

---

## Where we start from

Set balancing is **slower**: the same work takes 32–52% longer and goes to memory 2.5–7 times more often.
Two fresh board sessions on 15/16 September, with the new honest counters, put the hit rate at 67.2% with
the feature off and 35.0% with it on, and show **47% of the cache holding moved lines** (477 and 483 of
1,024) — the third independent measurement of the same 47%.

The moved lines themselves are **not** the problem: each one is reused about 74 times. The problem is what
they push out. Our rules protect a moved line and sacrifice the destination set's own lines, so a
destination ends up with 1 line of its own and 15 guests.

The paper does the opposite, and I confirmed that by reading the paper's own figures this week (its text
could not be copied out, so I read the rendered pages). Its cache throws out the least recently used line
whatever it is, a new guest replaces an older guest, and a pairing **ends** when its last guest leaves.
The paper's pairings hold about **2** moved lines. Ours hold 15 and never end.

---

## The plan

| | What | Who | When |
|---|---|---|---|
| 1 | Tag today's commit as the starting point | done | Tue |
| 2 | Write the decision doc and the work order | done | Tue |
| 3 | **Task 007 in RTL** — five commits: exact parked counts, then the four rule changes, built plainly with no new switch register | coder | Tue–Wed |
| 4 | Simulation gate after every commit — stress test 7/7, switch test, both shadow checkers quiet | coder | Tue–Wed |
| 5 | Build **one** FPGA image | Wed–Thu |
| 6 | One board session: fixed work, feature off then on, in one boot | Thu–Fri |
| 7 | Write the result up — which of the two answers it is | Fri |

Task 005's remaining two commits (the counter fixes and the interval sampler) are **postponed**, not
cancelled. The counters we need for this experiment already landed.

## What gets built

Four small rule changes. **No new control register** — each rule is simply built. Three of the four
*remove* logic, so the cache gets simpler, not more complicated:

1. **A moved line can be thrown out** like any other line, instead of being protected until a set has
   nothing of its own left.
2. **A move may take an older moved line's slot**, instead of always eating one of the destination's own
   lines.
3. **A pairing ends** when its last moved line is gone, and both sets go back in the pool.
4. **A pairing may park at most about 2 lines** in its partner — which is what the paper's own cache
   settles at, and what protects the destination's own working set now that we have no
   least-recently-used information to do it for us. This is the one number we may want to change later,
   so it is a build setting, like the existing thresholds.

One thing has to be fixed first: two counters that track moved lines lose events when two things happen
in the same cycle. One of them decides when a pairing ends, and a wrong count there means writing a line
back to the **wrong memory address**. It is also the number this whole experiment is judged on.

## What we run

**In simulation, after every commit** — this is where we see what each change did on its own:

| after | what we expect to see |
|---|---|
| exact counts | nothing moves except the parked count, which may have been reading too high |
| moved lines can be thrown out | parked falls, but my arithmetic says it stays near half |
| a move may reuse a slot | refused moves collapse |
| pairings end | the parked count **falls** as well as rises, for the first time |
| a cap of 2 | parked near 2 per pairing, and the hit rate recovers |

**On the board, one session:** one image, fixed work, feature off then on in the same boot. The old rules
are already measured twice (15/16 September, same board, same benchmark), so that is what we compare
against — no switch needed.

## How we decide, agreed before the runs

- The board run lands **within 2 points** of the feature-off hit rate while still serving moved-line
  hits → **our rules were wrong.** Fix confirmed, carry on, re-test on a second benchmark.
- Still **more than 10 points behind** → **the idea does not pay on this cache.** Stop tuning and write up
  the explained negative: no recency information, so a moved line cannot earn its place, and a miss here
  costs ~40 cycles against ~500 in the paper.
- Anything in between → partial. The next lever is a real replacement policy (see below), not more tuning.
  Separating which of the four changes helped would then cost extra images — that is the accepted price of
  not building a switch register.
- If the simulation shows "moved lines can be thrown out" alone landing near the baseline, my arithmetic
  is wrong. Stop and re-plan before the board run.

## Decided this week, worth recording

- **No switch register for the four changes.** Building both the old and the new rule side by side, with
  a mux to choose, would add more hardware than the fix removes. The comparison we actually need — feature
  off against feature on — already has a switch from task 006.

- **The heat-counter question is settled** (tracker M11). The paper's Figures 2 and 3 show the home set
  going up on a miss even when the partner search hits, and the partner going down. We already do the
  half that decides anything. No code change.
- **Do not add a "is the destination busy" check** before each move. The paper measured that and it was
  worse.
- **FIFO replacement was considered and rejected.** It throws out the oldest line, not the least useful
  one, so it does not protect a destination's working set. What would is **PLRU**, which rocket-chip
  already ships with its state in SRAM, and which is the paper's actual mechanism. That is the **next**
  step if this week's fix lands close but short — and it would mean re-measuring the baseline too, so it
  is a separate decision, not part of this week.

## Risks

- One of the four changes (ending a pairing) has a corner where a search already in flight could read a
  line that now belongs to a different set. It has a named net in the work order and must be an assert in
  the RTL, not a comment.
- Letting moved lines be thrown out puts a code path under real load that has only ever been tested by
  forcing it. That is what the simulation gate is for.
- Everything still rests on one run per setting for the older numbers. The two sessions on 15/16 September
  agreed to within 0.1% with the feature off, so repeatability looks good, but the "on" halves varied more.
