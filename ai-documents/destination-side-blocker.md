# Destination-side blocker — why 98% of migrations abort, and what to do about it

**Date:** 2026-08-24 · **Status:** analysis complete, decision recorded · **Owner:** thinker
Companions: [phase-3.md](phase-3.md) (always-use SSOT) · [phase-2.md](phase-2.md) ·
[bug-fix-log.md](bug-fix-log.md) · [diagram.md](diagram.md) (STEP 4→5 is the code path here)

---

## Where this came from

Two fixes landed on 2026-08-24, in this order:

1. **`internalRead`** — the migration destination probe was reaching the directory observation tap, so
   every probe counted a phantom miss against the set it probed. **84% of all HOT events were the cache
   heating sets by looking at them.** Correct fix, but it cut commits 424 → 14.
2. **DSS destination-reject block list** — `internalRead` exposed a real defect it had been masking.
   See "The coldness metric was wrong" below. Commits recovered to **749**.

| | baseline `707445c` | `internalRead` alone | + DSS reject |
|---|---:|---:|---:|
| Result | 7/7 PASS | 7/7 PASS | **7/7 PASS, 0 asserts** |
| Committed | 424 | 14 | **749** |
| Commit rate | 0.64% | 0.03% | **1.70%** |
| MIG-START | 66,278 | 45,896 | 44,060 |
| ABORT-DST | 99.36% | 99.97% | 98.30% |
| Copies / commits | 424 / 424 | 14 / 14 | 749 / 749 |
| Rejects by dst set | 8 sets | **100% set 7** | even over 2/3/5/7 |
| Displaced-reclaim fired | 380 | 0 | **701** |

Run: `migration_stress_test`, stock `VerilatorRocket8KL116KL2Config`, log dir
`sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2Config_c15-dss-reject/`.

### The coldness metric was wrong

Saturation counts **miss-pressure**. A set whose working set fits its ways **hits every time**, so its
counter decays to 0 and it is permanently "coldest" — while being exactly the set with **no spare way**.
Set 7 received 100% of offers and refused 100% of them.

> **Low miss-pressure ≠ has room.** This is the single most important thing learned this day.

The phantom heat had been the *only* thing rotating the destination choice. Removing it locked the DSS
onto one set forever. Fix: a destination that refuses a migration sets a per-slot `blocked` bit in
`DSS.scala` and is skipped by the coldest-compare; cleared when all candidates are blocked, plus a
free-running 1024-cycle retry timer as the liveness net.

**Trap recorded:** clearing `blocked` *only* on all-blocked deadlocks. The consumer also requires
`coldestLevel < T_lo`, so if the coldest set is blocked and nothing else is cold enough, nothing is
offered → nothing is refused → the clear never fires → migration off forever. Hence the timer.

**Trap recorded:** the penalty goes to the **DSS only, never to `sat`**. In the buggy baseline the heat
landed in `sat`, which also drives HOT/source selection — that is why set 0 produced 19,251 fake HOT
events and became a migration *source*. Keep them separate.

**Why the previously-specified fix would not have worked:** [spec 1e](spec-sbc-phase3-prereqs.md) fires
the DSS `remove` port on **commit**. Rotation that only fires on success cannot rescue a set that never
succeeds (14 commits vs 45,882 rejects). Easy trap to re-enter — do not.

---

## What the 98.3% actually is

The destination probe (`dread`, `preferInvalid` + `preferEvictable`) asks the directory for **one** way
in the destination set that is free, or **clean AND client-free AND not displaced**. It already returns
the best way it can find, so a reject means **all 8 ways failed**.

| Reject cause | Count | Share of 43,311 | Addressable? |
|---|---:|---:|---|
| `clients` only — line is **clean**, flagged as held by L1 | 20,084 | **46.37%** | **Yes — the bit is stale** |
| `dirty` only | 20,345 | 46.97% | Needs a writeback |
| `dirty` + `clients` | 2,466 | 5.69% | Needs a writeback |
| `displaced` only | 416 | 0.96% | Our own earlier migrations |
| *(accept: free)* | 2 | 0.00% | — |
| *(accept: clean evictable)* | 747 | 1.70% | — |

