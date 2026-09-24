# 010 hang bisection — running progress log

**Status: IN PROGRESS.** Live working log for the task in [TASK.md](TASK.md). The deliverable is
`REPORT.md`; this file is the audit trail it will be built from, written as the trials happen so that
nothing is reconstructed from memory. **Update it after every run, before reprogramming anything.**

- **Goal:** find the newest bitstream on which `520.omnetpp_r` still completes with SBC migration ON.
- **Measuring, not fixing.** No `.scala` edits, no `git checkout`, no test tweaks. A failure is a finding.
- **Started:** 2026-09-24 ~09:16. **Last updated:** 2026-09-24 18:57.
- **Trials used: 6** (#3 PASS, #5 PASS, #6 FAIL, #7 PASS on random / **WEDGED on plru**, **#3 PASS on plru**). Trials 5 and 6 were user-directed after the §7 budget of four was spent.
- 🔴 **THE HANG REPRODUCED 2026-09-24 15:36 on #6 `7b83bcbc8f02`** — single-core, `random`, migration
  ON, 2nd run of boot A, wedged between t=60 s and t=121 s. Full §6 capture in §3A.4. **#6 = FAIL.**
- 🔴 **#5, #6 and #7 are ONE bitstream** — byte-identical configuration data, differing only in the
  6-byte embedded build timestamp (§3A.6). TASK §3's "three different place-and-route runs" is wrong.
- 🔴 **The hang is NON-DETERMINISTIC on identical hardware: random 3 pass / 1 wedge.**
- 🔴 **PLRU ALONE WEDGES THE BOARD — policy isolated (§3C.3).** As the **first and only** workload run
  of a fresh boot, `parked=0 migrations=0` verified beforehand: wedged inside 60 s. Both confounds
  (prior run, accumulated state) removed. **Final tally on identical bits: plru 0 pass / 4 wedge,
  random 3 pass / 1 wedge.** The 009 destination-eviction path runs ~2,938 times in the first 0.71 s
  under PLRU vs **0** under random — the wedge rate tracks exposure to it.
  So "the newest bitstream on which omnetpp completes" is not answerable as posed, a single PASS
  establishes nothing, and **rebuilding from `739bd2a` is provably useless** (the build is
  deterministic: #5 ≡ #7 bit for bit).

---

## 1. Score board

| # | bitstream (filename) | sha256 (first 12) | commit | R1 OFF | R2 ON same boot | R3 ON fresh boot | verdict |
|---|---|---|---|---|---|---|---|
| 3 | `…SBCPLRU-008-c2B-7494296-wip-2026-09-17.bit` | `e41f780c67ff` | **`201ebae`** (008 C2) ⁴ | 1017 s rc=0 | **1091 s rc=0** | **1091 s rc=0** | **PASS** |
| 3 | *(same bitstream, **plru**, trial 6)* | `e41f780c67ff` | **`201ebae`** ⁴ | — | — | **1029 s rc=0** ³ | **PASS** |
| 6 | `…SBCPLRU-739bd2a-unverified-tree-2026-09-22.bit` | `7b83bcbc8f02` | `739bd2a` | 1018 s rc=0 | **WEDGED t=60–121 s** | not run | FAIL ¹ |
| 7 | `…SBCPLRU-009-c2-739bd2a-clean-rebuild-2026-09-22.bit` | `7fb33ce6357c` | `739bd2a` | 1019 s rc=0 | **1091 s rc=0** | not run | PASS ¹ ² |

² #7 additionally ran **ON + `plru`** as a 3rd run of boot A: **WEDGED between t=0 s and t=60 s** (§3B).

¹ **#5, #6 and #7 are the same bitstream** (identical configuration data; only the embedded build
timestamp differs — §3A.6). So these two rows are the *same bits*: they passed twice and wedged once.
The PASS/FAIL split is **intermittency, not a difference between bitstreams.**
| 5 | `…SBCPLRU-009-c2-739bd2a-2026-09-21.bit` | `afa13ca9b010` | `739bd2a` | 1019 s rc=0 | **1091 s rc=0** | **1090 s rc=0** | PASS ¹ |

³ **#3 under `plru`, migration ON, first and only workload run of a fresh boot — the exact
configuration that wedges #5≡#6≡#7 four times out of four. It completed.** §3D. This is the
control the bisection previously lacked, and it moves the pass/fail boundary to lie between two
bitstreams of *different, known* provenance for the first time.

⁴ **Proven 2026-09-24, not inferred** (REPORT §8.4): the archived `.inclusive-cache.patch` beside #3
carries git `index <pre>..<post>` blob hashes, and for all 11 files the pre-image is `7494296`'s blob
and the post-image is **`201ebae`**'s. The run's own K3 identity agrees independently. The same bytes
are also archived as `…009-c1-2026-09-19.bit` (**zero** differing bytes, identical mtime) — a
mislabelled `cp -p` copy, so **009 C1 has never been on the board** (REPORT §8.5).

Full sha256 values as read on the machine, 2026-09-24 (re-verified immediately before each use):

```
e41f780c67ff2da2e60ff55c59f6750d0ea60e9fbfd9d18e36d06cb58711d177  …SBCPLRU-008-c2B-7494296-wip-2026-09-17.bit
afa13ca9b0107f15533bb01ff54cab85894e02de0f514fab8e9fc37b8d8734c9  …SBCPLRU-009-c2-739bd2a-2026-09-21.bit
```

### Search order actually followed

TASK §3 says start at #3 and walk newer on a pass. The user then overrode the next step
(2026-09-24, mid-session): after #3, go straight to **#5**; **if #5 fails walk back to #4, if #5 passes
walk up to #6/#7**. That is the order in force. #5–#7 are three different place-and-route runs of the
**same commit `739bd2a`**, so a split verdict among them means **timing/P&R, not RTL** — they must be
reported individually and never averaged.

---

## 2. Trial 1 — `e41f780c67ff` (008 C2 wip) — **PASS**

Recipe per TASK §4: two boots, policy `random`, migration read-back confirmed before every ON run,
manual §4.3 heartbeat loop (not the script's silence detector) for R2 and R3.

### 2.1 §7 run table

| field | R1 (OFF, 1st of boot A) | R2 (ON, 2nd of boot A) | R3 (ON, 1st of boot B) |
|---|---:|---:|---:|
| migrate read-back | **OFF** ✓ | **ON** ✓ | **ON** ✓ |
| policy read-back | random ✓ | random ✓ | random ✓ |
| `ITER_RC` | **0** | **0** | **0** |
| `ITER_SECS` | **1017** | **1091** | **1091** |
| `L2_Cycles` | 50,815,729,214 | 55,697,074,643 | 55,572,100,116 |
| `L2_Cycles` ÷ 50e6 | 1016.3 s | 1113.9 s ¹ | 1111.4 s ¹ |
| `accessA` | 1,015,399,773 | 1,035,058,029 | 1,037,219,431 |
| `primaryHit` | 680,972,432 | 694,269,268 | 695,938,023 |
| `secondaryHit` | 0 | 999,651 | 971,459 |
| `dataMiss` | 334,427,341 | 339,789,110 | 340,309,949 |
| `upgradeMiss` | 0 | 0 | 0 |
| `secondSearch` | 0 | 15,959,266 | 15,674,272 |
| `secondaryMiss` | 0 | 14,959,615 | 14,702,813 |
| **`memReads`** | **334,427,341** | **339,789,110** | **340,309,949** |
| **`memWrites`** | **69,392,342** | **71,080,856** | **71,297,535** |
| `memAcqPerm` | 0 | 0 | 0 |
| `memRelClean` | 265,034,999 | 264,320,975 | 264,651,714 |
| `migrations` | **0** | 4,387,279 | 4,360,700 |
| `attempted` | 0 | 4,616,254 | 4,590,515 |
| `parked` (live) | 0 | 26 | 31 |
| `dstAbortDirty` | 0 | 115,541 | 114,771 |
| `dstAbortHeld` | 0 | 103,090 | 104,529 |
| `dstAbortBoth` | 0 | 10,344 | 10,515 |
| heartbeats seen | n/a (script path) | **21** | **20** |
| outcome | **completed** | **completed** | **completed** |

¹ The R2/R3 figures are the post-run dump, taken ~23 s after the workload exited, so they run past
`ITER_SECS`. The clean in-run check is the `HB t=1030s` heartbeat, which read `L2_Cycles` =
51,522,953,381 (R2) and 51,523,479,843 (R3) → **1030.46 s and 1030.47 s against a heartbeat clock of
1030 s** — the 50 MHz reference and the `L2_Cycles ÷ 50e6 = seconds` identity both confirmed to
0.05%. R1 is the script's own `[SBC-DELTA]`, which brackets the run exactly: 1016.3 s vs
`ITER_SECS` 1017 (0.07%).

### 2.2 Identity checks — all exact

| identity | R1 | R2 | R3 |
|---|---|---|---|
| `accessA = primaryHit + secondaryHit + dataMiss + upgradeMiss` | exact (0) | exact (0) | exact (0) |
| `dataMiss + upgradeMiss = memReads + memAcqPerm` | exact | exact | exact |
| `secondSearch = secondaryHit + secondaryMiss` | exact (0=0) | exact | exact |
| `L2_Cycles ÷ 50e6 ≈ ITER_SECS` | 0.07% | 0.05% ¹ | 0.05% ¹ |
| **K3:** `attempted − migrations = dstAbortDirty + Held + Both` | n/a (0) | **228,975 = 228,975** | **229,815 = 229,815** |

The K3 match is the important one: it holds **only** under 008-c2 abort semantics, so it independently
confirms this bitstream's provenance as a 008-c2 build, without trusting the filename (TASK trap 4).

### 2.3 Trap checks

1. **`SBC_MigrateEnable` resets to 0** — read back and confirmed `migrate : ON` before both R2 and R3,
   and corroborated after the fact by the counters: millions of `migrations`, non-zero `secondaryHit`,
   non-zero `parked`. R1 shows `migrations=0 parked=0 secondSearch=0`, so the OFF run really was off
   and parked nothing — which is what makes `--reset-all` legal before R2.
2. **`sbc_read --zero -- cmd` hides the child's status** — every run echoed `ITER_RC=$?` explicitly.
   All three read `ITER_RC=0`. No rc=127.
3. **`L2_Cycles` counts while the core is wedged** — liveness was judged from `accessA` growth
   (~900 k/s across the heartbeats), never from `L2_Cycles`.
4. **Filenames and dates are unreliable** — `sha256sum` taken immediately before each programming run,
   and the hash, not the name, is the identifier in every table here.

### 2.4 Verdict

R2 completed **and** R3 completed → **PASS** by the TASK §4 table. The hang did not reproduce on this
bitstream, including through K7b's t=1 s–61 s window, on either the second-run-of-a-boot path (R2) or
the first-run-of-a-boot path (R3). 21 and 20 heartbeats respectively, no gap longer than one interval.

### 2.5 Side observations (not the task's question)

Recorded because they were measured cleanly; they are **not** the deliverable and they carry the
confounds noted.

- **R2/R3 agree to the second** (1091 s both), and their counters agree to ~0.2%. Run order within a
  boot changes nothing measurable on this bitstream.
- **SBC ON costs +7.3% wall time here** (1091 s vs 1017 s) with **+1.6% `memReads` / +2.4%
  `memWrites`**. Two caveats before this is compared with anything: R1 ran without `--zero` (see §4.1),
  so it is a delta, not a zeroed window; and R2 is the *second* run of the boot, which if anything has
  the warmer file cache. It is a single pair on a wip build, not an A/B result.
- **The TASK's "healthy run is 1033 s" reference matches neither half** — OFF is 1017 s, ON is 1091 s.
  Worth knowing when 1033 s is used as a pass threshold elsewhere.
- `upgradeMiss = 0`, `memAcqPerm = 0`, `probedHit = 0`, `secPerm = 0` throughout, as expected on one core.

---

## 3. Trial 2 — `afa13ca9b010` (commit `739bd2a`, 009 C2) — IN PROGRESS

sha256 re-verified `afa13ca9b010…` at 12:15 before programming.

### 3.1 Timeline

| stage | time | state |
|---|---|---|
| boot A programmed + Linux up, prompt reached | 12:15:31 → ~12:29 | ✓ |
| 6 tool files pushed over ZMODEM | ~12:29–12:38 | ✓ |
| `/dev/mmcblk0p2` mounted at `/mnt` (`MOUNT_SUCCESS`, ext4 recovery clean) | ~12:40 | ✓ |
| benchmark copy → `/root/test_dir/520.omnetpp_r_run_ref/` | 12:43–~12:52 | ✓ |
| **R1** omnetpp, migrate OFF | 12:52 → 13:09:37 | ✓ **completed, rc=0, 1019 s** |
| console released by `tmp.exp`, re-attached by hand | 13:10:16 | ✓ |
| policy `random` set, `--reset-all --migrate=on`, read-back | 13:10–13:11 | ✓ `migrate : ON` |
| `/root/hb.sh` staged, matches §4.3 verbatim | 13:11 | ✓ |
| **R2** omnetpp, migrate ON, same boot, manual heartbeat | **launched 13:11:52** | **running** |
| **R3** boot B, omnetpp, migrate ON, first run of the boot | pending | — |

Boot A's marker sequence matched #3's healthy boot exactly — no panic, no retry, no missing spinner.

### 3.2 R1 (OFF, 1st of boot A) — completed

`ITER_RC=0`, `ITER_SECS=1019`. Figures are the script's `[SBC-DELTA]`, which brackets the run.

| field | value | vs #3 R1 |
|---|---:|---|
| `ITER_SECS` | **1019** | 1017 (+0.2%) |
| `L2_Cycles` | 50,912,847,006 | — |
| `L2_Cycles` ÷ 50e6 | **1018.3 s** (0.07% of `ITER_SECS`) ✓ | — |
| `accessA` | 1,018,624,582 | +0.3% |
| `primaryHit` | 684,713,845 | +0.5% |
| `dataMiss` | 333,910,737 | −0.2% |
| **`memReads`** | **333,910,737** | −0.2% |
| **`memWrites`** | **69,515,801** | +0.2% |
| `migrations` / `parked` | **0 / 0** ✓ | 0 / 0 |
| `secondaryHit` / `secondSearch` / `upgradeMiss` / `memAcqPerm` | 0 / 0 / 0 / 0 | same |

Identities: `accessA = primaryHit + secondaryHit + dataMiss + upgradeMiss` **exact**;
`dataMiss + upgradeMiss = memReads + memAcqPerm` **exact**; `L2_Cycles ÷ 50e6` vs `ITER_SECS` 0.07%.
`migrations=0 parked=0` confirms the OFF run parked nothing, which is what makes `--reset-all` legal
before R2 (TASK §4).

**Reading:** with migration OFF this bitstream boots, runs omnetpp to completion, and lands within
0.2% of #3's OFF run on every counter. So the build and the staging are sound, and anything R2/R3 do
differently is attributable to migration being ON — not to a bad bitstream or a bad copy.

### 3.3 R2 (ON, 2nd of boot A) — **completed, no wedge**

Pre-run state verified, in this order: `--policy=random` rc=0 → `--reset-all --migrate=on` rc=0 →
read-back **`migrate : ON`, `policy : random`** (from line 2 of `sbc_read`, per §4.1 below) → `hb.sh`
staged and echoed back verbatim → `--zero` rc=0 → run. Launched 13:11:52, finished 13:30:03.

**`ITER_RC=0`, `ITER_SECS=1091`** — the same wall time as #3's R2 to the second. `wl.out` ends
`Calling finish() at end of Run #0... End.`, so omnetpp genuinely ran to completion; it did not exit
early. **18 heartbeats, no gap longer than one 61 s interval**, including straight through K7b's
t=1 s–61 s window.

Liveness was judged from `accessA`, never `L2_Cycles` (trap 3). It advanced monotonically at
~1.0 M/s across every heartbeat: 0.53 M (t=0) → 55.0 M (t=61) → 109.3 M (t=122) → … → 982.8 M (t=970)
→ 1032.0 M (t=1030). `migrations` rose 16 k → 4.92 M and `parked` oscillated 9–31 throughout, so SBC
was firing continuously, not stalled (trap 1 / K8).

| field | in-run dump at `HB t=1030s` | post-run dump ¹ |
|---|---:|---:|
| `L2_Cycles` | 51,527,469,776 | 55,764,183,993 |
| `L2_Cycles` ÷ 50e6 | **1030.55 s** vs heartbeat clock 1030 s ✓ | 1115.3 s ¹ |
| `accessA` | 1,032,049,347 | 1,035,699,094 |
| `primaryHit` | 691,453,678 | 693,577,507 |
| `secondaryHit` | 1,106,816 | 1,110,197 |
| `dataMiss` | 339,488,853 | 341,011,390 |
| `upgradeMiss` | 0 | 0 |
| `secondSearch` | 17,812,712 | 17,886,261 |
| `secondaryMiss` | 16,705,896 | 16,776,064 |
| **`memReads`** | **339,488,853** | 341,011,390 |
| **`memWrites`** | **70,786,648** | 71,311,898 |
| `memAcqPerm` | 0 | 0 |
| `memRelClean` | 268,702,205 | 269,699,492 |
| `migrations` | 4,924,564 | 4,943,964 |
| `attempted` | 4,924,564 | 4,943,964 |
| `aborted` (legacy) | 5,012 | 5,021 |
| `parked` (live) | 22 | 31 |
| `dstAbortDirty` | 14,512 | 14,674 |
| `dstAbortHeld` | 12,898 | 13,071 |
| `dstAbortBoth` | 1,183 | 1,188 |

¹ Taken ~24 s after the workload exited, so it runs past `ITER_SECS`. The clean in-run check is the
`HB t=1030s` column.

Identities on the in-run dump — `accessA = primaryHit + secondaryHit + dataMiss + upgradeMiss`
**exact**; `dataMiss + upgradeMiss = memReads + memAcqPerm` **exact**;
`secondSearch = secondaryHit + secondaryMiss` **exact**; `L2_Cycles ÷ 50e6` vs heartbeat clock 0.05%.

### 3.4 K3 does **not** hold on #5 — and that is the provenance evidence

| | #3 (`e41f780c67ff`) | #5 (`afa13ca9b010`) |
|---|---|---|
| `attempted − migrations` | 228,975 | **0** |
| `dstAbortDirty + Held + Both` | 228,975 | 28,593 |
| K3 identity | **holds exactly** | **fails** |

On #5 **every attempted migration commits** (`attempted == migrations` exactly, both dumps), while the
three destination-abort counters still tick. That is precisely the change `739bd2a` describes — "evict
D's PLRU way whatever its state": a dirty or client-held destination way is no longer a reason to
abort, it is probed and written back, so the abort counters became reason-tallies rather than abort
counts.

**This is hash-independent confirmation that #5 really is a 009-c2 build and #3 really is a 008-c2
build** — the two bitstreams have observably different abort semantics. It closes TASK trap 4 for both
without relying on a filename, and it means the pair genuinely straddles the 008→009 RTL change.

⚠️ It also means **K3 must not be applied as a health check to any 009 bitstream.** TASK §2 states K3
as a general identity; it is specific to 008-c2. Recorded in §4.1 below.

### 3.5 R3 (ON, 1st of boot B) — **completed, no wedge**

sha256 re-verified `afa13ca9b010…` at 13:31, immediately before reprogramming (the Vivado
`PROGRAM.FILE` line confirms the path actually loaded). Boot B via
`reboot_and_handover.exp -policy random`, which programs, boots, pushes the tools and sets the policy
but **runs no workload**, so R3 was the first and only workload run of its boot.

| stage | time |
|---|---|
| boot B launched | 13:31:22 |
| `>>> SERIAL PORT FREED <<<`, policy `random` set | 14:07:33 (36 min) |
| console attached by hand, fresh-boot state verified | 14:08:04 |
| benchmark copied by hand → `test_dir/` (`COPY_DONE_RC=0`) | 14:08:32 → 14:17:43 (9 min) |
| `--reset-all --migrate=on` rc=0, read-back `migrate : ON` / `policy : random` | 14:17–14:18 |
| `hb.sh` staged, echoed back verbatim | 14:18 |
| **R3 launched**, `ZERO_RC=0`, `HB t=0s` | **14:18:38** |
| **R3 finished** | **14:36:48** |

Fresh-boot state on arrival: uptime 920.9 s, `/mnt` mounted, `test_dir` present, **`migrate : OFF`** —
trap 1 observed directly: the switch really does reset to 0 on a fresh bitstream load, so the read-back
before an ON run is not optional. R3 used the **identical command sequence** to #3's R3, checked
against that log rather than from memory.

**`ITER_RC=0`, `ITER_SECS=1090`.** `wl.out` ends `End.` and `wl.err` is **0 bytes**, so omnetpp ran to
completion with nothing on stderr. **18 heartbeats, no gap longer than one 61 s interval**, including
through K7b's t=1 s–61 s window.

Liveness from `accessA` (trap 3), read from the log's own HB↔counter pairing: 0.57 M (t=0) → 55.1 M
(t=60) → 109.5 M (t=121) → 163.7 M (t=182) → 221.1 M (t=242) → 277.5 M (t=303) → 345.6 M (t=363) →
412.3 M (t=424) → 478.4 M (t=484) → 544.0 M (t=545) → 609.6 M (t=606) → 675.0 M (t=666) → 740.3 M
(t=727) → 804.9 M (t=787) → 864.4 M (t=848) → 924.1 M (t=908) → 983.7 M (t=969) → 1032.9 M (t=1030).
**Strictly monotonic, ~1.0 M/s, every interval.** `L2_Cycles ÷ 50e6` tracked the heartbeat clock to
within 1.3 s at all 18 beats.

| field | in-run dump at `HB t=1030s` | post-run dump ¹ |
|---|---:|---:|
| `L2_Cycles` | 51,528,829,748 | 56,779,474,238 |
| `L2_Cycles` ÷ 50e6 | **1030.58 s** vs heartbeat clock 1030 s ✓ | 1135.6 s ¹ |
| `accessA` | 1,032,896,025 | 1,035,750,240 |
| `primaryHit` | 692,476,015 | 694,179,114 |
| `secondaryHit` | 1,061,781 | 1,063,759 |
| `dataMiss` | 339,358,229 | 340,507,367 |
| `upgradeMiss` | 0 | 0 |
| `secondSearch` | 17,105,279 | 17,143,748 |
| `secondaryMiss` | 16,043,498 | 16,079,989 |
| **`memReads`** | **339,358,229** | 340,507,367 |
| **`memWrites`** | **70,608,271** | 70,971,806 |
| `memAcqPerm` | 0 | 0 |
| `memRelClean` | 268,749,958 | 269,535,561 |
| `migrations` | 4,703,653 | 4,714,545 |
| `attempted` | 4,703,653 | 4,714,545 |
| `aborted` (legacy) | 5,312 | 5,321 |
| `parked` (live) | 8 | 24 |
| `dstAbortDirty` | 14,650 | 14,673 |
| `dstAbortHeld` | 12,931 | 12,966 |
| `dstAbortBoth` | 1,161 | 1,164 |

¹ Taken ~45 s after the workload exited. The clean in-run check is the `HB t=1030s` column.

Identities on the in-run dump — `accessA = primaryHit + secondaryHit + dataMiss + upgradeMiss`
**exact**; `dataMiss + upgradeMiss = memReads + memAcqPerm` **exact**;
`secondSearch = secondaryHit + secondaryMiss` **exact**; `L2_Cycles ÷ 50e6` vs heartbeat clock 0.06%.
K3 fails again (`attempted − migrations = 0` vs abort sum 28,742), consistent with §3.4.

### 3.6 Verdict for #5 — **PASS**

R2 completed **and** R3 completed → **PASS** by the TASK §4 table. The hang did not reproduce on
`afa13ca9b010` on either path — not as the second run of a boot, not as the first run of a fresh boot.

| run | migrate | policy | `ITER_RC` | `ITER_SECS` | heartbeats | outcome |
|---|---|---|---:|---:|---:|---|
| R1 (OFF, 1st of boot A) | OFF ✓ | random ✓ | 0 | 1019 | n/a (script path) | completed |
| R2 (ON, 2nd of boot A) | ON ✓ | random ✓ | 0 | 1091 | 18 | completed |
| R3 (ON, 1st of boot B) | ON ✓ | random ✓ | 0 | 1090 | 18 | completed |

### 3.7 #5 vs #3

| run | #3 `e41f780c67ff` (008-c2) | #5 `afa13ca9b010` (009-c2) | delta |
|---|---:|---:|---|
| R1 OFF | 1017 s rc=0 | 1019 s rc=0 | +0.2% |
| R2 ON, 2nd of boot A | 1091 s rc=0 | 1091 s rc=0 | 0.0% |
| R3 ON, 1st of boot B | 1091 s rc=0 | 1090 s rc=0 | −0.1% |
| R2 `memReads` | 339,789,110 | 339,488,853 | −0.1% |
| R2 `memWrites` | 71,080,856 | 70,786,648 | −0.4% |
| R2 `migrations` | 4,387,279 | 4,924,564 | +12.2% |
| R2 `secondaryHit` | 999,651 | 1,106,816 | +10.7% |
| R2 `attempted − migrations` | 228,975 | **0** | K3 diverges (§3.4) |

The two builds are within 0.4% on time and memory traffic but differ sharply on migration behaviour —
009-c2 commits **12% more migrations** because destinations no longer abort on dirty/held ways, and
gets **11% more secondary hits** for it. Neither turns into a memory-traffic win at this size. This is
one pair, not an A/B campaign, and it is **not** what the task asked — recorded because it is the
cleanest 008-vs-009 comparison the session produced.

---

## 3A. Trial 3 — `7b83bcbc8f02` (`739bd2a`, unverified tree) — IN PROGRESS

#5 passed, so per the user's rule the search steps **up** to the next newer build. #6 is
`…SBCPLRU-739bd2a-unverified-tree-2026-09-22.bit`, sha256 re-verified `7b83bcbc8f02…` at 14:38
immediately before programming; file dated Sep 22 08:42, 27,609,952 bytes.

**This trial is inside the same-commit P&R group.** #5, #6 and #7 are three different place-and-route
runs of `739bd2a`. #5 has now passed. If #6 or #7 fails, the boundary lies **between two bitstreams of
the same RTL**, which is **timing / place-and-route evidence, not RTL evidence** — it must be reported
that way, per TASK §3 and the user's instruction not to average them.

### 3A.1 Timeline

| stage | time | state |
|---|---|---|
| boot A programmed (`PROGRAM.FILE` confirms #6) | 14:38:37 | ✓ |
| Linux up, 6 tools pushed, policy confirmed `random`, `/mnt` mounted | → ~15:06 | ✓ |
| benchmark copied → `test_dir/` | ~15:06–15:15 | ✓ |
| **R1** omnetpp, migrate OFF, random | 15:15 → **15:32:23** | ✓ **rc=0, 1018 s** |
| **R2** omnetpp, migrate ON, random, same boot | **launched 15:33:34** | **running** |
| **R4** omnetpp, migrate ON, **plru**, same boot — new, see §5C | pending | — |
| **R3** boot B, migrate ON, random, first run of the boot | pending | — |
| **R5** boot B, migrate ON, **plru**, second run — new | pending | — |

### 3A.2 R1 (OFF, random, 1st of boot A) — completed

`ITER_RC=0`, `ITER_SECS=1018`. From the script's `[SBC-DELTA]`:

| field | value | #3 R1 | #5 R1 |
|---|---:|---:|---:|
| `ITER_SECS` | **1018** | 1017 | 1019 |
| `L2_Cycles` ÷ 50e6 | **1017.7 s** (0.03% of `ITER_SECS`) ✓ | 1016.3 | 1018.3 |
| `accessA` | 1,016,806,889 | 1,015,399,773 | 1,018,624,582 |
| `primaryHit` | 682,629,325 | 680,972,432 | 684,713,845 |
| `dataMiss` | 334,177,564 | 334,427,341 | 333,910,737 |
| **`memReads`** | **334,177,564** | 334,427,341 | 333,910,737 |
| **`memWrites`** | **69,474,523** | 69,392,342 | 69,515,801 |
| `migrations` / `parked` | **0 / 0** ✓ | 0 / 0 | 0 / 0 |

Identities exact: `accessA = primaryHit + secondaryHit + dataMiss + upgradeMiss`;
`dataMiss + upgradeMiss = memReads + memAcqPerm`. All three OFF runs now agree within **0.2%** on
every counter — the three bitstreams are indistinguishable with migration off, as expected for one
netlist placed three ways.

### 3A.4 R2 (ON, random, 2nd of boot A) — **WEDGED. The hang reproduced.**

**This is the first wedge of this session, and it is on a single-core bitstream in `random` mode.**

| | |
|---|---|
| bitstream | **#6 `7b83bcbc8f02`** (`…739bd2a-unverified-tree-2026-09-22.bit`) |
| run | **R2** — ON, `random`, **2nd workload run of boot A** (R1 OFF completed first, 1018 s) |
| launched | 15:33:34, `ZERO_RC=0`, `migrate : ON` ✓, `policy : random` ✓ |
| last heartbeat | **`HB t=60s`** |
| wedged between | **t=60 s and t=121 s** |
| detected | 15:36:42 — no new heartbeat for 115 s against a 60 s interval |
| log | `chipyard/scripts/logs/uart_R2_bit6_20260924_153244.log` |

The last healthy heartbeat, `HB t=60s`, showed nothing wrong: `accessA` 55,050,548 (~900 k/s, normal),
`migrations` 941,569, `parked` 14, `L2_Cycles` 3,066,773,535 → **61.34 s against a heartbeat clock of
60 s** ✓, `dstAbortDirty/Held/Both` 65/72/2, `attempted == migrations` (009 semantics, §3.4). It was a
completely ordinary run one heartbeat before it died.

#### §6 capture — taken in full BEFORE any reprogramming (K7c)

| probe | result |
|---|---|
| **1. last heartbeats** | `HB t=0s` (accessA 538,528) → `HB t=60s` (accessA 55,050,548) → **nothing** |
| **2. `/root/sbc_read \| head -1` × 3**, ~25–30 s apart (15:38:39, 15:39:04, 15:39:29) | **0 bytes each time.** No counters readable at all |
| **3. Ctrl-C × 1** | **0 bytes. No `#` prompt, no echo** |
| **4. Enter × 3** | **0 bytes, no echo** |
| **5. UART log** | `chipyard/scripts/logs/uart_R2_bit6_20260924_153244.log` |

**Control check:** picocom was verified **alive** throughout the probing (PID 2723537, 5:19 elapsed,
still holding `/dev/ttyUSB2`), so the silence is the board and not a dead connection or a closed port.
The logfile stayed at **exactly 2,215 bytes** across ~3 minutes of probing — not one byte arrived after
the `HB t=60s` counter line.

**Reading:** this is the **K7c hard wedge**, reproduced exactly. The heartbeat is a *shell* loop
independent of the workload, and it stopped; no character echoes at all, so the kernel tty path cannot
run, i.e. the core cannot complete a cacheable access. It is not a slow workload and not a stuck
benchmark — the machine is gone below the OS. Only reprogramming recovers it.

**Verdict for #6: FAIL.** Under TASK §4, R2 wedging is a FAIL whichever way R3 goes; R3 only
distinguishes *whether a prior run in the same boot is required*.

### 3A.5 What this wedge overturns

Three statements made earlier in this session are now superseded. Recording them explicitly rather than
quietly editing them:

1. ~~"Neither documented hang matches the recipe this task prescribes"~~ (§5C.4) — **wrong as a
   conclusion about the recipe.** The prescribed recipe (single-core, `random`, ON, 2nd run of a boot)
   **does** reproduce the wedge. It just needed the right bitstream. §5C.4's factual table stands, but
   its inference — that the recipe could not reproduce either hang — is refuted by this run.
2. **PLRU is not required for the wedge.** This run was `policy : random`, confirmed by read-back and
   by `policy=random` in every dump. The user's PLRU hypothesis remains worth testing as a separate
   variable, but PLRU is no longer needed to explain a hang.
3. **Dual-core is not required either.** This is a single-core image.

### 3A.6 ~~The boundary is place-and-route, NOT RTL~~ — **RETRACTED. #5, #6 and #7 are ONE bitstream.**

**Retracted 2026-09-24 15:50.** I concluded from #5 PASS / #6 FAIL that the fault was timing /
place-and-route. **That was wrong, and so is the premise it rested on.** Prompted by the user asking
whether the stashed changes could have caused the stall, I compared the bitstreams byte by byte.

**All three candidates have byte-identical configuration data.** Skipping the 200-byte header:

```
ced7fdc79ee7e2d9375f18ca6992db1dc0f4f350eeac4f030e3805abbf14027a   #5  009-c2-739bd2a-2026-09-21.bit
ced7fdc79ee7e2d9375f18ca6992db1dc0f4f350eeac4f030e3805abbf14027a   #6  739bd2a-unverified-tree-2026-09-22.bit
ced7fdc79ee7e2d9375f18ca6992db1dc0f4f350eeac4f030e3805abbf14027a   #7  009-c2-739bd2a-clean-rebuild-2026-09-22.bit
```

`cmp -l` finds **7 differing bytes between #5 and #6, 6 between #5 and #7, 5 between #6 and #7** — every
one inside the header's embedded build timestamp, offsets 122–134:

| # | header date | header time |
|---|---|---|
| 5 | `2026/09/21` | `12:20:19` |
| 6 | `2026/09/22` | `08:42:58` |
| 7 | `2026/09/22` | `14:17:28` |

**#5, #6 and #7 are the same bitstream under three names.** The identical timing reports (WNS 0.433 ns,
TNS 0.000, 145,047 endpoints, WHS 0.010, WPWS 0.143 — equal to three decimals on every field) were the
first clue; the byte comparison settles it.

#### What this overturns

| earlier claim | status |
|---|---|
| "the fault is timing / place-and-route, not RTL" (§3A.6, 15:42) | **RETRACTED.** There is no P&R difference — there is no *difference* |
| TASK §3: "5, 6 and 7 are three DIFFERENT bitstreams built from the same commit — different place-and-route runs" | **FACTUALLY WRONG.** They are one build. TASK §3 warns of two byte-identical duplicate pairs; this is a **third duplicate group it missed**, and it is the one the search order was built around |
| "at most four trials", candidates #1–#7 | The list holds **5 distinct** single-core bitstreams, not 7. #3 ≡ `009-c1-2026-09-19` (TASK knew); **#5 ≡ #6 ≡ #7** (TASK did not) |
| my own "#5 PASS, #6 FAIL, adjacent, boundary found" | **There is no boundary.** Those are the same bits |

#### The actual finding: **the hang is non-deterministic on identical hardware**

The same configuration data, on the same board, with `migrate : ON`, `policy : random`, as the **2nd
workload run of a boot after an OFF run that parked nothing**:

| run | label | result |
|---|---|---|
| #5 R2 | ON, random, 2nd of boot A | **completed**, 1091 s, rc=0 |
| #5 R3 | ON, random, 1st of boot B | **completed**, 1090 s, rc=0 |
| #6 R2 | ON, random, 2nd of boot A | **WEDGED**, t=60–121 s, hard (§3A.4) |

**1 wedge in 3 ON runs of one bitstream.** Every controlled variable was equal between #5 R2 and #6 R2:
same bits, same board, same policy, same switch state (read back both times), same run position, same
`--zero` window, same staged `hb.sh`. The only uncontrolled variables are the ones nobody can hold
fixed — DRAM timing/refresh, first-touch page allocation, and whatever the kernel happened to be doing.

This also means the earlier §5C.2 record is consistent after all: the same RTL has now deadlocked twice
(prior session, PLRU), passed twice and wedged once (here, random). **It is one intermittent failure,
not several configuration-specific ones.**

#### Consequences for this task

1. **"The newest bitstream on which omnetpp still completes" is not answerable as posed** for this
   group. The newest distinct bitstream **both completes and wedges**.
2. **A single PASS establishes nothing.** The reproducibility worry raised at 14:40 is now measured, not
   suspected. #3's PASS (2 ON runs) is under exactly the same doubt.
3. **Rebuilding from `739bd2a` cannot help, and is provably a waste of hours.** #5 (built 09-21 from a
   clean tree) and #7 (built 09-22 from a clean tree after the reset) are byte-identical in
   configuration data — the build is **deterministic**, so another rebuild yields the same bits again.
4. **The stashed changes are empirically exonerated.** #6 was built 09-22 08:42 *while the tree was
   dirty*; #7 was built 09-22 14:17 *after* the stashes were taken. Their configuration data is
   identical. So the stashed doc / `sw/` / test-only Verilator-config changes had **zero** effect on the
   hardware — confirmed at the bit level, not merely by the source audit in §5B. (§5B's conclusion was
   right; this is the independent proof.)
5. **Only #7's trial is spared.** Testing #7 would be testing #5's bits a fourth time — useful only as
   another sample of the intermittency, not as a new point in the search.

### 3A.3 The PLRU runs added to this trial

Per the user's instruction (2026-09-24 15:20) and §5C: the F4/B9-1 deadlock was a **PLRU** run and this
task had tested only `random`. `L2_Replacement` (`0x490`) is documented safe to flip at any time, so
two PLRU runs fold into the boots already planned at **no extra boot cost** (~18 min each):

- **R4** — ON + `plru`, in boot A **after** R2. A wedge here costs nothing extra, because boot B needs
  a reprogram anyway.
- **R5** — ON + `plru`, in boot B **after** R3, i.e. as the second run of a boot, which is the K7
  condition.

Both keep #6's TASK verdict intact, because R1/R2/R3 still run exactly as §4 prescribes.

**R4's start-up differs from R2's, and must.** R2 began with `--reset-all --migrate=on`, which was legal
only because R1 (migrate OFF) parked nothing. R2 leaves ~20 lines parked, and `--reset-all` reads
`SBC_Parked` and **refuses** rather than orphaning them — the AT is the only record of where a parked
line's home set is (`sw/sbc_read.c:42-44`, guard at `:258-263`). So R4 uses:

- **no `--reset-all`** — `--zero` (`SBC_StatsReset`, `0x3B8`) only, which zeroes just the event counters
  and leaves `sat`/AT/DSS/`parkCount`/`nParked` alone. That is the sanctioned per-window reset inside a
  live SBC run.
- **no re-arm** — the migrate switch defaults OFF only at hardware reset, so it is still ON from R2's
  arm. Verified by read-back anyway (trap 1), never assumed.
- **a fresh picocom logfile.** Detaching picocom does not reboot the board, so boot A survives; a new
  logfile is required because the wedge watcher would otherwise match R2's own `ITER_RC=0` and report
  DONE instantly.

Consequence to keep in mind when reading R4: it inherits R2's parked lines and AT pairings, so it is a
run with **accumulated SBC state**, which amplifies rather than isolates the K7 "second run" condition. If either
PLRU run wedges, **capture §6 before reprogramming** (K7c). The clean single-variable follow-up would
be ON + `plru` as the **first** run of a fresh boot, on #5 — the bitstream F4 actually deadlocked on.

Trials used after this one: **3 of 4.** #7 is the only remaining budgeted trial.

---

## 3B. Trial 4 — #7 `7fb33ce6357c` (clean rebuild) + **the first PLRU run**

Chosen by the user as a clean starting position: built 2026-09-22 14:17:28 from a clean tree (after the
stashes at 13:41/13:43), own DRC + timing reports, and it is the artifact already in `generated-src`
(so `reboot_and_handover.exp`'s `DEFAULT_BIT` points at it). sha256 re-verified `7fb33ce6357c…` at
15:54 before programming. **Its configuration data is identical to #5 and #6** (§3A.6), so this trial
also samples the intermittency on the same bits.

| run | policy | position in boot | `ITER_RC` | `ITER_SECS` | heartbeats | outcome |
|---|---|---|---:|---:|---:|---|
| R1 (OFF) | random ✓ | 1st | 0 | **1019** | n/a (script) | completed |
| R2 (ON) | random ✓ | 2nd | 0 | **1091** | 18 | completed |
| **R4 (ON)** | **plru ✓** | 3rd | — | — | **1** | **WEDGED** |

R1 `[SBC-DELTA]`: `L2_Cycles` 50,957,978,072 → **1019.16 s** vs `ITER_SECS` 1019 (0.02%) ✓;
`accessA` 1,019,281,323 = `primaryHit` 684,937,272 + `dataMiss` 334,344,051 exactly; `migrations=0
parked=0` ✓. R2 final: `memReads` 340,053,055, `memWrites` 71,018,776, `migrations` 4,706,967,
`wl.out` ends `End.`, `wl.err` 0 bytes. R2's apparent flat heartbeats (HB#4, HB#14) were the known
one-beat watcher lag — the log's own pairing gives 163,279,818 at t=182 s and 804,196,690 at t=788 s,
strictly monotonic at ~1.0 M/s.

### 3B.1 The PLRU run — §6 capture

| | |
|---|---|
| launched | 17:09:34 — `PLRU_RC=0`, read-back **`migrate : ON`, `policy : plru`** (re-verified after the picocom reattach), `ZERO_RC=0` |
| heartbeats | **exactly one**, `HB t=0s`. Never reached t=60 s |
| wedged | **between t=0 s and t=60 s** — faster than the random wedge (t=60–121 s) |
| detected | 17:11:42, no heartbeat for 111 s |
| `sbc_read \| head -1` × 3 (17:12:53, 17:13:18, 17:13:43) | **0 bytes each** |
| Ctrl-C × 1 | **0 bytes, no prompt** |
| Enter × 2 | **0 bytes, no echo** |
| log | `chipyard/scripts/logs/uart_R4plru_bit7_20260924_170907.log`, frozen at **724 bytes** |
| control | picocom verified alive throughout (PID 2808969) — the silence is the board |

No `--reset-all` was used (R2 left 29 lines parked; the guard would refuse). `--zero` only, and the
migrate switch was not re-armed — it was still ON from R2 and was read back, not assumed.

### 3B.2 The tally on one set of bits

Every row below is the **same configuration data** (#5 ≡ #6 ≡ #7):

| run | policy | position | result |
|---|---|---|---|
| #5 R2 | random | 2nd of boot | ✓ 1091 s |
| #5 R3 | random | 1st of boot | ✓ 1090 s |
| #6 R2 | random | 2nd of boot | ✗ **WEDGED** t=60–121 s |
| #7 R2 | random | 2nd of boot | ✓ 1091 s |
| #7 R4 | **plru** | 3rd of boot | ✗ **WEDGED** t=0–60 s |

**random: 3 pass / 1 wedge. plru: 0 pass / 1 wedge.** Adding the prior F4/B9-1 report (§5C.2), which
was PLRU and hung on **both** attempts: **PLRU 0 / 3, random 3 / 4.**

### 3B.3 Why PLRU is the stronger suspect — a mechanism consistent with the data

The first heartbeat of each run, ~0.7 s in, shows how differently the two policies drive the
destination path:

| at `HB t=0s` | R2 (random) | R4 (**plru**) |
|---|---:|---:|
| `parked` | 21 | 71 |
| `secondaryHit` | 2,507 | 5,355 |
| `dstAbortDirty` | **0** | **1,875** |
| `dstAbortHeld` | **0** | **2,676** |
| `dstAbortBoth` | **0** | **204** |

In a 009 PLRU build those three counters are **not** aborts — `attempted == migrations` exactly
(11,411 = 11,411), so nothing aborted. Per the stashed 009 REPORT they count **W evictions by reason**:
a destination way that was dirty and/or client-held, which 009 C2 now **probes and writes back**
instead of declining. The RTL reason is §5C.3: `Directory.scala:211` disables the `preferEvictable`
tier under PLRU, so the destination probe takes D's recency way, which is very often dirty or held.

So **the 009 destination-eviction path runs ~4,755 times in the first 0.7 s under PLRU, and 0 times
under random** at the same point. Over a full run random does exercise it (#6 R2 showed 65/72/2 by
t=60 s) — roughly **two orders of magnitude less**.

⚠️ **Hypothesis consistent with every observation so far, not proven:** the wedge is a rare race in the
009 destination-eviction path (probe + write-back of a dirty or client-held W into the fenced
destination row), and the observed hang rate tracks how often that path runs. PLRU raises the exposure
~100×, so PLRU hangs almost immediately (0/3) while random survives most runs (3/4). This is exactly
the mechanism the prior investigation suspected as **NEST-D / B9-1** ("dirty write-back into fenced row
D"), and `MSHR.scala:513-517` already carries the invariant *"never wait on the L1 while holding the
destination fence"*.

**Caveats, stated plainly:** n is small (4 random, 1 PLRU today). Today's PLRU run was the **3rd** run
of its boot and started with 71 parked lines against R2's 21, so run position and accumulated SBC state
are confounded with policy. The prior F4 runs' position in their boots is not recorded.

**The clean next experiment** is PLRU as the **first** run of a fresh boot — no accumulated state, no
prior run — which removes both confounds. If it wedges there, policy is isolated.

---

## 3C. Trial 5 (user-directed) — the clean PLRU test, and a permanent fix to the boot cost

Two things asked for at 17:20:

1. **Reboot and run `migrate=on` + `plru` ONLY** — no OFF run first, so PLRU is the **first and only
   workload run of a fresh boot**. This removes both confounds from §3B.3: no prior run, and no
   accumulated parked lines (a fresh boot has `parked=0`). If it wedges here, **policy is isolated**.
2. **Cache the tool binaries on `/mnt`** so they no longer have to be ZMODEMed from the host.

### 3C.1 Why dropping the OFF run is safe here

TASK §4 insists R1 (OFF) runs before R2 because "the OFF run parks nothing, and that is the only thing
that makes `--reset-all` legal before R2." That rationale is about **not having a prior ON run that
parked lines** — and a freshly programmed board has parked nothing at all. `SBC_MigrateEnable` defaults
to 0 at reset, so nothing can have migrated. `parked` is verified to be 0 from the read-back before
arming, so `--reset-all` is legal on exactly the grounds §4 gives. Dropping R1 loses only the
same-boot OFF timing baseline, which four runs have already pinned to 1017–1019 s.

### 3C.2 The `/mnt` tool cache — kills ~9 min per boot

The board rootfs is volatile, so every boot has re-pushed 6 binaries over a 115200 baud line with no
flow control (~9.3 kB/s, ~9 min). `/dev/mmcblk0p2` is persistent — it is where the benchmark data
already lives. Caching the tools there replaces the ZMODEM step with a local `cp`.

Files cached to `/mnt/damith_vcu118/tools/`: `sbc_read`, `run_sbc_window.sh`, `l2_miss_calib`, `cyc`,
`pe`, `csrprobe` (the full `CANDIDATES` list from `reboot_and_handover.exp:49-56`). The user asked for
`sbc_read`; copying all six costs nothing extra and removes the step entirely.

**Order matters:** the copy happens **after boot but before the workload run**, because the run may
wedge the board and a wedge makes the SD card unreachable.

Expected saving from the next boot onward: **~9 min per boot**, ~25% of the per-boot cost. The
benchmark copy into `test_dir` (~9 min) remains, since that is a local SD→rootfs copy already.


### 3C.3 RESULT — **PLRU alone wedges the board. Policy is isolated.**

Run log: `chipyard/scripts/logs/uart_PLRUonly_bit7_20260924_184122.log`

| | |
|---|---|
| bitstream | #7 `7fb33ce6357c` (≡ #5 ≡ #6) |
| boot | fresh, programmed 18:05:27, handover done 18:41:08 |
| pre-arm state | **`migrations=0`, `parked=0`** — verified, so `--reset-all` was legal on exactly §4's grounds |
| arm | `--reset-all --migrate=on` rc=0 → read-back **`migrate : ON`**, **`policy : plru`** |
| run | **first and only workload run of the boot**, `ZERO_RC=0`, launched **18:51:46** |
| heartbeats | **exactly one** (`HB t=0s`) |
| wedged | **between t=0 s and t=60 s** |
| detected | 18:53:59 (no heartbeat for 115 s) |

#### §6 capture

| probe | result |
|---|---|
| Enter × 2 | **0 bytes** |
| Ctrl-C × 1 | **0 bytes**, no prompt |
| `/root/sbc_read \| head -1` × 3 (18:55:08 / :33 / :58) | **0 bytes each** |
| log size | frozen at **2,095 bytes** — nothing arrived after the first heartbeat |
| picocom | alive throughout (14:34 elapsed) — the silence is the board |

**Confirmed a hard SBC wedge, not the MMC stall** (§4.4): zero character echo and **no hung-task
output**. The MMC stall keeps the kernel alive and printing; this does not.

#### Why this run settles the question

The two confounds that weakened the 17:09 PLRU wedge are both gone:

| confound | 17:09 run | **this run** |
|---|---|---|
| prior workload run in the boot | 3rd run (R1 OFF, R2 ON preceded it) | **none — first run** |
| accumulated SBC state at start | 71 parked lines | **`parked=0`, `migrations=0`** |
| policy | plru | plru |

Nothing was carried in, and it still wedged inside 60 s. **PLRU is sufficient on its own.**

#### The tally

| policy | pass | wedge | detail |
|---|---:|---:|---|
| **random** | **3** | **1** | #5 R2, #5 R3, #7 R2 passed; #6 R2 wedged t=60–121 s |
| **plru** | **0** | **4** | prior F4/B9-1 ×2 (§5C.2); #7 R4 t=0–60 s; **this run t=0–60 s** |

All seven runs are the **same configuration data** (#5 ≡ #6 ≡ #7, §3A.6). The user's hypothesis
(2026-09-24 15:20, "maybe PLRU might have caused those deadlocks") is **supported by every run taken
since**: PLRU 0/4, and all four wedges arrived fast.

#### Mechanism — now with a clean data point

At `HB t=0s` (0.71 s in) this run had already recorded, with `attempted == migrations` exactly
(7,211 = 7,211, so **no aborts** — these are **W evictions by reason**, §3B.3):

| counter | this PLRU run @ t=0s | random @ t=0s |
|---|---:|---:|
| `dstAbortDirty` | 1,160 | **0** |
| `dstAbortHeld` | 1,639 | **0** |
| `dstAbortBoth` | 139 | **0** |
| **total destination evictions** | **2,938 in 0.71 s** | **0** |

So the 009 C2 destination-eviction path — probe and write back a dirty or client-held destination way —
runs thousands of times per second under PLRU and essentially never under random at the same point
(`Directory.scala:211`: `evictTier = preferEvictable && !io.usePlru`). **The wedge rate tracks exposure
to that path**: PLRU saturates it and hangs 4/4; random touches it ~100× less and survives 3/4.

⚠️ Still a hypothesis about the *mechanism*, though the *policy* dependence is now measured: the
suspected race is **NEST-D / B9-1**, a dirty write-back into the fenced destination row, with
`MSHR.scala:513-517` already carrying the invariant *"never wait on the L1 while holding the
destination fence"*. Confirming that needs RTL work, not more board runs.

#### What this means for task 009

009 C2's headline change is exactly this path, and PLRU is the mode 009 was written for
(`ai-documents/coder/009-…/REPORT.md`: *"Drop random mode… a PLRU build never aborts at the
destination"*). **On hardware, a PLRU build does not survive 520.omnetpp_r with migration ON at all** —
0 for 4. The sim gate was green (stress 8/8), so this is a board-only failure that the Verilator gate
does not catch.


## 3D. Trial 6 (user-directed, 2026-09-24 21:37) — **#3 under PLRU: PASS.** The control the bisection lacked

Run log: `chipyard/scripts/logs/uart_PLRUonly_bit3_20260924_212536.log`
Boot log: `$SP/BOOT_plru_bit3.log`

Asked for at 20:26 after an RTL review of `739bd2a` predicted this run would pass. It is the run
REPORT §12 ranked first: **#3 (008-c2) with `policy=plru`, migration ON, first and only workload run of
a fresh boot** — the exact configuration in which #5/#6/#7 wedged 4 times out of 4.

### 3D.1 Board state found at the start (not what the record said)

The session summary said the board was wedged from the 18:51 PLRU run. It was not. Between then and
20:26 the board had been **reprogrammed with the Dual-core image**
(`FPGADualRocketVCU118L18K64K16WL2ConfigSBCPLRU-2026-09-18.bit`, session log
`board_session_20260924-192148.log`, programmed 19:21) and a live `picocom` (PID 2980563, started
20:09, log `uart_20260924_200932.log`) was attached at a board prompt with an `l2_miss_calib` run
finished at 20:13. **The user was asked before the board was taken** and confirmed at 20:27. Recorded
because §5C's provenance rule applies to this trial too: the board was not in the state the log implied.

### 3D.2 Setup — every §4 precondition verified, not assumed

| step | evidence | time |
|---|---|---|
| sha256 re-verified **before** programming | `e41f780c67ff2da2e60ff55c59f6750d0ea60e9fbfd9d18e36d06cb58711d177` — matches §1 | 20:28 |
| `-bit` passed explicitly | `reboot_and_handover.exp`'s `DEFAULT_BIT` points at generated-src (= #7); the default would have re-run the wrong bitstream | 20:29 |
| boot health | 0 panics / oops / BUG / hung_task / "could not get prompt"; one `login:`; 6/6 tools staged; `MOUNT` ok; log 206,575 B (healthy band 205–211 KB) | 21:24:32 |
| `Setting policy=plru`, `SERIAL PORT FREED` | both present | 21:24:32 |
| uptime at first probe | 959.87 s — genuinely fresh, no prior workload | 21:25 |
| **pre-arm state** | **`migrations=0 attempted=0 parked=0`**, `migrate : OFF`, `policy : plru`, `dstAbort*` 0/0/0 | 21:26 |
| benchmark staged | `cp -r /mnt/...` → `COPY_DONE_RC=0`; binary 5,239,864 B, `WL_OK=0` | 21:35:52 |
| arm | `--reset-all --migrate=on` → `ARM_RC=0` | 21:36 |
| **read-back** | `migrate : ON`, `policy : plru` — read from **lines 2–3**, not `head -1` (REPORT §9.3) | 21:36 |
| `hb.sh` staged | echoed back byte-identical to trials 1–5 | 21:37 |
| launch | `--zero` → `ZERO_RC=0` | **21:37:21** |

`--reset-all` was legal on exactly §4's grounds: `parked=0` was **read back and verified**, not inferred.

### 3D.3 RESULT — **`ITER_RC=0 ITER_SECS=1029`**

17 heartbeats, no stall at any point. `wl.out` ends `Calling finish() at end of Run #0... End.`
(733 B); `wl.err` **0 bytes**. Post-run read-back still `migrate : ON`, `policy : plru`.

| | #3 PLRU (this run) | #3 random R2 | #3 random R3 |
|---|---:|---:|---:|
| `ITER_RC` | **0** | 0 | 0 |
| `ITER_SECS` | **1029** | 1091 | 1091 |
| `accessA` | 993,997,740 ¹ | 1,035,058,029 | 1,037,219,431 |
| `primaryHit` | 703,296,066 ¹ | 694,269,268 | 695,938,023 |
| **`memReads`** | **288,561,901** ¹ | 339,789,110 | 340,309,949 |
| **`memWrites`** | **58,691,518** ¹ | 71,080,856 | 71,297,535 |
| `migrations` | 4,531,530 ¹ | — | — |
| `dstAbortDirty / Held / Both` | 11,627 / 9,836 / 351 ¹ | 115,541 / 103,090 / 10,344 | 114,771 / 104,529 / 10,515 |

¹ last in-run sample, `HB t=969s` (94.2% of the run). `L2_Cycles=48,474,597,743 ÷ 50e6 = 969.49 s`,
which matches the heartbeat label — so the sample is where it claims to be.

**Reads and writes are reported separately** per the standing rule. PLRU takes both down against the
same bitstream's random runs (reads −15.1%, writes −17.6% at 94% of the run), and the run is 5.7%
shorter — consistent with 008's "PLRU alone −3.56% cycles / −12.65% memory traffic".

#### Identity checks — all five exact

| identity | value | ok |
|---|---|---|
| `accessA = primaryHit + secondaryHit + dataMiss + upgradeMiss` | 993,997,740 = 993,997,740 | ✓ |
| `dataMiss + upgradeMiss = memReads + memAcqPerm` | 288,561,901 = 288,561,901 | ✓ |
| `secondSearch = secondaryHit + secondaryMiss` | 16,708,155 = 16,708,155 | ✓ |
| `L2_Cycles ÷ 50e6 ≈ ITER_SECS` | 969.49 s at the `t=969s` beat | ✓ |
| **K3** `attempted − migrations = dstAbortDirty + Held + Both` | **21,814 = 21,814** | ✓ |

**K3 holding exactly is independent proof this is a 008-c2 build**, not a hash argument: the TASK
records that K3 holds under 008-c2 semantics and **fails by design on 009**, where every attempt
commits. On #5/#6/#7 it failed, here it holds.

### 3D.4 What this settles, and what it does not

**Settles:** PLRU is *not* sufficient on its own to wedge the board. §3C.3 concluded "PLRU alone wedges
the board — policy is isolated". That conclusion was drawn from four wedges **all on one bitstream**
(#5 ≡ #6 ≡ #7). Run on a genuinely different RTL, the same policy, the same workload, the same
first-run-of-a-fresh-boot protocol completes. **§3C.3's wording is too strong and is corrected here:
what is isolated is `policy=plru` *on the 009-c2 build*.**

**The revised tally, split by RTL — the split §3C.3 could not make:**

| build | policy | pass | wedge |
|---|---|---:|---:|
| #3 `e41f780c67ff` (008-c2) | random | 2 | 0 |
| #3 `e41f780c67ff` (008-c2) | **plru** | **1** | **0** |
| #5≡#6≡#7 (009-c2, `739bd2a`) | random | 3 | 1 |
| #5≡#6≡#7 (009-c2, `739bd2a`) | **plru** | **0** | **4** |

**The mechanism split, measured in the same 0.7 s window.** At `HB t=0s` both builds had done
comparable SBC work — #3: 7,767 migrations / 548,806 accesses; #7: 7,211 / 570,845 — but:

| `0x498`/`0x4A0`/`0x4A8` at `t=0s` | #3 (008-c2) | #7 (009-c2) |
|---|---:|---:|
| meaning of the registers | destination **aborts** | destination **W evictions** |
| value | **0 / 0 / 0** | **2,938 total** |

Over #3's whole run those registers reached 21,814 — so 008-c2 *did* meet dirty and client-held
destination ways 21,814 times and **declined every one of them**. It never probed a destination way and
never Released one. 009-c2 evicts them, which is the code path this trial did not exercise, and it is
the only build that wedges.

**Does not settle:** one PLRU run is one sample. #5/#6/#7 passed 3 of its first 4 random runs before
wedging, so a single #3 PLRU pass cannot prove #3 never wedges. It does break the claim that PLRU alone
is sufficient, and it makes the pass/fail boundary fall between two bitstreams of **different, known**
provenance for the first time — which is what the "don't name a commit" rule in TASK §7 requires.

### 3D.5 Trial budget

This is trial **6**, past TASK §7's "at most four". Trials 5 and 6 were both directed by the user after
the original budget was spent, and the budget's purpose (limit board time spent guessing) no longer
applies: this run was a stated prediction from an RTL review, not a guess.

## 4. Deviations, contradictions and incidents

Everything here belongs in `REPORT.md` §8.4.

### 4.1 TASK internal contradictions found

1. **§4.2's `migrate : ON` check cannot work as written.** It says to confirm the switch from
   `` `/root/sbc_read | head -1` ``, but `show()` in [sw/sbc_read.c](../../../sw/sbc_read.c) prints the
   `[SBC-COUNTERS] key=value…` one-liner **first** (sbc_read.c:148-151) and `migrate : ON/OFF` on
   **line 2** (sbc_read.c:152). `head -1` therefore never shows the migration state.
   **Worked around by** `grep`/`head -3` on the read-back before every ON run. Not a measurement risk,
   but the recipe needs correcting.
2. **§4 requires `--zero` on every run; §4.1's prescribed R1 path omits it.** TASK §4 mandates
   `--zero` on all three runs, but the sanctioned R1 command is
   `tmp.exp -hangtest migoff -iters 1`, and [tmp.exp:543](../../../../../scripts/ssbc_scripts/tmp.exp#L543)
   wraps the workload as `(cd $STAGE/$OMNET_SET && /root/sbc_read -- $raw)` — **no `--zero`**.
   R1's counters are therefore absolute-since-boot. **Handled by** reporting R1 from the script's own
   `[SBC-DELTA]` (before/after difference), which brackets the run to 0.07% of `ITER_SECS`. R1's
   absolute dumps are in the log if the raw values are ever needed.

3. **K3 is presented as a general identity but is 008-c2-specific.** TASK §2 gives
   `attempted − migrations = dstAbortDirty + dstAbortHeld + dstAbortBoth` as a fact to check. It holds
   exactly on #3 (008-c2) and **fails by construction on #5** (009-c2, `739bd2a`), where every attempt
   commits and the abort counters became reason-tallies — see §3.4. **Do not use K3 as a health check on
   a 009 bitstream**; a "failure" there is the expected new semantics, not a broken run. Used the other
   way round it is valuable: it tells 008 and 009 builds apart from the counters alone.

### 4.2 Method notes

- **Manual heartbeat, not the script's detector**, for every ON run (TASK §4.3). The workload prints
  nothing between start and finish, so silence proves nothing; only the heartbeat line disappearing
  proves the shell stopped executing.
- **The board rootfs is volatile.** Every boot re-pushes the 6 tool binaries over ZMODEM (~9.3 kB/s)
  and re-copies the benchmark. That is ~35 min of setup **per boot**, so one bitstream costs
  ~1 h 40 m for both boots. This is the dominant cost of the whole bisection and the reason the trial
  budget is four.
- **One tmux session per bitstream** (G1). Session in use: `hang010`.
- **Every command sent to the board pane goes through `send.sh`**, which sends `C-u` first to clear the
  input line. See the incident below for why.

### 4.3 Incidents

- **Stray board relaunch, 11:08:40 — cost ~10 min, no recorded data lost.** After
  `reboot_and_handover.exp` exited, bash still held an un-submitted history line
  (`./tmp.exp -hangtest migoff -bit … -iters 1`). My next `send-keys` text was **appended** to it,
  producing `-iters 1clear; picocom …` (the relaunched header showed `iters=1clear`). That re-ran
  `tmp.exp`, **reprogrammed the FPGA, and destroyed the boot B I had just spent 35 min staging.**
  R1/R2 were already complete and R3 had not started, so no recorded data was lost. **Fix:** all
  sends now go through `send.sh`, which clears the line first. Boot B was redone
  (`BOOTB2_bit3.log`).
- **Inherited stalled run cleared at ~09:16.** A previous session's `run_test.exp`/`tmp.exp`/`picocom`
  stack was live in tmux `bisection`, stuck 12 min at "[3/5] connecting to Linux console" with 0 bytes
  from the board. Killed the stack and the session and reprogrammed in `hang010`; the board booted
  normally. **This was a stale interrupted boot, not a bitstream fault** — it must not be read as
  evidence against any bitstream.
- **Three false positives in my own log detectors**, all fixed before any data was taken, recorded so
  the next session does not repeat them: (a) matching `Contents of /mnt`, which appears in the
  *echoed command text*, not the output; (b) matching `ITER_RC=` against the literal `ITER_RC=$?`
  inside the staged `hb.sh` — the pattern must require digits, `ITER_RC=[0-9]+`; (c) matching
  `STAGING_OK` from the command echo, which reported staging done in 5 s when it really took 9 min —
  fixed by requiring ≥2 occurrences (echo + real output); (d) matching `handover` in
  `reboot_and_handover.exp`'s **startup banner** ("handover mode : exit picocom -> …"), which reported
  boot B complete 14 s after launch — the real completion marker is `>>> SERIAL PORT FREED <<<`
  (reboot_and_handover.exp:291). Separately, `tr` block-buffers in a pipe and silently swallowed
  events; use `grep -aE --line-buffered` directly.

  (e) **my own `hbwatch.sh` display lagged by one heartbeat.** `hb.sh` prints the `HB t=` line *before*
  its `sbc_read` output; the watcher polls every 5 s, so when it caught the gap between the two it
  reported the *previous* heartbeat's counters. On #5 R3 that made HB#7/#8 and HB#17/#18 print identical
  `accessA` — which reads exactly like a 60 s core stall and did not happen. The wedge test itself
  (counting `^HB t=` lines) was never affected, and the logfile holds the correct pairing.
  **Always re-derive `accessA` progress from the log by pairing each `HB t=` with the counter line that
  follows it, never from the monitor's own summary.** Caught before anything was recorded.

  **Pattern behind all five:** these logs echo the command and the script's own banner text, so any
  marker that also appears in a banner, a prompt echo or a staged script body will fire early. Match
  the *terminal* marker, anchor it, and require digits where a value is expected. And a monitor that
  samples a two-line report can split it — pair the lines in the log, not in the sampler.

---

## 5. Provenance limit to respect when concluding

TASK: **do not name a commit as the cause** unless the pass/fail boundary lies between two bitstreams
from **different** commits with **known** provenance.

- **#4 (`3bea9add4cb8…`) has no matching commit.** If the boundary lands at #4, the report must say
  what is missing rather than guess.
- **#5, #6, #7 are all commit `739bd2a`** — three P&R runs of one RTL. A split verdict among them is
  **timing/place-and-route evidence, not RTL evidence**, and each must be reported on its own row.

---

## 5B. Provenance audit of #5 / #6 / #7 — are they really the same RTL?

**Question raised by the user, 2026-09-24:** "unverified tree" in #6's filename means the build may have
come from a tree with uncommitted changes, so the claim that #5/#6/#7 share RTL cannot rest on the
filename. **Answer: verified — same RTL. The dirt was docs, `sw/`, and one test-only Verilator config;
no RTL.** Evidence chain below.

### 5B.1 The archive note — a claim, not a verification

`…-739bd2a-unverified-tree-2026-09-22.bit.NOTE.txt` (written 2026-09-22 13:44:37):

> archived pre-existing unverified build (built 2026-09-22 08:10-08:43, **origin unknown**, tree had
> uncommitted doc/sw changes at build time **but no RTL diffs**) before starting a clean rebuild from
> 739bd2a

The note asserts "no RTL diffs" but also "origin unknown" — its author did not make the build. So the
claim needed independent checking, which is what follows.

### 5B.2 The L2 RTL (this generator, 26 `.scala` files) — unchanged since the commit

| check | result |
|---|---|
| `git diff 739bd2a -- '*.scala'` | **empty** — every RTL file matches the commit today |
| newest `.scala` mtime | `MSHR.scala` **2026-09-21 10:19**, `Scheduler.scala` 09-21 09:28 — both **before** the commit (09-21 11:39:39) |
| generator `stash@{0}` (taken 09-22 **13:41:02**, the reset point) | 7 files, **no `.scala` at all**: `CLAUDE.md`, 4 × `ai-documents/*`, `sw/sbc_read.c` |

The mtime argument is the strong one: **no RTL file has been written since 09-21 10:19**, ~22 h before
#6's build. An edit-then-revert would have left a later mtime, and none exists.

### 5B.3 rocket-chip — untouched

Clean working tree, HEAD `72690b07` dated 2024-08-15, every `src/**/*.scala` mtime 2026-04-26. Not a
factor.

### 5B.4 The one real gap, and how it closes

`generators/chipyard/src/main/scala/config/RocketConfigs.scala` — the file that sets `nWays`,
`capacityKB`, `plruReplacement`, `sbcAutoMigrate` — has mtime **2026-09-22 13:43**, i.e. **after** #6's
08:10–08:43 build window. It is clean now, so it was **reverted** at that moment; its content during
#6's build was therefore *not* the committed content.

That mtime is exactly when chipyard's `stash@{0}` was taken (**13:43:00**). Its complete diff is
**+22 lines, 0 deletions**, adding one new class:

```
+class VerilatorRocket8KL116KL2NestDConfig extends Config(
+  … WithL1DCacheWays(8) … WithInclusiveCache(nWays = 8, capacityKB = 4,
+      subBankingFactor = 2, sbcAutoMigrate = true, plruReplacement = true, sbcForceDstSet = 6) …
```

a **test-only Verilator** config for the NEST-D / B9-1 hunt (4 KB L2, forced destination set 6, 8-way
L1). It does **not** touch `SingleRocketVCU118L18K64K16WL2ConfigSBCPLRU` — the class the VCU118 FPGA
config delegates to — nor `WithInclusiveCache`'s defaults. **Adding a Scala class cannot change the
elaboration of a different class**, so the FPGA config elaborated identically with or without it.

Also checked: `fpga/src/main/scala/vcu118/Configs.scala` clean, mtime 09-18 09:23. And a tree-wide
search for anything modified in 09-22 07:00–09:30 returned **only the `.bit` itself plus two sbt
`streams/out` files** — no source file was written during the build window.

### 5B.5 Corroboration from the bitstreams themselves

Real build times, read from the Xilinx `.bit` headers (not file dates, which are copy times):

| # | sha256 | header build time | bytes |
|---|---|---|---|
| 5 | `afa13ca9b010` | **2026-09-21 12:20:19** | 27,609,952 |
| 6 | `7b83bcbc8f02` | **2026-09-22 08:42:58** | 27,609,952 |
| 7 | `7fb33ce6357c` | **2026-09-22 14:17:28** | 27,609,952 |

All three are **byte-identical in size**, consistent with one netlist placed three ways, and the header
times confirm the TASK's build order **independently of the filenames**. (For reference the other
candidates' header times are: #1 09-16 17:52:32, #2 09-16 22:14:20, #3 09-17 19:40:51, #4 09-19
01:18:14 — and `009-c1-2026-09-19.bit` carries #3's exact header time and size, corroborating TASK §3's
byte-identical-duplicate warning.)

### 5B.6 Residual limit — state it plainly

**#6 emitted no build log and no utilization/timing report.** Its build artifacts were overwritten by
#7's clean rebuild: `fpga/generated-src/…FPGASingleRocketVCU118L18K64K16WL2ConfigSBCPLRU/obj/VCU118FPGATestHarness.bit`
now hashes to `7fb33ce6357c` = **#7**. So the RTL-identity argument rests on **repository state**
(diff + mtimes + stash contents), not on an artifact emitted by #6's own build. The one check that
would be direct — comparing post-synthesis instance counts from #6's utilization report — is impossible
because that report does not exist. The conclusion is well supported but indirect, and #6 keeps the
label "unverified tree" for that reason.

⚠️ Note for whoever runs the board next: `reboot_and_handover.exp`'s `DEFAULT_BIT` points at that
generated-src path, i.e. **#7**. Always pass `-bit` explicitly. Every run in this session did.

---

## 5C. Two contradictions found in the record — both material

### 5C.1 K7b and K7c come from the **Dual-core** bitstream, which §3 excludes

The TASK's anchor fact — K7b, "REPRODUCED 2026-09-24 08:10 … on the same bitstream, run 1 completed
(`ITER_SECS=1033`); run 2 … wedged between t=1s and t=61s" — cites heartbeat log
`uart_20260924_074558.log`. Tracing what was on the board for that log:

| evidence | finding |
|---|---|
| `board_session_20260924-065703.log`, 06:57 → **07:44:58** | programmed `FPGADualRocketVCU118L18K64K16WL2ConfigSBCPLRU-2026-09-18.bit` — the **Dual** image |
| `uart_20260924_074558.log`, **07:45:58** → 08:10 | picocom only; **no programming between 07:44 and 08:58** (no session or Vivado log exists in that gap) |
| marker order inside that log | `ITER_SECS=245` → `migrate : ON`/`policy : random` → omnetpp → **`ITER_SECS=1033`** (run 1 completed) → omnetpp again → **`HB t=1s`** → silence = the wedge |

So **the only reproduction of the wedge, the K7c "0 bytes from the board" probe, and the TASK's
"a healthy run is 1033 s" reference are all from the Dual-core image** — while every candidate in §3 is
single-core, and §3 itself says to "run single-core images only and ignore the Dual image except as the
known-bad reference."

All four early-09-24 sessions (03:08, 04:46, 05:18, 06:57) used the Dual image, giving `ITER_SECS`
1028–1033. That explains the reference mismatch noted in §2.5: 1033 s is a **Dual** number, and my
single-core runs are 1017/1019 s (OFF) and 1090/1091 s (ON) — it was never supposed to match either.

**Consequence for this task:** the bisection is searching single-core bitstreams for a failure whose
only reproduction is on a dual-core bitstream that is excluded from the search. If the wedge is
core-count-dependent, the answer to "the newest single-core bitstream on which omnetpp completes" may
be "all of them", and the real variable would be core count, not commit.

### 5C.2 The prior single-core `739bd2a` hang was in **PLRU** mode — a configuration this task never tested

**Corrected 2026-09-24 15:20** (user raised it; earlier text in this file called it a contradiction of
my #5 PASS — it is not, it is a *different experiment*).

The generator's `stash@{0}` (created 2026-09-22 13:41:02, HEAD `739bd2a`) records:

> Sim gate is green on this commit (stress 8/8) but **the board hard-deadlocks on 520.omnetpp_r with
> migration ON** (two attempts stuck at "Setting up network 'largeNet'...", Ctrl-C dead; migration OFF
> completes in 16.4 min). This is bug B9-1 / 009 finding F4.

The stash's own supporting transcript, `ai-documents/tmp.md` (the l2_miss_calib A/B the stash message
cites), shows **`policy=plru` on every single counter dump** — 8 dumps, `policy : plru` throughout. So
the F4/B9-1 deadlock was observed with the replacement policy set to **PLRU**.

**Every run in this task, on every bitstream, used `policy=random`** — TASK §4 mandates it. So my
#3 and #5 PASSes do **not** refute F4; they test a configuration that was never reported to hang.

### 5C.3 Why the policy matters — verified against the live RTL, not the doc

The stashed 009 REPORT claims (Q3): *"The MSHR does not read the policy… A PLRU build evicts D's way W
whatever `L2_Replacement` says. Random mode is not tested (V1 dropped)."* Checked against the RTL as it
stands today:

| RTL | what it does | policy-dependent at run time? |
|---|---|---|
| `MSHR.scala:308` — `val evictW = params.micro.plruReplacement && params.micro.enableSetBalancing` | gates 009's "evict D's way whatever its state" | **No** — a Scala compile-time constant. Never reads `L2_Replacement` |
| `Directory.scala:195` — `policyOH = Mux(io.usePlru, UIntToOH(r.io.victimWay), lfsrVictimOH)` | picks the victim way | **Yes** — PLRU recency way vs LFSR way |
| `Directory.scala:211` — `evictTier = preferEvictable && !io.usePlru` | the tier that may offer a **parked** way as the victim | **Yes — and it is random-only.** Disabled entirely when PLRU is on |

Two consequences:

1. **009's destination-eviction change is live in random mode too**, because it is gated on the build
   flag, not the register. This is independently confirmed by my own measurement: on #5 in *random*
   mode, `attempted == migrations` exactly with the abort counters still ticking (§3.4). So random mode
   is not "the old behaviour".
2. **But which way gets evicted, and whether a parked way can be the victim at all, both change with
   the register.** Under PLRU the `preferEvictable` tier is off and the victim is the recency way. That
   is a materially different destination path — different probe and write-back traffic — which is
   exactly the kind of difference that decides whether a narrow deadlock window is hit.

So PLRU is not a cosmetic knob here, and the F4 report sits squarely in the untested half.

### 5C.4 Neither documented hang matches the recipe this task prescribes

| hang on record | cores | policy | run position | tested by this task? |
|---|---|---|---|---|
| **F4 / B9-1** (09-21→22), `739bd2a`, 2 attempts, "Ctrl-C dead" | **Single** | **plru** | omnetpp + migration ON | **No** — task mandates random |
| **K7b / K7c** (09-24 08:10), 09-18 image, wedged t=1–61 s of run 2 | **Dual** | random | 2nd run of a boot | **No** — §3 excludes the Dual image |
| what I ran on #3 and #5 (5 ON runs total) | Single | random | 2nd run of boot A **and** 1st of boot B | yes — **all completed** |

**This is the central methodological finding.** TASK §4 fixes policy at `random` and §3 restricts the
candidates to single-core images, but the two hangs on record are single-core **PLRU** and **Dual**
random. The prescribed recipe therefore cannot reproduce either one, which is consistent with what
happened: five ON runs across two bitstreams, no wedge, on a machine where a wedge was reproduced
twice on 09-24 and twice on 09-21/22.

⚠️ It follows that **"the newest bitstream on which omnetpp still completes" cannot be answered from
random-mode single-core runs alone.** A PASS in this configuration is evidence about this
configuration only. The next runs should hold everything else fixed and flip `L2_Replacement` to
`plru`, which is the one documented difference between "completes" and "hard deadlock" on single-core
`739bd2a`.

---

## 6. Log path index

Every run's log, per G4. Paths under `$SP` are
`/tmp/claude-1000/-home-damith-…-inclusive-cache/90250370-a111-4441-97e7-2fe7b519b40b/scratchpad`
(session scratchpad).

| run / stage | log |
|---|---|
| #3 boot A + R1 (script path) | `$SP/R1_bit3.log` |
| #3 boot A board session (script's own) | `chipyard/scripts/logs/board_session_20260924-091637.log` |
| #3 **R2** (ON, manual heartbeat) | `chipyard/scripts/logs/uart_R2_bit3_20260924_101014.log` |
| #3 boot B — **aborted** by the stray relaunch (§4.3) | `$SP/BOOTB_bit3.log` |
| #3 boot B — redone | `$SP/BOOTB2_bit3.log` |
| #3 **R3** (ON, first run of boot B) | `chipyard/scripts/logs/uart_R3_bit3_20260924_114548.log` |
| #5 boot A + R1 | `$SP/R1_bit5.log` · script transcript `chipyard/scripts/logs/board_session_20260924-121530.log` |
| #5 **R2** (ON, manual heartbeat) | `chipyard/scripts/logs/uart_R2_bit5_20260924_131016.log` |
| #5 boot B | `$SP/BOOTB_bit5.log` · handover transcript `chipyard/scripts/logs/handover_20260924-133121.log` |
| #5 **R3** (ON, first run of boot B) | `chipyard/scripts/logs/uart_R3_bit5_20260924_140804.log` |
| #6 boot A + R1 | `$SP/R1_bit6.log` |
| #6 **R2** (ON, random) — **WEDGED**, §6 capture inside | `chipyard/scripts/logs/uart_R2_bit6_20260924_153244.log` |
| #7 boot A + R1 | `$SP/R1_bit7.log` |
| #7 **R2** (ON, random) — completed | `chipyard/scripts/logs/uart_R2_bit7_20260924_164854.log` |
| #7 **R4** (ON, **plru**) — **WEDGED**, §6 capture inside | `chipyard/scripts/logs/uart_R4plru_bit7_20260924_170907.log` |

Helper scripts written for this task, in `$SP`: `send.sh` (line-clearing `send-keys` wrapper),
`stage_hb.sh` (writes the §4.3 heartbeat script to `/root/hb.sh` one short `echo` at a time, because
the UART has no flow control), `hbwatch.sh` (implements the §5 wedge test by watching heartbeat growth
in the picocom logfile).

---

## 7. What is still owed

- [x] #3 `e41f780c67ff` — **PASS** (R2 1091 s, R3 1091 s, **R6 plru 1029 s** §3D).
- [x] #5 `afa13ca9b010` — **PASS** on random (R2 1091 s, R3 1090 s).
- [x] #6 `7b83bcbc8f02` — **FAIL**, wedged t=60–121 s, §6 captured.
- [x] #7 `7fb33ce6357c` — **PASS** on random (R2 1091 s); **WEDGED on plru** t=0–60 s, §6 captured.
- [x] Provenance audit of #5/#6/#7 (§5B) and the byte-identity finding (§3A.6).
- [x] **Trial 5: PLRU as the first and only workload run of a fresh boot — WEDGED in <60 s** on
      #5≡#6≡#7; §6 captured (§3C.3).
- [x] **Trial 6: the same run on #3 (008-c2) — COMPLETED, `ITER_RC=0`, 1029 s** (§3D). Corrects §3C.3:
      what is isolated is `policy=plru` **on the 009-c2 build**, not PLRU in general.
- [x] `REPORT.md` updated for trial 6: §0, §1, §3.1 (R6 column), §4, §6.1, §7, §11, §12, and a new
      **§13 RTL review** of `739bd2a` with six suspected defects and their fixes.
- [ ] **`REPORT.md`** — the deliverable. Must carry: the §7 per-run table; the two §6 wedge captures;
      **the byte-identity finding (#5 ≡ #6 ≡ #7) and that it makes "the newest bitstream that
      completes" unanswerable as posed**; the intermittency tally (random 3/4, plru 0/3 with the prior
      F4 report); the PLRU mechanism hypothesis (§3B.3); and every TASK error found:
      1. §3's "#5/#6/#7 are three different place-and-route runs" — **they are one bitstream**.
      2. §2's K3 identity stated as general — it is **008-c2-specific** and fails by design on 009.
      3. §4.2's `sbc_read | head -1` check for `migrate : ON` — **cannot work**; `migrate` is on line 2.
      4. §4 mandates `--zero` on every run, but §4.1's prescribed R1 path (`tmp.exp`, line 543) omits it.
      5. K7b/K7c and the "1033 s healthy run" reference are from the **Dual-core** image, which §3
         excludes from the candidate list.
- [ ] Optional but recommended: a `NOTE.txt` beside #5 and #6 in `bitstream_storage/` recording that
      they are byte-identical to #7, so no future session repeats this bisection. Not done — awaiting
      the user's go-ahead, since it writes into the archive.

### Open questions this session could not settle

1. ~~Is the wedge PLRU-specific or just PLRU-accelerated?~~ **ANSWERED, then REFINED.** §3C.3 read
   "PLRU alone is sufficient" from 4 wedges that were all on one bitstream. Trial 6 (§3D) ran the same
   configuration on #3 and it **completed**, so PLRU is *not* sufficient on its own. The fault is one
   whose rate tracks how hard the **009 destination-eviction path** is driven, and that path exists
   only on `739bd2a`. PLRU is the accelerant, not the cause.
1b. **Is 008-c2 actually safe under PLRU?** One pass is not a rate — `739bd2a` passed 3 of its first 4
   before wedging. **3 more PLRU ON runs on #3** is now the top recommendation (REPORT §12).
2. **What is the random-mode wedge rate?** One wedge in four runs is too few samples to act on.
3. **Does #3 (008-c2, genuinely different RTL) ever wedge?** Two ON runs passed, but that is the same
   sample size that made #5 look clean before #6 wedged. Until #3 has ~4+ ON runs, "008 is safe" is
   unsupported — and it is the only claim that would make this an RTL bisection rather than a
   noise measurement.
