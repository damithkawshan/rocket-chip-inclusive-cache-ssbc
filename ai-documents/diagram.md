# SBC — Migration + Always-Use reuse flow (visual)

**Part A (migration / spill): COMPLETE & verified** (forced torture config: 20000 iters, PASS, 0
asserts, 10 migrations committed, data correct). **Part B (always-use reuse): PLANNED** — the current
Phase-3 architecture, not built yet. Companion to [phase-3.md](phase-3.md) (the words, always-use SSOT),
[phase-2.md](phase-2.md), and [bug-fix-log.md](bug-fix-log.md) (the bugs).

**Colour key:** 🟢 green = built & verified · 🟡 yellow = fallback / rare · 🔵 blue = Phase-3 planned.

## The idea, in plain English
- **Spill (Part A, built).** A **hot** set keeps missing. Instead of throwing away a clean victim, the
  cache **copies it to a cold partner set** and puts the new line in the freed slot. The copy is parked
  as a **displaced** line.
- **Always-use (Part B, planned).** On a later miss, the cache **searches the partner first**. If the
  line is there, it **swaps it home and serves it** — no memory trip. That saved trip is the speedup.
- **Pinning.** Once a source pairs with a partner, **all** its spills go there and **all** its searches
  look there, until teardown. One partner at a time (strict 1:1).
- **Safety net.** A displaced line is always **clean**, so any awkward case degrades to "drop the copy,
  fetch from memory" — safe, just no speedup that time.

---

## Part A — Migration / spill (Phase 2, built & verified)

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000","activationBkgColor":"#f2f2f2","activationBorderColor":"#666666"}}}%%
sequenceDiagram
    autonumber
    participant CPU as CPU (TL-C client)
    participant SA  as SinkA
    participant M   as MSHR (demand = migrate owner)
    participant D   as Directory
    participant SBU as SBU / DSS / AT
    participant SCU as SetCopyUnit
    participant SD  as SourceD
    participant SB  as SinkB / SourceB
    participant BS  as BankedStore
    participant Mem as Outer memory

    Note over CPU,Mem: Phase 2 — migrate-on-eviction (green = built & verified)

    %% STEP 1 — demand miss
    rect rgb(223,240,216)
    Note over CPU,M: STEP 1 — a demand miss arrives. One MSHR is allocated for set s.
    CPU->>SA: AcquireBlock [A]  (NtoB / NtoT)
    SA->>M:  allocate MSHR for set s
    M->>D:   dir-read(s)  preferEvictable=true
    D-->>M:  pick victim vWay — must be valid, clean, no clients, not displaced
    end

    %% STEP 2 — optional probe (rare, clean victims skip this)
    opt victim has clients
        rect rgb(255,243,205)
        Note over M,SB: If the victim is shared, shrink it first. A dirty probe-ack means abort migrate.
        M->>SB:  Probe [B] toN
        SB-->>M: ProbeAck [C] (BtoN/TtoN)
        end
    end

    %% STEP 3 — the migrate gate
    rect rgb(223,240,216)
    Note over M,SBU: STEP 3 — should we migrate? Ask the SBU.
    M->>SBU: is set s hot? is there a cold set? is the token free?
    SBU-->>M: yes — migrate to cold set d
    M->>M:   reserve d (dstValid=1). The fence now protects set d.
    end

    %% STEP 3b — Phase-3 pinning change (planned)
    rect rgb(207,226,255)
    Note over M,SBU: Phase-3 change (planned): if s is ALREADY paired, destination = its partner —<br/>ignore the DSS/coldness. Only an UNPAIRED source consults the DSS (and skips already-paired sets).
    end

    %% STEP 4 — 2nd dir-read of the cold set
    rect rgb(223,240,216)
    Note over M,D: STEP 4 — find a parking spot in d.
    M->>D:   dir-read(d)  preferInvalid=true, preferEvictable=true
    D-->>M:  dWay — a free way, else a clean way we can overwrite
    end

    %% STEP 5 — three outcomes
    alt d has no usable way (all dirty / client-held)
        rect rgb(255,243,205)
        Note over M: ABORT-DST. Drop the migration. Do a normal eviction. Safe, rare.
        M->>Mem: Release [C] (clean victim, no data)
        Mem-->>M: ReleaseAck [D]
        end
    else d has a free OR clean way  (the migrate path)
        rect rgb(223,240,216)
        Note over M,SCU: STEP 5 — copy the victim block s → d.
        Note over SCU,SD: Two safety gates: copy_safe (don't read what SourceD will write),<br/>copy_wsafe (don't write what SourceD is still reading).
        M->>SCU: copy (s,vWay) → (d,dWay)
        SCU->>BS: read (s,vWay) beat-by-beat  (copy_safe)
        BS-->>SCU: block buffered
        SCU->>BS: write (d,dWay) beat-by-beat (copy_wsafe)
        SCU-->>M: copy_done
        end
    end

    %% STEP 6 — fetch the demanded line
    rect rgb(223,240,216)
    Note over M,Mem: STEP 6 — fetch the new line. Held until the copy read is done (A2 interlock).
    M->>Mem:  AcquireBlock [A]
    Mem-->>M: GrantData [D]
    end

    %% STEP 7 — two dir-writes, grant, commit
    rect rgb(223,240,216)
    Note over M,SBU: STEP 7 — write the directory twice, answer the CPU, commit.
    M->>D:   dir-write one — install displaced copy at (d,dWay)
    M->>D:   dir-write two — install the demanded line at (s,vWay)
    M->>BS:  write the new line's data at (s,vWay)
    M->>SD:  GrantData / Grant [D] → CPU
    CPU-->>M: GrantAck [E]
    M->>SBU: commit — record s↔d, migrationCount++
    M->>M:   retire — drop the reservation (dstValid=0)
    end

    Note over D,Mem: Part A is BUILT and VERIFIED. The blue note (pinning) is the one planned change to this path.