---

## The `clients` bit is stale — and it is a *config flag*, not a hardware problem

### The bits are stale — mechanism, plus a supporting (not conclusive) count

⚠️ **Correction, 2026-08-24:** an earlier draft called the count below "proof by arithmetic". It is not.
The 6.25% is a share of **all 64 L2 lines**; the 52.07% is a share of **destination probe rejects** —
and the probe returns the *best* way in the set, so a `clients` reject means all 8 ways failed. Those are
different populations and the comparison is suggestive, not conclusive. The real argument is the
**mechanism**: a grant sets the bit and a silent drop never clears it, so the bits can only accumulate.
The population fraction has never been measured directly — worth a counter if the run is ambiguous.

- The Rocket **I$ is not a TL-C client.** `ICache.scala:154-158` declares `TLMasterParameters.v1` with
  **no `supportsProbe`**, and `Parameters.scala:196/218` builds the client mask from
  `inner.client.clients.filter(_.supports.probe)`. The I$ therefore never occupies a `clients` bit.
- So **100% of `clients` bits come from the Rocket D$**, which in this config is
  `WithL1DCacheSets(2) × WithL1DCacheWays(2)` = **4 lines**.
- L2 holds 64 lines, so at any instant **at most 4/64 = 6.25% of L2 lines can truly be held.**
- The `clients`-present share of rejects is **52.07%** — far above that, but see the caveat above: it is
  a different population, so treat it as *strongly suggestive* of staleness, not as a measurement of it.

### Root cause — `silentDrop` is on by default

