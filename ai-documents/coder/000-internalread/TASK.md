# Coder handover — Phase 3 Part 1 (pinned 1:1 association)

> **Filed retroactively 2026-08-28.** Written 2026-08-24 as `handover-phase3-part1.md`, before
> the `coder/` exchange convention existed. Moved here for consistency; content unchanged.


**Spec:** [spec-sbc-phase3-prereqs.md](spec-sbc-phase3-prereqs.md) — that document is the contract.
This one is the working order: what to do first, what to verify at each stop, and the traps that have
already cost time in this code.

**Read before you write anything:**

1. [spec-sbc-phase3-prereqs.md](spec-sbc-phase3-prereqs.md) — the whole thing, including the appendix
2. [diagram-stale-destination-query.md](diagram-stale-destination-query.md) — why Part 1a exists at all
3. [phase-3.md](phase-3.md) §"Decision — decline-and-skip" — the policy you are implementing

Do **not** start from the July version of the spec. It was rewritten 2026-08-24 and the old Part 1 is
kept in the appendix only as a warning.

---

## Ground rules (non-negotiable)

| Rule | Why |
|---|---|
| **Comments: 1–3 lines max** | Long comment blocks make this file unreadable. Long rationale goes in `ai-documents/` or the commit message, never inline. |
| **Everything gated by `enableSetBalancing`** | With SBC off the RTL must stay bit-exact with upstream. Use Scala `if` where possible so nothing elaborates. |
| **Do NOT reorder BankedStore priorities** | Tested and rejected. The order is load-bearing for protocol deadlock-freedom. |
| **Do NOT delete `s_wsafe` in `SetCopyUnit.scala`** | It has been silently deleted by a cleanup pass once already. It is the Bug-B fix. |
| **Keep `sbcGateStallCycles` and `sbcForceDstSet`** | Both are compile-time gated, zero hardware at defaults, and both are the only way to reproduce specific bugs. |
| **Package stays `sifive.blocks.inclusivecache`** | Upstream compatibility. |
| **MMIO flush must never be used with SBC on** | Documented platform constraint, not a bug to fix. |

**The debugging rule that matters most here:** if SBC-era logic misbehaves, `git show <pre-SBC>:file`
and compare **every consumer** of the signal you touched, *before* you start instrumenting. The last
bug in this subsystem was a Phase-2 gate added in one place and missed in the sibling line beside it —
a 30-second diff, found only after several 10-minute instrumented sim cycles chasing a wrong theory.

---

## The work, in four commits

Keep them separate. Each one is independently verifiable, and commit 1 needs a clean before/after
measurement that a squashed commit would destroy.

```
Commit 1  internalRead            Phase-2 defect fix. Small. Measure before and after.
Commit 2  abort tally             No RTL. A parser change plus one run.
Commit 3  pinned 1:1 association  The main work. Spec Part 1.
Commit 4  building blocks         Spec Part 2. Compile-only verification.
```

---

### Commit 1 — `internalRead` (spec Part 2a(e))

**This is a Phase-2 defect, not Phase-3 work.** It is first because it is small, because it is
confirmed, and because it changes the numbers commit 2 measures.

What is wrong today: the migration destination probe is a normal directory read, so it (a) tag-matches
against `tag = 0` and can override the victim choice, and (b) reaches the saturation tap, counting a
**phantom miss on the destination set**. That last one is self-defeating — pick the coldest set, probe
it, make it look hotter.

Files: `Directory.scala`, `Scheduler.scala`, `MSHR.scala`. Exact edits in the spec.

**Before you commit, run this twice** — once on the current tree, once with the fix:

- `migration_stress_test` on `VerilatorRocket8KL116KL2Config`
- Record: migrations committed, `ABORT-DST` count, and which sets were used as destinations
- Put both numbers in the commit message

**Pass criteria:** 7/7 PASS, 0 asserts, and the destination sets should stay cold longer. If committed
migrations go *down*, stop and report — that would mean the phantom misses were accidentally load
bearing, which is worth knowing before anything else is built.

---

### Commit 2 — the abort tally (no RTL)

One question: **why do destination probes fail?**

