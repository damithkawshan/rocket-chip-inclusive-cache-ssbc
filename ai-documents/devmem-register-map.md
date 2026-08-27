# SBC L2 MMIO Register Map — devmem Reference

Control block base: **`0x2010000`**  
Authoritative source: [`Control.scala`](../design/craft/inclusivecache/src/Control.scala)

## Register Table

| Offset | Command | What you get |
|--------|---------|--------------|
| `0x000` | `devmem 0x2010000 64` | Config word: `banks(8b) \| ways(8b) \| lgSets(8b) \| lgBlockBytes(8b)` |
| `0x300` | `devmem 0x2010300 32` | R/W — set index to observe (write a set number here first) |
| `0x308` | `devmem 0x2010308 32` | Saturation counter of the set selected above |
| `0x310` | `devmem 0x2010310 32` | DSS coldest destination set index |
| `0x318` | `devmem 0x2010318 32` | Saturation level of that coldest set |
| `0x320` | `devmem 0x2010320 8`  | Status: `bit0`=SBC enabled, `bit1`=coldestValid, `bit2`=AT[sel].valid |
| `0x328` | `devmem 0x2010328 32` | Migrations committed |
| `0x330` | `devmem 0x2010330 32` | Secondary hits (0 until Phase 3) |
| `0x338` | `devmem 0x2010338 32` | Secondary misses (0 until Phase 3) |
| `0x348` | `devmem 0x2010348 32` | Migrations attempted (setup reached) |
| `0x350` | `devmem 0x2010350 32` | Migrations aborted (ineligible src/dst) |
| `0x358` | `devmem 0x2010358 32 1` | W — write any value to zero all SBC observation state (sat, AT, armed, DSS, event counters) |

Note: `0x340` (`SBC_BalanceSet`) is write-only and **dead in Phase 2** — see below.

## Common Recipes

### Inspect a specific set's saturation counter
```bash
devmem 0x2010300 32 42      # select set 42
devmem 0x2010308 32         # read its saturation counter
```

### Check migration health
```bash
devmem 0x2010328 32   # committed
devmem 0x2010348 32   # attempted
devmem 0x2010350 32   # aborted
# healthy ratio: committed close to attempted, aborted low
```

### Check DSS state
```bash
devmem 0x2010310 32   # coldest candidate set index
devmem 0x2010318 32   # its saturation level (should be < T_lo = nWays-2)
devmem 0x2010320 8    # status word; bit1=1 means coldestValid (DSS has a candidate)
```

### Cold-start SBC state between runs
```bash
devmem 0x2010358 32 1   # zero all SBC observation state (issue while cache is quiescent)
```

### Decode the config word (offset 0x000)
```
bits [63:56] = banks
bits [55:48] = ways      (K; used to verify threshold derivation: T_hi=K, T_lo=K-2)
bits [47:40] = lgSets
bits [39:32] = lgBlockBytes
```
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
- **`0x330` / `0x338`** (secondary hits/misses) are always 0 until Phase 3 (AT-based secondary search).
- **Thresholds** (as of the way-dependent change): for `nWays=K`, saturation range is `[0, 2K-1]`, `T_hi = K`, `T_lo = K-2`. Verify `SBC_ColdestLevel` (0x318) is below `T_lo` to confirm migrations are actually being triggered.
