# Board session log — 2026-09-25, task 012 V7

What was done on the board, in order, with the reason. Numbers live in `REPORT.md`; this file is the
record of **actions**, so that a later reader can tell a measurement apart from a side effect.

Session runs in tmux `hang010` (the session that was already attached to the board).

| # | time | action | why / result |
|---|---|---|---|
| 0 | 00:38 | **Read the board's state before touching it.** `hang010` had picocom attached, the board at a `#` prompt, holding the **R0** image (`e41f780c…d177`, commit `201ebae`) with its last PLRU run on screen (`ITER_RC=0 ITER_SECS=1029`, then a second dump). | The board was NOT idle-unknown. Nothing was reprogrammed until this was established (a summary once claimed a wedge that turned out to be a live session). |
| 1 | 00:47 | **Accident, no damage:** `expect -n` does not mean "parse only" — it ran `run_012_v7.exp` and spawned a second picocom on `/dev/ttyUSB2`. Killed at once; hang010's picocom (pid 3053500) still owned the port and the board answered a bare Enter with a fresh prompt. | Recorded because it touched the serial port. No register was written and no run was in flight. Syntax is now checked with `info complete` under `tclsh`, which does not execute. |
