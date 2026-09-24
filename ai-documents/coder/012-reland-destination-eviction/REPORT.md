# Coder report 012 — re-land 009's destination eviction, staged and board-gated

**Date:** started 2026-09-24 · **Branch:** `sbc-009-redo` · **Status:** IN PROGRESS — C1 + C2 written,
**sim gate GREEN (V1–V5, including the directed dirty-guest test)**. **Bitstream BUILT (V6) — not yet on the board.**
C3 (client-held W) not written; the synthesizable watchdog is awaiting a user decision.

> Filled in as the work happens. Task 009's REPORT was left as an empty template and its gates were
> never recorded, which is how a board-hanging build shipped. A commit with no filled row here is not done.

## ▶ RESUME HERE

- **Baseline (R0) needs no build.** This branch's RTL is `201ebae`, and the bitstream from that exact tree is
  archived and board-proven — **`e41f780c67ff2da2e60ff55c59f6750d0ea60e9fbfd9d18e36d06cb58711d177`**
  (`…SBCPLRU-008-c2B-7494296-wip-2026-09-17.bit`), `plru` ON 1029 s and 1030 s, rc=0. Full row in `TASK.md` §0.
  ⚠️ The same bytes are also archived under a `009-c1` name — cite the hash, never the filename.
- **Candidate (C1+C2) bitstream — BUILT, NOT ON THE BOARD:**
  **`af11762b917780e5ec71e8246ec4455b7914b9102e635c0ce1c91741a3d32ec4`** = commit `97d0162`. Row in "Board run" below.
- **Sim gate is closed.** V1, V2, V3, V5 PASS; **V4 PASS** with 9 dirty-guest write-backs, all to the source
  set's address, plus the random-mode control (0 events, 47,990 aborts). See "V4" below.
- **Next: V7 on the board** — 4 consecutive PLRU-ON completions, then 4 random. Board rules in `TASK.md` §4:
  sha256 the file before running it, one bitstream per session, capture §6 before reprogramming if it wedges.
- **Not built:** C3 (client-held W, the probe — needs the 011 §11.2(b) restructure).
- **Open for the user — the synthesizable watchdog (011 §11.4).** Not built: it is new state and 009's U6
  forbade it. **The image has no safety net:** every assert, including the `dstEvictCtr` watchdog, is inside
  `ifndef SYNTHESIS` and is absent from the bitstream (checked in the generated `MSHR.sv`, lines 421–1102).
  Without a hardware watchdog the next board failure is as blind as the last one.

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
| **V4** | **PASS** | directed test `dirty_guest_evict_test`, config `SipTestPlruConfig`: **9 `DST-RELEASE guest=1`, every one `home=5` (the source set), never `dstSet=6`** · all 8 ways of D released at least once · H3 held the copy **9 of 9** · read-back **144 lines, 0 mismatches** (the 8 evicted guests were re-fetched from memory) · **0** asserts / TLMonitor / shadow events · **random-mode control: 0 releases, 47,990 aborts**. Details below |
| V5 | **PASS** | NoSbc counters identical to baseline in both policies (part of V1) |
| **V6** | **BUILT** | `af11762b…3ec4`, WNS **+0.156 ns**, TNS 0, WHS +0.009 ns, 0 failing endpoints, DRC 54 (= previous build). Not board-tested. Row in "Board run" |
| V7 | pending | board — 4 consecutive PLRU ON completions |

### V2 detail — what the new path actually did (SBC config, PLRU, stress test)

| signal | count | meaning |
|---|---:|---|
| `DST-RELEASE` | **24** | a dirty, client-free W written back instead of aborting the migration |
| …of which guests | **0** | all 24 were **native** lines of D — the guest case is covered by the directed test V4 below (9 of 9 guests) |
| `H3-HOLD` | **24** | the copy was held while SourceC still read row D — **fired on every single eviction** |
| `ABORT-DST` | 97, **all `dirty=0 held=1`** | **zero dirty-only aborts remain.** Before C2 those 24 were in this count |
| `DST-RELEASE` in **random** mode | **0** | the runtime gate holds |

