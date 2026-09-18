# Coder report 008 — PLRU replacement behind `L2_Replacement`

**Date:** started 2026-09-17 · **Author:** coder session · **Status:** IN PROGRESS — C1 committed (`7494296`); C2 (option B workaround) gate green, board-tested, **not committed**

> Filled in as the work happens, not at the end.

## ▶ RESUME HERE — state at end of 2026-09-17 (22:30)

- **C1 committed** (`7494296`). **C2 = TASK §3 counters + option B** (destination takes D's least-recent clean,
  client-free way). Option B is a **workaround** (user decision); **task 009 is the permanent fix**. C2 gate
  `008-c2` green (9/9, V1–V4, V6), bitstream V5 clean. **C2 is NOT committed** — waiting for the user's word.
- **Board image:** `fpga/bitstream_storage/FPGASingleRocketVCU118L18K64K16WL2ConfigSBCPLRU-008-c2B-7494296-wip-2026-09-17.bit`
  (+ two `.patch` files = the exact uncommitted source). Sessions R and P done, one run each — see "Board run".
  **Headline: PLRU alone −3.56% cycles / −12.65% memory traffic on the plain L2; SBC on top +0.07% cycles /
  +0.41% traffic (still no win); secondary hits per migration 0.22 → 0.44.**
- **Next, in order:** (1) commit C2 on the user's go-ahead; (2) repeat sessions P→R, R→P on the same image;
  (3) B8-1 (open correctness bug, below); (4) the next speed lever — M18 heat counters (TASK §8).
- **Open bugs:** **B8-1** teardown vs deferred-migration claim (sim assert; silent on the FPGA); **B7-2** 256 KB
  combinational loop (64/128 KB clean). Details in `ai-documents/bugs/bug-fix-log.md`.
- Day summary: `ai-documents/daily-summary/2026-09-17.md`.

## Day log 2026-09-17 (kept for the record, oldest first)

- **Commit 1 committed** on the user's go-ahead (2026-09-17). V0, V1, V2, V3 all PASS.
  Configs (chipyard repo, uncommitted) say `plruReplacement = true` in the three configs.
- ⚠️ **F5 = bug log B8-1 (tracker M19)** — pre-existing in `744fabb` (007 C3 teardown race), proven. Not fixed;
  its own task later (user). **If the same assert appears in the C2 gate, check the event sequence against
  B8-1 and report it as a recurrence, not as a C2 regression.**
- The Verilator simulator on disk for the SBC config is the **flag-off** build (from the F5 check); the next
  gate rebuilds it (`SBC_CLEAN=1`).
- **Commit 2 written, NOT committed. Gate `008-c2` STOPPED by the user (2026-09-17 ~17:55)** during the SBC
  PLRU stress test, after case 6 passed. Done before the stop: SBC PLRU switch test PASS (F6). Not run: stress
  cases 7–8, SBC random, NoSbc plru/random/random0 — so V1, V2, V3, V4, V6 for C2 are all still open. To resume:
  `sw/scripts/run_008_gate.sh c2` in tmux `sbc008` (log `sw/verilator_logs/008-c2-gate.log`); it rebuilds.
- **Taken over in a new chat (2026-09-17 17:45).** C2 re-checked against TASK §3 — code complete: evictable
  tier `preferEvictable && !usePlru`, three MSHR pulses at the abort branch, `PopCount` into `PerfCounters`
  (held by `L2_StatsHold`, cleared by both resets), `0x498`/`0x4A0`/`0x4A8`, reason in `ABORT-DST`, `sec=` in
  `PLRU-VICTIM`, `sbc_mmio.h`, `sbc_read`, `[SBC-DSTABORT]` under `#ifdef L2_POLICY`, CLAUDE.md rows. Binary
  checks: flag-free stress test loads byte-identical to HEAD's (V1); `-DL2_POLICY=0`/`=1` differ in one byte.
