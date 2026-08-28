# TASK 001 — Phase 3: close the reuse loop

**Opened:** 2026-08-26 · **From:** thinker · **Report into:** `REPORT.md` in this directory

**Specs:** [../../spec-sbc-phase3-prereqs.md](../../spec-sbc-phase3-prereqs.md) (Part 1 + Part 2) ·
[../../phase-3.md](../../phase-3.md) (design SSOT) ·
[../../diagram.md](../../diagram.md) **Part B** (the swap sequence, step by step)

---

## 0. The point of all of this

Read this section before the technical detail. It is what decides the judgement calls.

**The research goal:** let an overloaded cache set borrow capacity from an underused one, so effective
associativity rises without paying for real associativity.

**The only thing that creates value is a secondary hit** — a line evicted from a hot set, parked in its
partner, and later *found there and served* without a DRAM trip. Everything else in SBC — migration,
the DSS, saturation counters, thresholds, pinning — is machinery that exists to make secondary hits
possible. Payoff per miss ≈ `f × (M − C)`: `f` = secondary hit rate, `M` = DRAM cost, `C` = swap cost.

**Where we actually are:** 100% of the machinery, **0% of the payoff.**

| `matmult` N=32, 2026-08-25 | SBC on | SBC off |
|---|---:|---:|
| checksum | 29824 | 29824 ✅ correct |
| cycles | 22,278,686 | 15,683,316 (**+42%**) |
| DRAM fetches | 122,144 | 13,147 (**9.29×**) |
| secondary hits | **0** | — |

We migrate 1,737 lines and every one is pure cost: a displaced line **cannot serve a hit** (the
Directory excludes displaced ways from hit detection by construction), so each migration buys an
internal copy plus a cache way that can never help.

**Therefore: this task builds the whole loop, not a piece of it.** Pinning on its own adds restriction
with still no reuse — and with only 8 sets it degenerates fast (see §1). The value appears only when
`pair → spill → search → hit → serve` closes.

**Success is not "it compiles" or "migrations went up". Success is `SBC_SecHits > 0` with correct data,
and cycles moving back down toward 15,683,316.**

---

## 1. Why pinning alone was rejected as a standalone task

Each pairing consumes **two** of our 8 sets (one source, one destination).

- 4 pairings uses every set.
- No unpaired set left → no new pairing can form.
- Without teardown the map is **frozen for the rest of the run**.

So pinning alone would freeze after ~4 commits, with nothing reading the association. That is why
**teardown is in scope here** (commit 4) rather than deferred — without it the experiment stops
adapting almost immediately at this geometry.

---

## 2. The loop, in order

`diagram.md` **Part B** is the authoritative sequence. In words:

1. Demand miss in set **S**. Directory says MISS (displaced ways can't hit).
2. Ask the AT: is S a paired source? → partner **D**.
3. **Hold the memory Acquire.** Read D with the real tag, matching *displaced* ways.
4. Three outcomes:
   - **Not found** → drop the reservation, `SecMiss++`, normal DRAM fetch. Costs ~2 cycles.
   - **Found, permissions too weak** (copy is BRANCH, request needs TRUNK) → invalidate the copy
     (it is clean, so a silent drop is legal), normal DRAM fetch.
   - **Found, permissions OK** → **the swap**.
5. The swap: read L from `(D, dWay)` and S's victim V from `(S, vWay)`, write them crossed. Two
   dir-writes: V becomes displaced in D, L becomes native in S.
6. **Swap-then-replay** — re-run the request's lookup. It now hits natively in S, and the *unmodified*
   hit path grants it. No new grant or permission datapath.
7. `SecHits++`. If D now holds none of S's lines → teardown.

**Half-swap:** if S's victim V is dirty or client-held it cannot become displaced (`displaced ⇒ clean`
is load-bearing — a displaced line sits at the wrong physical set, so its address cannot be
reconstructed for a writeback). Release V normally and still bring L home. The pool shrinks by one;
that is expected and is the `f* = p` balancing term in `phase-3.md`.

---

## 3. Build order — four commits, stop and report after each

