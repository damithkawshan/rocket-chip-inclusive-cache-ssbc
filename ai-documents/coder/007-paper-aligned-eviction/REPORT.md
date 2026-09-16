# Coder report 007 — put the paper's placement and eviction rules back

**Date:** started 2026-09-16 · **Author:** coder session · **Status:** IN PROGRESS — commit 0 gated, green

> Filled in as the work happens, not at the end.

## Summary

_(one sentence per commit as it lands)_

- **Commit 0 (exact parked counts)** — the two lossy fan-ins in `Scheduler.scala` are now one-per-cycle
  arbitrated (no event lost, none charged to the OR of two set numbers) and `SBC_Parked` moves by the
  PopCounts instead of by 1. **Gate green: 7/7 on both configs, T1–T4, 0 asserts, both shadow checkers
  quiet.** The one check that did **not** hold is TASK §2.3's "every counter identical" — see
  "Where the work order is wrong", it is not achievable on this platform and task 005's finding F2
  already said so.

## Commits

| # | SHA | What | Gate result |
|---|---|---|---|
| 0 | _(pending commit)_ | exact parked counts | ✅ 7/7 + 7/7, T1–T4, 0 asserts, shadows quiet |
| 1 | | guests can be evicted | |
| 2 | | a migration may reuse a guest slot | |
| 3 | | teardown | |
| 4 | | cap guests per pairing | |

## What commit 0 changed

| File | Change |
|---|---|
| `Scheduler.scala` | new local `def grantOnePerCycle(req, set, what)`: a pending bit + a latched set per MSHR, fixed-priority grant of one per cycle, three asserts. Used for the parked-erase path (was `reduce(_\|\|_)` + `Mux1H`) and for its twin `migReject` |
| `SetBalanceUnit.scala` | the two erase inputs `dispRelease`/`dispDrop` become one `dispErase` — after arbitration the two are indistinguishable, and the SBU only ever OR-ed them (`parkErase`) |
| `PerfCounters.scala` | `SBC_Parked` level moves by `migCommit` / `dispRelease + dispDrop` (PopCounts), clamped at 0, instead of by ±1 on a boolean |
| `sw/migration_stress_test.c` | one new `[SBC-PARK] parked= dispRelease= dispDrop=` line in `sbc_summary()` — see finding F1 |
| `CLAUDE.md` | `SBC_Parked` register row: says it is exact from this commit |

**Where the pending registers live.** TASK §2.1 says "each MSHR holds its erase event". They are in the
Scheduler's existing `if (params.micro.enableSetBalancing)` block instead — same structure, same
guarantee, but **zero hardware in the SBC-off build for free**. In the MSHR they would have needed their
own `enableSetBalancing` gate to keep the off build bit-identical, and the logic would be split over two
files. Agreed with the thinker before the edit.

**The SBC-off build.** Every Scheduler change is inside the existing `if (enableSetBalancing)` block, so
SBC-off elaborates none of it. The `PerfCounters` change is not gated by `enableSetBalancing` — that file
was deliberately put outside the gate by task 005 so both halves of an A/B count identically — so the
SBC-off **Verilog** for the parked level does change shape (two adders instead of ±1). It is dead there:
`migCommit`, `dispRelease` and `dispDrop` can never assert without SBC, so `SBC_Parked` stays 0, and no
cache decision reads the level. Cache behaviour in the SBC-off build is unchanged; the netlist is not
byte-for-byte the same.

**PerfCounters keeps the raw pulses** (TASK §2.1): `SBC_DispRelease` / `SBC_DispDrop` still count
`PopCount` on the cycle the event happens. Only the SBU path is delayed.

**Why the 1-deep slot cannot overflow.** Each MSHR can hold one event and cannot raise a second until it
is granted; with fixed priority the backlog is at most `mshrs` and drains one per cycle, so the delay is
bounded by `mshrs` cycles while an MSHR's eviction sequence is far longer. The third assert
(`"... event lost, slot still full"`) polices exactly that, so it is checked rather than assumed.

## Did `SBC_Parked` change after commit 0?

**There is no before value to compare against — see F1.** `migration_stress_test` never read `SBC_Parked`
until this commit added the read, so the only "before" figure that exists anywhere in simulation is the
switch test's, and that one is **unchanged**: `parked = 2` after T3 and `3` after T4, exactly as in
`005-c1` and `006-closeout`.

After commit 0 the stress test ends at **`parked = 18`**, with `migrations = 8,354`, `dispRelease = 0`,
`dispDrop = 8,337`. The F8 identity gives `8,354 − 0 − 8,337 = 17`, so the gap is **1**, and that one is
explained by the reads being at different instants (F11: `sbc_summary()` is not bracketed by
`L2_StatsHold`, and `SBC_Parked` is a level that is never held anyway).

