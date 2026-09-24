# Coder report 011 — fix the 009 C2 destination-eviction path

**Date:** · **Author:** coder session · **Status:** OPEN — not started

> Template. Fill it in as you go, not at the end. A task that stops early still gets a report saying
> where it stopped and why.
>
> ⚠️ Task 009's REPORT was left as an empty template and its gates were never recorded — which is how a
> board-hanging build shipped. **Do not repeat that.** A commit with no filled row below is not done.

## ▶ RESUME HERE

## Plan (write before any RTL edit; wait for the go-ahead)

## Decisions D1–D4 — the thinker's answers, as received

| # | Question | Answer | Date |
|---|---|---|---|
| D1 | fix in place, or revert 009 C2? | | |
| D2 | is F1 alone the first commit? | | |
| D3 | F4 — mask the other tiers, or delete the flag? | | |
| D4 | may an MSHR lock a second way? | | |

## Findings F1–F7 — verified, or refuted

**One row per lead. "Refuted" is a perfectly good outcome — say so and give the evidence.**

| # | Confirmed? | Evidence (RTL line, sim log, printf count, or the argument that kills it) |
|---|---|---|
| F1 | | |
| F2 | | |
| F3 | | |
| F4 | | |
| F5 | | |
| F6 | | |
| F7 | | |

## Any defect found that is NOT in the task

| # | What | Where | How found |
|---|---|---|---|

## TileLink rules T1–T8 (009 §2) — the RTL line that keeps each, after this task

| # | Line(s) | Note |
|---|---|---|

## Hazards H1–H7 (009 §3) — the RTL line that enforces each, after this task

| # | Line(s) | Note |
|---|---|---|

## In-flight cases I1–I8 (009 §5) — how each is handled, with evidence

| # | Evidence |
|---|---|

## Commits

| # | What | Hash | Gate result |
|---|---|---|---|

## Checks V0–V6

| # | Status | Result |
|---|---|---|
| V0 | | |
| V1 | | |
| V2 | | |
| V3 | | |
| V4 | | |
| V5 | | |
| V6 | | |

### V4 coverage counts — the table 009 owed and never filed

| path | count | directed test needed? |
|---|---:|---|
| W probed | | |
| W probe returned data | | |
| W released dirty | | |
| W released clean | | |
| W was a guest | | |
| **D-nest fired** | | |
| **S-nest fired** | | |
| **H3 held a copy** | | |
| N5 stalled the migrant | | |

## Board run

**Bitstream sha256 (not the filename):**

| run | policy | migrate | position in boot | `ITER_RC` | `ITER_SECS` | outcome |
|---|---|---|---|---|---|---|

Per run: `memReads` and `memWrites` **separately**, `L2_MemRelClean`, `L2_Cycles`, `SBC_Migrations`,
`SBC_Attempted`, `SBC_Aborted`, `SBC_DstDirty/Held/Both`, `parked`, and whether **K3** holds.

**Target: 4 consecutive PLRU ON completions**, plus 4 in random. Anything less is not a result.

## If it wedged — the 010 §6 capture, taken BEFORE reprogramming

## Findings (reported, not fixed)

## Where the work order is wrong

## Logs
