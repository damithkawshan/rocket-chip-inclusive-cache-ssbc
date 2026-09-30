# Coder task 013 — make the saturation counter follow the paper

**Opened 2026-09-30.** Repo `chipyard/generators/rocket-chip-inclusive-cache`. Start from branch
`sbc-009-redo` at the commit that adds this file (RTL = `c6e59d3`). Work on a new branch
`sbc-013-sat-counter`.

Authorised by the user 2026-09-30: *"create new coder task to fix paper mismatches"*. Evidence: the paper
PDF `ai-documents/background/178-rolan-1-2.pdf` (its text layer is broken — read pages 3–5 as images:
Fig. 2 is on page 3, §3.1–3.3 on page 4) and README tracker rows **M17** and **M18**.

**This file is written for a coding model.** Every edit, command and check is spelled out. Line numbers
are as of `c6e59d3` and will drift — always find the spot by the quoted code, not by the number. If the
code does not look like what this file quotes, **stop and ask**.

---

## 0. Rules — read before anything else

1. **Plan first.** Read this whole file, then reply to the user with a plan of at most 10 lines plus any
   questions. Edit nothing until the user says go. Simple English, short answers.
2. **Four stop points.** Stop and wait for the user's go:
   (a) after your plan;
   (b) after the C0 sizing table — ask for go on C1 **and** on the control bitstream build (§12);
   (c) after C3 — ask for go on the candidate bitstream build;
   (d) before the board session (§13).
3. **One commit per stage** (C0, C1, C2, C3), in order. A stage is done only when its gate passed and its
   REPORT rows are filled. Commit `REPORT.md` together with the stage.
4. **A failing check is a finding.** Write it in `REPORT.md`, stop, ask. Never edit RTL or a test to make a
   check pass. Never fix anything else you notice — report it.
5. **Simulate only through `sw/scripts/run_013_gate.sh`** (it calls `run_sbc.sh`, which passes `+dramsim`).
   Never run the simulator binary directly: without `+dramsim` a shadow checker false-fires at cycle 27141.
6. **Git:** never `git checkout <file>`, `git restore`, `git reset --hard`, `git clean`, or `git stash drop`.
   Uncommitted configs live in the chipyard tree. Stage files by name — never `git add -A` or `git add .`.
7. **RTL comments: 1–3 lines.** Long reasoning goes in `REPORT.md`.
8. **No new MMIO register and no new MMIO counter.** Sim-only debug registers inside
   `if (params.micro.sbcDebug)` are allowed: the FPGA configs set `sbcDebug = false`, so they are not in
   the bitstream.
9. **Do not change:** BankedStore priorities; the destination fence, migration token, `s_wsafe`, or any
   `w.displaced` test; the thresholds (`T_hi`, `T_lo`); `PerfCounters.scala` or its `tap` input; any sim
   test program `sw/*.c`; the `WORKLOADS` list of the board script.
10. **Logs:** give the user every log path the moment a run starts. Never read a whole log — use
    `sbc_stats.txt`, `grep -c`, `tail`.
11. **FPGA builds and the board session run in ONE tmux session, `sbc013`.**
12. **Board numbers:** report `memReads` and `memWrites` separately, never summed. Cite a bitstream by its
    sha256, never by file name.
13. **Gap 5 is measured, not fixed.** Do not build a fix for it.

---

## 1. Goal

The saturation ("heat") counter is the input to every SBC decision: which set is hot (a source), which is
cold (a destination). Ours differs from Rolan et al. in four places (§2). Make it match the paper, prove
each change in simulation, then build two single-core 1 MB images — a **control** on today's RTL and the
fixed **candidate** — and A/B both on the board.

This is a **faithfulness** fix: it makes our SBC the paper's SBC. It does not promise a speed-up, and none
may be claimed from it.

---

## 2. The mismatches

| Gap | Paper | Ours today | Stage |
|---|---|---|---|
| **1** | §2: the counter "is modified each time the set is accessed" — miss +1, hit −1 | `Directory.scala:314` `io.tap.valid := ren2 && !internalRead`: **every** directory read except SBC's own moves it. Inner-C write-backs (`Release`/`ReleaseData` — always a hit under inclusion, so always −1) and X-channel flushes count too. Board, omnetpp, image `98aedafa…929e`, 2026-09-26: at least **21.4%** of counter events were not accesses | **C1** |
| **2** | §3.3: "the saturation counter is updated and if it has reached its maximum…" — update, **then** test | `SetBalanceUnit.scala:162` `hotOK` reads `sat` when the request **arrives**, 1–2 cycles before this access's own update lands. A set whose hits and misses alternate at the top (14↔15) displaces on every miss in the paper and on **none** in ours | **C2** |
| **3** | §3.1–3.2: displace only from a set whose counter is at its maximum | `Scheduler.scala:701-702` asks the SBU about `request.bits.set` — the **incoming** sink request. An MSHR that pops a **queued** request latches that answer (`MSHR.scala:1469`), so the popped request's own set is never asked (tracker M17). The destination side is keyed correctly, so the damage is on the source side: a set that is not hot can start a pairing | **C2** |
| **4** | Fig. 2 (page 3): after a secondary hit in set 2, set 2's counter goes **1 → 0**; the displacement into set 2 left it at 1. §3.3: "Here we can get a secondary hit or a definitive miss. In both cases the set saturation counter will be updated" | The second search is an `internalRead`, so it never reaches the tap: the partner's counter never moves on it | **C3** |
| **5** | Every access moves the counter | A queued demand request for the **same line** (a "repeat") reuses the MSHR's state with no directory read, so it is never counted | **C0 measures it. No fix in this task** |

