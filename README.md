# Rocket Chip SoC Inclusive Cache Generator

> ## This fork: the Set-Balancing Cache (SBC) — optimization phase, 2026-09-25
>
> A research fork of SiFive's inclusive L2 adding the **Set-Balancing Cache**: when one set runs hot, a
> line is moved into a colder partner set and served from there. Everything below this box is the
> upstream generator's own README and still applies.
>
> ### The main result
>
> Measured on an FPGA over 80 A/B points, **set balancing pays exactly while a hot set's overflow fits
> the empty ways of its partner**:
>
> > **`overflow ≤ spare`**  (equivalently `MP ≤ 2·ways − HP`)
>
> The rule holds on **78 of 80 points**. Inside the region: up to **−41% cycles** and **−85% memory
> reads**. Outside: up to **+14% cycles** and **+24% reads** — and half the plane is outside, so being
> out of range is a real loss, not merely no gain.
>
> ![operating envelope](ai-documents/performance/figs-calib-envelope-2026-09-25/envelope.png)
>
> ### Where the work stands
>
> | | |
> |---|---|
> | Correctness | Correct data with SBC on — simulation tests, directed tests and real programs |
> | Speed, synthetic workload inside the envelope | **−41% cycles, −85% memory reads** |
> | Speed, real workload (omnetpp, 64 KB 16-way) | **parity** — it sits far outside the envelope |
> | Where the gain comes from | **77% the source set no longer thrashing**, 23% serving moved lines |
> | Phase | **Optimization.** Build phases and the destination-side tasks are complete |
> | Next | An adaptive throttle: stop migrating when the overflow does not fit |
>
> ### Where to read next
>
> - [ai-documents/performance/sbc-findings-2026-09-25.md](ai-documents/performance/sbc-findings-2026-09-25.md)
>   — **start here**: what is settled, what it overturns, what to build
> - [ai-documents/performance/board-calib-envelope-2026-09-25.md](ai-documents/performance/board-calib-envelope-2026-09-25.md)
>   — the 80-point experiment, method and caveats
> - [ai-documents/README.md](ai-documents/README.md) — doc index and per-task status tracker
> - [CLAUDE.md](CLAUDE.md) — how to build, simulate and measure, and the traps that have cost us time
>
> ### Rules that keep biting us
>
> - Name a bitstream by its **sha256**, never its filename — two files were archived under wrong names.
> - A green simulation is not a green board. Task 009 was sim-green and still hung the FPGA.
> - **"Hits per parked line" decides nothing** — among winning points it ranges 0.19 to 295.

This `block` package contains an RTL generator for creating instances of a coherent, last-level, inclusive cache.
The `InclusiveCache` controller enforces coherence among a set of caching clients
using an invalidation-based coherence policy implemetated on top of the the TileLink 1.8.1 coherence messaging protocol.
This policy is implemented using a full-map of directory bits stored with each cache block's metadata tag.

The `InclusiveCache` is a TileLink adapter;
it can be used as a drop-in replacement for Rocket-Chip's `tilelink.BroadcastHub` coherence manager.
It additionally supplies a SW-controlled interface for flusing cache blocks based on physical addresses.

The following parameters of the cache are easily `Config`-urable: 
size, ways, banking and sub-banking factors, external bandwidth, network interface buffering.

Stand-alone unit tests coming soon.

This repository is a replacement for https://github.com/sifive/block-inclusivecache-sifive
