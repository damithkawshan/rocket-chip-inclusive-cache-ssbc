# REPORT 001 — Phase 3: close the reuse loop

**Coder:** Claude (coder session) · **Status:** `in progress`
**Last updated:** 2026-08-27

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

Plan as revised by **Amendment 1** (2026-08-26). Branch: `sbc-paper-aligned`.

| # | Commit | What | Landed? |
|---|---|---|---|
| 1 | `97b0d54` | Pinning (§1a/§1b/§1d/§1e/§1f) + §2b pulled forward | ✅ yes |
| 2 | `522c540` | **Displaced lines evictable** — drop `& nonDisplacedOH`. Single-variable experiment (A3/A4) | ✅ yes |
| 3 | | Building blocks (§2a directory secondary-search) | 🔄 in progress |
| 4 | | **Search + serve in place** ⭐ (was: swap + replay — dropped per A2) | |
| 5 | | Teardown | |

The commit-2/3 work written before Amendment 1 (the §2a directory secondary-search) is unchanged and
parked in the scratchpad; it simply moves from slot 2 to slot 3. Nothing was lost.

---

## Commit 2 — displaced lines evictable (Amendment 1 A3/A4)

**The change is one line.** [Directory.scala:153](../../../design/craft/inclusivecache/src/Directory.scala#L153):

```scala
-  val lfsrVictimOH   = victimWayOHLFSR & nonDisplacedOH
+  val lfsrVictimOH   = victimWayOHLFSR
```

Comments around it updated to match (the old ones asserted the quarantine as an invariant, which is
exactly the kind of stale comment that let the `s_wsafe` fix be deleted in a previous cleanup pass).

**What I deliberately did NOT change**, per A4:

- `evictableOH` still carries `!w.displaced`. That tier picks a **migration source**, and a displaced
  line can never be one — its address cannot be reconstructed, so it cannot be re-parked elsewhere.
- `displacedOH` and the last-resort Mux arms stay. With the mask gone, `lfsrVictimOH` is always exactly
  one-hot, so the last two arms are unreachable today; they remain as the structural guarantee that
  `victimWayOH` can never be zero and trip `assert(PopCount(victimWayOH) === 1.U)`.
- Nothing in destination eligibility or the ABORT-DST path.

**Baseline-exactness is preserved.** With SBC off no entry is ever `displaced`, so `nonDisplacedOH` is
all-ones and `victimWayOHLFSR & nonDisplacedOH === victimWayOHLFSR`. The removed term was the identity.

### Evidence this was worth doing (measured on the commit-1 build, before the change)

| | matmult | stress test |
|---|---:|---:|
| MIG-COMMIT (lines parked) | 11 | 19,126 |
| EVICT-DISPLACED-RECLAIM (lines freed) | **2** | 19,107 |
| still parked at end of run | **9 of 11** | ~19 |

The quarantine only bites when migration is rare. Under the stress test's hammer the sets fill fast
enough that the last-resort tier fires constantly and everything recycles — so **the stress test cannot
detect this class of bug**, and did not. On matmult, 9 of the 11 parked lines were never freed: they
could not hit and could not be evicted. Teardown could never have fired. A3 confirmed.

### Results — correctness gate: PASS

`migration_stress_test`, `VerilatorRocket8KL116KL2Config`:

- **7/7 PASS**, `*** PASSED *** Completed after 22,227,006 cycles`, **0 asserts**
- `COPY-DONE 17,356 == MIG-COMMIT 17,356`
- 1:1 invariant **OK** — pairings `1->0`, `5->7`, `6->3`; no source with two destinations, no set on
  both sides (commit 1's pairings were `1->3`, `5->7`, `6->0`; different destinations are expected,
  the DSS sees different timing)

| stress-test event | commit 1 (masked) | commit 2 (unmasked) | |
|---|---:|---:|---|
| OUTER-A | 160,896 | **147,202** | −8.5% |
| EVICT-NORMAL | 97,128 | 85,235 | −12.2% |
| MIG-COMMIT | 19,126 | 17,356 | −9.3% |
| EVICT-DISPLACED-RECLAIM | 19,107 | 17,347 | |
| ABORT-DST | 25,217 | 26,550 | +5.3% |
| MIG-DECLINE | 253 | 650 | +157% |
| HOT | 8,276 | 22,317 | +170% |

### A4's warning, measured: displaced lines do die young

`sbc_life.py` pairs each `COPY-DONE (dstSet,dstWay)` with the next `EVICT-DISPLACED-RECLAIM` of the same
way and uses the `C0:` trace as the clock. Commit-2 stress run, 17,347 matched park/reclaim pairs:

| lifetime (cycles) | share |
|---|---:|
| 0 .. 100 | **5.2%** |
| 100 .. 1,000 | 36.6% |
| 1,000 .. 10,000 | 57.6% |
| > 10,000 | 0.6% |

median **1,366 cycles**, mean 3,736, p10 203. Only 9 lines were still parked at the end of the run.

### ...but against the commit-1 baseline the effect is the **opposite** of what A4 feared

I re-ran the commit-1 build on the same test to get a true single-variable comparison (the original
`.out` had been overwritten). Verilator is deterministic, so the two are directly comparable:

| displaced-line lifetime | commit 1 (quarantined) | commit 2 (evictable) |
|---|---:|---:|
| median | **131 cycles** | **1,366 cycles** |
| mean | 109 | 3,736 |
| p10 | 0 | 203 |
| max | 2,047 | 10,028,150 |
| died within 100 cycles | **41.3%** | **5.2%** |
| died within 1,000 cycles | 100.0% | 41.8% |
| total cycles | 22,302,276 | 22,227,006 |

**Removing the mask made parked lines live 10x LONGER, not shorter.** A4 predicted the reverse, and the
reasoning behind the prediction was sound — random replacement gives a freshly-copied line no
protection. What that reasoning missed is what the quarantine actually left behind:

> With the mask, a displaced way could be victimized **only** by the last-resort tier — and that tier is
> `PriorityEncoderOH(displacedOH)`, which always picks the **lowest-indexed** displaced way. So once a
> set filled with parked lines, the same way was killed over and over, deterministically. Removing the
> mask hands displaced ways to the LFSR, which spreads eviction across all 8 ways.

So the quarantine was not protecting displaced lines. It was condemning them to a degenerate,
deterministic reclaim, and 41% of them died inside 100 cycles of being copied in — **the exact failure
mode A4 warned the change would cause was already happening before the change.** The lines that fixed
this ordering are not new: this is the same last-resort tier described in `CLAUDE.md` as verified only
under forcing. It is now measured on a real run, and it was worse than assumed.

No temporary-protection mechanism is needed. Do not build one.

### matmult — the run that decides commit 2

```
Matrix multiplication successful. Checksum: 29824
*** PASSED *** Completed after 15,699,736 simulation cycles      0 asserts
```

| `bringup_matmult` N=32 | cycles | vs SBC-off | OUTER-A | vs SBC-off |
|---|---:|---:|---:|---:|
| SBC **off** (`NoSbcConfig`) | 15,683,316 | — | 13,147 | — |
| SBC, pre-pinning (2026-08-25) | 22,278,686 | +42.0% | 122,144 | 9.29x |
| SBC + pinning (commit 1) | 19,088,476 | +21.7% | 64,923 | 4.94x |
| **SBC + pinning + evictable (commit 2)** | **15,699,736** | **+0.10%** | **13,146** | **1.00x** |

**The entire measured cost of SBC was the quarantine.** Not migration, not the copies, not the fence —
one `&` term in the victim-select. OUTER-A is now 13,146 against the no-SBC control's 13,147: one fewer
DRAM fetch than not having SBC at all. Per-set OUTER-A matches the control set for set (6,071 vs 6,037
on set 0, within 4% on every other set). Overhead is +0.10% of cycles, which at this scale is noise.

Migration behaviour is unchanged from commit 1 — **the same 11 migrations, the same 11 commits.** What
changed is what happens to the parked lines afterwards:

| | commit 1 | commit 2 |
|---|---:|---:|
| MIG-COMMIT | 11 | 11 |
| EVICT-DISPLACED-RECLAIM | **2** | **11** |
| still parked at end of run | **9** | **0** |
| median parked lifetime | — | 4,831 cycles |

### Why 9 stuck lines cost 4.94x the DRAM traffic

That ratio looks impossible until you look at where the 9 lines were. **Confirmed by a commit-1 rerun**
(identical to the original run event for event): commit 1's 11 migrations split **`5->0 x9`, `7->6 x2`**.
This L2 has **8 ways**. Nine lines were pushed into an eight-way set, and only two ever came back out.

A displaced way could neither hit nor be evicted except by the
last-resort tier, which needs `!nonDisplacedOH.orR`, i.e. *every* way displaced. It fired twice. Each
time it freed exactly one way, that way became native, `nonDisplacedOH` went non-zero, and the tier shut
off again — leaving set 0 cycling through **a single usable way**.

Set 0 was effectively direct-mapped for the rest of the benchmark, and set 0 is the hottest set in this
workload (6,000 of 13,000 DRAM fetches even with SBC off). That is the 4.94x.

This is the caveat `CLAUDE.md` already records — *"a 7/8-displaced set recycles its one native way until
Phase-3 spreading"* — reaching its worst case: **8/8 displaced.** It was written down as a known
limitation and it turned out to be the single dominant cost in the whole design.

### What commit 2 does NOT do

**Zero secondary hits, still.** SBC is now roughly free rather than expensive, but free is not the goal —
it still returns nothing. `SBC_SecHits` is tied to `0.U`
([SetBalanceUnit.scala:211](../../../design/craft/inclusivecache/src/SetBalanceUnit.scala#L211)) and
nothing reads a parked line. The payoff is still commits 3-4.

One thing commit 2 *does* buy for commit 4: parked lines now live a median of **4,831 cycles** on matmult
(min 134, p90 23,538) instead of being frozen forever or, on the stress test, killed inside 131. That is
a window a secondary search can actually hit in.

---

## Commit 3 — directory secondary search (§2a)

Written before Amendment 1 and unchanged by it; it only moved from slot 2 to slot 3. Additive only —
nothing drives `secondarySearch` yet, so the expectation is **event-for-event identical behaviour**.

`DirectoryRead` gains `secondarySearch`; `DirectoryResult` gains `secondaryHit` / `secondaryWay` /
`secondaryEntry` / `displacedOther`. `secHits` is the exact mirror of `hits` — displaced ways included,
native ways excluded — plus a write-bypass match, because a displaced entry written this cycle is not in
`ways` yet and missing it would let a refill install a second copy (the stale-twin hole). `secondaryEntry`
returns the whole entry, not just hit/way, because installing the line natively needs its `state`.

`Scheduler.scala` stops assuming the `dread` lane is always the migrate probe and routes
`preferInvalid` / `internalRead` / `preferEvictable` / `secondarySearch` from `schedule.dread.bits`.
The MSHR drives all four to their previous constants, so this is a no-op today.

### Two asserts added (TASK §5 trap 6, "Assert it")

```scala
assert (!ren2 || PopCount(secHits) <= 1.U, "SBC: two displaced copies of the same line in one set")
assert (!ren2 || (displacedValidOH & displacedOwedOH) === 0.U,
        "SBC: displaced way is dirty or client-held (its address cannot be reconstructed)")
```

The second is the read-side check for `displaced => clean + client-free`. Until now that invariant was
only asserted at *install* ([MSHR.scala:482](../../../design/craft/inclusivecache/src/MSHR.scala#L482)),
which is exactly why the Q1 hazard would have been silent. It now fires on the next read of the set.
It holds today (the install path guarantees it), so it costs nothing and turns a data-corruption bug
into a crash if commit 4 goes the serve-in-place route.

### Results — no behaviour change, proved rather than argued

`migration_stress_test` on commit 3 is **event-for-event identical to commit 2**:

| | commit 2 | commit 3 |
|---|---:|---:|
| cycles | 22,227,006 | 22,227,006 |
| OUTER-A | 147,202 | 147,202 |
| MIG-COMMIT / COPY-DONE | 17,356 / 17,356 | 17,356 / 17,356 |
| EVICT-DISPLACED-RECLAIM | 17,347 | 17,347 |
| ABORT-DST | 26,550 | 26,550 |
| pairings | `1->0 x17338`, `5->7 x7`, `6->3 x11` | identical |

7/7 PASS, **0 asserts** — including the new displaced-invariant assert, which held across 17,356
migrations and 17,347 reclaims. Every other event count matches too; the histograms are the same file.

The generated Verilog confirms it structurally: `secondarySearch` is tied to `false.B`, so `secHits`
folds to a constant and firtool deletes the entire secondary-search datapath (`grep secondary
Directory.sv` returns nothing, and the stale-twin assert is folded away as vacuously true). The
displaced-invariant assert *does* survive into the Verilog, because it does not depend on
`secondarySearch`. That is exactly the intended shape: new machinery costs nothing until commit 4
drives it.

### Prerequisite this surfaced for commit 4

[MSHR.scala:882](../../../design/craft/inclusivecache/src/MSHR.scala#L882) clears the partner latch on a
repeat allocate:

```scala
pairValidReg := io.pairInfo.valid && !io.allocate.bits.repeat
```

That gating is right for what commit 1 needed (trap 2: on a reload, `io.pairInfo` describes the *incoming*
request's set, not this MSHR's). But once commit 4 exists, a secondary pop would reload with no partner,
skip the search, and **refill from DRAM while a displaced copy of the same line is still parked** — a
stale twin, and the new `PopCount(secHits) <= 1` assert would catch it only on the next search.

The set does not change on a repeat, so the fix is to hold rather than clear:

```scala
pairValidReg := Mux(io.allocate.bits.repeat, pairValidReg, io.pairInfo.valid)
pairSetReg   := Mux(io.allocate.bits.repeat, pairSetReg,   io.pairInfo.bits)
```

Not applied here — it is inert until something consumes the latch, and it belongs with commit 4.

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

### Q1 — "serve in place" (A2) breaks `displaced => clean + client-free`, and it breaks it *silently*

Raised now rather than at commit 4 because it changes what commit 4 is.

Granting a line to the CPU sets its `clients` bit — an inclusive L2 has no choice, it must track the
L1 copy. So a displaced line that has been served in place is **client-held**. Three RTL facts then
collide with that:

| RTL | what it does |
|---|---|
| [MSHR.scala:482](../../../design/craft/inclusivecache/src/MSHR.scala#L482) | `assert(!mig_dir1 \|\| (!meta.dirty && (meta.clients & ~probes_toN) === 0.U), "migrate source must be clean+client-free")` |
| [MSHR.scala:487](../../../design/craft/inclusivecache/src/MSHR.scala#L487) | `assert(!(meta_valid && meta.displaced && !s_release), ...)` — a displaced victim may never be Released |
| [MSHR.scala:1038-1046](../../../design/craft/inclusivecache/src/MSHR.scala#L1038) | the reclaim branch drops a displaced victim with **no probe and no release** |

The install-side assert only guards the *install*, and the release-side assert only fires if something
tries to Release. **Neither catches the case that matters**: a client-held displaced line reaching the
reclaim branch is dropped with no probe, so the L1 keeps a block the L2 has forgotten. That is an
inclusivity violation with no assert behind it — it would show up as wrong data, not as a crash.

The reason none of this is currently a problem is the same reason the whole thing is safe today: a
displaced line's address cannot be reconstructed. `expandAddress(tag, physicalSet, off)` uses the set
the entry *sits in*, and a displaced entry sits in the wrong one, so any probe or release it generates
carries the wrong address.

**Two ways out. This is a design call, not mine:**

- **(A) Faithful serve-in-place.** Allow client-held (and eventually dirty) displaced lines, and
  reconstruct their address through the AT: `AT[D].assocSet` is the source set `S`, so the real address
  is `expandAddress(tag, AT[D].assocSet, off)`. This is sound *only* because commit 1's strict 1:1
  pinning guarantees D holds displaced lines from exactly one source — which is a good argument that
  pinning was the right prerequisite after all. Cost: probe and release paths must learn to take their
  set from the AT rather than from the entry's location, and the reclaim tier must stop being silent.
- **(B) Serve by repatriation.** On a secondary hit, install L natively in S's victim way and invalidate
  the displaced copy in D; the ordinary hit path then grants it. This is the original half-swap with the
  parking step removed — one internal block copy (the SCU already does exactly this move), two directory
  writes, and **the invariant is untouched**. Costs one copy per secondary hit; the paper's measurement
  that swapping "had a negligible impact on performance" is evidence that paying it is not fatal.

**The part of A2 I'd push back on: serve-in-place is not the simplification it looks like.** A2 says it
"deletes the single most complex piece of the original plan." It deletes the swap, but it adds
AT-based address reconstruction to the probe path *and* the release path, and it lifts an invariant that
three separate places in `MSHR.scala` currently lean on. Option (B), by contrast, is the Phase-2
migration datapath run backwards — `s_dread`/`w_dread` for the search, `s_copy`/`w_copy` for the move,
two directory writes — all of which already exist and are debugged. On net I expect (B) to be *less*
new RTL than (A), not more.

Note also that the paper's argument for leaving the line in place ("it goes up to L1, so later accesses
hit there") is weakest exactly where A2 already flags it — **our L1 is 4 lines.** The copy falls out
almost immediately, and under (A) every re-reference pays another partner search, whereas under (B) it
is a native hit.

**My recommendation is (B)**, on the grounds that it reuses proven machinery and does not touch a
load-bearing invariant. But the paper is your evidence base, not mine, and A2 was an explicit
instruction — so I will not choose. I am continuing with commit 3, which is needed either way, and will
hold at the commit-4 boundary.

---

## Numbers

### Owed from Amendment 1 §A5 — the pinned run's **completed** figures

`bringup_matmult` N=32, `VerilatorRocket8KL116KL2Config`, run to `*** PASSED ***` (not windowed):

| run | cycles | vs SBC-off | OUTER-A | vs SBC-off |
|---|---:|---:|---:|---:|
| SBC **off** (`NoSbcConfig`) | 15,683,316 | — | 13,147 | — |
| SBC, pre-pinning (2026-08-25) | 22,278,686 | **+42.0%** | 122,144 | **9.29x** |
| SBC + pinning (commit 1, `97b0d54`) | **19,088,476** | **+21.7%** | **64,923** | **4.94x** |

**Pinning halved the overhead.** It is still 21.7% worse than not having SBC at all, which is expected:
migration without reuse is pure cost, so the only way commit 1 could help was by migrating less — and it
did (11 commits vs 1,737). This is movement toward the bottom row for the wrong reason. It is not a win,
it is confirmation of the diagnosis.

Checksum 29824 on all three runs. 0 asserts.

### ⚠️ Two things I told you on 2026-08-26 that were wrong

1. **"The 2026-08-25 reference figures are from a truncated 10M-cycle window."** They are not. Both
   reference runs reach `*** PASSED ***` (22,278,686 and 15,683,316 cycles). The table above is sound.
2. **"`variables.mk:258` appends `+max-cycles=$(TIMEOUT_CYCLES)` after the caller's `SIM_FLAGS`, so my
   `+max-cycles` was ignored."** Also wrong — the caller's value does win. My commit-1 run was never
   capped; it ran the full 19,088,476 cycles and passed.

What actually happened: the previous session exited while the sim was running, which killed the
`spike-dasm`/`tee` pipeline and froze the **instruction trace** in `bringup_matmult.out` at cycle
7,942,442. The simulator kept going and its final `*** PASSED *** Completed after 19088476` line still
reached the file. I read the truncated trace and inferred a cap that was not there. The windowed
0..7.9M comparison in the Verdict section above is still internally valid (both sides windowed the same
way) but is superseded by the completed-run table.

### Commit-1 full-run SBC event totals (matmult)

| event | count | | event | count |
|---|---:|---|---|---:|
| OUTER-A | 64,923 | | MIG-START | 13 |
| EVICT-ASSESS | 64,859 | | MIG-COMMIT | 11 |
| EVICT-NORMAL | 64,844 | | COPY-DONE | 11 |
| HOT | 5,665 | | ABORT-DST | 2 |
| ADVICE-MIG | 17 | | EVICT-DISPLACED-RECLAIM | **2** |

`EVICT-DISPLACED-RECLAIM = 2` against `MIG-COMMIT = 11` is the A3 quarantine, measured: **9 of the 11
parked lines were still sitting in their partner sets when the benchmark ended.** They could not hit and
could not be evicted, so teardown could never have fired. Amendment 1 A3 is confirmed on this workload.

For contrast, on `migration_stress_test` the same commit-1 build shows `EVICT-DISPLACED-RECLAIM = 19,107`
against `MIG-COMMIT = 19,126` — i.e. under the stress test's hammer, the last-resort reclaim tier *does*
recycle essentially every parked line. The quarantine only bites when migration is rare, which is exactly
the regime matmult is in. That is worth knowing: **the stress test cannot detect this class of bug.**

---

## Reproducing

Use `make run-binary` exactly as TASK §6 says. Two notes worth having:

- **Pass `TIMEOUT_CYCLES=` as a make variable, not `SIM_FLAGS=+max-cycles=`.** Both work, but only the
  make variable is unambiguous — `variables.mk:258` appends its own `+max-cycles` to `SIM_FLAGS`, so the
  command line ends up with two of them.
- **Do not pipe the simulator's stderr through `awk`/`grep` to filter it live.** I tried that to get
  cycle-stamped `[SBC]` lines cheaply. With `+verbose` the stderr stream is the full instruction trace,
  and the filter becomes the bottleneck: the simulator blocks on the pipe and runs roughly 100x slower.
  It looked exactly like a hang — 250 `[SBC]` lines in six minutes, zero growth, cycle stuck at 54 — and
  I nearly reported the commit-2 RTL change as a deadlock. The same run under `make run-binary` reached
  stress-test case 5 in 45 seconds. `spike-dasm` keeps up; a shell filter does not. Post-process the
  `.out` afterwards.

The lifetime analysis in this report comes from `sbc_life.py` (in the session scratchpad), which pairs
`COPY-DONE dstSet/dstWay` with the next `EVICT-DISPLACED-RECLAIM srcSet/srcWay` for the same way and
uses the interleaved `C0: <cycle>` trace lines as the clock.

### One caveat on every trace-derived number in this report

**`[SBC]` printfs and the instruction trace are the same stream.** Chisel `printf` is gated on the
harness's `verbose` condition, so `+verbose` is what turns the `[SBC]` lines on — running without it
produces a completely empty stderr (I checked: 505 bytes of DRAMSim banner and nothing else). There is
no way to get a cheap SBC-only log.

The `.out` files stop mid-trace before the harness's own `$finish`: commit-1 matmult at cycle 7,942,442
of 19,088,476, commit-2 at 6,248,072 of 15,699,736, the SBC-off control at 6,239,863 of 15,683,316. In
every case the last traced instruction is the post-`exit()` spin (`c.j pc + 0` at `0x80006c9a`), the
`.log` holds the complete program output (8,650 bytes, byte-identical across runs), and the last `[SBC]`
event precedes the spin — so all cache activity is captured. Two independent commit-1 runs, hours apart
under very different machine load, produced **byte-identical 295,540,538-byte** `.out` files, which
rules out a load-dependent truncation race.

So the event counts are complete for the program's execution, and all four runs are counted the same
way. The **cycle** figures are the harness's own `Completed after N simulation cycles` and are unaffected.