**Decision for gap 4 (recorded 2026-09-30):** the partner gets **−1 on a secondary hit** (shown in Fig. 2)
**and +1 on a definitive miss**. The +1 half rests on the §3.3 sentence plus the §2 per-access rule; the
sentence does not name the set. If the user appends an amendment before C3, follow the amendment.

**Already matching — do not change:** counter range 0 to 2K−1, `T_hi = 2K−1`, `T_lo = K`
(`Configs.scala:128-130`); S gets +1 on a native miss even when the partner hits; installing a guest does
not move the partner (Fig. 2); the partner's level is ignored once paired (§3.2); paired sets never enter
the DSS (`SetBalanceUnit.scala:130`).

---

## 3. Words used here

| Word | Meaning |
|---|---|
| demand access | an inner-A request: `prio(0) && !control` (Acquire, Get, Put, atomics). Same test as `L2_AccessA` (`Scheduler.scala:433`) |
| primary lookup | a directory read of a request's own (home) set — on a fresh allocate, a bypass reload, or a queue pop with a new tag |
| second search | the directory read of the **partner** set looking for a parked line (`dread` lane, `secondarySearch = 1`) |
| destination probe | the directory read of the destination before a migration (`dread` lane, `preferInvalid = 1`). **Never counted** |
| repeat | a reload whose request has the same tag as the MSHR's current one. No directory read happens |
| tap | `Directory.io.tap`: raw, one event per non-internal read. Feeds `PerfCounters` (`L2_Accesses`, `L2_Hits`). **Stays exactly as it is** |
| satTap | new `Directory.io.satTap`: the counter's feed — demand lookups (C1), plus demand second searches (C3) |
| plan cycle | `migPlanCycle` in `MSHR.scala`: the cycle this MSHR's own primary-lookup result lands |
| resume cycle | `migResumeCycle`: the cycle a deferred second search answers |
| S, D | source set, destination (partner) set |
| `T_hi`, `T_lo` | 2K−1 and K. The sim config has K = 8, so `T_hi` = 15, `T_lo` = 8, counter max = 15 |

---

## 4. Before you start

```bash
L2=/home/damith/Research/repos/chipyard_performance_eval/chipyard/generators/rocket-chip-inclusive-cache
CY=/home/damith/Research/repos/chipyard_performance_eval/chipyard
cd $L2
git status --short        # expect only untracked (??) lines: PNGs, CSVs, scratch/, sw/vivado.*
                          # any " M" line: stop and ask
git log --oneline -1      # the commit that added this TASK.md
git switch -c sbc-013-sat-counter
wc -l CLAUDE.md           # about 714 lines (restored in 0c9265e after a318711 emptied it).
                          # If it is 0 again: stop and tell the user. Do not restore it yourself.
pgrep -af vivado          # a Vivado build may be running. Sims may run alongside it,
                          # but first: export SBC_THREADS=8 SBC_JOBS=8
```

Then read, in this order: §2 above; `Directory.scala` lines 40–110, 150–180, 300–320;
`SetBalanceUnit.scala` lines 1–10 and 40–170; `Scheduler.scala` lines 240–250, 325–460, 650–760;
`MSHR.scala` lines 170–180, 320–345, 660–680, 1085–1180, 1460–1475, 1500–1506, 1830–1842.

---

## 5. The gate

### 5.1 Script — create `sw/scripts/run_013_gate.sh` exactly as below (commit it with C0)

```bash
#!/usr/bin/env bash
# run_013_gate.sh <label> - task 013 gate: stress + switch tests on the SBC and NoSbc configs, PLRU.
# One simulator build per config (SBC_CLEAN=1 on the first config only). Label: base|c0|c1|c2|c3.
set -uo pipefail
L="${1:?usage: $0 base|c0|c1|c2|c3}"
HERE="$(cd "$(dirname "$0")" && pwd)"
RUN="$HERE/run_sbc.sh"
BOTH="sbc_migrate_switch_test migration_stress_test"
clean=1
for cfg in VerilatorRocket8KL116KL2Config VerilatorRocket8KL116KL2NoSbcConfig; do
  echo "######## 013-$L on $cfg (SBC_CLEAN=$clean) $(date)"
  SBC_CONFIGS="$cfg" SBC_TESTS="$BOTH" SBC_LABEL="013-$L" SBC_CLEAN=$clean \
    EXTRA_CFLAGS="-DL2_POLICY=1" "$RUN" || echo "######## run_sbc.sh FAILED ($cfg)"
  clean=0
done
echo "######## 013-$L gate finished $(date)"
```

Run it in the background and give the user the log path at once:

```bash
cd $L2 && chmod +x sw/scripts/run_013_gate.sh
nohup sw/scripts/run_013_gate.sh <label> > sw/verilator_logs/013-gate-<label>.log 2>&1 &
echo "log: $L2/sw/verilator_logs/013-gate-<label>.log"
```

It writes four run directories `sw/verilator_logs/<test>_<config>_013-<label>/`, each holding
`sbc_stats.txt` (the summary) and `sbc.log` (every `[SBC]` line). `<run dir>` below means one of them.

### 5.2 How to check a gate

**Pass** — all four run dirs, the same bar as task 012 V2:

```bash
L=$L2/sw/verilator_logs; X=<label>
for d in $L/*_013-$X; do echo "== $d"; sed -n 2,3p $d/sbc_stats.txt; grep -- "--->" $d/sbc_stats.txt; done
```

- every `result` line reads `PASS`, every `crash/asserts` line reads `NONE`;
- `migration_stress_test`: **8/8** on the SBC config, **7/7** on NoSbc; `sbc_migrate_switch_test`: all
  cases pass on both configs;
