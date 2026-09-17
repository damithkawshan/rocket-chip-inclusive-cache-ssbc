# Running the SBC L2 on VCU118 under Linux

End-to-end recipe to build a bitstream that contains this L2, boot Linux on the VCU118, and read the
SBC / L2 counters from userspace. This is the **performance-eval path** — the real geometry (64, 128 or
256 KB L2, 16 ways, 64 B lines), not the 8-set Verilator toy used for correctness.

**For a measurement, use section 7** (`run_board_session.exp -ab`). It does steps 2–6 for you. Steps 2–6
are the manual path, for debugging.

All helper scripts live in the parent tree at `chipyard/scripts/ssbc_scripts/`. Commands below assume
`CY=…/chipyard` and that you have `source $CY/env.sh` in the shell.

## 0. What you need

- Vivado 2021.1 in `PATH` (the bitstream and `programFPGA.sh` both call it).
- VCU118 board (xcvu9p): USB-JTAG cable **and** USB-UART cable (the UART shows as `/dev/ttyUSB2`).
- An SD card flashed with a FireMarshal `br-base` Linux image (see step 3).
- Host tools: `picocom` + `lrzsz` (`sz`/`rz`). `connect_serial.sh` installs them if missing.

## 1. Build the bitstream

`bistream_gen_vcu118.sh` takes the L2 size as its argument. Each size has an SBC config and an SBC-off
twin (`…NoSbc`). `enableSetBalancing` is a compile-time flag, so the twin is a separate bitstream. The
one-bitstream A/B in section 7 needs only the SBC image; build the twin when you need area/Fmax or a true
SBC-off control.

| Argument | Config built | L2 |
|---|---|---|
| `sbc` / `nosbc` / `both` | `FPGASingleRocketVCU118L18K256K16WL2ConfigSBCResetEnabled` / `…256K16WL2ConfigNoSbc` | 256 KB, 256 sets |
| `sbc_128l2` / `nosbc_128l2` / `both_128l2` | `FPGASingleRocketVCU118L18K128K16WL2ConfigSBC` / `…128K16WL2ConfigNoSbc` | 128 KB, 128 sets |
| `sbc_64l2` / `nosbc_64l2` / `both_64l2` | `FPGASingleRocketVCU118L18K64K16WL2ConfigSBC` / `…64K16WL2ConfigNoSbc` | 64 KB, 64 sets |
| any config name | that config | |

```bash
bash $CY/scripts/ssbc_scripts/bistream_gen_vcu118.sh sbc_128l2
# which runs, from $CY/fpga/:
#   make -j20 SUB_PROJECT=vcu118 CONFIG=FPGASingleRocketVCU118L18K128K16WL2ConfigSBC bitstream
# log: $CY/fpga/build-logs/<config>-<stamp>.log
# bit: $CY/fpga/generated-src/chipyard.fpga.vcu118.VCU118FPGATestHarness.<config>/obj/VCU118FPGATestHarness.bit
```

- **Every build starts clean.** Since 2026-09-17 the script deletes that config's `generated-src` dir
  first. Make decides what to rebuild from file timestamps, so an old `.fir`, `.sv` or Vivado checkpoint
  could otherwise be reused and the image would not match the RTL. **Never run `make clean` in `fpga/`**:
  it deletes all of `generated-src`, including other configs' bitstreams that were never archived.
- **Archive before the next build of the same config** overwrites the `.bit`: copy it to
  `$CY/fpga/bitstream_storage/` with a dated, feature-tagged name.
- **Write down which RTL went in.** Uncommitted inclusive-cache edits are built in if they are in the tree.
  sbt compiles from the working tree, and a Verilator run may already have compiled them.
- Build time: ~25 min (128 KB and 256 KB builds took 23–24 min on 2026-09-17).

## 2. Program the FPGA

```bash
bash $CY/scripts/ssbc_scripts/programFPGA.sh /path/to/<config>.bit
```

JTAG-only; it does not touch the SD card, so re-programming does not disturb the Linux image.

## 3. Boot Linux

Linux is booted from the SD card by the VCU118 bootrom (raw binary at sector 34 → DRAM `0x80000000`).
Flash the image once with:

```bash
# build br-base first (see the header of the script), then:
bash $CY/scripts/ssbc_scripts/sdflash_firemarshal.sh /dev/sdX
```

Boot flow: bootrom prints `INIT/CMD*/LOADING…`, then `BOOT`, then the kernel comes up. **Login is
`root` / `fpga`.** br-base already ships `devmem` and `sz`/`rz` (`BR2_PACKAGE_LRZSZ=y`).

## 4. Connect the serial console

```bash
bash $CY/scripts/ssbc_scripts/connect_serial.sh   # picocom on /dev/ttyUSB2 @ 115200 8N1
```

File transfer is wired into picocom:

- `Ctrl-A Ctrl-S` — **send host → board** (run `rz -b -E` on the board first)
- `Ctrl-A Ctrl-R` — **pull board → host** (run `sz -b <file>` on the board first)
- `Ctrl-A Ctrl-X` — quit

