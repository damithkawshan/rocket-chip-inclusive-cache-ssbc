# ai-documents — index and status tracker

What is in this folder, grouped, plus one tracker of every task and how far it got.
Updated **2026-10-12**: 512 KB image built; 1 MB put aside; all fresh-boot results in one list; tracker rows
brought up to date (teardown, 64-bit counters, open bugs); guides, doc lists and folder layout (sections 2–10).
**Folders applied on 2026-09-14** — the layout is in section 10.

> **Corrected 2026-10-12: the old "overdue" box is gone.**
> - It said set pairs never break up. That stopped being true on 2026-09-17: task 007 added the code that ends
>   a pairing when its last moved line leaves. It works in simulation
> - Still not done: a board check that reads every set's pairing after a run (tracker M12, L8)
> - Moved lines do not pile up on the board: the 2026-09-26 run ended with 2 moved lines after 617,145 moves
> - The box's second item (the heat counter on a partner hit) was settled on 2026-09-16 with no code change

**Doc labels**
- 🟢 **Use now**
- 📘 **History** — was right when it was written
- ⚠️ **Out of date** — the design changed; a note at the top of the doc says what to read instead
- ⏸ **On hold**

**Three rules for keeping docs honest**
- A doc we still use (this README, CLAUDE.md, guides, the workplan): **edit the wrong line.**
- A history doc (day logs, weekly reports, phase docs, coder reports): **add a note at the top**; do not
  rewrite old lines.
- A doc that is wrong from top to bottom: **move it to `obsolete_files_do_not_refer/`.**

---

## 0. Where we are (updated 2026-10-12)

> **▶ NEW 2026-10-12 — the next setup (your decision):**
> - **512 KB L2 (8 ways) with a 32 KB L1 (8 ways), one core**, same cache logic as every recent run
> - **The FPGA image is built** (2026-10-12 14:01, hash `37923e6b…82d3ee`), not on the board yet
> - How to build and measure: [guides/fpga-linux-run.md](guides/fpga-linux-run.md) (rewritten 2026-10-12)
> - **The 1 MB runs are put aside**, including the planned before/after test of the heat-counter fix
>   (task 013). That test never ran on the board
> - Both the L2 and the L1 get bigger than in the runs below, so the new numbers will not compare one-to-one
> - What to run on it first: not decided yet
>
> **All fresh-boot results so far** (256 KB L2, 8 KB L1, one core, 100 valid runs, 2026-10-05 to 10-10).
> Change in memory accesses with SBC on vs off (negative = fewer = better):
> - LU alone: −12.9% at block size 64 · about −4% at block 128 · −1.3% at 256 · about 0 at 16 and 32
> - Two LU programs together: −1% to −10% (best: two LU b64)
> - LU with FFT: −5% to −9% with LU b64 · about −3.5% with LU b128 · about 0 with LU b32 · FFT alone about 0
> - LU with streamcluster: −2.0%
> - bodytrack: about 0 at the default · −1% to −2% around 400 particles · **+4% (worse) at 1600 and 4000 particles**
> - Run time barely moves (1.2% or less): on this board memory is fast compared with the core
> - Most of these are one run each way; bodytrack has no repeats yet
>
> **What these runs changed:**
> - Every measured run must start from a fresh boot; runs on a board that has been up a while are not trusted
> - A low hit rate does **not** mean SBC will help
> - SBC does **not** win back the slowdown that a second program causes
>
> Details: [performance/fresh-boot-experiments-2026-10-09.md](performance/fresh-boot-experiments-2026-10-09.md) ·
> bodytrack: [coder/016 REPORT](coder/016-bodytrack-sbc-sweep/REPORT.md)
>
> ---
> **This week, in one paragraph.** We built the paper's own configuration — 1 MB 8-way with a **32 kB
> L1** — ran omnetpp on it, and **reproduced the paper's miss rate** (24.58% against its 28%). SBC was
> still at **parity**: 617,145 lines moved, **2 still parked at the end**, 0.34 hits per move. Chasing
> that we confirmed a real defect in what feeds the decision (**M18 gap 1**): write-backs and flushes
> move the saturation counters, which biases omnetpp heavily and `l2_miss_calib` barely — so part of the
> synthetic-vs-real gap is a **measurement** defect, not only a workload one. **M18 is now the top
> suspect, ahead of the adaptive throttle.** Caveat: that run was dual core and **96.7% of failed moves
> failed on a client-held destination**, so a single-core re-run was owed before quoting it as final
> (**put aside 2026-10-12** with the other 1 MB runs).
> Week: [weekly-report/2026-10-07.md](weekly-report/2026-10-07.md).
>
> **Since then (2026-09-30):** task 013 **fixed all four saturation-counter gaps in RTL** — C0 `93eee79`,
> C1 `86a8866`, C2 `89d9c20`, C3 `fed7227`, every stage gated green in simulation, and the feature-off
> build byte-identical to baseline throughout. Two 1 MB 8-way single-core images are built and verified
> per `.scala` file against their commits. **The board A/B was never run; it was put aside on 2026-10-12.**
> The fixed counter is in every image since 2026-10-01. [013 REPORT](coder/013-sat-counter-paper-mismatches/REPORT.md).
>
> **Since then (2026-10-07 to 10-09), task 015:** the pair headline of the 10-07 weekly report (−12.58%)
> was mostly board age. Fresh-vs-fresh it is **−1.99%** (p = 0.073). LU alone, fresh-vs-fresh (18 boots):
> SBC cuts memory accesses 3–5% where the working set overflows, 12.9% at n=512 b=64, nothing where it
> fits. Two LU programs on one core (model-chosen pairs, 8 windows): they make up to +72% more memory
> accesses than alone, and SBC recovers only 6%, 2% and −3% of that in the three large pairs.
> Reading so far (interim; S2–S7 not run): SBC helps a program whose own working set overflows;
> co-running is one way to cause that. [015 REPORT](coder/015-pair-verification-campaign/REPORT.md) (F11, F15).
>
> **⭐ 2026-10-09 — the most important set of experiments so far:**
> [performance/fresh-boot-experiments-2026-10-09.md](performance/fresh-boot-experiments-2026-10-09.md). Every SBC on/off
> result with one fresh boot per window, in one place: LU alone (9 points), LU + LU (6 pairs), LU + FFT (3 × 3 grid),
> LU + streamcluster. Interference depends on the victim (LU b64), not the partner; SBC's gain on a pair ≈ the sum of
> the two programs' gains alone (10 of 14 recovered values below 10%); with streamcluster the pair keeps only ~27% of
> LU's gain alone. One lead to repeat: pairs where LU b128 suffers recover 12–23% (4 of 5, weak band, 0.13–0.34 M). To continue in a new chat, start with [guides/fresh-boot-campaign-handoff.md](guides/fresh-boot-campaign-handoff.md).

