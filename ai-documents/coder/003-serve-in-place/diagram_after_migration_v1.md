# After migration — every transaction, with sample data

**Status: as built and passing, 2026-08-30** (`sbc-serve-in-place-gate4-green`, GATE 4 green, 7/7).

This file is **only transaction diagrams**. It answers one question: once a line has been migrated out
of its home set, what happens to it next — in every case.

---

## The cast (real values from the passing run)

Geometry: **8 sets, 8 ways, 64-byte blocks.** Pairing under test: **set 5 is the hot source, set 7 is
its cold partner.**

| name | address | home set | tag as the directory records it | role |
|---|---|---:|---|---|
| **A** | `0x81000340` | 5 | `0x88001` | the line that gets migrated, then re-used |
| **B** | `0x80022F40` | 5 | `0x80117` | the line that refills into A's freed way |
| **V** | `0x81000140` | 5 | `0x88000` | a second resident of set 5 |

Data payloads are shown as `DA0`, `DA1`, … so you can follow which bytes are where.

> The tag column is what the directory actually stores. It is the address with the set-index and
> block-offset bits stripped, after bank interleaving — so it is **not** simply `address >> 9`. The
> values above are copied from the real run, not derived.

Directory entry shorthand used in the notes:

```
set 7 way 5 = tag 0x88001 | state TIP | displaced 1 | clients 0 | dirty 0
```

`displaced 1` means **the bytes are in row 7 but the address belongs to set 5.** That one bit is the
whole design.

---

## Index

| # | scenario | key point |
|---|---|---|
| 1 | Migration — how A gets parked | the setup for everything below |
| 2 | Read of a parked line, entry is TIP | serve in place, nothing moves |
| 3 | Write of a parked line, entry is TIP | serve, entry becomes TRUNK, stays displaced |
| 4 | Write of a parked line, entry is TRUNK | **probe the holder back, then serve** — the 9a fix |
| 5 | Write of a parked line, entry is BRANCH | serve **and** fetch permission — built, unexercised |
| 6 | Client releases a parked line dirty | a displaced line becomes dirty — the 9b consequence |
| 7 | Evicting a dirty parked line | Release must use the **home** address, not the row |
| 8 | Not parked anywhere | ordinary miss, for contrast |
| 9 | MMIO flush of a parked line | unsupported, and now asserted |

---

## 1 — Migration: how A gets parked in set 7

Set 5 is hot. A demand for **B** misses in set 5. Instead of throwing A away, the L2 moves it to the
cold partner and gives its way to B.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000"}}}%%
sequenceDiagram
    autonumber
    participant CPU as CPU / L1
    participant M as MSHR
    participant D as Directory
    participant AT as AT / SBU
    participant SCU as SetCopyUnit
    participant BS as BankedStore
    participant MEM as Outer memory

    Note over CPU,MEM: START. set 5 way 3 = tag 0x88001 A, state TIP, displaced 0, clean, no clients. Data DA0.

    CPU->>M: AcquireBlock 0x80022F40  which is line B, home set 5
    M->>D: read set 5, tag 0x80117
    D-->>M: MISS. victim offered is way 3, holding A

    Note over M: A is clean and client-free, so it can be moved rather than dropped
    M->>AT: is set 5 paired and where to
    AT-->>M: yes, partner is set 7
    M->>D: read set 7, prefer a free way
    D-->>M: way 5 is free

    SCU->>BS: read row 5 way 3, all beats
    BS-->>SCU: DA0
    SCU->>BS: write row 7 way 5, DA0
    Note over BS: DA0 now physically sits in row 7 way 5

    M->>D: dir-write set 7 way 5 = tag 0x88001, TIP, displaced 1
    M->>AT: commit pairing. set 5 source, set 7 destination
    Note over D: set 7 way 5 = tag 0x88001 A, state TIP, displaced 1, clients 0, dirty 0

    M->>MEM: AcquireBlock 0x80022F40
    MEM-->>M: GrantData DB0
    M->>BS: write row 5 way 3, DB0
    M->>D: dir-write set 5 way 3 = tag 0x80117, TIP, displaced 0
    M-->>CPU: GrantData DB0

    Note over CPU,MEM: END. A is parked at row 7 way 5. B owns row 5 way 3. Nothing was lost.
