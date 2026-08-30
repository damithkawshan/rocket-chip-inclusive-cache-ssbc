# SBC Phase 3R — serve-in-place + first-class displaced lines (visual)

**Status: PLANNED.** Companion to [TASK.md](TASK.md) and [../../phase-3.md](../../phase-3.md).
Supersedes Part B of [../../diagram.md](../../diagram.md), which draws the full swap — that was
superseded first by repatriation, and now by serve-in-place.

**Colour key:** 🟢 green = built and working today · 🟡 yellow = changes in this task ·
🔴 red = deleted or broken today · 🔵 blue = new hardware.

## The idea, in plain English

- Today a secondary hit **copies the line home** (repatriation), evicting a good line from the home
  set to make room. The paper does not do this. It **serves the line where it sits.**
- Serving in place means the line **stays displaced while a client holds it**. It can then be dirty.
  That is the whole unlock: today `displaced` means clean and client-free, which is what forbids
  migrating dirty victims.
- To make that safe we need one thing: **the home set of a parked line**. Under strict 1:1 pinning it
  is already known — `AT[row].assocSet`. No directory format change.
- The refactor is therefore: **stop letting one wire called `set` mean two different things.**

## The whole vocabulary (nine names)

| name | in one line |
|---|---|
| `homeSet` | the set the **address** maps to — today's `request.set`, unchanged |
| `physSet` | the SRAM **row** actually being used |
| `probeSet` / `probeTag` | where our outstanding probe will be answered |
| `pairSetReg` | the associated set. **Already exists** — no new register |
| `isSrc` | is my set the source side of the pairing (else the destination side) |
| `lineHome` | the home set of the line currently in `meta` |
| `inPlace` | this MSHR is serving from the partner row |
| `secDefer` | eviction held back until the search answers |
| `busyWays` / `freeWays` | ways locked by a live MSHR / the rest |

---

## Part 1 — today vs planned, side by side

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff"}}}%%
flowchart TD
    START["Demand miss in set s<br/>s is a paired source, partner is d"] --> SEARCH["Secondary search of set d<br/>finds the line at way w"]

    SEARCH --> T
    SEARCH --> P

    subgraph T["TODAY - repatriate (being deleted)"]
      T1["Evict a victim from set s<br/>to make room"] --> T2["SetCopyUnit copies the block<br/>from d,w into s,victimWay"]
      T2 --> T3["dir-write 1: erase parked copy at d,w"]
      T3 --> T4["dir-write 2: install natively in s"]
      T4 --> T5["SourceD reads s,victimWay<br/>GrantData"]
      T5 --> T6["Line is native. displaced stays<br/>clean and client-free."]
    end

    subgraph P["PLANNED - serve in place"]
      P1["Evict nothing.<br/>Set s is not touched at all."] --> P2["No copy. Nothing moves."]
      P2 --> P3["SourceD reads d,w directly<br/>GrantData"]
      P3 --> P4["dir-write: set clients at d,w<br/>displaced STAYS true"]
      P4 --> P5["Line stays parked, now client-held<br/>and eventually dirty"]
    end

    style START fill:#ffffff,stroke:#555555,color:#000000
    style SEARCH fill:#dff0d8,stroke:#3c763d,color:#000000
    style T1 fill:#f8d7da,stroke:#a94442,color:#000000
    style T2 fill:#f8d7da,stroke:#a94442,color:#000000
    style T3 fill:#f8d7da,stroke:#a94442,color:#000000
    style T4 fill:#f8d7da,stroke:#a94442,color:#000000
    style T5 fill:#f8d7da,stroke:#a94442,color:#000000
    style T6 fill:#f8d7da,stroke:#a94442,color:#000000
    style P1 fill:#fff3cd,stroke:#aaaa33,color:#000000
    style P2 fill:#fff3cd,stroke:#aaaa33,color:#000000
    style P3 fill:#fff3cd,stroke:#aaaa33,color:#000000
    style P4 fill:#fff3cd,stroke:#aaaa33,color:#000000
    style P5 fill:#fff3cd,stroke:#aaaa33,color:#000000
