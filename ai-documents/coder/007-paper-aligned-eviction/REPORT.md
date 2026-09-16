# Coder report 007 — put the paper's placement and eviction rules back

**Date:** started 2026-09-16 · **Author:** coder session · **Status:** IN PROGRESS — c0–c2 + random-source-victim landed; **C3 blocked by B7-1, 256 KB blocked by B7-2** (see RESUME HERE)

> Filled in as the work happens, not at the end.

## ▶ RESUME HERE — state at end of 2026-09-17 (02:50)

**Two blockers, both OPEN in `ai-documents/bugs/bug-fix-log.md`:**

1. **B7-1 (finding F7) — stale `dispHome`.** A reclaimed guest is charged to the wrong set, so a source's
   `parkCount` never goes down. Teardown (C3) can never fire, the cap (C4) would lock sources out forever,
   and `mayHold` is stuck true (likely most of the board's wasted searches). Pre-existing, found by
   T-TEARDOWN. **Fix is designed, not built** (bug log has it). **Do this first.**
2. **B7-2 — combinational loop at 256 KB.** Vivado DRC `LUTLP-1` refused the 256 KB bitstream of `251c9d7`.
   The pre-007 256 KB build and the c0–c2 64 KB build were both clean. Cause unknown; do not bypass it.

**Tree state (branch `sbc-sampling`, HEAD `251c9d7` = tag `sbc-007-c2-breakeven-2026-09-16`):**

| Item | State |
|---|---|
| C3 RTL (`MSHR.scala`, `SetBalanceUnit.scala`, `Scheduler.scala`) + case 8 in `sw/migration_stress_test.c` | **uncommitted** in the working tree; gate FAILED (B7-1). Snapshot: `wip-2026-09-17/c3-uncommitted.patch` |
| C4 | **not applied.** Staged, dry-run-verified patch: `wip-2026-09-17/apply_c4.py` (`DRY=1` to check, run without it to apply). Adds a config to the **chipyard** repo's `RocketConfigs.scala` |
| `sw/sbc_guest_cap_test.c` (T-CAP) | **untracked**, compiles. Snapshot in `wip-2026-09-17/` |
| 256 KB bitstream | **none** — build failed (B7-2). tmux session `sbc256` still open |
| ⚠️ `fpga/bitstream_storage/…256K16WL2ConfigSBC-random-source-evict-2026-09-17.bit` | **MISLABELLED — byte-identical to the OLD pre-007 image** (`cmp` confirmed). It was archived after the failed build. Do not program it; delete or rename |

**Order for the next session:**
1. Fix B7-1 in `MSHR.scala` (on top of the uncommitted C3).
2. Re-run the commit-3 gate (`SBC_LABEL=007-c3`). Expect case 8 PASS, `TEARDOWN` printfs, a big drop in `L2_SecondSearch`.
3. Commit B7-1 and C3 as two commits.
4. Apply C4 (`apply_c4.py`), gate + T-CAP on `VerilatorRocket8KL116KL2GuestCap1Config`.
5. Investigate B7-2 before any new 256 KB bitstream. The 64 KB builds were clean, so a 64 KB board A/B of
   B7-1 + C3 (+ C4) does not wait on B7-2 — and it is the likeliest result for the supervisor, since B7-1
   should remove most wasted searches.

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
| 0 | `1486d4a` | exact parked counts | ✅ 7/7 + 7/7, T1–T4, 0 asserts, shadows quiet |
| 1 | `f885382` | guests can be evicted | ✅ 7/7 + 7/7, T1–T4, 0 asserts, shadows quiet; **SBC-off build bit-identical** |
| 2 | `060e96d` | a migration may reuse a guest slot | ✅ 7/7 + 7/7, T1–T4, 0 asserts, shadows quiet |
| 3 | | teardown | |
| 4 | | cap guests per pairing | |

## What commit 0 changed

| File | Change |
|---|---|
| `Scheduler.scala` | new local `def grantOnePerCycle(req, set, what)`: a pending bit + a latched set per MSHR, fixed-priority grant of one per cycle, three asserts. Used for the parked-erase path (was `reduce(_\|\|_)` + `Mux1H`) and for its twin `migReject` |
| `SetBalanceUnit.scala` | the two erase inputs `dispRelease`/`dispDrop` become one `dispErase` — after arbitration the two are indistinguishable, and the SBU only ever OR-ed them (`parkErase`) |
| `PerfCounters.scala` | `SBC_Parked` level moves by `migCommit` / `dispRelease + dispDrop` (PopCounts), clamped at 0, instead of by ±1 on a boolean |
| `sw/migration_stress_test.c` | one new `[SBC-PARK] parked= dispRelease= dispDrop=` line in `sbc_summary()` — see finding F1 |
| `CLAUDE.md` | `SBC_Parked` register row: says it is exact from this commit. It rode in with the thinker's own uncommitted 2026-09-16 doc edits in `fea9353`, one commit earlier, on the user's instruction — not with the code as TASK §9 would have it |

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
| 1 guests evictable | 5 | 17,431 | 177,331 | 4,196 | 150,219 | **54.71%** |
| 2 reuse a guest slot | 4 | 20,108 | 192,218 | 3,788 | 136,773 | **58.90%** |
| + random source victim (experiment, kept) | 4 | 20,343 | 192,820 | 4,646 | 135,300 | **59.34%** |
| 3 teardown | | | | | | |
| 4 cap | | | | | | |

Start row from `sw/verilator_logs/migration_stress_test_VerilatorRocket8KL116KL2Config_005-c1/`, which is
a run of this exact tree (task 005 commit 1 = the tag). Hit rate = (primary + secondary) ÷ `accessA`
(316,251) per `cache-terminology.md`.

## Commit 1 (C1) — what the gate showed

**Gate:** `migration_stress_test` 7/7 on both configs, `sbc_migrate_switch_test` T1–T4, 0 asserts, no
shadow-checker output. The switch test again reproduced the baseline exactly (`2 → 3`, `211 → 399`).

**The SBC-off build came out bit-identical to commit 0** — same counters to the digit, same
26,249,306 simulation cycles. That is the strongest form of TASK §9's "SBC-off build unchanged" check and
it also calibrates the noise: when the Verilog does not change, the run does not change. (With SBC off no
line is ever `displaced`, so the deleted mask folds away and the netlist is the same.)

| | after c0 | after c1 | |
|---|---:|---:|---|
| hit rate | 49.55% | **54.71%** | +5.2 points |
| `L2_PrimaryHit` | 154,723 | 177,331 | +14.6% |
| `L2_SecondaryHit` | 13,773 | 4,196 | **−70%** |
| `L2_DataMiss` | 171,605 | 150,219 | −12.5% |
| `L2_MemReads` + `L2_MemWrites` | 213,023 | **176,443** | **−17.2%** |
| `L2_Cycles` | 13,840,057 | 13,210,772 | −4.5% |
| `SBC_Migrations` | 8,354 | 17,431 | 2.1× |
| `SBC_Parked` at end | 18 | 5 | |
| `dispRelease` / `dispDrop` | 0 / 8,337 | **1** / 17,425 | |

**The trade is visible and it is the one the change predicts:** guests are evicted quickly now, so
secondary hits collapse by 70% — but primary hits rise by more than that loss, and the headline metric
(`memReads + memWrites`, measured at the outer port) falls 17%. Guests stopped crowding out home lines.

**This is one run and it is a cross-build comparison**, which F2 says carries no signal on its own. What
makes it worth something here is the NoSbc control being bit-identical across the same two builds, plus
the size and the direction of the move. The board A/B after commit 4 is still what decides it.

**The parked identity is now exact:** `17,431 − 1 − 17,425 = 5`, and `SBC_Parked` reads exactly 5. Commit
0's off-by-one was read skew, as stated.

## Commit 2 (C2) — what the gate showed

**Gate:** 7/7 on both configs, `sbc_migrate_switch_test` T1–T4, 0 asserts, no shadow-checker output. No
corruption from a migration overwriting a guest as its destination victim.

**The switch test is the real evidence here, not the stress test — it is short, deterministic, and
reproduced the pre-C2 numbers exactly through commits 0 and 1.** Same hammer, same destination set:

| | commits 0–1 (and the original baseline) | after C2 |
|---|---:|---:|
| T3: migrations / parked | 2 / 2 | **47** / 2 |
| T4: migrations / parked | 3 / 3 | **48** / 3 |

Migrations rose 16–24×, and parked did **not move**. That is the accounting working exactly as intended:
once the one destination set filled with guests, migrations used to stall (no home line left to take);
now they take a guest's slot instead, the migration commits, and one guest leaves as one arrives - net
zero on `parkCount` and `SBC_Parked` alike. Implied reuse share here: 45 of 47 commits (`47 − 2 −
dispRelease(0) − dispDrop(0)`).

The stress test moved the same direction, more noisily (cross-build, F2 applies): migrations 17,431 →
20,108 while `dispDrop` fell 17,425 → 5,645 and `parked` stayed low (5 → 4) - consistent with most new
commits now being reuses.

**No hardware exists to read the reuse rate directly.** TASK §3's "no new register" is read here as
covering a new monitoring counter, not only a policy switch - a new register still needs a new MMIO
offset and address-map/header changes, which is exactly the kind of addition §3 is trying to avoid. The
switch-test arithmetic above is offered as the substitute evidence.

## Board run after commit 2 (FPGA, 2026-09-16) — the fix works, and SBC is now break-even

Session `chipyard/scripts/logs/board_session_20260916-175311.log`. 64 KB L2
(`FPGASingleRocketVCU118L18K64K16WL2ConfigSBC`), bitstream built 17:52 from the commit-2 tree, one
boot, `-ab` (migrate OFF half then ON half), omnetpp `--sim-time-limit=0.002s`, both halves
`workload rc=0` and both reached the simulation time limit, so this is fixed work.

**The control holds.** The migrate-OFF half cannot be touched by C1 or C2 (nothing is ever a guest),
and it reproduced the 2026-09-15/16 sessions to within 0.1%:

| OFF half | 09-15/16 runs | this run |
|---|---:|---:|
| `L2_AccessA` | 1,017.6 M / 1,017.8 M | 1,018.5 M |
| hit rate | 67.21% / 67.19% | 67.22% |
| `memReads + memWrites` | 403.0 M / 403.2 M | 403.4 M |
| `L2_Cycles` | 50.90 B / 50.88 B | 50.94 B |

So the ON-half comparison below is valid.

**What the four rule changes did to the ON half:**

| ON half | old rules (09-15/16) | after c0+c1+c2 |
|---|---:|---:|
| hit rate | 35.17% / 34.74% | **67.11%** |
| `L2_PrimaryHit` | 29.45% / 30.32% | 67.05% |
| `L2_SecondaryHit` | 5.73% / 4.42% | 0.064% |
| `memReads + memWrites` | 1,101.6 M / 1,090.6 M | **404.8 M** |
| `L2_Cycles` | 77.93 B / 77.46 B | **51.37 B** |
| `SBC_Parked` at end | 483 / 477 | **1** |
| `SBC_Migrations` | 1.04 M / 0.83 M | 4.31 M |

**The regression is gone.** Against the OFF half in the same boot: cycles **+53.1% → +0.85%**, memory
traffic **2.73× → 1.004×**. The 47%-parked fixed point is gone with it — 483 lines → 1.

**But SBC does not win. It is break-even to very slightly negative:**

| ON vs OFF, same boot | |
|---|---:|
| hit rate | 67.11% vs 67.22% — **0.11 points worse** |
| `memReads + memWrites` | 404.80 M vs 403.38 M — **+0.35%** |
| `L2_Cycles` | 51.369 B vs 50.935 B — **+0.85%** |

**The problem has inverted.** Guests used to never leave; now they leave almost immediately.
`SBC_Parked` sits at 1–33 lines out of 1,024. The second search ran **180.7 M** times and hit
**650 k** — a **0.36%** search hit rate, against 5.73%/4.42% of accesses under the old rules. Migration
is now nearly free but also nearly pointless: the cost is paid in searches, and the payoff is gone
because a guest is overwritten before anyone looks for it.

**C2's reuse share, derived from the identity** `reuse = migrations − dispRelease − dispDrop − parked`:
4,312,348 − 652 − 736,780 − 1 = **3,574,915 = 82.9% of all migrations reused a guest slot.** That is
the first direct measurement of the C2 path, and it confirms the mechanism the switch test implied.

**F4 is answered, and the prediction was right: `dispRelease = 652`.** The dirty-guest writeback
path — one execution in its entire history before this — ran 652 times on a real workload with no
assert, no corruption and a clean `workload rc=0`. Leftover **L2** now has real coverage. It is still
only 0.09% of the 737,432 reclaims, so it is exercised, not stressed.

### What this means for commits 3 and 4 (for the thinker, not a coder decision)

- **C4's cap is very likely a no-op now.** The fix plan sized it to bring 15-of-16 guests per pairing
  down to the paper's ~2.15. The measurement says we are already at roughly **1**. A cap of 2 would
  almost never bind. Building it would be honest to the work order and change nothing.
- **C3's teardown becomes high-frequency, not occasional.** With sources holding ~1 guest, `parkCount`
  hits zero on close to every reclaim — on the order of **737 k teardowns** across this run, each
  unpairing two sets that then re-enter the DSS. The fix plan modelled teardown against "pairs never
  end"; the regime it will actually land in is "pairs barely last". That is still the paper's rule and
  it is still worth building, but it is a much more dynamic change than the plan assumed, and N1's
  "blocked by a live migration" path will be hit constantly.
- **The lever that matters now points the other way.** The open question is no longer how to get
  guests out; it is how to keep a guest resident long enough to be found. That is a design decision,
  and it is outside this task.

## Experiment (outside C1–C4): random victim on the source side — KEPT

**2026-09-16.** Not a task-007 rule change: it removes a Phase 2 behaviour. Started from tag
`sbc-007-c2-breakeven-2026-09-16`. Folded in here from a standalone `performance/` doc (commits
`cacd961`, `838a649`, `acf9bec`); full history is in those commits.

**What was wrong.** On a demand miss to a hot source set, the Scheduler raised `preferEvictable` on the
directory read (`alloc_uses_directory && adviceMigrate`). The victim mux then returned the **first**
clean, client-free way instead of a random one. So in a hot set, way 0 (if eligible) was evicted on every
miss, while the plain L2 always evicts at random. It was added in Phase 2 to make migration possible at
all (the low-p blocker); probe-then-migrate (`cbb3837`) later solved that a better way.

**The change.** One line in `Scheduler.scala`: `preferEvictable` is now driven only by the migration
destination probe. SBC evicts exactly the line the plain L2 would; migration only changes where that
victim goes.

**Verilator gate — green.** 7/7 on both configs, T1–T4, 0 asserts, shadows quiet. SBC-off build
bit-identical again (26,249,306 cycles).

| `migration_stress_test`, SBC on | commit 2 | experiment |
|---|---:|---:|
| hit rate | 58.90% | **59.34%** |
| `L2_PrimaryHit` / `L2_SecondaryHit` | 192,218 / 3,788 | 192,820 / 4,646 |
| `L2_DataMiss` | 136,773 | 135,300 |
| `SBC_Migrations` | 20,108 | 20,343 |

**Board A/B** — 64 KB, `board_session_20260916-221525.log`, same omnetpp fixed work, both halves
`rc=0`. Image archived as `fpga/bitstream_storage/…64K16WL2ConfigSBC-preferEvictable-experiment-2026-09-16.bit`.

| ON vs OFF, same boot | tag (c2) | experiment |
|---|---:|---:|
| `memReads + memWrites` | +0.352% | **+0.072%** |
| `memReads` (= data misses) | +0.399% | **+0.032%** |
| `L2_Cycles` | +0.853% | **+0.411%** |
| hit rate | −0.116 pts | −0.209 pts |

| ON half | tag (c2) | experiment |
|---|---:|---:|
| `SBC_Migrations` | 4,312,348 | 3,353,354 (−22%) |
| `L2_SecondaryHit` | 649,606 | 705,154 (+8.5%) |
| secondary hits per migration | 0.151 | **0.210** |
| `SBC_DispRelease` | 652 | 4,057 |

**Reading it.** Every outer-port metric moved the right way: extra memory traffic fell ~5×, extra cycles
roughly halved. Fewer migrations produced more secondary hits, so a random victim is a better migration
candidate. The hit rate moved the other way only because the ON half issued 0.60% fewer L1 requests — a
denominator effect; absolute data misses fell. Limits: SBC still does not beat the plain L2, and the
effect is about the size of the boot-to-boot drift between the two sessions' OFF halves (+0.17% traffic,
+0.27% cycles), from one run each.

