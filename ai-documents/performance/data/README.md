# Archived measurement data

Copies of the CSVs and transcripts behind the reports in `ai-documents/performance/`. The
originals live in `scripts/logs/`, which accumulates everything; these are the runs that a report
actually cites, kept so the numbers stay reproducible after `scripts/logs/` is pruned.

Every CSV is one row per run, written as each run finished — a killed session still leaves its
completed runs behind. Columns: the point's identity, then `status`, `rc`, and every counter
`sbc_read` prints. `status` is `OK` only when the `[SBC-WINDOW]` line, the `workload rc=` echo and
(for pair runs) the `[PAIR-TIME]` line were all seen; anything else means no window was reported.

**Always check `status` and `rc` before using a row.** Every `canneal` row in
`2026-10-05-partner-calibration/cal_runs_*.csv` has `rc=1`: the program never completed, and its
counters were quoted as valid results for several days before anyone checked. See
`lu-streamcluster-pair-2026-10-05.md` §6.1.

---

## 2026-10-02-lu-single-app/
Report: `../lu-single-app-2026-10-02.md` §3
Single-core 256 KB 8-way, `lu_ncb` star sweep around `-p2 -n512 -b128`, 72 runs, 3 repeats per
point per phase.
- `lu_runs_20261002-010911_recovered.csv` — rebuilt from the transcript by
  `scripts/ssbc_scripts/recover_csv_from_log.py` after a capture bug stamped 36 of 72 rows `OK`
  with no counters. Use this, not the original `lu_runs_20261002-010911.csv`.
- `lu_session_20261002-010911.log` — transcript.
- ⚠ The `lu-p2-n512-b128-plru` row is contaminated by SD writeback (first point of the session).
  Use `lu-p1-n512-b128-plru` as the clean centre point.

## 2026-10-03_04-lu-n768-boots/
Report: `../lu-single-app-2026-10-02.md` §4
`lu_ncb -p2 -n768 -b128` on the dual-core bitstream, one OFF/ON pair per boot. Shows that prior
migrate=on activity roughly doubles the apparent benefit.
- COLD (fresh reprogram, valid): `lu_runs_20261003-135712`, `20261003-203825`, `20261004-065147`
  (+ transcripts) → −4.38%, −3.50%, −3.44%, mean **−3.77%**
- WARM (three n=512 ON runs first, **not** cold-start results): `lu_runs_20261002-035925`,
  `20261002-060333` → −10.45%, −7.93%
- These CSVs hold several points each; filter to `n=768` rows.

## 2026-10-05-partner-calibration/
Report: `../lu-streamcluster-pair-2026-10-05.md` §6
Choosing a co-runner for LU.
- `cal_runs_20261005-025344.csv` + transcript — `canneal`, 6k and 30k netlists, `NSWAPS` 0 →
  200000. **Every row `rc=1`.** `NSWAPS=0` isolates the loader cost, which is how the annealing
  phase was differenced out.
- `sccal_runs_20261005-033000.csv` — `streamcluster`, large footprints (512 K → 8 M).
- `sccal_runs_20261005-100107.csv` — `streamcluster`, small footprints (140 K → 428 K), the ones
  that fit the L2; `n > chunksize` keeps the footprint fixed while extending runtime.
- `lu_runs_20261005-095124.csv` — LU `-p1` block-size sweep (b=16/32/64/128) on the dual-core
  bitstream, establishing the 93.6 / 70.4 / 49.5% hit-rate ladder.

## 2026-10-05-lu-pair/
Report: `../lu-streamcluster-pair-2026-10-05.md` §1, §3, §4
LU + streamcluster sharing one L2.
- `pair_runs_20261005-034606.csv` — dual-core, oversized (LU b=128 + 3 MB sc). Interference only
  **+2.40%**: both apps already thrashing alone.
- `pair_runs_20261005-102612.csv` — the `nop` harness baseline (`-pair lu,nop`). Gives pair.sh's
  own cost: 837,645 accesses fixed + 246,327 per workload branch.
- `pair_runs_20261005-103531.csv` — dual-core, LU b=32. Interference +23.96%, but the pair stays at
  85% hit, not low enough for SBC to help.
- `pair_runs_20261005-105713.csv` — dual-core, LU b=64, **3 repeats**. Interference **+40.5%**
  (a single shot of the same point read +52.2%; see report §4.1).
- `pair_runs_20261005-110920.csv` + transcript — the dual-core ON attempt. `status=TIMEOUT`:
  the board hard-deadlocked under migrate=on. This is why the experiment moved to single-core.
- `pair_runs_20261005-121456.csv` — **single-core OFF baseline, 3 repeats.** Interference +74.2%.
- `pair_runs_20261005-125128.csv` + transcript — **the headline ON run: −12.50% memAcc.**

---

## Reading a pair CSV

Extra columns before the counters: `kind` (solo/pair), `wl_a`/`wl_b`, `cpu_a`/`cpu_b`, then
`window_s`, `wall_a`, `wall_b`, `overlap_s`, `overlap_frac`, `ra`, `rb`.

- `overlap_frac` is the fraction of the counter window during which **both** workloads were
  running. Below ~0.5 the row is one application plus a bystander, not a pair. All rows cited in
  the reports are ≥ 0.916.
- `ra`/`rb` are the per-workload exit codes. A crashed workload still produces an `[SBC-WINDOW]`
  line, just a meaningless one, so these are what distinguish a real run.
- `memAcc` is `memReads + memWrites` and is not a CSV column — compute it.
- For interference, use `memAcc`, which needs no harness correction. Do **not** use
  `accessA(pair) == accessA(A) + accessA(B)` as a same-work check for a pair; it is invalid
  (report §5.2).