**This does not confirm the F8 explanation, and there is a reason to doubt it.** The old assert
`PopCount(dispOH) <= 1` never fired in any recorded run, so two erases in one cycle never happened in
simulation — which means the old `SBC_Parked` arithmetic was already exact *here* and the fix cannot move
the simulated value. The board's 340–376 gap therefore needs the double-erase case to be common on the
board (16 ways, ~1 M migrations, real workload) rather than absent as it is in this 8-set test. Plausible,
not proven. **The board A/B after commit 4 is what settles it**, and until then the 477–483 parked figure
should be treated as "possibly inflated, by an unknown amount" rather than either confirmed or corrected.

One related observation: `dispRelease = 0` in this test — **every** parked-line reclaim here is a clean
drop. The dirty-writeback half of that path (leftover **L2**) still has no coverage, and commit 1 is what
is expected to produce it.

## Checks

| # | Check | Result |
|---|---|---|
| C1 | `migration_stress_test` 7/7, 0 asserts, `VerilatorRocket8KL116KL2Config` | ✅ 7/7, PASS, 27,819,576 cycles |
| C2 | `migration_stress_test` 7/7, 0 asserts, `…NoSbcConfig` | ✅ 7/7, PASS, 26,249,306 cycles |
| C3 | `sbc_migrate_switch_test` T1–T4 | ✅ all four PASS; skips correctly on `…NoSbcConfig` |
| C4 | both shadow checkers quiet | ✅ no `SBC shadow`, no `Assertion failed`, in any of the four runs |
| C5 | every counter identical to `sbc-start-2026-09-16` except `SBC_Parked` | ❌ **not achievable** — see below. The deterministic half of it (the switch test) **is** identical |
| C6 | the three new arbitration asserts never fire | ✅ never fired in ~54 M simulated cycles |
| C7 | at rest, `parked == migrations − dispRelease − dispDrop` (the F8 identity, in simulation) | ✅ off by 1, explained by F11 |
| C8 | the switch test's numbers match the baseline exactly | ✅ `migrations 2 → 3`, `parked 2 → 3`, events `211 → 399`, parked-at-flip 3 — identical to `005-c1` **and** `006-closeout` |
| C9 | SBC-off build behaviour unchanged | ✅ `parked`, `migrations`, `dispRelease`, `dispDrop` all 0, so the changed PerfCounters arithmetic never moves there |

### Why C5 cannot be met (evidence, not opinion)

`migration_stress_test` diverges across **every** build pair on this platform, which task 005 recorded as
finding **F2** (the `.out` traces differ at cycle 32, before any L2 traffic). Task 005's own two commits
show the size of it: `migrations` went **14,903 → 8,661** between 005-c0 and 005-c1, a 42% swing from a
counter-only change. Commit 0's 8,354 sits inside that band.

The decisive control is the **`…NoSbcConfig` run**, where none of the `Scheduler` / `SetBalanceUnit`
changes elaborate at all and the only altered logic is the parked arithmetic in `PerfCounters` — whose
three inputs are provably constant 0 there (the run reports `migrations=0 dispRelease=0 dispDrop=0
parked=0`, so the arithmetic never once moved). That build still diverged:

| `…NoSbcConfig` | 005-c1 | 007-c0 |
|---|---:|---:|
| `L2_AccessA` | 312,350 | 333,558 (+6.8%) |
| `L2_PrimaryHit` | 175,399 | 198,205 |
| `L2_DataMiss` | 136,951 | 135,353 |
| hit rate | 56.15% | 59.42% |

A build that cannot have changed behaviour moved by the same order as the build that did. So a counter
diff between builds carries no signal here, in either direction.

**What commit 0 genuinely does change, and it should not hide behind that noise:** the `migReject` grant
is now at least one cycle late, and it feeds the DSS block list, so destination rotation shifts. TASK §2.1
ordered that fix and says the delay is safe in both directions; it is still a real behaviour change, not a
no-op. The same is true of the `parkCount` decrement (one cycle of `mayHold` staying true = at most one
wasted search).

## Numbers after each commit (simulation)

_The only per-change evidence there is — there is no runtime switch._

| after commit | `SBC_Parked` end | `SBC_Migrations` | primary hits | secondary hits | data misses | hit rate |
|---|---:|---:|---:|---:|---:|---:|
| start (`sbc-start-2026-09-16`) | not readable (F1) | 8,661 | 140,118 | 12,158 | 163,976 | 48.15% |
| 0 exact counts | 18 | 8,354 | 154,723 | 13,773 | 171,605 | 49.55% |
| 1 guests evictable | | | | | | |
| 2 reuse a guest slot | | | | | | |
| 3 teardown | | | | | | |
| 4 cap | | | | | | |

Start row from `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2Config_005-c1/`, which is
a run of this exact tree (task 005 commit 1 = the tag). Hit rate = (primary + secondary) ÷ `accessA`
(316,251) per `cache-terminology.md`.

## Findings (reported, not fixed)