```

**A normal lookup of set 7 will never find A** — the directory excludes `displaced` ways from ordinary
hits. Only the secondary search can see it. That is what keeps two lines with the same tag in one row
from colliding.

---

## 2 — Read of a parked line, entry is TIP

The simple win. A is parked, nobody holds it, and the CPU wants to read it.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000"}}}%%
sequenceDiagram
    autonumber
    participant CPU as CPU / L1
    participant M as MSHR
    participant D as Directory
    participant AT as AT / SBU
    participant SCU as SetCopyUnit
    participant BS as BankedStore
    participant SD as SourceD

    Note over CPU,SD: set 7 way 5 = tag 0x88001 A, TIP, displaced 1, clients 0, dirty 0. Data DA0.
    Note over SCU: IDLE for the whole transaction. Serving in place copies nothing.

    CPU->>M: Get 0x81000340  which is line A, home set 5
    M->>D: read set 5, tag 0x88001
    D-->>M: MISS. victim way offered, but do not evict yet

    Note over M: DEFER. arm nothing. no eviction, no fetch, no probe.
    M->>AT: is set 5 paired
    AT-->>M: partner is set 7
    M->>D: secondary search of set 7 for tag 0x88001
    D-->>M: HIT at way 5. state TIP, clients 0

    Note over M: SERVE IN PLACE.<br/>inPlace true. physSet becomes 7. meta points at way 5.<br/>The deferred eviction is never armed. Set 5 is untouched.<br/>The memory fetch is cancelled.

    M->>SD: grant from row 7 way 5
    SD->>BS: read row 7 way 5
    BS-->>SD: DA0
    SD-->>CPU: GrantData DA0

    M->>D: dir-write set 7 way 5 = tag 0x88001, TIP, displaced 1, clients now L1
    Note over D: set 7 way 5 = tag 0x88001 A, TIP, displaced 1, clients L1, dirty 0

    Note over CPU,SD: No copy. No eviction. No memory traffic. This is the payoff.
```

Compare with the old design, which copied A back into set 5 first — that cost one block copy, one
eviction of a healthy line, and one extra directory write, **per hit**.

---

## 3 — Write of a parked line, entry is TIP

Same as scenario 2, but the CPU wants to write. The entry ends up **TRUNK** — a client holds it
exclusively and may dirty it. **A displaced line is now client-held**, which the old invariant forbade.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000"}}}%%
sequenceDiagram
    autonumber
    participant CPU as CPU / L1
    participant M as MSHR
    participant D as Directory
    participant AT as AT / SBU
    participant SCU as SetCopyUnit
    participant BS as BankedStore
    participant SD as SourceD

    Note over CPU,SD: set 7 way 5 = tag 0x88001 A, TIP, displaced 1, clients 0, dirty 0. Data DA0.
    Note over SCU: IDLE. A serve moves no bytes between rows.

    CPU->>M: AcquireBlock NtoT 0x81000340. needT is true
    M->>D: read set 5, tag 0x88001
    D-->>M: MISS. defer the eviction
    M->>AT: is set 5 paired
    AT-->>M: partner is set 7
    M->>D: secondary search of set 7 for tag 0x88001
    D-->>M: HIT at way 5. state TIP, clients 0

    Note over M: SERVE IN PLACE. TIP with no clients, so no probe is needed.<br/>We hold full permission already.

    M->>SD: grant toT from row 7 way 5
    SD->>BS: read row 7 way 5
    BS-->>SD: DA0
    SD-->>CPU: GrantData DA0 with permission T

    M->>D: dir-write set 7 way 5 = tag 0x88001, TRUNK, displaced 1, clients L1
    Note over D: set 7 way 5 = tag 0x88001 A, state TRUNK, displaced 1, clients L1

    Note over CPU: The L1 now writes. DA0 becomes DA1 in the L1 only.<br/>The L2 copy in row 7 way 5 is still DA0 and is now STALE.

    Note over CPU,SD: A displaced line is now client-held and about to become dirty.<br/>This is the state scenario 4 has to cope with.