`H3-HOLD` at 24 of 24 is worth stating plainly: without that interlock every one of those write-backs
would have raced the copy over the same row, which is silent data corruption, not a hang.

### V4 — directed test for a dirty GUEST (done)

**What C2's one risky line does.** `dstHome := request.set` sends W's write-back to the *source* set's address
when W is a guest, because a guest's home is S, not the row it sits in. A wrong answer writes a block to the
wrong memory address: silent corruption, not a hang. The stress test only ever met a native W (24 of 24).

**Test:** `sw/dirty_guest_evict_test.c` on the new `VerilatorRocket8KL116KL2SipTestPlruConfig` (the SipTest
geometry with pairing pinned 5↔6, plus PLRU — `SipTestConfig` has no `L2_Replacement`, so C2 does not exist
there). Runner: `sw/scripts/run_012_v4.sh` (`V4_POLICY=0` for the random control). Log dir:
`sw/verilator_logs/dirty_guest_evict_test_VerilatorRocket8KL116KL2SipTestPlruConfig_012-v4/`.

| result | value |
|---|---|
| destination filled with guests | **8 of 8** (`parked=8`) |
| `DST-RELEASE` total / `guest=1` / `guest=0` | **9 / 9 / 0** |
| `home=` on every release | **5 = the source set**, never 6 |
| ways of D released | **all 8** (0,4,2,6,1,5,3,7) then way 0 again |
| `H3-HOLD` | **9** — once per release |
| `ABORT-DST` | **0** |
| read-back | **144 lines, 0 mismatches** — includes the 8 evicted guests, re-fetched from memory |
| asserts, TLMonitor, shadow | **0** — so the `W`'s-address assert and the `homeShadow` assert both held |
| `triggers-without-migration` | 0 |

The read-back is the check on the address: had a write-back gone to S's row 6 address instead of S's own,
memory at the guest's address would be stale and the re-fetch would return the old value.

**Random-mode control (same test, `L2_Replacement = 0`):**

| | PLRU (C2) | random |
|---|---:|---:|
| `DST-RELEASE` | **9** | **0** |
| `EVICT-DST release=1` | 9 | **0** |
| `ABORT-DST` | **0** | **47,990** (47,590 dirty-only, 287 held, 113 both) |
| migrations committed in the 8 triggers | 8 of 8 | **0 of 8** |
| read-back mismatches | 0 | 0 |

The runtime gate holds on the guest path too. The random run "FAILs" the test's own verdict on purpose — no
migration can commit into a fully dirty destination without C2 — and that is exactly the abort storm C2 removes.

#### Finding: the first version of V4 did not test anything (test design, not RTL)