| # | What | Spec | Checkpoint |
|---|---|---|---|
| 1 | **Pinning** — split advice/destination queries, DSS `remove`, asserts | §1a, §1b, §1d, §1e, §1f | 1:1 invariant holds; stress 7/7; matmult checksum 29824 |
| 2 | **Building blocks** — directory secondary-search mode + MSHR `pairInfo` latch | §2a, §2b | compiles; **no behaviour change** (nothing consumes them yet); stress 7/7 |
| 3 | **Search + swap + replay** ⭐ | `diagram.md` Part B, `phase-3.md` §Serving | **`SecHits > 0`**, checksum still 29824 |
| 4 | **Teardown** | `phase-3.md` §Pairing ends only at teardown | pairings form → dissolve → re-form; not frozen after 4 |

**Commit 3 is the one that matters.** Commits 1 and 2 are enabling work with no observable payoff —
do not spend days polishing them.

### Scope boundaries

- **Do NOT build a read-only "would have hit" detector before the swap.** It was considered and
  rejected: without the swap, stale copies linger and poison the count (`phase-3.md`, "always-use, not
  detect-first"). Go straight to the swap.
- **Do NOT touch destination eligibility, `dstEvictable`, or the ABORT-DST path.** Out of scope. Until
  commit 3 lands, a *higher* migration success rate makes the machine slower, not faster.
- **Do NOT re-enable `s_verify`.** Explicitly out of scope.
- Teardown v1 is the simple rule only: when a read of D shows zero displaced bits, erase both AT
  entries. The background "cold-source drain" in `phase-3.md` step 4 is a later task.

---

## 4. Deltas since the specs were written

### `7ad8020` added a DSS reject block list — it is **not** the same as §1e's `remove`

| port | set by | meaning | effect | lasts |
|---|---|---|---|---|
| `reject` (exists) | ABORT-DST | "no room in there right now" | `blocked(i) := true` | temporary — cleared when all are blocked, or by a 1024-cycle timer |
| `remove` (§1e, build it) | commit | "this set is now paired" | `valid(i) := false` | permanent, until teardown |

They cannot substitute for each other. `remove` fires only on success, so it can never rotate away a
set that *always* fails (set 7 in the stress test: 100% of offers, 100% refused, zero commits).
`reject` is designed to wear off, so it can never hold the 1:1 invariant. Build both.

⚠️ **§1e's stated rationale is wrong — keep the code, ignore the reason.** It claims remove-on-commit
stops a paired set blocking the pool; commits are far rarer than rejects (1,737 vs 8,571), so it could
never do that. Its real job is the 1:1 invariant.

⚠️ **Teardown must return the set to the DSS.** §1e also gates DSS updates with `!at(tapSet).valid`.
When teardown clears the AT entry, confirm the set genuinely re-enters the candidate pool on its next
tap — otherwise the pool drains permanently and migration stops for good.

---

## 5. Traps — each of these has already cost a debugging cycle

1. **Fast-path timing (§1a).** `dstValid` comes from the `migrating` register, which rises the cycle
   *after* the decision, so on the fast path `migrantOH` is all-zero exactly when the destination is
   taken. The spec's `decidingOH = Mux(anyMigrating, migrantOH, directoryFanout)` exists for this.
   Do not collapse it back to one query port.
2. **`assocQuery` must be gated to fresh allocates (§2b):** `request.valid && alloc`. The reload path
   allocates with `status.bits.set` (the MSHR's own prior set), not `request.bits.set` — ungated,
   `pairInfo` latches the wrong set on a secondary pop.
3. **Combinational loops around `dstClaim`.** Everything feeding the destination offer must be
   registered. Keep the loop-freedom note at
   [Scheduler.scala:~507](../../../design/craft/inclusivecache/src/Scheduler.scala#L507) true, and
   update it if you change the inputs.
4. **A gate added in one place and missed in its sibling.** `707445c` was exactly this — a migration
   gate added to `retire` but not `reload`. Grep every consumer before moving on.
5. **The swap needs two BankedStore reads and two writes** with the existing `copy_safe` / `copy_wsafe`
   hazard gates. **Do not reorder BankedStore priorities** — tested and rejected (`bug-fix-log.md` Q3);
   the order is load-bearing for protocol deadlock-freedom.
6. **`displaced ⇒ clean` is load-bearing.** If the swap ever installs a dirty displaced line, its
   address becomes unreconstructable and the reclaim tier's silent drop loses data. Assert it.
7. **The `[born → gate]` window** (`bug-fix-log.md`, open) widens with every extra cycle between MSHR
   birth and the destination fence. The search adds a directory read on that path. If the dst-collision
   assert reappears, this is the first suspect — report it, do not work around it.

---

## 6. Build and run

```bash
./sw/scripts/run_sbc.sh                       # stress test (edit config block at top)

./sw/scripts/compile_bringup.sh matmult 32    # the real gate
make -C $CY/sims/verilator run-binary \
  BINARY=sw/build/bringup_matmult.riscv \
  CONFIG=VerilatorRocket8KL116KL2Config       # then ...NoSbcConfig for the control
```

- Checksum is printed to `bringup_matmult.log` (stdout). `bringup_matmult.out` is the spike-dasm'd
  instruction trace. Grepping the wrong one reports "no result" on a perfectly good run.
- **An SBC-OFF run still emits ~39k `[SBC]` lines** — `OUTER-A`/`EVICT-ASSESS`/`EVICT-NORMAL` are gated
  by `sbcDebug`, not `enableSetBalancing`. To prove SBC is off, grep `MIG-`.
- `CLEAN=1` in `run_sbc.sh` runs `make clean` and wipes `sims/verilator/output/`. Copy out anything you
  want to keep first.

⚠️ **Never `git checkout -- <file>` to undo your own edit.** Large parts of this project are
uncommitted working-tree changes; a whole-file revert destroyed every SBC config in
`RocketConfigs.scala` on 2026-08-25. Reverse your own lines with an exact-match edit.

---

## 7. Acceptance criteria

**Correctness — hard gate, every commit:**

- [ ] `migration_stress_test` 7/7 PASS, 0 asserts
- [ ] `matmult` N=32 completes `*** PASSED ***` with **checksum 29824** (externally verified against
      the SBC-off control — if this changes, the swap is corrupting data)
- [ ] `COPY-DONE` == `MIG-COMMIT`; no assert from §1d / §1f / trap 6

**The 1:1 invariant — after commit 1:**

- [ ] In `COMMIT by (src->dst)`: every source maps to **exactly one** destination, and **no set appears
      on both sides**. Paste the table.

**The payoff — after commit 3. This is the task's reason to exist:**

- [ ] **`SBC_SecHits > 0`** — add the counter if it is not already wired to the regmap
- [ ] Report `SecHits` vs `SecMiss` — that ratio is `f`, the term the whole design turns on
- [ ] Report cycles and `OUTER-A` against **22,278,686 / 122,144** (SBC today) and
      **15,683,316 / 13,147** (SBC off). Moving back toward the SBC-off numbers is the goal.

**Expected, not failures — report, do not chase:**

- Migration count drops after commit 1 (pinning restricts destinations). Correct behaviour.
- `MIG-DECLINE` rises — decline-and-skip working when a partner is unavailable.
- Half-swaps occur whenever S's victim is dirty or held. Count them (`SBC_HalfSwaps`); `phase-3.md`
  risk #1 says `p` is directly observable as `fullSwaps / (fullSwaps + halfSwaps)`.

---

## 8. When to stop and ask

- **After commit 3, if `SecHits` is 0 or near-0.** Do not start tuning. That result would mean the
  parked lines are never re-referenced before being reclaimed, which is a finding about the workload,
  not a bug to fix. Report the numbers and stop.
- **If the 4-pairs-freeze (§1) makes commit 3 unmeasurable** — e.g. the hot sets never manage to pair.
  Report the pairing map; the fix is a policy decision, not yours to make.
- **If any spec line contradicts the RTL.** The spec has been wrong before — three defects were found
  in review on 2026-08-24, and §1e's rationale is wrong in this very document. **Trust the RTL, report
  the contradiction.**
- If closing the loop seems to require touching destination eligibility or ABORT-DST. That is out of
  scope and §0 explains why.

---
---

# Amendment 1 — 2026-08-26 — the base paper changes commits 2-4

**Trigger:** we went back to the source paper (Rolán, Fraguela, Doallo, *Adaptive Line Placement with
the Set Balancing Cache*, MICRO'09). Two of its design decisions differ from ours and both bear
directly on what you reported in commit 1. **Commit 1 stands as landed. Everything after it changes.**

Your report is answered in §A5 below.

---

## A1. Work has moved to a branch

```
sbc-paper-aligned      <- work here
  9f7cdd5 docs: SBC research record through 2026-08-26
  6c273c5 sw: port bringup-bench to Chipyard bare metal
  97b0d54 SBC Phase 3 (1/4): pinned 1:1 association     <- your commit
```

`set_migration_refactored` is the known-good line. This branch is the experiment. If it improves the
numbers we merge it back; if not we abandon it and nothing is lost.

---

## A2. Finding 1 — the paper does NOT swap. Drop swap-then-replay.

Paper §2.4, verbatim:

> *"the SBC does not swap lines to return them to their original set when they are found displaced in
> another set... Experiments performing swapping of lines in the SBC to return displaced lines to their
> original set under a hit proved that this policy had a negligible impact on performance."*

On a secondary hit the paper **serves the line where it sits** and leaves it there. Their reasoning:
the line goes up to L1, so later accesses hit there — shuffling L2 buys nothing.

**Consequence for commit 4 (was 3):** build **serve-in-place**, not swap-then-replay.

- No reading two blocks, no writing them crossed, no two directory writes, no replay.
- Find the line in the partner set, grant it to the CPU from there, leave it displaced.
- This deletes the single most complex piece of the original plan.

⚠️ **Where their reasoning is weaker for us:** their L1 is 32 KB. **Ours is 256 B — 4 lines.** The copy
falls out of L1 almost immediately, so we will re-pay the partner search more often than they did (they
measured only ~10% of accesses needing one). That is a cost, not a correctness problem. Build the simple
version, measure the second-search rate, and we revisit swapping only if that rate is bad.

---

## A3. Finding 2 — our displaced lines are quarantined. The paper's are ordinary.

| | Paper | Us |
|---|---|---|
| Replacement policy | LRU | LFSR / random ([Directory.scala:141](../../../design/craft/inclusivecache/src/Directory.scala#L141)) |
| Displaced line on insert | inserted **MRU** — a head start | — |
| Displaced line afterwards | **competes normally**, ages out | **excluded from every victim tier** except last-resort reclaim |

The quarantine is this line, [Directory.scala:151](../../../design/craft/inclusivecache/src/Directory.scala#L151):

```scala
val lfsrVictimOH = victimWayOHLFSR & nonDisplacedOH
```

The `& nonDisplacedOH` masks displaced ways out of normal victim selection.

**Why we think this is the root cause of several problems at once:**

- Sets clog with lines that cannot hit — measured: `displaced` share of destination rejects went
  1.0% → **22.9%** on matmult.
- **Teardown can never fire.** The rule is "dissolve when the partner holds no displaced lines"
  (paper §3.4). That requires displaced lines to actually be evictable. Ours are not — which is very
  likely why your two pairings froze.

**This is now commit 2, and it is a single-variable experiment. Do it before anything else.**

---

## A4. Revised commit plan

| # | What | Status |
|---|---|---|
| 1 | Pinning | ✅ landed `97b0d54` |
| **2** | **Displaced lines evictable** — drop the `& nonDisplacedOH` mask. **Measure alone.** | ⭐ do first |
| 3 | Building blocks — §2a directory secondary-search (§2b already landed in commit 1) | |
| 4 | **Search + serve in place** (was: search + swap + replay) | ⭐ the payoff |
| 5 | Teardown | |

**Commit 2 is a standalone experiment — report before starting commit 3.** Compare against:

| | cycles | OUTER-A |
|---|---:|---:|
| SBC today (2026-08-25) | 22,278,686 | 122,144 |
| SBC **off** | 15,683,316 | 13,147 |

Moving toward the bottom row is the win. Correctness gate unchanged: stress 7/7, matmult checksum 29824.

⚠️ **Watch for the opposite failure.** The paper gets away with this because LRU + MRU-insertion gives
a displaced line a head start. We use **random replacement**, so removing the mask gives it *no*
protection — it may be evicted almost immediately after being copied in, making every migration
pointless in a new way. **If you see copies being reclaimed within a few hundred cycles, say so.** The
fix would be some temporary protection, but do not build that pre-emptively — measure the plain version
first.

⚠️ The last-resort reclaim tier and `displacedOH` must **stay**. They are what stops `victimWayOH` going
to zero and tripping the `PopCount` assert. You are removing a mask from one tier, not deleting a tier.

---

## A5. Answers to your report

- **F3 — removing both `src` and `dst` from the DSS: correct, and an important catch.** With
  `dssEntries = 8` and 8 sets, every set is a permanent candidate and the hottest-candidate eviction
  path never runs, so a paired source would have blocked `dssOK` forever. Endorsed, keep it.
- **F2 — following §1a over §2a(f): right call**, and your reasoning is the reason. §2a(f) predates the
  2026-08-24 rewrite of §1a.
- **F1, F4** — agreed, no action.
- **Your decision to continue rather than stop: right at the time.** Teardown genuinely was the designed
  release valve. The paper then showed the valve could never open, which you could not have known.
- **Your lockout diagnosis is accepted and it corrected mine.** I predicted a capacity freeze
  ("4 pairings uses all 8 sets"); you showed 4 sets were still free and the real mechanism is that a
  destination may never source, so the workload's dominant source was locked out two migrations in.
  That distinction is why A3 matters — with evictable displaced lines and teardown, the lock releases.

**Still owed from you:** the pinned run's **final** cycle count and total `OUTER-A`. The comparison in
your report is windowed to cycles 0–7.9M, so we cannot yet say whether pinning moved us toward the
SBC-off baseline. Please add the completed-run numbers to `REPORT.md`.

---

## A6. Calibration — what the paper actually achieves

Useful for judging whether our numbers are sane. From paper §5.1, §7.3:

| Measure | Paper (dynamic SBC) |
|---|---:|
| Lines displaced **per association** | **2.15** |
| Secondary hits **per displaced line** | 3.29 |
| Second searches that hit | 47.7% |
| Association requests satisfied | ~35% |
| Accesses needing a second search | 10.2% |
| Miss-rate reduction / IPC gain | 12.8% / 5.25% |

Two things to take from this:

1. **Pairings are tiny and short-lived** — about two lines each, then dissolved. Our mental model of
   long-lived pinning was wrong, and the "4 pairings freeze the cache" worry mostly evaporates once
   pairings actually turn over. They only froze because ours cannot dissolve (A3).
2. **A ~35% association success rate is normal**, not a bug. Our 83% ABORT-DST is not as far off as it
   looked. Do not chase it.

---

# Amendment 2 — 2026-08-28 — the serve-path corruption is diagnosed. Stop patching the copy.

Written after reading your commit-4 section. **Do not continue down the `s_verify` / SCU-hazard
lead.** I believe I have found the defect by reading the RTL, and it is not in the copy path.

Read B0 → B3 before touching any file. B3 is a gate: it costs one grep on a log you already have,
and if it comes back the other way, this whole amendment is wrong and the copy path is back on.

(Optional: the same argument as diagrams is at `tmp.md` in the repo root. It adds nothing this
amendment does not say — skip it if you prefer prose.)

---

## B0. First, three things you were right about and I was not

- **A4's warning was wrong, and your measurement is what showed it.** I predicted removing the
  quarantine would let copies be reclaimed instantly under random replacement. You measured the
  reverse — median lifetime 131 → 1,366 cycles, died-within-100 41.3% → 5.2% — and found the real
  cause: `PriorityEncoderOH(displacedOH)` always killed the lowest-indexed way. The quarantine was
  condemning displaced lines, not protecting them. **Accepted. Do not build temporary protection.**
- **Q1: your recommendation stands and is already the instruction** (`3284b24`) — serve by
  repatriation, do not serve in place. Your push-back on A2 was correct: serve-in-place deletes the
  swap but adds AT-based address reconstruction to two paths and lifts an invariant three sites lean
  on. That was a better read of the RTL than mine was of the paper.
- **The owed completed-run numbers arrived and settled it.** +42.0% → +21.7% → **+0.10%**. One `&`
  term. That result stands and is not affected by anything below.

---

## B1. The defect — an MSHR can latch **another set's partner**

The pairing lookup is keyed to the request **waiting at the input port**, broadcast to every MSHR,
and latched by any MSHR whose allocate is not a tag-`repeat`.

| what | where | why it is wrong |
|---|---|---|
| lookup keyed to the port | `Scheduler.scala:584-585` | `assocQuery.bits := request.bits.set` |
| answer broadcast to all MSHRs | `Scheduler.scala:329-330` | one wire, every MSHR sees it |
| latched on any non-repeat allocate | `MSHR.scala:999-1000` | `repeat` is a **tag** test |
| reload keeps the MSHR's old set | `Scheduler.scala:313-314` | set unchanged, tag changed → `repeat = false` |
| `alloc` ≠ "this request is allocating" | `Scheduler.scala:208` | it only means "no MSHR owns that set" |

**`repeat` is a tag comparison being used to decide whether the MSHR's SET changed.** On a reload
the set never changes — only the tag does. So a secondary pop with a new tag re-reads a pairing it
had no business re-reading.

### The cycle

1. MSHR-A owns set **5**, retires, pops its next queued miss for set 5 with a **different tag**.
   → `allocate.bits.set = 5` (its own), `repeat = false`, `lb_tag_mismatch = true`.
2. That same condition sets `mshr_uses_directory_assuming_no_bypass` (`Scheduler.scala:352`), so the
   request waiting at the port — say for set **1** — **cannot allocate** (`request_alloc_cases`
   false, `Scheduler.scala:362`).
3. But `alloc` is still **true** for set 1 (no MSHR owns it). So
   `assocQuery.valid = request.valid && alloc` fires **for set 1**.
4. AT says set 1 is a paired source with partner set **3**. `pairInfo` broadcasts `{valid, 3}`.
5. MSHR-A, sitting on set 5, executes the latch: `pairSetReg := 3`.
6. MSHR-A's non-repeat reload does its own directory read → the plan block
   (`MSHR.scala:1071`) → `searching := true` (`MSHR.scala:1237`) with the poisoned value.
7. MSHR-A now searches **set 3** for set 5's tag.

**The two events are not independent.** The thing that blocks set 1 from allocating is the very
reload that triggers the re-latch. This is not a rare alignment — it is caused.

### Why the search then matches

`set_addr(s,t) = DRAM_BASE + t*512 + s*64`. The `t*512` term sits entirely **above** the set-index
bits, so **tag = f(t) only, identical in all 8 sets**. A displaced line parked in set 3 for set 1
with tag `t` passes every rule `secHits` applies: tag match, valid, displaced, not the bypass way.
It is a real, valid, clean parked line — **just the wrong line**.

---

## B2. Why this explains your report exactly

| your finding | this |
|---|---|
| serve OFF passes, serve ON fails | erasing a foreign parked copy is **harmless** — `displaced ⇒ clean + client-free`, so dropping it loses nothing and we then fetch correctly from DRAM. **Serving** it hands the CPU another address's data. The invariant that makes SBC safe is exactly what hid this. |
| both failures are wrong reads, zero asserts | every assert checks **shape** (one-hot, clean, not-self). None checks **identity**. |
| `case_full_dirty_dst` finds **cold-set** lines wrong | that cold set is full of dirty lines so it is never a *destination* — but it can be a paired *source*. Its own reads then search a wrong partner and get served a foreign line. |
| the partner fence did not fix it | the fence protects the **right** set. You were reading the **wrong** one. Your diagnosis was sound; it was aimed one level too low. |
| `6→0` 189 hits / 14,357 migrations vs `1→3` and `5→7` near 100% | pairings behaving completely differently is the signature of a mis-keyed lookup |

---

## B3. GATE — confirm before writing any RTL. No re-run needed.

From the commit-4 stress log you already have:

1. `grep MIG-COMMIT` → the distinct `(srcSet, dstSet)` pairs. These are the **true** pairings.
2. `grep SEC-HIT` → the distinct `(set, partner)` pairs. These are what the searches **actually used**.
3. Compare the two sets of pairs.

**Any `SEC-HIT` whose partner is not that set's committed partner is a direct sighting of this bug.**

Report the two lists in `REPORT.md` either way.

- If they disagree → proceed to B5.
- If every SEC-HIT pair matches a committed pair → **this amendment is wrong.** Say so plainly, and
  the SCU serve-path lead you were on goes back to the top. Do not implement B5 on my say-so.

---

## B4. Blast radius — what else this breaks

**No TileLink protocol violation.** I checked each candidate:

- Messages are all well formed; only the payload is wrong, which no monitor sees.
- The cancelled outer Acquire is safe — `a.valid` carries `&& !searching`, so nothing can have left
  before the answer arrives.
- No inclusivity violation from the erase — `displaced` implies client-free.
- SBC-off baseline untouched (everything is behind `enableSetBalancing`).

**But four other consequences, and the first is a hang:**

1. **`partnerBusy` can deadlock.** Your no-deadlock note says *"under 1:1 pinning a destination is
   never a source, so the MSHR holding the partner never waits on us."* A **wrong** partner voids
   that premise. MSHR-A on set X wrongly points at Z; MSHR-B on set Z wrongly points at X. Each
   waits for the other. Neither can retire — `sec_ready = !searching && s_sinval` gates **both**
   `reload` and the final writeback, and `!searching` also blocks the outer Acquire. Nothing breaks
   the cycle. **There is no assert behind this — only the `sbcDebug`-gated `SEC-STUCK` printf.**
   ⚠️ You attributed one commit-4 hang to the `d_ready` sibling-gate bug. That fix was clearly
   right on its own terms, but please re-check whether it accounted for **every** hang you saw.
2. **The fence protects the wrong set.** `secSet` (`MSHR.scala:328-329`) feeds `dstSetConflict`, so
   an innocent set is stalled while the set actually being read is unfenced.
3. **Stale twin reopens.** We install a native copy of A5 in set 5 while a *legitimate* parked copy
   of A5 may still sit in set 5's **true** partner. Two entries, one address, different data.
   `PopCount(secHits) <= 1` is per-set and cannot see it. If the CPU writes the native one it is
   written back to DRAM under A5 — permanent.
4. **Every commit-4 SecHit/SecMiss number is void.** Polluted three ways: searches that should have
   happened did not (`pairValidReg` wrongly false), searches ran against the wrong set (false
   SecMiss), and weak-permission rejects destroyed foreign parked lines (depressing future hits).
   **Do not quote `f` from the current build.**

### What is NOT broken — checked, not assumed

- **1:1 pinning itself is fine.** It is enforced by the SBU, whose `destQuery` **is** correctly keyed
  to the deciding MSHR's own set (`Scheduler.scala:540-541`). So **the parking is correct — only the
  lookup is wrong.** The fix touches no parked data.
- **Commits 1–3 stay data-safe.** There `pairValidReg` fed only an assert and `pairSetReg` fed
  nothing live. `522c540` and its +0.10% result stand.

### One weaker guard than it looks

`MSHR.scala:915` — `assert(!dstClaim.valid || !pairValidReg || dstClaim.bits === pairSetReg)` — is
skipped whenever `pairValidReg` is wrongly **false**, which is the common mis-latch outcome. It has
been passing partly by vacuity. **If it starts firing after the fix, that is a second real finding,
not a regression.** Report it, do not silence it.

---

## B5. The fix

**Take Option B.** Option A is the fallback if B turns out to fight the elaboration loop-freedom
rules (see the LOOP FREEDOM note at `MSHR.scala:249`).

- **Option B — key the query to the asker.** Delete the broadcast latch. Key the pairing query to
  the MSHR the same way `destQuery` already is (`Scheduler.scala:540-541`), and latch `pairSetReg`
  at the moment `searching` is armed. This makes the wrong-set class **unrepresentable** rather
  than fixed once. Note `status.bits.set` is already correct at the directory-result cycle, because
  `request` is latched at allocate.
- **Option A — minimum.** Add a `fresh` bit to the allocate bundle. The two paths are already
  distinct (`Scheduler.scala:416` fresh vs `Scheduler.scala:313-314` reload). Latch the pairing
  **and** `migAdviceValidReg` only when `fresh`; **hold** otherwise.

**Fix `migAdviceValidReg` (`MSHR.scala:994`) in the same change either way.** It has the identical
defect and has been live since Phase 2 — a reloading MSHR could latch another set's "this set is
hot" and migrate out of a cold set. Data-safe, so nothing caught it, but it is noise in every
migration number we have.

**Both options also fix a mirror-image loss:** today a same-set reload with a new tag **clears** a
valid pairing, so the MSHR skips a search it should have done. Expect SecHits to go **up**.

**Add one identity assert.** Every existing check tests shape; none tests identity. At search time,
assert the partner really is this set's partner, read **live from the AT**, not from the latch.
That turns any recurrence into a crash instead of silent data. Keep it behind
`enableSetBalancing`.

---

## B6. What NOT to do

- **Do not rebuild `s_verify`.** The copy moves the right bytes from the place it was told to read —
  a copy verifier would have **passed**. It is an expensive detour. (The `CLAUDE.md` note that made
  it look like the lead is about a different failure mode; it stays parked.)
- **Do not strip or bless the partner fence yet.** It was built on a diagnosis now known to be
  wrong, and while the partner is wrong it is *actively harmful* (B4.1). Leave it in, fix the
  latch, then re-test with and without it and report which way it goes. That is the right order and
  it was right of you to hand the decision over rather than guess.
- **Do not touch destination eligibility** or the `ABORT-DST` rise you noted. Still out of scope,
  still correctly logged rather than acted on.

---

## B7. Acceptance for commit 4 (replaces §7 for this commit only)

1. **B3 answered in `REPORT.md`** — the two pair lists, and which way they came out.
2. `migration_stress_test` **7/7 PASS, 0 asserts**, including `case_full_dirty_dst` and
   `case_reaccess_migrated`.
3. `bringup_matmult` checksum **29824**.
4. Every `SEC-HIT set=X partner=P` in the log has `P` equal to `X`'s committed partner.
5. `SEC-HIT` count reported before and after the fix — it should **rise** (B5, mirror-image loss).
6. Cycles and `OUTER-A` for matmult against the 15,683,316 / 13,147 SBC-off control. This is the
   first build that can plausibly beat it. If it does not, say so plainly — a null result here is
   information, not a failure.
7. Say explicitly whether the `MSHR.scala:915` 1:1 assert stayed quiet after the fix.

Then stop and report. Teardown (commit 5) is still after this.

---

# Amendment 3 — 2026-08-28 — close this task. The fix moves to 002.

Amendment 2's diagnosis turns commit 4 from "finish the serve path" into "fix a latching defect that
predates commit 4, then finish the serve path". That is a different work order, and this task has
already absorbed two amendments and four commits. It stops here.

**What you owe to close 001 — nothing new, just the record:**

1. Fill in the **Verdict** section of `REPORT.md`. It should say, in your own words: commits 1–3
   landed and are measured; commit 4 is written, working, and **blocked** on the partner-latch
   defect; commit 5 (teardown) was never started.
2. **Leave the uncommitted commit-4 tree exactly as it is.** `MSHR.scala`, `Scheduler.scala`,
   `SetBalanceUnit.scala` and the `sbcDebug` printfs all carry forward. Task **002** owns them. Do
   not revert, do not commit, do not clean up.
3. Nothing else. No re-runs, no new measurements.

**Amendment 2 stays here as the record of what was found and when.** Task 002 acts on it and cites
it rather than repeating the derivation.

Teardown (commit 5) becomes task **003**, opened after 002 closes.

---

## Amendment 4 — Q1 IS REVERSED (2026-08-28)

Recorded here because this is where the decision was made, and the task-directory rule is
append-only.

**Q1 resolved to option B (serve by repatriation). That is now reversed. Task 003 builds option A —
the paper's serve-in-place.**

Not because the Q1 reasoning was sloppy. Both of its premises were true when written; one of them
stopped being true, and the other turned out to be a config artifact:

1. *"Serve-in-place needs AT-based address reconstruction and lifts an invariant three sites lean
   on."* Still true — but the cost was assessed as a **directory format change** (`CLAUDE.md:222`,
   `destination-side-blocker.md:180-207`: +log2(sets) bits **per way**). Under the strict 1:1 pinning
   that commit 1 of *this task* shipped, the home set is one value **per set** — which is exactly
   what `ATEntry.assocSet` already stores. The expensive part evaporated, and it was this task that
   made it evaporate.
2. *"Our L1 is 4 lines, so a served copy falls out immediately and every re-reference pays another
   partner search."* An artifact of `VerilatorRocket8KL116KL2Config` (4KB L2 / 8 sets / **256B L1**),
   not of the design. The paper's L1 is 32KB.

The decisive argument was neither of those, though. It is that `displaced ⇒ clean` is what forbids
migrating dirty victims, and in any real workload most victims are dirty. Repatriation cannot lift
that; serve-in-place is the prerequisite for it.

→ continues in `../003-serve-in-place/`
