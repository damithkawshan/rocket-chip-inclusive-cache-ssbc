# 020 — Make SBC obey the TileLink rules for write-backs (draft fix plan)

**Status:** DRAFT for review, written 2026-10-15 after task 019's simulation found the dual-core freeze. Nothing is built.
**Superseded by `TASK.md` in this folder (2026-10-15 ~22:00)** where they differ. That file adds D8 and fix F1f: the partner search
skips way-locked lines, so F1a alone would make a write-back miss its own line in Form B. It also has F1d lowering `nestC`, a
one-cycle F1e and a fixed commit order.
**Scope:** the inclusive L2 with SBC (`design/craft/inclusivecache/src`, RTL = `494ab5e`). The sim evidence is in
`coder/019-dual-core-lu-sweep/REPORT.md` ("SIMULATION" blocks) and `scripts/logs/clean-runs/dual-hang-hunt-20261015/sim/`.

Words used here:

| Word | Meaning |
|---|---|
| write-back | TileLink `Release` / `ReleaseData`: a core gives a line back to the L2 |
| write-back acknowledgement | `ReleaseAck`: the L2's answer to a write-back |
| probe, probe answer | `Probe` (channel B): the L2 asks a core to give up or downgrade a line. `ProbeAck[Data]` (channel C) is the answer |
| request slot | an MSHR. The last slot (`c_mshr`) is reserved for write-backs, the one before it (`bc_mshr`) for B or C work |
| home row, partner row | the set a line's address maps to (S), and the set where SBC parks it (P) |
| parked line | a displaced line: it lives in row P, but its address belongs to row S |

## 1. Summary

1. The TileLink spec says the L2 must always be able to accept and answer a core's write-back. It may not make that answer
   wait for another transaction that needs a probe answer or a grant. When a write-back meets a transaction on the same line,
   the L2 must **merge** the two (spec §9.2, quoted below).
2. SBC breaks this for parked lines. The write-back must search the partner row, and the search waits while any other slot
   works in that row. The simulation froze this way in every two-core run, in two forms (§4, D1).
3. Reading the code against the spec found five more gaps of the same family (D2 to D6). Two of them are hidden today
   behind the freeze, and one would lose data the moment the freeze is fixed naively.
4. The root cause is structural. In the original SiFive cache, one set is one point of serialization: every action on a
   line happens through that line's set. SBC splits a parked line between two rows, S and P, and the merge rules were never
   carried across that split.
5. The fix (F1 to F3) restores the spec's rules **without touching any SBC decision**: what migrates, where it goes, which
   way is evicted and when a line is served from the partner all stay the same. Only the timing of write-backs, probe-answer
   routing and a few-cycle guards change. So the performance we measured should not move. §8 says how we prove it.
6. Decisions for you are in §10.

## 2. The TileLink rules that apply (TileLink spec 1.8.1)

**Forward progress (chapter 5).** Every agent must follow these rules:

> 1. The agent graph contains no cycles.
> 2. Agents must eventually present all beats of a received message.
> 3. Unless they have a higher priority message in flight or unanswered:
>    i. Agents must eventually accept a presented beat.
>    ii. Agents must eventually answer a received request message.

The priority order for an agent like our L2 (lowest first): A received from cores, A sent to memory, B received from memory
(unused), **B sent to cores (probes)**, **C received from cores (write-backs, probe answers)**, C sent to memory, D received
from memory, D sent to cores (grants, write-back acknowledgements), E received from cores (grant acknowledgements), E sent
to memory.

