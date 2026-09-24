# Board session log — 2026-09-25, task 012 V7

What was done on the board, in order, with the reason. Numbers live in `REPORT.md`; this file is the
record of **actions**, so that a later reader can tell a measurement apart from a side effect.

Session runs in tmux `hang010` (the session that was already attached to the board).

| # | time | action | why / result |
|---|---|---|---|
| 0 | 00:38 | **Read the board's state before touching it.** `hang010` had picocom attached, the board at a `#` prompt, holding the **R0** image (`e41f780c…d177`, commit `201ebae`) with its last PLRU run on screen (`ITER_RC=0 ITER_SECS=1029`, then a second dump). | The board was NOT idle-unknown. Nothing was reprogrammed until this was established (a summary once claimed a wedge that turned out to be a live session). |
| 1 | 00:47 | **Accident, no damage:** `expect -n` does not mean "parse only" — it ran `run_012_v7.exp` and spawned a second picocom on `/dev/ttyUSB2`. Killed at once; hang010's picocom (pid 3053500) still owned the port and the board answered a bare Enter with a fresh prompt. | Recorded because it touched the serial port. No register was written and no run was in flight. Syntax is now checked with `info complete` under `tclsh`, which does not execute. |
| 2 | 00:49 | **Hashed the image, then programmed it.** `af11762b917780e5ec71e8246ec4455b7914b9102e635c0ce1c91741a3d32ec4` — checked against the archive copy **before** Vivado ran, not after. Vivado finished 00:49:46. | The rule is to name a bitstream by its hash: two files in the archive already carry wrong names. Programming destroyed the R0 board state, which was safe to lose — its runs are recorded in 010 §6.1. |
| 3 | 00:49–01:1x | **Boot.** `reboot_and_handover.exp -bit <R1> -policy plru` in `hang010`. Long UART silence before the first board output; compared against a known-good boot log (951 spinner ticks before `BOOT`) to confirm this is normal for this board, rather than guessing. | A "no output" boot has been mistaken for a wedge before. The check cost nothing and ruled it out. |
| 4 | 01:05 | **Corrected the baseline record.** Parsing R0's own transcript showed **3** migration-ON PLRU completions (1029, 1030, 1029 s) plus one migration-OFF run (1030 s) — the REPORT said "2/2". Fixed in `REPORT.md` and `TASK.md`. | The candidate is judged against this baseline, so an understated baseline would flatter it. |
