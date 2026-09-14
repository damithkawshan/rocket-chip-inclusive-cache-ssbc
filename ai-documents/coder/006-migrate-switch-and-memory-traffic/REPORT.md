# Coder report 006 — migration on/off switch + main-memory traffic counters

**Date:** 2026-09-10 (updated 2026-09-14, Amendment 6) · **Author:** coder session · **Status:** 🟢 **CLOSED** (2026-09-14) — Parts A/B/D/E landed, C partial; fixed-work board A/B done, SBC is slower; 64-bit counters committed (`06fcca8`), close-out cleanup committed

> Template. Fill in as you go, not at the end. A task that stops early still gets a report
> saying where it stopped and why.

## Summary

**Updated 2026-09-14 (Amendment 3). Parts A, B, D and E landed; C is partial** — the `sbc_read`
switch/reset/zero knobs shipped, but child cycle/instret timing was never built and is now moot (§13.4).
The fixed-work board A/B this task exists to feed has run, and **SBC is slower and moves more memory
traffic in all three pairs** — pair B (256 KB, the real geometry): **+46.0% cycles, 3.56× main-memory
accesses** (see Numbers). The one-bitstream A/B method is accepted: on the board, **"SBC built but
switched off" matches a separately-built NoSbc bitstream to within noise** (90.19% vs 90.24% hit rate,
fixed-time windows), so switching migration off behaves like not building SBC and the G3 two-build
offset below does **not** apply to the real measurement — the board A/B is migrate-ON vs migrate-OFF on
**one** bitstream.

What landed: **Part A** `SBC_MigrateEnable` (0x3C0, R/W, default OFF) gating `hotOK`, wired Control →
InclusiveCache → Scheduler → SetBalanceUnit, plus the `SBC_Reset`-while-parked assert; **Part B**
`PerfCounters` + `enablePerfCounters` and the five 0x3C8–0x3E8 memory-traffic MMIO counters; **Part D**
CLAUDE.md + `devmem-register-map.md`; **Part E** `L2_Cycles` (0x3E8), promoted to mandatory once
`rdinstret`/`perf_event_open` proved dead on the board (Amendment 1); and the software `sbc_read
--migrate/--reset-all/--zero` plus `sbc_migrate_switch_test.c` (4/4, its T4 **is** gate G4).

**Historical (2026-09-10): work first stopped at G3.** G3's counter-matching half fails as a literal
two-build comparison — `L2_Accesses` −5.21%, `L2_Hits` −7.36% between the switch-off and NoSbc builds —
but derived **misses differ by only 1.47%** and outer **AcquireBlock by 1.58%**, so the gap is in
*counted hit-lookups*, not data moved; its migration-gating half passes cleanly (7/7, all SBC counters
0). The board's one-bitstream method sidesteps this, which is why Parts B/D/E were then built.

## What was built