- **Gate `008-c2` restarted 17:48** (tmux `sbc008`, log `results/008-c2/run_c2_gate_then_bitstream.log`; the
  stopped run's partial results are overwritten). Then, only if all 9 gate runs PASS with no assert,
  `bistream_gen_vcu118.sh sbc_64l2_plru` starts in the same session (user: prototype soon). V1/V3/V4/V6 are
  checked by hand afterwards and do not block the build.
- **Gate `008-c2` STOPPED 18:04 (user: "wait till the run, then we can decide")** after the SBC PLRU stress test
  (PASS, V3 + V6 PASS). **F7: 91.8% of destination aborts are client-held, 0.9% dirty-only.** C2 behaviour is
  being redesigned (user wants destination aborts fixed); no bitstream built. Waiting for the choice A/B/B+A/C.
- **DECISION (user, 2026-09-17 ~18:10): option B, as a WORKAROUND ONLY. Task 009 (probe + write-back of a dirty
  or client-held destination way) is the permanent, paper-faithful fix.** Built: with PLRU on, the destination
  probe takes an invalid way, else **D's least-recent clean, client-free way** (`Recency.maskedWay`, a PLRU walk
  that steps into the older half only if it holds an allowed way), else D's PLRU way → abort (counted by
  reason). Random mode is `744fabb`'s pick again (lowest-index clean way). This replaces TASK §3.1's "skip the
  evictable tier". Then: gate `008-c2` → 64 KB SBC+PLRU bitstream → archive → board commands to the user.
  The C2-as-staged logs are kept as `*_008-c2staged-*`.
- **FPGA config (user, 2026-09-17: "create new config for PLRU with PLRU suffix"):** new
  `SingleRocketVCU118L18K64K16WL2ConfigSBCPLRU` (chipyard `RocketConfigs.scala`) and
  `FPGASingleRocketVCU118L18K64K16WL2ConfigSBCPLRU` (`fpga/.../vcu118/Configs.scala`); build shortcut
  `bistream_gen_vcu118.sh sbc_64l2_plru`. `plruReplacement = true` taken back off the plain
  `…64K16WL2ConfigSBC`, so that image builds as before. The two Verilator configs keep the flag. No bitstream
  built yet. All three files are uncommitted work in the chipyard repo.

## Order changed (user, 2026-09-17: "I need to see a working prototype soon")

- The prototype runs first. **V0 moved to after the C1 gate, before the commit:** set the three config
  lines back to `false`, `make verilog` for both Verilator configs, diff against
  `results/008-v0/pre-edit-generated-src/`, set them back to `true`. The next rebuild is needed for C2 anyway.
- The step "elaborate the unchanged tree first" was skipped. The saved Verilog is the `007-b71-c3` gate
  build (11:36 / 11:58), 35 min before `c6a824c`/`744fabb` were committed. If V0 shows a difference, the
  first step is to rebuild `744fabb` to find out whether the difference is mine.
- Gate order per config: PLRU first, then random, same simulator build (`sw/scripts/run_008_gate.sh`).

## Decisions taken on the open questions (defaults, told to the user)

| # | Question | Taken |
|---|---|---|
| Q1 | `#ifdef L2_POLICY` changes the binary layout (007 F4) | Random runs of V1 stay flag-free (switch test must match `007-b71-c3`). The policy value sits in a `volatile` pinned to `.sdata`, so `-DL2_POLICY=0` and `=1` differ in **one data byte** (checked with `objcopy -O binary` + `cmp`). V4 (C2) will use `=0` vs `=1` |
| Q2 | V3 "chosen = plruWay whenever tier = 2" | Checked in PLRU mode only; in random mode tier 2 is the LFSR way. `plruWay` = model is checked in both |
| Q3 | Write-bypass tag match: the result names `bypass.way`, not the mux pick | `PLRU-VICTIM` also prints `res=`; the replay counts how often `res != chosen` |
| Q4 | Header name | `SBC_L2_REPLACEMENT` (every name in `sbc_mmio.h` has the `SBC_` prefix) |

## Commit 1 — what changed

