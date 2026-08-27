# Paper idea vs. real hardware — the same feature, two very different costs

**The one thing to take away:** the SBC paper's reuse feature is *simple on paper* and *hard in real
hardware*. The performance idea is the same. The engineering effort is not — and this document shows
exactly where all the extra effort comes from.

**The scene (same in both diagrams):** a line was moved from its home set **S** to a partner set
**D**. Now an L1 asks for that line again. What does the cache do to give it back?

**Colour key:** 🟢 green = one simple step · 🟡 yellow = a special case · 🔵 blue = extra hardware the
paper never mentions.

---

## The setup is the same — only the L2 is different

Both designs have the same shape: private **L1** caches per core, one shared **L2**. Set-balancing
happens in the L2. So the gap is **not** about how many caches there are.

The difference is what the L2 has to *do*:

| | Paper's L2 | Our L2 (real RISC-V) |
|---|---|---|
| What it is | A **lookup table** — just tags and data | A **coherence manager** |
| Does it track the L1s? | **No** | **Yes** — it knows which L1 holds each line |
| Can it serve a line from any set? | **Yes** — read it wherever it sits | **No** — a line must sit in the set its address points to |

**Why that last row is everything:** in our cache, later messages about a line (a probe, a
write-back) are found **by the line's address**. The address points to home set S. If the line is
parked in D, those messages look in S and never find it. So before we can hand the line to an L1, we
must **physically move it back home**. That single rule is what turns the paper's one easy step into
our long sequence.

---

## Diagram 1 — The paper's version (easy)

Find the line in the partner set, read it, hand it over. Done. The line never moves. Nothing else in
the cache has to change.

```mermaid
sequenceDiagram
    autonumber
    participant L1 as L1 (asks for the line)
    participant L2 as L2 (paper — just a lookup)
    participant D as Partner set D (has the copy)

    Note over L1,D: Paper L2 does not track the L1s, so serving from any set is fine

    rect rgb(223,240,216)
    L1->>L2: I want line L
    end
    rect rgb(223,240,216)
    L2->>D: read L from the partner set
    D-->>L2: here is L
    end
    rect rgb(223,240,216)
    L2-->>L1: here is your line
    end

    Note over L1,D: 3 easy steps. The line stays where it is. No moving, no cleanup.
```

**Cost: almost nothing.** One lookup, one read, one hand-off.

---

## Diagram 2 — Our version (hard)

We cannot serve the line where it sits. We have to **swap it home**: bring L back to set S, and to
keep both sets full, push S's outgoing line into the slot L just left in D. Then let the normal cache
path hand it over.

But there is more hidden cost than "a few extra steps." The real cache is a **pipeline** where the
directory and the data array are **shared, one-at-a-time resources**. So every box below is not just
an action — it is a scheduled turn that waits its slot, comes back a couple of cycles later, and must
be fenced against other requests touching the same sets. Every **blue** box is work the paper never
counted. The **grey** side-notes are the pipeline realities hiding inside those boxes.

Every unit below is a real, separate piece of hardware in our design (same names as
[diagram.md](diagram.md)). The paper's diagram needed 3 boxes. Ours needs 9 — because a pipelined,
coherent cache splits the work across a request channel, one MSHR, a shared directory, the
association table, a copy engine, a shared data array, and the grant channel, instead of one
lookup table.