| Part | File(s) | Landed? | Notes |
|---|---|---|---|
| A — `SBC_MigrateEnable` (0x3C0) | `Control.scala`, `InclusiveCache.scala`, `Scheduler.scala`, `SetBalanceUnit.scala` | ✅ | R/W level bit, `RegInit(false.B)`, wired like `sbcSetSel`; gates `hotOK` only |
| A — `SBC_Reset` while-parked assert | `SetBalanceUnit.scala` | ✅ | placed just before the `when(io.clear)` block |
| B — `PerfCounters.scala` + `enablePerfCounters` | new file, `Parameters.scala`, `Configs.scala`, `Scheduler.scala` | ✅ | Hooked at `io.out.a.fire` (opcode-decoded) and `sourceC.io.req.fire` (`.bits.dirty`), per §B.2. Flag defaults true, plumbed through `WithInclusiveCache`; `false` skips the module and reads zero. |
| B — beat reconciliation assert | `Scheduler.scala` | ✅ | Upper bound only — see note below. Scala-gated on `beatsPerBlock > 1`. |
| B/E — five MMIO fields (0x3C8–0x3E8) | `Control.scala` | ✅ | |
| B/E — added to `SBC_StatsReset` clear list | `PerfCounters.scala` | ✅ | All five, including `L2_Cycles`. Not touched by `SBC_Reset`, same rule as `L2_Accesses`/`L2_Hits`. |
| C — `sbc_read --migrate=on\|off` | `sw/sbc_read.c` | ✅ | Writes 0x3C0 and **verifies the read-back**, aborting on mismatch. Warns when `SBC_Status` bit0 says SBC is not built in (the register exists in every build; only an SBC build has anything that reads it). Applied before `--zero` so the counter window starts with the switch already in position. Every dump now prints `migrate: ON / OFF / n/a`, per the "read-back is mandatory" rule. |
| C — `sbc_read --reset-all` (+`--force`) | `sw/sbc_read.c` | ✅ | Writes `SBC_Reset` (0x358) but **reads `SBC_Parked` first and refuses** if anything is parked, since the AT is a parked line's only home-set record. `--force` overrides, with a warning naming the count. The refusal is what catches an A/B run in the wrong order. |
| C — child cycle/instret timing | `sw/sbc_read.c` | ❌ not started | needs Part E's `L2_Cycles`; `rdcycle`/`rdinstret`/`perf_event_open` are all dead on this board |
| (unplanned) board A/B automation | `chipyard/scripts/ssbc_scripts/run_board_session.exp` | ✅ | Two-phase one-bitstream A/B, now the default. See below. |
| (unplanned) switch verification test | `sw/sbc_migrate_switch_test.c` | ✅ new | T1 read-back both ways, T2 gate off, T3 gate on, T4 flip-off-while-parked. T4 **is gate G4.** |
| C — `sbc_mmio.h` offsets | `sw/sbc_mmio.h` | ✅ | all six 006 offsets (0x3C0–0x3E8) are in the header. Part B has since landed, so the five 0x3C8–0x3E8 counter offsets are now answered by `PerfCounters` hardware (no longer "declared but unanswered"). |
| D — `CLAUDE.md` + `devmem-register-map.md` | both | ✅ | Register table extended to 0x3E8 in both. `devmem-register-map.md` had stopped at 0x358 — everything from 0x360 up was missing, now filled in, plus the `sbc_read` recipes and the A/B ordering rule. |
| D — config-word bit layout **was wrong** | `devmem-register-map.md` | ✅ fixed | It documented `[63:56]=banks … [39:32]=lgBlockBytes`. `RegFieldGroup` packs from the **LSB up** (`RegMapper.scala:44`, `fields.scanLeft(byte * 8)(_ + _.width)`), so it is `[7:0]=banks … [31:24]=lgBlockBytes`. Pre-existing error, unrelated to 006, found because `sbc_read` now decodes that word. |
| (unplanned) test-harness switch write | `sw/migration_stress_test.c` | ✅ | `#ifndef SBC_MIGRATE_OFF` guarded; see the contradictions section |

## Pre-flight: do the software cycle counters work?

**Already answered — see TASK.md §11 (Amendment 1). `rdcycle` traps from user mode on this board.**
What remains is the option-A probe.

**No board runs were performed in this session** — this session has no VCU118 access. The rows below
are transcribed from TASK.md §12.1, which recorded the probe results on 2026-09-10; they are the
thinker's measurements, not repeats of them. Gates G6–G12 all need the board and are unrun for the
same reason.

| Question | Answer |
|---|---|
| `./csrprobe` — `rdcycle` | TRAPS (illegal instruction) — as expected |
| `./csrprobe` — `rdtime` | **OK**, value=564750466 |
| `./csrprobe` — `rdinstret` | TRAPS (illegal instruction) |
| `cat /proc/sys/kernel/perf_event_paranoid` | not recorded in §12.1 |
| `./pe` — cycles read back | `perf_event_open failed: Invalid argument` |
| `./pe` — instructions read back | `perf_event_open failed: Invalid argument` |
| `./pe` — errno if it failed | `EINVAL` on both hardware events |
| **Verdict: is option A (perf_event_open) usable?** | **No.** The `cpu` PMU is registered but has no backing counters. Do not wire it into `sbc_read`. Instruction count is unavailable on this board by any route — no IPC. |

## What was built — Amendment 1 additions

| Part | File(s) | Landed? | Notes |
|---|---|---|---|
| E — `L2_Cycles` (0x3E8) in `PerfCounters` | `PerfCounters.scala`, `Control.scala` | ✅ | Promoted from optional to **mandatory** by the board finding below: with `rdinstret` and `perf_event_open` both dead, it is the only work/time normaliser available. |
| C(rev) — `sbc_read` prints traffic + cycles | `sw/sbc_read.c` | ✅ | Five registers appended to `REGS[]` (appended, not inserted — `show()` indexes positionally, so the old indices are now named constants). Prints `MEMORY ACCESSES`, bytes moved, and accesses/kcycle. |
| C(rev) — `rdtime` / `getrusage` child timing | `sw/sbc_read.c` | ❌ not done | `L2_Cycles` covers the primary need; the remaining sources are additive and unstarted. |

**Clock domain: not determined.** Deferred with Part E — the question only bites once `L2_Cycles`
exists. Flagging it as still owed rather than guessing: it needs checking against the actual FPGA
config (`SingleRocketVCU118L18K256K16WL2Config`) and its bus/tile crossing, not against the Verilator
config, where it is single-clock and therefore tells you nothing about the board.