The first run passed the data check but fired **0** `DST-RELEASE`. The destination never held more than one
guest: 9,649 of 9,739 migrations offered **way 0**, a clean guest. The cause was the *program*, not the RTL:
its own expected-value array, printf code and stack put seven native lines in the destination set that were
dirty or stale-client-held (Rocket's L1 drops clean lines silently, so the L2 `clients` bit stays set). The only
evictable way was the guest the previous migration had just installed, so every migration recycled it and C2
was never offered a dirty one. A green data check there meant nothing.

Fix, all in the test: the hot code, its stack and its data are aligned to 512 B and sized to stay in L2 sets
0–4; no printf while the test runs; expected values recomputed from `pat()`; the destination emptied first
(flood + `Flush64`) so its ways start invalid. `sw/scripts/check_v4_layout.sh` verifies the layout on the ELF
**before** a simulator build is spent (it caught a compiler-renamed symbol on its first use).

Lesson for the next directed test: *count the event, not just the data.* A test whose pass condition is data
correctness passes trivially when the scenario never happens (003 Amendment 10: "the scenario never happened
is a FAIL").

## Board run

**Not started.** Two bitstreams, recorded by hash:

| | R0 — baseline | **R1 — candidate (this task)** |
|---|---|---|
| sha256 | `e41f780c67ff2da2e60ff55c59f6750d0ea60e9fbfd9d18e36d06cb58711d177` | **`af11762b917780e5ec71e8246ec4455b7914b9102e635c0ce1c91741a3d32ec4`** |
| file | `…SBCPLRU-008-c2B-7494296-wip-2026-09-17.bit` | `chipyard/fpga/bitstream_storage/FPGASingleRocketVCU118L18K64K16WL2ConfigSBCPLRU-012-c2-97d0162-2026-09-25.bit` |
| commit | `201ebae` (proven, 010 §8.4) | **`97d0162`** on `sbc-009-redo`; L2 repo clean at build time |
| config | `FPGASingleRocketVCU118L18K64K16WL2ConfigSBCPLRU` | same |
| built | 2026-09-17 | 2026-09-25 00:01–00:29 (28 min) |
| timing | — | WNS **+0.156 ns**, TNS 0, WHS +0.009, 0 failing endpoints (previous build +0.433) |
| DRC | — | 54 violations, same count as the previous build |
| board | `plru` ON **1029 s, 1030 s**, rc=0 | **not run** |
| provenance | patches beside it | `…-012-c2-97d0162-2026-09-25.provenance.txt`: commit, sha256 of every RTL source, chipyard state |
| reports | — | `…-012-c2-97d0162-2026-09-25.reports/` (timing, DRC, utilization, clocks) |

**Verified on the generated Verilog before synthesis:** `MSHR.sv` contains `lock2Valid`, `dstEvict`,
`s_drelease`, `w_dreleaseack`, `dstRelOut` and `usePlru`. All assertions (`MSHR.sv` lines 421–1102) are inside
`ifndef SYNTHESIS`, so **none of the sim safety nets are in this image** — the board has no assert and no
watchdog. That is the same state as R0, and the reason the watchdog decision is open.

**Provenance note.** `RocketConfigs.scala` in chipyard was edited *after* Chisel elaboration finished (to add the
V4 sim config). Elaboration had already produced the Verilog, and the edit adds a Verilator-only class, so it is
not in the image; the provenance file's hash of `RocketConfigs.scala` is the pre-edit one, which is what the
build read.

**Plan (V7), unchanged from `TASK.md` §5:** 4 consecutive `plru` ON completions, then 4 random, on R1, same SPEC
`520.omnetpp_r --sim-time-limit=0.002s`. Report `memReads` and `memWrites` separately. K3 (`attempted −
migrations = dstAbortDirty + Held + Both`) is **expected to fail on R1 by design** — a dirty destination is now
written back instead of aborting — so it works as the fingerprint that R1, not R0, is the image on the board.

## Findings (reported, not fixed)

- **Timing margin is 0.28 ns tighter** (WNS +0.156 vs +0.433 for the previous build). Met, so not a defect, but the
  012 logic is on a path worth watching if the next stage (C3) adds more.
- **V4's first version tested nothing** — see the finding under V4. A test-design error, fixed in the test.

## Where the work order is wrong

- **011 §11.3's R2 folded in F6.** F6 (the `ReleaseAck` identity) is meaningless until a second Release
  exists, so it belongs in C2, where it landed.
- **011 §11.3 staged R3 as "client-free W, clean or dirty".** Split by the board data instead — see
  "Scope decision" above.

## Logs

- elaboration: `$SP/012/c1_elab.log`, `$SP/012/c2_elab.log`
- gate: `$SP/012/c2_gate.log` (tmux `sbc012`); per-run dirs `sw/verilator_logs/*_012-c2-{plru,random}/`
- V4: `$SP/012/v4b.log` (PLRU), `$SP/012/v4c_random.log` (control); `sw/verilator_logs/dirty_guest_evict_test_*_012-v4/`
- bitstream: `chipyard/fpga/build-logs/FPGASingleRocketVCU118L18K64K16WL2ConfigSBCPLRU-20260925-000156.log`, flow log `$SP/012/build_012.log`
