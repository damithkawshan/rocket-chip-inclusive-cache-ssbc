# Cache terminology — hit, miss, secondary hit

**Status:** 🟢 use now · **Agreed with the user:** 2026-09-14

The words we use for what happens to a request in our L2 cache. Use them in every report, table,
counter name and doc. They follow the SBC paper, the Hennessy & Patterson textbook, and the gem5 and
zsim simulators (sources at the end), adapted to TileLink.

**This file is the single source.** [CLAUDE.md](../../CLAUDE.md) carries a short copy — if you change a
definition here, change that copy in the same edit.

## 1. Four rules

1. **Only an inner-A request is an access.** Write-backs, invalidations, flushes and SBC's own
   directory reads are never accesses.
2. **Hit or miss depends on one thing:** did the access need an **outer A** message? No → hit.
   Yes → miss.
3. **Every access ends as exactly one of:** primary hit, secondary hit, data miss, upgrade miss.
4. **Some hits cost extra time.** A secondary hit pays a second directory read; a probed hit pays a
   round trip to another L1 client. Count them separately, but they are still hits.

## 2. The table

| Term | Definition |
|---|---|
| **Access** | One request from L1 on **inner A** (`AcquireBlock`, `AcquirePerm`, `Get`, `PutFullData`, `PutPartialData`), counted **once** when accepted, however many directory reads it needs. Every access ends as exactly one of: primary hit, secondary hit, data miss, upgrade miss |
| **Home set** | The set taken from the request's address |
| **Partner set** | The set paired with the home set in the Association Table (AT) |
| **Parked line** | A line moved out of its home set into the partner set. Its directory entry has `displaced = 1` |
| **Migration** | On a data miss in a hot home set, a clean victim that no L1 holds is copied into the partner set and becomes a parked line, instead of leaving through **outer C** `Release`. No TileLink message is sent |
| **Primary hit** | The home set holds the line (tag match, `displaced = 0`) with the permission the request needs. **No outer A message.** An inner B `Probe` to another L1 client does not change this (that case is also a probed hit) |
| **Second search** | After the home set misses, when the home set has lines parked in its partner: a directory read of the partner set for a tag match with `displaced = 1`. Internal only, no TileLink message. Costs time |
| **Secondary hit** | The second search finds the line with the permission the request needs. It is served in place from the partner set. **No outer A message** |
| **Probed hit** | A primary or secondary hit where the L2 first sends **inner B** `Probe` to another L1 client, which answers with inner C `ProbeAck` / `ProbeAckData`. Still a hit (no outer A message). Counted separately because it pays an extra round trip to that client |
| **Miss** | Any access that needs an **outer A** message. There are exactly two kinds: data miss and upgrade miss |
| **Data miss** | The L2 does not hold the line, in either the home set or the partner set. Sends outer A `AcquireBlock` `NtoB`/`NtoT`, or `AcquirePerm` `NtoT` when the request overwrites the whole block. If the set is full, a victim is first removed with outer C `Release` (clean) or `ReleaseData` (dirty), or by a migration |
| **Upgrade miss** | The L2 holds the line (home set, or partner set found by the second search) but only in **BRANCH**, and the request needs **TRUNK** (write). Sends outer A with param **`BtoT`**: `AcquireBlock`, or `AcquirePerm` for a whole-block write |
| **Secondary miss** | The second search ran and did not find the line. It is always a data miss; the second search was extra cost |
| **Write-back** *(not an access)* | **Inner C** `Release` / `ReleaseData`: L1 gives a line back. The L2 always holds it (inclusion). Neither hit nor miss |
| **Invalidation** *(not an access)* | **Inner B** `Probe` (`toN` or `toB`) from L2 to L1, answered by inner C `ProbeAck` / `ProbeAckData`. Part of serving an access or evicting a victim |
| **Flush** *(not an access)* | An **X-channel** request from MMIO. It may send outer C `Release` / `ReleaseData` |
| **SBC internal read** *(not an access)* | A directory read SBC does for itself: the second search, or the migration destination read |
| **Hit rate** | (primary hits + secondary hits) ÷ accesses |
| **Miss rate** | (data misses + upgrade misses) ÷ accesses = 1 − hit rate |
| **Second-search rate** | second searches ÷ accesses |
| **Second-search hit rate** | secondary hits ÷ second searches |

## 3. Where the words come from

| Our term | Literature |
|---|---|
| Access = what reaches the L2 | SBC paper §4.1 and Table 2 ("accesses that reach the caches under the first level"); H&P local miss rate (misses in this cache ÷ accesses to this cache) |
| Secondary hit | SBC paper §2.3: "If the second search is successful, a secondary hit is obtained"; column-associative caches call it a "second-time hit" |
| Secondary miss | SBC paper §3.3 calls it a "definitive miss"; §5.2 says second searches "delay the resolution of misses" |
| Report the time cost, not only a hit rate | SBC paper §5.2: "Hit and miss rates are not the best characterization for SBCs". Its headline numbers are average access time and IPC |
| Upgrade miss | zsim counter `mGETXSM`, "GETX S->M misses (upgrade misses)"; gem5 counts it as a miss and sends `UpgradeReq`; H&P class the first write to a shared block as a true sharing miss |
| Write-backs are neither hit nor miss | zsim `PUTS` / `PUTX`, "Clean / Dirty evictions (from lower level)"; gem5 leaves writebacks out of its demand totals |
| Invalidations are counted apart | zsim `INV` / `INVX` |
| Probed hit | Our own term. zsim also counts the invalidation apart from the hit or miss |

