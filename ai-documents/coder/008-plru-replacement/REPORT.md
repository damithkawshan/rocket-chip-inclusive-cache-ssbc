# Coder report 008 — PLRU replacement behind `L2_Replacement`

**Date:** started 2026-09-17 · **Author:** coder session · **Status:** IN PROGRESS — commit 1 committed (V0–V3 green); commit 2 in progress

> Filled in as the work happens, not at the end.

## ▶ RESUME HERE

- **Commit 1 committed** on the user's go-ahead (2026-09-17). V0, V1, V2, V3 all PASS.
  Configs (chipyard repo, uncommitted) say `plruReplacement = true` in the three configs.
- ⚠️ **F5 = bug log B8-1 (tracker M19)** — pre-existing in `744fabb` (007 C3 teardown race), proven. Not fixed;
  its own task later (user). **If the same assert appears in the C2 gate, check the event sequence against
  B8-1 and report it as a recurrence, not as a C2 regression.**
- The Verilator simulator on disk for the SBC config is the **flag-off** build (from the F5 check); the next
  gate rebuilds it (`SBC_CLEAN=1`).
- Next: commit 2 (TASK §3) — started.

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

## Checks

| # | Status | Result |
|---|---|---|
| V0 | **PASS** | Flag set to `false` in both Verilator configs, `make verilog`, compared with the saved `007-b71-c3` build: 465 (SBC) and 463 (NoSbc) Verilog files **identical once Scala line numbers are normalised**; L2 regmap JSON, DTS and `l2.json` identical byte for byte. Not identical *raw*: 18 files differ, all in Scala line numbers only (see "Where the work order is wrong"). This also shows the saved build was `744fabb` |
| V1 | **PASS** | Both configs. Switch test: SBC T1–T4 PASS, NoSbc SKIP (as before); both `.log` **identical** to `007-b71-c3`. Stress test 8/8 (SBC) and 7/7 (NoSbc), 0 asserts — and **every counter identical to `007-b71-c3` in both configs** (F3) |
| V2 | **PASS** | Both configs, `[SBC] policy=1` read back in both (the register is built in NoSbc too). Stress test 8/8 / 7/7, 0 asserts, shadow checkers quiet. SBC: accessA 267,592 = 137,266 + 872 + 129,454 + 0 ✓, secondSearch 51,813 = 872 + 50,941 ✓. NoSbc: accessA 266,926 = 136,745 + 0 + 130,181 + 0 ✓ |
| V3 | **PASS** (8 logs) | `plruWay` = model on every line: SBC switch PLRU 9,608 / random 6,890, SBC stress PLRU 211,058 / random 234,210, NoSbc switch PLRU 173, NoSbc stress PLRU 130,191 / random 137,099. `chosen = plruWay` on every PLRU tier-2 pick. `res != chosen`: 0 in all. Model self-test: for 2/3/5/8/16 ways and every state, a just-touched way is never the victim |

## Numbers after each commit (simulation)

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
- **F2 — `PLRU-VICTIM` cannot tell a destination probe from a secondary-search read** (both `internal=1`).
  Tier 0/1 can only be a destination probe; `internal=1 tier=2` mixes both. Suggest adding `sec=` to the
  printf in commit 2 (sim-only). Not changed now: any Scala edit mid-gate forces a rebuild between the
  PLRU and random runs.

## Where the work order is wrong

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