**Decision (user, 2026-09-17):** kept without a repeat run. Committed as the new baseline; tag
`sbc-007-c2-breakeven-2026-09-16` moved onto it.

## Commit 3 (C3) — teardown

**2026-09-17.** Built from tag `sbc-007-c2-breakeven-2026-09-16` (`251c9d7`). Chisel edited only after the
256 KB bitstream build had reached Vivado, so none of this is in that bitstream.

**What landed.**

| File | Change |
|---|---|
| `SetBalanceUnit.scala` | detect `parkCount(src)` going 1 → 0; queue it in a 1-deep slot; drain only while no migration is in flight (**N1**) and not in a commit cycle; **re-check `parkCount == 0` when it drains**; clear `at(src)` and `at(partner)`. Asserts: a commit never happens with `anyMigrating` low; the partner points back. `sbcDebug` printfs `TEARDOWN` / `TEARDOWN-CANCEL` / `TEARDOWN-DROP` are the only teardown counts (no new register, TASK §3) |
| `Scheduler.scala` | `sbu.io.anyMigrating := anyMigrating` |
| `MSHR.scala` | **N2**: `pairStale` compares the pairing latched when the search was issued with the live one when the answer arrives. Stale → `willServe` false and the whole hit branch skipped, so it counts as a secondary miss and the eviction goes ahead. Sim assert reports it |
| `sw/migration_stress_test.c` | new case 8, T-TEARDOWN (below) |