- SBC stress run: every line of the "cross-check" block reads `ok`, except `secMiss`, which may show a
  small `~ delta` (printfs after the final MMIO read); the copy/commit block reads
  `ok - every completed copy committed`.

**Identity** with an earlier label — everything except the path header and the new debug tags:

```bash
same() {  # same <test> <config> <labelA> <labelB>
  local f='SBC run summary\|SAT-SUM\|HOT-NOW\|D-TAP'
  diff <(grep -v "$f" $L/$1_$2_013-$3/sbc_stats.txt | sort) \
       <(grep -v "$f" $L/$1_$2_013-$4/sbc_stats.txt | sort) >/dev/null \
    && echo "IDENTICAL $1 $2" || echo "DIFFERENT $1 $2"
}
# e.g.  same migration_stress_test VerilatorRocket8KL116KL2NoSbcConfig base c1
```

The `result` line carries the cycle count, so `IDENTICAL` also proves the timing did not move.

**Last debug totals** of a run: `grep "SAT-SUM" <run dir>/sbc.log | tail -1`.

---

## 6. Stage B — baseline (no edits)

Run the gate with label `base` on the untouched branch.

⚠️ This is also the **first simulation after commit `a318711`**, which changed how
`migration_stress_test.c` decodes `SBC_AtAssoc` (its `case_teardown` reads that register). If `base`
does not pass, stop and report — it is a pre-existing problem, not this task's.

Record in REPORT: the four verdicts, and from the SBC stress run's `sbc_stats.txt` the MMIO block
(`migrations`, `attempted`, `aborted`, `secHits`, `secMiss`, `l2Accesses`, `l2Hits`).

---

## 7. C0 — sim-only totals that size the gaps (no behaviour change)

Only `Scheduler.scala` changes, plus the new gate script.

**7.1** Directly after this line (~line 460):

```scala
  directory.io.read.bits.secondarySearch := mshr_uses_directory_for_dread && schedule.dread.bits.secondarySearch
```

add:

```scala
  // 013: a demand access is what the paper's counter counts (cache-terminology.md "access").
  def isDemand(r: QueuedRequest): Bool = r.prio(0) && !r.control
  // The request a primary lookup is for: the popped one (list buffer) or the incoming one.
  val readIsDemand = Mux(mshr_uses_directory_for_lb, isDemand(requests.io.data), isDemand(request.bits))
```

**7.2** Inside the SBC block (`if (params.micro.enableSetBalancing) {`), directly after the existing
`if (params.micro.sbcDebug) { … ADVICE-MIG … MIG-CLAIM … }` block (~lines 743–750), add:

```scala
    // 013: sim-only totals that size the counter gaps. Printed every 16384 cycles; the last line is the total.
    if (params.micro.sbcDebug) {
      val dbgCyc       = RegInit(0.U(64.W))
      val lookDemand   = RegInit(0.U(64.W))  // primary lookups for a demand access
      val lookOther    = RegInit(0.U(64.W))  // primary lookups for inner C or X (gap 1)
      val repeatDemand = RegInit(0.U(64.W))  // demand accesses served with no lookup (gap 5)
      val popWrongKey  = RegInit(0.U(64.W))  // popped demand lookups whose advice came from another set (gap 3)
      dbgCyc := dbgCyc + 1.U
      val primaryRead    = directory.io.read.valid && !mshr_uses_directory_for_dread
      val reloadIsDemand = Mux(bypass, isDemand(request.bits), isDemand(requests.io.data))
      when (primaryRead &&  readIsDemand) { lookDemand := lookDemand + 1.U }
      when (primaryRead && !readIsDemand) { lookOther  := lookOther  + 1.U }
      when (will_reload && !mshr_uses_directory && reloadIsDemand) { repeatDemand := repeatDemand + 1.U }
      when (mshr_uses_directory_for_lb && isDemand(requests.io.data) &&
            !(request.valid && request.bits.set === scheduleHomeSet)) { popWrongKey := popWrongKey + 1.U }
      when (dbgCyc(13, 0) === 0.U) {
        printf(p"[SBC] SAT-SUM cyc=$dbgCyc lookDemand=$lookDemand lookOther=$lookOther " +
               p"repeatDemand=$repeatDemand popWrongKey=$popWrongKey\n")
      }
    }
```

Each event happens at most once per cycle (one directory read per cycle, one reload per cycle), so `+ 1`
is exact — no `PopCount` is needed. Later stages add fields to this same block and printf.

**7.3 Checks**

| # | check | pass |
|---|---|---|
| C0-a | gate `c0` passes (§5.2) | yes |
| C0-b | `same` for all four (test, config) pairs, `base` vs `c0` | four `IDENTICAL` |
| C0-c | last `SAT-SUM` line of both SBC-config runs | recorded |
| C0-d | `grep -c "\[SBC\] HOT " <run dir>/sbc.log`, both SBC-config runs. Each `HOT` line is a miss that took a set to `T_hi` — the moment the paper acts and ours (gap 2) does not | recorded |

**7.4 Sizing table** (REPORT, one row per SBC-config run):

| run | lookDemand | lookOther | gap 1 = lookOther ÷ (lookDemand + lookOther) | repeatDemand | gap 5 = repeatDemand ÷ (lookDemand + repeatDemand) | popWrongKey | gap 3 exposure = popWrongKey ÷ lookDemand | `HOT` lines |
|---|---|---|---|---|---|---|---|---|

Commit: `013 C0: sim-only totals that size the saturation-counter gaps`. Then **stop point (b)**.

---

## 8. C1 — only demand accesses move the counter (gap 1)

**8.1 `Directory.scala`**