```

---

## 4 — Write of a parked line, entry is TRUNK — **the 9a fix**

Another writer wants A while a client still holds it exclusively. This is the case that used to
destroy the line.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000"}}}%%
sequenceDiagram
    autonumber
    participant CPU as CPU / L1 requester
    participant M as MSHR
    participant D as Directory
    participant AT as AT / SBU
    participant SCU as SetCopyUnit
    participant SB as SourceB probe
    participant H as Holder / L1 owning A
    participant BS as BankedStore
    participant SD as SourceD

    Note over CPU,SD: set 7 way 5 = tag 0x88001 A, TRUNK, displaced 1, clients L1. L1 holds DA1 dirty.
    Note over SCU: IDLE. The probe writeback lands straight in the parked row. No copy.

    CPU->>M: AcquireBlock NtoT 0x81000340. needT is true
    M->>D: read set 5, tag 0x88001
    D-->>M: MISS. defer the eviction
    M->>AT: is set 5 paired
    AT-->>M: partner is set 7
    M->>D: secondary search of set 7 for tag 0x88001
    D-->>M: HIT at way 5. state TRUNK, clients L1

    rect rgb(248,215,218)
    Note over M,D: BEFORE 9a. TRUNK failed the serve test, so the code ERASED the entry.<br/>No probe, no writeback. The L2 forgot a line the L1 still held dirty.<br/>The bytes then surfaced in a way the directory had given to another address.
    end

    rect rgb(223,240,216)
    Note over M: AFTER 9a. A secondary hit is a HIT. TRUNK just means a client has it,<br/>which is a reason to PROBE, exactly as an ordinary home hit would.
    end

    M->>SB: Probe toN, address 0x81000340
    Note over SB: The probe carries the REAL address, built from tag plus HOME set 5.<br/>The line physically sits in row 7. Those are different numbers now.
    SB->>H: Probe toN 0x81000340
    H-->>M: ProbeAckData DA1
    Note over M: The reply is routed back by probeSet plus probeTag, not by set alone
    M->>BS: write row 7 way 5, DA1
    Note over BS: The client's dirty data lands in the PARKED row, where the line lives

    M->>SD: grant toT from row 7 way 5
    SD->>BS: read row 7 way 5
    BS-->>SD: DA1
    SD-->>CPU: GrantData DA1 with permission T

    M->>D: dir-write set 7 way 5 = tag 0x88001, TRUNK, displaced 1, dirty 1, clients new L1
    Note over D: set 7 way 5 = tag 0x88001 A, TRUNK, displaced 1, dirty 1

    Note over CPU,SD: The line survived, stayed parked, and served correct data.<br/>12 percent of all serves in the passing run took this path.
```

---

## 5 — Write of a parked line, entry is BRANCH

The one case that cannot be served as-is: the L2 only has a **shared** copy, so it has no write
permission to give. It serves in place **and** asks memory for permission — it does not erase and
refetch.

> ⚠️ **Built but never exercised.** `secPerm = 0` in the passing run — no secondary hit ever found a
> BRANCH entry. This path elaborates and is asserted safe. It is **not** proven working.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000"}}}%%
sequenceDiagram
    autonumber
    participant CPU as CPU / L1
    participant M as MSHR
    participant D as Directory
    participant AT as AT / SBU
    participant SCU as SetCopyUnit
    participant SB as SourceB probe
    participant SH as Sharers
    participant MEM as Outer memory
    participant BS as BankedStore
    participant SD as SourceD

    Note over CPU,SD: set 7 way 5 = tag 0x88001 A, state BRANCH shared, displaced 1, clients some sharers.
    Note over SCU: IDLE. Only a permission is fetched. The bytes never move.

    CPU->>M: AcquireBlock NtoT 0x81000340. needT is true
    M->>D: read set 5, tag 0x88001
    D-->>M: MISS. defer the eviction
    M->>AT: is set 5 paired
    AT-->>M: partner is set 7
    M->>D: secondary search of set 7 for tag 0x88001
    D-->>M: HIT at way 5. state BRANCH

    Note over M: SERVE IN PLACE, but keep the fetch.<br/>BRANCH means we may read it, not write it.<br/>Do NOT cancel the memory request. Turn it into an AcquirePerm.

    M->>SB: Probe toN to the sharers, address 0x81000340
    SB->>SH: Probe toN
    SH-->>M: ProbeAck, no data needed
    M->>MEM: AcquirePerm 0x81000340
    MEM-->>M: Grant with permission T, no data
    Note over M: The DATA never moves. Only the permission is fetched.<br/>DA0 is already correct in row 7 way 5.

    M->>SD: grant toT from row 7 way 5
    SD->>BS: read row 7 way 5
    BS-->>SD: DA0
    SD-->>CPU: GrantData DA0 with permission T
    M->>D: dir-write set 7 way 5 = tag 0x88001, TRUNK, displaced 1
