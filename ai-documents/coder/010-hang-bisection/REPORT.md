# 010 — hang bisection: REPORT

**Task:** [TASK.md](TASK.md) — *find the newest bitstream on which `520.omnetpp_r` still completes.*
**Run:** 2026-09-24, 09:16 → 18:57. One board (VCU118), one tmux session (`hang010`).
**Working log with the raw trail:** [PROGRESS.md](PROGRESS.md). This report is self-contained.

> **Measuring, not fixing.** No `.scala` was edited, no `git checkout` was run, no test was tweaked to
> make a run pass. Every failure below is reported as a finding. Read-only git commands
> (`diff`, `stash show`, `log`) were used for the provenance audit in §8.

---

## 0. Summary

| # | finding |
|---|---|
| **1** | **Candidates #5, #6 and #7 are ONE bitstream.** Their configuration data is byte-identical; only the 6-byte embedded build timestamp differs. TASK §3's premise that they are "three different place-and-route runs" is wrong. |
| **2** | **The question is not answerable as posed.** The newest distinct bitstream **both completes and wedges**. It passed 3 of 4 ON runs in `random` and failed all 4 in `plru`. There is no "newest bitstream that completes". |
| **3** | **The replacement policy is the dominant variable *on the `739bd2a` build*.** On those bits `plru` is **0 pass / 4 wedge** and `random` **3 pass / 1 wedge**, with both confounds removed (PLRU wedged as the **first and only** workload run of a fresh boot, `parked=0, migrations=0` verified beforehand). But PLRU is **not sufficient on its own**: the same policy, workload and protocol on **#3 `e41f780c67ff` (008-c2) completed — `ITER_RC=0`, 1029 s** (§3.3). |
| **4** | **A commit boundary now exists, but on a thin sample.** TASK §8 allows naming a commit only when the pass/fail boundary lies between bitstreams of *different* commits with known provenance. After the #3 PLRU run that condition is met for the first time: **008-c2 passes PLRU 1/1, 009-c2 (`739bd2a`) fails it 4/4.** The suspect is the 009 C2 change. It is **not proven** — #5≡#6≡#7 passed 3 of its first 4 random runs before wedging, so one #3 pass cannot establish safety (§12). |
| **5** | **Mechanism (hypothesis, now with a control):** the wedge rate tracks how hard the **009 C2 destination-eviction path** is driven. On `739bd2a` PLRU runs it ~2,938 times in the first 0.71 s and `random` **0** times. On **#3 in the same window it is 0** — 008-c2 *declines* a dirty or client-held destination way instead of evicting it (21,814 declines over the full run), so it never probes or Releases one, and it completes. |
| **6** | **Five substantive errors found in TASK.md** — see §9. |
| **7** | **An RTL review of `739bd2a` is in §13**, with six suspected defects and fixes. The top one is provable by reading one line: the new destination-eviction path is gated on a **compile-time** flag, not the runtime `L2_Replacement` register, so **random mode runs it too** — which contradicts the task's own decision U2. |

**Practical consequence for task 009:** on hardware, the **`739bd2a`** build does not survive
`520.omnetpp_r` with migration ON in PLRU mode — **0 for 4**, always wedging inside 60 s. PLRU is the
mode 009 was written for, and its Verilator gate was green (stress 8/8). **The sim gate does not catch
this.** The 008-c2 build passes the identical run, so the fault is in what 009 added, not in PLRU.

---

## 1. The answer to the question asked

**"The newest bitstream on which omnetpp still completes" has no well-defined answer**, for two reasons
established below:

1. The three newest candidates are **the same bitstream** (§2), so they cannot be ordered against each
   other.
2. That bitstream **both completes and wedges** under identical, controlled conditions (§3, §5).

The closest true statements that can be made from the evidence:

- The newest **distinct** bitstream is `ced7fdc79ee7…` (configuration-data hash), archived under three
  names: #5 `afa13ca9b010`, #6 `7b83bcbc8f02`, #7 `7fb33ce6357c`, all from commit **`739bd2a`**.
  With `policy=random` it completed **3 of 4** ON runs (1090–1091 s); with `policy=plru` it completed
  **0 of 4**.