`-b` (binary) is mandatory or the ELF gets newline-mangled; the small zmodem window (`-w 1024 -L 128`)
is what keeps the transfer stable on this RTS/CTS-less UART.

## 5. Build and transfer `sbc_read`

The counters have no driver — `sbc_read` `mmap`s the control block through `/dev/mem`. Cross-compile it
static on the host, then zmodem it over:

```bash
riscv64-unknown-linux-gnu-gcc -O2 -static -o sbc_read $CY/generators/rocket-chip-inclusive-cache/sw/sbc_read.c
riscv64-unknown-linux-gnu-strip sbc_read     # ~466 KB after stripping
```

On the board: `rz -b -E`, then `Ctrl-A Ctrl-S` on the host and pick `sbc_read`. Then `chmod +x sbc_read`.

## 6. Take a measurement

Two ways to get a clean window. **`--zero` is the one to use**: it writes `SBC_StatsReset` (0x3B8),
which zeroes only the event/hit counters in hardware and never touches `sat`/AT/DSS/`parkCount`/
`nParked`, so the SBC flow keeps running across the reset.

```bash
./sbc_read                    # print counters now (free-running since power-on — includes boot)
./sbc_read --zero             # pulse SBC_StatsReset, then print the now-zeroed counters
./sbc_read --zero -- <cmd>    # zero, run <cmd>, print ABSOLUTE counts = the exact window, one read
./sbc_read -- <cmd>           # no reset: snapshot, run <cmd>, print the DELTA
```

**Do NOT use `SBC_Reset` (0x358) to zero counters** — it wipes the Association Table while lines are
still parked, orphaning them. `SBC_StatsReset` is the safe per-window reset.

Register offsets and semantics: [`devmem-register-map.md`](devmem-register-map.md) and
[`../../sw/sbc_mmio.h`](../../sw/sbc_mmio.h) (single source of truth, mirrors `Control.scala`).

## 7. Board A/B in one command — `run_board_session.exp`

The measurement path. One SBC image is programmed once, and the workload runs twice on it: **migrate
OFF** (`SBC_MigrateEnable` = 0, plain L2), then **migrate ON**. Both halves use the same silicon, routing
and clock. The script programs the board, boots Linux, pushes `sbc_read` over zmodem, stages the workload
from the SD card, runs both halves and logs everything.

Full sequence (clean → build → archive → A/B), as used for the 128 KB run on 2026-09-17:

```bash
tmux new -s sbc128

CY=/home/damith/Research/repos/chipyard_performance_eval/chipyard
CFG=FPGASingleRocketVCU118L18K128K16WL2ConfigSBC
GEN=$CY/fpga/generated-src/chipyard.fpga.vcu118.VCU118FPGATestHarness.$CFG
BIT=$GEN/obj/VCU118FPGATestHarness.bit

rm -rf "$GEN"                                                        # 1. clean (the script also does this)
bash $CY/scripts/ssbc_scripts/bistream_gen_vcu118.sh sbc_128l2       # 2. build, ~25 min
cp -p "$BIT" $CY/fpga/bitstream_storage/$CFG-<feature-tag>-<date>.bit  # 3. archive
cd $CY/scripts/ssbc_scripts && ./run_board_session.exp -bit "$BIT" -ab  # 4. A/B, ~65 min for omnetpp
```

For another size, change `CFG` and the build argument (section 1 table). To re-measure an archived image,
skip steps 1–3 and pass the archived file to `-bit`.

- **Log:** `$CY/scripts/logs/board_session_<stamp>.log`. Each half ends in an `[SBC-WINDOW]` dump. Check
  `workload rc=0` in **both** halves: `sbc_read` returns the child's exit status but never prints it.
- **Workload:** the `WORKLOADS` list in the script, run word for word in both halves. Today it is
  `520.omnetpp_r` ref with `--sim-time-limit=0.002s` under `sbc_read --zero --` (fixed work, not fixed time).
- **Order is fixed, OFF first.** The ON half starts with `sbc_read --reset-all --migrate=on`, which is
  only legal while nothing is parked.
- Other flags: `-noprog` (board already programmed), `-poll N` (sample counters every N s),
  `-tmo N` (per-workload limit). The usage block at the top of the script lists them all.

Runs so far: [board-128kb-omnetpp-2026-09-17.md](../performance/board-128kb-omnetpp-2026-09-17.md) (128 KB) and the
64 KB board sections of [coder/007 REPORT](../coder/007-paper-aligned-eviction/REPORT.md).

## Verified on hardware (2026-09-04)

`SBC_StatsReset` was confirmed on the VCU118 Linux boot, not just in sim:

- `./sbc_read --zero` drove every event/hit counter to 0 (`migrations`, `secHits`, `attempted`,
  `L2_Accesses`, …) while **`parked` held at 792** — the live flow state was untouched, which is the
  whole point of the register.
- Successive reads then climbed cleanly from zero (`migrations 151 → 304`, `secHits 12381 → 55987`,
  `L2_Accesses 297 k → 1.24 M`), i.e. migration kept running through the reset.

This is the acceptance evidence behind the `…-verified-linux-fpga` tag.
