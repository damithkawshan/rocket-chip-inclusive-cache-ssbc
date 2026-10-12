# Running the SBC L2 on VCU118 under Linux

End-to-end recipe to build a bitstream that contains this L2, boot Linux on the VCU118, and read the
SBC / L2 counters from userspace. This is the **performance-eval path** — the real geometry (64 KB to
1 MB L2, 8 or 16 ways, 64 B lines), not the 8-set Verilator toy used for correctness.

**Updated 2026-10-12:** section 1 builds with `build_bitstream.sh`; section 7 is the **fresh-boot** A/B
(one measured window per boot). The old one-boot A/B (OFF then ON on one boot) is **not valid** any more:
results drift as the board ages. Steps 2–6 are the manual path, for debugging.

All helper scripts live in the parent tree at `chipyard/scripts/ssbc_scripts/`. Commands below assume
`CY=…/chipyard` and that you have `source $CY/env.sh` in the shell.

## 0. What you need

- Vivado 2021.1 in `PATH` (the bitstream and `programFPGA.sh` both call it).
- VCU118 board (xcvu9p): USB-JTAG cable **and** USB-UART cable (the UART shows as `/dev/ttyUSB2`).
- An SD card flashed with a FireMarshal `br-base` Linux image (see step 3).
- Host tools: `picocom` + `lrzsz` (`sz`/`rz`). `connect_serial.sh` installs them if missing.

## 1. Build the bitstream

**Use `build_bitstream.sh` (since 2026-10-12).** One script for every config. It replaces the per-config
copies (`build_256kb_8w.sh`, `build_1mb_8w*.sh`, `build_dual_*.sh`). ⚠️ The script is not yet committed
in chipyard.

```bash
tmux new -s build-<name>        # every build and board run goes in tmux
S=$CY/scripts/ssbc_scripts
bash $S/build_bitstream.sh --dry-run <FPGAConfig> <tag>   # all checks + provenance header; builds nothing
bash $S/build_bitstream.sh <FPGAConfig> <tag>
# e.g.  build_bitstream.sh FPGASingleRocketVCU118L132K512K8WL2ConfigSBCPLRU 512KB-8way-L1-32K8W-c3
```

What it does:
- **Refuses to start** if another build is running, the config class is not in `fpga/src`, or an archive
  with the same name exists. **Warns** if the L2 RTL (`design/`) has uncommitted changes
- Calls `bistream_gen_vcu118.sh <config>`: deletes that config's `generated-src` dir, then
  `make -j20 SUB_PROJECT=vcu118 CONFIG=<config> bitstream`
- **Archives** into `$CY/fpga/bitstream_storage/`, all named `<config>-<tag>-<date>`:
  - `.bit`
  - `.provenance.txt`: both repos' HEADs, the sha256 of every L2 `.scala` file, and the bit's sha256
  - `.dts`: the cache sizes, read from the build itself
  - `.config.diff`: the uncommitted part of the two config files, so an uncommitted config can be rebuilt
  - `.reports/`: Vivado timing and DRC reports
- **Logs:**
  - Driver: `fpga/build-logs/driver-<config>-<stamp>.log`. Its end has the cache sizes, `SHA256=` and the timing summary
  - make/Vivado: `fpga/build-logs/<config>-<stamp>.log`

Configs in use (`fpga/src/main/scala/vcu118/Configs.scala`; Scala side in `chipyard/.../config/RocketConfigs.scala`):

| FPGA config | L2 | L1 (I and D) | cores | image (sha256) |
|---|---|---|---|---|
| `FPGASingleRocketVCU118L132K512K8WL2ConfigSBCPLRU` | 512 KB 8-way, 1024 sets | 32 KB 8-way | 1 | `37923e6b…` — **the next config** (built 2026-10-12) |
| `FPGASingleRocketVCU118L18K256K8WL2ConfigSBCPLRU` | 256 KB 8-way, 512 sets | 8 KB 4-way | 1 | `5ace4125…` — every fresh-boot result to 2026-10-10 |
| `FPGADualRocketVCU118L18K256K8WL2ConfigSBCPLRU` | 256 KB 8-way | 8 KB 4-way | 2 | `71fa886e…` |
| `FPGASingleRocketVCU118L132K1024K8WL2ConfigSBCPLRU` | 1 MB 8-way, 2048 sets | 32 KB 8-way | 1 | task 013's A/B pair — set aside 2026-10-12 |
| 16-way configs (64 / 128 / 256 KB, `bistream_gen_vcu118.sh sbc_64l2` etc.) | | 8 KB | 1 | older. **256 KB 16-way fails Vivado DRC** (bug B7-2) |

- The 8-way configs have **no SBC-off twin**. The off arm is the same image with `--migrate=off`
- **Never run `make clean` in `fpga/`**: it deletes all of `generated-src`, including other configs'
  bitstreams that were never archived
- **Cite an image by its sha256, never by its filename.** Two files were once archived under wrong names
- Build time: 23–24 min for 128 / 256 KB (2026-09-17); **39 min for 512 KB** (2026-10-12)

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