| File | Change |
|---|---|
| `Parameters.scala`, `Configs.scala` | `plruReplacement: Boolean = false`, passed through `WithInclusiveCache` |
| `Recency.scala` (new) | Registered touches (valid resets to 0) → `SetAssocLRU(sets, ways, "plru").access` (access folded before install) → `victimWay := plru.way(querySet)`. `sbcDebug`: `PLRU-TOUCH` printed in the cycle the touch is applied |
| `Directory.scala` | Option IO `touch`, `usePlru`. `Recency` only when the flag is on; `querySet := set` (result-aligned). The policy tier uses `policyOH` = PLRU or LFSR; flag off, `policyOH` **is** `lfsrVictimOH`. `sbcDebug`: `PLRU-VICTIM` |
| `MSHR.scala` | `ScheduleRequest.dirInstall: Option[Bool]` (flag && SBC) `:= mig_dir1` |
| `Scheduler.scala` | Option IO `usePlru`; T0/T1 into the Directory; `assert(!schedule.d.valid \|\| sourceD.io.req.ready)` — all inside `if (plruReplacement)` so V0 can hold |
| `Control.scala`, `InclusiveCache.scala` | `RegInit(false.B)` at `0x490` appended to the regmap only when the flag is on; tie-down `false.B` without a control port |
| `sw/sbc_mmio.h` | `SBC_L2_REPLACEMENT`; `sbc_set_policy()` under `#ifdef L2_POLICY` |
| `sw/migration_stress_test.c`, `sw/sbc_migrate_switch_test.c` | `sbc_set_policy()` first thing in `main` under `#ifdef L2_POLICY` |
| `sw/scripts/compile_app.sh` | `${EXTRA_CFLAGS:-}` |
| `sw/scripts/run_008_gate.sh` (new) | Both configs × {plru, random}, one build per config |
| chipyard `RocketConfigs.scala` (uncommitted work) | `plruReplacement = true` in the two Verilator configs and `SingleRocketVCU118L18K64K16WL2ConfigSBC` |

Binary check before the gate: without `-DL2_POLICY` the loaded images of both tests are identical to the
`sw/build` binaries of the `007-b71-c3` gate.

## Commit 2 — what changed

| File | Change |
|---|---|
| `Directory.scala` | `evictableTier = preferEvictable && !usePlru` (a Scala `map`, so a flag-off build is unchanged) in the victim mux and the tier printf. `PLRU-VICTIM` gains `sec=` (F2) |
| `MSHR.scala` | `dstAbortDirty` / `dstAbortHeld` / `dstAbortBoth` pulses in the destination-read abort branch, from `io.directory.bits` (both policies). `ABORT-DST` printf gains `dstWay=`, `dirty=`, `held=` |
| `Scheduler.scala`, `PerfCounters.scala` | `PopCount` fan-in; three 64-bit counters in the SBC event group — added under `go` (held by `L2_StatsHold`), cleared by `clearStats` and `clearSbc` |
| `Control.scala` | `SBC_DstAbortDirty/Held/Both` at `0x498` / `0x4A0` / `0x4A8` |
| `sw/sbc_mmio.h`, `sw/sbc_read.c` | three defines; three `REGS` entries appended; a `dst aborts` summary line |
| `sw/migration_stress_test.c` | `[SBC-DSTABORT]` line under `#ifdef L2_POLICY` only (the flag-free image is unchanged — checked with `objcopy` + `cmp`) |
| `sw/scripts/plru_replay.py` | reads `sec=`; tier table split into demand / destination probe / second search |
| `sw/scripts/run_008_gate.sh` | adds a NoSbc `random0` stress run (`-DL2_POLICY=0`) for V4 |

Not a change: the MSHR's `dstFree \|\| dstEvictable` test (TASK §3.1 — no MSHR change for the behaviour).

## Checks

| # | Status | Result |
|---|---|---|
| V0 | **PASS** | Flag set to `false` in both Verilator configs, `make verilog`, compared with the saved `007-b71-c3` build: 465 (SBC) and 463 (NoSbc) Verilog files **identical once Scala line numbers are normalised**; L2 regmap JSON, DTS and `l2.json` identical byte for byte. Not identical *raw*: 18 files differ, all in Scala line numbers only (see "Where the work order is wrong"). This also shows the saved build was `744fabb` |
| V1 | **PASS** | Both configs. Switch test: SBC T1–T4 PASS, NoSbc SKIP (as before); both `.log` **identical** to `007-b71-c3`. Stress test 8/8 (SBC) and 7/7 (NoSbc), 0 asserts — and **every counter identical to `007-b71-c3` in both configs** (F3) |
| V2 | **PASS** | Both configs, `[SBC] policy=1` read back in both (the register is built in NoSbc too). Stress test 8/8 / 7/7, 0 asserts, shadow checkers quiet. SBC: accessA 267,592 = 137,266 + 872 + 129,454 + 0 ✓, secondSearch 51,813 = 872 + 50,941 ✓. NoSbc: accessA 266,926 = 136,745 + 0 + 130,181 + 0 ✓ |
| V3 | **PASS** (8 logs) | `plruWay` = model on every line: SBC switch PLRU 9,608 / random 6,890, SBC stress PLRU 211,058 / random 234,210, NoSbc switch PLRU 173, NoSbc stress PLRU 130,191 / random 137,099. `chosen = plruWay` on every PLRU tier-2 pick. `res != chosen`: 0 in all. Model self-test: for 2/3/5/8/16 ways and every state, a just-touched way is never the victim |

