# Running the SBC L2 on VCU118 under Linux

End-to-end recipe to build a bitstream that contains this L2, boot Linux on the VCU118, and read the
SBC / L2 counters from userspace. This is the **performance-eval path** — the real geometry (256 KB
L2, 16 ways, 256 sets, 64 B lines), not the 8-set Verilator toy used for correctness.

All helper scripts live in the parent tree at `chipyard/scripts/ssbc_scripts/`. Commands below assume
`CY=…/chipyard` and that you have `source $CY/env.sh` in the shell.

## 0. What you need

- Vivado 2021.1 in `PATH` (the bitstream and `programFPGA.sh` both call it).
- VCU118 board (xcvu9p): USB-JTAG cable **and** USB-UART cable (the UART shows as `/dev/ttyUSB1`).
- An SD card flashed with a FireMarshal `br-base` Linux image (see step 3).
- Host tools: `picocom` + `lrzsz` (`sz`/`rz`). `connect_serial.sh` installs them if missing.

## 1. Build the bitstream

The config is **`FPGASingleRocketVCU118L18K256K16WL2ConfigSBCResetEnabled`** — same geometry as
`…ConfigSBC`, built from the RTL that now carries `SBC_StatsReset` (0x3B8). `enableSetBalancing` is a
compile-time flag, so the SBC-off twin (`…ConfigNoSbc`) is a separate bitstream; build it too when you
need an A/B.

```bash
# edit VCU118CONFIG at the top of the script if needed, then:
bash $CY/scripts/ssbc_scripts/bistream_gen_vcu118.sh
# which runs, from $CY/fpga/:
#   make -j SUB_PROJECT=vcu118 CONFIG=FPGASingleRocketVCU118L18K256K16WL2ConfigSBCResetEnabled bitstream
```

Build time is ~20 min on a 24-core host. Archive the result under `$CY/fpga/bitstream_storage/` with a
dated, feature-tagged name (that dir already holds the pre-StatsReset `…SBC_no-statsreset_*.bit`).

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
bash $CY/scripts/ssbc_scripts/connect_serial.sh   # picocom on /dev/ttyUSB1 @ 115200 8N1
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
./sbc_read -- <cmd>           # no reset: snapshot, run <cmd>, print the 32-bit-wrap-safe DELTA
```

**Do NOT use `SBC_Reset` (0x358) to zero counters** — it wipes the Association Table while lines are
still parked, orphaning them. `SBC_StatsReset` is the safe per-window reset.

Register offsets and semantics: [`devmem-register-map.md`](devmem-register-map.md) and
[`../sw/sbc_mmio.h`](../sw/sbc_mmio.h) (single source of truth, mirrors `Control.scala`).

## Verified on hardware (2026-09-04)

`SBC_StatsReset` was confirmed on the VCU118 Linux boot, not just in sim:

- `./sbc_read --zero` drove every event/hit counter to 0 (`migrations`, `secHits`, `attempted`,
  `L2_Accesses`, …) while **`parked` held at 792** — the live flow state was untouched, which is the
  whole point of the register.
- Successive reads then climbed cleanly from zero (`migrations 151 → 304`, `secHits 12381 → 55987`,
  `L2_Accesses 297 k → 1.24 M`), i.e. migration kept running through the reset.

This is the acceptance evidence behind the `…-verified-linux-fpga` tag.
