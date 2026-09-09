# Weekly task sheet — week of 2026-09-08

**One-line goal of the week: produce the first *measured benefit* number for SBC** — a real workload
with reuse, run on both bitstreams, hit-rate / access-time differential. Everything else is in support
of that, or is cheap hygiene we do while the runs bake.

Owner tags: **[E]** = evaluation/analysis (me + you) · **[C]** = coder RTL task (spec → hand off) ·
**[D]** = decision only you can make.

---

## Where we are today

The mechanism is **built, correct, and characterised for cost — but its benefit is still unmeasured.**

| Axis | State |
|---|---|
| **Correctness** | ✅ Serve-in-place 7/7, 0 asserts, both sim shadow monitors clean. Long-open corruption bug closed (2026-08-31). Real-workload clean on `tmp.c` (checksum = oracle). |
| **FPGA / Linux** | ✅ Both bitstreams exist and boot the same image: SBC-on (`…SBCResetEnabled`) + SBC-off twin (`…NoSbc`), proven comparable (netlist diff clean). `sbc_read` counter reader working. |
| **Observability** | ✅ `L2_Accesses`/`L2_Hits`, `secHits`/`secMiss`/`secPerm`/…, and `SBC_StatsReset` (0x3B8, counter-only reset) — **verified in Linux on the board this week**, committed + tagged `sbc-statsreset-verified-linux-fpga`. |
| **Cost** | ✅ Area +18.4% LUT whole-design (L2 +157%), no BRAM; timing +0.473 ns / ~2.5% Fmax at 50 MHz. Fine at this clock. |
| **Benefit** | 🔴 **NOT MEASURED.** Every doc converges here. No performance number exists on any workload with real reuse. |

## What we have done (recent arc)

- Phase 0/1/2 (observe → migration primitives → migrate-on-eviction) — signed off.
- **Phase 3R serve-in-place** — the core mechanism: a moved line answers from where it sits, no
  repatriation copy, correct probe-back + dirty-writeback. 2,873 lines served in sim.
- Observability + FPGA bring-up: on/off bitstreams, Linux boot, `sbc_read`, devmem map,
  [fpga-linux-run.md](fpga-linux-run.md).
- Area/timing cost characterised on the real 256 KB / 16-way / 256-set geometry
  ([daily-summary/2026-09-05.md](daily-summary/2026-09-05.md)).

## What is left (the real backlog)

1. **Benefit measurement** — decisive, gates the thesis claim. *(This week's goal.)*
2. **Dirty-source migration** — only clean lines migrate today; most real victims are dirty. Biggest
   lever on how often SBC can fire. Address-recovery machinery already built.
3. **A workload with genuine reuse** — the stress test streams 32 lines/8 slots = no reuse, so
   `secHit` rate is flat *by construction of the test*, not by the design.
4. **Two unexercised paths** — shared-copy-plus-write (`secWrite`/`secPerm`) built but never taken;
   not proven working.

## What to improve (method + hygiene, mostly cheap)

- **Baseline is contaminated:** `setCopyUnit` is instantiated unguarded → 240 LUT / 529 FF of dead SBC
  logic in the SBC-off build. One-line guard; makes every cost delta honest.
- **`armed` is a silent trap:** dead under `sbcAutoMigrate=true`, so `SBC_BalanceSet` is a no-op on the
  shipping bitstream. Anyone trying to drive an experiment by arming sets from SW gets nothing. Gate
  the MMIO or document it loudly.
- **SBU now owns the critical path** (60 logic levels) and scales worse-than-linearly with set count.
  Not urgent at 50 MHz, but it is the scaling story for the thesis — keep the DSS-fold / LUTRAM levers
  on the list as *scaling insurance*, not present-day fixes.
- **Threshold hygiene:** perf runs must use the auto-derived `T_hi=15 / T_lo=8` (the paper's rule),
  never the `4/2` coverage values.

---

## This week's tasks

### P0 — the benefit number

| # | Task | Owner | Effort | Done when |
|---|---|---|---|---|
| T1 | **Choose the reuse workload.** The stress test has no reuse. Options: (a) purpose-built reuse microbench with controlled reuse distance — fastest first number; (b) PARSEC (`br-base-parsec` already exists); (c) SPEC2017 subset — the "real" claim, slowest. Recommend (a) this week to get a number, (b)/(c) queued. | **[D]** | 15 min | you pick a, b, or c |
| T2 | **A/B run on both bitstreams.** For the chosen workload: `sbc_read --zero -- <workload>` on `…SBCResetEnabled` and on `…NoSbc`. Capture `L2_Accesses`/`L2_Hits` (primary hit rate), `secHits`/`secMiss` (serve rate), `migrations`, and `p = secHits / L2_Accesses`. | **[E]** | 1 day | one table: SBC-on vs SBC-off, hit rate + p + cycles |
| T3 | **Interpret.** Is the total hit rate up? Is `p` non-trivial? If flat, is it "no reuse in workload" or "mechanism not firing"? Write it up plainly (a negative result stated clearly is still a result). | **[E]** | 0.5 day | short verdict note in a daily-summary |

### P1 — the biggest lever if the number is flat

| # | Task | Owner | Effort | Done when |
|---|---|---|---|---|
| T4 | **Spec dirty-source migration** (003 Stage 2 — the `p` unlock). Displaced victims Release at `lineHome` instead of silent-drop; relax `displaced ⇒ clean` to `displaced ⇒ client-free`; drop `!dirty` from the migrate gate; guard the AT against `io.clear` while lines are parked. Address recovery via `AT[row].assocSet` already exists. | **[E]** → **[C]** | 1 day spec | TASK.md handed to coder, gates defined (checksum-clean + `p` up) |

### P2 — cheap hygiene (do while runs bake)

| # | Task | Owner | Effort | Done when |
|---|---|---|---|---|
| T5 | Guard `setCopyUnit` behind `enableSetBalancing` (`Scheduler.scala:86`). | **[C]** | 1 line | SBC-off netlist loses `SetCopyUnit.sv` |
| T6 | Decide `armed` / `SBC_BalanceSet`: gate the MMIO write, or add a loud "no-op under sbcAutoMigrate" note in `sbc_mmio.h` + devmem map. | **[D]** → **[C]** | 1 line | no silent no-op left |
| T7 | Directed test for the shared-copy-plus-write path (`secWrite`/`secPerm`) — currently built but never exercised. | **[E]** | 0.5 day | a test that takes the path at least once, 0 asserts |

### Housekeeping

| # | Task | Owner |
|---|---|---|
| T8 | Commit the parent-repo changes (the `…SBCResetEnabled` config + `bistream_gen_vcu118.sh` pointer) if you want them tracked — still uncommitted in the chipyard tree. | **[D]** |
| T9 | Fix stale memory `sbc-vs-paper-gaps` — the second-search bit **is** implemented (`fb07d10`, §2.3). | **[E]** (done) |

---

## The decision that shapes the week

**T1 is the fork.** Pick the workload and the rest follows:

- If you want a number **fastest** → purpose-built reuse microbench (a). We can write it in an hour and
  run it today.
- If you want a number that **survives a reviewer** → PARSEC (b) or SPEC2017 (c), which take longer to
  set up and run but are defensible in the thesis.

My recommendation: **(a) this week for a first signal**, then queue **(b)** so a defensible number is
running by next week. A flat (a) result immediately points at T4 (dirty-source) as the unlock.