## Numbers after each commit (simulation)

### Commit 2 (option B), `VerilatorRocket8KL116KL2Config`, PLRU (`008-c2-plru`)

Same binary as the stopped `008-c2staged-plru` run (only the RTL differs), so these two columns compare.

| | C2 option B | C2 as staged (stopped run) |
|---|---:|---:|
| switch test: migrations / destination aborts | 1,488 / 0 | 1 / 1,377 |
| stress: 8/8, asserts | PASS, 0 | PASS, 0 |
| `SBC_Migrations` / `SBC_Attempted` / `SBC_Aborted` | 47,353 / 47,486 / 133 | 20 / 43,992 / 44,022 |
| aborts dirty / held / both (V6: sum = 133 `ABORT-DST` ✓) | 36 / 50 / 47 | 384 / 40,369 / 3,219 |
| `L2_AccessA` | 340,740 | 357,719 |
| `L2_PrimaryHit` / `L2_SecondaryHit` | 204,335 / 2,059 | 210,442 / 3,041 |
| `L2_DataMiss` | 134,346 | 144,236 |
| hit rate | 60.57% | 59.68% |
| `L2_MemReads` / `L2_MemWrites` | 134,344 / 25,129 | 144,234 / 24,226 |
| `L2_Cycles` | 13,510,527 | 14,197,803 |
| `SBC_Parked` at end | 4 | 0 |
| installs on the same way as the previous install in that set | 22,191 / 47,348 (46.9%) — switch test 0.1% | — |

**Random mode, same build (`008-c2-random`): V1 PASS** — switch test `.log` and every stress counter identical
to `007-b71-c3`, so option B leaves random mode exactly as `744fabb`. Its destination aborts by reason (the
007 F9 question, sim only): client-held 19,742, dirty 8,210, both 2,564 (= 30,516; `SBC_Aborted` 30,669 adds
153 declines).

V3: 255,652 victim lines, `plruWay` = model on all, **47,352 tier-1 picks = the masked walk on all**. The 46.9%
same-way figure is the stress test's 8-way sets with many client-held lines: often only one clean candidate.

### Commit 2 (option B), gate result — GREEN (2026-09-17 19:12)

All 9 runs PASS, 0 asserts, shadow checkers quiet. **V1 PASS** (both configs: random switch test and every
random stress counter identical to `007-b71-c3`). **V2 PASS** (8/8, 7/7, identities hold). **V3 PASS** on every
log (incl. 47,352 masked-walk picks). **V6 PASS** (133 = 36 + 50 + 47). **V4 (NoSbc, same binary layout,
`random0` vs `plru`):** hit rate 59.51% → **60.54%**, `L2_DataMiss` 135,129 → 131,117 (−3.0%), memReads
135,121 → 131,115 (−3.0%), memWrites 25,659 → 25,821 (+0.6%), cycles 13,097,068 → 13,014,089 (−0.6%). PLRU is not
worse on the plain L2 — sanity only (8 sets, 256 B L1). Bitstream build started 19:12:35
(`fpga/build-logs/FPGASingleRocketVCU118L18K64K16WL2ConfigSBCPLRU-20260917-191235.log`).

