# Priority orders — who goes first

**Written 2026-09-14.** A guide. Older docs refer to this file as `priority-orders.md`.
Checked against the RTL on the same date — line numbers drift, so re-check before relying on one.

---

## What a priority order is

Many parts of the cache share one thing: one data memory, one directory, one queue. When two parts
want it in the same clock cycle, a fixed rule decides who goes first. That rule is a **priority order**.

Why it matters:

- **A wrong order can deadlock the cache** — A waits for B while B waits for A, forever.
- **The order decides speed** — whoever goes last waits longest.
- Most orders below come from SiFive's original design and **the rest of the design depends on them**.
  The table says which ones are safe to change.

Words used below:

- **Worker** — one request being handled. The code calls it an MSHR. There is at most one per set.
- **Channels** — the message types between caches (TileLink):
  **A** = the CPU asks for a line · **B** = a cache asks for a line back ·
  **C** = the CPU hands a line back, or answers a B · **D** = replies · **X** = flush/control from software.

---

## Summary

| # | Shared thing | Who goes first | Safe to change? | Code |
|---|---|---|---|---|
| 1 | Entry into the cache | C, then X, then A | No | `Scheduler.scala:213-220` |
| 2 | Whose turn it is to act | Take turns — the last winner goes to the back | No | `Scheduler.scala:160-172` |
| 3 | A worker's waiting line | C, then B, then A | No | `Scheduler.scala:284-312, 374-378` |
| 4 | Directory read | Workers already running beat new requests | Careful | `Scheduler.scala:382-431` |
| 5 | Directory write | Reset first, then one worker at a time | No | `Directory.scala:122`, `MSHR.scala:498-506` |
| 6 | **Which line to evict** | empty → easy to move → random → first → moved line | **Yes — main tuning target** | `Directory.scala:217-226` |
| 7 | Data memory | 7 users in a fixed order; the SBC copy is last | No — tested | `BankedStore.scala:156-222` |
| 8 | Starting a migration | Only one migration at a time | Maybe — a design decision | `Scheduler.scala:271-282, 356-359` |
| 9 | A set busy with a migration or search | New requests to that set wait | No | `Scheduler.scala:257-270, 399-407` |
| 10 | Messages from one worker | Never bundle a message that might stall with a higher one | No | `MSHR.scala:365-368` |

---

## 1. Entry into the cache

- Order: **C → X → A** (`Scheduler.scala:213-220`).
- C messages answer questions the cache is already waiting for (a probe answer, a returned line).
  Letting them in first stops a new request from blocking the answer an old one needs.
- **SBC note:** bug P1 was a C message stuck behind a busy set. The fix lets C messages pass the
  destination fence (order 9).

## 2. Whose turn it is to act

- Workers take turns (round-robin). The one that just acted goes to the back (`Scheduler.scala:171-172`).
- No worker can be starved.

## 3. A worker's waiting line

- A request for a set that already has a worker waits in that worker's small queue. The queue serves
  **C first, then B, then A** (`Scheduler.scala:374-376`).
- The last two workers are kept for urgent work: one for B or C, one for C only (`Scheduler.scala:487-503`).
- A request may only wait behind a worker at its own level or lower (`Scheduler.scala:291-295`).
  This stops a low-priority request from holding up a high-priority one.
- B requests only happen when another cache sits below ours. Our configs have none.

## 4. Directory read

- There is one read port. A worker that needs it — to load its next queued request, or for an SBC
  second read — takes it. A new incoming request waits that cycle (`Scheduler.scala:382-398`).
- Which address is read, in order: SBC second read (migration check or partner search) → worker's
  next request → new request (`Scheduler.scala:427-431`).
- **Careful:** SBC adds reads here. More SBC reads means new requests wait more — part of SBC's cost.

## 5. Directory write

- A reset wipe goes before everything (`Directory.scala:122`).
- Only the worker whose turn it is (order 2) can write.
- Inside one worker, the SBC writes take turns (`MSHR.scala:498-506`): first put the moved line into
  its new set, then mark the old slot empty. Erasing a moved copy after a search waits for both.

## 6. Which line to evict — the eviction ladder

`Directory.scala:217-226`. The first rule that finds a line wins:

1. An **empty** slot — only when the caller asks (the SBC read of a destination set)
2. A line that is **easy to move** — unchanged, not held by the CPU, not already moved — only when a
   migration may happen
3. A **random** home line
4. The **first** home line — used when the random pick lands on a moved line
5. A **moved** line — only when the set has no home line left

Slots another worker is using are always skipped (the "way-lock"), so two workers never pick the same slot.

**This is the order to tune.** Step 3 of the
[parked-occupancy workplan](../performance/workplan-parked-occupancy-2026-09-11.md) tests two changes here:

- **Problem A** — moved lines are always last (rule 5), so they pile up and crowd out home lines.
- **Problem B** — rule 4 always picks the same slot. This happens on about 44% of evictions today.

## 7. Data memory (BankedStore)

`BankedStore.scala:156-222`. First wins:

1. `sinkC` — data coming back from the CPU's cache
2. `sourceC` — evicted data going out to main memory
3. `sinkD` — data arriving from main memory
4. `sourceD` write — the CPU writes into the cache
5. `sourceD` read — the CPU reads from the cache
6. SBC copy — write
7. SBC copy — read

- The code comment above the list gives the reason for each step.
- A request that cannot get its part of memory still blocks lower requests on that part, so nobody
  cuts in line.
- **Do not reorder.** Tested in Phase 2 (question Q3): the SBC copy being last causes delay, not
  deadlock. The old copy self-check (`s_verify`) starved at step 7, which is why it was removed
  (leftover L1 in CLAUDE.md).

## 8. Starting a migration

- **Only one migration at a time** (`Scheduler.scala:271-282`). While one runs, other workers get no
  destination offer (`Scheduler.scala:356-359`). An assert checks it.
- This may limit how many lines can move when there are millions of migrations.
- Changing it is a design decision, not a tweak — the fences in order 9 assume one migration at a time.

## 9. Sets busy with a migration or a search (fences)

- A new request to a set that a migration is using as its destination **waits** until the migration
  ends (`Scheduler.scala:257-260`).
- A new request may not start a worker on the partner set of a search that is still running
  (`Scheduler.scala:267-270`). It is still accepted into the cache.
- **Exception:** a C message may pass the destination fence (`Scheduler.scala:406-407`) — the bug P1 fix.
- **Do not loosen.** Two fixed bugs depend on these fences: the destination collision and P1.

## 10. Messages from one worker

- TileLink ranks some messages above others. A worker never sends a request to main memory that might
  stall in the same step as a release or probe message (`MSHR.scala:365-368`).

---

## Small tie-breaks

- SBC counter reset and a count in the same cycle: **reset wins**; one event is lost, which is harmless
  (`Directory.scala:330`).
- Software "arm this set" and "this set cooled down" in the same cycle: **arm wins**
  (`SetBalanceUnit.scala:176`).

## Rules before changing any order

1. Write down which order, why, and which deadlock it could cause.
2. Run the stress test (7/7) and the switch test (4/4) through `make run-binary`.
3. Update this file in the same change.