a. After `class DirectoryTap … { … }` (~lines 45–50), add:

```scala
// SBC (013): the saturation-counter feed. `second` = a second-search result, keyed to the partner set.
class SatTap(params: InclusiveCacheParameters) extends DirectoryTap(params)
{
  val second = Bool()
}
```

b. In `class DirectoryRead`, after `val internalRead = Bool()` (~line 71), add:

```scala
  // SBC (013): an inner-A demand access. Only these move the saturation counter (paper section 2).
  val demand = Bool()
```

c. In the `io` bundle, after `val tap    = Valid(new DirectoryTap(params)) // SBC: result-aligned observation tap`
(~line 105), add:

```scala
    val satTap = Valid(new SatTap(params)) // SBC (013): the saturation counter's feed
```

d. After `val allowDisplacedVictim = params.dirReg(RegEnable(io.read.bits.allowDisplacedVictim, ren), ren1)`
(~line 174), add the pipeline register — same pattern as the lines above it:

```scala
  val demand = params.dirReg(RegEnable(io.read.bits.demand, ren), ren1)
```

e. After the four `io.tap.*` lines (~lines 314–317), add. **Do not touch the `io.tap` lines.**

```scala
  // SBC (013): only demand lookups move the saturation counter. io.tap above stays raw for PerfCounters.
  io.satTap.valid       := ren2 && demand && !internalRead
  io.satTap.bits.set    := set
  io.satTap.bits.hit    := io.result.bits.hit
  io.satTap.bits.way    := io.result.bits.way
  io.satTap.bits.second := secondarySearch
```

**8.2 `MSHR.scala`** — after
`io.schedule.bits.dread.bits.internalRead    := true.B     // neither is a demand access` (~line 675), add:

```scala
  // 013: a demand access's second search counts for the partner (C3); the destination probe never does.
  io.schedule.bits.dread.bits.demand          := doSearch && request.prio(0) && !request.control
```

**8.3 `Scheduler.scala`**

a. After the `readIsDemand` line from C0, add:

```scala
  directory.io.read.bits.demand := Mux(mshr_uses_directory_for_dread, schedule.dread.bits.demand, readIsDemand)
```

b. Change `sbu.io.dirTap     := directory.io.tap` (~line 660) to `sbu.io.dirTap     := directory.io.satTap`.
   **Leave `perf.io.tap        := directory.io.tap` (~line 810) exactly as it is.**

c. In the C0 debug block add, in the same style:

```scala
      val satFeed = RegInit(0.U(64.W))       // demand primary lookups the SBU saw (must equal lookDemand)
      when (directory.io.satTap.valid && !directory.io.satTap.bits.second) { satFeed := satFeed + 1.U }
```

   and append ` satFeed=$satFeed` to the `SAT-SUM` printf.

**8.4 `SetBalanceUnit.scala`** — change `val dirTap = Flipped(Valid(new DirectoryTap(params)))` (~line 46) to:

```scala
    val dirTap = Flipped(Valid(new SatTap(params)))   // 013: demand accesses only
```

If elaboration reports a field "not fully initialized", some `DirectoryRead` is built without `demand`. Find
it with `grep -n "read.bits\.\|dread.bits\." *.scala` and **report it before adding anything**.

**8.5 Checks**

| # | check | pass |
|---|---|---|
| C1-a | gate `c1` passes | yes |
| C1-b | `same` for both NoSbc pairs, `base` vs `c1` | `IDENTICAL` ×2 |
| C1-c | the SBU saw exactly the demand lookups — command below, both SBC-config runs | `bad 0` |
| C1-d | MMIO block of the SBC stress run, and the `HOT` line count, `c0` vs `c1` | recorded. They are expected to change; there is no pass/fail on the direction |

```bash
grep "SAT-SUM" <run dir>/sbc.log | awk '{for(i=1;i<=NF;i++){split($i,a,"=");v[a[1]]=a[2]}
  d=v["satFeed"]-v["lookDemand"]; if(d<-2||d>2){bad++; if(bad<=3)print "BAD",$0}}
  END{print "lines",NR,"bad",bad+0}'
```

(±2 covers reads in flight at the instant of the print: one counter counts at issue, the other at the
result.)

Commit: `013 C1: only demand accesses move the saturation counter (gap 1, M18)`.

---

## 9. C2 — test after the update, on the looked-up set (gaps 2 and 3)

**The idea.** Today "is this set hot?" is answered when a request **arrives**, keyed by the incoming
request, and latched by whichever MSHR allocates or reloads. After C2 it is answered in the cycle the
lookup's **result** lands, keyed by the **tap's own set**, from the pre-update level: a miss takes the set
to `T_hi` exactly when `cur >= T_hi − 1`. The MSHR that receives that result uses the answer in the same
cycle (the plan cycle) and keeps it for the resume path. One change closes both gaps.

⛔ **`hotNow` must stay a function of registers only**: `sat`, `at`, `armed`, the DSS outputs, the
Directory's pipeline registers (through `satTap`), `migrateEnable`, `anyMigrating`. Never add
`io.dirTap.bits.hit`, any `io.allocate.*` signal, or any MSHR wire to it. The MSHR's claim logic feeds
`allocReady`, so anything else can close a combinational loop (see the loop-freedom note above
`migFastTerms` in `MSHR.scala`). If elaboration reports a combinational loop, stop and report.

**9.1 `SetBalanceUnit.scala`**