```

**What the planned side deletes:** one block copy, one home-set eviction, and one directory write,
per secondary hit. It also deletes `doSecCopy` / `s_scopy` / `w_scopy` and the SetCopyUnit write into
a live set — which is where both open corruption leads live.

---

## Part 2 — the planned serve-in-place transaction

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000"}}}%%
sequenceDiagram
    autonumber
    participant CPU as CPU (TL-C client)
    participant M   as MSHR (home set s)
    participant D   as Directory
    participant AT  as SBU / AT
    participant SB  as SourceB (probe)
    participant SD  as SourceD
    participant BS  as BankedStore

    Note over CPU,BS: Set s is paired with partner d. The line the CPU wants is parked in d.

    rect rgb(223,240,216)
    Note over CPU,D: STEP 1 - ordinary demand miss. Unchanged.
    CPU->>M: AcquireBlock for address A (home set s)
    M->>D:  dir-read set s
    D-->>M: MISS, victim way vWay offered
    end

    rect rgb(255,243,205)
    Note over M,AT: STEP 2 - NEW. Defer the eviction. Arm the search only.
    Note over M: secDefer := true<br/>do NOT set s_release<br/>do NOT set s_rprobe<br/>do NOT set s_acquire<br/>sibling of the proven migDeferred
    AT-->>M: pairInfo - valid, isSrc = true, pairSet = d
    M->>D:  dir-read set d, secondarySearch, tag of A
    end

    rect rgb(255,243,205)
    Note over D,M: STEP 3 - the search answers.
    D-->>M: secondaryHit at way w, entry state and clients
    Note over M: inPlace := true<br/>physSet = d, homeSet = s<br/>meta re-pointed at d,w<br/>secDefer := false<br/>NO eviction was ever armed
    end

    rect rgb(224,231,255)
    Note over M,SB: STEP 4 - NEW. The parked line may be client-held now.
    Note over M: if we need T, or the entry is TRUNK,<br/>and it has clients: arm a pprobe.<br/>No such logic exists today - the old rule<br/>guaranteed parked lines were client-free.
    M->>SB: Probe, address = expandAddress(tag, homeSet s)
    SB-->>M: ProbeAck routed back by probeSet plus probeTag
    end

    rect rgb(255,243,205)
    Note over M,BS: STEP 5 - serve it from where it is.
    M->>SD: schedule d, physSet = d, way = w
    SD->>BS: read row d, way w
    BS-->>SD: block data
    SD-->>CPU: GrantData
    end

    rect rgb(255,243,205)
    Note over M,D: STEP 6 - one directory write, in the partner row.
    M->>D: dir-write at d,w - clients set, displaced STAYS true
    Note over D: The line is now displaced AND client-held.<br/>Directory assert at 211-213 must be relaxed.
    end

    Note over CPU,BS: Set s was never touched. No victim evicted, no block copied.
```

---

## Part 3 — the one structural change: `set` currently means two things

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff"}}}%%
flowchart TD
    OLD["MSHRStatus.set<br/>ONE field, THIRTEEN readers"] --> A1
    OLD --> A2
    OLD --> A3

    A1["Used as a PHYSICAL ROW<br/>Directory row, BankedStore row,<br/>SourceD hazard tuples"]
    A2["Used as ADDRESS BITS<br/>expandAddress builds the<br/>real memory address"]
    A3["Used as a PROBE ROUTING KEY<br/>Scheduler line 91 matches<br/>ProbeAck responses by set"]

    A1 --> NEW
    A2 --> NEW
    A3 --> NEW

    NEW["DELETE the field named set.<br/>All 13 readers become COMPILE ERRORS."] --> F1
    NEW --> F2
    NEW --> F3

    F1["physSet - the row actually in use<br/>= partner d when serving in place"]
    F2["homeSet - request.set, unchanged<br/>the address the CPU asked for"]
    F3["probeSet plus probeTag<br/>where our probe reply will arrive.<br/>Tag is mandatory - set alone aliases."]

    style OLD fill:#f8d7da,stroke:#a94442,color:#000000
    style A1 fill:#ffffff,stroke:#555555,color:#000000
    style A2 fill:#ffffff,stroke:#555555,color:#000000
    style A3 fill:#ffffff,stroke:#555555,color:#000000
    style NEW fill:#fff3cd,stroke:#aaaa33,color:#000000
    style F1 fill:#dff0d8,stroke:#3c763d,color:#000000
    style F2 fill:#dff0d8,stroke:#3c763d,color:#000000
    style F3 fill:#dff0d8,stroke:#3c763d,color:#000000
