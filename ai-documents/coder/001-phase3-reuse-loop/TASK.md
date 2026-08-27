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
