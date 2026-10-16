# Coder task 020 — Make SBC obey the TileLink rules for write-backs (fix the dual-core freeze)

**Filed:** 2026-10-15 ~22:00 · **Status:** open, not started. The fix plan is a draft and **waits for the user's decisions (§7)**.
**This file:** `chipyard/generators/rocket-chip-inclusive-cache/ai-documents/coder/020-tilelink-release-merge/TASK.md` (the work order; do not edit it).
**Your report:** `REPORT.md` next to this file. Create it from the template in §16 and fill it in as you go.
**Also in this folder (read both before you start):**
- `BUG-dual-core-release-deadlock.md` is the full bug record. It covers the board facts, the simulation reproduction, the freeze step by step, and how to reproduce it.
- `PLAN-DRAFT.md` is the draft fix plan the user saw. **This TASK supersedes it** where they differ (§6.0 lists the differences).

This file is written so that a Sonnet session can run the whole task from it alone. Follow it literally.
If this file and your own judgment disagree, follow this file and write the disagreement in `REPORT.md`.
If a case is not covered here, stop and ask. Never guess about the board, the build or the RTL.

```bash
CY=/home/damith/Research/repos/chipyard_performance_eval/chipyard
L2=$CY/generators/rocket-chip-inclusive-cache          # the L2 generator repo (its own git repo)
SRC=$L2/design/craft/inclusivecache/src                 # the RTL
SWD=$L2/sw
HUNT=$CY/scripts/logs/clean-runs/dual-hang-hunt-20261015       # task 019's hunt + simulation folder: READ ONLY
NEW=$CY/scripts/logs/clean-runs/tilelink-merge-020-<YYYYMMDD>  # this task's folder (date = the day you create it)
```

---

## 0. Rules — read before anything else

1. **Talk to the user in plain English.** Every reply to a question or suggestion follows the skill
   `~/.claude/skills/plain-english-answers/SKILL.md`. Answer first. Use short, complete sentences, not text chunks. Say what every
   number means, spell out each abbreviation once, and keep "measured", "we think" and "not known" apart. Long detail goes in
   `REPORT.md`, and the chat gets a plain summary. The user has corrected this twice.
2. **Plan first.** Read this whole file, `BUG-dual-core-release-deadlock.md` and `PLAN-DRAFT.md`. Then reply with a plan of at
   most 10 lines and the questions of §7. **Touch nothing until the user answers §7 and says go.**
3. **Stop points** (wait for the user at each one):
   - (a) after your plan and the §7 questions;
   - (b) after the "before" baselines on the unchanged RTL (§9), before the first RTL edit;
   - (c) after the RTL changes and the simulation verification V1–V4 (§10), before any FPGA build;
   - (d) after the FPGA builds V5 (§11), before any board use;
   - (e) after the board checks V6 and V7 (§12);
   - (f) after the documentation and the close-out (§13).
   Any failed check is also a stop (rule 14).
4. **No RTL edit before stop (a) is passed.** Until then `design/` must equal commit `494ab5e`:
   `git -C $L2 diff --quiet 494ab5e -- design/ && echo same` prints `same`, and `git -C $L2 status --porcelain design/` prints nothing.
5. **Git.** Until the user answers §7 Q3, commit nothing in either repo. Even after that, never run `git checkout <file>`,
   `git restore`, `git reset --hard`, `git clean`, `git stash` or `git add -A`. Add files by name only. The L2 repo has
   uncommitted changes you did not make (`ai-documents/bugs/bug-fix-log.md`, several guides, `.obsidian/`, `scratch/`, and more). Never
   commit them, never revert them, and never touch them.
6. **The board is shared.** On 2026-10-15 evening another session started an FFT sweep on the board
   (`$CY/scripts/logs/clean-runs/fft-sweep-dstmode1-single-core-512k-20261015/`). Nothing before stop (d) needs the board.
   Before any board step (§12), these two commands must print nothing:
   ```bash
   ps -eo args= | grep -aE "^(/usr/bin/)?expect" | grep -aE "run_|reboot_and_handover|hunt_"
   fuser /dev/ttyUSB2
   ```
   If either prints something, the board is busy: wait and tell the user. Never kill a launcher, a harness or its tmux session
   while a window runs. Never kill old tmux sessions (`sbc009fix`, `sbc012`, `sbc015-*`, `sbc016-bt`, `b019`, `hunt1`..`hunt8`, …).
   An open tmux session alone does not mean a run is active.
7. **Never pass any `-allow-*` option. Never use `-resetall` or `sbc_read --reset-all`.**
8. **Binaries that must stay byte-identical** (check before and after your work with `sha256sum`):
   - `$SWD/build/sbc_read` must start `8241eb8b…`;
   - `$SWD/build/018/sbc_read` must start `b103216d…`;
   - `$CY/software/parsec-benchmark/staged/lu_ncb` must start `cb5dadbc…`.
9. **Never edit:** `$CY/scripts/ssbc_scripts/run_parsec_session.exp`, `$CY/scripts/ssbc_scripts/lib/*`,
   `$CY/scripts/logs/clean-runs/tools/results_common.py`, anything in an earlier campaign folder (including `$HUNT` and the 018 and
   019 LU campaigns), the TASK, REPORT or HANDOFF files of tasks 017, 018 and 019, and any **existing** config class. Adding new
   files and new config classes is allowed. When you need a changed copy of an existing tool, copy it into `$NEW/tools/` and edit the copy.
10. **Every change must be a no-op when SBC is off.** Put every new piece of logic under `if (params.micro.enableSetBalancing)`, or
    make it reduce to the stock expression when that flag is false. The `…NoSbcConfig` simulation must stay cycle-identical to the
    baseline (§10, V4). This is a standing rule of the repo (`CLAUDE.md`, "Conventions").
11. **Simulation-only logic stays simulation-only.** Asserts and `printf`s already sit inside `ifndef SYNTHESIS`. New debug printfs
    go under `if (params.micro.sbcDebug)`.