**Is `L2_MemRelClean` ever non-zero? Unknown** — Part B not built, so the counter does not exist yet.
The question stays open. The cheap way to pre-answer it before building the register: count outer C
`Release` (no data) messages in an existing `sbcDebug` log, the same way the `AcquireBlock`/`Get`
split in the G3 table below was obtained.

## Verify

| # | Gate | Command run | Result |
|---|---|---|---|
| G0 | NoSbc + `enablePerfCounters=false` bit-exact | **not run** — needs a config with the flag off. The off-path is a Scala `if`, so no module is instantiated and the `else` drives zeros (same shape as the proven `io.sbcStats := 0.U.asTypeOf(...)`), but elaboration of that path is unexercised. Also see the contradictions section: this gate cannot hold *literally* once Part A lands. **Deferred to task 005** — its first check (V1) elaborates the `enablePerfCounters=false` build. | 005 |
| G1 | NoSbc + counters on; `L2_Accesses`/`L2_Hits` unchanged | `mst_prePartB.riscv` (Part-A-era source, `[SBC-MEM]` printf stripped) on the new NoSbc build | ✅ **PASS, exactly.** `l2Accesses=368219 l2Hits=233963` — byte-identical to the Part A run. `PerfCounters` is behaviourally transparent. |
| G2 | stress test, switch ON — 7/7 unchanged from `d8671cf` | `run_sbc.sh` clean rebuild, both configs, `migration_stress_test.riscv` (ON variant, `-DSBC_MIGRATE_OFF` undefined) | ✅ PASS — 7/7, 0 asserts. `migrations=13566 attempted=31507 aborted=18261 secHits=15290 secMiss=53918 l2Accesses=360436 l2Hits=187939`. **Not bit-diffed against a literal d8671cf rebuild** (would need a second full clean-rebuild cycle; the nearest captured baseline, `l2-hitrate-counters-004` from 2026-09-02, predates `322494a` "parked lines last priority" which changes victim selection and thus migration counts for unrelated reasons — not a valid comparison point). Correctness argued by construction instead: the only diff to `hotOK` is a new `io.migrateEnable &&` term forced to `1` by the SW write, so the gated expression is identical to pre-006 whenever the switch is on. |
| G3 | stress test, switch OFF — matches NoSbc | same simulator (no rebuild), `migration_stress_test_off.riscv` (`-DSBC_MIGRATE_OFF`) vs the NoSbc run above | ⚠️ **SPLIT VERDICT — see the delta table below.** Migration-gating half **passes cleanly** (7/7 PASS, 0 asserts, `migrations=0 attempted=0 aborted=0 secHits=0 secMiss=0`). Counter-matching half **fails as written**: `L2_Accesses` −5.21%, `L2_Hits` −7.36% vs the NoSbc run — far outside OR-reduction noise. But derived **misses** are only −1.47% apart and outer **AcquireBlock −1.58%**, so the divergence is concentrated in *counted hits*, not in data movement. |
| G4 | flip switch off mid-run with lines parked | `sw/sbc_migrate_switch_test.c` T4, on `VerilatorRocket8KL116KL2Config` | ✅ **PASS** — flipped OFF with 3 lines parked, then hammered 1500 iterations: `migrations` frozen at exactly 3, `secHits` kept climbing 211 → 399, no assert, no hang. Parked lines are still served in place while the switch is off. |
| G5 | memory-counter identity vs `sbcDebug` OUTER-A | stress test on both configs, counters vs the `OUTER-A` tally in the same run | ✅ **PASS — but the gate's premise in TASK.md is wrong, see below.** `memUpgrades` vs `perm=1`: 0 vs 0, exact, both configs. `memReads` vs `perm=0`: SBC 142,258 vs 142,315 (gap 57); NoSbc 133,301 vs 133,308 (gap 7). |
| G6 | `SBC_Reset` while parked — assert fires, `sbc_read` refuses | assert is in the RTL (`SetBalanceUnit.scala`) but **never exercised** — the switch-off run parks nothing, and no test issues `SBC_Reset` while parked. **Next board session:** after a migrate-on half leaves lines parked, `sbc_read --reset-all` must refuse (its parked-line check). | next board |
| G7 | full board recipe end to end | ✅ **board evidence** — the three fixed-work A/B pairs (2026-09-11 → 09-13) ran the full recipe end to end: boot, `sbc_read --zero -- omnetpp`, migrate off→on, dump. See Numbers / fpga-ab-baseline §4. | board |
| G8 | `L2_Cycles` calibration vs `sleep 10` | not run — Part E not started, and needs the board | — |
| G9 | `L2_Cycles` windowing consistent with wall clock | ✅ **board evidence** — `L2_Cycles` framed every A/B window on the board and tracked run length sensibly (pair B: 606 G off / 885 G on ≈ ~3.4 h / ~4.9 h at 50 MHz). Absolute CPU-cycle calibration is G8, still open (§13.4). | board |
| G10 | `sw/build/pe` verdict + errno recorded | ✅ recorded above, transcribed from TASK.md §12.1 (not re-run) | `EINVAL`, option A dead |

