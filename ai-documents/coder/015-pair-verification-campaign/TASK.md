# Coder task 015 — multi-application SBC verification campaign

**Opened 2026-10-07.** Board work on the VCU118. Harness is
`chipyard/scripts/ssbc_scripts/run_pair_session.exp` (1132 lines) plus `lib/*.exp`.

Authorised by the user 2026-10-07: *"lu and streaming is not enough for solid verification"* and
*"give me a comprehensive .md file for this experiment."*

**This file is written for a coding model.** Every command is spelled out. Line numbers are as of
2026-10-07 and will drift — **always find the spot by the quoted code, not by the number.** If the
code does not look like what this file quotes, **stop and ask.**

**No bitstream builds are in scope.** Every image this campaign needs already exists.

---

## 0. Rules — read before anything else

1. **Plan first.** Read this whole file, then reply with a plan of at most 10 lines plus any
   questions. Touch nothing until the user says go. Simple English, point form.
2. **Stop points.** Stop and wait for the user's go:
   (a) after your plan;
   (b) after the §4 harness fixes are written and unit-tested, **before** the first board session;
   (c) after §6 R0 (the reproduction gate) — do not start new science until the known number comes back;
   (d) before S7 (dual-core), which is the one stage with a known failure mode.
3. **PHASE ORDER IS ABSOLUTE: every `migrate=off` run before any `migrate=on` run.** The harness
   canonicalises `-phases` (SAFETY LOCK 1.1), but never rely on that — type them in the right order.
4. **ONE `migrate=on` window per boot.** A migrate=on run leaves parked lines and a built
   Association Table behind, and **nothing in software can undo that.** Measured 2026-10-04: a warm
   ON run read −13.1% / −10.0% where three cold boots of the same point read −4.45% / −3.50% /
   −3.44%. Reprogram the FPGA before every single ON window.
5. **Never pass `-allow-dirty-on`.** It exists to be refused. If a plan trips SAFETY LOCK 1.2, the
   plan is wrong — split it, do not override it.
6. **`sbc_read --reset-all` is NOT functional at the hardware level.** Do not use it and do not
   describe it as a reset. **A full FPGA reprogram is the only true SBC reset.**
7. **`L2_Replacement` resets to `random` on every reprogram.** Always pass `-policy plru`. The
   harness sets it explicitly per run; never assume the board remembers a build flag.
8. **Report `memReads` and `memWrites` separately in every table**, as well as their sum. SBC can
   trade one for the other (a parked dirty line that would have been dropped now gets written back)
   and a combined figure hides exactly that. See the repo CLAUDE.md, "The headline metric".
9. **Use the project's cache terminology** (`ai-documents/guides/cache-terminology.md`): access,
   primary hit, secondary hit, data miss, upgrade miss, probed hit. **`probedHit` is a subset of
   the hits, not a fifth outcome** — never add it into a hit rate. Hit rate =
   `(primaryHit + secondaryHit) / accessA`.
10. **A failing check is a finding.** Write it in `REPORT.md`, stop, ask. **Never edit the harness
    to make a gate pass.** Never "fix" anything else you notice — report it.
11. **Give the user every log path the moment a run starts** (repo convention). Transcripts land in
    `chipyard/scripts/logs/pair_session_<stamp>.log`, data in `pair_runs_<stamp>.csv`.
12. **Never read a whole session log.** Use `grep -a` (they contain binary bytes from the serial
    console and plain `grep` will skip them), `grep -c`, `tail`, or parse the CSV.
13. **Parse every CSV by column NAME, never by position.** The layout changed on 2026-10-06
    (`nproc` was inserted after `cpu_b`).
14. **Git:** never `git checkout <file>`, `git restore`, `git reset --hard`, `git clean`, or
    `git stash drop`. Stage files by name — never `git add -A` or `git add .`.
15. **Board hygiene:** `busybox taskset`, not bare `taskset`. There is **no `timeout`** on the
    board. If a run wedges, the harness's `-tmo` is the only timeout that exists.

---

## 1. Goal, stated as a falsifiable question

The 2026-10-05 result is real but it is **one pair, measured once**:

> Two applications sharing a 256 KB L2, SBC on vs off: memory traffic **−12.58%**, MPKI **−12.94%**,
> **29.5% of co-running interference repaired.**