12. **Give the user every log path** when a run starts (`CLAUDE.md`: "when running something internally, give me the link of the log").
13. **`RESULTS.md`, if you make one, holds results only.** Analysis goes in `REPORT.md`.
14. **When a check fails, stop.** Write what you saw in `REPORT.md` and ask. Do not "fix forward" through a failed gate.
15. **Build only with `$CY/scripts/ssbc_scripts/build_bitstream.sh`.** Before each FPGA build, delete that config's
    `fpga/generated-src/chipyard.fpga.vcu118.VCU118FPGATestHarness.<CONFIG>` folder. Never run `make clean` in `fpga/`. Cite every
    image by its sha256, never by its file name alone.
16. **The fix must not lower the measured performance.** This is the user's explicit condition. §8 says why it should hold, and V4 and V7 prove it.

---

## 1. Goal, in plain words

On a two-core chip with SBC on, the whole board freezes within seconds. Task 019 found the cause in simulation.
When a core writes back a **parked** line, the L2 has to search the partner row before it can acknowledge the write-back. That
search waits while any other request slot works in the partner row. The slot working there can be the very request the write-back
interrupted. That slot is either frozen by the scheduler (Form A) or waiting for a probe answer that the releasing core will not
send until it gets its acknowledgement (Form B). Each side waits for the other, and both cores stop.

The TileLink spec forbids this. A write-back must always be accepted and answered, and a write-back that collides with another
transaction on the same line must be **merged** into it, never made to wait.

The job has four parts:
1. Make the L2 obey those rules for parked lines, including six related gaps found by reading the code (§5).
2. Change **no** SBC decision, so performance does not move.
3. Prove it in simulation. Two-core runs must finish with correct data, and single-core counters must be unchanged.
4. Then prove it on the board. The dual-core image must stop freezing, and the single-core LU result must stay the same.

---

## 2. Background you need (verified 2026-10-15; do not re-derive it, but check it in §9)

### 2.1 Words

| Word | Meaning |
|---|---|
| write-back | TileLink `Release` / `ReleaseData` on channel C: a core gives a line back to the L2. In the RTL, a request with `prio(2)` |
| write-back acknowledgement | `ReleaseAck` on channel D |
| probe, probe answer | `Probe` (channel B) asks a core to give up or downgrade a line. The answer is `ProbeAck` or `ProbeAckData` on channel C |
| request slot | an MSHR. There are 7 slots: 0–4 are normal (`abc_mshrs`), 5 is `bc_mshr`, and 6 is `c_mshr`, which is reserved for write-backs |
| home row S, partner row P | S is the set a line's address maps to. P is the set where SBC parks it. Pairing is strict 1:1, and a destination is never a source |
| parked line | a displaced line (`displaced = 1`). It sits in row P, but its address belongs to row S |
| serve in place | the L2 answers a request for a parked line straight from row P (`inPlace`, task 003 stage 2e). The slot's `physSet` becomes P |
| nest | a write-back to a set whose slot waits for a probe answer or a grant goes into `c_mshr` (`nestC`). The normal slot of that set is paused (`mshr_stall_abc`) |
| merge broadcast | `nestedwb`: when the nested slot writes the directory, it tells the other slots so they patch their copy of the line's metadata |

### 2.2 What was measured

- **Board (task 019).** The dual-core 512 KB image (sha256 `f5e3608f…`) freezes the whole board with SBC on, with setting 0 and
  with setting 1. Our own two-thread store pattern froze it in under 7 seconds, three times. LU at a 2048 × 2048 matrix with
  128 × 128 blocks froze it 4 times in 5. With SBC off it never froze. The single-core image (`502649e2…`) never froze.
- **Simulation (task 019).** A two-core, 4 KB L2 Verilator model froze in 5 of 5 runs, in both forms. The controls finished with
  correct data: migration off, and one core idle (1,847 parked-line write-backs). The tables and logs are in
  `BUG-dual-core-release-deadlock.md` §3.

### 2.3 Code anchors (RTL = `494ab5e`, line numbers checked 2026-10-15)

Scheduler (`$SRC/Scheduler.scala`):

| Line | What |
|---|---|
| 107–109 | `m.io.sinkc.valid`: a probe answer is delivered to **every** slot whose `probeSet` and `probeTag` match. There is no `probeAckPending` term (D5) |
| 124–127 | `mshr_stall_abc`: normal slots of the same home set pause while `c_mshr` or `bc_mshr` is valid |
| 141–151 | `mshr_request`: which slots may be scheduled this cycle |
| 225–233 | `nestedwb` producer. It comes **only** from `c_mshr` and `bc_mshr` (`select_c`, `select_bc`) and is keyed by that slot's `homeSet` and `tag` |
| 245 | `setMatches`, which uses `homeSet` |
| 279–282 | `dstSetConflict`, the migration-destination fence |
| 289–291 | `secSetConflict`, the partner-search fence |
| 292 | `allocReady = alloc && !dstSetConflict && !secSetConflict` (D2) |
| 308, 312 | `blockC`, `nestC` |
| 387–389 | `partnerBusy`: true when another valid slot's `physSet` equals my partner row |
| 417–420 | `request_alloc_cases` (uses `allocReady`) |
| 428–429 | `request.ready`: the existing `prio(2)` exemption from `dstSetConflict` (bug P1). It covers the queue and nest path only |
| 442–446 | the C-entrance watchdog `cHeadBlocked` (it counts only `dstSetConflict` refusals) |
| 497–503 | `busyWays`, the way-lock mask sent with every directory read |
| 537–543 | `c_mshr` allocation on `nestC` |
| 549 | `directoryFanout`, one cycle after the read (`dirReg` is false in our configs) |
| 565–582 | the way CAM for probe-answer data. It **does** check `probeAckPending`, and an unmatched answer gets way 0, set 0 (D6) |

MSHR (`$SRC/MSHR.scala`):