```

---

## Part B — Always-use reuse / swap (Phase 3, planned)

Assumes Part A already parked line **L** in partner set **d**, and **s** is still paired with **d**.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000","activationBkgColor":"#f2f2f2","activationBorderColor":"#666666"}}}%%
sequenceDiagram
    autonumber
    participant CPU as CPU (TL-C client)
    participant SA  as SinkA
    participant M   as MSHR
    participant D   as Directory
    participant SBU as SBU / AT
    participant SCU as SetCopyUnit
    participant SD  as SourceD
    participant BS  as BankedStore
    participant Mem as Outer memory

    Note over CPU,Mem: Phase 3 — always-use (blue = planned). Search the partner BEFORE going to memory.

    %% STEP 1 — miss + association check
    rect rgb(223,240,216)
    Note over CPU,SBU: STEP 1 — demand miss in home set s (Phase-2 machinery, unchanged)
    CPU->>SA: AcquireBlock [A] for line L
    SA->>M:  allocate MSHR for set s
    M->>D:   dir-read(s)
    D-->>M:  MISS (displaced ways can't hit)
    M->>SBU: is s a paired source?
    SBU-->>M: yes — partner is d
    end

    %% STEP 2 — mandatory secondary search, memory Acquire HELD
    rect rgb(207,226,255)
    Note over M,D: STEP 2 — NEW: hold the memory Acquire. Search the partner first.
    M->>M:   reserve d (dstValid=1 — the fence now protects set d)
    M->>D:   dir-read(d)  secondarySearch=true, tag=L
    D-->>M:  is L displaced in d? → dWay + state
    end

    %% STEP 3 — three outcomes
    alt L not in d (secondary miss)
        rect rgb(255,243,205)
        Note over M,Mem: Fallback A — exactly today's path, just ~2 cycles later.
        M->>M:   drop reservation, SecMiss++
        M->>Mem: AcquireBlock [A]
        Mem-->>M: GrantData [D]
        end
    else L in d but permissions too weak (CPU wants T, copy is B)
        rect rgb(255,243,205)
        Note over M,D: Fallback B — copy cannot satisfy a write-intent request. Drop it (clean, silent) and fetch.
        M->>D:   dir-write INVALID at (d,dWay)
        M->>Mem: AcquireBlock [A]
        Mem-->>M: GrantData [D]
        end
    else L in d, permissions OK — THE SWAP (secondary hit)
        rect rgb(207,226,255)
        Note over M,SCU: STEP 3 — NEW: true swap. Read both blocks, write them crossed. Zero memory traffic.
        Note over M: (if s's victim V is dirty / client-held: release V normally + still home L — half-swap)
        M->>D:   pick victim vWay in s (prefer invalid / evictable)
        M->>SCU: swap (d,dWay) ⇄ (s,vWay)
        SCU->>BS: read L from (d,dWay) → buf A  (copy_safe)
        opt s's victim V is eligible (valid, clean, no clients)
            SCU->>BS: read V from (s,vWay) → buf B
        end
        SCU->>BS: write buf A (=L) → (s,vWay)  (copy_wsafe)
        opt V present
            SCU->>BS: write buf B (=V) → (d,dWay)
        end
        SCU-->>M: swap_done
        M->>D:   dir-write one @ (d,dWay): V displaced=true  (or INVALID if no V)
        M->>D:   dir-write two @ (s,vWay): L native, displaced=false
        Note over M,SD: swap-then-replay — re-run the request, now it HITS natively in s
        M->>D:   dir-read(s)  (replay)
        D-->>M:  HIT — L native @ (s,vWay)
        M->>SD:  GrantData [D] → CPU   (unmodified hit path: probes / perms / grant reused)
        CPU-->>M: GrantAck [E]
        M->>SBU: SecHits++ — if d now empty of s's lines → teardown (erase AT[s], AT[d])
        M->>M:   retire — drop reservation (dstValid=0)
        end
    end

    Note over D,Mem: Secondary hit = ZERO outer bandwidth. Stale twins impossible:<br/>a refill only ever happens after d was searched and found empty of L.
```