What that result does **not** establish is that this is a *multi-application* effect. The one arm
that contradicts it is solo LU, which read **−17.66%** on its own — *better* than the pair — though
only once, cross-session, n=1.

**The question this campaign answers:**

> Does SBC's benefit come from applications *interfering* with each other, or merely from a
> workload sitting at a low hit rate — which `b=64` produces with no second application at all?

Two outcomes, both publishable, and the campaign is designed to tell them apart:

- **"SBC helps co-running applications"** — repair scales with interference, and self-pairs behave
  differently from mixed pairs.
- **"SBC helps low-hit-rate workloads; co-running is one way to create one"** — repair tracks the
  hit rate regardless of how many applications produced it.

**Do not try to make the first outcome true.** The dose-response curve in S2 is the deciding
measurement, and a flat curve is a result, not a failure.

---

## 2. Platform and invariants

| thing | value |
|---|---|
| Board | VCU118 (xcvu9p), 50 MHz uncore, 2 GiB DDR4 |
| L2 under test | **256 KB, 8-way → 512 sets, 64 B blocks** |
| Serial | `/dev/ttyUSB2` @ 115200, login `root` / `fpga`, prompt `"# "` |
| Board dirs | workloads in `/root/parsec`, `sbc_read` at `/root/sbc_read` |
| Counter block | one L2-wide MMIO block at `0x2010000` |

### 2.1 Bitstreams — both already exist, do not rebuild

```
CY=/home/damith/Research/repos/chipyard_performance_eval/chipyard
BIT1=$CY/fpga/bitstream_storage/FPGASingleRocketVCU118L18K256K8WL2ConfigSBCPLRU-256KB-8way-candidate013-2026-10-01-2026-10-01.bit
BIT2=$CY/fpga/bitstream_storage/FPGADualRocketVCU118L18K256K8WL2ConfigSBCPLRU-dual-256KB-8way-2026-10-02.bit
```

- **`BIT1` — single core.** This is what produced the 2026-10-05 headline. Confirmed by reading the
  `programFPGA.sh` line out of `pair_session_20261005-{121456,125128,144834,160348,163558}.log`.
  Use it for **S1–S6**. Requires `-samecore` (both workloads on cpu 0).
- **`BIT2` — dual core.** Used only by `pair_session_20261005-110920.log`, which **TIMED OUT**.
  Use it only for **S7**, and only after stop point (d).

> **The headline is a single-core result.** Both applications time-share one core and genuinely
> share the L2, but they are not concurrent. Say so in every table. The dual-core concurrent case
> is S7 and is not yet measured with migration on.

### 2.2 The one structural limit you cannot work around

**There is no per-application counter attribution.** One L2-wide register block, and nothing in
`PerfCounters.scala` carries a source or client id. So you can never say "streamcluster's share of
the traffic fell". The pair must be measured as one unit, and per-application evidence can only
come from **wall-clock runtime per workload**, which is why S7 exists.

### 2.3 Interference — use definition B, and say so

```
I_off = memAcc(pair, off) - memAcc(A, off) - memAcc(B, off)
I_on  = memAcc(pair, on)  - memAcc(A, off) - memAcc(B, off)     <-- solos stay at OFF
repaired = (I_off - I_on) / I_off
```

The solo terms are **fixed at their OFF values** in both numerator and denominator. This matters
enormously for cost, because the solo terms then cancel in the numerator:

```
I_off - I_on = memAcc(pair, off) - memAcc(pair, on)
```

**So each experimental point needs only ONE migrate=on window — the pair's.** The solos are needed
at OFF only, to set the denominator. That is what takes S2 from 15 ON boots down to 5.

State the definition in every table. Under definition A (same-phase solos) the solo ON runs would
also be required, and the numbers would differ.

---

## 3. The reference configuration

Reconstructed from `pair_session_20261005-121456.log` (OFF, n=3) and `-125128`/`-144834` (ON).

| workload | exact command on the board |
|---|---|
| LU | `./lu_ncb -p1 -n512 -b64` |
| streamcluster | `./streamcluster 2 3 16 43008 2048 200 none output.txt 1` |

Harness flags that produce them:

```
-lu-p 1 -lu-n 512 -lu-b 64
-sc-k 2,3 -sc-dim 16 -sc-n 43008 -sc-chunk 2048 -sc-cluster 200 -sc-threads 1
-samecore -policy plru
```

Point labels (these are what `-bench` takes): `solo-lu`, `solo-streamcluster`,
`pair-lu+streamcluster`.

### 3.1 Numbers that must come back (single core, b=64, `-samecore`)

| arm | n | memAcc | hit % | accessA |
|---|---:|---:|---:|---:|
| solo LU OFF | 3 | 6,446,901 | 74.51 | 14,236,027 |
| solo streamcluster OFF | 3 | 1,390,328 | 92.12 | 15,242,430 |
| **pair OFF** | 3 | **13,650,286** ± 773,063 (±5.7%) | 72.80 | 33,180,357 |
| **pair ON** | 2 | **11,933,076** sd 16,156 (±0.14%) | 76.32 | 33,117,542 |
| solo LU ON | 1 | 5,308,519 | 78.93 | 14,192,752 |
| solo streamcluster ON | 1 | 1,254,398 | 93.00 | 15,243,140 |

Derived: headline **−12.58%**, interference 5,813,058 (**+74.17%**), repaired **29.5%**,
MPKI 272.0 → 236.8 (**−12.94%**), Welch **t=3.85, df=2.00, p≈0.061**.

### 3.2 Known-bad arms — do not reuse these as evidence

- **solo streamcluster ON (−9.78%)** is **noise.** Its 3,849 secondary hits cannot account for
  133,971 fewer read misses — off by 35x.
- **solo LU ON (−17.66%)** is **n=1 and cross-session.** Held open, not dismissed. S3/S4 settle it.
- **`canneal` has never completed a run on this board** (rc=1 both phases, silently — `sbc_read`
  reports a signal-killed child as exit 1 and the riscv kernel does not log `raise`/`abort`). Do
  not select it. Do not try to fix it.
- The documented 72-run sweep in `ai-documents/performance/lu-single-app-2026-10-02.md` §3 has
  **36 ON windows on one boot** (0 reprograms in its transcript). Its ON half is contaminated and
  contamination **flatters SBC**. Do not cite it for anything.

---

## 4. Prerequisite harness fixes (host-side, no board)

Three defects found 2026-10-07 while surveying the workloads. **All three block stages below.**
Write and unit-test all three, then stop at point (b).

Back up first:

```bash
cp scripts/ssbc_scripts/run_pair_session.exp /tmp/run_pair_session.exp.bak-$(date +%Y%m%d)
```

### P1 — a self-pair silently merges its two solo rows

`plan_pair` builds labels as `solo-$a`, `solo-$b`, `pair-$a+$b`:

```tcl
proc plan_pair {a b} {
    ...
    lappend plan [point "solo-$a"    solo $a $ca $::CPU_A ""  ""  $::CPU_B]
    lappend plan [point "solo-$b"    solo $b $cb $::CPU_B ""  ""  $::CPU_B]
    lappend plan [point "pair-$a+$b" pair $a $ca $::CPU_A $b  $cb $::CPU_B]
```

With `-pair lu,lu` **both solo points are labelled `solo-lu`**. `find_row` matches on exact label:

```tcl
proc find_row {label mig} {
    ...
        if {[dict get $r label] eq $label && [dict get $r migrate] eq $mig \
            && [dict get $r status] eq "OK"} { lappend hits $r }
```

so it collects both and averages them into one row, and the report prints A and B identically.
`-bench solo-lu` would also run the point twice.

**Required fix.** Disambiguate **only when `$a eq $b`**, so every existing label for a mixed pair
is unchanged and old CSVs stay comparable:

- when `a eq b`: label them `solo-$a-a` and `solo-$b-b`
- otherwise: leave `solo-$a` / `solo-$b` exactly as they are

Then make the report section stop reconstructing label strings. It currently does:

```tcl
    set ra [find_row "solo-$WL_A" $ph]
    set rb [find_row "solo-$WL_B" $ph]
```

**Derive the labels from `$PLANNED` instead** (the two `kind eq "solo"` points, in order), so the
report cannot drift from the plan again. The same applies to the OFF-vs-ON section further down
(`rao`/`ran`/`rbo`/`rbn`).

### P2 — the workload registry is stale, and the thread check silently skips unknowns

