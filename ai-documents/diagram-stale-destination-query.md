# The stale destination query — why the Phase-3 spec cannot be applied as written (visual)

Companion to [spec-sbc-phase3-prereqs.md](spec-sbc-phase3-prereqs.md) (the spec this corrects),
[phase-3.md](phase-3.md) (always-use SSOT) and [diagram.md](diagram.md) (the full flow).

**Status:** the bug is **latent today** and becomes **real the moment Phase-3 pinning lands**.
Nothing is broken in the tree right now.

**Colour key:** 🟢 green = works today · 🟡 yellow = harmless today · 🔴 red = what breaks · 🔵 blue = the fix.

---

## The idea, in plain English

- Phase 2 answers one question: **"give me any cold set."** The answer does not care who asked.
- Phase 3 answers a different question: **"give me set S's partner."** The answer depends entirely on who asked.
- The code today asks that question on behalf of **whatever request is at the head of the queue** —
  not on behalf of the MSHR that is actually migrating.
- Those two were the same thing in Phase 2. **Probe-then-migrate pulled them apart**, because a
  migration now decides many cycles after it was allocated.
- So applying the spec as written would send most migrations to **somebody else's partner**.

---

## Diagram A — Today (Phase 2.5). Harmless, because the answer ignores the asker.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000","activationBkgColor":"#f2f2f2","activationBorderColor":"#666666"}}}%%
sequenceDiagram
    autonumber
    participant Q as Request queue head
    participant MA as MSHR A (owns set 5)
    participant PB as SourceB / SinkC (probe)
    participant SCH as Scheduler offer wire
    participant SBU as SBU (DSS and AT)

    Note over Q,SBU: TODAY — the answer does not depend on who asked, so a wrong asker does no damage

    rect rgb(223,240,216)
    Note over Q,MA: Set 5 misses. MSHR A takes it and starts probe-then-migrate.
    Q->>MA: allocate for set 5
    MA->>PB: Probe toN on the chosen victim
    MA->>MA: migDeferred = 1, waiting many cycles
    end

    rect rgb(255,243,205)
    Note over Q,SBU: While A waits, the queue head moves on to an unrelated request.
    Q->>SBU: migrateQuery = set 9, the head, NOT set 5
    SBU-->>SCH: destOk and destSet = DSS coldest set
    Note over SBU,SCH: destSet is built ONLY from the DSS.<br/>It never reads the query set. That is why set 9 does no harm.
    end

    rect rgb(223,240,216)
    Note over PB,MA: The probe answer arrives. A decides now.
    PB-->>MA: ProbeAck, victim is clean and client free
    SCH-->>MA: offer = coldest set d
    MA->>MA: migDstSet = d. Good enough — any cold set satisfies Phase 2.
    end
```

**Read the middle block again.** The query says set 9 and the answer is about neither 9 nor 5. That
mismatch is invisible today only because the answer is a pure DSS output.

---

## Diagram B — The spec applied as written. The mismatch becomes a wrong partner.

Assume set 5 is paired with set 12, and the unrelated request at the head is for set 9, paired with set 30.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000","activationBkgColor":"#f2f2f2","activationBorderColor":"#666666"}}}%%
sequenceDiagram
    autonumber
    participant Q as Request queue head
    participant MA as MSHR A (set 5, paired with 12)
    participant PB as SourceB / SinkC (probe)
    participant SCH as Scheduler offer wire
    participant SBU as SBU (DSS and AT)

    Note over Q,SBU: SPEC AS WRITTEN — the answer now reads the query set, and the query set is the wrong one

    rect rgb(223,240,216)
    Q->>MA: allocate for set 5
    MA->>PB: Probe toN on the chosen victim
    MA->>MA: migDeferred = 1, waiting many cycles
    end

    rect rgb(248,215,218)
    Note over Q,SBU: Head has moved to set 9, which is paired with set 30.
    Q->>SBU: migrateQuery = set 9
    SBU->>SBU: read AT of set 9, it is a paired source, partner is 30
    SBU-->>SCH: destSet = 30
    end

    rect rgb(248,215,218)
    PB-->>MA: ProbeAck, victim is clean and client free
    SCH-->>MA: offer = set 30
    MA->>MA: migDstSet = 30
    Note over MA: WRONG. Set 5's partner is 12.<br/>The line is parked in 30 and no search will ever look there.
    end
```

