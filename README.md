# Rocket Chip SoC Inclusive Cache Generator

> ## This fork: the Set-Balancing Cache (SBC) — status 2026-09-25
>
> This is a research fork of SiFive's inclusive L2. It adds the **Set-Balancing Cache**: when one set
> runs hot, a line is moved into a colder partner set and served from there. Everything below this box
> is the upstream generator's own README and still applies.
>
> **Where the work stands**
>
> | | |
> |---|---|
> | Correctness | The cache returns correct data with SBC on — simulation tests and real programs |
> | Speed | SBC is at **parity** with the plain L2, not yet ahead. PLRU replacement on its own **is** ahead (−3.56% cycles, −12.65% memory traffic on the board) |
> | Active task | **012** — re-landing the destination-eviction change that hung the board as task 009, one stage at a time |
> | Built and sim-green | The half that needs no help from the CPU (a dirty destination line with no CPU copy is written back, then its slot reused) |
> | Not built, on purpose | The half where a CPU still holds the line. That needs the CPU to answer while the row is fenced, which is exactly the wait that hung the board |
> | On the board now | Image `af11762b…3ec4`. Baseline for comparison: `e41f780c…d177` (1029 s and 1030 s, both clean) |
>
> **Where to read next**
>
> - [ai-documents/README.md](ai-documents/README.md) — the index and the status tracker for every task
> - [CLAUDE.md](CLAUDE.md) — how to build, simulate and measure, and the traps that have cost us time
> - [ai-documents/coder/012-reland-destination-eviction/](ai-documents/coder/012-reland-destination-eviction/) —
>   the active task: the work order, the report, and a [diagram](ai-documents/coder/012-reland-destination-eviction/diagram.md)
>   of what changed in the migration transaction
>
> **Two rules that keep biting us**
>
> - Name a bitstream by its **sha256**, never by its filename — two files have been found archived under
>   the wrong name.
> - A green simulation is not a green board. Task 009 was sim-green and still hung the FPGA.


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