**Why the hit branch is skipped, not just `willServe`.** A stale answer can match a guest parked by a
*different* source after the partner was re-paired, and the existing `homeShadow` assert inside the hit
branch would then fire on a correctly handled event.

**Why N2's "treat as a miss" is safe.** A search from source S only runs on S's own A-channel MSHR, and
there is one per set, so no migration from S can commit while S is searching. So if S's pairing changed
during the search, it was torn down, which only happens at `parkCount(S) == 0`: nothing of S's is parked.

**T-TEARDOWN** (case 8, stock config, runs in every gate): hammer a set until it reads as a source, read
its partner from `SBC_AtAssoc`, write every source line (the parked ones are served in place and become
dirty guests), then flood only the partner with fresh misses until the source↔partner pairing is gone from
both AT entries, and re-read every written line. SKIPs on NoSbc and under `SBC_MIGRATE_OFF`.

**Gate: FAILED — stopped on the new case, not on RTL correctness.** Cases 1–7 PASS, 0 RTL asserts, both
shadow checkers quiet, none of the new N1/N2/teardown asserts fired. **Case 8 FAILED**: pairing 4↔0 still
present on both entries after 4,000 partner misses, data correct. The test's non-zero exit stopped
`run_sbc.sh`, so the NoSbc runs and the switch test did not run. **Zero `TEARDOWN`, `TEARDOWN-CANCEL` or
`TEARDOWN-DROP` lines in the whole run** across 19,998 migrations and 5,415 guest reclaims: the source's
count never went 1 → 0. Cause is finding F7 (a pre-existing bug), not the teardown logic. C3 RTL left
uncommitted in the tree; commit 4 not applied.

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
- **F4 — the dirty-guest writeback path is still, effectively, untested.** Commit 1 was expected to put
  leftover **L2** under real load, and it did for the clean half: `dispDrop` doubled to 17,425. But
  `dispRelease` — a *dirty* guest written back to memory at its recovered home address — fired **once**
  in 26.5 M cycles, up from zero. A guest only becomes dirty if a writer is served in place, and
  `SBC_SecWrite`/`SecPerm` stay ~0 on one core (leftover L7). So the riskiest single line in this design
  has one execution behind it. It needs the two-core config, not more of this test.