---

## Correctness prerequisite (Phase 3 — build this first)

- **Pinned 1:1 association.** One partner per source until teardown; a set already in a pairing cannot
  be picked as a new destination. Without it, a source's lines scatter across sets, the AT remembers
  only one, and the search looks in the wrong place → duplicate copies → the stale-data hole reopens.
- *(Flush fix dropped 2026-07-05: MMIO flush (`Flush64`/`Flush32`) is unsupported on this platform —
  documented constraint: never use MMIO flush while SBC is enabled. No hardware built.)*

---

## What changed since the last version of this diagram (plain English)
- **Phase 3 is now "always-use," not "detect-first."** We no longer build a read-only detector and
  measure before the swap — the detector's number is poisoned by stale copies. On every miss to a
  paired source we search the partner and swap the line home if found.
- **Serving is "swap-then-replay."** Do only the physical swap + two dir-writes, then re-run the
  request's lookup so the **unmodified hit path** serves it. No new grant/permission datapath.
- **Destination pinning** (planned) modifies the Part-A migrate gate: a paired source always spills to
  its partner and ignores the DSS.
- **Part-A migration is unchanged and still verified** — the destination-collision and set-bricking
  bugs remain FIXED; `s_verify` is still OFF (re-enable rises in priority now that Part B *serves*
  parked copies to the CPU). See [bug-fix-log.md](bug-fix-log.md).

---

## TileLink messages used

| Channel | Direction | Message | When |
|---|---|---|---|
| A (inner) | CPU → L2 | `AcquireBlock` | demand miss enters |
| B | L2 → CPU | `Probe(toN)` | victim is shared (shrink before migrate) |
| C | CPU → L2 | `ProbeAck` / `ProbeAckData` | client shrink; data means dirty → abort migrate |
| C | L2 → Mem | `Release` | **abort / half-swap only** — normal clean eviction |
| A (outer) | L2 → Mem | `AcquireBlock` | fetch line (spill refill, or a secondary **miss**) |
| D (outer) | Mem → L2 | `GrantData` | memory returns the line |
| D (inner) | L2 → CPU | `GrantData` / `Grant` | L2 answers the CPU |
| E | CPU → L2 | `GrantAck` | CPU confirms |

**Key point:** on the migrate path *and* on a secondary **hit**, the line's data moves inside the
cache (a BankedStore copy / swap) — **no outer `Acquire`, zero outer bandwidth**. A secondary **miss**
falls back to a normal memory `Acquire`.

---

## MSHR states (simplified)

**Migrate / spill path (Part A, built):**
```
allocate (set s)
   │  (if victim shared) probe → probe-ack
   ▼
migrate gate: is s hot, is there a cold set, is the token free?
   │  yes → reserve d, migrating = true     [Phase-3: if s paired, d = its partner]
   ▼
2nd dir-read (set d) ──► all ways unusable ──► ABORT: normal eviction (Release)
   │ free or clean way
   ▼
copy s → d   (copy_safe read gate, copy_wsafe write gate)   [s_verify DISABLED]
   ▼
dir-write #1 (install displaced @ d)
fetch line   (Acquire → GrantData, held by A2 interlock)
dir-write #2 (install demanded line @ s)
grant to CPU → GrantAck
   ▼
commit (record s↔d, count++) → retire (drop reservation)
```

**Always-use / swap path (Part B, planned):**
```
allocate (set s) → dir-read(s) = MISS
   │  s paired?  ── no ──► normal memory Acquire (unchanged)
   │  yes → reserve d
   ▼
secondary search: dir-read(d, secondarySearch, tag=L)
   │  not found ──────────► SecMiss++, normal memory Acquire
   │  found, perms weak ──► invalidate (d,dWay), normal memory Acquire
   │  found, perms OK
   ▼
swap: read L(d) + V(s) → write L→(s,vWay), V→(d,dWay)   [half-swap if V dirty/held]
   ▼
dir-write #1 (V displaced @ d, or INVALID)
dir-write #2 (L native @ s)
   ▼
swap-then-replay: dir-read(s) = HIT → unmodified hit path grants to CPU → GrantAck
   ▼
SecHits++; teardown-check on d → retire (drop reservation)
```