- **Branch now:** `sbc-013-sat-counter` (cut from `sbc-009-redo`). `sbc-paper-aligned` is still the main
  line; `sbc-sampling` holds the 010/011 documents.
- **Two images that matter.** Quote the **hash**, never the filename — two files were found archived
  under wrong names (010 §8.5):
  - baseline, board-proven: `e41f780c…d177` (commit `201ebae`) — PLRU runs 1029 s and 1030 s, both rc=0
  - candidate, built 2026-09-25: `af11762b…3ec4` (commit `97d0162`) — **board gate 4 of 4, PASSED**
  - 1 MB 8-way single-core, built 2026-09-25: `585dbde1…3138` — used for the 1 MB envelope sweep
  - 1 MB 8-way **dual-core**, built 2026-09-26: `98aedafa…929e` — **boots Linux**, ran the
    paper-configuration omnetpp A/B
  - ⚠️ **Both 1 MB images = commit `962fa05`**, tag `sbc-1mb-8way-linux-booted-2026-09-26`. Their
    provenance files say `c3faa05` / `de88ddc`; both are wrong, because `Control.scala` was dirty in
    both builds and neither commit elaborates at 2048 sets. Fixed and committed 2026-09-30.
    Still not reproducible standalone: the `1024K8W` configs are in no chipyard commit
  - **task 013's A/B pair, 1 MB 8-way single core, built 2026-09-30** — every `.scala` checked against
    the commit: control `3abaca52…4870` = C0 `93eee79`, candidate `1bcba361…26cb` = C3 `fed7227`. The
    only difference between them is the four files the fix touches. **Never on the board; put aside 2026-10-12**
  - 1 MB 8-way dual core `bb0d3d0b…9095` (2026-09-30) = clean **pre-013** RTL, commit `cd7924b`
  - 256 KB 8-way, from **C3** RTL: single `5ace4125…9108`, dual `71fa886e…e418`. **The single-core one ran
    every fresh-boot result** (100 runs, 2026-10-05 to 10-10); not part of 013's A/B
  - **512 KB 8-way, 32 KB 8-way L1, single core, C3 RTL: `37923e6b…82d3ee`, built 2026-10-12.** The next
    setup; not on the board yet
- **What is still true from before:** SBC is correct. On real programs it cuts memory accesses only for LU
  b64 (−13%) and b128 (about −4%); elsewhere it is about 0 or slightly worse, and run time barely moves on
  this board (updated 2026-10-12). Every earlier number below stands unless a later line replaces it.
- **NEW 2026-09-25 — the design's operating envelope is measured:**
  [board-calib-envelope-2026-09-25.md](performance/board-calib-envelope-2026-09-25.md). 80 A/B points on
  the board. Set balancing pays **exactly while a hot set's overflow fits the empty ways of its partner**
  (`overflow <= spare`) — the rule is right on **78 of 80 points**, and the cliff moves to the predicted
  place as the spare ways are varied. Inside the envelope: up to **−41% cycles, −85% memory reads**.
  Outside: up to **+14% cycles, +24% reads** — half the plane loses, and losing is not free.
  77% of the gain is the **source set no longer thrashing**, not reuse of parked lines; "hits per parked
  line" is therefore not a figure of merit (0.19–295 among *winning* points).

### The 2026-09-14 picture (kept — it is what the tracker below was written against)

- **Branches:** `sbc-paper-aligned` is the main branch. `sbc-sampling` is a testing branch and will be
  merged back into the main branch.
- **Phase:** measuring speed on the FPGA board and improving it. Building is finished. What building
  left behind is in section 1d, and explained in CLAUDE.md ("Build-phase leftovers").
- **Correct data:** yes. With the feature on, the cache still returns correct data (simulation tests
  and a real program).
- **Speed: SBC is slower today.** Same program, same amount of work, feature on vs off:
  - it takes **32% to 51% longer**
  - it goes to main memory **2.5 to 7 times more often**
  - each setup was measured once, not repeated yet ([details](performance/fpga-ab-baseline-2026-09-11.md), section 4)
- **Must check this week:** set pairs never break up — see the box at the top. The heat-counter question
  is settled (2026-09-16).
- **Start here on 2026-09-15:** [why-sbc-loses-2026-09-15.md](performance/why-sbc-loses-2026-09-15.md) —
  an analysis, **not yet verified**, of why SBC loses where the paper wins. Its claim: three of our
  eviction rules plus pairings that never end leave about 47% of the cache full of moved lines, at any
  cache size. It also says Step 3 of the workplan needs rethinking. Its two board tests come first.