TileLink permissions map onto the MSI model — None = Invalid, Branch = Shared, Trunk = Modified — which
is why the simulator words carry over.

## 4. Our counters against these words (2026-09-14, updated 2026-09-16)

**Since task 005 commit 1 there is a counter for every word in the table** (`0x3F0`–`0x428`), so a hit
rate can be quoted: (`L2_PrimaryHit` + `L2_SecondaryHit`) ÷ `L2_AccessA`. Read them under `L2_StatsHold`
(`0x438`) — `sbc_read` does. **Misses** are still also visible at the outer port as
`L2_MemReads + L2_MemAcqPerm`, and that must equal `L2_DataMiss + L2_UpgradeMiss` exactly; it did in four
board halves. A bitstream built before 2026-09-15 has none of these registers, and they read 0 there.

| Counter | Follows the words? | What it really counts |
|---|---|---|
| `L2_Accesses`, `L2_Hits` | ❌ legacy | Directory lookups on **every** channel: include write-backs and flushes, leave out repeats. `L2_Hits` counts upgrade misses and write-backs as hits, and secondary hits as misses. Kept unchanged so old numbers stay comparable |
| `L2_MemReads` + `L2_MemAcqPerm` | ✅ all misses | Every outer A message. Split by opcode (bytes moved or not), **not** by kind of miss: `L2_MemAcqPerm` counts `AcquirePerm` (a whole-block write), which is not the same thing as an upgrade miss (`BtoT`). Renamed from `L2_MemUpgrades` in task 005 commit 0 (same address `0x3D8`; old logs say `memUpgrades=`) |
| `SBC_SecHits` | ❌ | Every serve from the partner set, **including** write-backs (`SBC_SecC`) and upgrade misses (`SBC_SecPerm`) |
| `SBC_SecMiss` | ≈ ✅ | Secondary misses. A flush over a parked line would also count, but that case is asserted unsupported |
| `SBC_SecProbe` | ≈ | Serves from the partner set that probed first — the parked half of probed hits, but it also includes upgrade misses |
| `SBC_SecWrite` | 🐞 | Meant "serves that needed write". Also counts write-backs: C-channel `Release` / `ReleaseData` reuse the opcode numbers of `AcquireBlock` / `AcquirePerm` (6 and 7), so `needT()` is true for them. A counting mistake only — cache behaviour is not affected. Fix: task 005 |
| `SBC_Aborted` | ⚠️ | Migrations aborted after they started **plus** migrations declined before they started. That is why "attempted" is smaller than "committed + aborted". Fix: task 005 adds `SBC_Declined` |
| **Landed in task 005 commit 1** | ✅ | One counter per term, `0x3F0`–`0x428`: `L2_AccessA`, `L2_PrimaryHit`, `L2_SecondaryHit`, `L2_ProbedHit`, `L2_DataMiss`, `L2_UpgradeMiss`, `L2_SecondSearch`, `L2_SecondaryMiss`. Read them under `L2_StatsHold` (`0x438`), as `sbc_read` does. Verified on the board 2026-09-15/16: accesses = the four outcomes exactly, misses = outer-port messages exactly, second searches = secondary hits + secondary misses exactly |

## 5. Not decided here

- **The heat counters (`sat`)** are fed by directory lookups on every channel, not by accesses, and a
  secondary hit counts as a miss for the home set. Changing either one changes what SBC does, not just
  a report. Decide it together with the open question of how a partner hit should change heat.

## Sources

- SBC paper (Rolán, Fraguela, Doallo, MICRO 2009) — [../background/178-rolan-1-2.pdf](../background/178-rolan-1-2.pdf): §2.3, §3.3, §4.1, §5.2, §7.3
- Hennessy & Patterson, via course notes — [UC Davis EEC170](https://www.ece.ucdavis.edu/~soheil/private/EEC170/eec170-cache-performance.pdf), [UMD Cache Coherence II](https://www.cs.umd.edu/~meesh/411/CA-online/chapter/312/index.html)
- Column-associative caches (Agarwal & Pudar, ISCA 1993) — [MIT DSpace](https://dspace.mit.edu/entities/publication/53f1c6ad-4d34-4227-a2b0-fcd9548d2284), [summary](https://wangziqi2013.github.io/paper/2020/05/30/column-associative-cache.html)
- gem5 classic cache — [base.cc](https://github.com/gem5/gem5/blob/stable/src/mem/cache/base.cc), [cache.cc](https://github.com/gem5/gem5/blob/stable/src/mem/cache/cache.cc)
- zsim MESI controller — [coherence_ctrls.h](https://github.com/s5z/zsim/blob/master/src/coherence_ctrls.h), [coherence_ctrls.cpp](https://github.com/s5z/zsim/blob/master/src/coherence_ctrls.cpp)
- TileLink specification 1.8.1 — [PDF](https://sifive.cdn.prismic.io/sifive/7bef6f5c-ed3a-4712-866a-1a2e0c6b7b13_tilelink_spec_1.8.1.pdf)