```

---

## 6 — A client releases a parked line, dirty

The L1 evicts A and writes it back. The L2 must find where A actually lives before it can accept the
data. This is why the C channel had to learn to search the partner set.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000"}}}%%
sequenceDiagram
    autonumber
    participant CPU as CPU / L1
    participant SKC as SinkC
    participant M as MSHR
    participant D as Directory
    participant AT as AT / SBU
    participant SCU as SetCopyUnit
    participant BS as BankedStore

    Note over CPU,BS: L1 holds A dirty as DA1. set 7 way 5 = tag 0x88001, TRUNK, displaced 1, data DA0 stale.
    Note over SCU: IDLE. SinkC writes the row directly. No copy engine involved.

    CPU->>SKC: ReleaseData TtoN 0x81000340 carrying DA1
    SKC->>M: allocate on the address home set, which is set 5
    M->>D: read set 5, tag 0x88001
    D-->>M: MISS

    rect rgb(248,215,218)
    Note over M,D: Without the partner search this trips the assert<br/>"C-channel request for a line that is neither resident nor parked".<br/>A voluntary Release must always find its line.
    end

    M->>AT: is set 5 paired
    AT-->>M: partner is set 7
    M->>D: secondary search of set 7 for tag 0x88001
    D-->>M: HIT at way 5, displaced 1

    Note over M: SERVE IN PLACE. A Release does not need T, so it always serves.<br/>physSet becomes 7. The write goes where the line actually lives.

    SKC->>BS: write row 7 way 5, DA1
    Note over BS: row 7 way 5 now holds DA1, the client's modified copy

    M->>D: dir-write set 7 way 5 = tag 0x88001, TIP, displaced 1, clients 0, dirty 1
    Note over D: set 7 way 5 = tag 0x88001 A, TIP, displaced 1, clients 0, DIRTY 1
    M-->>CPU: ReleaseAck

    Note over CPU,BS: A displaced line is now DIRTY. The old invariant said this was impossible.<br/>Scenario 7 is what has to happen to it next.
```

---

## 7 — Evicting a dirty parked line — the address split earns its keep

Set 7 is full and needs a way. It picks the displaced way 5. The bytes are in **row 7**, but the
address is **set 5**. Get this wrong and you corrupt an unrelated line in DRAM.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000"}}}%%
sequenceDiagram
    autonumber
    participant M as MSHR owning row 7
    participant D as Directory
    participant AT as AT / SBU
    participant SCU as SetCopyUnit
    participant SB as SourceB probe
    participant H as Holder / L1
    participant BS as BankedStore
    participant SC as SourceC
    participant MEM as Outer memory

    Note over M,MEM: set 7 way 5 = tag 0x88001 A, displaced 1, DIRTY 1, data DA1.<br/>A demand for some other line whose home is set 7 needs a way.
    Note over SCU: IDLE. A parked line is never migrated again - single hop only.<br/>It goes to DRAM, not to a third row.

    M->>D: read set 7 for the new line
    D-->>M: MISS. victim offered is way 5, the displaced entry

    M->>AT: my row is 7. who is it paired with
    AT-->>M: set 7 is a DESTINATION, its source is set 5
    Note over M: lineHome becomes 5.<br/>A displaced line in MY row came from my partner, so its home is the partner.

    opt the parked line still has a client
    M->>SB: Probe toN, address 0x81000340
    SB->>H: Probe toN
    H-->>M: ProbeAck or ProbeAckData
    Note over M: Added in 2e. Dropping a client-held parked line silently<br/>would leave the L1 holding a line the L2 has forgotten.
    end

    SC->>BS: read row 7 way 5
    Note over BS: PHYSICAL. the bytes are in row 7
    BS-->>SC: DA1
    SC->>MEM: ReleaseData to address 0x81000340
    Note over MEM: ADDRESS. built from tag 0x88001 plus HOME set 5.<br/>Using row 7 here would write A's bytes over a different line.

    M->>D: dir-write set 7 way 5 = the new line, displaced 0
    M->>AT: tear the pairing entry down

    Note over M,MEM: The dirty parked line went home correctly. This is the path<br/>that lets migration accept dirty lines at all.
