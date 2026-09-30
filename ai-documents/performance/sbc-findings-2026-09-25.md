# Where the Set-Balancing Cache actually stands — findings, 2026-09-25

**This is the document to read before planning any further SBC work.** It replaces guesswork about why
SBC does not win with a measured rule, and it changes what should be built next.

Audience: me and the supervisor. The experiment records are
[board-calib-envelope-2026-09-25.md](board-calib-envelope-2026-09-25.md) (the sweep) and
[coder/012 REPORT](../coder/012-reland-destination-eviction/REPORT.md) (the RTL work).

---

## 1. The finding, in one box

> **Set balancing pays exactly while a hot set's overflow fits the empty ways of its partner.**
>
> `overflow ≤ spare`, i.e. **`MP ≤ 2·ways − HP`**
>
> — where `MP` is the hot set's working set, `HP` the cold set's, `ways` the associativity.
>
> **Confirmed on 78 of 80 measured board points (98%).** Inside the region: up to **−41% cycles** and
> **−85% memory reads**. Outside it: up to **+14% cycles** and **+24% reads** — and *half the plane is
> outside*.

Our real workload (omnetpp, 64 KB 16-way L2) is far outside that region. That single fact explains
every disappointing SBC number we have recorded since August, and it is now measured rather than
hypothesised.

---

## 2. What we did this week

| | Work | Outcome |
|---|---|---|
| 1 | Re-land task 009's destination eviction safely, in stages (task 012 C1+C2) | Built, sim-green, **board gate passed 4/4** where 009's image was 0/4 |
| 2 | Directed test for the one dangerous untested path (dirty **guest** write-back) | 9 events, all to the correct address, 0 asserts |
| 3 | Add the missing experiment axis to `l2_miss_calib` (`-P`) | The "empty ways" side of the model became measurable for the first time |
| 4 | 80-point board sweep of (empty ways × overflow), migration OFF vs ON | The rule above, 78/80 |

---

## 3. Task 012: the fix works, and it does not pay

C2 lets a migration evict a **dirty, client-free** destination line by writing it back, instead of
giving up. On the board:

| | R0 baseline `201ebae` | R1 candidate `97d0162` |
|---|---:|---:|
| completions | 3 of 3 | **4 of 4** (009: **0 of 4**) |
| seconds | 1029, 1030, 1029 | 1032, 1032, 1033, 1032 |
| `dstAbortDirty` | 48,258 | **0** |
| total destination aborts | 81,150 | ~16,000 |
| hit rate | 70.56% | 70.50–70.56% |
| memReads / memWrites | 297.7 M / 62.68 M | 299.0 M / 62.90 M |

**It did exactly its job** — dirty-only aborts eliminated, 80% fewer aborts overall, and it is stable.
**And it bought nothing:** the hit rate did not move, and time and traffic are ~0.3% on the wrong side.

The reason is arithmetic: destination aborts were **1.8% of attempts**. A fix confined to 1.8% of a
mechanism cannot move the result. We spent tasks 009, 011 and 012 on the destination side; the sweep
shows the destination side was never where the performance was.

---

## 4. The sweep: when does set balancing pay?

`l2_miss_calib` touches exactly one line per set per step, so a set's page count is both its working set
and its reuse distance. That makes the design two numbers, and the prediction falsifiable.

![operating envelope](figs-calib-envelope-2026-09-25/envelope.png)

The black staircase is the formula, not a fit. The measured cliff moves with the empty ways, landing on
the prediction four rows out of five:

| empty ways | rule: win while overflow ≤ | measured wins up to | first loss |
|---:|---:|---:|---:|
| 16 | 16 | **16** | 20 |
| 12 | 12 | **12** | 14 |
| 8 | 8 | **8** | **9** |
| 4 | 4 | **4** | **5** |
| 0 | 0 | 2 | 3 |

**The losing half is not neutral.** Outside the envelope, migrations flood the partner and evict the
lines that were hitting there: worst point **+13.6% cycles, +23.9% reads**.

---

## 5. Two beliefs this overturns

**(a) "Hits per parked line" is not the figure of merit.** I had reported that omnetpp's 0.47 hits per
park (against a break-even of 1.0) was the gap. It is not: among *winning* points that number ranges
**0.19 to 295**, and 0.47 sits comfortably inside it.

**(b) The gain is mostly not from reusing parked lines.** Across the winning points, **77% of the gain
is primary hits** — the *source* set no longer thrashing once its excess is moved out — and 23% is
serving parked lines.

![where the gain comes from](figs-calib-envelope-2026-09-25/gain-source.png)

So the mechanism's value is *relieving the hot set*, not *finding lines in the partner*. That reframes
the design: the second search is a cost to be minimised, not the feature.

---

## 6. Why the paper gets 13% and we do not

The paper (Rolán et al.) reports 9–13% miss-rate reduction on a **2 MB, 8-way, 4096-set** L2. We run
**64 KB, 16-way, 64 sets** — 64× fewer sets and twice the associativity.

- The paper's own text warns about our regime: *"the larger (and fewer) the sets are, the more similar
  or balanced their working sets tend to be"*, with diminishing returns.
