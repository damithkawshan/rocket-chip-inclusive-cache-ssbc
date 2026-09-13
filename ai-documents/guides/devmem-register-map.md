# SBC L2 MMIO Register Map — devmem Reference

Control block base: **`0x2010000`**  
Authoritative source: [`Control.scala`](../../design/craft/inclusivecache/src/Control.scala)

## Register Table

| Offset | Command | What you get |
|--------|---------|--------------|
| `0x000` | `devmem 0x2010000 64` | Config word: `banks(8b) \| ways(8b) \| lgSets(8b) \| lgBlockBytes(8b)` |
| `0x300` | `devmem 0x2010300 32` | R/W — set index to observe (write a set number here first) |
| `0x308` | `devmem 0x2010308 32` | Saturation counter of the set selected above |
| `0x310` | `devmem 0x2010310 32` | DSS coldest destination set index |
| `0x318` | `devmem 0x2010318 32` | Saturation level of that coldest set |
| `0x320` | `devmem 0x2010320 8`  | Status: `bit0`=SBC enabled, `bit1`=coldestValid, `bit2`=AT[sel].valid |
| `0x328` | `devmem 0x2010328 64` | Migrations committed |
| `0x330` | `devmem 0x2010330 64` | Secondary hits (parked line served from the partner set) |
| `0x338` | `devmem 0x2010338 64` | Secondary misses (partner set searched, line not there) |
| `0x348` | `devmem 0x2010348 64` | Migrations attempted (setup reached) |
| `0x350` | `devmem 0x2010350 64` | Migrations aborted (ineligible src/dst) |
| `0x358` | `devmem 0x2010358 32 1` | W — write any value to zero all SBC observation state (sat, AT, armed, DSS, event counters). **Unsafe while lines are parked** — it wipes the AT, which is a parked line's only home-set record |
| `0x360` | `devmem 0x2010360 64` | Secondary hits that had to acquire permission |
| `0x368` | `devmem 0x2010368 64` | Serves where the requester needed T |
| `0x370` | `devmem 0x2010370 64` | Serves that probed a client off the parked line first |
| `0x378` | `devmem 0x2010378 64` | Dirty parked lines written back |
| `0x380` | `devmem 0x2010380 64` | Clean parked lines released with no data |
| `0x388` | `devmem 0x2010388 64` | Serves raised by a C-channel Release |
| `0x390` | `devmem 0x2010390 64` | Requests that found their own HOME line in BRANCH |
| `0x398` | `devmem 0x2010398 32` | AT[sel]: bits[7:0]=assocSet, bit8=sd |
| `0x3A0` | `devmem 0x20103A0 64` | Live displaced (parked) lines currently resident |
| `0x3A8` | `devmem 0x20103A8 64` | `L2_Accesses` — total primary directory lookups, free-running |
| `0x3B0` | `devmem 0x20103B0 64` | `L2_Hits` — total primary hits (misses = accesses − hits) |
| `0x3B8` | `devmem 0x20103B8 32 1` | W — zero ONLY the event/hit counters. Safe mid-run; the SBC flow is untouched |
| `0x3C0` | `devmem 0x20103C0 32` | R/W — `SBC_MigrateEnable`. **Default 0.** 1 lets a new migration start; 0 stops new ones. Never affects a line already parked |
| `0x3C8` | `devmem 0x20103C8 64` | `L2_MemReads` — blocks read from main memory |
| `0x3D0` | `devmem 0x20103D0 64` | `L2_MemWrites` — dirty blocks written to main memory |
| `0x3D8` | `devmem 0x20103D8 64` | `L2_MemUpgrades` — permission only, no bytes |
| `0x3E0` | `devmem 0x20103E0 64` | `L2_MemRelClean` — clean eviction announced, no bytes |
| `0x3E8` | `devmem 0x20103E8 64` | `L2_Cycles` — free-running L2 clock, zeroed by `0x3B8` |

Note: `0x340` (`SBC_BalanceSet`) is write-only and **dead in Phase 2** — see below.

## Prefer `sbc_read` over raw devmem