| Line | What |
|---|---|
| 125–133 | `NestedWriteback` bundle |
| 236–248 | the comment justifying the `partnerBusy` wait. Its deadlock argument covers waits inside the L2 only |
| 445–451 | `nestedwb` consumer. It matches `io.nestedwb.homeSet === request.set` (D3: it should be the line's home) |
| 456 | `physSet = Mux(inPlace, pairSetReg, request.set)` |
| 462–465 | `lineHome`: the home set of the line in `meta`. For a destination-side slot holding a parked line, it is the partner |
| 485–487 | `probeSet`, `probeTag`, `probeAckPending` |
| 494–495 | `blockC := !meta_valid` and `nestC` |
| 534–538 | way-locks. `lockBorrowed = !s_sinval \|\| inPlace` locks a served parked way, and the victim lock covers the chosen victim |
| 557 | `assert (!io.status.bits.nestC \|\| !io.status.bits.blockC)`. **F1d must respect this** (§6, F1d) |
| 579–591 | `no_wait`, `mig_dir1`, `mig_ready = !migrating \|\| (s_dmeta && w_dread)`, `sec_dir1`, `sec_ready = !searching && s_sinval` |
| 638 | `d_ready = w_pprobeack && w_grant && sec_ready`: the acknowledgement waits for the search |
| 667 | `doSearch = searching && !s_ssearch && !io.partnerBusy` (D1) |
| 867–878 | `cDirty` and the Release opcode. These read `meta.dirty` **when the Release is sent**, so a late merge broadcast still turns it into `ReleaseData` |
| 931–932, 966–1047 | the stock state-transition check. Its `when` at 966 fires on any schedule step with `!s_writeback && no_wait` (D7) |
| 1051–1072 | probe-answer handling (`probes_done`, `probes_toN`, `w_*probeack*`, the `meta.dirty` set on `ProbeAckData`) |
| 1155–1236 | `armEviction`. A parked victim is probed and then released at `lineHome` |
| 1384–1400 | the liveness watchdog "MSHR wedged" (20,000 cycles) |
| 1489–1610 | the partner-search result. Line 1545 re-points `meta` at the parked entry. Line 1605 asserts that a C request never misses |
| 1685–1755 | assess-reset. The asserts at 1725 (`!migrating`) and 1735 (destination write-back) false-alarm on two cores (D7) |
| 1759–1797 | `armSearch`. The C branch arms the search at 1772 |

Directory (`$SRC/Directory.scala`):

| Line | What |
|---|---|
| 132 | `val write = Queue(io.write, 1)`. A scheduled directory write reaches the SRAM one cycle later at the earliest |
| 162 | `write.ready := !io.read.valid` (a read takes the port first) |
| 176–177, 286–288 | the write bypass. A write waiting at the queue output while a read's result forms is merged into that result (`setQuash`) |
| 221 | `freeWays = ~busyWays` |
| 315–317 | `secHits = … & freeWays`. **The partner search cannot see a way-locked line** (D8, new) |
| 320–323, 338 | `secLockedDirty` and the "scenario 10" assert |

Rocket (master side, for reference): `DCache.scala:767-770` refuses a probe while a release is in flight or a same-address release
acknowledgement is due. `:810` holds a dirty probe answer while `release_ack_wait`. `acquireBeforeRelease = false`, so a core
sends its victim's write-back before its next Acquire.

---

## 3. The TileLink rules (spec 1.8.1) and what they require of our L2

- **Chapter 5, rule 3.** An agent must eventually accept a presented beat and answer a received request, unless it has a
  higher-priority message of its own in flight or unanswered. For our L2 the priority order is, lowest first: A from cores, A to
  memory, B from memory (unused), **B to cores (probes)**, **C from cores (write-backs and probe answers)**, C to memory, D from
  memory, D to cores, E from cores, E to memory. So a write-back may wait only on our own C-to-memory, D or E messages, or on
  bounded internal work. It may **never** wait on a probe answer, on another slot's grant, or on memory's answer to an A request.
- **§9.2, concurrency limits.**
  - The slave sends no Grant while a ProbeAck is due on the block.
  - The slave sends no Probe while a GrantAck is due.
  - After a Release, the master sends no ProbeAck, Acquire or Release on that block until the ReleaseAck arrives.
- **§9.2, the merge text.** "The slave cannot simply block the voluntary Release transaction until the Acquire transaction
  completes … The slave must accept the voluntary Release as well as any overtaken ProbeAcks … This scenario is an example of two
  transaction message flows being merged by the slave agent."

| # | Obligation |
|---|---|
| O1 | Accepting and answering a write-back waits only on bounded internal work or on our own C-to-memory, D or E messages |
| O2 | A write-back that meets another transaction on the same line is merged. Its data goes into the line, every slot that holds the line sees the new dirty state, and the other transaction finishes with that data |
| O3 | At most one probe per line is outstanding, and each probe answer reaches the slot that sent the probe |
| O4 | No grant while one of the line's probe answers is due |
| O5 | No probe while the line's grant acknowledgement is due |
| O6 | Every mechanism that names a line (merge, probe routing, probe address, write-back address) names it by home set and tag, never by the row it sits in |

The stock SiFive cache meets O1 and O2 with one rule: a write-back to a busy set nests into `c_mshr`, which never waits for
anything, and it patches the paused slot through `nestedwb`. All of this works only because both actions are on **one** set. SBC
splits a parked line between rows S and P, and these rules were never carried across that split.

**Rules SBC already keeps** (checked, keep them working):
- **O3 for A requests.** An A-request search waits for `partnerBusy` and blocks new row-P slots (`secSetConflict`). A served line
  stays locked from the search answer to retire (`lockBorrowed`).
- **O5.** The same lock lasts until the serving slot retires, which is after the grant acknowledgement.
- **O6 for probes and memory write-backs.** Both use `lineHome`.
- **A destination is never a source** (`SetBalanceUnit.scala:163-164, :180`). So a write-back of a native line never searches.

---

## 4. Where SBC breaks the rules

| # | What goes wrong | Rule | Evidence |
|---|---|---|---|
| D1 | A write-back of a parked line waits for `!partnerBusy` (MSHR:667), and its acknowledgement waits for the search (MSHR:638). **Form A:** the slot holding row P is the same-set slot the write-back pre-empted, and `mshr_stall_abc` freezes it. **Form B:** that slot also waits for the releasing core's probe answer | O1, O2 | **Reproduced**, 5 of 5 two-core runs |
| D2 | A write-back that needs a **new** slot on a set fenced by a migration or a search is refused at the entrance (`allocReady`, Scheduler:292) | O1 (rule 3i) | By reading. Bounded, so it does not freeze, but it breaks the rule and delays every probe answer queued behind it |
| D3 | A row-P slot evicting parked line X (X is its chosen victim, and it probes X's holder) never learns that the holder's write-back made X dirty. The broadcast comes only from the special slots and is matched against the consumer's own set (P), not X's home (S). The evictor would send X to memory as clean and lose the data | O2, O6 | By reading. Hidden behind D1 today |
| D4 | Two same-cycle windows in the merge. (a) A write-back nests while the same-set slot's search answer is in flight. That slot then re-points `meta` (MSHR:1545) to a snapshot without the merge. (b) A row-P slot's directory result arrives in the very cycle the broadcast fires. Its `meta := new_meta` (MSHR:1687) overrides the patch, and the Directory bypass covers only writes queued **before** that cycle (§6, F1e) | O2 | By reading. Two cores only. Hidden behind D1 |
| D5 | A probe answer reaches every slot whose probe key matches, waiting or not (Scheduler:107–109). A non-probing slot collects stray `probes_done` and `probes_toN` bits (MSHR:1059–1061), and those are cleared only once per request (MSHR:1697). SBC can start a probe later in the same request (MSHR:1508, :1581), so a stray bit makes that probe wait forever or finish before the real answer | O3, O4 | By reading. Needs both cores on one parked line |
| D6 | A data-carrying probe answer that no waiting slot matches is written to set 0, way 0 (the way CAM defaults to zero) | O3 | Follows from D5 |
| D7 | Two simulation-only checks fire falsely on two-core traffic. MSHR:1725 and :1735 fire on a same-line `repeat` reload in the cycle a migration retires. The transition check at MSHR:966 fires on the write-back's search step, before `meta` points at the parked entry | checker bugs | **Reproduced**, explained cycle by cycle |
| **D8** | **New in this TASK, not in PLAN-DRAFT.** The partner search ignores way-locked lines (`secHits & freeWays`, Directory:315–317). If another slot has locked X's way, a write-back of X **misses** X. Two cases: (i) Form B, where the same-set slot serves X in place (`lockBorrowed`); (ii) D3, where the row-P evictor locked X as its victim. After a miss, the C request writes its line into the home row's victim way. In simulation that trips MSHR:1605. **On the board it corrupts two lines** | O2 | By reading, 2026-10-15 22:00. Today D1 makes the write-back wait in both cases, so the search never runs. **Removing the wait (F1a) without F1f turns Form B into silent corruption** |

---

## 5. Notes found while writing this task (the critical review)

- **N1 = D8.** This is the biggest change to the draft. F1a alone was already known to be unsafe because of D3. It is also unsafe
  because of D8, and D8 hits the **reproduced** Form B, not only a rare race. F1f below closes it.
- **N2.** F1d as drafted ("`blockC` also covers the in-flight search") would raise `blockC` while `nestC` is true, and that trips
  the assert at MSHR:557. F1d must also lower `nestC` in that window.
- **N3.** D4(b) is narrower than the draft said. The Directory's one-entry write queue plus its bypass already merges any write
  queued before the result cycle (Directory:132, :176–177, :286–288). What is left is the result cycle itself, where the MSHR's
  `meta := new_meta` overrides the broadcast patch. So F1e is a one-cycle hold with `dirReg = false` (two cycles with `dirReg = true`).
- **N4. Order matters.** Remove the waits (F1a, F1b) **last**. Every earlier commit is then no worse than today, because each
  merge fix sits behind the wait it will later need.
- **N5.** The V2 cost in the draft was too low. A 200,000-step run takes about 15 minutes of wall time on the 6-thread simulator
  (the 100,000-step controls took 7.5 minutes each, four at a time on 24 cores). So 60 runs take about 4 hours, four at a time.
- **N6.** Today the board is busy with another session's FFT sweep. Simulation work does not need the board.

---

## 6. The fix — implement exactly this, in this order (after the user approves §7)

### 6.0 Differences from PLAN-DRAFT.md

- F1f is new (D8).
- F1d also lowers `nestC` (N2).
- F1e is a one-cycle hold (N3).
- The commit order is fixed (N4).
- The probe-routing change is gated by SBC, so the baseline stays bit-exact (rule 10).

### 6.1 Steps (one commit each if the user allows commits; otherwise the same steps, uncommitted)

**C0 — F3: simulation-only check fixes, watchdogs and coverage printfs** (no hardware change)
1. **MSHR:1725.** `assert (!migrating, …)` fires on a `repeat` reload in the cycle a migration retires. The reload needs
   `mig_ready`, and `mig_ready = !migrating || (s_dmeta && w_dread)`, so a reload in that cycle is legal. Change the condition to
   exempt a migration that completes in this cycle. The likely form is `assert (!migrating || mig_ready, …)`. Check this against the
   code, and explain the chosen form cycle by cycle in `REPORT.md` with the `rep_share_dst1` event (both asserts fired in one cycle there).
2. **MSHR:1735.** The destination write-back assert fires in the same cycle. Find the term that is legitimately still set in a
   retiring cycle, exempt exactly that, and explain it the same way. A real drop (an unfinished `dstEvict`) must still fire.
3. **MSHR:966.** Enable the stock transition check only on the real final directory write. Use exactly the writeback term of
   `io.schedule.bits.dir.valid` (`!s_writeback && no_wait && mig_ready && sec_ready`) together with `io.schedule.ready`.
4. **Write-back progress watchdog** (new, in MSHR, under `enableSetBalancing`). Count cycles while `request_valid && request.prio(2)`.
   Assert below a limit (start with 4,000) with the same scoreboard print as the "MSHR wedged" assert. Report the largest value
   seen in V2, and keep the limit at least 4 times that.
5. **C-entrance watchdog** (Scheduler:442). Widen `cHeadBlocked` to every refusal: `sinkC.io.req.valid && !request.ready`. Keep a
   separate counter for the old `dstSetConflict`-only meaning, or drop it if it becomes redundant, and say which. Set the limit
   the same way as in step 4.
6. **Probe-answer uniqueness** (Scheduler, under `enableSetBalancing`). When `sinkC.io.resp.valid`, exactly one slot must match
   (`probeAckPending` and key). Before F2 this check **is expected to fire** in two-core runs, if D5 happens at all. Add it as a
   counter plus `printf` (`[SBC][SCHED] PROBEACK-STRAY …`, under `sbcDebug`) in C0. Turn it into an assert in C1.
7. **Coverage printfs** (under `sbcDebug`, one line per event, with a fixed tag so V3 can count them):
   - `C-SEARCH-BUSY`: a C search issued while `partnerBusy` was true (F1a path; from C4 on).
   - `C-FENCE-PASS`: a C request allocated a new slot while `dstSetConflict` or `secSetConflict` was true (F1b; from C4 on).
   - `C-SEARCH-LOCKED`: a C search hit a way that `busyWays` would have masked (F1f).
   - `NESTWB-XROW`: a merge broadcast matched a slot whose `request.set` differs from the broadcast's home set (D3 merge, F1c).
   - `BLOCKC-SEARCH`: F1d held a write-back back.
   - `DIRW-HOLD`: F1e held a directory write back.
   - `PROBEACK-STRAY`: see step 6.
8. **After C0** the stopping two-core simulator must reproduce the freeze as a **named** assert (the new write-back watchdog or
   "MSHR wedged") instead of a false alarm. Run `drd_noshare_dst1` and `drd_share_dst1` on it. This proves C0 before any behaviour change.

**C1 — F2: probe answers reach only the waiting slot** (fixes D5 and D6)
- Scheduler:107–109. Add `m.io.status.bits.probeAckPending` to the `sinkc.valid` match, inside `if (enableSetBalancing)`, so the
  SBC-off build keeps the stock expression.
- Turn the C0 uniqueness check into an assert: when `sinkC.io.resp.valid`, the number of matching slots is exactly 1.
- Expect no change on one core, where V4 must show identical counters.

**C2 — F1c + F1f: the merge reaches every holder, and the write-back always finds its line** (fixes D3 and D8)
- **F1c, producer** (Scheduler:225–233). In addition to the `c_mshr` and `bc_mshr` cases, broadcast `c_set_dirty` from **the selected
  slot**, whichever it is, when it is a C request (`prio(2)`) and its `schedule.dir` write is valid and dirty. `homeSet` and `tag`
  must be that slot's. You need a new status bit, for example `io.status.bits.isRelease := request.prio(2)`. Keep the `b_*` fields
  exactly as they are. Under SBC off, keep the stock producer.
- **F1c, consumer** (MSHR:446). Match `io.nestedwb.homeSet === lineHome` instead of `=== request.set`. `lineHome` already reduces to
  `request.set` when SBC is off. Check the three cases in a comment:
  - a row-S slot serving X in place has `lineHome = S`;
  - a row-P slot whose victim is X has `lineHome = S`;
  - a row-P slot with a native line has `lineHome = P`.
- **F1f** (new). A write-back's partner search must not be masked by the slot locks. Add a flag to the dread bundle, for example
  `io.schedule.bits.dread.bits.mergeSearch := doSearch && request.prio(2)`. When the read is such a search, the Scheduler sends
  `busyWays` with **only** the `lock2` terms (migration destinations) and leaves out the `lockValid` terms (Scheduler:497–503).
  Why this is safe, to restate in the code comment:
  - The locks protect victim choice and serving. A write-back is a merge, and it must find the line (O2).
  - The evictor cannot read X out before the releasing core answers its probe, and Rocket answers only after the
    acknowledgement. The acknowledgement and the write-back's directory write go out in the same schedule step (check this; see
    the V3 item "ack and write in one step").
  - A migration destination is never a held line, because held ways are never offered. So `lock2` can stay. Add an assert that a
    `mergeSearch` never hits a `lock2` way.
- After C2, MSHR:1605 must never fire.

**C3 — F1d + F1e: close the two same-cycle windows** (fixes D4)
- **F1d** (MSHR:494–495). Let `searchInFlight = searching && s_ssearch && !w_ssearch` (the search is issued and its answer is not in).
  Set `blockC := !meta_valid || searchInFlight` and `nestC := meta_valid && !searchInFlight && (old terms)`. Both changes go inside
  `if (enableSetBalancing)`, so SBC off keeps stock. The write-back waits 1 or 2 cycles, then nests with up-to-date metadata.
- **F1e** (Scheduler, inside `mshr_request`). Do not select a slot whose `schedule.dir` write targets row R while it is a C request
  served in place (`physSet =/= homeSet`), if a directory read of row R was issued in the previous cycle. Use a register of
  `directory.io.read.valid` and the set. With `dirReg = true`, also hold one more cycle (use `params.dirReg` to build the chain).
  This is at most a one-cycle hold, and only for write-backs of parked lines.
- **Alternative to F1e**, only if the user picks it in §7: on the result cycle, apply a matching broadcast to `new_meta` inside the
  MSHR. Do not build both.

**C4 — F1a + F1b: remove the waits** (fixes D1 and D2). Do this last (N4).
- **F1a** (MSHR:667). `doSearch = searching && !s_ssearch && (!io.partnerBusy || request.prio(2))`. Update the comment at
  MSHR:236–248. The wait stays for A requests: their line may be clean and unheld, so another slot could evict it silently during
  the search. A write-back's line is held by the releasing core, so it cannot be evicted before the acknowledgement (§6.1, F1f reasoning).
- **F1b** (Scheduler:292). `allocReady = alloc && ((!dstSetConflict && !secSetConflict) || request.bits.prio(2))`, inside
  `if (enableSetBalancing)`. Update the comment at Scheduler:421–427. Why it is safe:
  - A write-back neither evicts nor refills. Its line is native to the fenced set, because a destination is never a source, so it
    never searches there.
  - A migration destination way is never a held line.
- **B7-2 caution.** This touches the `allocReady` → `io.allocate` → `dstClaim` → `dstSetConflict` path that Vivado once reported as a
  combinational loop (LUTLP-1). V5 checks it.

**What must stay exactly as it is:**
- the saturation counters, the DSS, the pairing table and teardown;
- the migration start and abort rules and the destination setting (0, 1, 2);
- PLRU and random victim choice, and the way-locks for everything except a write-back's search;
- the A-request search and its `partnerBusy` wait, and serve-in-place;
- every counter and every MMIO register.

### 6.2 How the two freeze forms end after the fix (to check in V2 and V3)

- **Form A.** `c_mshr` searches row P at once (F1a), serves its line in place, acknowledges and retires. The paused slot then continues.
- **Form B.**
  1. `c_mshr` finds X even though the serving slot locked it (F1f).
  2. It writes the data and the directory entry, with dirty set and the releasing core removed.
  3. The broadcast sets `dirty` in the serving slot (F1c; same home set).
  4. The acknowledgement goes out. The core answers the serving slot's probe with "I hold nothing" (`ProbeAck` toN).
  5. The serving slot grants the merged data. This is the spec's Figure 30 flow.

---

## 7. Questions for the user at stop (a) (my defaults in brackets)

Ask these in plain English, in one message. Explain D8 first, because it changes the plan the user saw.

1. **Scope.** All of F1a–F1f, F2 and F3? [yes. F1a without F1c and F1f is unsafe: D3 loses data, and D8 corrupts lines in the
   reproduced Form B]
2. **F1e form.** A one-cycle hold of the directory write, or merging the broadcast into the arriving result inside the MSHR?
   [the hold. It is simpler to prove, and it costs at most one cycle and only for write-backs of parked lines]
3. **Git.** Work on a new L2 branch `sbc-020-tilelink-merge`, cut from `sbc-013-sat-counter`, with one commit per step C0–C4 and
   files added by name? Or commit nothing, as in task 019? [a branch with commits. A clean commit also gives the FPGA build a
   reproducible provenance; the build script warns on an uncommitted `design/`]
4. **V2 size.** The full matrix, which is 60 runs in about 4 hours? Or a reduced one: 3 seeds, 36 runs, about 2.5 hours? [full]
5. **Board depth.** V6 (two hunt boots, plus LU with two threads at n2048 b128 SBC off once and on twice) and V7 at n2048 b128, about
   6 to 7 hours of board time in total? Or also the 6-hour n4096 point? [without n4096]
6. **B8-1** (teardown races a deferred migration's partner claim; `ai-documents/bugs/bug-fix-log.md`). Keep it out of 020, and
   only watch for it in V2? [yes]

---

## 8. Performance: why it should not move, and how we prove it

- **Why.** No change touches what migrates, where it goes, which way is evicted, or whether a line is served from the partner
  row. The changes alter only these things:
  - (a) how soon a write-back finishes when the partner row is busy (sooner);
  - (b) whether a write-back is let in while a fence is up (sooner);
  - (c) merge and routing behaviour in races that today freeze or misroute;
  - (d) a hold of one or two cycles in two rare races.
- **One core can still see small differences**, through write-back timing. The instruction cache also sends requests that can
  occupy row P, so F1a can fire on one core too. Expect counters equal, or different only through that timing, and explain any
  difference.
- **Proof in simulation:** V4. **Proof on the board:** V7. The memory-access change of LU at n2048 b128, setting 1 against SBC off,
  must stay within about 1.5 points of −10.7% (replicate −9.7%).

---

## 9. Step 1 — preflight and the "before" baselines (no RTL change) → stop (b)

1. Run the checks of rules 4 and 8, and record the outputs in `REPORT.md`.
2. Record `git -C $L2 branch --show-current` (expect `sbc-013-sat-counter`), `git -C $L2 log --oneline -1` (expect `e572ffe`, docs
   only above `494ab5e`), and `git -C $L2 status --short`.
3. Create `$NEW` with `sim/`, `gates/`, `tools/`, `board/` and `bin-494ab5e/`.
4. **Keep the old simulators.** Copy (do not move) `$CY/sims/verilator/simulator-chipyard.harness-VerilatorRocket8KL116KL2SipTestDualDstConfig`
   and `…SipTestDualDstNoStopConfig` into `$NEW/bin-494ab5e/`. A later `make clean` or rebuild overwrites them.
5. **Single-core gate baseline.** Copy `$SWD/scripts/run_018_gate.sh` to `$SWD/scripts/run_020_gate.sh` (a new file). Change only the
   label prefix to `020-$TAG-` (`TAG=base` or `TAG=fix` from the environment). Run every stage g0–g6b with `TAG=base`, **one stage
   at a time**. Logs go to `$SWD/verilator_logs/`. Also run `$SWD/scripts/run_013_gate.sh` once. Record every PASS line and every
   counter line in `REPORT.md`, as the "before" table. `CLEAN=1` runs `make clean`, which **wipes every simulator build**, the dual
   ones included. That is why step 4 comes first. Use `CLEAN=1` only where the 018 script says so.
6. **Two-core baseline.** The freeze reproductions already exist (`$HUNT/sim/`). Re-run `drd_noshare_dst1` once on the copied
   old NoStop simulator, to show the environment still reproduces the freeze at the same cycle (4.31 million L2 cycles). Use a copy of
   `$HUNT/sim/run_nostop.sh` in `$NEW/tools/` whose `SIM` and `H` point at `$NEW`.
7. **Stop (b).** Report the baseline in plain English and wait.

---

## 10. Step 2 — the changes and the simulation verification → stop (c)

Make the changes in the order C0, C1, C2, C3, C4 (§6.1). After **each** step, rebuild both two-core simulators. Run the two
reproduction binaries and the single-core `g3` stage before you go on. If a step makes things worse than the step before, stop.

### 10.1 Simulators and tests

- **Configs** (chipyard `generators/chipyard/src/main/scala/config/RocketConfigs.scala`, uncommitted, added in task 019; do not edit them):
  - `VerilatorRocket8KL116KL2SipTestDualDstConfig`: two Rocket cores; a tiny data cache of 2 sets × 2 ways, so lines leave the L1
    often; a 4 KB L2 of 8 sets × 8 ways with SBC, PLRU and `dstModes`. Pairing is pinned 5↔6 (`sbcForceDstSet = 6`), and the
    shadow checkers and `sbcDebug` are on. The **stopping** simulator.
  - `VerilatorRocket8KL116KL2SipTestDualDstNoStopConfig`: the same hardware. It is built with `STOP_COND=0`, so asserts print but
    do not stop, as on the board.
- **Build** (each takes a few minutes; use 6 threads so that four runs fit on 24 cores):
  ```bash
  cd $CY && source env.sh
  make -C sims/verilator CONFIG=VerilatorRocket8KL116KL2SipTestDualDstConfig VERILATOR_THREADS=6 -j20
  make -C sims/verilator CONFIG=VerilatorRocket8KL116KL2SipTestDualDstNoStopConfig VERILATOR_THREADS=6 -j20 \
       "VERILATOR_PREPROC_DEFINES=+define+VERILATOR +define+STOP_COND=0"
  ```
  Check the elaboration output: the MSHR count is 7, and the SBC parameters match the old `$HUNT/sim/build_*.log`.
- **Test program:** `$SWD/dual_release_deadlock_test.c`. Build it with `$SWD/compile_dual_test.sh <test> <out-name> [-D…]`,
  which writes `$SWD/build/019/<out-name>.riscv` (for this task, add new names only; keep the old binaries). The knobs are:
  - `-DSHARE=1` makes both cores use the same lines (Form B); `-DSHARE=0` gives each core its own half (Form A);
  - `-DMIGRATE=0|1`, `-DDSTMODE=0|1|2`, `-DPOLICY=0|1` (random or PLRU), `-DHART1=0|1` (the second core races or idles);
  - `-DITERS=n` (default 200,000) and `-DSEED=n`.

  Success is the line `[DRD] RESULT: finished with no hang. data OK (bad words: hart0=0 hart1=0)`.
- **Runners.**
  - `$NEW/tools/run_nostop.sh` (step 9.6).
  - `$NEW/tools/run_stop.sh`: a copy that uses the stopping simulator, leaves out `+verilator+error+limit+…`, and uses
    `+max-cycles=120000000`.
  - Both pipe through `$HUNT/sim/dedup.awk`, and both must keep `+dramsim +dramsim_ini_dir=$CY/generators/testchipip/src/main/resources/dramsim2_ini`.
    Without `+dramsim`, the shadow checker false-alarms (`CLAUDE.md`).
- **Traps** (memory note `verilator-nostop-sim`):
  - Never raise `+verilator+error+limit` above 20,000. At 1e9 the testbench timeout is ignored, and one run wrote 13.9 GB.
  - `pkill -f` and `pgrep -f` from the Bash tool match their own shell and kill it. Anchor the pattern to `^$CY/sims/verilator/simulator-`
    and kill by PID.
  - `+max-cycles` counts testbench cycles (1 ns), while the L2 runs at half that rate. Trace stamps `[t]` are in ps, so an L2 cycle
    is t / 2000 − 389.
  - A freeze starts 20,000 cycles before the first "wedged" message.

### 10.2 Verification steps

| Step | What | Pass |
|---|---|---|
| V1 | Build both two-core simulators on the new RTL. Also confirm that `…L2Config`, `…NoSbcConfig` and `…DstConfig` elaborate | builds succeed, and the elaboration prints are unchanged |
| V2 | **Two-core suite on the stopping simulator.** `SHARE` 0 and 1 × `DSTMODE` 0, 1 and 2 × `POLICY` 1 and 0 × `SEED` 1–5, `ITERS` 200,000 (60 runs, or 36 if the user chose 3 seeds). Plus the 5 original freeze binaries and the 2 controls. At most 4 runs at a time | every run prints `data OK`, no assert fires, and the simulator exits 0. Record wall time, L2 cycles, migrations and the heartbeat counters for each run |
| V3 | **Coverage.** Count the §6.1 C0 step 7 tags in the V2 logs. Each must appear at least once, except `PROBEACK-STRAY`, which after C1 can only appear as an assert (so it must be 0). For any tag that never appears, write a directed variant of the test (new file `$SWD/dual_merge_directed_test.c`), or say plainly that it is not covered. Also prove from a trace that the write-back's acknowledgement and its directory write go out in the same schedule step ("ack and write in one step", §6.1 F1f) | each event proven to happen at least once, with data correct |
| V4 | **Single-core regression.** Run `run_020_gate.sh` stages g0–g6b with `TAG=fix`, and `run_013_gate.sh` | all pass. `…NoSbcConfig` results are cycle-identical to the baseline. SBC counters (migrations, secondary hits, memory reads and writes, cycles) are equal to the baseline, or every difference is explained by write-back timing (§8) |
| V4b | **Board-like run.** The 5 original freeze binaries on the new **NoStop** simulator | every run prints `data OK` |

**Stop (c).** Report V1–V4b in plain English: what passed, the counter comparison, and any unexplained difference.

---

## 11. Step 3 — FPGA builds (V5) → stop (d)

Only after the user says go at stop (c).

1. **Dual-core image:** `$CY/scripts/ssbc_scripts/build_bitstream.sh FPGADualRocketVCU118L132K512K8WL2ConfigSBCPLRUDst <tag>`.
   Use a tag naming 020 and the L2 commit, for example `dual-512KB-8way-L1-32K8W-tl020-<commit>-<date>`.
2. **Single-core image:** `… FPGASingleRocketVCU118L132K512K8WL2ConfigSBCPLRUDst <tag>`.
3. Run them one after the other (about 35–40 minutes each). Rule 15 applies to both, including deleting the config's
   `generated-src` folder first.
4. **Pass conditions:**
   - The build finishes with no LUTLP-1 combinational-loop error (B7-2). If it appears, stop. Do not bypass it.
   - Timing is met. The old dual image had a worst slack of +0.548 ns; report the new slack next to it.
   - The archive in `$CY/fpga/bitstream_storage/` holds the `.bit`, the provenance, the `.dts`, the `.config.diff` and the reports.
     The provenance names the L2 commit, with no "uncommitted design/" warning if the user chose commits.
5. **Stop (d).** Report both sha256 values (first 16 hex digits plus the last 4), the slack, and the archive paths.

---

## 12. Step 4 — board checks (V6, V7) → stop (e)

Only after the user says go at stop (d), and only when rule 6's two commands print nothing. The board method is the same as in
tasks 015–019:
- One fresh boot (program, boot, stage) gives ONE measured window.
- SBC off and SBC on each get their own fresh boot, ordered as pairs per point (off, then on).
- Results come only from a launcher. A calibration or hunt boot is never a result.
- Do a `-noprog` or `LU_DRYRUN=1` dry run before any new command form (memory note `dry-run-new-command-forms`).

**V6, dual-core image (the freeze is gone):**
1. **Hunt boots.**
   - Copy `$HUNT/tools/hunt_session.exp` to `$NEW/tools/`, and change only `HUNT` (to `$NEW/board`) and the default `BIT`.
   - Write ladders in `$NEW/board/ladders/`. Use the fastest freezing pattern from task 019 (boot 7 rung a1 and boot 8 rung b1):
     `--migrate=on --dstmode=1 | -T 2 -g same -w -r 4 -d 600 -H 0.25`, then the same with `--dstmode=0`.
   - Run two fresh boots. The program is `$SWD/build/l2_miss_calib_dual` (unchanged; sha256 starts `a53a322f…`).
   - **Pass:** every rung prints `[L2DUAL-DONE]`, with no HANG.
   - Launch it as in 019:
     `tmux new -d -s hunt020_N "cd $NEW/board && $NEW/tools/hunt_session.exp -bit <bit> -ladder <file> -silence 120 2>&1 | tee $NEW/board/console_bootN.txt; echo HUNT-DONE >> $NEW/board/console_bootN.txt; exec bash"`.
2. **LU with two threads**, `lu-p2-n2048-b128-plru`. This point froze 4 times in 5.
   - Copy task 019's launcher, checker and harness copy from
     `$CY/scripts/logs/clean-runs/lu-sweep-dstmode1-dual-core-512k-20261015/tools/` into `$NEW/tools/`.
   - Change only the image and the campaign root, and write the change list in the header, as v4 did.
   - Run SBC off once and SBC on twice, each on its own fresh boot.
   - **Pass:** every window completes and the checker says VALID. Also check the LU output: compare the printed diff with the host
     reference, and never expect "PASSED" (memory note `lu-t-check-always-fails`).
   - Report the memory-access change (it is new data; there is no earlier two-thread n2048 result to compare with).

**V7, single-core image (performance unchanged):**
- LU `-p1` at n2048 b128, SBC off then setting 1, each on its own fresh boot. Use a copy of task 018's launcher
  (`$CY/scripts/logs/clean-runs/lu-sweep-dstmode1-single-core-512k-20261013/tools/launch_dst_sweep_v3.sh`, with `DST_BIT` and
  `DST_BIT_SHA` set to the new image) in `$NEW/tools/`.
- **Pass:** the memory-access change is within about 1.5 points of −10.7% (replicate −9.7%). Also compare migrations per access and
  secondary hits with the 018 windows, and explain any difference.
- Run n4096 b128 only if the user chose it in §7 Q5 (earlier −20.9%, replicate −21.1%; about 6 hours).

**Stop (e).** Report V6 and V7 in plain English, with every console and results path.

---

## 13. Step 5 — documentation and close-out → stop (f)

- `ai-documents/guides/priority-orders.md`: add the rule "a write-back passes every fence, never waits for the partner row, and
  its search sees locked ways".
- `ai-documents/bugs/bug-fix-log.md`: add one entry per D1–D8. That file has uncommitted edits by someone else. Add your entries
  without touching theirs, and if the user chose commits, ask before committing that file.
- `CLAUDE.md` (L2 repo) and `ai-documents/coder/README.md`: a short status line for 020 (ask the user before editing either).
- Memory: update `task-020-handoff.md` and the `MEMORY.md` index line.
- Task 019 can then resume its SBC-on dual-core points. That is the user's call; say so.

---

## 14. Files you may change — nothing else

- `$SRC/MSHR.scala`, `$SRC/Scheduler.scala`, and `$SRC/Directory.scala` (only if F1f needs it there).
- New files: `$SWD/scripts/run_020_gate.sh`, `$SWD/dual_merge_directed_test.c` (V3, if needed), new binaries in
  `$SWD/build/019/` or `$SWD/build/020/`, everything under `$NEW/`, and this folder's `REPORT.md`.
- The docs of §13, after the user agrees.

---

## 15. Not in scope

- B8-1 (unless the user says otherwise in §7).
- B7-2 beyond detecting it in V5.
- The L2 flush register for parked lines (documented as unsupported).
- Any change to SBC decisions or counters.
- Rocket changes (`acquireBeforeRelease`).
- Task 019's LU sweep blocks C and D.

---

## 16. `REPORT.md` template (create it next to this file)

```markdown
# Coder report 020 — SBC obeys the TileLink write-back rules

## ▶ RESUME HERE
(one paragraph: where you are, what runs, what waits for the user)

## Decisions (user answers to §7, and any decision you made without the user, numbered)

## Preflight and baselines (§9)

## Changes (§6), one block per step C0–C4: what changed, where, why it is safe, the commit (if any)

## Simulation verification (§10): V1, V2 table, V3 coverage table, V4 counter comparison, V4b

## FPGA builds (§11)

## Board checks (§12)

## Findings (anything new, numbered F1, F2, …, each with measured / inferred / unknown kept apart)

## Verdict

## All log paths
```

---

## 17. Where to look things up

- The bug and its reproduction: `BUG-dual-core-release-deadlock.md` (this folder). Start with
  `$HUNT/sim/deadlock_excerpt_noshare_dst1.txt`.
- The draft plan the user saw: `PLAN-DRAFT.md` (this folder).
- The task 019 history (board hunt, simulation blocks, decisions D17–D21): `../019-dual-core-lu-sweep/REPORT.md`.
- Board method and the LU campaign tools: `../019-dual-core-lu-sweep/TASK.md` and `../018-dst-dirty-ways/TASK.md`.
- Every arbitration and priority order: `ai-documents/guides/priority-orders.md`.
- Recurring bug patterns (same-cycle register reads, a guard added in one place and missed in its twin): `CLAUDE.md`, "Bug patterns that keep coming back".
- The TileLink spec 1.8.1: chapter 5 (forward progress) and §9.2 (concurrency, Figure 30).