```tcl
proc wl_known {} { return {lu streamcluster canneal blackscholes nop} }
```

Of those five: `canneal` never completes, `blackscholes` runs 0.17 s (smoke test only). Meanwhile
`lu_cb`, `bodytrack` and `barnes` are built, staged, and **proven rc=0 on this board**, and task
014 adds `radix` and `fft`.

Add these workload trios. Each needs `wl_<id>_stage` and `wl_<id>_cmd`:

```tcl
# lu_cb: same algorithm and flags as lu_ncb, but each block is allocated contiguously, so at the
# same -n/-b it lays a different set-index distribution over the 512 sets. See the note at
# run_parsec_session.exp:272. Shares LU_N/LU_B deliberately - that is the point of the comparison.
proc wl_lu_cb_stage {} { stage_file lu_cb }
proc wl_lu_cb_cmd   {} { return "./lu_cb -p$::LU_P -n$::LU_N -b$::LU_B" }

proc wl_radix_stage {} { stage_file radix }
proc wl_radix_cmd   {} { return "./radix -p1 -n$::RADIX_N -r$::RADIX_R -m$::RADIX_M" }

proc wl_fft_stage {} { stage_file fft }
proc wl_fft_cmd   {} { return "./fft -p1 -m$::FFT_M -l$::FFT_L" }
```

with new config defaults and argv flags alongside the existing ones:

```tcl
set RADIX_N 65536 ; set RADIX_R 1024 ; set RADIX_M 524288
set FFT_M   16    ; set FFT_L   4
```

`-radix-n`, `-radix-r`, `-radix-m`, `-fft-m`, `-fft-l`.

Add all new ids to `wl_known`. **Leave `canneal` in the list** (the usage header references it) but
add a one-line comment that it has never completed.

**Second half of P2, do not skip it.** The core-count enforcement reads a dict that only covers
three workloads, and silently passes anything missing:

```tcl
set _thr [dict create lu $LU_P streamcluster $SC_THREADS canneal $CANN_THREADS]
foreach _w [list $WL_A $WL_B] {
    if {![dict exists $_thr $_w]} { continue }
```

Add an entry for **every** workload you introduce (`lu_cb $LU_P`, `radix 1`, `fft 1`, and
`bodytrack 1` / `barnes 1` if you add them). Otherwise the check you were asked to enforce is
vacuous for exactly the new workloads.

### P3 — `barnes` cannot be run through `pair.sh` as-is

Only do this if you reach S5b. It is recorded here because it is a trap, not an oversight.

`barnes` reads its parameters from **stdin** (`./barnes < barnes_input`). The guard in `run_phase`
takes only the first word, so it would pass:

```tcl
            set exe [lindex [split $c] 0]
            if {[board_probe "test -x $exe"] != 0} {
```

but `pair.sh` then runs the command as

```sh
  busybox taskset -c $CA $A > a.out 2> a.err
```

with `$A` unquoted. Word-splitting happens, **but a `<` arriving via variable expansion is not
honoured as a redirection** — it is passed to `barnes` as a literal argument, and `barnes` then
reads the serial console instead. **It will hang.**

**Fix:** stage a one-line wrapper on the board and return that as the command:

```sh
# barnes_run.sh
exec ./barnes < barnes_input
```

`wl_barnes_cmd` returns `sh barnes_run.sh`. Note the guard then tests `test -x sh`, which passes
for the wrong reason — mention it in `REPORT.md` rather than reworking the guard.

Two more barnes facts, both verified 2026-10-07:

- The staged `barnes_input` is **nbody=1024**, which gives a **97.36% hit rate and a 1.47 s
  runtime** — useless as a co-runner. SPLASH-2's own default is `nbody=16384`. A new input file is
  required, and the format is positional (blank `in=`, then `nbody`, `seed`, then blanks for the
  integration params, then `NPROC`). **Verify the order against `code.C`'s `defv[]` — do not guess.**
- The staged input ends in `2`, i.e. **NPROC=2**, which contradicts single-core pinning. Set it to 1.

### 4.1 Unit tests before the board — all host-side

