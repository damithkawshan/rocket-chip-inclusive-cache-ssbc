# Data: fresh-boot SBC on/off experiments (2026-10-05 to 2026-10-09)

Report: [../../fresh-boot-experiments-2026-10-09.md](../../fresh-boot-experiments-2026-10-09.md)

| file | what |
|---|---|
| `windows.csv` | one row per **valid** measured window: experiment, setting, SBC (off/on), run, start time, memAcc, hit rate, every L2 counter, source folder |
| `build_windows_csv.py` | rebuilds `windows.csv` from `chipyard/scripts/logs/clean-runs/` (the checkers' `stage_result.json`; for the `pair.sh` sessions, the first window after each boot) |
| `make_tables.py` | computes every table of the report from `windows.csv` and writes them between the report's `<!-- table:NAME -->` markers; refuses to write if text outside the markers would change |

- `experiment` values: `lu-alone-sweep`, `lu-b64-alone-pairsh`, `lu-b64+streamcluster-pairsh`, `lu-b32+b128-pair`,
  `lu-pair-search`, `lu-fft-grid` (report sections 2.1–2.6)
- memAcc = memReads + memWrites (64 B blocks); hit rate = (primaryHit + secondaryHit) / accessA
- Transcripts, per-window checker output and void attempts stay in `clean-runs/` (see the report, section 8)