## Numbers

The table this task exists to produce — **the fixed-work A/B, run on the board 2026-09-11 → 09-13.**
Every run used `sbc_read --zero -- <omnetpp> --sim-time-limit`, so the benchmark **ran to the end** and
both halves reached the **same event count** (same work — the valid A/B). One bitstream, one boot:
migrate-OFF half first, then `--reset-all --migrate=on`. Values copied from the session logs via
[fpga-ab-baseline-2026-09-11.md](../../performance/fpga-ab-baseline-2026-09-11.md) §4.

**Main table — pair B** (256 KB 16-way L2, the real geometry and the longest run; sim-time 0.1 s,
35,128,553 events; `board_session_20260912-021811.log`):

| | SBC on (migrate ON) | SBC off (migrate OFF) | ratio ON/OFF |
|---|---:|---:|---:|
| `L2_MemReads` (blocks read from memory) | 8,983,608,947 | 2,322,681,628 | **3.87×** |
| `L2_MemWrites` (dirty blocks written) | 2,040,240,733 | 775,830,225 | **2.63×** |
| **main-memory accesses (reads + writes)** | **11,023,849,680** | **3,098,511,853** | **3.56×** |
| bytes moved (accesses × 64) | 705,526,379,520 | 198,304,758,592 | 3.56× |
| `L2_MemUpgrades` | not separated in these logs | | |
| `L2_MemRelClean` | not separated in these logs | | |
| cycles (`L2_Cycles`, uncore clock) | 885,140,998,271 | 606,430,476,198 | 1.46× (**+46.0%**) |
| instret | not available on this board (Amendment 1) | | |
| `SBC_Migrations` / `SBC_Parked` at end | 8,323,681 / 1,903 | 0 / 0 | |
| `secHits` | 996,051,875 | 0 | |

**The other two pairs** (headline only; full per-counter breakdown in the source doc §4.2):

| pair | L2 | sim-time | events | cycles ON/OFF | mem-access ON/OFF |
|---|---|---|---:|---:|---:|
| A | 256 KB | 0.001 s | 28,270 | +31.9% (1.32×) | 6.96× |
| **B** | **256 KB** | **0.1 s** | **35,128,553** | **+46.0% (1.46×)** | **3.56×** |
| C | 64 KB | 0.02 s | 9,347,248 | +51.2% (1.51×) | 2.45× |

**Read before quoting:**
- **One run per pair — no reboot-separated repeat yet, so G12 stays open.** Pair A (0.001 s) is too
  short to trust alone (mostly network setup); pair C is a different bitstream (64 KB), not a repeat of A/B.
- Reads and writes **both** rise, reads more — reported separately, never combined into one figure.
- Quote **memory accesses and cycles, not hit rate**: hit rate still counts L1 write-backs as hits
  (task 005), and `L2_Accesses` is higher in every ON half despite equal work — it is **not** a work
  measure (see G3). The event count is the "same work" anchor.
- `L2_Cycles` is the L2/uncore clock; whether it equals CPU cycles is unconfirmed (§13.4). The ON/OFF
  ratio holds either way.
- These bitstreams read the SBC event counters as **32-bit**; nothing wrapped (largest `secHits` 996 M
  in pair B), but a ≥1.0 s run would — which is exactly why §13.3 widens them to 64-bit.

## G5's premise in TASK.md is wrong — read this before re-running it

TASK.md §7 says *"`L2_MemReads` equals the count of `OUTER-A` debug lines"*. Compared naively on the
`op=` field that gives **115,234 against 142,258** and looks like a bad miscount. It is not.

`MSHR.scala:757` prints `op=${request.opcode}` — the **inner** request's opcode, i.e. what the CPU
asked for (Get=4, AcquireBlock=6, PutPartialData=1). The *outer* A opcode is never `Get`:
`SourceA.scala:50` emits only `Mux(block, AcquireBlock, AcquirePerm)`. The discriminator in the
printf is `perm=` — `perm=0` is AcquireBlock, `perm=1` is AcquirePerm.

Compared on `perm=`, the counters line up:

| | counter | `perm=` tally | gap |
|---|---:|---:|---:|
| SBC `memReads` | 142,258 | 142,315 | 57 |
| SBC `memUpgrades` | 0 | 0 | **0** |
| NoSbc `memReads` | 133,301 | 133,308 | 7 |
| NoSbc `memUpgrades` | 0 | 0 | **0** |