```

**Why delete rather than rename.** Nine readers want the address, four want the row, two want the
routing key. Whichever meaning we keep, a **missed** site silently keeps working for native lines and
breaks only for displaced ones — the worst possible failure. Deleting the name makes every site a
compile error that must be decided explicitly.

---

## Part 4 — evicting a DIRTY displaced line (Stage 2, the `p` unlock)

This is the split doing real work. The same MSHR uses **both** meanings in the same transaction.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000"}}}%%
sequenceDiagram
    autonumber
    participant M   as MSHR (owns row d)
    participant AT  as SBU / AT
    participant SC  as SourceC
    participant BS  as BankedStore
    participant Mem as Outer memory

    Note over M,Mem: A demand for a line whose home is d misses.<br/>The chosen victim is a DISPLACED line parked here from set s.

    rect rgb(248,215,218)
    Note over M: TODAY - this line is dropped silently.<br/>Its address cannot be reconstructed, so it may<br/>never be written back. Dirty lines therefore<br/>may never migrate. This is the cap on p.
    end

    rect rgb(255,243,205)
    Note over M,AT: PLANNED - recover the home set from the AT.
    AT-->>M: pairInfo - isSrc = false, pairSet = s
    Note over M: my row d is a DESTINATION,<br/>so any displaced line here came from s<br/>lineHome = pairSet = s
    end

    rect rgb(255,243,205)
    Note over M,Mem: The two meanings, in one request.
    M->>SC: physSet = d, homeSet = s, way, tag, dirty
    SC->>BS: read row d, way  (PHYSICAL - where the bytes are)
    BS-->>SC: block data
    SC->>Mem: ReleaseData, address = expandAddress(tag, s)
    Note over Mem: ADDRESS - where the bytes belong.<br/>Using d here corrupts an unrelated line<br/>in DRAM. This is risk 2 in the plan.
    end
```

---

## Part 5 — the probe-response routing fix

The first thing that breaks once displaced lines can be client-held. It is a **hang, not corruption**,
which makes it a good early canary.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff"}}}%%
flowchart TD
    S1["MSHR sits on physical row d.<br/>Its victim is a displaced line whose home is s."] --> S2["It must probe that line's client.<br/>Probe address = expandAddress(tag, s)"]
    S2 --> S3["L1 replies. ProbeAck carries address A,<br/>so SinkC parses set = s"]
    S3 --> S4{"Scheduler line 91 routes by<br/>resp.set === status set"}

    S4 -->|"TODAY: compares s against d"| B1["NO MATCH.<br/>ProbeAck delivered to nobody."]
    B1 --> B2["MSHR waits forever on w_rprobeacklast.<br/>No watchdog on this path."]

    S4 -->|"PLANNED: compares against probeSet AND probeTag"| G1["probeSet = s, probeTag = victim tag"]
    G1 --> G2["Routed correctly.<br/>Tag term is REQUIRED - another MSHR<br/>legitimately has homeSet = s."]

    style S1 fill:#ffffff,stroke:#555555,color:#000000
    style S2 fill:#ffffff,stroke:#555555,color:#000000
    style S3 fill:#ffffff,stroke:#555555,color:#000000
    style S4 fill:#ffffff,stroke:#555555,color:#000000
    style B1 fill:#f8d7da,stroke:#a94442,color:#000000
    style B2 fill:#f8d7da,stroke:#a94442,color:#000000
    style G1 fill:#dff0d8,stroke:#3c763d,color:#000000
    style G2 fill:#dff0d8,stroke:#3c763d,color:#000000