**V5 PASS (bitstream, 19:41).** DRC 0 errors, **no `LUTLP-1`**; the warning set is identical to the last 64 KB
image (`…64K16WL2ConfigSBC`, 2026-09-16). Timing met: WNS 0.579 ns (was 0.592), TNS 0, WHS 0.010. The worst
core-domain path is the debug module (`dtm` → `dmInner/programBufferMem`), not `Recency` or the victim mux
(core-domain WNS 1.965 → 1.039 ns, same kind of path as before). Area, whole design: LUT 66,459 → 69,094
(+2,635), FF 45,645 → 46,922 (+1,277). `directory`: LUT 1,716 → 3,995, FF 112 → 1,182 (PLRU tree 64 × 15 = 960 FF
+ touch registers). Archived and `cmp`-verified:
`fpga/bitstream_storage/FPGASingleRocketVCU118L18K64K16WL2ConfigSBCPLRU-008-c2B-7494296-wip-2026-09-17.bit`, with
the exact source diffs beside it (`….inclusive-cache.patch`, `….chipyard-configs.patch`) — the tree is uncommitted.
`sw/build/sbc_read` rebuilt (has `--policy` and the abort counters). Board sessions R and P handed to the user.

### Commit 1, `VerilatorRocket8KL116KL2NoSbcConfig` (plain L2), stress test, same simulator build

**The clean comparison is PLRU vs `008-c1-random0`** (binaries differ by one data byte). The flag-free random
column shows how much the binary layout alone moves the numbers.

| | PLRU (`008-c1-plru`) | random, same layout (`008-c1-random0`) | random, flag-free binary (`008-c1-random`) |
|---|---:|---:|---:|
| `L2_AccessA` | 266,926 | 269,309 | 273,208 |
| `L2_PrimaryHit` | 136,745 | 135,844 | 136,124 |
| `L2_DataMiss` | 130,181 | 133,465 | 137,085 |
| hit rate | **51.23%** | **50.44%** | 49.82% |
| `L2_MemReads` / `L2_MemWrites` | 130,177 / 25,748 | 133,461 / 24,332 | 137,077 / 26,959 |
| `L2_Cycles` | 11,687,895 | 11,755,638 | 11,589,360 |

PLRU vs same-layout random, plain L2: hit rate +0.79 pt, reads −2.5%, writes +5.8%, reads+writes −1.2%,
cycles −0.6%. One run, 8 sets, 256 B L1 — sanity only. Note the binary layout alone moved random's hit rate
by 0.62 pt (49.82% → 50.44%), about as much as the policy did.

**SBC vs plain L2 with the same binary (both `-DL2_POLICY=1`):** hit rate 51.62% vs 51.23% (+0.39 pt), memory
reads 129,447 vs 130,177 (−0.6%), writes 25,396 vs 25,748 (−1.4%), cycles +0.94%. With random (both flag-free):
49.09% vs 49.82% (−0.73 pt), reads +1.6%. A sign flip in the sim only — 8 sets and a 256 B L1 are not the
board, and it is one run each.

### Commit 1, `VerilatorRocket8KL116KL2Config`, stress test, same simulator build

⚠️ **Not yet a clean policy comparison.** The PLRU binary has `sbc_set_policy()`, the random binary does not,
and 007 F4 showed that a code change alone moves these numbers by 5–13%. The same-layout random run
(`-DL2_POLICY=0`, label `008-c1-random0`) is queued; compare PLRU against that one.

| | PLRU (`008-c1-plru`) | random (`008-c1-random`, flag-free binary) |
|---|---:|---:|
| `SBC_Parked` at end | 3 | 1 |
| `SBC_Migrations` | 28,150 | 16,448 |
| `SBC_Attempted` | 28,151 | 46,964 |
| `SBC_Aborted` | 128 | 30,669 |
| `L2_AccessA` | 267,592 | 273,483 |
| `L2_PrimaryHit` | 137,266 | 131,228 |
| `L2_SecondaryHit` | 872 | 3,024 |
| `L2_DataMiss` | 129,454 | 139,232 |
| hit rate | 51.62% | 49.09% |
| `L2_MemReads` / `L2_MemWrites` | 129,447 / 25,396 | 139,229 / 26,415 |
| `L2_Cycles` | 11,797,526 | 11,662,284 |
| migration commits by pair | 4→0 16,125 · 2→3 6,281 · 5→7 5,680 | (see `sbc_stats.txt`) |
| aborts by pair | 5→6 1 | 6→3 16,315 · 1→7 3,517 · 4→7 3,179 · 1→5 2,525 · … |
| installs on the same way as the previous install in that set | 28,137 / 28,144 (100.0%) | 11,991 / 16,444 (72.9%) |
| victim tiers (demand) | policy 130,523 (PLRU) + 190 before the SW write | policy 142,391 · lowest-free 161 |