- **Proposed fix, waiting on your decision (2026-09-16):**
  [fix-plan-follow-the-paper-2026-09-16.md](performance/fix-plan-follow-the-paper-2026-09-16.md) — read the
  paper itself, then four small rule changes that put us back on the paper's placement and eviction
  behaviour: moved lines can be evicted, a move may reuse a moved-line slot, pairings end, and a cap of
  about 2 moved lines per pairing (the paper's own steady state). One switch register, one image. It
  replaces step 3 of the workplan.
- **Hit and miss words are now fixed (2026-09-14):** [guides/cache-terminology.md](guides/cache-terminology.md).
  Use them in every report. Until task 005 is done, **do not quote a hit rate** — the old counters mix
  in messages that are not real requests. The number of misses is already exact.
- **Doing now, in this order:**
  1. task 005 (ready for a fresh coder chat) — first move every counter into one place behind one switch,
     so the area can be measured without them; then new counters that follow the hit and miss words, with
     strict timing rules; fix three counting mistakes; take snapshots of the counters over time
  2. merge the testing branch into the main branch
  3. build one new FPGA image (one for tasks 006 and 005, not one each)
- **Done 2026-09-14:** task 006 closed (`e4c5d53`).
- **Plan:** [workplan-parked-occupancy-2026-09-11.md](performance/workplan-parked-occupancy-2026-09-11.md) — find out
  why moved lines make the cache slower. Step 3 is under review (see the analysis above).
- **Moved to the last phase:** task 003's final tests (GATE 5) and the copy self-check.

---

## 1. Status tracker

**How to use it:** when a piece of work changes, update its row in the same change. Never delete a
row — mark it ✅ or ⛔.

Marks: ✅ done · 🟡 half done · 🔴 not started · ⏭ moved to the last phase · ⏸ on hold · ⛔ dropped ·
🐞 known bug, not fixed

### 1a. Phases

| # | Phase | Status | What is left | Doc |
|---|---|---|---|---|
| P0 | Watch the cache: count hits and misses for every set | ✅ | — | [SBC_integration_plan.md](design/SBC_integration_plan.md) |
| P1 | Building blocks for moving a line to another set | ✅ 2026-06-24 | — | [phase-1.md](tasks/phase-1.md) |
| P2 | Move a line out of a busy set instead of throwing it away | ✅ 2026-08-17 | L1, L2, L3 | [phase-2.md](tasks/phase-2.md) |
| P2.5 | Ask the CPU's cache first, so more lines can move | ✅ 2026-08-18 | — | [phase-2.5-probe-then-migrate.md](tasks/phase-2.5-probe-then-migrate.md) |
| P3 | Copy a moved line back home when it is needed | ⛔ replaced by P3R | — | [phase-3.md](tasks/phase-3.md) |
| P3R | Use a moved line right where it sits | 🟡 works, not fully tested | L4, L5, L7 | [coder/003](coder/003-serve-in-place/) |
| P4 | End old set pairings + stop moving lines nobody reuses | 🟡 half built | Ending pairings: ✅ built 2026-09-17 (task 007 C3, L8). Stopping moves that don't pay (L6, the throttle): 🔴 not built — the main design job left | [SBC_integration_plan.md](design/SBC_integration_plan.md) |
| M | Measure speed on the FPGA and improve it | 🟡 **now** | section 1c | [workplan-parked-occupancy-2026-09-11.md](performance/workplan-parked-occupancy-2026-09-11.md) |
| LAST | Final checks before any final claim | ⏭ later | L1, L4, L7 | this tracker |

### 1b. Coder tasks

| # | Task | Status | What is left | Last update |
|---|---|---|---|---|
| 000 | Stop the cache heating sets just by looking at them; pick cold sets better | ✅ | — | 2026-08-24 |
| 001 | First version of "find the moved line again" | ✅ | — | 2026-08-28 |
| 002 | Fix a one-cycle timing bug in set pairing | ✅ | — | 2026-08-28 |
| 003 | Use a moved line right where it sits | 🟡 | final tests (GATE 5) ⏭ moved to the last phase | 2026-09-14 |
| 004 | Count all cache hits and misses | ✅ | — | 2026-09-02 |
| 005 | Count hits honestly + sample counters over time | 🟡 **half done** | **Commits 0 (`a1f4cbd`) and 1 (`b3bd6e4`) landed** — every counter in `PerfCounters` behind one switch, plus the eight terminology counters (`0x3F0`–`0x428`) with `PopCount` fan-in and `L2_StatsHold`; board-verified, all four identities exact. **Commits 2 and 3 (the three counter fixes + the interval sampler) not started**, which is why M8 and M15 are still open. Row said "not started" until 2026-09-27 | 2026-09-27 |
| 006 | On/off switch + count trips to main memory | ✅ 2026-09-14 | closed (`e4c5d53`). A new FPGA image is still owed — one image after task 005 | 2026-09-14 |
| 007 | Follow the paper's placement and eviction rules | ✅ 2026-09-16 | closed. Moved lines can be evicted, a move may reuse a moved-line slot, pairings end. Turned a big loss into near-parity | 2026-09-16 |
| 008 | Pick the evicted line by recency (PLRU) instead of at random | ✅ 2026-09-18 | closed (`201ebae`). On the board PLRU alone won on the plain L2 (−3.56% cycles, −12.65% memory traffic); SBC on top still only ties | 2026-09-18 |
| 009 | Evict the destination's least-recent line whatever its state | ⛔ **reverted** | **Its image hung the board** — 0 of 4 runs finished. Reverted on the fallback branch. The design rule it broke is written in the RTL: fencing the destination before the CPU answers adds a wait the baseline does not have | 2026-09-24 |
| 010 | Find out which change hung the board | ✅ 2026-09-24 | closed. The hang belongs to 009, not to PLRU: the 008 image finished 2 of 2 PLRU runs (1029 s, 1030 s) while 009's finished 0 of 4. Also found two bitstreams archived under wrong names — cite hashes, never filenames | 2026-09-24 |
| 011 | Plan how to re-land 009 safely | ✅ 2026-09-24 | the staged plan 012 follows | 2026-09-24 |
| 012 | Re-land 009's destination eviction, one stage at a time | ✅ **C1+C2 done 2026-09-25** | Board gate **4 of 4** (009's image was 0 of 4); dirty destination aborts **48,258 → 0**; **no speed change** — the envelope explains why. C3 (the CPU-holds-it case) **deliberately not built**; on dual-core it is now **96.7% of all aborts** (M22). Watchdog still waiting on a decision | 2026-09-25 |
| 013 | Make the heat counter follow the paper (4 gaps) | 🟡 **RTL done; 1 MB board test put aside 2026-10-12** — the fixed RTL is in every image since 2026-10-01 | All four stages committed and gated green in simulation: C0 `93eee79`, C1 `86a8866` (only demand accesses move the counter), C2 `89d9c20` (test for hot *after* the update, on the *looked-up* set — fixes M17), C3 `fed7227` (a demand second search updates the partner). Control + candidate 1 MB images built and verified, never on the board. The omnetpp A/B and calib sweep on them are put aside (user decision 2026-10-12) | 2026-10-12 |
| 014 | Build SPLASH-2 `radix` and `fft` for the board (host-only) | ✅ 2026-10-07 | both static; they feed task 015 S6 | 2026-10-07 |
| 015 | Multi-application SBC verification: interference, or low hit rate? | 🟡 **parked at stop (c)** | R0 + S1 done. Pair headline −12.58% was board age: fresh-vs-fresh **−1.99%** (p 0.073). Side studies 2026-10-08: LU sweep 18/18, LU pair, LU pair search 8/8 (SBC recovers 6% / 2% / −3% of the interference). 2026-10-09: LU + FFT grid 24/24 (under 10% recovered in 7 of 9). **S2 dose-response not started.** Results: [fresh-boot report](performance/fresh-boot-experiments-2026-10-09.md) | 2026-10-09 |
| 016 | bodytrack SBC off vs on and a one-parameter-at-a-time sweep (fresh boot per window) | 🟡 **one sweep done, repeats pending** | 2026-10-10: 36 of 36 windows valid. SBC costs 3.7–4.1% at 1600–4000 particles, small savings (1.2–1.9%) only around 400 particles, nothing at the centre (−0.4%). Output correct with SBC on at every setting (`poses.txt` md5 = host). **Left:** repeats to measure bodytrack's own noise (your decision); the extra settings toward the best point not started | 2026-10-10 |