So the L2 may hold back a write-back's acceptance or answer **only** while it has a C-to-memory, D or E message of its own
in flight or unanswered. It may **not** make a write-back wait for a probe answer (B is lower), for a grant to another
request (that grant's request is on A, which is lower), or for memory's answer to an A request.

**Concurrency limits (§9.2):**

> Grant: a slave should not issue a Grant if there is a pending ProbeAck on the block. Once the Grant is issued, the slave
> should not issue Probes on that block until it receives a GrantAck.
> Release: a master should not issue a Release if there is a pending Grant on the block. Once the Release is issued, the
> master should not issue ProbeAcks, Acquires, or further Releases until it receives a ReleaseAck.
> Probe: a slave should not issue a Probe if there is a pending GrantAck on the block. Once the Probe is issued, the slave
> should not issue further Probes on that block until it receives a ProbeAck.

**The merge requirement (§9.2, the Figure 30 text):**

> The slave cannot simply block the voluntary Release transaction until the Acquire transaction completes, because the
> ProbeAck message in that transaction could be blocked in the network behind the voluntary Release. ... The slave must
> accept the voluntary Release as well as any overtaken ProbeAcks ... and afterwards provide a ReleaseAck and Grant message
> to each master ... The voluntary Release's data can be used to respond to the original requestor with a Grant, but the
> Grant itself cannot be sent until the expected number of ProbeAcks have been collected by the slave. This scenario is an
> example of two transaction message flows being merged by the slave agent.

Rocket follows the master side exactly: while it waits for a write-back acknowledgement, it refuses probes for that line
and does not send a probe answer that carries data (`DCache.scala:767-770, :810`).

## 3. What these rules require from our L2

| # | Obligation | Source |
|---|---|---|
| O1 | Accepting and answering a write-back may wait only on bounded internal work, or on our own C-to-memory, D or E messages. Never on a probe answer, another slot's grant, or memory's answer to an A request | ch. 5, rule 3 |
| O2 | A write-back that meets another transaction on the same line is merged: its data goes into the line, every slot that holds the line sees the new dirty state, and the other transaction then finishes with that data | §9.2 merge text |
| O3 | At most one probe per line is outstanding, and each probe answer reaches the slot that sent the probe | §9.2 Probe rule |
| O4 | No grant for a line while one of its probe answers is still due | §9.2 Grant rule |
| O5 | No probe for a line while its grant acknowledgement is still due | §9.2 Probe rule |
| O6 | Every mechanism that names a line (merging, probe routing, probe address, write-back address) names it by its address (home set + tag), never by the row it happens to sit in | follows from O2 and O3 for parked lines |

The original SiFive cache meets O1 and O2 with one rule: a write-back to a set whose slot is waiting for a probe answer or a
grant goes into the reserved write-back slot, which **never waits for anything** (`nestC`, `Scheduler.scala:312`). The normal
slot of that set pauses (`mshr_stall_abc`, `Scheduler.scala:124-127`), and the write-back slot patches its metadata
(`nestedwb`, `Scheduler.scala:225-233`; `MSHR.scala:445-451`). All of this works because both actions are on the same set.

## 4. Where SBC breaks the rules

| # | What goes wrong | Rule | Evidence | Today |
|---|---|---|---|---|
| D1 | A write-back of a parked line must search row P, and `doSearch` waits for `!partnerBusy` (`MSHR.scala:667`). Its acknowledgement waits for the search (`MSHR.scala:638`). **Form A:** the slot that holds row P is the same-set slot it pre-empted, which `mshr_stall_abc` freezes. **Form B:** that slot also waits for the probe answer the releasing core withholds | O1, O2 | **Reproduced** in simulation: 5 of 5 two-core runs froze. Form A in the no-sharing runs, Form B in the shared-lines run. Controls (migration off, one core) finished | Freezes the dual-core board |
| D2 | A write-back that needs a **new** slot on a set fenced by a migration (`dstSetConflict`) or a search (`secSetConflict`) is refused at the entrance (`allocReady`, `Scheduler.scala:292`, used at `:418`). The fence can last as long as a memory fetch or another slot's grant | O1 (rule 3i) | By reading. Bounded in practice, so it does not freeze, but it breaks the rule and delays every probe answer queued behind it on that core's C channel | Silent rule break |
| D3 | A row-P slot that is evicting parked line X (it chose X as its victim and probes X's holder) never learns that the holder's write-back made X dirty. The merge broadcast comes only from the special slots, and it matches on the slot's own set (P), not on X's home set (S). The evictor would then send X to memory as clean and drop the written-back data | O2, O6 | By reading. Hidden today, because D1 makes the write-back wait instead of merging. **A naive fix of D1 would turn this into data loss** | Latent data loss |
| D4 | Two timing windows in the merge. (a) A write-back nests while the same-set slot's partner search result is still in flight, so that slot later re-points its metadata to a snapshot older than the merge. (b) A row-P slot's directory read is in flight when the write-back updates X, so the evictor latches a snapshot older than the merge broadcast. Either way, one slot keeps "clean" for a line that is now dirty | O2 | By reading. Two-core only. Hidden behind D1 | Latent data loss |
| D5 | A probe answer goes to **every** slot whose probe address matches, waiting or not (`Scheduler.scala:107-109`; only the data-write lookup checks `probeAckPending`). A slot that is not probing can collect stray answer bits (`probes_done`, `probes_toN`; `MSHR.scala:1059-1061`). Those bits are cleared only once per request (`:1697`), but SBC can start a probe later in the same request (`:1508`, `:1581`). With two cores, the leftover bit makes a later probe wait forever or finish before the real answer arrives | O3, O4 | By reading. Needs both cores on one parked line | Possible freeze or early grant |
| D6 | A data-carrying probe answer that no waiting slot matches is written to set 0, way 0 (the data-write lookup defaults to zero) | O3 | Follows from D5 | Possible silent corruption |
| D7 | Two simulation-only checks fire falsely on two-core traffic: `MSHR.scala:1725` and `:1735` (a same-line reload in the very cycle a migration retires), and the stock state-transition check (`MSHR.scala:966`, which runs on any schedule step, including the write-back's partner search) | none (checker bugs) | **Reproduced**, explained cycle by cycle | Blocks the stopping simulator |

**Rules SBC already keeps** (checked against the code):

- O3, one probe per line: an A-request search waits for `partnerBusy` and blocks new row-P slots (`secSetConflict`), and a
  line served from the partner row stays locked from the search answer to retire (`lockBorrowed`, `MSHR.scala:534-538`). So
  a row-P slot never probes a line a row-S slot is serving.
- O5, no probe before the grant acknowledgement: the same lock lasts until the serving slot retires, which is after the grant
  acknowledgement.
- O6, probe and write-back addresses: probes and memory write-backs of parked lines use the line's home set (`lineHome`;
  probe routing key `MSHR.scala:485-486`; write-back assert `:834-836`).
- A set that holds parked lines is never itself a migration source (`SetBalanceUnit.scala:163-164, :180`). So a write-back
  of a native line in such a set never searches anywhere.

**Open items outside TileLink that this plan does not fix:** B8-1 (teardown races a deferred migration's partner claim;
bug-fix-log), B7-2 (Vivado may see a combinational loop through the allocation logic; it is relevant because F1b touches
that logic), and the L2 flush register, which does nothing for a parked line (documented as unsupported; nothing on our
board uses it).

## 5. The fix

All changes keep every SBC decision unchanged. The left column names each change.

| # | Change | Where | Why it meets the rule | Performance effect |
|---|---|---|---|---|
| F1a | A write-back's partner search never waits for `partnerBusy` | `MSHR.scala:667`: `doSearch` ignores `partnerBusy` when `request.prio(2)` | O1. The wait exists to stop another slot from evicting the parked line during the search window. For a write-back that cannot happen: the line is held by the releasing core, so evicting it needs that core's probe answer, which Rocket sends only after the acknowledgement. The one remaining case, a row-P slot that already chose the line as its victim, is a merge (F1c) | Write-backs finish sooner when row P is busy. No decision changes |
| F1b | A write-back that needs a new slot is not refused because of a migration or search fence | `Scheduler.scala:292`: `allocReady` lets prio(2) pass both fences | O1, rule 3i. The fences protect victim choice and refill. A write-back has neither, and its line is native to the fenced set (a destination never sources), so it never searches either | Same as above |
| F1c | The merge broadcast comes from **any** slot that finishes a write-back, named by the line's home set and tag, and every slot matches it against its line's home set (`lineHome`) instead of its own set | producer `Scheduler.scala:225-233`, consumer `MSHR.scala:445-451` | O2, O6. The row-P evictor now learns that its victim became dirty, and writes it back with data | None. It only acts in the merge race |
| F1d | No nesting while the same-set slot's partner-search answer is in flight: `blockC` also covers `searching && s_ssearch && !w_ssearch` | `MSHR.scala:494` | O2, window (a) of D4. The write-back waits one or two cycles for the answer, then nests and patches up-to-date metadata | Up to 2 cycles, only in that race |
| F1e | A write-back being served in the partner row does not write the directory while a directory read of that row is in flight (a small register chain beside `directoryFanout`) | `Scheduler.scala:141-151` (`mshr_request`) | O2, window (b) of D4. The evictor latches its snapshot first, then receives the broadcast | Up to 2 cycles, only for write-backs of parked lines |
| F2 | A probe answer reaches only the slot that is waiting for it (`probeAckPending`), and a simulation check confirms exactly one slot matches | `Scheduler.scala:107-109` | O3, O4; removes D5 and D6 | None |
| F3 | Simulation only: fix the D7 checks (allow the reload in the retire cycle; run the transition check only on the real final write), add a write-back progress watchdog, widen the C-entrance watchdog to every refusal reason, and add the probe-answer uniqueness check | `MSHR.scala:966, :1725, :1735`; `Scheduler.scala:438-450` | Makes the stopping simulator usable on two cores | No hardware |
| Docs | `guides/priority-orders.md` rule 9 (write-backs pass every fence), bug-fix-log entries for D1 to D7, CLAUDE.md note | — | — | — |

**How the two freeze forms end after the fix:**

- **Form A.** The write-back slot searches row P at once, serves its line in place, acknowledges and retires. Then the paused
  same-set slot continues.
- **Form B.** The write-back slot merges the line (data in, dirty flag set, releasing core removed) and acknowledges. The
  core then answers the waiting slot's probe with "I hold nothing". That slot grants the merged data. This is exactly the
  flow the spec's Figure 30 describes.

## 6. Why not something simpler

| Alternative | Why rejected |
|---|---|
| Keep the wait, but stop `mshr_stall_abc` from freezing a slot that works in the partner row | Fixes Form A only. Form B still freezes, because the slot waits for a probe answer the core withholds |
| Queue the write-back behind the slot it would have pre-empted | The spec forbids it explicitly ("the slave cannot simply block the voluntary Release") |
| Do not serve written parked lines from the partner row (send them home or to memory instead) | Loses secondary hits for written lines, so the measured gains drop |
| Do not park lines that a core might write | Fewer migrations, so the measured gains drop |
| Pause all row-S work while row P is busy | More waiting on every access to a paired set, so performance drops |
| Write a parked victim back with data whenever it was held by a core | Extra memory writes on every such eviction, so the traffic numbers get worse |

## 7. What stays exactly as it is

The saturation counters, the destination-set selector, the pairing table, the migration start and abort rules, the
destination setting (0, 1 or 2), PLRU and random victim choice, the way-locks, the search for A requests (it keeps its
`partnerBusy` wait, which is still needed: an A request's line may be clean and unheld, so it could be evicted silently),
serve-in-place, and every counter.

## 8. Performance: why it should not move, and how we prove it

- **Why.** No change touches what migrates, where it goes, which way is evicted, or whether a line is served from the
  partner row. The changes alter only (a) how soon a write-back finishes when the partner row is busy (sooner), (b) whether
  a write-back is let in while a fence is up (sooner), (c) merge and routing behaviour in races that today freeze or
  misroute, and (d) waits of at most two cycles in two rare races.
- **Proof in simulation.** Run the existing single-core gates (`migration_stress_test`, `sbc_migrate_switch_test`,
  `dirty_guest_evict_test`, the serve-in-place tests, the 018 gate) on the old and new RTL. Compare migrations, secondary
  hits, memory reads and memory writes. Expect them equal, or different only through write-back timing. Any other
  difference is investigated before going to the board.
- **Proof on the board.** Single-core image, LU 2048 x 2048 matrix with 128 x 128 blocks, setting 1 against SBC off: the
  memory-access reduction must stay within about 1.5 points of our earlier results (-10.7% and its replicate -9.7%).
  Optional: LU 4096 x 4096 matrix, 128 x 128 blocks (earlier -20.9%, replicate -21.1%), about 6 hours.

## 9. Verification plan

| Step | What | Pass condition | Cost |
|---|---|---|---|
| V1 | Build the stopping and non-stopping two-core simulators on the new RTL | builds; elaboration prints unchanged | 2 min |
| V2 | Two-core suite on the **stopping** simulator (possible after F3): `dual_release_deadlock_test` with shared and unshared lines, settings 0, 1 and 2, PLRU and random, 5 seeds, 200,000 steps each | every run finishes, every written word reads back correctly, no check fires | about 1.5 h, 4 runs at a time |
| V3 | Directed two-core cases with coverage counters: Form A, Form B, a row-P evictor racing a write-back of its victim (D3), both D4 windows, and a stray probe answer (D5) | each case proven to occur in the run, data correct, no check fires | half a day to write, 30 min to run |
| V4 | Single-core regression gates, old RTL against new | all pass; counters as in §8 | 1 h |
| V5 | FPGA: post-synthesis DRC for the combinational-loop error (B7-2), then timing | no loop error; timing met (dual image slack was +0.548 ns) | 30 min, then two full builds of about 35 min each |
| V6 | Board, dual-core image: the boot-7 fast freezing pattern for 10 minutes on 2 fresh boots, then LU 2048 x 2048 with 2 threads and SBC on (the point that froze 4 times in 5) | no freeze; LU output equals the host reference | about 3 h |
| V7 | Board, single-core image: the §8 check | within about 1.5 points | about 1 h (6 h with the optional point) |

## 10. Decisions for you

1. **Scope.** Do F1a to F1e, F2 and F3 together (recommended), or F1a and F1c only first? F1a alone is not safe: it turns
   D3 into data loss.
2. **Branch and commits.** Task 019 commits nothing. Should 020 work on a new branch of the L2 repo (for example
   `sbc-020-tilelink-merge` from `sbc-013-sat-counter`) with one commit per change?
3. **Board validation depth.** V6 plus the n2048 point of V7 (recommended), or also the 6-hour n4096 point.
4. **B8-1.** Keep it out of 020 (recommended), but run V2 long enough to see whether two-core traffic hits it.
