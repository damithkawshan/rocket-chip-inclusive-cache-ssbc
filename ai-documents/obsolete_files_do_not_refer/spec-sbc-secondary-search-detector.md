# Spec — Phase 3 Step 2: secondary-search detector (measure-only)

**Author:** thinker/eval. **Implementer:** coder. **Status:** ready to implement.
**Depends on:** Step 1 resolved (migrations landing in cold sets). Pairs with
[spec-sbc-reset-register.md](spec-sbc-reset-register.md) (add the new counters to its clear list).

## Goal

Answer one number in hardware, with **no data movement and no coherence change**: *of demand misses to
a set that is an active migration source, how often is the requested line sitting in the associated
set as a displaced copy?* That's the **secondary-hit rate** — the go/no-go for building the Step 3
True Swap. Surface it on `SBC_SecHits` / `SBC_SecMiss` (both currently hardwired to 0).

This is a **passive observer**. It never serves the line, never moves anything, and never gates the
demand miss — the demand goes to memory exactly as today; the search runs in parallel and just counts.

## Why a second directory read is unavoidable

The tags of the displaced copy live in the directory SRAM of set **D** (the associated set). The
demand read only reads set **S**. So detection requires one extra directory read of D. The design
below issues that read **opportunistically at lowest priority** (only when no MSHR/alloc read wants
the port) and **droppable** (a single pending slot; overflow is counted, not stalled), so it perturbs
nothing.

## Data flow

```
demand miss to S (alloc result, hit=0)
   └─ S is an active source?  ── sbu.assocResp.activeSource  (AT[S].valid && sd==source)
        └─ yes → latch {tag T, dest D = AT[S].assocSet} into 1-entry pending
             └─ when dir read port free → inject read {set=D, tag=T, secondarySearch=1}
                  └─ directory returns secondaryHit = "a displaced way in D has tag T"
                       └─ secHit++ / secMiss++     (search read is hidden from the tap)
```

Everything lives in **Scheduler** (the detector block, gated by `enableSetBalancing`) + **Directory**
(additive: the displaced-tag match) + **SetBalanceUnit** (two counters). **MSHR is untouched.**

## Edits

Line anchors are "as of now" — match by surrounding code.

### 1. `Directory.scala` — add the displaced-tag match (additive, baseline-inert)

**(a) `DirectoryRead`** (~line 65) — new request flag:
```scala
// SBC Phase 3: secondary search — match a DISPLACED way by tag (the opposite of the normal hit,
// which excludes displaced ways). Baseline reads leave this false.
val secondarySearch = Bool()
```

**(b) `DirectoryResult`** (~line 71) — new outputs:
```scala
val secondaryHit = Bool()               // a displaced way matched `tag` (only meaningful when the read set secondarySearch)
val secondaryWay = UInt(params.wayBits.W) // its way (unused in Step 2; wired for Step 3 reuse)
```

**(c) pipeline the flag** to result alignment, next to `preferInvalid`/`preferEvictable` (~line 131):
```scala
val secondarySearch = params.dirReg(RegEnable(io.read.bits.secondarySearch, ren), ren1)
```

**(d) compute the displaced match** next to the normal `hits` (~line 172). Note: `displaced` is
**included** here and `INVALID` excluded — the mirror image of the normal `hits`:
```scala
val secHitsOH = Cat(ways.zipWithIndex.map { case (w, i) =>
  w.tag === tag && w.state =/= INVALID && w.displaced && (!setQuash || i.U =/= bypass.way)
}.reverse)
io.result.bits.secondaryHit := secHitsOH.orR
io.result.bits.secondaryWay := OHToUInt(secHitsOH)
```

**(e) hide the search read from the observation tap** (critical — see correctness note) — change the
tap valid at ~line 184:
```scala
io.tap.valid := ren2 && !secondarySearch
```

Baseline: when SBC is off no way is ever displaced and `secondarySearch` is always false, so
`secondaryHit` is always 0 and the tap is unchanged — bit-exact.

### 2. `Scheduler.scala` — the detector (inside `if (params.micro.enableSetBalancing)`)

**(a)** Add a baseline default for the new read field, next to the other `directory.io.read.bits.*`
assignments (~line 311, in the always-elaborated block):
```scala
directory.io.read.bits.secondarySearch := false.B
```