The residual gap is a **snapshot-ordering artifact, not miscounting**, on three grounds: it is always
in the direction "log ahead of counter" (double counting would go the other way); it is not
proportional to run length (57 on a 142 k run vs 7 on a 133 k run — 8× different as a ratio); and
`sbc_summary()` reads the MMIO registers *before* printing, so its own `printf` and the program
teardown still generate outer-A traffic that the log records and the snapshot cannot. The SBC build
has the larger tail because it misses more on that same teardown code.

**The beat assert was also weakened deliberately.** §B.2 asks for `memWriteBeats === memWrites *
beatsPerBlock`. That equality only holds at quiescence — a release's beats stream out over cycles
*after* `io.req.fire`, so asserting it continuously would false-fire constantly. Implemented as the
upper bound `memWriteBeats <= memWrites * beatsPerBlock`, which is always true and still catches the
failure it exists for ("a future change to SourceC sends more beats than the requests authorised").

`L2_MemRelClean` **does fire** — 108,057 (SBC) / 106,734 (NoSbc). Keep the register.

## Board automation — `run_board_session.exp` now runs the §12.2 A/B

Lives in the parent chipyard repo (`chipyard/scripts/ssbc_scripts/`), untracked there, so it is
recorded here rather than in a commit. **Default behaviour changed**: one SBC bitstream is
programmed once and the whole `WORKLOADS` set is measured twice on it — phase 1 `--migrate=off`
(plain-L2 half), phase 2 `--reset-all --migrate=on` (SBC half). `-sbc` / `-nosbc` still give the old
single-phase runs; `-nosbc` remains the synthesis control for area/Fmax.

Three things it now does that it could not before:

1. **Drives the switch and confirms it.** `set_switch` reads back sbc_read's own `migrate:` line
   rather than trusting the write, and refuses to start a phase it cannot confirm.
2. **Fixed an identity check that task 006 had silently broken.** It used to tell the bitstreams
   apart with "migrations > 0 or parked > 0". With the switch defaulting OFF, a freshly booted SBC
   bitstream reports `migrations=0 parked=0` — indistinguishable from NoSBC, so every A/B would have
   been mislabelled. It now keys on `SBC_Status` bit0 via the `migrate: n/a` vs `OFF`/`ON` line,
   which reports what was *built* regardless of switch position. Falls back to the old heuristic
   (loudly) if it meets a pre-006 `sbc_read`.
3. **Guards the ordering.** OFF half first, because it parks nothing and that is the only reason
   `--reset-all` is legal before the ON half. If the halves are ever run the other way, `sbc_read`
   refuses the reset and the script bails with the reason rather than producing a corrupted run.

It also flags a zero-migration SBC half, since that means both halves measured the same thing and
the A/B is empty — the failure mode most likely to be written up as a result by mistake.

**Not verified on hardware** — no board access from this session. What was checked: Tcl parse,
all four proc bodies compile, and the arg parser exits before touching Vivado. `run_sbc_window.sh`
was checked for interference and only ever calls `--zero`, so a phase's switch position survives
the workload. The script also carries the G3 caveat in its header so nobody reads the OFF half as
equivalent to a NoSbc build.

## First board A/B on one bitstream — 2026-09-11, `520.omnetpp_r` ref

Log: `chipyard/scripts/logs/board_session_20260910-234644.log`. Phase 1 ran 00:43:22–00:54:27,
phase 2 00:54:28–01:05:33 — both 60 s warm-up + 600 s window, same binary, no reboot between them.

| | phase 1 `migrate OFF` | phase 2 `migrate ON` |
|---|---:|---:|
| `L2_Accesses` | 853,446,927 | 682,103,636 |
| `L2_Hits` | 769,730,166 | 395,309,230 |
| primary hit rate | **90.19%** | **57.95%** |
| total incl. secondary | 90.19% | 62.05% |
| `migrations` | 0 | 98,663 |
| `parked` at end | 0 | 1,845 |
| `aborted` | 0 | 339,757 |
| `secHits` | 0 | 27,930,278 |
| `dispDrop` / `dispRelease` | 0 / 0 | 98,241 / 15 |

**Mechanically the session is clean and the automation is proven on hardware:** the new `sbc_read`
(466,800 bytes, matches the built binary) transferred and ran; `migrate:` reported `OFF` for phase 1
and `ON` for phase 2; `--reset-all` was accepted without refusing (correct — `parked` was 0 at that
moment, which is the whole reason the OFF half runs first); migrations occurred only in phase 2; the
switch was returned to `OFF` at the end. This reproduces the previously recorded FPGA result
(90.24% vs 66.10%) closely enough to call it confirmed.