a. In the `io` bundle: delete `val migrateQuery = Flipped(Valid(UInt(params.setBits.W)))` (~line 48) and the
   comment line above it, `// advisory queries (stubbed in Phase 0)`.
   In `migrateResp`, delete `val migrate = Bool()` and the comment sentence "`migrate` keeps its old
   meaning: source hot AND a destination exists." In the `destQuery` comment, replace "Split from
   migrateQuery because the two questions are about two different sets once pairings exist." with
   "Keyed to the MSHR deciding right now." Add:

```scala
    // SBC (013): hot-source advice for the set whose demand lookup lands this cycle (paper 3.3).
    val hotNow = Output(Bool())
```

b. Next to the line that defines `tHi` (`params.micro.migrationThreshold.U`, ~line 146), add:

```scala
  require(params.micro.migrationThreshold >= 1, "SBC: T_hi must be at least 1")
  val tHiMinus1 = (params.micro.migrationThreshold - 1).U
```

c. Replace this block (~lines 157–164):

```scala
  val qSet      = io.migrateQuery.bits
  val qEntry    = at(qSet)
  val dssPick   = dss.io.coldestSet
  // A fresh pairing may only consume a set that is genuinely cold AND in no pairing (strict 1:1).
  val dssOK     = dss.io.coldestValid && (dss.io.coldestLevel < tLo) && !at(dssPick).valid
  val hotOK     = io.migrateEnable && (params.micro.sbcAutoMigrate.B || armed(qSet)) && (sat(qSet) >= tHi)
  io.migrateResp.migrate := hotOK && !(qEntry.valid && qEntry.sd) &&
                            Mux(qEntry.valid && !qEntry.sd, true.B, dssOK)
```

with:

```scala
  val dssPick   = dss.io.coldestSet
  // A fresh pairing may only consume a set that is genuinely cold AND in no pairing (strict 1:1).
  val dssOK     = dss.io.coldestValid && (dss.io.coldestLevel < tLo) && !at(dssPick).valid
  // SBC (013): paper 3.3, update then test, on the looked-up set. `cur` is this access's pre-update
  // level, so a miss reaches T_hi exactly when cur >= T_hi-1. The MSHR still requires the miss.
  val tEntry    = at(tapSet)
  val hotSrc    = io.migrateEnable && (params.micro.sbcAutoMigrate.B || armed(tapSet)) && (cur >= tHiMinus1)
  io.hotNow    := io.dirTap.valid && !io.dirTap.bits.second && hotSrc &&
                  !(tEntry.valid && tEntry.sd) && Mux(tEntry.valid && !tEntry.sd, true.B, dssOK)
```

d. In the comment block just above it ("SBC Phase 2/3: two questions, two keys", ~lines 151–156), replace
   the line `// Advice ("is the ALLOCATING set a hot source that could spill somewhere") is latched at allocate.`
   with `// Advice ("is the set whose demand lookup lands NOW a hot source") is answered at that result (013).`
   Keep the destination lines.

e. Header comment, line 8
   (` * Phase 0: pure observation. Migration is OFF (migrateResp.migrate == false), the AT is inert.`):
   replace with ` * Migration advice (hotNow) is answered on each demand lookup's own set, after its update (013).`

f. In the `if (params.micro.sbcDebug)` block, add:

```scala
    when (io.hotNow) { printf(p"[SBC] HOT-NOW set=${tapSet} sat=${cur}\n") }
```

**9.2 `Scheduler.scala`**

a. Delete these three lines (~700–702):

```scala
    val isDemandA = request.bits.prio(0) && !request.bits.control
    sbu.io.migrateQuery.valid := request.valid
    sbu.io.migrateQuery.bits  := request.bits.set
```

b. Replace the advice line and its three-line comment (~lines 719–722):

```scala
    // SBC Phase 2: source-side advice only — is this a demand miss on a hot set, with no migration
    // already in flight. Latched by the allocating MSHR; staleness here is harmless (it only means an
    // MSHR may ask for a destination and be told no).
    adviceMigrate := isDemandA && sbu.io.migrateResp.migrate && !anyMigrating
```

with:

```scala
    // SBC (013): advice for the set whose demand lookup lands now, used by the MSHR taking that result
    // in the same cycle. Keyed by the tap, never by the incoming request (M17).
    adviceMigrate := sbu.io.hotNow && !anyMigrating
    // 013: so the MSHR taking a demand lookup's result must own the looked-up set.
    assert (!(directory.io.satTap.valid && !directory.io.satTap.bits.second && directoryFanout.orR) ||
            Mux1H(directoryFanout, mshrs.map(_.io.status.bits.homeSet)) === directory.io.satTap.bits.set,
            "SBC: a demand lookup result went to an MSHR of another set (advice mis-keyed)")
```

c. The comment block starting `// SBC Phase 2: migrate advice for the demand-allocating set.` (~lines
   693–699, seven lines ending `// window any more.`): replace it with the single line
   `// SBC Phase 2.5b: the advice is source-side only; every destination condition is checked live, below.`

d. Two comment words: in the `sbcForceDstSet` comment (~line 715) replace `` `migrateResp.migrate` `` with
   `` `hotNow` ``. In the comment above `accessA` (~line 430) replace
   `(unlike isDemandA below, which is inside it)` with `(unlike the SBC advice below, which is inside it)`.

e. The `ADVICE-MIG` printf (~lines 744–746): new condition and set field. Keep the tag name — `sbc_stats.py`
   counts it.

```scala
      when (adviceMigrate) {
        printf(p"[SBC][SCHED] ADVICE-MIG srcSet=${directory.io.satTap.bits.set} offerValid=${dstOfferValid} offerSet=${dstOfferSet}\n")
      }
```

**9.3 `MSHR.scala`**

a. The three comment lines above `val migAdvice = Input(Bool())` (~lines 174–176) become:

