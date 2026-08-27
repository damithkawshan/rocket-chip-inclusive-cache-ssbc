Here are the Mermaid diagrams for Phase 3 Step 2 — the secondary-search detector (measure-only). Simple English, short sentences.

## 1. Flow chart — the detector logic

```mermaid
flowchart TD
    A[Demand miss to set S] --> B{Did it miss?<br/>hit == 0}
    B -- No --> Z[Do nothing]
    B -- Yes --> C{Is S an active<br/>migration source?<br/>ask the AT}
    C -- No --> Z
    C -- Yes --> D[Read D from the AT<br/>D = the paired set<br/>T = the demand tag]
    D --> E{Is the pending<br/>slot free?}
    E -- No --> F[Drop it.<br/>nSecDrop plus 1]
    E -- Yes --> G[Latch tag T and set D<br/>into the 1-entry slot]
    G --> H{Is the dir read<br/>port free?<br/>no MSHR or alloc read<br/>and dir ready}
    H -- No --> H
    H -- Yes --> I[Inject one dir-read of D<br/>secondarySearch = 1<br/>lowest priority]
    I --> J{Does a displaced way<br/>in D hold tag T?}
    J -- Yes --> K[SBC_SecHits plus 1]
    J -- No --> L[SBC_SecMiss plus 1]
    K --> M[The line is not served.<br/>Nothing moves.]
    L --> M
    F --> M

    classDef green fill:#dff0d8,stroke:#3c763d,color:#000;
    classDef yellow fill:#fff3cd,stroke:#856404,color:#000;
    class A,C,D,G,I,J,K,L,M green;
    class B,E,F,H yellow;
```

## 2. Sequence diagram — one detected event

```mermaid
%%{init: {'theme':'base', 'themeVariables': {'noteTextColor':'#000000', 'noteBkgColor':'#fff3cd', 'noteBorderColor':'#856404', 'actorTextColor':'#000000', 'signalTextColor':'#000000', 'actorBorder':'#3c763d'}}}%%
sequenceDiagram
    autonumber
    participant CPU as CPU
    participant DET as Scheduler / Detector
    participant AT  as SBU / AT
    participant D   as Directory
    participant CNT as SBU counters

    Note over CPU,CNT: Phase 3 Step 2 — measure only. No data moves. The demand still goes to memory.

    %% qualify
    rect rgb(223,240,216)
    Note over CPU,AT: STEP 1 — a demand misses in set S. Check if S is a live source.
    CPU->>DET: demand miss to set S (hit = 0)
    DET->>AT: is set S an active source?
    AT-->>DET: yes — paired set is D
    DET->>DET: latch tag T and set D<br/>into the 1-entry slot
    end

    %% opportunistic inject
    rect rgb(223,240,216)
    Note over DET,D: STEP 2 — wait for a free port, then peek at D. Lowest priority.
    DET->>D: read D, tag T, secondarySearch = 1
    Note right of D: This read is hidden from<br/>the saturation tap.
    D-->>DET: secondaryHit = does a displaced way in D hold tag T?
    end

    %% count
    rect rgb(223,240,216)
    Note over DET,CNT: STEP 3 — just count the answer.
    alt displaced copy found
        DET->>CNT: SBC_SecHits plus 1
    else not found
        DET->>CNT: SBC_SecMiss plus 1
    end
    end

    %% slot busy path
    rect rgb(255,243,205)
    Note over DET: If the slot was busy when the miss came,<br/>the event is dropped. nSecDrop plus 1.
    end

    Note over CPU,CNT: The demand goes to memory as usual. The search runs beside it and only counts.
```

## 3. Transaction / port-arbitration view — where the extra read fits

```mermaid
flowchart LR
    subgraph PORT[Directory read port - one per cycle]
        direction TB
        P1[1. MSHR reload read] --> P2[2. Alloc demand read]
        P2 --> P3[3. MSHR 2nd dir-read]
        P3 --> P4[4. Search read<br/>lowest priority<br/>fires only in a bubble]
    end

    Q[Pending slot<br/>tag T, set D<br/>holds 1 event] --> P4
    P4 --> R{Port was free?}
    R -- Yes --> S[Inject search read of D]
    R -- No --> Q
    S --> T[Result counted.<br/>No MSHR reads it.<br/>directoryFanout = 0]

    W[A pending dir-write] -. delayed at most<br/>a cycle when<br/>search fires .-> P4

    classDef green fill:#dff0d8,stroke:#3c763d,color:#000;
    classDef yellow fill:#fff3cd,stroke:#856404,color:#000;
    class P1,P2,P3,S,T green;
    class P4,Q,R,W yellow;
```

Key points shown by the diagrams:
- The search only runs when the port is free. It never blocks the demand.
- The demand still goes to memory. Nothing is served, nothing moves.
- The search read is hidden from the saturation tap, so counters stay clean.
- Its result reaches no MSHR (`directoryFanout = 0`), so it is purely a count.
- A busy 1-entry slot drops the event and bumps `nSecDrop`.

Want me to save these into a doc (e.g. `ai-documents/diagram-phase3.md`), or adjust any diagram?