`--reset-all` left `L2_Accesses` untouched (855,485,618 immediately after it). **That is correct by
design** — `SBC_Reset` never touches the L2 totals, only `SBC_StatsReset` does, and
`run_sbc_window.sh` issues `--zero` at the top of each window, which is what actually brackets them.

### The finding that changes Part B's brief

**The two phases did not do the same amount of work.** Same 600 s, but 853 M vs 682 M L2 accesses —
phase 2 got through ~20% less of the program because it was slower. A fixed-*time* window measures
"how much did the machine manage", not "what did the same job cost".

That is fatal for the headline metric exactly as TASK.md §B.5 predicted: *"Fewer memory accesses over
a fixed 600-second wall-clock window can simply mean the machine did less work."* Built today and
run this way, `L2_MemReads + L2_MemWrites` would show SBC moving **less** DRAM traffic while actually
being much slower — the wrong sign, for the same reason the hit-rate metric was abandoned.

It is a protocol problem, not an RTL one. Part B is still worth building exactly as specified; what
has to change alongside it is the window: **fixed work, not fixed time.** And `L2_Cycles` (Part E)
stops being optional — with no `rdinstret` and no `perf_event_open` on this board, it is the only
work/time normaliser available, so "cycles to finish a fixed job" becomes the performance number and
memory traffic becomes its explanation.

### Why the hit rate collapsed, in one line

`secHits` (27.9 M) is 4.1% of phase-2 accesses, while primary hits fell 32 points (~220 M). Parked
lines are being *reused heavily* — 281 hits per park, far above the ~1.0 break-even — but the
disruption to ordinary caching costs an order of magnitude more than the reuse returns. The
mechanism works; the economics do not, at this geometry and workload.

## Switch verification — `sbc_migrate_switch_test`, all four sub-tests PASS

Run on `VerilatorRocket8KL116KL2Config` via `make run-binary` (so `+dramsim`, per CLAUDE.md).

| # | What it proves | Result |
|---|---|---|
| T1 | the register returns what was written, both ways (wrote 1/0/1/0) | ✅ PASS — this is the property `sbc_read --migrate=` aborts on, so it is now checked rather than assumed |
| T2 | switch OFF from reset ⇒ a hammer that reliably migrates commits **nothing** | ✅ PASS — `migrations=0 parked=0` |
| T3 | the *same* hammer with the switch ON does migrate | ✅ PASS — `migrations=2 parked=2 secHits=184`. Rules out "T2 passed because the workload was a dud" |
| T4 | flip OFF while lines are parked ⇒ no new migrations, parked lines still worked on | ✅ PASS — `migrations` frozen 3→3 over 1500 iterations, `secHits` 211→399 |

Two incidental confirmations worth keeping:

- **The one-migration-per-bank token holds.** Between T3's dump and T4's baseline the count moved
  2 → 3: exactly one in-flight migration drained after the switch flipped. Bounded at one, as the
  token promises, which is why T4 can demand the count be *exactly* stable rather than approximately.
- `dispRelease` / `dispDrop` stayed 0 throughout — parked lines were **served** but none **retired**
  in a run this short. T4 therefore tests the *sum* of serve-and-retire events, not each separately.
  It also means the displaced-reclaim path is still only ever exercised under forcing, unchanged from
  what CLAUDE.md already records.

## G3 delta — "SBC built but switched off" vs "SBC not built"

The number that says whether the switch really means "plain L2". **This is the finding of the task
so far, and it is why work stopped here rather than continuing into Part B.**

Both columns are freshly built **from the same source tree in one session** (one `make clean`, both
configs elaborated back to back), same program, same `+dramsim` flow, so this is apples-to-apples.
The only source difference between the two binaries is the single `sbc_wr(SBC_MIGRATEENABLE, 1)`
store that `-DSBC_MIGRATE_OFF` compiles out — one MMIO store, to a register the NoSbc build also
carries, and it never reaches the L2 data path.

| counter | switch OFF (SBC cfg) | NoSbc build | delta | delta % |
|---|---:|---:|---:|---:|
| `L2_Accesses` | 349,025 | 368,219 | −19,194 | **−5.21%** |
| `L2_Hits` | 216,743 | 233,963 | −17,220 | **−7.36%** |
| derived misses (`acc − hits`) | 132,282 | 134,256 | −1,974 | −1.47% |
| outer `AcquireBlock` (`op=6`) | 129,271 | 131,344 | −2,073 | −1.58% |
| outer `Get` (`op=4`) | 2,877 | 2,785 | +92 | +3.30% |
| outer `op=1` | 149 | 149 | **0** | **0.00%** |
| `DIR-WRITE` printf tally | 436,326 | 456,652 | −20,326 | −4.45% |
| `SBC_Migrations` | 0 | 0 | 0 | ✅ |
| `SBC_Attempted` / `SBC_Aborted` | 0 / 0 | 0 / 0 | 0 | ✅ |