## Board run — 64 KB, omnetpp, image `…SBCPLRU-008-c2B-7494296-wip-2026-09-17.bit` (2026-09-17)

Sessions R (`scripts/logs/board_session_20260917-194519.log`, 19:45–20:55) and P
(`scripts/logs/board_session_20260917-205542.log`, 20:55–22:04), each a fresh program + boot, `-ab`. One run each.
`workload rc=0` in all four halves; accesses = the four outcomes exactly in all four; `-policy` read back
correctly (first board use of it).

| | R-OFF | R-ON | P-OFF | P-ON |
|---|---:|---:|---:|---:|
| `L2_AccessA` | 1,016,764,878 | 1,017,229,523 | 995,891,870 | 995,636,955 |
| `L2_PrimaryHit` | 682,840,335 | 682,501,868 | 704,778,022 | 701,251,367 |
| `L2_SecondaryHit` | 0 | 923,537 | 0 | 2,178,569 |
| `L2_DataMiss` | 333,924,543 | 333,804,118 | 291,113,848 | 292,207,019 |
| hit rate | 67.16% | 67.18% | 70.77% | 70.65% |
| `L2_MemReads` | 333,924,543 | 333,804,118 | 291,113,848 | 292,207,019 |
| `L2_MemWrites` | 69,530,102 | 69,577,389 | 61,294,748 | 61,654,729 |
| `L2_Cycles` | 50,873,813,787 | 50,909,369,655 | 49,064,031,921 | 49,096,599,049 |
| migrations / attempted / aborted | — | 4,251,329 / 4,481,576 / 233,848 | — | 4,906,778 / 4,986,792 / 85,440 |
| dst aborts dirty / held / both | — | 112,919 / 107,139 / 10,189 | — | 44,159 / 34,827 / 1,028 |
| secondary hits per migration | — | 0.217 | — | **0.444** |
| second searches (% of accesses, % that hit) | — | 1.47%, 6.19% | — | 1.81%, 12.12% |
| `SBC_DispDrop` per migration | — | 0.21 | — | 0.51 |
| `SBC_Parked` at end of window | — | 2 | — | 9 |