```

`MSHR.scala:782` already warns *"Caution: the probe matches us only in set."* That comment becomes
false and must be updated in the same change.

---

## Part 6 — `displaced` is the discriminator. It must NOT be weakened anywhere.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff"}}}%%
flowchart TD
    Q["Row d holds TWO ways with tag T"] --> W1["way 1 - native, displaced = false<br/>its address is expandAddress(T, d)"]
    Q --> W2["way 2 - parked from s, displaced = true<br/>its address is expandAddress(T, s)"]

    W1 --> R["Directory line 188 excludes displaced from hits<br/>Directory line 196 requires displaced for secHits"]
    W2 --> R

    R --> OK["CORRECT TODAY. Do NOT change this.<br/>A normal lookup of d finds only the native line.<br/>The secondary search finds only the parked one."]
    R --> BAD["If the displaced term is dropped from hits:<br/>PopCount(hits) = 2, Mux1H returns garbage,<br/>the CPU gets another address's data."]

    style Q fill:#ffffff,stroke:#555555,color:#000000
    style W1 fill:#ffffff,stroke:#555555,color:#000000
    style W2 fill:#ffffff,stroke:#555555,color:#000000
    style R fill:#ffffff,stroke:#555555,color:#000000
    style OK fill:#dff0d8,stroke:#3c763d,color:#000000
    style BAD fill:#f8d7da,stroke:#a94442,color:#000000
```

**This is not hypothetical here.** `set_addr(s,t)` in the stress test puts the tag entirely above the
set-index bits, so **the same tag exists in every set**. The collision is guaranteed, not rare. Add
`assert(PopCount(hits) <= 1)` at `Directory.scala:190` in Stage 1 as a permanent net.

### And the same bit enforces single-hop

A displaced line may **never** be migrated again. The AT records **one hop** only.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff"}}}%%
flowchart LR
    L1["Line L lives natively in set s"] -->|"migrate - ALLOWED"| L2["L is parked in row d<br/>at(d).assocSet = s<br/>lineHome resolves to s. Correct."]
    L2 -->|"migrate again - FORBIDDEN"| L3["L is parked in row e<br/>at(e).assocSet = d<br/>lineHome resolves to d.<br/>But L's real home is s."]
    L3 --> BAD["Release goes to expandAddress(tag, d)<br/>WRONG DRAM ADDRESS.<br/>Corrupts an unrelated line."]

    L2 --> GUARD["Blocked by !displaced in<br/>evictableOH and migClean.<br/>Both terms must STAY."]

    style L1 fill:#dff0d8,stroke:#3c763d,color:#000000
    style L2 fill:#dff0d8,stroke:#3c763d,color:#000000
    style L3 fill:#f8d7da,stroke:#a94442,color:#000000
    style BAD fill:#f8d7da,stroke:#a94442,color:#000000
    style GUARD fill:#dff0d8,stroke:#3c763d,color:#000000
```

**Stage 2 drops `!dirty` from those expressions and nothing else.** `!displaced` stays:

```scala
val migClean = !new_meta.dirty && !new_meta.displaced   // BEFORE
val migClean =                    !new_meta.displaced   // AFTER
```

---

## Part 7 — stage map

> **Re-ordered by Amendment 1 (2026-08-29).** The original plan kept repatriation alive through three
> stages so that deleting it last would act as an experiment isolating the corruption. **The shadow
> model answered that question on its first run** (finding P5 — the repatriation copy overtaking the
> migration copy into the same way). With the cause known directly, there is no reason to keep the
> code, so serve-in-place moved to the front and the deferral/way-lock folded into it.

| stage | what changes | invariant after | gate |
|---|---|---|---|
| 1 ✅ | asserts, shadow models, delete `MSHRStatus.set`, add `isSrc` | both halves still hold | SBC-off regression green |
| **2** ⬅ | **serve in place** (`inPlace`) — delete the repatriation copy, plus `secDefer` and `busyWays`, pprobe arm, C/X search | `displaced ⇒ clean` only | 7/7, SecHits > 0 |
| 3 | displaced victims Release at `lineHome`, drop `!dirty` from migrate | `displaced` means only "row ≠ address set" | `p` measurably up |

**Stage 2 deletes P5's mechanism rather than fixing it** — serve-in-place performs no copy, so
`doSecCopy` and `doMigCopy` can no longer collide. If `case_reaccess_migrated` passes once Stage 2
lands, P5 was the long-open corruption. If it does not, P5 was real but not the whole story — and the
shadow model is now in place to find the rest.