- **F1 — `migration_stress_test` never read `SBC_Parked`.** `sbc_summary()` printed the counters,
  the memory traffic and the outcome counters but not the parked level, and no other line in the test
  reads `0x3A0`. So the headline number of this whole task was not observable in simulation at all —
  only `sbc_migrate_switch_test` printed it, at values of 2 and 3. Fixed here because TASK §8 asks for it
  after every commit (one extra `printf`, test-only, no RTL). This is also why the "start" row above has
  no parked figure: there is nothing in the old logs to read.
- **F4 — one extra `printf` moved every counter in the stress test by 5–13%, on both configs.** This is
  the important one. Adding the `[SBC-PARK]` line to `sbc_summary()` is the only change to the test, and
  it happens at the very end of the run, yet:

  | stress test | `005-c1` | `007-c0` | |
  |---|---:|---:|---|
  | SBC `accessA` | 316,251 | 340,101 | +7.5% |
  | SBC `attempted` / `aborted` | 27,001 / 18,426 | 36,532 / 28,629 | +35% / +55% |
  | SBC `memWrites` | 16,162 | 41,463 | +156% |
  | **NoSbc** `accessA` | 312,350 | 333,558 | +6.8% |
  | **NoSbc** `primaryHit` | 175,399 | 198,205 | +13% |

  The **NoSbc** column is the proof that this is not the RTL: nothing in commit 0 elaborates in an
  SBC-off build except a dead adder in `PerfCounters`, and `SBC_Parked` reads 0 there either way. What
  changed is the binary — extra code shifts every instruction and data address, and on a **4 KB, 8-set,
  64-line** L2 that re-maps the whole working set. The SBC column then amplifies it through the
  migration feedback loop (more attempts → more probes → more L1 invalidations → more traffic).
  Task 005's finding F2 said this sim is not bit-reproducible across builds; it is worse than that —
  **it is not comparable across any change to the test binary.** Consequence for this task: the "report
  the five numbers after every commit" sequence in TASK §8 is only meaningful while `migration_stress_test.c`
  is left alone. It must not be edited again between commits 1 and 4, and the T-CAP / T-TEARDOWN cases
  of §8 will break the chain when they are added — they should go in a **separate test binary**, or be
  added once, up front, so every later commit is compared against the same binary.

- **F2 — CLAUDE.md's new "Proposed fix" bullet still says "behind **one runtime register**".** TASK §3,
  written the same day, decided the opposite ("no new control register … do not build switchable
  variants"). Whoever reads CLAUDE.md first will expect a register that this task will not build.
- **F3 — CLAUDE.md's `SBC_Aborted` row is stale.** It says "two declines/aborts in one cycle count once
  (OR fan-in) — coder/005 fixes". Task 005 commit 0 already did it: `perf.io.sbc.migAbort` is a
  `PopCount` over the MSHRs (`Scheduler.scala`, the PerfCounters wiring block). The row should now say
  it is exact.

## Where the work order is wrong

**Commit 0, §2.3 — "every counter identical to the `sbc-start-2026-09-16` run except `SBC_Parked`" is
not achievable on this platform, and task 005 already knew.** Finding F2 of the 005 report says the
Verilator sim is not bit-reproducible across builds, and TASK 005 §0 accepted that ("values need not
match"); TASK 007 §2.3 asks for the opposite. The `…NoSbcConfig` control above shows a build that cannot
have changed behaviour moving by 6.8% on accesses. **Suggested replacement for the later commits:** keep
the five numbers as the trend record, but treat the *switch test* as the exactness check — it is short
and deterministic and reproduced the baseline byte for byte through three builds now (006-closeout,
005-c1, 007-c0).

**Commit 0, §8 — the five numbers could not be read before this commit.** `SBC_Parked` was not readable
from `migration_stress_test` at all (F1), so the "start" row of the numbers table can never be filled
retrospectively. From commit 1 on it is fine.

**Otherwise commit 0: nothing wrong.** Both quoted blocks (§2.1 `Scheduler.scala:689-694`, §2.2
`PerfCounters.scala:233-237`) matched the tree character for character; the erase block actually starts
one line earlier, at 688, which changes nothing. §2.1's claim that the SBU is the only consumer of the
two erase inputs is correct — `parkErase` is their only use.

## Logs

- `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2Config_007-c0/`
- `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2NoSbcConfig_007-c0/`
- `sw/verilator_logs/sbc_migrate_switch_test_VerilatorRocket8KL116KL2Config_007-c0/`
- `sw/verilator_logs/sbc_migrate_switch_test_VerilatorRocket8KL116KL2NoSbcConfig_007-c0/` (skips, SBC off)

The commit-0 gate was interrupted by a session restart after run 2 of 4; the simulator survived as an
orphan and finished, and runs 3 and 4 were collected and completed by hand with the same
`run_sbc.sh` invocation (`SBC_CLEAN=0`, same build). No rebuild happened in between.

- `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2Config_007-c0/`
- `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2NoSbcConfig_007-c0/`
- `sw/verilator_logs/sbc_migrate_switch_test_VerilatorRocket8KL116KL2Config_007-c0/`
- baseline for comparison: the matching `…_005-c1` directories (a run of the tag's tree)