```scala
    // SBC (013): hot-source advice for the set whose directory result lands THIS cycle (paper 3.3:
    // update, then test). Used in that cycle; latched for the search-resume path.
```

b. The comment above `val migAdviceValidReg = RegInit(false.B)` (~line 328) becomes
   `// SBC (013): the plan-cycle advice, kept for the search-resume decision.`

c. `def migFastTerms(m: DirectoryResult): (Bool, Bool) = {` becomes
   `def migFastTerms(m: DirectoryResult, advice: Bool): (Bool, Bool) = {`, and inside it replace
   `migAdviceValidReg` with `advice`.

d. After `val migResumeCycle = io.directory.valid && searching && !w_ssearch && secDefer` (~line 1119), add:

```scala
  // 013: the advice describes our set only in our own lookup's result cycle. Register-derived: loop-free.
  val planAdvice = migPlanCycle && io.migAdvice
```

e. The two calls (~lines 1120–1121) become:

```scala
  val (planWant,   planDecline)   = migFastTerms(io.directory.bits, planAdvice)
  val (resumeWant, resumeDecline) = migFastTerms(meta, migAdviceValidReg)
```

f. `def armEviction(m: DirectoryResult, wantMigrate: Bool, srcSet: UInt): Unit = {` gets a fourth
   parameter, `advice: Bool`. Inside it: in the `EVICT-ASSESS` printf change `adviceValid=${migAdviceValidReg}`
   to `adviceValid=${advice}`; change
   `} .elsewhen (params.micro.enableSetBalancing.B && migAdviceValidReg && migProbe) {` to
   `} .elsewhen (params.micro.enableSetBalancing.B && advice && migProbe) {`.

g. The resume call (~line 1504) becomes
   `when (!willServe) { armEviction(meta, migResumeWantW, request.set, migAdviceValidReg) }`.

h. The plan call (~lines 1839–1840) becomes — the new argument carries the same repeat guard as
   `wantMigrate`:

```scala
          armEviction(new_meta, migFastWantW && !(io.allocate.valid && io.allocate.bits.repeat),
                      new_request.set, planAdvice && !(io.allocate.valid && io.allocate.bits.repeat))
```

i. In the `when (io.allocate.valid) {` block (~lines 1464–1472): delete the comment line
   `// SBC Phase 2: latch migrate advice for this set (suppressed on repeat allocations).` and change
   `migAdviceValidReg := io.migAdvice && !io.allocate.bits.repeat` to
   `migAdviceValidReg := false.B   // 013: set on our own lookup's result, below`.

j. Directly after that `when (io.allocate.valid) { … }` block closes, add:

```scala
  // 013: keep the plan-cycle advice for the search-resume path. A same-cycle reload wins (it clears it).
  when (migPlanCycle && !io.allocate.valid) { migAdviceValidReg := io.migAdvice }
```

After these edits, `grep -n "migrateQuery\|migrateResp.migrate\|isDemandA\|hotOK" *.scala` (in
`design/craft/inclusivecache/src`) must print nothing.

**9.4 Checks**

| # | check | pass |
|---|---|---|
| C2-a | gate `c2` passes — this includes the new assert never firing | yes |
| C2-b | `same` for both NoSbc pairs, `base` vs `c2` | `IDENTICAL` ×2 |
| C2-c | `grep -c "HOT-NOW" <run dir>/sbc.log`, both SBC-config runs | > 0 |
| C2-d | lines with `sat=14` (command below): advice given on the miss that takes a set to `T_hi`, impossible before C2 | > 0 on the stress run. If 0 on both runs, report it: the new path is not covered |
| C2-e | MMIO block, `HOT` and `ADVICE-MIG` counts, `c1` vs `c2` | recorded |

C2-d command:

```bash
grep "HOT-NOW" <run dir>/sbc.log | grep -cw "sat=14"
```

Commit: `013 C2: test for hot after the update, on the looked-up set (gaps 2+3, M17)`.

---

## 10. C3 — a demand second search updates the partner (gap 4)

**10.1 `Directory.scala`** — change two of the `io.satTap` lines from C1:

```scala
  // 013 C3: a demand access's second search is an access to the partner set (paper Fig. 2).
  io.satTap.valid       := ren2 && demand && (!internalRead || secondarySearch)
  io.satTap.bits.hit    := Mux(secondarySearch, io.result.bits.secondaryHit, io.result.bits.hit)
```

Nothing else is needed for the behaviour. The SBU's counter update is generic in (set, hit); the DSS
update already skips paired sets (`!at(tapSet).valid`); `hotNow` already ignores `second`; the destination
probe never taps (its `demand` is 0); a Release searching for its parked line never taps (its `demand` is 0).

**10.2 Debug (sim only)**

a. `SetBalanceUnit.scala`, in the `sbcDebug` block:

```scala
    when (io.dirTap.valid && io.dirTap.bits.second) {
      printf(p"[SBC] D-TAP set=${tapSet} hit=${io.dirTap.bits.hit} sat=${cur}->${nxt}\n")
    }
```

b. `Scheduler.scala`, in the C0 debug block — two counters, and append
   ` secDemand=$secDemand satFeedSec=$satFeedSec` to the `SAT-SUM` printf:

```scala
      val secDemand  = RegInit(0.U(64.W))    // demand second searches issued
      val satFeedSec = RegInit(0.U(64.W))    // second-search results the SBU saw (must equal secDemand)
      when (mshr_uses_directory_for_dread && schedule.dread.bits.secondarySearch && schedule.dread.bits.demand) {
        secDemand := secDemand + 1.U
      }
      when (directory.io.satTap.valid && directory.io.satTap.bits.second) { satFeedSec := satFeedSec + 1.U }
```