| # | command | must do |
|---|---|---|
| T1 | `info complete` on the edited file | parse clean |
| T2 | `-pair lu,lu -list` | two **distinct** solo labels |
| T3 | `-pair lu,streamcluster -list` | labels **unchanged** from today: `solo-lu`, `solo-streamcluster`, `pair-lu+streamcluster` |
| T4 | `-pair lu,lu -phases on -list` | still refused by lock 1.2 (3 points × 1 ON > 1) |
| T5 | `-pair lu,lu -phases on -bench pair-lu+lu -list` | **passes** the guard (1 ON window) |
| T6 | `-pair radix,fft -list` | both resolve, commands render with the right args |
| T7 | `-pair lu,fft -phases on,off -list` | prints the reorder note, runs `off` then `on` |
| T8 | `-pair radix,fft -sc-threads 2 -list` | unaffected; then set `-radix-n` and confirm it renders |

`-list` prints the plan and **exits without touching the board** — use it for all of the above.

---

## 5. How to run a session

### 5.1 OFF — all labels, repeats, one boot

OFF parks nothing, so the whole OFF side shares a single boot. Reprogram once, then run.

```bash
cd $CY/scripts/ssbc_scripts
./run_pair_session.exp -prog $BIT1 \
  -pair lu,streamcluster -samecore -policy plru \
  -lu-p 1 -lu-n 512 -lu-b 64 \
  -sc-k 2,3 -sc-dim 16 -sc-n 43008 -sc-chunk 2048 -sc-cluster 200 -sc-threads 1 \
  -phases off -repeat 3
```

Further OFF sessions on the **same** boot: drop `-prog`, and add `-skippush` once the binaries are
staged (the harness attaches to a booted board by default).

### 5.2 ON — one label, one boot, reprogram every time

```bash
./run_pair_session.exp -prog $BIT1 \
  -pair lu,streamcluster -samecore -policy plru \
  -lu-p 1 -lu-n 512 -lu-b 64 \
  -sc-k 2,3 -sc-dim 16 -sc-n 43008 -sc-chunk 2048 -sc-cluster 200 -sc-threads 1 \
  -phases on -bench pair-lu+streamcluster
```

`-prog` is **mandatory** here. Repeat the whole invocation, reprogramming, for each ON label you
need. Under §2.3 you normally need only the pair.

### 5.3 Boot budget

A reprogram is a few minutes of Vivado plus a Linux boot. This dominates the campaign.

| stage | OFF boots | ON boots |
|---|---:|---:|
| R0 reproduction | 1 | 0 (reuse 10-05 ON) |
| S1 statistics | 0 (shares R0) | 0 |
| S2 dose-response (5 points) | 1 | 5 |
| S3 self-pairs (2) | 1 | 2 |
| S4 `lu_cb` | shares S3 | 1 |
| S5 bodytrack (+barnes) | 1 | 1 (+1) |
| S6 radix + fft | 1 | 2–3 |
| S7 dual core | 1 | 3 |
| **total** | **~7** | **~14–15** |

---

## 6. R0 — the reproduction gate (stop point c)

**Before any new science.** One OFF session, exactly §5.1, `-repeat 3`.

| check | tolerance |
|---|---|
| `pair-lu+streamcluster` OFF memAcc | within the known spread: 12.76 M – 14.11 M |
| `solo-lu` OFF memAcc | 6,446,901 ± 5% |
| `solo-streamcluster` OFF memAcc | 1,390,328 ± 10% (this arm varies ±8.9%) |
| `accessA` per label | within 1% of §3.1 |
| every row | `status=OK`, `rc=0` |
| `overlap_frac` on the pair | ≥ 0.9 |
| `nproc` column | `1` |

If memAcc lands outside those bands, **stop and ask.** Something about the board, the staging or
the bitstream differs from 2026-10-05 and nothing downstream is interpretable until that is known.

---

## 7. The stages

### S1 — statistical hardening (cheapest, do it first)

The headline sits at **p≈0.061**, just outside 0.05, purely because OFF has n=3 (2 degrees of
freedom). More OFF samples fix it and **cost no extra boots** — OFF parks nothing.

Run §5.1 with **`-repeat 6`** instead of 3. Projected: n=4 → p≈0.021, n=6 → p≈0.003, n=8 → p≈0.0004.

Report Welch's t, df, and p against the existing ON pair (n=2). **Do not pool OFF runs from
different boots** without saying so — note which boot each came from.