| Compare | hit rate | reads | writes | cycles |
|---|---:|---:|---:|---:|
| **P-OFF vs R-OFF** (PLRU alone, plain L2) | **+3.61 pt** | **−12.82%** | **−11.84%** | **−3.56%** |
| **P-ON vs P-OFF** (SBC vs plain L2 with PLRU — headline) | −0.12 pt | +0.38% | +0.59% | **+0.066%** |
| R-ON vs R-OFF (today's rules; F9) | +0.03 pt | −0.04% | +0.07% | +0.070% |
| P-ON vs R-ON | +3.47 pt | −12.46% | −11.39% | −3.56% |

**Reading (one run each, the ordered repeats P→R, R→P are not done):**
- PLRU is a large win for the L2 on its own: −3.6% cycles, −12.7% memory traffic on the plain L2.
- SBC on top of PLRU still does not win: +0.07% cycles, +0.41% memory traffic (reads and writes both up). Same
  tie as R (+0.07%). The loss is small but the sign is not in SBC's favour.
- The MRU head start works in part: secondary hits per migration doubled, 0.217 → 0.444, and a second search hits
  twice as often (6.2% → 12.1%). Still below ~1, so a migration does not pay for itself; half of all guests
  (dispDrop 0.51 per migration) are evicted without being used.
- **Task 009's upside is small here:** destination aborts are 1.6% of attempts in P-ON (80,014), so evicting dirty
  and client-held destination ways would add at most ~1.6% more migrations. The problem is the value of a
  migration, not the number.
- **007 C4:** `SBC_Parked` is 9 at the end of P-ON — far below a cap; nothing for a guest cap to do.
- 007 F9 on the board (R): 5.1% of attempts abort at the destination, dirty and client-held about equally.
- TASK §8: "M18 (heat counters) next if P-ON does not beat P-OFF" — it did not.

## Findings (reported, not fixed)

- **F1 — in commit 1 every migration reuses the same destination way (switch test, PLRU mode).** 1,487 of
  1,487 installs landed on the same way as the previous install into that set, and all 1,488 destination
  probes took tier 1 (evictable, lowest index). The guest just installed is clean and client-free, so the
  lowest-index evictable way is that guest again. This is the plan §2 hypothesis ("each new displacement
  would evict the line inserted in the previous one"), seen directly. Commit 2 (`!usePlru` on the evictable
  tier) is the change aimed at it. The switch test is a single-set hammer, so the stress test number is the
  one to quote.
- **F3 — the Verilator sim IS reproducible across these two builds.** With random selected and the same
  binary, the `008-c1-random` stress test gave counters identical to `007-b71-c3` on every field
  (migrations 16,448, aborted 30,669, accessA 273,483, memReads 139,229, ...). So 005 F2's "not
  reproducible across builds" is not a property of the simulator here; the variation it saw is more likely
  the binary change (007 F4). Only one data point.
- **F4 — the plan's "72% of migrations overwrite a guest" is the previous guest.** In random mode 72.9% of
  installs land on exactly the way of the previous install into that set (plan §2 asked V3 to check). In PLRU
  mode with commit 1 it is 100%: the destination probe still takes tier 1, so the MRU guest is overwritten
  by the next migration anyway. Commit 2 is the fix for that.
- **F5 (= bug log B8-1, tracker M19) — ASSERT, random mode, `-DL2_POLICY=0` binary: "SBC: paired source migrated outside its partner set"**
  (`MSHR.scala:1265`, `mshrs_1`, sim time 4,645,571,000, during `case_bankstore_saturation`; cases 1–6 had
  passed). Run: `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2Config_008-c1-random0/`
  (collected by hand — `run_sbc.sh` stopped on the failure, so there is no `sbc_stats.txt`).
  Event sequence at the end of `sbc.log`:
  `EVICT-DISPLACED-RECLAIM srcSet=0 srcWay=2` (source 2's last guest in set 0 leaves) → `ADVICE-MIG srcSet=2`
  → `TEARDOWN src=2 dst=0` → `EVICT-ASSESS srcSet=2 way=5 … offerValid=0 offerSet=0 … clients=1` →
  `MIG-DEFER srcSet=2 srcWay=5` → `MIG-CLAIM dstSet=6` → `MIG-START srcSet=2 dstSet=6` → assert.
  **Reading (a guess, not checked in RTL):** the MSHR's pairing (2 → 0) was taken before the teardown; the
  migration deferred for a probe, the teardown freed source 2, the DSS then offered set 6 (a legal new
  destination), and the claim was checked against the stale partner 0. 007 C3's N2 added a re-check after a
  teardown for the *second search*; the deferred-migration claim has none. Whether the assert is only stale
  or the MSHR can really park a line with a stale pairing is not known.
  **Why I think it is not 008:** in random mode the victim mux output is the LFSR way exactly as in
  `744fabb`, and the tracker is read by nothing else; the only difference from `008-c1-random` (which passed,
  identical to `007-b71-c3`) is the test binary layout, which changes the traffic (007 F4). Being checked by
  running the same binary on the flag-off build — **DONE, reproduced:** `008-c1-random0-flagoff` (no tracker,
  no register, no touches) stops at the same assert, same MSHR, same sim time 4,645,571,000, and its 233,842
  `[SBC]` lines are the **same multiset** as the flag-on run with the `PLRU-` lines removed (only the
  in-cycle print order differs, because the module order changed). So F5 is in `744fabb`, and — a runtime
  counterpart of V0 — **random mode with the tracker built behaves exactly like no tracker**. Consequence for 008: the SBC-config same-layout random
  numbers are not available (the run stopped at case 7), so the clean PLRU-vs-random comparison exists only
  for NoSbc. PLRU-mode runs may hit this race too — it is traffic-dependent, and C3 is live in both modes.
- **F6 — C2 switch test, PLRU: D's PLRU way is almost always DIRTY, so the migration aborts.** 1,378
  destination probes, **1,377 aborts, every one `dirty=1 held=0`**, 1 migration committed (C1 PLRU on the
  same test: 1,485 migrations, all taking tier 1). The test still passes (T3 needs ≥1 migration). This is the
  D2 staging doing what it says: the destination's least-recent line is a dirty, client-free line, and
  taking it needs a write-back (task 009). The switch test hammers one set, so the stress test is the number
  to judge by — see its `[SBC-DSTABORT]` line. If it holds there too, C2 as staged turns migration nearly
  off, and task 009 decides whether PLRU+SBC can win at all.
- **F7 — C2 stress test, PLRU: migration is almost switched off, and the cause is CLIENT-HELD, not dirty.**
  Run `008-c2-plru` (PASS 8/8, 0 asserts, 28,546,266 cycles): **20 migrations, 43,972 destination aborts**
  (C1 PLRU: 28,150 migrations, 128 aborts). Reasons: clean but client-held **40,369 (91.8%)**, dirty and
  client-held 3,219 (7.3%), **dirty only 384 (0.9%)**. So allowing dirty destination eviction alone
  recovers under 1% here (100% in the switch test, F6, which hammers one set). Aborts spread over 11
  (src→dst) pairs. **V6 PASS:** 384 + 40,369 + 3,219 = 43,972 = `ABORT-DST` printfs (`SBC_Aborted` 44,022 =
  those + 50 declines). **V3 PASS** (227,451 victim lines). Other numbers: accessA 357,719, primary hits
  210,442, secondary hits 3,041, data misses 144,236, memReads 144,234 / memWrites 24,226, parked 0.
  ⚠️ The accesses differ from C1's PLRU run (267,592) partly because the C2 stress binary gained the
  `[SBC-DSTABORT]` printf (007 F4 effect) — do not compare C1 and C2 totals directly.
  **Unchecked guess:** most client bits on D's least-recent line are stale (Rocket's L1 drops clean lines
  without a Release — CLAUDE.md G5; the source-side probe found them stale 99.99% of the time, `cbb3837`).
  **Gate stopped here by the coder on the user's word** (random switch test had just started): C2 will change,
  so the queued bitstream of C2-as-staged was not built. Options put to the user: A dirty-only write-back,
  B PLRU among clean client-free ways (masked tree walk), B+A, C probe + write-back (task 009, P1 deadlock
  shape). Waiting for the decision.
- **F2 — `PLRU-VICTIM` cannot tell a destination probe from a secondary-search read** (both `internal=1`).
  Tier 0/1 can only be a destination probe; `internal=1 tier=2` mixes both. Suggest adding `sec=` to the
  printf in commit 2 (sim-only). Not changed now: any Scala edit mid-gate forces a rebuild between the
  PLRU and random runs.

## Where the work order is wrong

- **§3.1 (commit 2) replaced on the user's decision, 2026-09-17.** Skipping the evictable tier made the
  destination take D's PLRU way, which in the stress test was unusable 43,972 times against 20 migrations
  (F7: 92% client-held, 1% dirty). Built instead: option B — the least-recent **clean, client-free** way of D
  (masked PLRU walk). **Recorded as a workaround:** it does not evict D's LRU line when that line is dirty or
  client-held, which the paper does; **task 009 is the permanent fix.** The three abort counters are unchanged
  and now count the probes where D has no clean, client-free way at all.

- **§4 V0 "byte-identical" cannot hold literally for any RTL edit.** firtool writes Scala line numbers into
  the Verilog three ways: `// @[File.scala:L:C]` locators, `at File.scala:L` inside every assert's `$error`
  string, and `connected at …/Configs.scala:L:C` inside TLMonitor messages (rocket-chip files I did not touch
  differ because `Configs.scala` gained two lines). Done instead: normalise every `.scala:N[:N]` and require
  the rest byte-identical (`results/008-v0/v0_compare.sh`). Raw: 18 files differ; normalised: 0.
- §2.4 cites `Scheduler.scala:142` for the SourceD-ready term; it is line 139 now. Content matches.

## Logs

- Gate driver log: `sw/verilator_logs/008-c1-gate.log` (tmux `sbc008`)
- Per run: `sw/verilator_logs/<test>_<config>_008-c1-{plru,random}/`
- V0 baseline: `results/008-v0/pre-edit-generated-src/`, hashes `results/008-v0/pre-edit.sha256`
- V0 flag-off build: `results/008-v0/flagoff-generated-src-*`; compare script `results/008-v0/v0_compare.sh`;
  driver + log `results/008-v0/run_v0_and_repro.{sh,log}`
- Same-layout random: `sw/verilator_logs/migration_stress_test_*_008-c1-random0/` (SBC one stopped at F5),
  `008-c1-random0.log`; F5 repro on flag-off: `…VerilatorRocket8KL116KL2Config_008-c1-random0-flagoff/`