**Why this is the common case, not a corner case.** The deferred path in Diagram B is exactly what
probe-then-migrate (`cbb3837`) made the majority path — it is what took `p` from 0.018 percent to
78.4 percent. Almost every migration now decides late, so almost every migration would take the
wrong partner.

---

## Diagram C — What a misplaced copy actually costs

Not an immediate corruption. Only clean, client-free lines are ever migrated, so memory already holds
identical bytes. The copy is **redundant**, not wrong. The damage arrives later.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000","activationBkgColor":"#f2f2f2","activationBorderColor":"#666666"}}}%%
flowchart TD
    A[Line L parked in set 30<br/>marked displaced, carries set 5's tag] --> B[But the AT says set 5's partner is set 12]
    B --> C[CPU asks for L again]
    C --> D[Secondary search looks in set 12 and finds nothing]
    D --> E[Fetch L from memory into set 5<br/>TWO copies of L now exist on chip]
    E --> F[CPU writes L<br/>set 5 copy is current, set 30 copy is now stale]
    F --> G[Set 5 eventually writes back<br/>memory is correct, set 30 is still stale]
    G --> H{Does a search ever reach set 30}
    H -- no --> I[Orphan line<br/>occupies a way until displaced reclaim evicts it<br/>capacity loss, NOT corruption]
    H -- yes, after a teardown and a re-pairing --> J[Tag matches on a displaced way<br/>STALE DATA SERVED TO THE CPU]
```

Two separate costs, and both are silent:

| Outcome | Severity | Notes |
|---|---|---|
| Orphan lines in the wrong set | capacity loss | Teardown checks the *recorded* partner, sees it empty, and tears down while lines still sit elsewhere. The displaced-reclaim tier eventually frees them. |
| A later search reaches that set | **corruption** | No assert catches it. It lands thousands of cycles after the mistake. |

---

## Diagram D — The fix. Point the query at the migrant, not at the queue head.

The fact that makes this cheap: **only one migration is in flight per bank**, enforced by
`anyMigrating` at [Scheduler.scala:218](../design/craft/inclusivecache/src/Scheduler.scala#L218) and
[:498](../design/craft/inclusivecache/src/Scheduler.scala#L498). One query serves everybody.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000","activationBkgColor":"#f2f2f2","activationBorderColor":"#666666"}}}%%
sequenceDiagram
    autonumber
    participant Q as Request queue head
    participant MA as MSHR A (set 5, paired with 12)
    participant PB as SourceB / SinkC (probe)
    participant SCH as Scheduler
    participant SBU as SBU (DSS and AT)

    Note over Q,SBU: THE FIX — the query follows the migrant

    rect rgb(223,240,216)
    Q->>MA: allocate for set 5
    MA->>PB: Probe toN on the chosen victim
    MA->>MA: migDeferred = 1, so status.migPending = 1
    end

    rect rgb(207,226,255)
    Note over SCH,SBU: NEW. The Scheduler sees migPending and retargets the query.
    SCH->>SCH: migrantOH built from status.migPending<br/>querySet = Mux(migrantOH.orR, migrantSet, request.set)
    SCH->>SBU: migrateQuery = set 5
    SBU->>SBU: read AT of set 5, paired source, partner is 12
    SBU-->>SCH: destSet = 12 and destOk = 1<br/>no coldness test for a pinned source, by design
    end

    rect rgb(207,226,255)
    Note over Q,SCH: Head still moves on to set 9 — it simply no longer wins the query.
    Q-->>SCH: set 9 request continues normally
    Note over SCH: It cannot start its own migration either,<br/>because anyMigrating is already high.
    end

    rect rgb(223,240,216)
    PB-->>MA: ProbeAck, victim is clean and client free
    SCH-->>MA: offer = set 12
    MA->>MA: migDstSet = 12. Correct partner.
    MA->>MA: assert dstClaim equals pairSetReg — fires loudly if this ever regresses
    end
```