```

---

## 8 — Not parked anywhere: the ordinary path, for contrast

Most misses look like this. The search runs, finds nothing, and the normal machinery takes over. The
search costs one extra directory read and nothing else.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000"}}}%%
sequenceDiagram
    autonumber
    participant CPU as CPU / L1
    participant M as MSHR
    participant D as Directory
    participant AT as AT / SBU
    participant SCU as SetCopyUnit
    participant SC as SourceC
    participant MEM as Outer memory
    participant BS as BankedStore

    Note over SCU: IDLE. This victim is dirty, so it is released, not migrated.<br/>The copy engine only runs on the migrate path in scenario 1.

    CPU->>M: AcquireBlock 0x80022F40, line B, home set 5
    M->>D: read set 5, tag 0x80117
    D-->>M: MISS. victim offered is way 3 holding V, dirty
    Note over M: DEFER the eviction. V is not thrown away until the search answers.

    M->>AT: is set 5 paired
    AT-->>M: partner is set 7
    M->>D: secondary search of set 7 for tag 0x80117
    D-->>M: SEC-MISS. not parked anywhere

    Note over M: Only now is the eviction armed. Nothing was wasted.
    SC->>BS: read row 5 way 3
    BS-->>SC: DV0
    SC->>MEM: ReleaseData to V's own address
    M->>D: dir-write set 5 way 3 = INVALID
    M->>MEM: AcquireBlock 0x80022F40
    MEM-->>M: GrantData DB0
    M->>BS: write row 5 way 3, DB0
    M->>D: dir-write set 5 way 3 = tag 0x80117, TIP, displaced 0
    M-->>CPU: GrantData DB0

    Note over CPU,BS: Cost of SBC on this path: one extra directory read. That is all.
```

---

## 9 — MMIO flush of a parked line: unsupported, and now loud about it

A flush by address reaches the home set, which does not hold the line. Before, this was a **silent
no-op** — the flush reported success and the line stayed in the cache. Now it asserts.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000"}}}%%
sequenceDiagram
    autonumber
    participant SW as Software via MMIO
    participant M as MSHR
    participant D as Directory
    participant AT as AT / SBU
    participant SCU as SetCopyUnit

    Note over SCU: IDLE. The transaction never gets far enough to move anything.

    SW->>M: Flush64 address 0x81000340
    M->>D: read set 5, tag 0x88001
    D-->>M: MISS
    M->>AT: is set 5 paired
    AT-->>M: partner is set 7
    M->>D: secondary search of set 7 for tag 0x88001
    D-->>M: HIT at way 5, displaced 1

    rect rgb(248,215,218)
    Note over M: ASSERT. MMIO flush of a displaced line is unsupported.<br/>Serving it would be wrong, and erasing it would lose the data<br/>now that parked lines can be dirty.<br/>Made loud in 2d, kept in 9a. Build it before trusting flush-by-address.
    end