`SBC_Parked` is not printed by the stress test's `sbc_summary()` (it prints migrations / attempted /
aborted / secHits / secMiss / secPerm / accesses / hits). `parked = 0` follows by construction:
nothing can be parked without a commit, and `migrations = 0`.

### What this means

**The switch itself works.** With `SBC_MigrateEnable = 0` from reset, zero migrations start, zero
lines park, the secondary-search counters stay at zero, and the test is still 7/7 data-correct with
0 asserts. Nothing about the gate is broken.

**But "switch off ⇒ bit-identical to a NoSbc build" is false**, so TASK.md §12.2's argument — which
is a static read of the RTL, and says so — does not survive contact with a measurement. The gate was
written to catch exactly this.

**The shape of the delta is the informative part.** Misses and outer block-fetches differ by ~1.5%,
while counted accesses and hits differ by 5–7%. Same workload, near-identical data movement, but
thousands more *counted hit-lookups* in the NoSbc build. Two readings, not mutually exclusive:

1. **Most of the 5–7% is a counting artifact, not behaviour.** `L2_Accesses` counts
   `io.result.valid && !internalRead` — every directory lookup, including MSHR reload re-reads. How
   many of those a given request stream generates depends on request merging and reload timing
   (`mshr_uses_directory = will_reload && scheduleTag =/= …`), which is timing-sensitive. This is the
   same class of definitional slipperiness that motivated moving the headline metric off hit rate in
   §0 point 2 — and it argues the move was right.
2. **The residual ~1.5% on real traffic is most likely LFSR replacement divergence.** Victim choice
   is `random.LFSR(...)` sampled at each read. Any structural difference between the two elaborations
   — and there are many, all inert but present: the `busyWays`/`freeWays` mask, the
   `preferInvalid`/`preferEvictable`/`secondarySearch`/`internalRead` fields carried through the
   directory read pipe, the extra dread arbitration terms — can shift when that sample lands by a
   cycle. With random replacement, a one-cycle shift early in the run cascades into a completely
   different (but equally legal) eviction sequence forever after. **This is a hypothesis, not a
   proven root cause** — confirming it needs a cycle-level waveform diff, which was not run.

What was **ruled out** by tracing the RTL (all confirmed inert when the switch is off):
`hotOK` → `migrateResp.migrate` has exactly one consumer, `adviceMigrate` (`Scheduler.scala:655`);
`adviceMigrate` is the sole driver of every MSHR's `migAdvice` (`:355`) and of the alloc-side
`preferEvictable` term (`:449`); the dread lane needs `doSearch || doDread` (`MSHR.scala:580`), and
both need a pairing or a live migration, neither of which can exist if nothing ever commits;
`nonDisplacedOH` is all-ones and `displacedOH` all-zeros when no line is ever displaced, so
`victimWayOH` reduces to `lfsrVictimOH & freeWays` in both builds. None of these explains the delta —
which is what points at timing rather than logic.

One thing found while tracing that the thinker should look at independently:
`MSHR.scala:460`, `io.status.bits.lockValid := lockBorrowed || (meta_valid && !meta.hit && meta.state
=/= INVALID)`. The second term locks *any* MSHR's chosen victim on *any* ordinary miss, with no SBC
condition on it, and it feeds `busyWays` → `freeWays` → victim selection unconditionally. It is
present in both builds so it does not by itself explain a *divergence*, but "every miss narrows the
victim pool" is a bigger standing behaviour than the Stage-2c comment above it describes.

## Anything that contradicted the work order

- **`migration_stress_test.c` was not in TASK.md's touched-file list, but had to be edited.** With
  `SBC_MigrateEnable` defaulting OFF, the stress test (which relied solely on the compile-time
  `sbcAutoMigrate` flag, no MMIO write) would migrate 0 lines and G2 could never pass. Added
  `sbc_wr(SBC_MIGRATEENABLE, 1)` at the top of `main()`, guarded by `#ifndef SBC_MIGRATE_OFF` so a
  `-DSBC_MIGRATE_OFF` build gives the G3 control run without a second source file. This mirrors the
  codebase's existing compile-time-knob convention (`sbcForceDstSet`, `sbcAutoMigrate`, etc).
