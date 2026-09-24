# Coder report 012 — re-land 009's destination eviction, staged and board-gated

**Date:** started 2026-09-24 · **Branch:** `sbc-009-redo` · **Status:** IN PROGRESS — C1 + C2 written,
**sim gate GREEN (V1, V2, V3, V5)**. V4 partial: the dirty-**guest** path never ran, directed test owed.
Bitstream and board not started.

> Filled in as the work happens. Task 009's REPORT was left as an empty template and its gates were
> never recorded, which is how a board-hanging build shipped. A commit with no filled row here is not done.

## ▶ RESUME HERE

- **Baseline (R0) needs no build.** This branch's RTL is `201ebae`, and the bitstream from that exact
  tree is archived and board-proven — **`e41f780c67ff2da2e60ff55c59f6750d0ea60e9fbfd9d18e36d06cb58711d177`**
  (`…SBCPLRU-008-c2B-7494296-wip-2026-09-17.bit`), `plru` ON 1029 s rc=0. Full row in `TASK.md` §0.
  ⚠️ The same bytes are also archived under a `009-c1` name — cite the hash, never the filename.
- **C1 written** (way-lock the destination way + the F4 comment). `ELAB_RC=0`.
- **C2 written** (dirty client-free W evicted with a real `ReleaseData`, runtime-gated on
  `L2_Replacement`). `ELAB_RC=0`.
- **Running:** `sw/scripts/run_012_gate.sh c2` in tmux `sbc012`.
- **Not started:** V1 diff against `f385555`, bitstream, board. **C3 (client-held W) not written** — it
  is the stage that needs the probe and therefore the restructure.
- **Open for the user:** the synthesizable watchdog (011 §11.4). Not built — it is new state and 009's
  U6 forbade it. Without it the next board failure is as blind as the last one.

## Scope decision — C2 is the DIRTY slice, not the whole of 009 C1

011 §11.3 staged R3 as "client-free W, clean or dirty". The board data says split it differently.
008's P-ON destination aborts were **dirty-only 44,159 / client-held 34,827 / both 1,028**, so:

| slice | share of aborts | needs a probe? | stage |
|---|---:|---|---|
| dirty, client-free | **55%** | **no** | **C2 — this commit** |
| client-held (± dirty) | 45% | yes | C3 |

C2 therefore recovers the larger slice **without any L1 involvement at all**. It holds the destination
fence while waiting on **memory** for `ReleaseAck`, and memory is an unconditional responder, so no
cycle can form. C3 waits on the **L1**, whose ProbeAck shares one channel with its voluntary
write-backs — that is the cycle that hung the board, and it is why C3 is separate and last.

Also **deliberately not changed:** the clean, client-free W keeps its existing *silent overwrite*.
009 converted it to a real `Release` (its T8). That is a memory-traffic question, not a correctness one,
and leaving it alone keeps the diff smaller. Revisit with data, not by default.

## C1 — what changed

| File | Change |
|---|---|
| `MSHR.scala` | `MSHRStatus` gains **`lock2Valid` / `lock2Set` / `lock2Way`** — a second way-lock slot. Driven `migrating && w_dread` / `migDstSet` / `migDstWay` |
| `Scheduler.scala` | `busyWays` ORs both slots. The "at most one way per row" note rewritten with the argument that still holds |
| `Scheduler.scala` | **F4:** the `allowDisplacedVictim` comment corrected — it reaches only `evictableOH` → tier 1, which only the destination probe can reach and where it is always true, so it changes no behaviour. Flag kept, not deleted |
| `MSHR.scala` | 2 asserts: the slots never name one row; a set is never its own destination |

**Why the `w_dread` gate on `lock2Valid` matters:** `migDstWay` is a register written in the dread-result
block. Before that it still holds the *previous* migration's way, so locking on `migrating` alone would
steer victims away from an innocent way in an innocent row. `w_dread` is written in the same block, so
the pair is consistent from the same cycle.

## C2 — what changed

| File | Change |
|---|---|
| `MSHR.scala` | New IO **`usePlru`** (`Option`, absent when `plruReplacement = false`) — the **live** `L2_Replacement` register |
| `MSHR.scala` | New registers `dstEvict`, `s_drelease`, `w_dreleaseack`, `dstRelOut`, `dstMeta`, `dstHome`. None of the stock release/probe state is reused |
| `MSHR.scala` | `dstReleasable = usePlru && valid && dirty && !clients` accepted at the dread result: latch W's entry and home set, arm the write-back |
| `MSHR.scala` | `c.valid` gains `dRelNow`; `c.bits` muxed to W's own tag / home set / row / way / dirty |
| `MSHR.scala` | `no_wait` gains `w_dreleaseack`; `doMigCopy` gains `s_drelease` |
| `MSHR.scala` | **F6:** `dstRelOut` set where SourceC *accepts* the message, so the `ReleaseAck` is attributed by identity, not by which wait-flag is low |
| `SourceC.scala` | exports **`busy`** |
| `Scheduler.scala` | **H3:** `copy_wsafe` also false while SourceC is reading that row; `H3-HOLD` coverage printf; `usePlru` routed to every MSHR |
| `MSHR.scala` | 9 asserts incl. T6, C-channel mutual exclusion, W's address, and a `dstEvictCtr` liveness watchdog |
| `sw/scripts/run_012_gate.sh` | new — **both** policies, both configs |