### Why the mux is exact, not a heuristic

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000","activationBkgColor":"#f2f2f2","activationBorderColor":"#666666"}}}%%
flowchart TD
    A{Is any MSHR mid migration} -- no --> B[Nobody is waiting on a probe]
    B --> C[The only possible migrant is the request allocating right now]
    C --> D[querySet = request.set is that MSHR's own set<br/>CORRECT]
    A -- yes --> E[Exactly one MSHR, guaranteed by anyMigrating]
    E --> F[querySet = that MSHR's own set<br/>CORRECT]
    F --> G[No second migration can start meanwhile<br/>adviceMigrate requires not anyMigrating]
```

---

## The two sides of the partner question

The destination is not the only place the partner is needed. The other place tolerates staleness, and
that difference is why they are built differently.

```mermaid
%%{init: {"theme":"base","themeVariables":{"background":"#ffffff","mainBkg":"#ffffff","textColor":"#000000","lineColor":"#8a8a8a","primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#555555","secondaryColor":"#f2f2f2","tertiaryColor":"#ffffff","nodeTextColor":"#000000","nodeBorder":"#555555","clusterBkg":"#f7f7f7","clusterBorder":"#999999","edgeLabelBackground":"#ffffff","actorBkg":"#ffffff","actorBorder":"#555555","actorTextColor":"#000000","actorLineColor":"#8a8a8a","signalColor":"#8a8a8a","signalTextColor":"#000000","labelBoxBkgColor":"#ffffff","labelBoxBorderColor":"#555555","labelTextColor":"#000000","loopTextColor":"#000000","noteBkgColor":"#fff5ad","noteBorderColor":"#aaaa33","noteTextColor":"#000000","activationBkgColor":"#f2f2f2","activationBorderColor":"#666666"}}}%%
flowchart TD
    A[Who is set S's partner] --> B[SPILL side<br/>where do I park this line]
    A --> C[LOOKUP side<br/>where do I go search]
    B --> D[Wrong answer means<br/>a lost line plus a future stale copy]
    C --> E[Wrong answer means<br/>a search that finds nothing<br/>then a normal memory fetch]
    D --> F[Must be FRESH<br/>live AT read, retargeted as in Diagram D]
    E --> G[May be SLIGHTLY OLD<br/>pairInfo latched once at allocate]
    G --> H[One reader per MSHR would cost<br/>a wide AT mux each, near the dir-read path]
    F --> I[Only one reader ever<br/>so one AT port is enough]
```

---

## The three edits, in order

| Edit | File | What |
|---|---|---|
| 1 | `Scheduler.scala` | `migrateQuery` follows the migrant. `migrantOH` from `status.migPending`, `Mux1H` for its set, mux against `request.bits.set`. |
| 2 | `SetBalanceUnit.scala` | `destSet = Mux(paired, partner, DSS pick)`. `destOk = not a destination AND (paired OR the DSS pick is cold and unpaired)`. A pinned source gets no coldness test. |
| 3 | none | "Partner is busy" already works. `dstOfferOwned` compares the offer against every live MSHR set — with edit 2 that offer *is* the pinned partner, so the existing line becomes the decline-and-skip check unchanged. |

Plus one assert in `MSHR.scala`, once `pairInfo` is latched:

```scala
assert(!io.dstClaim.valid || !pairValidReg || io.dstClaim.bits === pairSetReg,
       "SBC: paired source migrated outside its partner set")
```

That converts the silent, thousands-of-cycles-later corruption of Diagram C into an immediate,
loud simulation failure.

### Cost

- One `Mux1H` over MSHR status registers, all of which already exist
- One extra wide AT read, `at(dssPick)`, alongside the one the spec already adds
- No new state, no new fences, no change to the offer wire, the claim, or the `dstSetConflict` fence
- Loop-free: `migrantSet` is built only from registered status, satisfying the note at
  [Scheduler.scala:505](../design/craft/inclusivecache/src/Scheduler.scala#L505)

---

## What this changes in the spec

`spec-sbc-phase3-prereqs.md` Part 1 is **correct in intent and wrong in mechanism**. It was written
2026-07-05, before late destination binding (`f40fbd4`) replaced per-source destination selection with
a single broadcast offer wire.

- **Keep:** the pinning rules, the 1:1 invariant, the two commit asserts, the DSS remove port, the
  `secondarySearch` building block, the `pairInfo` latch.
- **Replace:** the destination logic, with edits 1 and 2 above.
- **Drop:** any assumption that `migrateQuery` is driven by the migrating set. It is not, and that is
  the whole bug.