The information is already in the log. [MSHR.scala:868](../design/craft/inclusivecache/src/MSHR.scala#L868)
prints `DREAD-RESULT` with `state`, `dirty`, `clients` and `displaced` on every probe.
`sw/scripts/sbc_stats.py` counts `ABORT-DST` but does not break it down by cause
([sbc_stats.py:26](../sw/scripts/sbc_stats.py)).

Add a parser rule for `DREAD-RESULT` and report the reject split:

| Cause | What it means |
|---|---|
| `state == INVALID` | not a reject — this is the good case |
| `dirty` | destination way holds modified data |
| `clients` | destination way is marked client-held — **the possibly-stale bit** |
| `displaced` | already a parked copy from another source |

**This is a measurement, not a fix.** Do not change any RTL in this commit. Report the split and stop
— the thinker decides what, if anything, follows from it.

Precedent: the source-side blocker was diagnosed exactly this way, with no RTL change and no re-run,
purely by tallying printfs that already existed.

---

### Commit 3 — pinned 1:1 association (spec Part 1)

Implement in this order. **1a before 1b — 1b is actively wrong without it.**

| Step | File | What |
|---|---|---|
| 1a | `Scheduler.scala` | **Split the two questions.** `migrateQuery` (advice) stays on the queue head; a new `destQuery` follows the deciding MSHR — in flight, else `directoryFanout`. Also fold the duplicate `anyMigrating`/`migBusy` into one `migrantOH`, and drop the `&& dstOfferValid` term from `preferEvictable`. |
| 1b | `SetBalanceUnit.scala` | Two answers, two keys. New `destQuery` port. Refresh the stale comment above it while you are there. |
| 1c | — | Nothing. The existing `dstOfferOwned` filter becomes the "partner is busy" check for free. Read the spec before concluding it needs changing. |
| 1d | `SetBalanceUnit.scala` | Commit asserts, with the `sbcForceDstSet` carve-out. |
| 1e | `DSS.scala` + `SetBalanceUnit.scala` | Remove port, so paired sets leave the candidate list. |
| 1f | `MSHR.scala` | The self-policing assert. Needs `pairInfo` from commit 4 — if you prefer, move 1f into commit 4. |

**Verification for this commit** (spec checklist items 2, 3, 4, 6). Note 1a was **revised
2026-08-24** after the fast-path hole was found — do not implement the muxed single-query version;
the correction note at the end of spec 1a explains why.

- Every `MIG-COMMIT` from the same `src` reports the same `dst` for the whole run
- A set seen as `dst` never appears as `src`
- The 1d asserts stay silent
- **Check the deferred path specifically.** A fast-path-only test passes even with 1a missing, because
  that is the one case where the two sets coincide. This is the whole point of the commit — do not let
  it slip through untested.
- Decline rate: pinning can only raise `ABORT-DST` and the no-offer `MIG-DECLINE`s, because a pinned
  source cannot re-pick. Report the number. If it jumps sharply, **stop and report before commit 4** —
  the escape hatches are the optimization table in `phase-3.md`, and choosing between them is the
  thinker's call, not yours.

---

### Commit 4 — shared building blocks (spec Part 2)

`secondarySearch` in the directory, `pairInfo` latch in the MSHR. Neither has a consumer yet (except
the 1f assert), so **compile-only verification is acceptable.**

Two things not to skip:

- The `false.B` defaults on every path, so SBC-off stays bit-exact
- The note at [MSHR.scala:332](../design/craft/inclusivecache/src/MSHR.scala#L332) about `dread.bits.tag`
  being 0. The swap spec depends on someone seeing it.

---

## Traps this code has already sprung

These are recorded because each one cost real time.

**1. Combinational loops around the destination claim.**
`dstClaim` feeds `dstSetConflict` → `allocReady` → `io.allocate.*`. So the claim logic must **not**
read `io.allocate.valid` or `io.allocate.bits.*`. Spell conditions out from `io.directory.bits` and
`request` instead of `new_meta` / `new_request`. Same discipline applies to `migrantSet` in 1a — build
it from registered status bits only. See the note at
[Scheduler.scala:505](../design/craft/inclusivecache/src/Scheduler.scala#L505).

**2. Mask "another migration in flight" per MSHR, never globally.**
A deferred migrant raises `migPending` itself. A global mask takes the offer away from exactly the
MSHR that needs it, and every deferred migration declines silently. The existing fanout at
[Scheduler.scala:297](../design/craft/inclusivecache/src/Scheduler.scala#L297) already does this
correctly — preserve it when you rename `migBusy` to `migrantOH`.

**3. When you add a gate to a condition, grep every sibling of that condition.**
`reload` and `retire` were the same expression upstream. Phase 2 gated one and missed the other.
Result: MSHRs advertised themselves free mid-migration and were reset without retiring. Cost: 94
dropped migrations and an empty association table.

**4. Do not trust a passing fast path as evidence.**
The whole reason Part 1a exists is that the fast path and the deferred path agree by coincidence.

---

## Build and run

```bash
# edit the config block at the top of the script, then:
sw/scripts/run_sbc.sh
```

- `CLEAN=1` after any RTL edit. The build silently reuses stale Verilog otherwise.
- Logs land in `sw/verilator_logs/<test>_<config>_<label>/`, with `[SBC]` lines pre-extracted into
  `sbc.log`, and `sbc_stats.py` run automatically.
- `sbcDebug` defaults to **true** in `WithInclusiveCache`, so the printfs are already on in every
  Verilator config.

**Configs you need:**

| Config | Use |
|---|---|
| `VerilatorRocket8KL116KL2Config` | The main one. Must stay **7/7 PASS, 0 asserts** at every commit. |
| `VerilatorRocket8KL116KL2NoSbcConfig` | SBC off. Run it if anything fails — it proves whether the failure is ours or the benchmark's. |
| `VerilatorRocket8KL116KL2DstCollisionConfig` | `sbcForceDstSet=0`. Only for the reclaim/collision repros. Expect the 1d asserts to be suppressed here by design. |

---

## What to report back

For each commit, in the commit message and in a short note:

1. Suite result — `x/7 PASS`, exit code, assert count
2. Migrations: attempted / aborted / committed
3. `ABORT-DST` count, and after commit 2, the cause split
4. Anything in the spec that did not match the code you found

That last one matters. **The spec's line numbers were verified 2026-08-24 against the current tree,
but if anything has moved or reads differently than described, say so rather than working around it.**
The reason this spec needed rewriting at all is that the previous version silently assumed a mechanism
that a later commit had replaced.