### 1c. Measure and improve (current phase)

| # | Item | Status | Notes |
|---|---|---|---|
| M1 | Same-work speed test on the board, feature on vs off | ✅ 2026-09-13 | 3 tests: 32–51% slower, 2.5–7× more trips to memory |
| M2 | Repeat each test 3 times | 🟡 | Fresh-boot repeats exist only for a few points: LU + streamcluster (3 off, 4 on), LU b32 + b128 (2 each), LU b64 alone. Every other fresh-boot result, and all of bodytrack, is one run each way. Measured noise: 0.5–0.9% per window (LU) |
| M3 | Watch moved lines pile up for one hour | 🔴 | workplan step 2 |
| M4 | Try two eviction fixes: stop over-protecting moved lines, and stop always evicting the first slot | ⛔ replaced 2026-09-16 | folded into the fix plan: the "first slot" fallback disappears on its own once moved lines can be evicted. See [fix plan](performance/fix-plan-follow-the-paper-2026-09-16.md) C1 |
| M5 | Count hits honestly (task 005) | ✅ 2026-09-16 (005 c1) | The exact counters exist and are board-verified, so **a hit rate may now be quoted** from `L2_PrimaryHit` + `L2_SecondaryHit` ÷ `L2_AccessA`. The §0 line below saying "do not quote a hit rate" is superseded. Words: [cache-terminology.md](guides/cache-terminology.md) |
| M6 | 64-bit counters | ✅ | Saved and passing in simulation 2026-09-14 (`06fcca8`). Every FPGA image built since 2026-09-17 has them (`06fcca8` is in the RTL of all of them), and the board reads have used them since |
| M7 | Is the cache's cycle counter the same clock as the CPU? | 🔴 | the on/off comparison is fair either way |
| M8 | The "moves attempted" counter does not add up | 🟡 cause found 2026-09-14 | the "aborted" count also includes moves that were turned down before they started, and two in the same cycle count once. Fix is in task 005 |
| M9 | Check that changed moved lines are always saved, never lost | 🔴 | cheap to check, bad if wrong |
| M10 | Try the CPU-cache setting that makes it report lines it drops | 🔴 | never tried; should cut wasted move attempts |
| M11 | Which heat counter a partner hit should change | ✅ settled 2026-09-16 | Read from the paper PDF (Figures 2 and 3): home **+1** on a native miss even when the partner search hits, partner **−1**. We match the home half; the partner half changes no decision while paired. **No RTL change.** [fix plan](performance/fix-plan-follow-the-paper-2026-09-16.md) section 0a |
| M12 | Board check 1: after an SBC run, read every set — paired or not, and how hot | 🔴 | analysis §6 check 1. Predicted every set paired — written before teardown was built (2026-09-17). **Also verifies L8.** Tool half-ready: [sw/sat_snap.c](../sw/sat_snap.c) reads every set's heat in one pass; it does not read the pairing (`SBC_AtAssoc`) yet |
| M20 | Map when SBC pays: sweep spare ways x overflow lines on the board | ✅ 2026-09-25 | **Done, 80 points.** Rule `overflow <= spare` holds on 78/80. [envelope](performance/board-calib-envelope-2026-09-25.md). Turns L6 (adaptive throttle) from a hunch into a specified job: detect "my overflow does not fit" and stop |
| M21 | Envelope at other geometries: 64 KB 8-way and 1 MB 8-way | ✅ 2026-09-25/26 | The rule `overflow <= spare` holds at all three geometries. More sets and fewer ways shrink the downside: worst-case cycles **+13.61%** (64 KB 16-way) → **+4.95%** (64 KB 8-way) → **+3.27%** (1 MB 8-way). [64 KB 8-way](performance/board-calib-envelope-8way-64kb-2026-09-25.md) · [1 MB 8-way](performance/board-calib-envelope-8way-1mb-2026-09-26.md) |
| M22 | omnetpp in the paper's own configuration (1 MB 8-way, 32 kB L1, dual core) | ✅ 2026-09-26, n=1 | **Reproduced the paper's regime**: L2 miss 24.58% vs the paper's 28% for omnetpp. SBC still at **parity** — cycles −0.010%, reads +0.182%, writes +0.011%, hit rate −0.07 pts. Mechanism: **617,145 migrations, 2 lines parked at the end**, 0.34 hits per park, 0.004% of accesses served from a parked line, 93.3% of second searches wasted. 44% of migrations overwrote another guest (inferred — `migCommitReuse` has no counter). **52% of attempts aborted, 96.7% of them client-held** — a dual-core effect, so re-run single-core before judging (**put aside 2026-10-12** with the other 1 MB runs). One-boot method, n=1. Log: `chipyard/scripts/logs/board_session_20260926-155336.log` |
| M23 | Is SBC's benefit on two co-running programs an interference effect, or a low-hit-rate effect? (task 015) | 🟡 interim 2026-10-09 | Pair fresh-vs-fresh **−1.99%** (p 0.073), not −12.58% (board age). LU alone fresh sweep: −3 to −5% where the working set overflows, −12.9% at n=512 b=64, nothing where it fits. LU+LU pairs: SBC recovers **6% / 2% / −3%** of the interference. LU + FFT grid (2026-10-09): under 10% in 7 of 9. **Reading so far (not final):** SBC helps a program whose own working set just overflows (LU b64, b128); it does not win back what a partner costs, and a low hit rate does not predict a gain (bodytrack). S2 dose-response, S3–S7 not run. [REPORT](coder/015-pair-verification-campaign/REPORT.md) F11, F15 · [fresh-boot report](performance/fresh-boot-experiments-2026-10-09.md) |
| M24 | Measure on the next setup: 512 KB L2 + 32 KB L1, one core (your decision 2026-10-12) | 🔴 next | Image built 2026-10-12 (`37923e6b…82d3ee`), not on the board. What to run first: not decided. Needs a new campaign folder (the launchers check the 256 KB image's hash) |
| M25 | bodytrack: does any setting gain? (task 016) | 🟡 one sweep 2026-10-10 | No clear gain anywhere; **+4% worse at 1600 / 4000 particles** (lost primary hits). Repeats not run. [016 REPORT](coder/016-bodytrack-sbc-sweep/REPORT.md) |
| M13 | Board check 2: read the moved-line count every few seconds during the "on" half | 🔴 | analysis §6 check 2. Predicted steps of ~15 as pairs form, then flat, never falling. Overlaps M3. The "never falling" part already looks wrong: the 2026-09-26 run ended with 2 moved lines after 617,145 moves (M22) |
| M14 | Decide the fair version to test, before building M4's switches | 🟡 proposed 2026-09-16 | [fix plan](performance/fix-plan-follow-the-paper-2026-09-16.md): C1 evictable + C2 reuse a slot + C3 end pairings + C4 cap. **New:** equal competition alone is not enough — with random replacement the moved lines still settle at roughly half the set (flow argument, section 2), which is why the cap is in. Waiting on your answers in section 7 |
| M16 | 🐞 The moved-line count can be decremented for the wrong set | ✅ **fixed — verified in RTL 2026-09-27** | The OR + `Mux1H` this row described is **gone**. `Scheduler.scala:752-756` latches each MSHR's erase pulse **with its own home set** in a per-MSHR pending slot (`grantOnePerCycle`, `Scheduler.scala:669-684`) and drains one per cycle, with an assert on the only remaining loss case. Fixed by 007 c0; the row was simply never updated. `PerfCounters` still moves the global level by `PopCount`, which is correct |
| M17 | 🐞 Migrate advice can be checked against the wrong set | ✅ **fixed in RTL 2026-09-30 — task 013 C2 (`89d9c20`)**, gated green; in every image since 2026-10-01. Its own 1 MB board A/B was put aside 2026-10-12. Found 2026-09-17, confirmed in the RTL 2026-09-30 (the queue-pop path). The fix also adds an assert that catches a mis-keyed lookup | A request popped from the per-MSHR queue latches `migAdvice` built from the incoming sink request's set (`Scheduler.scala` `migrateQuery.bits := request.bits.set`). Can start a migration from a set that is not at max. Also see [fix plan](performance/fix-plan-follow-the-paper-2026-09-16.md) §1 rows 9–12: the other ways our heat counter differs from the paper |
| M18 | Make the saturation ("heat") counters follow the paper | ✅ **gaps 1–4 FIXED in RTL 2026-09-30 — task 013 C1–C3**, all gated green, and in every image since 2026-10-01, so every fresh-boot result ran with it. **Its before/after board A/B was put aside 2026-10-12**, so the size of the fix's own effect is not measured. Gap 5 (repeat reloads are never counted) is **measured only — do not build a fix**. The diagnosis that led here: | `Directory.scala:314` taps **every** directory read (`ren2 && !internalRead`), so inner-C write-backs and X-channel flushes update the saturation counter — and a write-back to a resident line reads as a **hit**, cooling the set by 1. Board 2026-09-26: `L2_Accesses` 6,270,326,354 vs `L2_AccessA` 4,931,117,515 → **21.4% of heat-counter events are not demand accesses**. Those 1.34 G non-demand lookups are the pollution. It bites write-heavy workloads hardest, because Rocket drops clean L1 victims silently, so inner-C Releases are mostly dirty write-backs: omnetpp moved **514 M** dirty blocks to memory, `l2_miss_calib` a few thousand — so the instrument the envelope was measured with is barely affected and the real workload is. ~~Other three gaps unchanged~~ (all four fixed 2026-09-30). ~~Now the top suspect, ahead of the throttle~~ — fixed; the throttle (L6) is next |
| M15 | The "served a write" counter also counts lines the CPU cache hands back | 🐞 found 2026-09-14 | a counting mistake only — the cache itself works correctly. Two message types share the same number. Fix is in task 005 |
| M19 | 🐞 A pairing can end while a migration is still deferred for that source, and the migration then claims the wrong destination | 🔴 found 2026-09-17 (task 008), pre-existing since 007 C3 | RTL assert fires: "SBC: paired source migrated outside its partner set". Proven not an 008 bug — reproduces identically on a build with no PLRU code at all. **Still open 2026-10-12; no fix designed.** It shows up as a simulation assert; board images have no asserts, so whether it ever happens on the board is unknown. [bug-fix-log.md](bugs/bug-fix-log.md) B8-1 |

### 1d. Left over from the build phases — details in CLAUDE.md

| # | Item | From | Status | Notes |
|---|---|---|---|---|
| L1 | Check that each copied line landed correctly | P2 | ⏭ | removed because it got stuck; never rebuilt |
| L2 | Last-resort eviction of moved lines | P2 | 🟡 | Only proven in a forced test. Since 2026-09-16 (task 007) moved lines are evicted like any other line, so this path now runs in every SBC run. Board runs end with correct output: bodytrack matched the host's output at all 18 settings with SBC on |
| L3 | Rare timing-window bug | P2 | 🐞 | never seen in a run; do not build a fix in advance |
| L4 | Final tests of task 003 (GATE 5) | P3R | ⏭ | 6 of 12 cases unproven; the two-core cases never ran |
| L5 | Move lines that have been written to | P3R | 🔴 | only unchanged lines can move today |
| L6 | Stop moving lines from sets whose moved lines are never reused | P4 | 🔴 | not built |
| L7 | Test "serve a moved line that needs write permission" | P3R | ⏭ | needs two cores; never happened in any run |
| L8 | End a pairing once its moved lines are gone (teardown) | P4 | ✅ built 2026-09-17 | Task 007 C3 (`744fabb`); it only started firing after the B7-1 fix (`c6a824c`). Simulation: 2,460 teardowns in the gate run. **Not yet checked on the board** (no counter for it; M12). Known race: M19 / B8-1. *Was "never built" from 2026-09-14 until this row was corrected 2026-10-12* |

### 1e. Tidy-up — low priority, do during the improve phase

| # | Item | Status | Notes |
|---|---|---|---|
| H1 | Remove dead copy logic from the feature-off build | 🔴 | 240 LUT / 529 FF; makes the baseline fair |
| H2 | Delete an old commented-out debug block | 🔴 | |
| H3 | Fix 2 code comments that describe deleted hardware | 🔴 | |
| H4 | The "arm a set" register does nothing in auto mode | 🟡 | written down, not removed |
| H5 | Write the priority-order guide | ✅ 2026-09-14 | [priority-orders.md](guides/priority-orders.md) |
| H6 | Commit the chipyard-side changes (configs, board scripts) | 🟡 | Partly done: the 1 MB configs (`4a2f8064`) and the LU campaign scripts (`fa785739`, `068aa805`). **Still uncommitted:** the 512 KB and dual 256 KB configs, `build_bitstream.sh`, and the bodytrack and LU + FFT campaign tools. Until the configs are committed, the 512 KB image can only be rebuilt from its archived `.config.diff` |
| H7 | Merge `sbc-sampling` into `sbc-paper-aligned` | 🔴 | after task 005 (006 is closed) |
| H8 | Move docs into folders, then fix links | ✅ 2026-09-14 | 24 files moved; one link to a long-deleted file marked as deleted |
| H9 | Remove debug and measurement hardware for the final area number | 🔴 | task 005 puts every counter, the read-back muxes and the sampler behind one switch (`enablePerfCounters`); the rest is listed in CLAUDE.md; only after the last measurement |

---

## 2. Guides — how we work — `guides/` (+ two files elsewhere)

- 🟢 [../CLAUDE.md](../CLAUDE.md) — rules for working on this code, current status, build-phase leftovers.
- 🟢 [coder/README.md](coder/README.md) — how the thinker and the coder hand work to each other.
- 🟢 [guides/fpga-linux-run.md](guides/fpga-linux-run.md) — build the FPGA image, boot Linux on the board, read the counters. **Rewritten 2026-10-12:** section 1 builds any image with `build_bitstream.sh` (archives it with its provenance); section 7 is the fresh-boot A/B. The old one-boot A/B is kept only as history.
- 🟢 [guides/devmem-register-map.md](guides/devmem-register-map.md) — every counter and switch the cache exposes.
- 🟢 [guides/priority-orders.md](guides/priority-orders.md) — who goes first when two parts of the cache want the same thing.
- 🟢 [guides/fresh-boot-campaign-handoff.md](guides/fresh-boot-campaign-handoff.md) — **restart point for any fresh-boot campaign** (tasks 015, 016, and the 512 KB runs): image, flow of one window, counters, tools, standing rules, state, next experiments.
- 🟢 [guides/cache-terminology.md](guides/cache-terminology.md) — **the words we use** for hit, miss, secondary hit and the rest, where they come from, and which counters follow them.

## 3. Task documents — `tasks/` and `coder/`

### 3a. Phase documents and plans — `tasks/`

- 📘 [phase-1.md](tasks/phase-1.md) — building blocks for moving a line.
- 📘 [phase-2.md](tasks/phase-2.md) — move a line out of a busy set.
- 📘 [phase-2.5-probe-then-migrate.md](tasks/phase-2.5-probe-then-migrate.md) — ask the CPU's cache first, so more lines can move.
- ⚠️ [phase-3.md](tasks/phase-3.md) — the old "copy it back home" design (not built). Its last section lists the pairing rules that **are** built.
- 📘 [July18AfterBreakWorkplan.md](tasks/July18AfterBreakWorkplan.md) — the Phase-3 restart plan (18 Aug); every step is done or replaced.
- 🟢 [code-cleanup-suggestions.md](tasks/code-cleanup-suggestions.md) — tidy-up list, and what NOT to touch.

### 3b. Coder work orders — `coder/` — each has `TASK.md` + `REPORT.md`

- 📘 [000](coder/000-internalread/) · 📘 [001](coder/001-phase3-reuse-loop/) · 📘 [002](coder/002-partner-latch-fix/)
- 🟢 [003](coder/003-serve-in-place/) — the current design, with diagrams.
- 📘 [004](coder/004-l2-hitrate-counters/)
- 🟡 [005](coder/005-hit-accounting-and-sampling/) — **half done**: commits 0 and 1 landed (all counters behind one switch; the exact hit/miss counters, board-verified). Commits 2 and 3 (three counter fixes, the interval sampler) not started — that is why M8 and M15 are still open.
- 📘 [006](coder/006-migrate-switch-and-memory-traffic/) — closed 2026-09-14.
- 📘 [007](coder/007-paper-aligned-eviction/) · 📘 [008](coder/008-plru-replacement/) — both closed.
- ⚠️ [009](coder/009-evict-destination-lru/) — **reverted**: its image hung the board. Read 010 before reusing anything from it.
- 📘 [010](coder/010-hang-bisection/) — which image hung, proved by hash and by counter identity. 📘 [011](coder/011-fix-009-destination-eviction/) — the re-land plan; carried out as task 012 (its own REPORT was never started).
- 📘 [012](coder/012-reland-destination-eviction/) — C1+C2 landed, board gate 4/4, no performance change; C3 not built.
- ⏸ [013](coder/013-sat-counter-paper-mismatches/) — make the saturation counter follow the paper (gaps 1–4). **All four RTL stages are committed and gated green, and that RTL is in every image since 2026-10-01. The 1 MB board A/B was put aside on 2026-10-12** (next setup: 512 KB, see section 0).
- 📘 [014](coder/014-splash2-radix-fft-build/) — SPLASH-2 `radix` and `fft` built static for the board (host-only); feeds 015 S6.
- ⏸ [015](coder/015-pair-verification-campaign/) — **parked at stop (c)**: multi-application verification. Pair headline corrected to −1.99% fresh-vs-fresh (F11); LU sweep, LU pair, pair search and the LU + FFT grid done (F14, F15); S2 dose-response not started. Read its "RESUME HERE".
- 🟡 [016](coder/016-bodytrack-sbc-sweep/) — **one sweep done 2026-10-10, repeats pending** (SBC +3.7 to +4.1% at 1600–4000 particles; small savings only around 400): bodytrack SBC off vs on, then each parameter (cameras, particles, layers, threads, serial model, BMP output) one at a time; LU-sweep method and report shape.

## 4. Speed and improvement — `performance/`

- ⭐ 🟢 [fresh-boot-experiments-2026-10-09.md](performance/fresh-boot-experiments-2026-10-09.md) — **the most important set of experiments so far**: every SBC on/off result with one fresh boot per window (LU alone sweep, LU + LU pairs, LU + FFT grid, LU + streamcluster), the observations across them, and the open questions. Data: `performance/data/2026-10-09-fresh-boot/`. Since 2026-10-12 it also has an "on vs off at a glance" list and a pointer to bodytrack (task 016).
- 🟢 [sbc-findings-2026-09-25.md](performance/sbc-findings-2026-09-25.md) — **the rule** (SBC pays only while a set's overflow fits its partner's empty ways): what the envelope settles, the two figures of merit it overturns, and what to build.
- 🟢 [board-calib-envelope-2026-09-25.md](performance/board-calib-envelope-2026-09-25.md) — the 80-point envelope sweep, 64 KB 16-way. Method and caveats.
- 🟢 [board-calib-envelope-8way-64kb-2026-09-25.md](performance/board-calib-envelope-8way-64kb-2026-09-25.md) · 🟢 [board-calib-envelope-8way-1mb-2026-09-26.md](performance/board-calib-envelope-8way-1mb-2026-09-26.md) — the same sweep at 128 and 2048 sets. ⚠️ Both hold the **per-set** overflow fixed while scaling the cache, so they say "if a workload has this shape, SBC helps at any size" — **not** "a real workload will have this shape at that size".
- 🟢 [board-dualcore-l2miss-calib-2026-09-24.md](performance/board-dualcore-l2miss-calib-2026-09-24.md) — the calib instrument on two cores.
- ⚠️ [parsec-dual-1mb-8way-2026-09-30.md](performance/parsec-dual-1mb-8way-2026-09-30.md) — blackscholes and swaptions on the 1 MB dual-core image. **One-boot method** (off, then on, on the same boot) and a 1 MB setup that is now put aside; do not quote it as an SBC effect. *Added to this list 2026-10-12.*
- ⚠️ [lu-single-app-2026-10-02.md](performance/lu-single-app-2026-10-02.md) — LU single-application sweep, one boot for 72 runs. **Its small-point results are wrong** (board age); note at the top gives the fresh-vs-fresh numbers. Replaced by the ⭐ fresh-boot report.
- ⚠️ [lu-streamcluster-pair-2026-10-05.md](performance/lu-streamcluster-pair-2026-10-05.md) — LU + streamcluster on one L2. **The −12.5% headline was mostly board age** (fresh-vs-fresh −1.99%); note at the top. Replaced by the ⭐ fresh-boot report.
- 📘 [board-128kb-omnetpp-2026-09-17.md](performance/board-128kb-omnetpp-2026-09-17.md) — *(one-boot method)* first 128 KB board A/B (tag `sbc-007-c2-breakeven` behaviour, no B7-1 fix): break-even, +0.16% memory traffic, +0.15% cycles; 7.9% of accesses pay a second search that almost never hits. Has the exact commands.
- 📘 [fpga-ab-baseline-2026-09-11.md](performance/fpga-ab-baseline-2026-09-11.md) — the first same-work board numbers (+32% to +51% cycles), from before task 007 fixed the loss. Section 4 is the main result.
- 📘 [why-sbc-loses-2026-09-15.md](performance/why-sbc-loses-2026-09-15.md) — why our SBC lost where the paper wins (2026-09-15). Its main points were acted on: task 007 made moved lines evictable and made pairings end.
- 📘 [plru-plan-2026-09-17.md](performance/plru-plan-2026-09-17.md) — the PLRU plan; **done** as task 008.
- 📘 [fix-plan-follow-the-paper-2026-09-16.md](performance/fix-plan-follow-the-paper-2026-09-16.md) — what the paper actually does (read from the PDF) and four rule changes. **Done** as task 007 C1–C3; C4 (a cap on moved lines) staged, not applied. Section 0 still holds the heat-counter reading.
- 📘 [workplan-parked-occupancy-2026-09-11.md](performance/workplan-parked-occupancy-2026-09-11.md) — the September plan (why SBC was slower). Replaced by tasks 007, 008 and the envelope findings.
- 📘 [omnetpp-differential-2026-09-10.md](performance/omnetpp-differential-2026-09-10.md) — first board test (fixed time, not fixed work). Its moved-line analysis still holds.
- 📘 [destination-side-blocker.md](performance/destination-side-blocker.md) — why most moves were refused at the target set (2026-08-24).
- ⚠️ [matmult-differential-2026-08-25.md](performance/matmult-differential-2026-08-25.md) — first simulation test. Numbers are old — do not quote.

## 5. Daily reports — `daily-summary/`

- 📘 [2026-08-18](daily-summary/2026-08-18.md) — why lines almost never moved, and the fix.
- 📘 [2026-08-21](daily-summary/2026-08-21.md) — two bugs fixed; first fully clean test run.
- 📘 [2026-08-28](daily-summary/2026-08-28.md) — two theories about bad data tested and ruled out.
- 📘 [2026-08-31](daily-summary/2026-08-31.md) — a moved line served correct data in a real program.
- 📘 [2026-09-01](daily-summary/2026-09-01.md) — what our tests check, in plain English.
- 📘 [2026-09-03](daily-summary/2026-09-03.md) — bug fixed, first fair on/off number, gap to the paper.
- 📘 [2026-09-08](daily-summary/2026-09-08.md) — feature-off build; what SBC costs in area and timing.
- 📘 [2026-09-10](daily-summary/2026-09-10.md) — first board test; why the hit rates cannot be trusted yet.
- 📘 [2026-09-11](daily-summary/2026-09-11.md) — task 006 built (none for 09-12 / 09-13 — those runs are in the 2026-09-14 weekly report).
- 📘 [2026-09-17](daily-summary/2026-09-17.md) — PLRU on the board: it wins on the plain L2, SBC on top still ties.
- 📘 [2026-09-24](daily-summary/2026-09-24.md) — which image hung the board, proved by hash.
- 🟢 [2026-09-26](daily-summary/2026-09-26.md) — the envelope at three geometries, and **omnetpp in the paper's own configuration**: its miss rate reproduced, SBC still at parity.
- 🟢 [2026-09-27](daily-summary/2026-09-27.md) — the paper's Tables 1 and 2 read from the PDF, **M18 gap 1 confirmed in the RTL**, and two tracker rows corrected.
- 🟢 [2026-09-30](daily-summary/2026-09-30.md) — SPLASH-2 FFT padding verified in source, page coloring as the likely reason omnetpp shows no imbalance, and the missing destination throttle.
- 📘 [2026-10-01](daily-summary/2026-10-01.md) — **latest day log**: the 256 KB 8-way PARSEC session (a side task, not task 013). No day logs after this; 2026-10-02 to 10-12 are in the task REPORTs (015, 016) and the fresh-boot report.

## 6. Weekly reports — `weekly-report/`

- 📘 [2026-08-21.md](weekly-report/2026-08-21.md) (+ `.html`) — moving lines works end to end.
- 📘 [2026-08-31.md](weekly-report/2026-08-31.md) (+ `.html`) — a moved line served real data for the first time.
- 📘 [2026-09-14.md](weekly-report/2026-09-14.md) (+ `.html`) — first fair test on the board: SBC is slower. Replaces the 09-08 weekly plan.
- 📘 [weekly-plan-2026-09-16.md](weekly-report/weekly-plan-2026-09-16.md) — the plan for that week: find out whether SBC loses because our rules are wrong or because the idea does not fit this cache. *Added to this list 2026-10-12.*
- 📘 [2026-09-25.md](weekly-report/2026-09-25.md) — the operating envelope, and the start of the optimization phase.
- 📘 [2026-09-30.md](weekly-report/2026-09-30.md) — the paper's configuration reached, SBC still at
  parity, and the saturation-counter defect.
- ⚠️ [2026-10-07.md](weekly-report/2026-10-07.md) — **latest; headline corrected 2026-10-08**: the pair
  result (−12.58%, 29.5% of interference repaired) was mostly board age; fresh-vs-fresh it is **−1.99%**
  (p = 0.073). Also: the counter chain is identity-verified and the "−3.77% best single-app" figure is
  **withdrawn**. The open question (multi-application effect, or just a low hit rate?) is task 015's;
  its section 7 is the plan.

## 7. Design notes and diagrams — `design/`

- 📘 [SBC_integration_plan.md](design/SBC_integration_plan.md) — the original plan (phases 0–4) and the decisions we locked in.
- 📘 [SetBalanceUnit_design.md](design/SetBalanceUnit_design.md) — early design note for the set-tracking block.
- 📘 [SBC_implementation_challenges.md](design/SBC_implementation_challenges.md) — list of risks: bad data, deadlock, lost updates.
- ⚠️ [diagram.md](design/diagram.md) — part A (moving a line) is still right; part B (copy it back home) was never built.

## 8. Bugs — `bugs/`

- 🟢 [bug-fix-log.md](bugs/bug-fix-log.md) — every bug found, with its status, plus lessons for debugging.

## 9. Background — `background/` and retired files

- 📘 [rolan-et-al-SBC.md](background/rolan-et-al-SBC.md) + [178-rolan-1-2.pdf](background/178-rolan-1-2.pdf) — the SBC paper.
- ⛔ `obsolete_files_do_not_refer/` — 10 retired files. Do not use. Retired on 2026-09-14:
  `complexity-comparison.md`, and `spec-sbc-phase3-prereqs.md` (its still-built parts are now at the
  end of [tasks/phase-3.md](tasks/phase-3.md)).

---

## 10. Folder layout — applied 2026-09-14, updated 2026-10-12

```
rocket-chip-inclusive-cache/
├── CLAUDE.md                              (stays here)
└── ai-documents/
    ├── README.md                          this file
    ├── guides/                            5 guides (section 2)
    │   ├── fpga-linux-run.md                  build, boot, measure
    │   ├── fresh-boot-campaign-handoff.md     how a fresh-boot campaign runs
    │   ├── devmem-register-map.md
    │   ├── priority-orders.md
    │   └── cache-terminology.md
    ├── tasks/                             phase records and plans, history (section 3a)
    ├── coder/                             000 … 016, each TASK.md + REPORT.md (section 3b)
    ├── performance/                       board results and analyses (section 4)
    │   ├── fresh-boot-experiments-2026-10-09.md   ⭐ main results
    │   ├── sbc-findings-2026-09-25.md             the rule
    │   ├── board-calib-envelope-*.md  + figs-calib-envelope-*/   envelope sweeps
    │   ├── … older reports and plans
    │   └── data/                          archived CSVs and transcripts, one folder per data set
    │       └── 2026-10-02 … 2026-10-09-fresh-boot/
    ├── daily-summary/                     day logs, 2026-08-18 … 2026-10-01 (section 5)
    ├── weekly-report/                     weekly reports and one weekly plan (section 6)
    ├── design/                            4 design notes (section 7)
    ├── bugs/
    │   └── bug-fix-log.md
    ├── background/
    │   ├── rolan-et-al-SBC.md
    │   └── 178-rolan-1-2.pdf
    └── obsolete_files_do_not_refer/       10 retired files
```