- It also notes that doubling associativity is equivalent to merging two sets — so our 16-way cache
  **already contains** part of what SBC would deliver on an 8-way one.
- Its premise is that low saturation implies **underutilised lines**. In a 2 MB cache holding dead data
  that inference is fair. In a fully warm 64 KB cache a low-saturation set is simply one that *hits*;
  its 16 ways are full of useful lines. **Low saturation ≠ free space**, and our DSS cannot tell the
  difference.

The paper is not wrong; we are running it outside its assumptions.

---

## 7. What to build next — and what not to

**Do not build 012 C3** (evicting a client-held destination line). It is the remaining 45% of a slice
that is under 2% of attempts, and it carries the hold-and-wait shape that hung the board on task 009.
The sweep says the whole destination-side question is not where the performance is.

**Build the throttle (tracker L6).** The cache cannot currently tell a `-p 19` workload from a `-p 48`
one: it migrates whenever a source is hot and a destination looks cold, and in the second case it
actively destroys performance. The throttle now has a specified job with a measured target:

> stop migrating when the source's overflow does not fit the destination's free ways.

Candidate signals we already have, cheapest first:

| signal | already in RTL? | note |
|---|---|---|
| destination way was **invalid** (`dstFree`) vs **overwritten** (`dstEvictable`) | yes, both exist | the direct test for "real free space" — currently not distinguished in the decision |
| secondary hit rate of a pairing | counters exist | lags; a pairing must first do damage |
| parked-line drop rate (`dispDrop`) vs secondary hits | counters exist | ~50/50 on omnetpp — a live "this is not paying" signal |

**One-line experiment worth doing first:** gate migration to fire *only* into a genuinely invalid way
(`dstFree`), as a runtime bit. That enforces the paper's precondition directly, needs no new state, and
is testable in simulation before any bitstream.

**Geometry is the other lever.** A 128 KB or 256 KB L2 moves us toward the paper's regime. Note B7-2:
the 256 KB image currently fails Vivado DRC with a combinational loop, unexplained.

---

## 8. What this does not say

- Every sweep point is **one run**, not repeated. The rule is supported by 80 points agreeing, not by
  any single point's precision.
- The sweep uses a **synthetic** workload built to have a clean hot/cold split. It maps where the
  mechanism *can* pay; it does not predict how often real programs land there.
- `l2_miss_calib` is static — hot sets stay hot. It says nothing about phase changes or pairing teardown.
- The board went unresponsive after the 012 V7 series, ~6 h idle after a clean finish. Cause unknown and
  **still open**; it did not occur during a measured run.

---

## 9. Follow-Up: Associativity & Capacity Scaling (2026-09-25 / 2026-09-26)

Following the 64 KB 16-way sweep, we evaluated two further geometries:
1. **64 KB / 8-way / 128 sets** ([board-calib-envelope-8way-64kb-2026-09-25.md](board-calib-envelope-8way-64kb-2026-09-25.md))
2. **1024 KB (1 MB) / 8-way / 2048 sets** ([board-calib-envelope-8way-1mb-2026-09-26.md](board-calib-envelope-8way-1mb-2026-09-26.md))

| Metric | 64 KB / 16-way (64 sets) | 64 KB / 8-way (128 sets) | 1024 KB / 8-way (2048 sets) |
|:---|:---:|:---:|:---:|
| **Bitstream Commit** | `97d0162` | `c8cd4bf` | **`962fa05`** |
| **Grid Points** | 80 | 65 | 65 |
| **Cliff Formula** | $MP \le 32 - HP$ | $MP \le 16 - HP$ | $MP \le 16 - HP$ |
| **Cliff Invariance** | Confirmed ($4/5$ rows) | Confirmed ($4/5$ rows) | Confirmed ($4/5$ rows) |
| **Best Read Delta** | **−85.5%** | **−93.1%** | **−99.7%** |

> **Bitstream commits corrected 2026-09-30.** The 1 MB column said `c3faa05`, which cannot build a 2048-set cache — `Control.scala` was dirty in that build. The true fingerprint is `962fa05` (tag `sbc-1mb-8way-linux-booted-2026-09-26`). The 64 KB 8-way column said `c3faa05` too; that image was built at `c8cd4bf`, whose `design/` is identical, so the figures are unaffected.
| **Best Cycle Delta** | **−41.2%** | **−46.5%** | **−48.5%** |
| **Worst Cycle Penalty** | **+13.6%** | **+4.9%** | **+3.3%** |

### Key Scaling Findings:
- **Cliff Invariance:** The cliff formula $\text{overflow} \le \text{spare} \iff MP \le 2\cdot\text{ways} - HP$ is strictly invariant with cache capacity, shifting predictably as $HP$ varies.
- **Asymmetry Diminishes:** As cache capacity grows from 64 KB to 1 MB, the downside risk of being outside the envelope collapses from +13.6% cycles down to just +3.3%.
- **Pseudo-Associativity:** At 8-way, set-duality pairing allows PLRU to pool 16 ways across paired sets, providing benefits even for moderate overflows beyond the rigid capacity boundary.