**10.3 Checks**

| # | check | pass |
|---|---|---|
| C3-a | gate `c3` passes, including `case_teardown`, which now runs with the partner's counter moving | yes |
| C3-b | `same` for both NoSbc pairs, `base` vs `c3` | `IDENTICAL` ×2 |
| C3-c | the C1-c awk with `satFeedSec` and `secDemand` in place of `satFeed` and `lookDemand` | `bad 0` |
| C3-d | `D-TAP` lines with `hit=1` and with `hit=0` on the stress run (commands below) | both > 0 |
| C3-e | every `D-TAP` line moved the counter by the paper's rule — command below | `bad 0` |
| C3-f | MMIO block, and the `HOT`, `ADVICE-MIG`, `SEC-SERVE`, `SEC-MISS` counts, `c2` vs `c3` | recorded |

C3-d commands:

```bash
grep "D-TAP" <run dir>/sbc.log | grep -c "hit=1"
grep "D-TAP" <run dir>/sbc.log | grep -c "hit=0"
```

C3-e command:

```bash
grep "D-TAP" <run dir>/sbc.log | awk -v MAX=15 '{h=-1
  for(i=1;i<=NF;i++){ if($i~/^hit=/){split($i,x,"=");h=x[2]+0}
                      if($i~/^sat=/){split(substr($i,5),s,"->");a=s[1]+0;b=s[2]+0} }
  e=(h==1)?(a>0?a-1:0):(a<MAX?a+1:MAX); if(b!=e){bad++; if(bad<=3)print "BAD",$0}}
  END{print "lines",NR,"bad",bad+0}'
```

Commit: `013 C3: a demand second search updates the partner's counter (gap 4)`. Then **stop point (c)**.

---

## 11. What this task does and does not touch

**TileLink:** no message, probe, release, grant or inclusion rule changes. The task changes only *whether a
migration is attempted* (the advice) and the SBU's counter and DSS state.

**Data array:** no read or write path changes. BankedStore, SourceC, SourceD and SetCopyUnit are untouched.

**Requests in flight:**

| Case | After this task | Why it is safe |
|---|---|---|
| An MSHR is reloaded in the same cycle its old lookup's result lands | the reload clears `migAdviceValidReg`; the plan-cycle latch is skipped; the probe branch carries the same `!(allocate && repeat)` guard as `wantMigrate` | the new request gets its own advice at its own lookup |
| Repeat allocation (same line, no lookup) | no plan cycle, so no advice | same as today's `!repeat` |
| A migration is already in flight | `!anyMigrating` keeps the advice false | same as today |
| An inner-C or X lookup on a set an A MSHR holds | not counted | counter input only |
| A Release's second search for its parked line | `demand` = 0, never counted | not an access |
| Destination probe | never counted | Fig. 2: installing a guest does not move D |
| A second search answered after a teardown (`pairStale`) | the MSHR still distrusts the answer (unchanged); the counter counts the lookup as seen | the lookup did happen; rare (`SEC-STALE` printf) |
| The destination changed between the advice and the claim | the destination side is still checked live by `destQuery` (unchanged) | unchanged |

---

## 12. Bitstreams

Two images of the **same new config**, built one at a time, in tmux session `sbc013`:

| image | RTL | tag | when |
|---|---|---|---|
| **control** | the **C0** commit. C0 adds only `sbcDebug` code, which the FPGA config does not elaborate, so this is today's hardware | `control-013c0` | after go at stop point (b) — it can run while you do C1–C3 |
| **candidate** | the **C3** commit | `candidate-013c3` | after go at stop point (c) and after the control build finished |

⚠️ The control must be built from the C0 source. If the working tree is already past C0 when you get the
go, stop and ask — never check out old files.

**Why a new config:** the existing single-core 1 MB image (`585dbde1…3138`) has a 32 kB **4-way** L1; the
dual-core image (`98aedafa…929e`) and the paper use 32 kB **8-way**. Only the new config isolates core count.

**12.1 Config classes (chipyard repo) — add verbatim.**

`$CY/generators/chipyard/src/main/scala/config/RocketConfigs.scala`, directly after the line
`class SingleRocketVCU118L18K1M8WL2ConfigSBCPLRU extends Config(` and its one-line body:

```scala
// 1 MB 8-way L2 (2048 sets) with the paper's L1: 32 kB 8-way (64 sets x 8 ways x 64 B), single core.
// Same L1 lines as DualRocketVCU118L18K1024K8WL2ConfigSBCPLRU, so single vs dual differs only in cores.
class SingleRocketVCU118L132K1024K8WL2ConfigSBCPLRU extends Config(
  new freechips.rocketchip.subsystem.WithInclusiveCache(nWays = 8, capacityKB = 1024,
    sbcAutoMigrate = true, sbcShadow = false, sbcDebug = false, plruReplacement = true) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(8) ++
  new freechips.rocketchip.rocket.WithL1DCacheSets(64) ++
  new freechips.rocketchip.rocket.WithL1ICacheWays(8) ++
  new freechips.rocketchip.rocket.WithL1ICacheSets(64) ++
  new freechips.rocketchip.rocket.WithNBigCores(1) ++
  new chipyard.config.AbstractConfig)
```

`$CY/fpga/src/main/scala/vcu118/Configs.scala`, directly after `class FPGASingleRocketVCU118L18K1M8WL2ConfigSBCPLRU … )`
closes:

```scala
// 013: single core, 1 MB 8-way L2, 32 kB 8-way L1 (the paper's L1).
class FPGASingleRocketVCU118L132K1024K8WL2ConfigSBCPLRU extends Config(
  new WithFPGAFreq50MHz ++
  new WithVCU118Tweaks ++
  new chipyard.SingleRocketVCU118L132K1024K8WL2ConfigSBCPLRU
)
```