- The only candidate that never wedged is **#3 `e41f780c67ff`** (008 C2 WIP on `7494296`+), now on
  **3** ON runs: 2 `random` (1091 s) and **1 `plru` (1029 s, `ITER_RC=0`)** — the last one in the exact
  configuration that wedges `739bd2a` 4 times out of 4. That is still a thin sample (the same size that
  made #5 look clean before #6 wedged), so **#3 is not established as safe** — see §12.

---

## 2. The decisive finding — #5, #6 and #7 are one bitstream

Prompted by the user asking whether stashed changes could have affected the build, the three `.bit`
files were compared directly.

**sha256 of the configuration data only** (skipping the 200-byte header):

```
ced7fdc79ee7e2d9375f18ca6992db1dc0f4f350eeac4f030e3805abbf14027a   #5  …009-c2-739bd2a-2026-09-21.bit
ced7fdc79ee7e2d9375f18ca6992db1dc0f4f350eeac4f030e3805abbf14027a   #6  …739bd2a-unverified-tree-2026-09-22.bit
ced7fdc79ee7e2d9375f18ca6992db1dc0f4f350eeac4f030e3805abbf14027a   #7  …009-c2-739bd2a-clean-rebuild-2026-09-22.bit
```

`cmp -l` finds **7 differing bytes (#5↔#6), 6 (#5↔#7), 5 (#6↔#7)** — every one at offsets 122–134,
inside the Xilinx header's date/time fields:

| # | full sha256 (file) | header build time | size |
|---|---|---|---|
| 5 | `afa13ca9b0107f15533bb01ff54cab85894e02de0f514fab8e9fc37b8d8734c9` | `2026/09/21 12:20:19` | 27,609,952 |
| 6 | `7b83bcbc8f028db264e350ff3b1a0809df11ce304d62deecb2d9ba3f6a3ddb6c` | `2026/09/22 08:42:58` | 27,609,952 |
| 7 | `7fb33ce6357c532065abdaa5fb98cd69675bd8204411308144ece73a4218033d` | `2026/09/22 14:17:28` | 27,609,952 |

Corroborating: #5's and #7's timing reports are identical to three decimals on every field — WNS
**0.433 ns**, TNS 0.000, 145,047 endpoints, WHS 0.010, WPWS 0.143 — and both state *"All user specified
timing constraints are met."* Both DRC reports show the same 54 violations.

### What follows

- **The build is deterministic.** #5 (clean tree, 09-21) and #7 (clean tree, 09-22) are bit-identical,
  so **rebuilding `739bd2a` produces these same bits again**. A rebuild cannot be used to "get a
  different result".
- **TASK §3's search order is void for this group.** It warns of two byte-identical duplicate pairs
  (`#3 ≡ 009-c1-2026-09-19`, and the two 256 KB files) but asserts #5/#6/#7 are *different* P&R runs.
  They are not. The list contains **5 distinct** single-core bitstreams, not 7.
- **An earlier conclusion in this work was retracted.** On seeing #5 PASS / #6 FAIL, the boundary was
  first reported as timing/place-and-route. That is wrong: there is no P&R difference because there is
  no difference. The split is **intermittency within one bitstream**.

---

## 3. Every run — TASK §7 fields

`memReads` and `memWrites` are reported separately throughout, per TASK. "±" columns are the §7
identity checks. Policy and migrate are the **read-back** values, never what was requested.

### 3.1 #3 — `e41f780c67ff2da2e60ff55c59f6750d0ea60e9fbfd9d18e36d06cb58711d177`
`FPGASingleRocketVCU118L18K64K16WL2ConfigSBCPLRU-008-c2B-7494296-wip-2026-09-17.bit` · commit **`201ebae`** (008 C2) — proven from archived blob hashes, §8.4. Also archived, byte-identically, as `…009-c1-2026-09-19.bit`, which is mislabelled (§8.5)

| field | R1 (OFF, 1st of boot A) | R2 (ON, 2nd of boot A) | R3 (ON, 1st of boot B) | **R6 (ON, only run of a fresh boot)** |
|---|---:|---:|---:|---:|
| migrate / policy | OFF / random | **ON** / random | **ON** / random | **ON** / **plru** |
| `ITER_RC` | 0 | 0 | 0 | **0** |
| `ITER_SECS` | **1017** | **1091** | **1091** | **1029** |
| `L2_Cycles` | 50,815,729,214 | 55,697,074,643 ¹ | 55,572,100,116 ¹ | 48,474,597,743 ² |
| ÷ 50e6 | 1016.3 s | 1113.9 s ¹ | 1111.4 s ¹ | 969.49 s ² |
| `accessA` | 1,015,399,773 | 1,035,058,029 | 1,037,219,431 | 993,997,740 ² |
| `primaryHit` | 680,972,432 | 694,269,268 | 695,938,023 | 703,296,066 ² |
| `secondaryHit` | 0 | 999,651 | 971,459 | 2,139,773 ² |
| `dataMiss` | 334,427,341 | 339,789,110 | 340,309,949 | 288,561,901 ² |
| `upgradeMiss` | 0 | 0 | 0 | 0 ² |
| **`memReads`** | **334,427,341** | **339,789,110** | **340,309,949** | **288,561,901** ² |
| **`memWrites`** | **69,392,342** | **71,080,856** | **71,297,535** | **58,691,518** ² |
| `memAcqPerm` | 0 | 0 | 0 | 0 ² |
| `memRelClean` | 265,034,999 | 264,320,975 | 264,651,714 | 225,338,853 ² |
| `migrations` | 0 | 4,387,279 | 4,360,700 | 4,531,530 ² |
| `attempted` | 0 | 4,616,254 | 4,590,515 | 4,553,344 ² |
| `aborted` | 0 | 233,531 | 234,182 | 28,461 ² |
| `parked` | 0 | 26 | 31 | 69 ² |
| `secondSearch` | 0 | 15,959,266 | 15,674,272 | 16,708,155 ² |
| `secondaryMiss` | 0 | 14,959,615 | 14,702,813 | 14,568,382 ² |
| `dstAbort` D/H/B | 0/0/0 | 115,541/103,090/10,344 | 114,771/104,529/10,515 | 11,627/9,836/351 ² |
| heartbeats | n/a (script path) | 21 | 20 | **17, none missed** |
| **outcome** | **completed** | **completed** | **completed** | **completed** |

² R6's column is the last **in-run** sample, `HB t=969s` (94.2% of the run), not a post-run dump — the
run was left to finish rather than interrupted. `L2_Cycles ÷ 50e6 = 969.49 s` against a heartbeat clock
of 969 s, so the sample is where it claims to be. Its totals are therefore ~6% short of end-of-run and
must not be differenced against R2/R3's post-run dumps without that correction; the **rates** and the
identities are unaffected. Added 2026-09-24, trial 6 (PROGRESS §3D) — full setup evidence there.

¹ post-run dump, ~23 s after the workload exited. The clean in-run check is the `HB t=1030s`
heartbeat: `L2_Cycles` 51,522,953,381 (R2) and 51,523,479,843 (R3) → **1030.46 s / 1030.47 s** against a
heartbeat clock of 1030 s. R1 is the script's own `[SBC-DELTA]`, which brackets the run: 1016.3 s vs
`ITER_SECS` 1017 (0.07%).

**Identities — all exact on all three runs:** `accessA = primaryHit + secondaryHit + dataMiss +
upgradeMiss`; `dataMiss + upgradeMiss = memReads + memAcqPerm`; `secondSearch = secondaryHit +
secondaryMiss`.
**K3 (`attempted − migrations = dstAbortDirty + Held + Both`): holds exactly** — 228,975 = 228,975 (R2),
229,815 = 229,815 (R3) and **21,814 = 21,814 (R6)**. This independently confirms 008-c2 abort semantics
from the counters alone, without trusting the filename (TASK trap 4). It matters most for R6: K3 fails
by design on 009, so its holding here proves the PLRU control really ran on 008-c2 RTL.

### 3.2 #5 / #6 / #7 — all `ced7fdc79ee7…`, commit `739bd2a`

All seven runs below are **the same configuration data** (§2). Grouped by the name each was programmed
from, so the provenance is auditable.

| run | file | migrate / policy | position in boot | `ITER_RC` | `ITER_SECS` | HBs | outcome |
|---|---|---|---|---:|---:|---:|---|
| R1 | #5 | OFF / random | 1st | 0 | 1019 | n/a | completed |
| R2 | #5 | **ON** / random | 2nd | 0 | **1091** | 18 | completed |
| R3 | #5 | **ON** / random | **1st of a fresh boot** | 0 | **1090** | 18 | completed |
| R1 | #6 | OFF / random | 1st | 0 | 1018 | n/a | completed |
| **R2** | **#6** | **ON** / random | 2nd | — | — | **2** | **WEDGED** t=60–121 s |
| R1 | #7 | OFF / random | 1st | 0 | 1019 | n/a | completed |
| R2 | #7 | **ON** / random | 2nd | 0 | **1091** | 18 | completed |
| **R4** | **#7** | **ON** / **plru** | 3rd | — | — | **1** | **WEDGED** t=0–60 s |
| **R5** | **#7** | **ON** / **plru** | **1st of a fresh boot** | — | — | **1** | **WEDGED** t=0–60 s |

#### OFF runs (`[SBC-DELTA]`, brackets the run exactly)

| field | #5 R1 | #6 R1 | #7 R1 |
|---|---:|---:|---:|
| `ITER_SECS` | 1019 | 1018 | 1019 |
| `L2_Cycles` ÷ 50e6 | 1018.3 s (0.07%) | 1017.7 s (0.03%) | 1019.2 s (0.02%) |
| `accessA` | 1,018,624,582 | 1,016,806,889 | 1,019,281,323 |
| `primaryHit` | 684,713,845 | 682,629,325 | 684,937,272 |
| `dataMiss` | 333,910,737 | 334,177,564 | 334,344,051 |
| **`memReads`** | **333,910,737** | **334,177,564** | **334,344,051** |
| **`memWrites`** | **69,515,801** | **69,474,523** | **69,313,749** |
| `migrations` / `parked` | 0 / 0 | 0 / 0 | 0 / 0 |

All four OFF runs across all bitstreams (incl. #3's 1017 s) agree within **0.2 % on every counter**.
With migration off the machine is completely repeatable; **all the variance lives in the ON runs.**

#### Completed ON runs (in-run dump at `HB t=1030s`, except #7 R2 which is post-run)

| field | #5 R2 | #5 R3 | #7 R2 ² |
|---|---:|---:|---:|
| `L2_Cycles` ÷ 50e6 | 1030.55 s | 1030.58 s | — |
| `accessA` | 1,032,049,347 | 1,032,896,025 | 1,034,596,470 |
| `primaryHit` | 691,453,678 | 692,476,015 | 693,475,936 |
| `secondaryHit` | 1,106,816 | 1,061,781 | 1,067,479 |
| `dataMiss` | 339,488,853 | 339,358,229 | 340,053,055 |
| `upgradeMiss` | 0 | 0 | 0 |
| **`memReads`** | **339,488,853** | **339,358,229** | **340,053,055** |
| **`memWrites`** | **70,786,648** | **70,608,271** | **71,018,776** |
| `memAcqPerm` | 0 | 0 | 0 |
| `memRelClean` | 268,702,205 | 268,749,958 | 269,034,279 |
| `migrations` | 4,924,564 | 4,703,653 | 4,706,967 |
| `attempted` | 4,924,564 | 4,703,653 | 4,706,967 |
| `aborted` | 5,012 | 5,312 | 5,477 |
| `parked` | 22 | 8 | 29 |
| `secondSearch` | 17,812,712 | 17,105,279 | 17,195,553 |
| `secondaryMiss` | 16,705,896 | 16,043,498 | 16,128,074 |
| `dstAbort` D/H/B | 14,512/12,898/1,183 | 14,650/12,931/1,161 | 14,674/13,069/1,137 |

² #7 R2's figures are the post-run dump; its in-run heartbeat sequence was verified monotonic
(`accessA` 163,279,818 at t=182 s → 804,196,690 at t=788 s, ~1.0 M/s).

**Identities — exact on every completed ON run.** `L2_Cycles ÷ 50e6` matched the heartbeat clock to
within 0.06 % wherever an in-run dump exists.

**K3 fails on all of these, by design:** `attempted − migrations = 0` (every attempt commits) while the
abort counters still tick (28,593 / 28,742 / 28,880). See §9.2 — K3 is 008-c2-specific, and this
divergence is itself the hash-independent proof that #3 is a 008 build and these are 009 builds.

---

## 4. Verdicts (TASK §4 rule)

| bitstream | sha256 | commit | R2 | R3 | **verdict** |
|---|---|---|---|---|---|
| #3 | `e41f780c67ff` | **`201ebae`** (008 C2) — proven, §8.4 | completed | completed | **PASS** (random ×2; **plru ×1** ⁴) |
| #5 | `afa13ca9b010` | `739bd2a` | completed | completed | **PASS** |
| #6 | `7b83bcbc8f02` | `739bd2a` | **WEDGED** | not run ³ | **FAIL** |
| #7 | `7fb33ce6357c` | `739bd2a` | completed | not run ³ | **PASS** (random) / **FAIL** (plru) |

³ **R3 was deliberately not run on #6 or #7, and the verdict does not need it.** Under TASK §4, R2
wedging is a FAIL either way; R3 only distinguishes *whether a prior run in the same boot is required*.
That question was answered better by other runs: #5 R3 completed as the **first** run of a fresh boot,
and the PLRU run (§5.3) **wedged** as the first run of a fresh boot. So "needs a prior run in the boot"
is disproved directly.

⁴ Added 2026-09-24: #3 also completed a **PLRU** run (`ITER_RC=0`, 1029 s) as the first and only
workload run of a fresh boot — §6.1. That is the configuration `739bd2a` fails 4/4.

⚠️ **These four rows are only three distinct bitstreams, and rows #5/#6/#7 are the same one.** Read as
a bisection the table appears to show a boundary between #5 and #6; it does not (§2). **The real
boundary is between #3 and the #5/#6/#7 group**, and it is visible only once policy is held at `plru`
on both sides (§6.1).

---

## 5. The three wedges — TASK §6 captures

All three were captured **before** any reprogramming (K7c). In every case picocom was verified alive
during probing, so the silence is the board and not a dead connection.

### 5.1 Wedge 1 — #6 R2, migration ON, **random**, 2nd run of boot A
Log: `chipyard/scripts/logs/uart_R2_bit6_20260924_153244.log` · launched 15:33:34 · detected 15:36:42

Last healthy heartbeat `HB t=60s` showed nothing wrong: `accessA` 55,050,548 (~900 k/s), `migrations`
941,569, `parked` 14, `L2_Cycles` 3,066,773,535 → **61.34 s against a 60 s clock**, `memReads`
24,554,814, `memWrites` 3,070,619, `dstAbort` 65/72/2. It died one heartbeat later.

| probe | result |
|---|---|
| `/root/sbc_read \| head -1` × 3 (25–30 s apart) | **0 bytes each** |
| Ctrl-C × 1 | **0 bytes, no `#` prompt** |
| Enter × 3 | **0 bytes, no echo** |
| log size across ~3 min of probing | **2,215 bytes, unchanged** |
| picocom | alive (PID 2723537, 5:19 elapsed) |

### 5.2 Wedge 2 — #7 R4, migration ON, **plru**, 3rd run of boot A
Log: `chipyard/scripts/logs/uart_R4plru_bit7_20260924_170907.log` · launched 17:09:34 · detected 17:11:42

**Exactly one heartbeat** (`HB t=0s`), never reached t=60 s. At that point: `migrations` 11,411,
`attempted` 11,411, `parked` 71, `accessA` 494,337, `dstAbort` **1,875/2,676/204**.

| probe | result |
|---|---|
| `sbc_read` × 3 (17:12:53 / :13:18 / :13:43) | **0 bytes each** |
| Ctrl-C × 1 | **0 bytes, no prompt** |
| Enter × 2 | **0 bytes, no echo** |
| log size | frozen at **724 bytes** |
| picocom | alive (PID 2808969) |

### 5.3 Wedge 3 — #7, migration ON, **plru**, **FIRST AND ONLY run of a fresh boot**
Log: `chipyard/scripts/logs/uart_PLRUonly_bit7_20260924_184122.log` · launched 18:51:46 · detected 18:53:59

**This is the controlled run.** Before arming: **`migrations=0`, `parked=0`** verified from the
read-back — nothing had ever migrated on this boot, which is exactly what makes `--reset-all` legal on
§4's own grounds. Read-back after arming: **`migrate : ON`**, **`policy : plru`**. `ZERO_RC=0`.

**Exactly one heartbeat** (`HB t=0s`): `migrations` 7,211, `attempted` 7,211, `parked` 15, `accessA`
570,845, `primaryHit` 301,809, `secondaryHit` 3,843, `dataMiss` 265,193, `memReads` 265,193,
`memWrites` 59,166, `L2_Cycles` 35,637,950 → 0.71 s, `dstAbort` **1,160/1,639/139**.

| probe | result |
|---|---|
| Enter × 2 | **0 bytes** |
| Ctrl-C × 1 | **0 bytes, no prompt** |
| `sbc_read` × 3 (18:55:08 / :33 / :58) | **0 bytes each** |
| log size | frozen at **2,095 bytes** |
| picocom | alive (14:34 elapsed) |

### 5.4 The wedge signature, and how to tell it from an SD stall

All three are the **K7c hard wedge**: the heartbeat is a *shell* loop independent of the workload and
it stopped; **zero character echo** means the kernel tty path cannot run, i.e. the core cannot complete
a cacheable access. Only reprogramming recovers it.

⚠️ **A different failure exists on this board and must not be confused with it.** At 17:58 an SD-card
write (`cp` into `/mnt` + `sync`) stalled the MMC subsystem: `kworker` blocked in
`mmc_rescan → mmc_get_card → __mmc_claim_host`, `sync` blocked in `wb_wait_for_completion`. **There the
kernel stays alive** — Enter echoes and hung-task diagnostics print. Per the user, that stall
**recovers on its own and must not be met with a reprogram**. The discriminator is the probe sequence:

| | SBC wedge (K7c) | MMC/SD stall |
|---|---|---|
| Enter / Ctrl-C | **0 bytes, no echo** | echoes normally |
| kernel messages | none | hung-task backtraces printed |
| recovery | **reprogram only** | waits itself out |

---

## 6. The policy is the dominant variable — **on `739bd2a`, not in general**

Every run in the first table is the **same configuration data** (`ced7fdc79ee7…`).

| policy | pass | wedge | runs |
|---|---:|---:|---|
| **random** | **3** | **1** | #5 R2 ✓, #5 R3 ✓, #7 R2 ✓, #6 R2 ✗ |
| **plru** | **0** | **4** | prior F4/B9-1 ×2 ✗, #7 R4 ✗, #7 R5 ✗ |

⚠️ **Read this table with §6.1.** It was first written when every PLRU run in existence was on these
bits, which made "policy is the variable" and "the 009 build is the variable" indistinguishable. They
are now distinguishable, and the split runs the other way.

The two prior PLRU failures are from the generator's `stash@{0}` (created 2026-09-22 13:41:02, HEAD
`739bd2a`): *"the board hard-deadlocks on 520.omnetpp_r with migration ON (two attempts stuck at
'Setting up network largeNet...', Ctrl-C dead; migration OFF completes in 16.4 min) — bug B9-1 / 009
finding F4."* Its own supporting transcript (`ai-documents/tmp.md` in that stash) shows **`policy=plru`
on all 8 counter dumps**, which is how that report's policy was established.

**Wedge 3 removes both confounds** that weakened wedge 2:

| confound | wedge 2 (17:09) | **wedge 3 (18:51)** |
|---|---|---|
| prior workload run in the boot | 3rd run of the boot | **none — first run** |
| accumulated SBC state at start | 71 parked lines | **`parked=0`, `migrations=0`** |
| policy | plru | plru |
| result | wedged <60 s | **wedged <60 s** |

**PLRU alone, from a clean boot, with nothing carried in, is sufficient to wedge the board** — *on this
bitstream*. It is not *necessary*: `random` wedged once in four. So on `739bd2a` this is one fault whose
**rate** depends on policy.

### 6.1 The control run — PLRU on a different commit completes

Added 2026-09-24 21:37 (trial 6, PROGRESS §3D), after an RTL review predicted it. Same policy, same
workload, same protocol — **first and only workload run of a fresh boot**, `parked=0 migrations=0`
verified before arming, read-back `migrate : ON` / `policy : plru`:

| build | commit | policy | result |
|---|---|---|---|
| #5 ≡ #6 ≡ #7 `ced7fdc79ee7…` | `739bd2a` (009 C2) | plru | **wedged, 4 of 4** |
| **#3 `e41f780c67ff`** | 008 C2 WIP (`7494296`+) | **plru** | **`ITER_RC=0`, `ITER_SECS=1029`** |

So the split by RTL, which the table above could not make:

| build | policy | pass | wedge |
|---|---|---:|---:|
| #3 (008-c2) | random | 2 | 0 |
| #3 (008-c2) | **plru** | **2** ⁵ | **0** |
| #5≡#6≡#7 (009-c2) | random | 3 | 1 |
| #5≡#6≡#7 (009-c2) | **plru** | **0** | **4** |

**This corrects the earlier conclusion.** "PLRU alone wedges the board — policy is isolated" was drawn
from four wedges that were all on one bitstream. What is isolated is **`policy=plru` on the 009-c2
build**. PLRU on 008-c2 completes, and completes *faster* than the same bitstream's random runs
(1029 s vs 1091 s; reads −15.1%, writes −17.6%), which is what task 008 measured for PLRU alone.

⁵ **Second PLRU pass added 2026-09-24 ~23:05** (run by the user on the same boot, same bitstream):
**`ITER_RC=0 ITER_SECS=1030`**, against 1029 s for the first — 1 s apart, so the result is repeatable,
not a fluke. 009 is now 0/4 against 008-c2's 2/2 in the identical configuration.

**Sample-size caveat, stated plainly:** two PLRU passes on #3 do not prove #3 never wedges. `739bd2a`
passed 3 of its first 4 `random` runs before wedging. What the run does establish is that PLRU is not
sufficient on its own, and that the pass/fail boundary now lies between two bitstreams of **different,
known** provenance — the condition TASK §8 sets before a commit may be named.

---

## 7. Mechanism — a hypothesis the data supports

At `HB t=0s` (~0.7 s into the run), with `attempted == migrations` **exactly** in both cases (so
nothing aborted):

| counter at t=0 s | `739bd2a` **plru** (wedge 3) | `739bd2a` random (#7 R2) | **#3 008-c2 plru** (trial 6) |
|---|---:|---:|---:|
| `dstAbortDirty` | 1,160 | **0** | **0** |
| `dstAbortHeld` | 1,639 | **0** | **0** |
| `dstAbortBoth` | 139 | **0** | **0** |
| **total** | **2,938 in 0.71 s** | **0** | **0** |
| migrations at that beat | 7,211 | — | 7,767 |
| result | wedged | completed | **completed** |

The third column is the control, added 2026-09-24 (§6.1). #3 does comparable SBC work in the same
window — 7,767 migrations against 7,211 — so the difference is not "less migration". It is that on
008-c2 those three registers mean **aborts**: over the whole run #3 met a dirty or client-held
destination way **21,814 times and declined every one**, never probing and never Releasing one. It
completed. `739bd2a` evicts them, and only `739bd2a` wedges.

In a 009 PLRU build these three counters are **not aborts** — per the stashed 009 REPORT they count
**W evictions by reason**: a destination way that was dirty and/or client-held, which 009 C2 now
**probes and writes back** instead of declining. The RTL reason, verified against the live source:

- `Directory.scala:211` — `evictTier = preferEvictable && !io.usePlru.getOrElse(false.B)`. The tier that
  can offer a **parked** way as the victim is **random-only, and switches off under PLRU**.
- `Directory.scala:195` — `policyOH = Mux(io.usePlru, UIntToOH(r.io.victimWay), lfsrVictimOH)`. Under
  PLRU the destination probe takes D's **recency** way, which is very often dirty or client-held.
- `MSHR.scala:308` — `val evictW = params.micro.plruReplacement && params.micro.enableSetBalancing`, a
  **compile-time** constant that never reads `L2_Replacement`. So the 009 path is present in random
  mode too — it is simply reached ~100× less often.

**Hypothesis:** the wedge is a rare race in the 009 C2 destination-eviction path (probe + write-back of
a dirty or client-held W into the fenced destination row), and the observed hang rate tracks how often
that path executes. PLRU saturates it → 0/4. Random touches it ~100× less → 3/4 survive.

This is exactly the mechanism the prior investigation suspected as **NEST-D / B9-1** ("dirty write-back
into fenced row D"), and `MSHR.scala:513-517` already carries the invariant *"never wait on the L1
while holding the destination fence."* **Confirming it requires RTL work, not more board runs.**

The control run in §6.1 raises this from a correlation within one bitstream to a difference **between**
two bitstreams: the build that never executes the path completes, the build that executes it 2,938
times in 0.7 s wedges. §13 is the RTL review that followed, and it finds the nesting window this
hypothesis points at is indeed too narrow (§13.2).

---

## 8. Provenance audit — are #5/#6/#7 really the same RTL?

Asked by the user because #6's filename says "unverified-tree". Its archive note
(`…-739bd2a-unverified-tree-2026-09-22.bit.NOTE.txt`, written 13:44:37) claims *"tree had uncommitted
doc/sw changes at build time but no RTL diffs"* while also saying *"origin unknown"* — a claim, not a
verification. Checked independently:

| check | result |
|---|---|
| `git diff 739bd2a -- '*.scala'` (this generator) | **empty** — all 26 RTL files match the commit |
| newest RTL `.scala` mtime | `MSHR.scala` **2026-09-21 10:19**, *before* the commit (11:39:39). No RTL file has been written since — an edit-then-revert would leave a later mtime |
| generator `stash@{0}` (09-22 13:41:02) | 7 files, **no `.scala`**: `CLAUDE.md`, 4 × `ai-documents/*`, `sw/sbc_read.c` |
| rocket-chip | clean, HEAD `72690b07` (2024-08-15), sources untouched since 2026-04-26 |
| `fpga/src/main/scala/vcu118/Configs.scala` | clean, mtime 09-18 09:23 |
| **`generators/chipyard/.../RocketConfigs.scala`** | mtime **09-22 13:43** — *after* #6's build. This was the one real gap |
| → resolved | That mtime is exactly when chipyard's `stash@{0}` was taken (13:43:00). Its entire diff is **+22 lines, 0 deletions**, adding one **Verilator-only** class `VerilatorRocket8KL116KL2NestDConfig` (4 KB L2, `sbcForceDstSet=6`, 8-way L1). It does not touch `SingleRocketVCU118L18K64K16WL2ConfigSBCPLRU` or `WithInclusiveCache`. Adding a class cannot change another class's elaboration |
| tree-wide search, 09-22 07:00–09:30 | only the `.bit` itself + 2 sbt `streams/out` files — **no source file written during the build window** |

**Conclusion: same RTL — and §2 proves it empirically at the bit level.** #6 was built 09-22 08:42
*while the tree was dirty*; #7 was built 09-22 14:17 *after* the stashes were taken; their
configuration data is identical. **The stashed doc / `sw/` / test-config changes had zero effect on the
hardware.**

⚠️ `reboot_and_handover.exp`'s `DEFAULT_BIT` points at
`fpga/generated-src/…SBCPLRU/obj/VCU118FPGATestHarness.bit`, which currently hashes to **`7fb33ce6357c`
= #7**. Always pass `-bit` explicitly. Every run in this session did.

---

### 8.4 What commit is #3, exactly — proven, not inferred (added 2026-09-24)

Asked after trial 6: *which commit does the bitfile we ran belong to, and how is that guaranteed?*

**Answer: `201ebae` (008 C2), built ~22 h before that commit existed.** Not `7494296`, despite the
filename, and not 009 C1 despite a second filename on the same bytes.

**Proof 1 — the archived patch carries git blob hashes (exact).** #3 is the **only** candidate with
archived patches:
`…008-c2B-7494296-wip-2026-09-17.inclusive-cache.patch` (60,928 B) and `.chipyard-configs.patch`
(6,355 B). A git patch records `index <pre>..<post>` per file. For **all 11 files** (6 RTL + 5 SW):

| | matches |
|---|---|
| every **pre**-image blob | the blob at **`7494296`** (008 C1) — 11/11 |
| every **post**-image blob | the blob at **`201ebae`** (008 C2) — 11/11 |

So the tree at snapshot time was **byte-identical to `201ebae`** across `Control.scala`,
`Directory.scala`, `MSHR.scala`, `PerfCounters.scala`, `Recency.scala`, `Scheduler.scala`. "wip" means
only that the commit had not been made yet: patch written 09-17 19:12, bitstream 09-17 19:40, committed
as `201ebae` 09-18 10:44.

Config from the chipyard patch: `nWays = 16, capacityKB = 64, sbcAutoMigrate = true, sbcShadow = false,
sbcDebug = false, plruReplacement = true`. **`sbcShadow` and `sbcDebug` are both off**, which with
synthesis stripping `assert` means this build — like every board build — carries *no* safety net (§13).

**Proof 2 — the run's own counters, independent of any file (strong).** K3 held exactly
(21,814 = 21,814, §3.1). K3 means every dirty or client-held destination way produced a **non-commit**,
i.e. an abort. On **009 C1** a dirty client-free W is *evicted*, so the migration commits and K3 breaks;
on **009 C2** every attempt commits, so `attempted == migrations`. The counters therefore exclude both
009 commits on their own. Proofs 1 and 2 are independent and agree.

**What is NOT guaranteed.** A `.bit` contains **no commit hash**. Nothing inside the file ties it to the
patch; the link is the archive convention (same basename) plus the 28-minute gap between patch and
bitstream mtimes. Proof 1 establishes *what the tree was*; proof 2 establishes *what the silicon does*.
Either alone would be circumstantial — together they agree, and that is the whole guarantee.

### 8.5 A second mislabelled duplicate, worse than the #5/#6/#7 group

| file | full sha256 | mtime | size |
|---|---|---|---|
| `…SBCPLRU-008-c2B-7494296-wip-2026-09-17.bit` | `e41f780c67ff…` | 2026-09-17 19:40:51.324658235 | 27,921,848 |
| `…SBCPLRU-009-c1-2026-09-19.bit` | `e41f780c67ff…` | 2026-09-17 19:40:51.324658235 | 27,921,848 |

`cmp` reports **zero differing bytes** — not even the embedded build timestamp, unlike the #5/#6/#7
group. Identical mtime to the nanosecond: the 09-19 file is a **`cp -p` copy of the 09-17 build, given a
`009-c1` name**.

**This is worse than §2's duplicate group.** There, three names at least agreed on the commit. Here two
names make *contradictory* claims — 008 C2 and 009 C1 — about one build, so anyone walking TASK §3's
candidate list by filename would believe they had tested 009 C1 when they had tested 008 C2. It also
means **009 C1 has never been on the board at all** under that name; the only true 009 C1 artifact is
`…009-c1-rebuilt-2026-09-19.bit` (`3bea9add4cb8`, candidate #4), which was never run.

**Provenance ranking of the candidates, after this audit** — note the inversion:

| candidate | evidence | strength |
|---|---|---|
| **#3 `e41f780c67ff`** | archived pre/post blob hashes → `201ebae`, **plus** the K3 fingerprint from its own run | **provable** |
| #7 `7fb33ce6357c` | clean-tree rebuild, own DRC/timing; no patch | filename + process |
| #5 `afa13ca9b010` | no patch, no note | filename only |
| #6 `7b83bcbc8f02` | `NOTE.txt`: *"origin unknown, tree had uncommitted doc/sw changes … but no RTL diffs"* | self-declared unverified |
| #4 `3bea9add4cb8` | own DRC/timing/clocks; no patch; TASK §3 records no matching commit | weakest, never run |

The bitstream whose filename was most ambiguous is the only one whose commit can actually be proven.
Every `739bd2a` artifact rests on filenames and process, not on blob hashes — so **"#5/#6/#7 are
`739bd2a`" remains an inference**, supported by K3 *failing* on them and by §8's byte-identity, but not
proven the way #3 now is.

---

## 9. Errors found in TASK.md

### 9.1 §3 — "#5, #6 and #7 are three DIFFERENT bitstreams … different place-and-route runs"
**False.** They are one bitstream under three names (§2). The search order and the four-trial budget
were built around this premise, and the "at most four trials" budget was partly spent re-testing
identical bits.

### 9.2 §2 K3 — stated as a general identity, but it is 008-c2-specific
`attempted − migrations = dstAbortDirty + dstAbortHeld + dstAbortBoth` holds **exactly** on #3 (008-c2)
and **fails by construction** on every 009 build, where every attempt commits (`attempted ==
migrations`) and the three counters became *reason tallies* for W evictions.
**Do not use K3 as a health check on a 009 bitstream** — a "failure" there is the expected new
semantics. Used the other way round it is valuable: it tells 008 and 009 builds apart from the
counters alone, independently of filenames.

### 9.3 §4.2 — the `migrate : ON` check cannot work as written
§4.2 says to confirm the switch from `` `/root/sbc_read | head -1` ``. But `show()` in
[sw/sbc_read.c](../../../sw/sbc_read.c) prints the `[SBC-COUNTERS] …` one-liner **first** (lines
148–151) and `migrate : ON/OFF` on **line 2** (line 152). `head -1` can never show it.
**Worked around** with `sed -n '2,3p'` before every ON run.

### 9.4 §4 vs §4.1 — `--zero` is mandated but the prescribed R1 path omits it
§4 requires `--zero` on every run; §4.1's sanctioned R1 command is `tmp.exp -hangtest migoff`, and
[tmp.exp:543](../../../../../scripts/ssbc_scripts/tmp.exp#L543) wraps the workload as
`(cd $STAGE/$OMNET_SET && /root/sbc_read -- $raw)` — **no `--zero`**. R1's counters are therefore
absolute-since-boot. **Handled** by reporting R1 from the script's own `[SBC-DELTA]`, which brackets
the run to within 0.07 % of `ITER_SECS`.

### 9.5 §2 K7b / K7c — the anchor facts come from the **Dual-core** image, which §3 excludes
K7b cites heartbeat log `uart_20260924_074558.log` (07:45:58 → 08:10). The session immediately before
it, `board_session_20260924-065703.log` (06:57 → **07:44:58**), programmed
**`FPGADualRocketVCU118L18K64K16WL2ConfigSBCPLRU-2026-09-18.bit`** — the **Dual** image — and **no
programming occurred between 07:44 and 08:58**. That log's marker order is `ITER_SECS=245` →
`migrate : ON` / `policy : random` → omnetpp → **`ITER_SECS=1033`** (run 1 completed) → omnetpp again →
**`HB t=1s`** → silence.

So the only prior reproduction of the wedge, the K7c "0 bytes" probe, **and** the *"a healthy run is
1033 s"* reference are all from the **Dual** image, while every §3 candidate is single-core and §3 says
to *"run single-core images only and ignore the Dual image except as the known-bad reference."*

All four early-09-24 sessions (03:08, 04:46, 05:18, 06:57) used the Dual image, giving `ITER_SECS`
1028–1033. **The 1033 s reference is a Dual number** and matches neither half of the single-core data
(OFF 1017–1019 s, ON 1090–1091 s). It should not be used as a pass threshold for single-core runs.

---

## 10. Method, deviations and incidents

### 10.1 Deviations from the recipe, and why
- **R3 not run on #6 or #7** — the verdict does not depend on it and the question it answers was
  settled better elsewhere (§4, note ³).
- **The PLRU runs are additions**, not part of TASK §4, made at the user's direction after the
  `policy=random` mandate was found to exclude the configuration that the prior F4 report actually
  hung in.
- **Wedge-3's run dropped the OFF run.** §4 requires R1-first because "the OFF run parks nothing, and
  that is the only thing that makes `--reset-all` legal". A freshly programmed board has parked nothing
  at all (`SBC_MigrateEnable` defaults to 0 at reset), and `parked=0, migrations=0` was **verified from
  the read-back** before arming — so `--reset-all` was legal on §4's own grounds.
- **`--reset-all` was *not* used before the 3rd-run PLRU attempt** (wedge 2): R2 left 29 lines parked
  and the guard in `sw/sbc_read.c:258-263` refuses rather than orphaning them. `--zero`
  (`SBC_StatsReset`) was used instead — the sanctioned per-window reset inside a live SBC run.

### 10.2 Traps (TASK §2) — how each was handled
1. **`SBC_MigrateEnable` resets to 0** — confirmed by read-back before *every* ON run, and observed
   directly three times: a freshly programmed board always read `migrate : OFF`.
2. **`sbc_read --zero -- cmd` hides the child's status** — every run echoed `ITER_RC=$?` explicitly.
   No rc=127 occurred.
3. **`L2_Cycles` counts while the core is wedged** — liveness was judged only from `accessA` growth
   (~0.9–1.0 M/s), never from `L2_Cycles`.
4. **Filenames and dates are unreliable** — `sha256sum` was taken immediately before every
   programming, and §2 shows the filenames were misleading in a way TASK itself did not anticipate.

### 10.3 Incidents
- **Stray board relaunch, 11:08:40** — a leftover bash history line absorbed a `send-keys` command and
  relaunched `tmp.exp`, reprogramming the FPGA and destroying a staged boot B. ~10 min lost; **no
  recorded data lost** (R1/R2 complete, R3 not started). Fixed with a `send.sh` wrapper that sends
  `C-u` before every command. Boot B was redone.
- **Inherited stalled run, ~09:16** — a previous session's `run_test.exp`/`tmp.exp`/`picocom` stack was
  stuck 12 min at "[3/5] connecting to Linux console" with 0 bytes. Killed and reprogrammed; the board
  booted normally. **This was a stale interrupted boot, not a bitstream fault**, and must not be read
  as evidence against any bitstream. A second dead boot occurred at 18:02 and was cleared the same way.
- **MMC/SD stall, 17:58** — see §5.4. Cost one staged boot, because it was met with a reprogram before
  the user pointed out that it recovers on its own.
- **Five false positives in log detectors**, all caught before any data was taken. The root cause is
  shared: **these logs echo the command text and the scripts' own banners**, so any marker that also
  appears in an echo or banner fires early. (a) `Contents of /mnt` matched inside an echoed command;
  (b) `ITER_RC=` matched the literal `ITER_RC=$?` inside the staged `hb.sh` — the pattern must require
  digits; (c) `STAGING_OK` matched the command echo, reporting 5 s for a step that took 9 min;
  (d) `handover` matched `reboot_and_handover.exp`'s startup banner, reporting boot complete 14 s after
  launch — the real marker is `>>> SERIAL PORT FREED <<<`; (e) the heartbeat watcher's *display* lagged
  one beat, because `hb.sh` prints `HB t=` before its `sbc_read` output and a 5 s poll can land between
  them — it briefly looked like a 60 s core stall that had not happened. **Always re-derive `accessA`
  progress by pairing each `HB t=` with the counter line that follows it in the log, not from a
  sampler's summary.** Also: `tr` block-buffers in a pipe and silently swallows events — use
  `grep -aE --line-buffered`.

### 10.4 Platform cost, for planning
One boot costs ~36 min of setup: ZMODEM of 6 tool binaries at ~9.3 kB/s (~9 min) plus the benchmark
copy from `/mnt` into `test_dir` (~9 min), because the board rootfs is volatile. A completed ON run
adds ~18 min. **A wedge costs a full reprogram**, ~45 min. Caching the tools on `/mnt` to skip the
ZMODEM was attempted and triggered the MMC stall (§5.4); it is **not recommended** without further
investigation.

---

## 11. Log index

Scratchpad prefix `$SP` =
`/tmp/claude-1000/-home-damith-…-inclusive-cache/90250370-a111-4441-97e7-2fe7b519b40b/scratchpad`
(session-local). Everything else is under `chipyard/scripts/logs/`.

| run / stage | log |
|---|---|
| #3 boot A + R1 | `$SP/R1_bit3.log` · `board_session_20260924-091637.log` |
| #3 **R2** (ON, random) | `uart_R2_bit3_20260924_101014.log` |
| #3 boot B — aborted by the stray relaunch | `$SP/BOOTB_bit3.log` · `handover_20260924-103139.log` |
| #3 boot B — redone | `$SP/BOOTB2_bit3.log` · `handover_20260924-111004.log` |
| #3 **R3** (ON, random, 1st of boot) | `uart_R3_bit3_20260924_114548.log` |
| #3 **R6** boot (plru, trial 6) | `handover_20260924-204815.log` · `$SP/BOOT_plru_bit3.log` |
| #3 **R6** (ON, **plru**, only run of a fresh boot) — **completed** | `uart_PLRUonly_bit3_20260924_212536.log` |
| #5 boot A + R1 | `$SP/R1_bit5.log` · `board_session_20260924-121530.log` |
| #5 **R2** (ON, random) | `uart_R2_bit5_20260924_131016.log` |
| #5 boot B | `$SP/BOOTB_bit5.log` · `handover_20260924-133121.log` |
| #5 **R3** (ON, random, 1st of boot) | `uart_R3_bit5_20260924_140804.log` |
| #6 boot A + R1 | `$SP/R1_bit6.log` · `board_session_20260924-143836.log` |
| #6 **R2** — **WEDGE 1**, §6 capture inside | `uart_R2_bit6_20260924_153244.log` |
| #7 boot A + R1 | `$SP/R1_bit7.log` · `board_session_20260924-155436.log` |
| #7 **R2** (ON, random) | `uart_R2_bit7_20260924_164854.log` |
| #7 **R4** (ON, **plru**) — **WEDGE 2**, §6 capture inside | `uart_R4plru_bit7_20260924_170907.log` |
| #7 boot — MMC stall (§5.4) | `$SP/BOOT_plru_bit7.log` · `handover_20260924-172047.log` · `uart_PLRUonly_bit7_20260924_175724.log` |
| #7 boot — dead boot, reprogrammed | `$SP/BOOT_plru2_bit7.log` · `handover_20260924-180200.log` |
| #7 boot — clean | `$SP/BOOT_plru3_bit7.log` · `handover_20260924-180527.log` |
| #7 **R5** (ON, **plru**, 1st of boot) — **WEDGE 3**, §6 capture inside | `uart_PLRUonly_bit7_20260924_184122.log` |
| *(prior session, referenced in §9.5)* Dual-image K7b wedge | `board_session_20260924-065703.log` · `uart_20260924_074558.log` |

Helper scripts written for this task, in `$SP`: `send.sh` (line-clearing `send-keys` wrapper),
`stage_hb.sh` (writes the §4.3 heartbeat loop to `/root/hb.sh` one short `echo` at a time, because the
UART has no flow control), `hbwatch.sh` (the §5 wedge test, watching heartbeat growth in the picocom
logfile).

---

## 12. What remains open

1. **The mechanism is not proven.** §7 is a hypothesis consistent with every observation. Confirming it
   is RTL work — the suspected race is NEST-D / B9-1 in the 009 C2 destination-eviction path.
2. **The `random` wedge rate is only bounded, not measured.** One wedge in four runs. Any claim that a
   fix works must clear that noise floor; at this rate, a single passing run means very little.
3. **#3 (008-c2) is not established as safe.** It has now passed 3 ON runs — 2 `random` and, since
   2026-09-24, **1 `plru`** (§6.1), the condition that fails 4/4 on 009. That single PLRU pass is what
   moved "008 is safe / 009 broke it" from unsupported to *supported but thin*: `739bd2a` passed 3 of
   its first 4 runs before wedging, so one pass cannot establish safety. **Until #3 has ~4+ PLRU ON
   runs, the commit should be called a strong suspect, not a proven cause.**
4. **Candidates #1, #2 and #4 were never run.** The four-trial budget was consumed, partly on identical
   bits (§9.1). #4 (`3bea9add4cb8`) has no matching commit per TASK §3, so its provenance is weaker
   than #3's or #7's regardless.

### Recommended next steps

| priority | experiment | why |
|---|---|---|
| ~~1~~ | ~~#3 with `policy=plru`, migration ON, first run of a fresh boot~~ | **DONE 2026-09-24 — PASSED** (`ITER_RC=0`, 1029 s). §6.1. This was the highest-value run and it landed on the predicted side. |
| **1** | **3 more PLRU ON runs on #3**, same protocol | The only thing standing between "strong suspect" and "proven cause". One pass against 009's four failures is not yet a rate. All three fit in one boot (~17 min each, no reprogram between). |
| 2 | ~4 ON runs per policy on one bitstream | Turns "3/4 and 0/4" into rates a fix can be measured against. Multiple runs fit in one boot (~18 min each, no reprogram between); the boot is the expensive part. |
| 2b | **Fix §13.1 and rebuild once** | Gating the new path on the runtime `L2_Replacement` register instead of the compile-time flag restores random mode to 007-C2 behaviour. If random then goes 4/4 while PLRU still wedges, §13.1 is confirmed and the remaining fault is isolated to the probe/Release path. One rebuild, not a board experiment. |
| 3 | Annotate the duplicate group in `bitstream_storage/` | A short note beside #5 and #6 recording that they are byte-identical to #7 would stop a future session repeating this bisection, which TASK §3 actively invites. **Not done — it writes into the archive and needs the user's go-ahead.** |

### For anyone continuing from a "clean" build
**#7 `7fb33ce6357c` is the right starting point** — clean-tree provenance, own DRC/timing reports, and
it is already the `generated-src` artifact. But "clean" buys **provenance, not reliability**: it is
bit-identical to the build that produced all three wedges. Assume any omnetpp + migration-ON run can
wedge (certainly under `plru`, ~1 in 4 under `random`), keep the §4.3 heartbeat detector in the loop —
it caught every wedge within 115 s — and budget ~45 min for the reprogram each one costs.

---

## 13. RTL review of `739bd2a` — suspected defects and fixes

Added 2026-09-24, after §6.1. Source read: the 009 C2 diff and the current
`MSHR.scala`, `Scheduler.scala`, `Directory.scala`, `SourceC.scala`, `SinkC.scala`, against
`coder/009-evict-destination-lru/TASK.md`. **Nothing was edited** — this task measures, it does not fix.
Confidence is stated per item; only §13.1 is provable by reading a single line.

**Two context facts that frame all of it.**

1. **`coder/009-evict-destination-lru/REPORT.md` is an empty template.** The rules, hazards, in-flight
   cases, commits and every check are blank. So gates **V2** (the PLRU gate) and **V4** (coverage of the
   new paths) have no recorded result, although V4 says: *"Any of D-nest / S-nest / H3 = 0 → write a
   directed test that forces it and report it before C2 is committed."* The board is most likely the
   first place these paths ever ran.
2. **Every new safety net is sim-only.** All 12 asserts 009 added are inside `` `ifndef SYNTHESIS ``, so
   none is in the bitstream. That is why a wedge yields 0 bytes instead of a message.

A third fact fixes the timing: the destination read raises `preferInvalid`, so W is evicted **only when
row D has no invalid way**. Destination sets fill gradually — which is why the wedges arrive after
thousands of migrations rather than at once.

### 13.1 Random mode runs the new code — highest confidence, contradicts the task's own U2

`MSHR.scala:308` — `val evictW = params.micro.plruReplacement && params.micro.enableSetBalancing`, a
**Scala compile-time** value. `usePlru` (the runtime `L2_Replacement`, `0x490`) is wired to the
Directory and the Scheduler but **never reaches the MSHR at all**. So `takeW = evictW.B && !dstFree`
fires in random mode too, and the abort branch it replaced is dead in this build whatever is written to
`0x490`.

009 decision **U2** says *"do not change random mode in any way — including its silent overwrite of a
clean destination way."* It is changed. This is the cleanest explanation of the one random-mode wedge
(#6 R2): the Directory's clean-way tier still hands random a clean, client-free W, so random gets the
Release half of the new path but rarely the probe half — matching `dstAbort*` = 0 in random against
2,938 in PLRU, and 1/4 against 4/4.

**Fix:** add a `usePlru` input to the MSHR; `takeW = io.usePlru.getOrElse(false.B) && !dstFree`, abort
branch restored for random.

### 13.2 The C-channel escape hatch closes too early — high confidence

`MSHR.scala:497` — `nestD := dstEvict && !w_dprobeackfirst && !dstMeta.displaced`. `nestD` is what lets
an L1 `Release` addressed to row D reach the C MSHR instead of jamming the head of the inner C channel,
and it is true **only while W's probe is unanswered** — it drops on the *first* ProbeAck beat.

The fence on D (`dstValid`) stays up far longer: W's Release, the outer `ReleaseAck`, the copy,
dir-write #1, the `Acquire`, the `Grant`, the `GrantAck`. Through all of that a `Release` to row D
cannot be consumed (`allocReady` fenced, no MSHR matches D, so `nestC` is false and `request.ready` is
false) and it stops **all** inner C traffic behind it. TASK I3 argues no cycle forms because "the
migrant needs nothing from the L1 C channel to retire" — true for one transaction in isolation, but 009
stretched that window by a memory round trip, and the L1 arbitrates its probe unit and its writeback
unit onto that single channel.

**Fix:** widen to the whole eviction — `dstEvict && !(w_dprobeacklast && w_dreleaseack)`, or simply
`dstEvict`. N5 already stalls the migrant while the C MSHR is in row D, so H2/H7's ordering still holds.

### 13.3 The destination way W is never way-locked — high confidence

`MSHR.scala:551-554` gives each MSHR exactly **one** lock: its borrowed way in the partner set, or its
own victim in its own row. `(migDstSet, migDstWay)` is neither. So while the migrant is probing and
Releasing W — now a long, protocol-visible operation — nothing tells the Directory that way is
occupied. `Directory.scala`'s "at most two ways in a row are locked" stays true, but the way somebody is
*inside* is no longer among them. Tolerable before 009, when W was overwritten in a couple of cycles.

**Fix:** a second lock slot for `(migDstSet, migDstWay)` while `dstEvict`, and update the
`freeWays.orR` assert's reasoning.

### 13.4 `allowDisplacedVictim` is a no-op — medium confidence, latent trap

`Directory.scala:199` is its only use, inside `evictableOH` → tier 1. Tier 1 is
`evictTier = preferEvictable && !usePlru`, and `preferEvictable` is raised **only** for the migration
destination probe — where `allowDisplacedVictim` is always true. So the flag is true wherever it is
reachable and ignored everywhere else. The Scheduler comment claiming *"a source read must never pick a
guest, because the AT records one hop only"* describes a rule **nothing enforces**. It happens to hold
for a different reason (a guest picked on a demand miss is released, not migrated), but it sits next to
the standing hard rule about never weakening a `displaced` test.

**Fix:** apply the mask to the policy and free-way tiers too, or delete the flag and correct the comment.

### 13.5 N2 was downgraded from a fix to an assert — medium confidence

TASK **N2** required adding `|| (dstEvict && !w_dprobeackfirst)` to the MSHR's `nestC`, so a `Release`
of a **guest** W (address maps to S, the migrant's own set) nests rather than queuing. The
implementation skipped it and substituted
`assert (!probingW || io.status.bits.nestC, "009: nestC is false while W's probe is open (Q2)")`.

The reasoning — that `nestC`'s `!w_grantfirst` term is incidentally true all through a migration,
because the demand `Acquire` is gated on `w_dread && w_copy` — does hold today. But it is a coincidence
between two unrelated pieces of logic, and the only thing guarding it is an assert that is not in the
bitstream.

**Fix:** implement N2 as specified; keep the assert as a check, not as the mechanism.

### 13.6 The `ReleaseAck` split is by order, not identity — medium-low confidence

`MSHR.scala:1434-1436` — `when (!w_dreleaseack) { w_dreleaseack := true.B } .otherwise { w_releaseack := true.B }`.
Both Releases use `source = 0`, so they are told apart purely by which wait-flag is still low, and
`w_dreleaseack` goes low at the *directory result*, before the Release is sent. Correct only while the
T6 assert holds — and T6 is not synthesized. If it is ever wrong the MSHR retires with W's Release
unacknowledged, drops the fence, and the next `Acquire` for W's address goes out: a T5 violation.

**Fix:** distinguish them by something real — a distinct source id, or a bit set when the message is
actually accepted.

### 13.7 One data-corruption path worth a sim run

A freeze with no serial output fits corrupted kernel memory as well as a protocol deadlock.
`SourceC.scala:89` checks `evict_safe` **only on beat 0** (`beat.orR || io.evict_safe`); after that it
reads the row unguarded. That is stock behaviour, but 009 adds new writers to `(D, W)` during exactly
that read, and the H3 interlock covers only the copy, not SinkC or SourceD. No reachable interleaving
was constructed — our probe of W completes before our Release starts, so the client can no longer be
releasing W — but this is what the shadow checkers exist to catch. **Needs a sim run, not an argument.**