**The one rule C2 exists to enforce:** the decision reads `io.usePlru`, never
`params.micro.plruReplacement`. 009's F1 gated the same feature on the compile-time flag, so it ran in
random mode too and destroyed the only control we had. The gate keeps random runs for that reason.

## Checks

| # | Status | Result |
|---|---|---|
| Elab C1 | **PASS** | `ELAB_RC=0` · `…/012/c1_elab.log` |
| Elab C2 | **PASS** | `ELAB_RC=0` · `…/012/c2_elab.log` |
| V0 | not run | flag-off Verilog compare against `f385555` |
| **V1** | **PASS** | **Every counter in random mode identical to the `008-c2-random` baseline** — all 4 combinations (stress/switch × SBC/NoSbc), order-insensitive diff of `sbc_stats.txt`, 0 differing lines. 008 C2's RTL *is* `201ebae` *is* this branch's baseline, so this is a true control. **This is what licenses C1 and C2 sharing one bitstream**, and it is the direct proof that the runtime gate works where 009's compile-time flag did not |
| **V2** | **PASS** | `GATE_RC=0`, 4/4 runs. Stress 8/8 SBC and 7/7 NoSbc in both policies, switch test PASS. **0 asserts, 0 TLMonitor, 0 shadow-checker events across all 8 run directories.** C-head and `dstEvictCtr` watchdogs silent |
| **V3** | **PASS** | replay on the PLRU log: 254,720 victim lines, `plruWay != model` **0**, tier-2 `chosen != plruWay` **0** (207,663 picks), tier-1 `chosen != masked walk` **0** (46,865), `res != chosen` **0**, unparsed/backwards 0/0 |
| **V4** | **PARTIAL — 1 of 4 paths never ran** | W released dirty **24** ✓ · H3 held a copy **24** ✓ · random-mode control **0** ✓ · **W was a guest: 0** ✗ → directed test owed before C2 lands (below) |
| V5 | **PASS** | NoSbc counters identical to baseline in both policies (part of V1) |
| V6 | pending | bitstream |
| V7 | pending | board — 4 consecutive PLRU ON completions |

### V2 detail — what the new path actually did (SBC config, PLRU, stress test)

| signal | count | meaning |
|---|---:|---|
| `DST-RELEASE` | **24** | a dirty, client-free W written back instead of aborting the migration |
| …of which guests | **0** | all 24 were **native** lines of D — see the V4 gap |
| `H3-HOLD` | **24** | the copy was held while SourceC still read row D — **fired on every single eviction** |
| `ABORT-DST` | 97, **all `dirty=0 held=1`** | **zero dirty-only aborts remain.** Before C2 those 24 were in this count |
| `DST-RELEASE` in **random** mode | **0** | the runtime gate holds |

`H3-HOLD` at 24 of 24 is worth stating plainly: without that interlock every one of those write-backs
would have raced the copy over the same row, which is silent data corruption, not a hang.

### V4 gap — the directed test still owed

All 24 evictions were **native** lines of D. A **dirty guest** (a parked line of S, sitting in D, dirty)
never occurred, so `dstHome := request.set` — the branch that sends W's Release to **S's** address rather
than D's — has **never executed**. That is the one line of C2 that a wrong answer would make a silent
wrong-address write-back, so it is not acceptable to ship it unexercised.

**Plan:** force it with `sbcForceDstSet` (the existing debug knob — the only way to fill one set with
guests) plus write traffic, so the destination row fills with dirty guests and tier 1 finds no clean way.
009's V4 said exactly this and was never done; it is not being skipped again.

## Board run

Not started. The R0 baseline row is in `TASK.md` §0. Any image built here gets its own **sha256** row
beside it.

## Findings (reported, not fixed)

## Where the work order is wrong

- **011 §11.3's R2 folded in F6.** F6 (the `ReleaseAck` identity) is meaningless until a second Release
  exists, so it belongs in C2, where it landed.
- **011 §11.3 staged R3 as "client-free W, clean or dirty".** Split by the board data instead — see
  "Scope decision" above.

## Logs

- elaboration: `$SP/012/c1_elab.log`, `$SP/012/c2_elab.log`
- gate: `$SP/012/c2_gate.log` (tmux `sbc012`); per-run dirs `sw/verilator_logs/*_012-c2-{plru,random}/`