`sw/sbc_read.c` reads every register above in one pass, prints the switch state and the derived
rates, and refuses unsafe operations. Raw `devmem` is for poking one register in isolation.

```bash
./sbc_read                                  # everything, plus hit rates and memory traffic
./sbc_read --migrate=on                     # 0x3C0 = 1, with read-back verification
./sbc_read --reset-all --migrate=on         # clean slate then enable (refuses if anything is parked)
./sbc_read --zero -- ./bench                # run to completion; absolute counts ARE the window
```

### The one-bitstream A/B

```bash
./sbc_read --migrate=off             ; ./bench    # plain-L2 half - parks nothing
./sbc_read --reset-all --migrate=on  ; ./bench    # SBC half
```

Run the OFF half **first**. It parks nothing, which is the only reason `--reset-all` is legal before
the ON half. The reverse order needs a reboot — nothing in software can un-park a line, and
`sbc_read` will refuse the reset rather than orphan them. `run_board_session.exp` automates this.

## Common Recipes

### Inspect a specific set's saturation counter
```bash
devmem 0x2010300 32 42      # select set 42
devmem 0x2010308 32         # read its saturation counter
```

### Check migration health
```bash
devmem 0x2010328 64   # committed
devmem 0x2010348 64   # attempted
devmem 0x2010350 64   # aborted
# healthy ratio: committed close to attempted, aborted low
```

### Check DSS state
```bash
devmem 0x2010310 32   # coldest candidate set index
devmem 0x2010318 32   # its saturation level (should be < T_lo = nWays)
devmem 0x2010320 8    # status word; bit1=1 means coldestValid (DSS has a candidate)
```

### Cold-start SBC state between runs
```bash
devmem 0x2010358 32 1   # zero all SBC observation state (issue while cache is quiescent)
```

### Decode the config word (offset 0x000)
```
bits [7:0]   = banks
bits [15:8]  = ways      (K; used to verify threshold derivation: T_hi=2K-1, T_lo=K)
bits [23:16] = lgSets
bits [31:24] = lgBlockBytes
```

⚠️ **Corrected 2026-09-11.** This block previously read `[63:56]=banks … [39:32]=lgBlockBytes`, which
is backwards. `RegFieldGroup` packs its `Seq` from the **LSB up** — see `RegMapper.scala:44`,
`fields.scanLeft(byte * 8)(_ + _.width)` — so the first field in the `Seq` takes the lowest bits.
`sbc_read` reads `lgBlockBytes` from `[31:24]` to size "bytes moved", and sanity-checks it (anything
outside 16 B…512 B falls back to 64 B) rather than printing a wrong byte count.
For the FPGA config (`nWays=16, capacityKB=256`): ways=16, lgSets=8 (256 sets), lgBlockBytes=6 (64B).

## Migration Health Interpretation

| Scenario | committed | attempted | aborted | Meaning |
|----------|-----------|-----------|---------|---------|
| Healthy | ≈ attempted | high | low | DSS spreading normally; src hot, dst cold |
| `sbcForceDstSet=0` | moderate | high | ~96% | Expected — set 0 fills fast, all subsequent aborted on "dst full" |
| Never migrates | 0 | 0 | 0 | DSS never finds a cold set (`coldestLevel >= T_lo`), or no hot sets |
| All abort, no commit | 0 | high | high | DSS candidate always full when migration reaches alloc; check T_lo vs. actual counter levels |

## Phase 2 Notes

- **`0x340` (`SBC_BalanceSet`) arm-write is dead in Phase 2 auto mode** — `sbcAutoMigrate = true` triggers migrations from the demand MSHR, not from SW writes. The register remains wired for backward compat but has no effect.
- **`0x330` / `0x338`** (secondary hits/misses) are live since serve-in-place (task 003, 2026-08-30).
- **Thresholds** (as of the way-dependent change): for `nWays=K`, saturation range is `[0, 2K-1]`, `T_hi = 2K-1`, `T_lo = K` (`Configs.scala:129-130`; corrected 2026-09-14 — it said `T_hi = K`, `T_lo = K-2`). Verify `SBC_ColdestLevel` (0x318) is below `T_lo` to confirm migrations are actually being triggered.