- Loading Linux from the SD card takes about 21 min; a full program → boot → stage is about 28 min
- What the board runs (kernel 6.6.0, OpenSBI 1.2, busybox 1.36.0 with `taskset` but no `timeout`, SD
  partition 2 on `/mnt`, no network): CLAUDE.md, section "The Linux on the board"
- An `mmc_rescan` / hung-task message during an SD write usually recovers by itself. Run `sync` before a
  big extraction and give it time (task 016, finding F1)

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

**Set the switches before every window**; a reprogram resets both:

```bash
./sbc_read --policy=plru      # victim policy (0x490): random after every reprogram, so always set it
./sbc_read --migrate=off|on   # SBC_MigrateEnable (0x3C0): default OFF
./sbc_read                    # read both back before the window
```

Register offsets and semantics: [`devmem-register-map.md`](devmem-register-map.md) and
[`../../sw/sbc_mmio.h`](../../sw/sbc_mmio.h) (single source of truth, mirrors `Control.scala`).

## 7. Board A/B — fresh boot per window (the measurement path since 2026-10-07)

**Rule: one measured window per fresh boot, SBC off and SBC on through exactly the same steps.** With
migration never on, off-arm memory traffic rose with board age: +13% to +41% over six windows on one boot.
So an OFF window on an aged boot against an ON window on a fresh one measures board age as well as SBC.
Evidence: [015 REPORT](../coder/015-pair-verification-campaign/REPORT.md) F8, F11.

**One window** (the launcher does all of it):
1. Check the image's sha256, then reprogram (`programFPGA.sh`)
2. Boot Linux from the SD card, log in
3. Push `sbc_read` and the programs over zmodem, then `sync`
4. `sbc_read --policy=plru --migrate=<off|on>`, and read both back
5. `sbc_read --zero -- <command>`: the ONE window, run to completion (fixed work)
6. A checker writes VALID or VOID. A void window is retried on a fresh boot; the size of the effect is never used to judge validity

**How to run a campaign:**
- The harness is `scripts/ssbc_scripts/run_parsec_session.exp`, with a shared serial/staging library in
  `scripts/ssbc_scripts/lib/`
- Each campaign keeps its own copy of the harness, a launcher (`launch_*.sh`), a checker (`check_*_stage.py`)
  and a `PLAN.md` in `chipyard/scripts/logs/clean-runs/<campaign>/`. Start from the nearest campaign's `tools/`
- ⚠️ **The launchers pin the image by sha256** (`BIT1`, `BIT1_SHA` near the top of `launch_*.sh`). A new
  image, such as the 512 KB one, needs a new campaign folder with those two lines changed
- About 28 min per window; budget boots, not runs
- Full method, tools, rules and state: [fresh-boot-campaign-handoff.md](fresh-boot-campaign-handoff.md)

### 7a. ⚠️ Old: one-boot A/B with `run_board_session.exp -ab` — not valid for results

Kept for the record and for debugging. One image is programmed once and the workload runs twice on
the same boot: **migrate OFF**, then **migrate ON**. Don't quote its results as an SBC effect: the ON half
always runs on an older boot than the OFF half.

The sequence as used for the 128 KB run on 2026-09-17:

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

To re-measure an archived image, skip steps 1–3 and pass the archived file to `-bit`. To build, use
section 1 instead of steps 1–3.

- **Log:** `$CY/scripts/logs/board_session_<stamp>.log`. Each half ends in an `[SBC-WINDOW]` dump. Check
  `workload rc=0` in **both** halves: `sbc_read` returns the child's exit status but never prints it.
- **Workload:** the `WORKLOADS` list in the script, run word for word in both halves. Today it is
  `520.omnetpp_r` ref with `--sim-time-limit=0.002s` under `sbc_read --zero --` (fixed work, not fixed time).
- **Order is fixed, OFF first.** The ON half starts with `sbc_read --reset-all --migrate=on`, which is
  only legal while nothing is parked.
- Other flags: `-noprog` (board already programmed), `-poll N` (sample counters every N s),
  `-tmo N` (per-workload limit). The usage block at the top of the script lists them all.

One-boot runs: [board-128kb-omnetpp-2026-09-17.md](../performance/board-128kb-omnetpp-2026-09-17.md) (128 KB) and the
64 KB board sections of [coder/007 REPORT](../coder/007-paper-aligned-eviction/REPORT.md). Fresh-boot runs:
[fresh-boot-experiments-2026-10-09.md](../performance/fresh-boot-experiments-2026-10-09.md) and
[coder/016 REPORT](../coder/016-bodytrack-sbc-sweep/REPORT.md).

## Verified on hardware (2026-09-04)

`SBC_StatsReset` was confirmed on the VCU118 Linux boot, not just in sim:

- `./sbc_read --zero` drove every event/hit counter to 0 (`migrations`, `secHits`, `attempted`,
  `L2_Accesses`, …) while **`parked` held at 792** — the live flow state was untouched, which is the
  whole point of the register.
- Successive reads then climbed cleanly from zero (`migrations 151 → 304`, `secHits 12381 → 55987`,
  `L2_Accesses 297 k → 1.24 M`), i.e. migration kept running through the reset.

This is the acceptance evidence behind the `…-verified-linux-fpga` tag.