**12.2 Build script** — create `$CY/scripts/ssbc_scripts/build_1mb_8w_l132k.sh` as a copy of
`build_1mb_8w.sh` with exactly these three lines changed:

```bash
CFG=FPGASingleRocketVCU118L132K1024K8WL2ConfigSBCPLRU
TAG="1MB-8way-L1-32K8W-${1:?usage: $0 <tag, e.g. control-013c0>}"
  echo "geometry: 1024 KB (1 MB), 8 ways, 64 B lines -> 2048 sets; L1 D/I 32 KB 8-way"
```

**12.3 Commit in the chipyard repo** only those three files (`git -C $CY add <three paths>` — the other
chipyard changes are not yours). Message: `013: single-core 1 MB 8-way config with the paper's 32 kB 8-way L1`.

**12.4 Build one image**

```bash
pgrep -af vivado      # must print nothing. If a build is running, wait for it.
tmux new -s sbc013    # or: tmux attach -t sbc013
LOG=$CY/scripts/logs/013-build-<tag>-$(date +%Y%m%d-%H%M%S).log
bash $CY/scripts/ssbc_scripts/build_1mb_8w_l132k.sh <tag> 2>&1 | tee $LOG
```

Give the user `$LOG` at once. **Do not edit any `.scala` file and do not start a gate until Vivado is
running** (`pgrep -af "vivado -nojournal"` prints a process): until then the build is still elaborating
from the working tree. While Vivado runs, gates may run with `SBC_THREADS=8 SBC_JOBS=8`.

**12.5 After each build**, record in REPORT: sha256, WNS / TNS / WHS, the DRC count (the script prints
them), and the provenance check — every `.scala` hash in the provenance file must equal that file at the
image's commit:

```bash
P=<the .provenance.txt the script wrote next to the .bit>
C=<the image's commit sha>
grep "\.scala$" $P | while read h f; do
  g=$(git -C $L2 show $C:design/craft/inclusivecache/src/$f | sha256sum | cut -d' ' -f1)
  [ "$h" = "$g" ] || echo "MISMATCH $f"
done; echo "provenance checked"
```

Pass: no `MISMATCH` and WNS ≥ 0. A negative WNS is a finding — stop; do not restructure the RTL.

---

## 13. Board — after go at stop point (d)

Board rules: sha256 the file before programming it; one image per session; a hang with 0 bytes on the
UART is an SBC wedge — capture 010 TASK §6 **before** reprogramming; an `mmc_rescan` stall over 120 s
recovers by itself — do not reboot for it.

**13.1 Tools.** `sw/build/sbc_read` is from 25 Sep. Rebuild both tools from the current tree:

```bash
cd $L2
riscv64-unknown-linux-gnu-gcc -O2 -static -o sw/build/sbc_read sw/sbc_read.c && riscv64-unknown-linux-gnu-strip sw/build/sbc_read
riscv64-unknown-linux-gnu-gcc -O2 -static -o sw/build/l2_miss_calib sw/l2_miss_calib.c
```

**13.2 Write the predictions (13.4) into REPORT before the first run.**

**13.3 For each image, control first**, all in tmux `sbc013`:

1. omnetpp A/B, ~4–5 h (migration OFF half, then ON):

```bash
sha256sum <archived .bit>     # must equal the REPORT row
cd $CY/scripts/ssbc_scripts && ./run_board_session.exp -bit <archived .bit> -ab -policy plru
```

   Log: `$CY/scripts/logs/board_session_<stamp>.log` — give the user the path. Both halves must print
   `workload rc=0`.

2. calib sweep, ~25 min:

```bash
cd $CY/scripts/ssbc_scripts && ./reboot_and_handover.exp -bit <archived .bit>
cd $L2/sw/scripts && ./run_calib_sweep.exp -hm 64 -drain "-P 8 -p 24" -label 013-<tag>
```

   `-hm 64` and the 8-way drain match the 26 Sep 1 MB sweep
   (`ai-documents/performance/board-calib-envelope-8way-1mb-2026-09-26.md`). Give the user the transcript
   path it prints. Parse it with `sw/scripts/parse_calib_sweep.py` (read its usage first).

Record, per image and half: `L2_Cycles`, `memReads`, `memWrites` (separately), `L2_Accesses`,
`L2_AccessA`, hit rate, `attempted`, `migrations`, destination aborts by reason (dirty / held / both),
parked at end, `secHits`, second searches, workload rc. For calib: each image's point table (ON-vs-OFF
Δcycles and ΔmemReads) and the cliff position per row.

**13.4 Predictions**

- **P1 — omnetpp moves.** The ON half's `attempted` differs between control and candidate by **≥ 20%**:
  holds. 5–20%: inconclusive. < 5%: fails. Reason: gap 1 alone removes at least 21.4% of the counter's
  events on omnetpp (write-backs counted as hits).
- **P2 — calib does not.** The cliff stays at `MP = 16 − HP` in every row, and the median |difference in
  ON-vs-OFF Δcycles| between the images is ≤ 2 percentage points. Reason: calib makes few write-backs, its
  miss sets sit at the top (gap 2 inert), and its pairings are static (gap 4 inert).
- Every board number is n = 1 (tracker M2). Neither prediction is a speed claim. **If P1 fails, that
  falsifies the M18 explanation for omnetpp — report it plainly; it is a result, not a failure of the task.**

---

## 14. REPORT.md

Fill `REPORT.md` in this directory as you go — the template is already there. A stage with an empty row
is not done.