| Fact | Location |
|---|---|
| `acquireBeforeRelease: Boolean = false` (the default) | `rocket/HellaCache.scala:42` |
| `def silentDrop: Boolean = !acquireBeforeRelease` | `rocket/HellaCache.scala:54` |
| With `silentDrop`, the D$ **never enters `s_voluntary_release`** — no Release on clean eviction | `rocket/DCache.scala:810, 820` |
| `require(silentDrop \|\| acquireBeforeRelease)` — **a tautology**, since `silentDrop == !acquireBeforeRelease`. There is one knob, not two; this require can never fire | `rocket/DCache.scala:106` |
| **Our L2 already handles voluntary Release correctly** and clears the client bit on `toN` | [MSHR.scala:401-404](../design/craft/inclusivecache/src/MSHR.scala#L401-L404) |

The L1 drops clean lines and never tells L2. The `clients` bit records *"did L2 ever grant this to L1"*,
not *"is L1 holding it now"*. Rocket has a switch for this and **it is off by default**.

No Chipyard config fragment sets `acquireBeforeRelease` today — we would write one.

---

## Decision — try the config flag before building any RTL

### Option A (DO FIRST): `acquireBeforeRelease = true`

Flip it, re-run, measure. The D$ then sends `Release(BtoN)` on every clean eviction, L2 clears the bit,
and the `clients` test becomes **true instead of guessed**.

- **Zero new RTL in this generator.** Existing, upstream-tested path on both sides.
- Fixes the bit for **all** victim selection, not just migration — better baseline evictions too.
- **It is a config flag**, so it is reversible and measurable in one run with no design risk.
- If rejects collapse → hypothesis validated, and we may not need the probe at all.
- If they do not → the bits were real, and the destination probe would not have helped either. Either
  way we learn the answer for the price of one run.

⚠️ **Research-framing cost — weigh this before it reaches a paper.** If SBC *requires*
`acquireBeforeRelease = true` to function, that mode is a **precondition of SBC** and its traffic must be
charged against SBC's benefit, not treated as free platform setup. The control config keeps the
comparison internally fair, but the claim narrows to *"SBC + ABR vs baseline + ABR"*. Sharper still:
truthful `clients` bits improve L2 victim selection **everywhere**, so the baseline may get faster on its
own — read the control run for *absolute* performance, not just pass/fail, or part of ABR's win will be
misread as SBC's.

**Costs, honestly:** one Release per clean L1 eviction is a lot of C-channel traffic; the flag also
changes eviction ordering (acquire before release, `DCache.scala:174/607`); it perturbs the baseline, so
`VerilatorRocket8KL116KL2NoSbcConfig` must be re-run with the same flag for a fair comparison; and it
does nothing for the I$ (which, per above, never held a client bit anyway).

### Option B (only for the residual): destination-side probe-then-migrate

The same move `cbb3837` made on the source side, applied at [diagram.md](diagram.md) STEP 5: when the
best destination way is clean-but-flagged-held, **probe that line out of L1, then re-decide**. Nothing
returned → the way is genuinely free → proceed. Data returned → it really was dirty → abort as today.

Safe in principle (overwriting a destination way *is* an eviction, and a normal eviction of a clean
client-held line already requires exactly this probe), but: **two probe sequences inside one MSHR** —
which is the shape behind BUG-2 and the destination-collision bug — plus added demand-miss latency, a
new dirty-on-probe abort path, and L1 evictions we may then not use.

**Ceiling if it converts as well as the source side did (0.018% → 78.4%): commits ≈ 15–20k, from 749.**

⚠️ **The 46% is a floor on the addressable share, not a ceiling.** Truthful bits change the victim
**picker**, not just the accept test: `preferEvictable` currently skips ways whose stale bit says held,
so in sets where the probe returned a *dirty* way (46.97% of rejects) the picker may now find a clean
client-free way instead. The reachable share is above 46%, possibly most of the 98%.

---

## The two "dirty" questions — different answers

### Dirty at the **destination** (the 53%) — ⛔ DO NOT BUILD

Not merely complex. **Economically backwards.**

| | Today (abort) | With dirty-destination eviction |
|---|---|---|
| Source victim V (clean) | dropped, **costs nothing** | copied to set d |
| Destination line W (dirty) | untouched | **written back — a real memory write** |
| Net | 1 memory read (the demand) | 1 read + **1 write**, and W refetches later |

We would pay a real memory write to avoid a free one — a clean victim costs nothing to drop, which is
the entire reason we only migrate clean lines.

**And it is worse than neutral.** Set d looked cold because of *low miss-pressure*; a **full** set with
low miss-pressure is one whose lines **hit constantly**. W is therefore a well-behaved, frequently-hit
line. We would be evicting a hot line to preserve one we were about to throw away.

### Migrating dirty lines from the **source** — ⚠️ SECTION CORRECTED 2026-08-29

> **The "directory format change" cost below is WRONG and this section is superseded.** It predates
> strict 1:1 pinning. Under pinning, all displaced lines in a row come from exactly one source set, so
> the home set is one value **per set** — which `ATEntry.assocSet` already stores
> (`SetBalanceUnit.scala:26`). No per-way directory field is needed, and the address IS reconstructable
> via `expandAddress(tag, AT[row].assocSet)`. Verified against the RTL in coder task 003 Stage 0.
> Current design: `ai-documents/coder/003-serve-in-place/`. The reasoning below is kept for the record.

#### (superseded) Original assessment — 🔵 Phase 4, not now

Breaks the `displaced ⇒ clean` invariant, which is load-bearing:

- A displaced line sits in the **wrong physical set**, so `expandAddress(tag, physicalSet)` reconstructs
  the wrong address. That is why the MSHR **silently drops** a displaced victim today — it cannot
  address it to write it back.
- Clean → dropping is free and safe. This is the safety net the whole design rests on: every awkward
  case degrades to "drop it, refetch from memory".
- Dirty → may never be dropped; must be written back, but there is no way to compute where to.

**Fix requires a directory format change:** store the home set index in every entry, `+log2(sets)` bits
**per way** (3 here, ~11 in a realistic 2048-set L2), plus a reconstruction path in every consumer
(eviction, displaced-reclaim tier, probes, coherence lookups). And the safety net inverts: every failure
mode becomes "write back correctly or lose data".

**Upside is real** (saving a dirty line saves a writeback *and* a future refetch — roughly double the
payoff per migration) — but **it is not the current bottleneck.** `MIG-PROBE-DIRTY` was **51** this run;
probe-then-migrate already solved the source side.

### Ranking

| Option | Complexity | Payoff | Verdict |
|---|---|---|---|
| `acquireBeforeRelease = true` | **Config flag** | up to the full 46% | **Do first** |
| Destination probe (residual `clients`) | Medium — 2nd probe FSM | the rest of the 46% | Only if A falls short |
| Dirty at destination (53%) | High | **Negative** | ⛔ Never |
| Dirty source lines | Very high — directory format | Real, but later | Phase 4 |

---

## ⚠️ Strategic risk — SBC may have nowhere to put anything (added at user request, 2026-08-24)

**We found 2 free ways in 44,060 probes.**

Every migration is displacing a **resident** line, not filling a gap. SBC's premise is that some sets
have spare capacity. **In this workload, none do.** Two independent lines of evidence now point here:

1. The coldness metric failure — the sets that look coldest are the ones whose lines hit most, i.e. the
   *fullest* ones. "Cold" and "has room" are not the same property, and we have no signal for the second.
2. The dirty-destination economics — taking a way from a cold set means evicting a well-behaved line.
   That cost is real even in the clean case (a future read), just smaller.

**This must be considered a live explanation if the performance numbers do not materialise.** It is not
a bug to be fixed; it is a statement that the evaluation setup cannot demonstrate the effect.

Contributing factor — **the config is mislabelled**
(`chipyard/generators/chipyard/src/main/scala/config/RocketConfigs.scala:175`):

| | L1 each | L2 |
|---|---|---|
| config **name** says | 8 KB | 16 KB |
| code **comment** says | 2 KB ("4 sets × 8 ways") | 16 KB |
| **actual arguments give** | **256 B** (`Sets(2) × Ways(2) × 64B`) | **4 KB** → **8 sets × 8 ways = 64 lines** |

With only 8 sets and `dssEntries = 8`, the DSS holds **every set at once** — so the "select good
candidates out of many sets" half of SBC is **structurally unexercised**. For a set-balancing paper that
is a validity gap, not a cosmetic one. Every abort/commit rate ever quoted for SBC comes from this
geometry.

**Owed, and now upgraded from "nice to have" to "required before any performance claim":**
a config with enough sets that the DSS must choose, and `matmult_float` (or another write-heavy,
imbalanced workload) instead of a correctness test. Per [sbc-eval-platform] the real perf evaluation is
VCU118 + Linux + SPEC2017 — Verilator proves function only.

---

## Hard evidence that Phase 3 is mandatory, not optional

**HOT events went 4,113 → 27,334** once migrations actually started completing (real heat — the tap is
clean now). Displaced lines occupy ways but cannot serve hits, so **migration without secondary search
makes the cache measurably worse.**

> Today every migration is pure cost. The payoff exists only once a lookup can follow the AT to the
> displaced copy and turn a miss into a hit.

That is the strongest argument yet for finishing Phase 3 — and the strongest argument against tuning
migration rate any further than it takes to give Phase 3 something to work with.

---

## Recommended order

1. **`acquireBeforeRelease = true`** — one config fragment, one run. Measures the exact size of the
   stale-bit problem with zero design risk. Re-run the NoSbc control with the same flag.
2. **Destination probe** — only for whatever `clients` rejects survive step 1.
3. **Bigger-set config + write-heavy workload** — before any performance claim (see the strategic risk).
4. **Pinned 1:1 association** — the Phase-3 prerequisite. Deliberately *after* the rate work: pinning a
   source to a partner that refuses 98% of the time just locks in the failure.
5. **Phase 3 always-use** — the only step that turns migration from a cost into a benefit.