### S2 — aggressor dose-response (the deciding measurement)

Hold LU fixed at `-lu-n 512 -lu-b 64`. Sweep streamcluster's **footprint** via `dim`/`chunk`, and
hold runtime near LU's ~24 s with `n` — §6.3 of `lu-streamcluster-pair-2026-10-05.md` established
that **footprint and runtime are independent knobs** (no input file is read when `n > 0`).

| # | `-sc-dim` | `-sc-chunk` | est. footprint | expected solo hit % |
|---|---:|---:|---:|---:|
| I1 | 16 | 1024 | 140 KB | 96.2 |
| I2 | 16 | 2048 | 236 KB | 88.0 | ← the 10-05 reference point |
| I3 | 32 | 2048 | 401 KB | 59.4 |
| I4 | 16 | 4096 | 428 KB | 57.0 |
| I5 | 64 | 8192 | 2 MB | 47.2 |

Keep `-sc-k 2,3 -sc-cluster 200 -sc-threads 1`. **Adjust `-sc-n` per point** so solo runtime lands
in 15–30 s; start from 43008 and scale inversely with chunk. Record the `-sc-n` you used.

For each point: solos OFF + pair OFF (all five points share **one** OFF boot), then **pair ON only,
one boot per point** (§2.3).

**The deliverable is a curve**, with interference on the x-axis and repaired fraction on the y:

| point | I_off | pair_off | pair_on | Δ% | repaired % |
|---|---:|---:|---:|---:|---:|

**Interpretation, written before the runs:**

- repaired % **rises** with I_off → SBC's benefit is driven by interference. Supports the
  multi-application story.
- repaired % **flat or falling** → the benefit tracks the operating point, not the co-running.
  The honest framing becomes "SBC helps low-hit-rate workloads".

Either way, S2 is the result. Do not reshape it.

### S3 — self-pairs (the control S2 cannot provide)

`-pair lu,lu` and `-pair streamcluster,streamcluster`, at the I2 settings. Needs P1.

This separates "two applications interfere" from "twice the pressure". If a self-pair repairs as
well as the mixed pair, the mechanism is pressure, not application diversity.

For `lu,lu`, both halves are identical, so the pair's accessA should be ≈2x solo accessA — a free
work-conservation check. Note it.

### S4 — a second victim: `lu_cb`

`-pair lu_cb,streamcluster` at I2, same `-lu-n 512 -lu-b 64`. Needs P2.

`lu_cb` runs the same algorithm as `lu_ncb` with contiguous block allocation, so it presents a
different set-index distribution at identical `-n`/`-b`. **This is the cleanest available test of
whether the 10-05 result is specific to `lu_ncb`'s layout.**

Also run `solo-lu_cb` OFF **and** ON (this is one of the few places a solo ON is worth a boot),
because it directly addresses the open solo-LU-ON question from §3.2.

### S5 — a qualitatively different co-runner: `bodytrack`

`-pair lu,bodytrack`. Needs P2 and the input-tree staging logic ported from
`run_parsec_session.exp:252-269` (it extracts `sequenceB_1.tar.gz` on the board and **bails if
`sequenceB_1/CALIB` is absent** — without it bodytrack segfaults).

Command: `./bodytrack sequenceB_1 4 1 100 3 0 1`. Measured 2026-10-01: 14.96 s, accessA 5,811,698,
hit 84.16%, memAcc/access 0.230, **61,545 demand misses/s = 0.22x LU**.

bodytrack is a **weak** aggressor and that is deliberate — it anchors the low end of S2's curve
with a genuinely different access pattern (computer-vision particle filter, irregular) rather than
another streamcluster setting. Its 14.96 s against LU's ~24 s gives good overlap.

#### S5b — `barnes` (optional, only if S5 goes smoothly)

Needs **P3** plus a re-sized `barnes_input`. Barnes is an octree → irregular pointer chasing,
unlike anything else in the set. At the staged nbody=1024 it is useless (97.36% hit, 1.47 s); try
nbody=16384 and report the resulting hit rate and runtime **before** pairing it.

### S6 — `radix` and `fft` (needs task 014)

Do not start until task 014 has staged both binaries and reported them statically linked.