```
---

## 10 — POSSIBLE BUG (unconfirmed): the search skips a locked way — safe when clean, maybe not after 9b

**Status: not observed, reachability not proven. Recorded for the findings register, not a confirmed defect.**

Serve-in-place is the first thing in this cache that puts **two MSHRs on one directory row at once** —
row 7's owner and set 5's borrower reaching in. The baseline forbade this (one MSHR per set,
`Scheduler.scala:214-215`), so it never needed a way-lock; SBC adds one (`MSHR.scala:432-436`) and the
directory refuses a busy way as a victim. That closes the *eviction* side.

The **search** side is protected differently: it masks out locked ways with `& freeWays`
(`Directory.scala:247-249`), and the comment says missing one is safe *"because the requester simply
fetches from memory instead."* That was true only while **`displaced ⇒ clean`** held — DRAM was always
current. **9b retired that.** If the search skips a **dirty** parked way, the requester fetches a
**stale** line from DRAM and two copies of the line exist at once.

This needs two transactions running together, so both are shown from their own start. They are on
**different home sets** (5 and 7), so the one-MSHR-per-set interlock never serialises them.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000"}}}%%
sequenceDiagram
    autonumber
    participant CPU as CPU / L1
    participant M5 as MSHR for set 5<br/>demand for A
    participant M7 as MSHR for set 7<br/>evicting a victim
    participant D as Directory
    participant AT as AT / SBU
    participant SCU as SetCopyUnit
    participant SC as SourceC
    participant MEM as Outer memory

    Note over CPU,MEM: START. set 7 way 5 = tag 0x88001 A, displaced 1, DIRTY 1, data DA1.<br/>DRAM still holds the OLD A, DA0. C is any line whose home is set 7.
    Note over SCU: IDLE. Neither path uses the copy engine. A is never migrated again.

    Note over M7: TRANSACTION 1 begins - set 7 needs a way for C
    CPU->>M7: AcquireBlock 0x81000...C  (line C, home set 7)
    M7->>D: read set 7, tag for C
    D-->>M7: MISS. victim offered is way 5, the displaced dirty entry A
    M7->>AT: my row is 7, who is it paired with
    AT-->>M7: set 7 is a DESTINATION, its source is set 5
    Note over M7: lineHome becomes 5. Publishes lockWay = 5 - way 5 is now BUSY.
    M7->>SC: read row 7 way 5 for writeback
    SC->>MEM: ReleaseData A to 0x81000340 (home set 5), DA1
    Note over MEM: DRAM mid-update. Still returns DA0 until this lands.

    Note over M5: TRANSACTION 2 begins - a demand for A arrives while way 5 is locked
    CPU->>M5: Get 0x81000340  (line A, home set 5)
    M5->>D: read set 5, tag 0x88001
    D-->>M5: MISS. defer the eviction
    M5->>AT: is set 5 paired
    AT-->>M5: partner is set 7
    M5->>D: secondary search of row 7 for tag 0x88001

    rect rgb(248,215,218)
    Note over D: way 5 is locked, so `& freeWays` MASKS it out.<br/>Search reports SEC-MISS even though A is right there.
    end
    D-->>M5: SEC-MISS

    Note over M5: "not parked anywhere" -> fetch A from DRAM
    M5->>MEM: AcquireBlock 0x81000340
    MEM-->>M5: GrantData DA0  (STALE, if it beats the ReleaseData above)
    M5-->>CPU: GrantData DA0

    Note over CPU,MEM: END. Two copies of A exist, and the CPU may have taken the OLD one.<br/>When A was clean this refetch was harmless. When dirty it is not.
```

**Why it might still be safe.** Two guesses, neither verified:
- The in-flight `ReleaseData` and the demand `AcquireBlock` both name **A's real address** (home set
  5). If the outer fabric orders them, the demand may get the fresh DA1 — but that is an ordering
  assumption the baseline never had to make, because its one-per-set rule serialised the two on the
  directory itself.
- Reachability. The lock only bites while row 7's owner is *actively* evicting the parked way, in the
  same few cycles the borrower searches it. A single in-order Rocket core may not overlap them
  (`§10.7` already flags this for the way-lock case).

**Why it is worth recording anyway.** Same shape as the two hazards this project deferred rather than
dismissed — the `SBC_Reset` AT-wipe (`§10.9`) and the dirty-source blocker (`§10.10`) — each *"harmless
before 9b, and 9b changed exactly the thing the old reasoning rested on."* This is a third instance, on
the one path (`& freeWays`) whose safety comment still cites the retired invariant by name.

**Suggested next step:** do not write a directed case — a single core likely cannot force the overlap,
and claiming coverage would be faking it. Instead: (a) assert that a `SEC-MISS` never coincides with a
locked **dirty** displaced way in the searched row, so the S11 soak traps it if it ever occurs; and
(b) fix the `Directory.scala:246` comment, which still asserts refetch-is-safe on the strength of
`displaced ⇒ clean`.