- **F5 — C1 costs secondary hits, 70% of them.** Not a defect; worth the thinker seeing the size of it,
  because it is the mechanism C4's cap is meant to balance: guests that live longer are found more often,
  guests that die fast are not found at all. C1 alone moves all the way to the "die fast" end.
- **F7 — a reclaimed guest is charged to the WRONG set in `parkCount`. Pre-existing (since 003 §10.5), found
  by T-TEARDOWN.** A destination-home MSHR evicting a guest calls `armEviction(new_meta, …)` in its **plan
  cycle** (`MSHR.scala` ~1683), and `dispReleasePulse`/`dispDropPulse` fire in that cycle. But
  `io.dispHome := lineHome`, and `lineHome` is built from `meta.displaced`, `pairValidReg`,
  `pairIsSrcReg` and `pairSetReg` — **all registers written in that same cycle** (`meta := new_meta`,
  and the pairing latch). So `dispHome` is computed from the *previous* transaction. The previous victim is
  almost never a guest, so `lineHome` falls back to `request.set` = the destination itself: the decrement
  lands on the destination (already 0, clamped) and the source's `parkCount` **never decrements**. It
  saturates at `ways` and stays there. Bug pattern #1 in CLAUDE.md ("reading a register in the same cycle
  it is written"). The Release address uses `lineHome` later, once the registers have settled, so data and
  shadow checkers are unaffected — only the per-set count is wrong. `SBC_Parked` is global and has no set
  index, so it stays exact.
  **Consequences:** (1) C3 teardown can never fire. (2) C4's cap reads the same count: once a source hits
  the cap it would be refused **forever** — the staged commit-4 patch must not be applied before this is
  fixed. (3) `mayHold` (the paper's "sc" bit) is stuck true for every source that has ever migrated, so the
  second search runs on every miss even with nothing parked. **This is very likely a large share of the
  board's 180.7 M searches / 0.36% search hit rate** — the "guests die too fast to be found" reading was
  at least partly a stuck bit. Needs a board run to confirm.
- **F8 — the 256 KB bitstream of `251c9d7` fails Vivado DRC with a combinational loop (bug log B7-2).**
  16 LUTs through `sinkC/c_q`, `mshrs_*/request_tag`, `mshrs_*/bad_grant`, `mshrs_3/migDstSet[7]`,
  `directory/request_set[7]`, `requests/request_tag`. Absent at 256 KB before 007 and at 64 KB after c0–c2.
- **F6 — `sw/sip_common.h` `park_n()` comment describes the rule commit 1 deleted.** It says "displaced
  ways are last-resort victims, so they persist while the fresh natives absorb the eviction". Since C1 a
  guest is an ordinary victim, so the top-up no longer protects parked lines the way the comment claims.
  The serve-in-place tests that rely on `park_n()` should be re-run under the new rules before their
  results are trusted again. Comment not changed (outside this task).
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

**Otherwise commit 0: nothing wrong.**

**Commit 3, §6 N1 — "hold it in a 1-deep pending register and retry" can orphan a guest.** Sequence: S's
last guest is evicted (`parkCount(S)` → 0, teardown queued); a migration from S is already in flight, so
the teardown waits; that migration commits and parks a *new* guest in D (`parkCount(S)` → 1); the migration
ends and the queued teardown drains, clearing a pairing that now has a live guest. That guest's home set is
recovered from the AT, so its eventual writeback goes to the wrong address. **Built instead:** the drain
re-checks `parkCount == 0` and cancels if not (`TEARDOWN-CANCEL`). It also refuses to drain in a commit
cycle, independently of `anyMigrating`, so the two AT writes can never race.

**Commit 3, §8 T-TEARDOWN — two changes to the test as written.**
1. *"Both must report unpaired" is not a valid pass condition.* Flooding the partner with misses makes it
   hot, and once the pairing breaks it may start migrating elsewhere at once, so it reads as paired again on
   a correct design. The test checks that the specific source↔partner pairing is gone from both entries.
2. *No `sbcForceDstSet`.* The partner is discovered from `SBC_AtAssoc`, so the case runs on the stock
   config inside every gate instead of needing its own build.

**Commit 3, §6 N2 — the reporting assert makes a handled event fail the gate.** The race is handled by
design (served as a miss), but an assert stops the simulation and the gate requires 0 asserts. Built as
written; if it fires, that is a real race and I will report it rather than turn it into a printf.

**Commit 2: nothing wrong.** §5's two read sites (`Directory.scala` `evictableOH`, `MSHR.scala:1468-1470`
`dstEvictable`) and the `allowDisplacedVictim` design matched as described. The `reusedGuestSlot` bit
TASK §5 asks for is named `migCommitReuse` on the wire (the MSHR's own register is `migDstReuse`) - kept
close to the existing `migCommit` naming rather than introducing a new term.

**Commit 1, §4 — the line reference has moved and the replacement snippet drops a safety net.** The
victim mux is at `Directory.scala:211-221`, not `216-226`; the content matched. §4's replacement ends at
`PriorityEncoderOH(freeWays)`, which returns **zero** when `freeWays == 0` and would turn the
`PopCount(victimWayOH) === 1` assert from a check into a failure. The existing final arm exists for
exactly that reason, so it was kept: `Mux(freeWays.orR, PriorityEncoderOH(freeWays),
PriorityEncoderOH(~0.U(ways.W)))`. Still a net deletion of the two category tiers, as §4 intends. The
`freeWays.orR` assert that makes it unreachable is only elaborated when `enableSetBalancing` is true, so
the SBC-off build would have had no net at all. Both quoted blocks (§2.1 `Scheduler.scala:689-694`, §2.2
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
