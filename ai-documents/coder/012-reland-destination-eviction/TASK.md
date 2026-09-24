# Coder task 012 — re-land 009's destination eviction, staged and board-gated

**Opened 2026-09-24.** Branch **`sbc-009-redo`** (cut from `sbc-sampling`; `739bd2a` reverted in
`f385555`, RTL verified byte-identical to `201ebae` per file by blob hash).

Authorised by the user 2026-09-24: *"go ahead and implement this under new task. do the testing in sim
and fpga as well. and document the link to working bitstream."* So unlike 009/011, the same session
writes the RTL, runs the gate **and** runs the board.

Background and the six leads: `coder/011-fix-009-destination-eviction/`. The board evidence that forced
the revert: `coder/010-hang-bisection/`.

---

## 0. The working bitstream (R0) — already exists, already board-proven

**No build needed for the baseline.** This branch's RTL *is* `201ebae`, and the bitstream built from that
exact tree is archived and has passed on the board:

| | |
|---|---|
| file | `chipyard/fpga/bitstream_storage/FPGASingleRocketVCU118L18K64K16WL2ConfigSBCPLRU-008-c2B-7494296-wip-2026-09-17.bit` |
| **sha256** | **`e41f780c67ff2da2e60ff55c59f6750d0ea60e9fbfd9d18e36d06cb58711d177`** |
| commit | **`201ebae`** — proven, not inferred (010 §8.4: archived patch pre/post blob hashes + the run's K3 identity) |
| config | `nWays=16, capacityKB=64, sbcAutoMigrate=true, sbcShadow=false, sbcDebug=false, plruReplacement=true` |
| board record | `plru` ON: **1029 s, 1030 s, 1029 s — 3 completions, all rc=0** · `plru` OFF: 1030 s · `random` ON: 1091 s ×2 · OFF: 1017 s |
| provenance files | `….inclusive-cache.patch`, `….chipyard-configs.patch` beside it — the exact source |

⚠️ The **same bytes** are also archived as `…SBCPLRU-009-c1-2026-09-19.bit` (zero differing bytes). That
name is wrong — use the `008-c2B` one, and cite the **hash**, never a filename (010 §8.5).

Any new bitstream this task builds is recorded in `REPORT.md` with its own sha256 beside this one.

**R1 — the candidate (C1+C2), built 2026-09-25, NOT yet board-tested:**
`af11762b917780e5ec71e8246ec4455b7914b9102e635c0ce1c91741a3d32ec4` = commit `97d0162`, file
`…SBCPLRU-012-c2-97d0162-2026-09-25.bit`. Row, timing and provenance in `REPORT.md` "Board run".

---

## 1. Goal

Recover 009's **function** — stop skipping D's least-recent line, which cost **0.68 primary hits per
migration** on the 64 KB board (random: 0.15) — without recovering its **failure** (0 of 4 PLRU
completions, hard board freeze).

⚠️ **This is a lever on the omnetpp tie, not a blocker.** SBC already beats the plain L2 on
`l2_miss_calib` / DualRocket with **008 C2** RTL (−13.46% cycles, −29.52% reads, 2026-09-24) — see
011 §11.1, which proves that image is 008 C2 two independent ways. Do not justify risk here by claiming
SBC cannot win without it.

---

## 2. Why 009 hung, in one line

It raised the destination-set fence (`dstValid`) and **then waited on the L1** for W's ProbeAck — the
hold-and-wait edge `MSHR.scala:473-476` says the design deliberately never had. Bug **P1** was closed
only for the *partner* set; the `dstSet` term stays in `dstSetConflict` and its C-head watchdog is
sim-only, so nothing catches it on hardware.

**The staging below is built around that single fact: the client probe is last, and it is the only stage
that needs the restructure.**

---

## 3. Stages

Each commit is gated independently. **A green sim does not close a stage — 009's sim gate was green.**

| # | scope | behaviour change | board gate |
|---|---|---|---|
| **C1** | **F3** way-lock the destination way `(migDstSet, migDstWay)`; **F4** correct the `allowDisplacedVictim` comment; new sim asserts | **none intended** — must prove it by identical sim counters | rides with C2 (see §5) |
| **C2** | **Re-land for a CLIENT-FREE W only.** A valid, client-free destination way is evicted with a real `Release`/`ReleaseData` before the copy overwrites it. **Runtime-gated on `usePlru`** (F1's lesson). **F6** ReleaseAck identity. H3 interlock | yes, PLRU mode only | **4 consecutive PLRU ON completions** + 4 random |
| **C3** | **Client-held W — the probe.** The risky stage. Restructured per 011 §11.2(b): probe **before** the fence rises, way-lock instead of row fence | yes | 4 PLRU, then repeats |

### Why C2 cannot deadlock, and C3 can

C2 evicts only a **client-free** way, so there is **no probe and no L1 involvement**. It holds the
destination fence while waiting on **memory** for `ReleaseAck`. Memory is an unconditional responder —
there is no cycle. C3 waits on the **L1**, whose ProbeAck shares one channel with its voluntary
write-backs, one of which may be addressed to the row we fenced. That is the cycle, and it is why C3 is
last and gets the restructure.

**C2 is exactly 009 C1's scope** — which, per 010 §8.5, **has never run on the board**: the file named
`009-c1` is a mislabelled copy of the 008 C2 build.

---

## 4. Binding rules

- **Runtime, never compile-time.** Any new destination behaviour is gated on `usePlru`
  (`L2_Replacement`, `0x490`), never on `params.micro.plruReplacement` alone. 009's F1 broke this and
  cost us a clean random-mode control. Zero hardware when `plruReplacement = false`.
- **Do not reuse** the stock probe/release registers, `probes_done`/`probes_toN` or `meta` for W.
- **Do not repoint** `status.way` / `status.physSet`.
- **Do not weaken** the destination fence, the migration token, `s_wsafe`, or any `w.displaced` test.
- **Do not reorder** BankedStore priorities.
- **No new MMIO register or counter** without asking (009 U6). The synthesizable watchdog proposed in
  011 §11.4 is **not** in this task — it needs the user's approval first.
- Simulate only through `make run-binary` / `run_sbc.sh` (a bare `$SIM` omits `+dramsim`).
- Counter fan-in with `PopCount`, never OR (005 §2.2).
- ⛔ Never `git checkout` whole files — uncommitted configs live in the chipyard tree.

---

## 5. Checks

| # | what | pass |
|---|---|---|
| V0 | `plruReplacement = false`, `make verilog`, compare with `f385555` | identical once Scala line numbers are normalised |
| **V1** | **C1 is behaviour-preserving.** Stress + switch, both configs, both policies | **every counter identical to `f385555`.** This is what licenses C1 and C2 sharing one bitstream |
| V2 | PLRU gate after C2: stress (8/8 SBC, 7/7 NoSbc) + switch | 0 asserts, 0 TLMonitor errors, shadow checkers quiet, C-head and new watchdogs silent, all identities hold |
| V3 | `plru_replay.py` on the PLRU logs | `plruWay` = model on every line |
| V4 | coverage of the new paths, from `sbcDebug` printfs | non-zero count for: W released clean · W released dirty · W was a guest · H3 held a copy. **Any = 0 → directed test before the commit lands** |
| V5 | NoSbc config, PLRU | every counter identical to `f385555` NoSbc |
| V6 | 64 KB bitstream | DRC clean, timing met; LUT/FF against the R0 image |
| **V7** | **board** | per stage in §3. Protocol: fresh boot, verify `migrations=0 parked=0` by read-back, `--reset-all --migrate=on`, confirm read-back on **lines 2–3** (not `head -1`), `--zero` on the run, manual heartbeat watch |

Report `memReads` and `memWrites` **separately**, plus `SBC_DstDirty/Held/Both`, `attempted`,
`migrations`, `parked`, and whether **K3** holds — after C2 it must **not** hold in PLRU mode for
client-free ways, because those now commit instead of aborting. That is the counter-level proof the
re-land is live.

⚠️ If it wedges, capture 010 TASK §6 **before** reprogramming.

---

## 6. Do not

- Do not stack C3 on an unproven C2.
- Do not fix a failing gate by tweaking the test. A failure is a finding for `REPORT.md`.
- Do not fix B8-1 (008 F5 / tracker M19) here; if its assert fires, report it as a recurrence.
- Do not tune thresholds.