- **`-pair lu,radix`** — radix is the only available co-runner that is both high-miss **and**
  write-heavy, like LU (`memAcc/accessA` near 0.949 vs streamcluster's 0.54). Start at
  `-radix-n 65536` (512 KB, 2x L2) and report `memReads`/`memWrites` separately — this is the one
  workload where the read/write split is the interesting part.
- **`-pair lu,fft`** at `-fft-m 16` (2 MB), run twice: `-fft-l 4` and `-fft-l 6`.

**On fft, be careful what you claim.** The `-l` knob changes the row padding
(`pad_length` 1 vs 4 elements, row stride 4112 vs 4160 B). SPLASH-2 pads *deliberately* to avoid
cache conflicts and its minimum `pad_length` is 1, never 0 — so **neither setting produces
pathological power-of-two aliasing.** It is a modest stride change. Do not report it as an
aliasing on/off switch. Task 014 §5.4 describes the real unpadded variant, which needs a source
patch and the user's go.

### S7 — dual core, per-application attribution (stop point d first)

The only route to "application A was protected from application B", because the counters cannot
attribute traffic (§2.2). The evidence is **wall-clock runtime per workload**, which `pair.sh`
already records (`wall_a`, `wall_b`, `overlap_frac`).

Known OFF harm, measured 2026-10-05 on `BIT2`: LU 23.54 → 24.79 s (**+5.31%**), streamcluster
22.30 → 23.53 s (**+5.50%**), traffic **+40.50%**. **The ON half has never been measured.**

- Use **`BIT2`**, drop `-samecore`, `-cpus 0,1`.
- **Start at `-lu-b 128`, not 64.** `pair_session_20261005-110920.log` TIMED OUT with migration on
  at `b=64` with two taskset-pinned processes. `b=128` is the geometry the rest of the campaign was
  built on and is known to survive migration-on.
- The deadlock is **specific to this pair setup** — the n=768 campaign CSVs show `on,1,OK` rows
  across three boots on dual core, so dual-core + migrate=on is not broken in general.
- Needs **three** ON boots (`solo-lu`, `solo-streamcluster`, `pair-...`) because per-application
  runtime requires the solo baselines in the same phase.
- If it times out again: record it, **do not retry more than once**, and report. Three consecutive
  failures abort the plan by design (`consec_fail >= 3`).

---

## 8. Acceptance gates for every single run

The harness enforces the first four and will write a non-`OK` status rather than a number. **Never
loosen them.**

| gate | status written | meaning |
|---|---|---|
| four-outcome identity, `\|accessA − (primaryHit+secondaryHit+dataMiss+upgradeMiss)\| ≤ 64` | `COUNTER_IDENTITY` | counters disagree — the row is void |
| `memReads == dataMiss` exactly | `COUNTER_IDENTITY` | same |
| `overlap_frac` present | `NO_OVERLAP` | the pair never really overlapped |
| `overlap_frac ≥ 0.5` | `LOW_OVERLAP` | the tail of the window was one app alone |
| `[SBC-WINDOW]` + `[PAIR-TIME]` + `workload rc=` all seen | `TIMEOUT` / `NO_RC_SEEN` | partial result, not trusted |

Additional checks **you** must make, which the harness does not:

- `accessA` for the same label must match between OFF and ON **within 1%**. If it does not, the two
  phases did different amounts of work and the comparison is meaningless.
- `overlap_frac < 0.9` on a pair: report it next to the number.
- `nproc` must be `1` for S1–S6 (BIT1) and `2` for S7 (BIT2).
- `memReads − memWrites − memRelClean == 0` on every OFF row. This held **exactly on 30/30** OFF
  rows on 2026-10-05 and is the cross-check that validates `memWrites`. On ON rows expect a small
  shortfall (1–4,459, i.e. ≤0.11% of memWrites) from the migration release path — record it, do
  not try to explain it without reading the RTL.

---

## 9. What to record

For every stage, one table with these columns. **`memAcc` alone is not acceptable** (rule 8):

```
label | migrate | n | accessA | primaryHit | secondaryHit | probedHit | dataMiss | upgradeMiss
      | memReads | memWrites | memAcc | hit% | L2_Cycles | wall_a | wall_b | overlap_frac
      | migrations | secondaryHit | parked | status | rc | boot# | log path
```

Plus, per stage: the spread (`n`, sd, min..max) on the pair's memAcc — the harness's `spread` proc
prints it, and a bare mean hides that the three 10-05 OFF runs were 12.76 M / 14.11 M / 14.08 M.

**MPKI** is the penalty-independent metric and is preferred over IPC. If you quote an IPC-like
figure, state the platform limit alongside it: measured miss penalty here is **27.9 cycles** against
200–300 on a real part, memory is only **10.0%** of runtime, and the damping factor is **9.6x**. A
−12.58% traffic reduction therefore shows up as only **+1.33%** implied IPC on this board. Projected
at realistic penalties: 100 → 3.86%, 200 → 6.15%, 300 → 7.66% (an upper bound; assumes serialised
misses).

---

## 10. Deliverables

1. The three §4 fixes, unit-tested per §4.1, committed as **one** change with its own message.
2. R0 reproduced and signed off, or a stop with the discrepancy named.
3. S1: the headline's p-value, recomputed at the larger n.
4. **S2: the dose-response curve** — the single most important output of this campaign.
5. S3, S4: whether the effect survives a self-pair and a different victim layout.
6. S5–S6 as far as they get.
7. S7 only after stop point (d).
8. **One paragraph answering §1's question in plain words**, with the evidence that decides it, and
   explicitly saying which of the two framings the data supports.
9. `REPORT.md` filled in as you go, with every log path.

**If the data supports the second framing — "SBC helps low-hit-rate workloads, and co-running is
one way to create one" — say so plainly.** That is a perfectly good result and it is the one the
current evidence slightly favours. Do not bend the campaign toward the first answer.

---

## Amendment 1 — 2026-10-07: `fft` sizing corrected by task 014

Task 014 is complete and found **two errors in this file's §7 S6 and in 014's own §5.2/§5.3**. Both
are mine, not the coder's, and both change how `fft` must be configured. The amended facts win over
§7 S6 wherever they disagree. Source:
[014 REPORT](../014-splash2-radix-fft-build/REPORT.md) findings F1 and F2.

**A1.1 — `fft` allocates three large arrays, not two.** `fft.C:228-231` allocates `x`, `trans` and
`umain2` at `2*(N + rootN*pad_length)*8 + PAGE_SIZE` each; `umain2` is the full roots-of-unity matrix
and is live throughout (`TwiddleOneCol`, `fft.C:669`). Only two are touched per phase, so:

| `-fft-m` | per phase (2 arrays) | x L2 | whole run (3 arrays) | x L2 |
|---:|---:|---:|---:|---:|
| 12 | 136 KB | 0.53 | 204 KB | 0.80 |
| **14** | **520 KB** | **2.03** | **780 KB** | **3.05** |
| 16 | 2,056 KB | 8.03 | 3,084 KB | **12.05** |

**§7 S6 says `-fft-m 16` is "2 MB". It is 2 MB per phase and 3 MB over the run (12x L2), not 8x.**
Quote both figures in any table, labelled.

**A1.2 — use `-fft-m 14`, not 16.** It is the nearest even `M` to 2x the L2 per phase, which matches
the `radix -n65536` point (also 2x) and keeps the two new co-runners comparable. `M` must be even, so
exactly 2x is not reachable.

**A1.3 — the `-l4` vs `-l6` comparison is a no-op below `-fft-m 14`.** `pad_length` has a second
page-rounding step at `fft.C:212-224` that §5.3 did not account for. Measured (014 F2, `P=1`):

| `-fft-m` | `-l4` stride | `-l6` stride | differ? |
|---:|---:|---:|---|
| 10, 12 | 640 B / 1088 B | identical | **no — same program** |
| 14 | 2080 B | 2112 B | yes |
| 16 | 4112 B | 4160 B | yes |

So run the `-l` pair at **`-m14` or `-m16` only**. At `-m12` it would compare a binary to itself.

**A1.4 — the §7 S6 warning stands and is reinforced.** The row stride is never an exact multiple of
64 lines at any setting, so **neither `-l` value produces power-of-two aliasing.** Report `-l` as a
stride change only. 014 §5.4 adds that a genuine unpadded variant must force `pad_length = 0`
**after** the page-rounding step, or the rounding puts the padding straight back.

**No other stage changes.** `radix` had no drift (014 F4): `-radix-n 65536` is exactly 512 KB = 2x L2.