- **G0's "bit-exact with the pre-006 NoSbc build; zero new hardware" cannot hold literally once Part A
  lands**, because `Control.scala` is not gated by `enableSetBalancing` — it already carries the SBC
  registers unconditionally (same as `L2_Accesses`/`L2_Hits` from task 004). `SBC_MigrateEnable` adds
  one more always-present register + comparator to every build, SBC or not, same as the existing
  `sbcSetSel`/`sbcBalanceSetField` etc. Reading G0 as "no *additional* hardware beyond Part A's switch
  when `enablePerfCounters=false`" (i.e., scoped to Part B's own contribution, not Part A's, which is
  a separate and already-accepted-by-precedent addition).

## Verdict

**Done — and the answer is negative for SBC.** Parts A, B, D and E landed; C is partial (child timing
never built, now moot — §13.4). Both the measurement method and the result resolved on the board.

- **The one-bitstream A/B method is accepted.** The G3 worry — that a switch-off build would not match
  a separate NoSbc build — was about *two* bitstreams. The board method uses **one** bitstream and
  flips migration on/off within a single boot, so that offset never enters. Validated on the board:
  **"SBC built but switched off" matches a separately-built NoSbc bitstream to within noise**
  (**90.19% vs 90.24%** hit rate, fixed-time windows) — so switching migration off behaves like not
  building SBC at all.
- **The fixed-work A/B is done** (three pairs, 2026-09-11 → 09-13; see Numbers). Both halves of each
  pair reached the same omnetpp event count, so they did the same work.
- **SBC is slower and moves more main-memory traffic, in every pair.** Pair B (256 KB, the real
  geometry): **+46.0% cycles, 3.56× memory accesses** (reads 3.87×, writes 2.63×). Pairs A and C agree
  in sign and magnitude (+31.9% / +51.2% cycles; 6.96× / 2.45× accesses).

**Caveat that keeps this from being final:** one run per pair, no reboot-separated repeat yet, so
**G12 is open** (workplan Step 1.4). The sign is not in doubt across three pairs and two geometries,
but a published number needs the repeat. Quote memory accesses and cycles, not hit rate (task 005).

Nothing in this task's RTL is implicated in the *result* — the switch, the counters and `L2_Cycles` are
observation-only. The 64-bit counter widening (§13.3) landed in **`06fcca8`**, so a ≥1 s run cannot wrap
`secHits`. The Amendment 6 close-out (false-alarm beat check dropped, stale comments and dead
`sbc_read` code removed) landed in a follow-up commit — **no behaviour change**.

**Close-out verification (2026-09-14, `sw/verilator_logs/*_006-closeout`):** `migration_stress_test`
PASS on both `VerilatorRocket8KL116KL2Config` (22.40 M cyc) and `…NoSbcConfig` (23.11 M cyc), 0 asserts;
every `[SBC-COUNTERS]`/`[SBC-MEM]` value **byte-for-byte identical** to the `006-partB-counters`
baseline on both configs (confirming the cleanup changed nothing). `sbc_migrate_switch_test` 4/4, with
T4 now reporting **3 lines parked at the flip** (the loophole fix — T4 was previously a free pass when
nothing was parked). The 64-bit run itself left no logs; these close-out logs stand in.

**Bitstream:** the board still runs the 32-bit-counter image. One rebuild is owed **after task 005**
(it changes the register map again), not for this change alone — short board runs this week are safe on
the current image (§15.4).

### Reproduction

```bash
# both configs, clean rebuild, switch-ON binary  (G2 + the NoSbc column of G3)
SBC_CONFIGS="VerilatorRocket8KL116KL2Config VerilatorRocket8KL116KL2NoSbcConfig" \
SBC_TESTS="migration_stress_test" SBC_LABEL="006-partA-G2-G3base" SBC_CLEAN=1 \
  sw/scripts/run_sbc.sh

# switch-OFF binary, reusing the simulator just built  (the switch-OFF column of G3)
riscv64-unknown-elf-gcc -mcmodel=medany -static -std=gnu99 -O2 -fno-common -fno-builtin \
  -fno-builtin-printf -DSBC_MIGRATE_OFF -I $COMMON -I $ENV \
  -o sw/build/migration_stress_test_off.riscv sw/migration_stress_test.c \
  $COMMON/crt.S $COMMON/syscalls.c -nostdlib -nostartfiles -lm -lgcc -T $COMMON/test.ld
make -C sims/verilator run-binary CONFIG=VerilatorRocket8KL116KL2Config \
  BINARY=.../sw/build/migration_stress_test_off.riscv
```

Logs: `sw/verilator_logs/migration_stress_test_*_006-partA-G2-G3base/`, and the switch-OFF run at
`sims/verilator/output/chipyard.harness.TestHarness.VerilatorRocket8KL116KL2Config/migration_stress_test_off.{log,out}`.