```mermaid
sequenceDiagram
    autonumber
    participant CPU as CPU (asks for the line)
    participant SA as SinkA (request channel in)
    participant M as MSHR (the one job owner)
    participant Dir as Directory (one shared port, 2-cycle answer)
    participant SBU as SBU and AT (who is paired with whom)
    participant SCU as SetCopyUnit (the copy engine)
    participant BS as BankedStore (data array, one shared path, beat by beat)
    participant SD as SourceD (grant channel out)
    participant Mem as Memory

    Note over CPU,Mem: Blue = extra work vs the paper. Grey = the pipeline cost hiding inside it.

    rect rgb(255,243,205)
    Note over M,Dir: THE REASON — later, a message about L looks in home set S by its address.<br/>If L is parked in D, it is never found. So L must be moved home first.
    end

    rect rgb(223,240,216)
    Note over CPU,Dir: 1. CPU asks for L. One MSHR is given the job. Look in home set S.
    CPU->>SA: AcquireBlock for line L
    SA->>M: allocate the MSHR for set S
    M->>Dir: look in set S
    Dir-->>M: MISS — a parked copy is hidden, it never counts as a hit
    end

    rect rgb(207,226,255)
    Note over M,SBU: 2. NEW — ask who S is paired with, then LOCK that partner set D for the whole job
    M->>SBU: is S paired with anyone
    SBU-->>M: yes, partner is D
    M->>M: raise a fence on D, and HOLD the memory request so we do not double-fetch
    Note over M,SBU: while the fence is up, every other request that needs D must wait
    end

    rect rgb(207,226,255)
    Note over M,Dir: 3. NEW — a SECOND directory read, this time hunting for a hidden parked copy
    M->>Dir: is L parked in D (special search that looks at hidden copies)
    Dir-->>M: yes, at way dWay — answer comes back 2 cycles later
    end

    rect rgb(207,226,255)
    Note over M,BS: 4. NEW — the swap. There is no instant move. A separate engine copies through one port, beat by beat.
    M->>Dir: pick which line V leaves S to make room for L
    M->>SCU: do the swap
    SCU->>BS: read L out of D  (buffer it, one beat at a time)
    SCU->>BS: read V out of S  (buffer it too)
    SCU->>BS: write L into S    (only after safe — cannot write a slot still being read)
    SCU->>BS: write V into D
    SCU-->>M: swap done
    Note over SCU,BS: two hazard gates guard this so a copy never races a normal read or write
    Note over M: if V is dirty or shared, it cannot be parked — evict it the slow normal way (half-swap)
    end

    rect rgb(207,226,255)
    Note over M,Dir: 5. NEW — two directory writes so the labels match the data we just moved
    M->>Dir: write one — mark V as parked in D
    M->>Dir: write two — mark L as home in S
    end

    rect rgb(223,240,216)
    Note over CPU,SD: 6. Serve L — by REPLAYING the request so the proven normal path does the hard parts
    M->>Dir: look in set S again
    Dir-->>M: HIT now — L is home
    Note over M,SD: replay lets the normal path handle permissions, probes and the grant — we do not rebuild that
    M->>SD: GrantData
    SD-->>CPU: here is your line
    CPU-->>M: GrantAck
    end

    rect rgb(207,226,255)
    Note over M,SBU: 7. NEW — bookkeeping and unlock. Count the hit, drop the fence on D,<br/>check if D is now empty so the pairing can be torn down, then retire the MSHR.
    M->>SBU: count the hit, check if D can be torn down
    end

    Note over CPU,Mem: Steps 2, 3, 4, 5, 7 do not exist in the paper.<br/>And each one is a scheduled, fenced, multi-cycle pipeline turn — not a free action.
```

**Cost: a whole sequenced mini-protocol.** One MSHR must: lock a second set, wait a 2-cycle directory
answer, do a special hidden-copy search, copy two blocks beat-by-beat through a single data port
behind two hazard gates, write the directory twice, replay the request to reuse the normal serve
path, then unlock and clean up — with a slower fallback whenever the victim is dirty or shared.

**The hidden multipliers (why it is worse than the step count suggests):**
- **Shared directory, one port, 2-cycle answer** → the 3 lookups are 3 scheduled turns, each with
  latency, that also compete with every other request in the cache.
- **Shared data array, one path** → the swap is not one move but **many beats**, copied through a
  buffer, guarded by two hazard gates so it never collides with a normal access.
- **The fence** → set D is locked for the entire job, so unrelated traffic to D stalls behind it.
- **One MSHR does all of it in sequence** → no parallelism inside the job, and only one such swap
  runs at a time.

---

## Side by side

| | Paper | Ours |
|---|---|---|
| Directory reads | 1 | 3 (miss, search partner, re-check) |
| Directory writes | 0 | 2 |
| Data blocks moved | 0 | up to 2 (a full swap) |
| Sets locked during the job | 0 | 2 (home and partner) |
| Special cases to handle | none | victim dirty, victim shared, copy too weak, partner full |
| Extra rules to keep correct | none | one partner per set, never two copies of a line, clean up dead pairings |

---

## Why each extra piece is unavoidable

Every added cost traces back to **one** fact: our L2 is a coherence manager that finds lines by
address.

- Must **move the line home** → because messages find it by address.
- Must **swap in a victim** → because the home set is full, and we do not want to waste memory
  bandwidth throwing a good line away.
- Must **lock both sets** → because other requests could touch them mid-swap.
- Must **write the directory twice** → so both sets are labelled correctly after the move.
- Must **handle dirty or shared victims** → real lines are not always safe to move.
- Must **keep strict pairing rules** → so we always know the one place a moved line can be.

---

## The takeaway (slide-ready)

> **The paper's reuse = 1 easy lookup. Our reuse = a full coherent swap across two sets.**
>
> Same performance idea. The extra engineering exists entirely because a real, inclusive L2 must be
> able to find every line by its address — so a moved line has to come home before it can be used.

*(More detail: [phase-3.md](phase-3.md) for the plan, [diagram.md](diagram.md) for the full built and
planned flow, [spec-sbc-phase3-prereqs.md](spec-sbc-phase3-prereqs.md) for the coder edits.)*