**(b)** In the SBC block, drive the AT query with the *allocating* set and detect the qualifying event.
Replace the current tie-off (`sbu.io.assocQuery.valid := false.B` / `.bits := 0.U`, ~lines 492–493):
```scala
// Phase 3 detector: ask the AT whether the allocating set is an active migration source.
sbu.io.assocQuery.valid := alloc_uses_directory
sbu.io.assocQuery.bits  := request.bits.set
val ssQualIssue = alloc_uses_directory && isDemandA && sbu.io.assocResp.activeSource
val ssTagIssue  = request.bits.tag
val ssDestIssue = sbu.io.assocResp.assocSet
// align issue-time facts to the result (same construct as directoryFanout)
val ssQual_r = params.dirReg(RegNext(ssQualIssue))
val ssTag_r  = params.dirReg(RegNext(ssTagIssue))
val ssDest_r = params.dirReg(RegNext(ssDestIssue))

// single-entry pending search slot
val ssPendValid = RegInit(false.B)
val ssPendTag   = Reg(UInt(params.tagBits.W))
val ssPendDest  = Reg(UInt(params.setBits.W))
val nSecDrop    = RegInit(0.U(32.W))

// qualifying event = demand alloc that MISSED in an active-source set
val ssHitEvent = ssQual_r && directory.io.result.valid && !directory.io.result.bits.hit
when (ssHitEvent) {
  when (!ssPendValid) { ssPendValid := true.B; ssPendTag := ssTag_r; ssPendDest := ssDest_r }
  .otherwise          { nSecDrop := nSecDrop + 1.U }   // slot busy → dropped (undercount, counted)
}

// fire the search read only when no real reader wants the port, and dir is up
val existingRead = mshr_uses_directory || alloc_uses_directory || mshr_uses_directory_for_dread
val ssFire = ssPendValid && !existingRead && directory.io.ready
when (ssFire) {
  ssPendValid := false.B
  directory.io.read.valid            := true.B      // last-connect-wins over line ~306 (which is false here, since !existingRead)
  directory.io.read.bits.set         := ssPendDest
  directory.io.read.bits.tag         := ssPendTag
  directory.io.read.bits.secondarySearch := true.B
  directory.io.read.bits.preferInvalid   := false.B
  directory.io.read.bits.preferEvictable := false.B
}

// count at the aligned result (result fans out to NO mshr this cycle — see correctness note)
val ssInflight = params.dirReg(RegNext(ssFire))
sbu.io.secHit  := ssInflight && directory.io.result.valid &&  directory.io.result.bits.secondaryHit
sbu.io.secMiss := ssInflight && directory.io.result.valid && !directory.io.result.bits.secondaryHit
```

### 3. `SetBalanceUnit.scala` — real secondary-hit counters

**(a) IO** (next to `migAttempt`/`migAbort`, ~line 70):
```scala
val secHit  = Input(Bool())
val secMiss = Input(Bool())
```

**(b) counters** (next to `nAttempt`/`nAbort`, ~line 130):
```scala
val nSecHit  = RegInit(0.U(32.W))
val nSecMiss = RegInit(0.U(32.W))
when (io.secHit)  { nSecHit  := nSecHit  + 1.U }
when (io.secMiss) { nSecMiss := nSecMiss + 1.U }
```

**(c) stats** — replace the hardwired zeros at ~lines 156–157:
```scala
io.stats.secHits := nSecHit
io.stats.secMiss := nSecMiss
```

**(d)** If [spec-sbc-reset-register.md](spec-sbc-reset-register.md) is already in, add `nSecHit` and
`nSecMiss` to its `when (io.clear) { ... }` block.

## Correctness notes (read these — they're the traps)

- **Tap purity (edit 1e) is mandatory.** The tap feeds the saturation counter and DSS. Without the
  `&& !secondarySearch` gate, every search read of set D fires a spurious `sat(D)` ±1, corrupting the
  demand-only counter that Step 1 depends on. This gate is the single most important line in the spec.
- **The search result reaches no MSHR.** `ssFire` only asserts when `existingRead` is false, so
  `directoryFanout` (Scheduler ~line 366) is 0 at the aligned result cycle → no MSHR consumes it. The
  detector is the only reader of that result. Do not add the search to `directoryFanout`.
- **Port priority / no starvation.** `ssFire` is strictly lower priority than every real reader and
  clears its single pending slot on fire, so it injects at most one read per detected miss and delays
  a pending directory *write* by at most one cycle. Do not raise its priority.
- **`directory.io.ready` gate.** Reads assert `wipeDone`; `ssFire` includes `directory.io.ready` so it
  can't fire during the reset wipe.
- **1-entry pending is intentional.** Concurrent qualifying misses beyond one in flight are dropped
  and counted in `nSecDrop`. If `nSecDrop` is large relative to `secHits+secMiss`, widen to a small
  FIFO — but start with one slot (simple, rip-out-able).

## Deliberately NOT in this step

- No serving, no data movement, no dir-write, no coherence change (that's Step 3).
- MSHR FSM untouched.
- The `secondaryWay` output is wired but unused now (free hook for Step 3).

## Related observation (out of scope, flag only)

The existing **migration 2nd dir-read** (`preferInvalid` probe of dstSet) is *not* excluded from the
tap today, so it already nudges `sat(dstSet)`. That's a pre-existing minor purity leak in the
saturation counter, separate from this step. Do **not** fix it here — noting it so the coder/thinker
can decide separately whether the tap should also exclude `preferInvalid` reads.

## Verification

1. **Elaborates** with `enableSetBalancing` true and false; baseline bit-exact (secondaryHit≡0, tap
   unchanged).
2. **Functional (sim, `sbcDebug`):**
   - Force a migration of a line from S to D (existing stress path). Then issue a demand for that same
     line (it maps to S, misses in S).
   - Expect: one qualifying event → one search read of D → `secondaryHit=1` → `SBC_SecHits` +1.
   - Demand a line from S that was *not* migrated → `SBC_SecMiss` +1.
3. **Purity check:** confirm `sat`/`SBC_ColdestSet` are unchanged by search reads (point `SBC_SetSel`
   at D, verify its saturation only moves on real demand traffic, not on searches).
4. **FPGA:** run the target workload; `secHits / (secHits + secMiss)` is the Step-3 go/no-go rate;
   watch `nSecDrop` to gauge undercount.

## Optional (recommended for a trustworthy rate): expose the drop count

Add `SBC_SecDrop` at the next free offset **0x360** (mirror the read-only stat pattern:
`SBCStats.secDrop`, wired from `nSecDrop`, `RegField.r` in Control.scala, plus the CLAUDE.md /
devmem-map rows). Without it you can't tell a genuinely low secondary-hit rate from an undercount.
