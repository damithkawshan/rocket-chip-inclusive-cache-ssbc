/*
 * Monitoring counters (tasks 004, 005, 006): every counter software reads over MMIO lives here, and
 * nothing the cache runs on. enablePerfCounters = false removes this module; its registers read 0.
 * The outer-port, lookup and outcome groups are NOT gated by enableSetBalancing - an SBC-on vs
 * SBC-off A/B needs them counting identically in both builds (in a NoSbc build the three
 * second-search outcome counters stay 0, because the MSHR pulses that feed them never fire).
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._

class PerfCounterStats extends Bundle
{
  // outer port (006)
  val memReads    = UInt(64.W)  // outer AcquireBlock  - a block was READ from main memory
  val memWrites   = UInt(64.W)  // outer ReleaseData   - a dirty block was WRITTEN to main memory
  val memAcqPerm  = UInt(64.W)  // outer AcquirePerm   - requester overwrites the whole block, moves no bytes
  val memRelClean = UInt(64.W)  // outer Release       - clean eviction announced, moves no bytes
  val cycles      = UInt(64.W)  // free-running L2 clock
  // legacy lookups (004)
  val l2Accesses  = UInt(64.W)  // directory lookups, every channel, internal reads excluded
  val l2Hits      = UInt(64.W)  // those lookups that hit
  // SBC events
  val migrations  = UInt(64.W)  // committed migrations
  val attempted   = UInt(64.W)  // migrations started
  val aborted     = UInt(64.W)  // aborted after start, or declined before start
  val secHits     = UInt(64.W)  // serves from the partner set, any channel
  val secMiss     = UInt(64.W)  // partner searched, line not there
  val secPerm     = UInt(64.W)  // partner serves that had to acquire permission
  val secWrite    = UInt(64.W)  // partner serves where the requester needed T
  val secProbe    = UInt(64.W)  // partner serves that probed L1 first
  val dispRelease = UInt(64.W)  // dirty parked lines written back
  val dispDrop    = UInt(64.W)  // clean parked lines released with no data
  val secC        = UInt(64.W)  // partner serves raised by a C-channel Release
  val homeBranch  = UInt(64.W)  // directory results that found the home line in BRANCH
  val parked      = UInt(64.W)  // a level: parked lines resident now
  // outcomes (005 commit 1) - cache-terminology.md
  val accessA       = UInt(64.W)  // an inner-A request was accepted
  val primaryHit    = UInt(64.W)  // home line hit, enough permission, no outer A
  val secondaryHit  = UInt(64.W)  // served from the partner set, enough permission, no outer A
  val probedHit     = UInt(64.W)  // a primary or secondary hit that also probed a client
  val dataMiss      = UInt(64.W)  // outer A, param != BtoT
  val upgradeMiss   = UInt(64.W)  // outer A, param == BtoT
  val secondSearch  = UInt(64.W)  // the plan armed a partner search
  val secondaryMiss = UInt(64.W)  // partner searched, line not found
}

// SBC event pulses, as the number of MSHRs raising each one this cycle.
class SBCEventPulses(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val migAttempt  = UInt(log2Ceil(params.mshrs + 1).W)
  val migAbort    = UInt(log2Ceil(params.mshrs + 1).W)
  val migCommit   = UInt(log2Ceil(params.mshrs + 1).W)
  val secHit      = UInt(log2Ceil(params.mshrs + 1).W)
  val secMiss     = UInt(log2Ceil(params.mshrs + 1).W)
  val secPerm     = UInt(log2Ceil(params.mshrs + 1).W)
  val secWrite    = UInt(log2Ceil(params.mshrs + 1).W)
  val secProbe    = UInt(log2Ceil(params.mshrs + 1).W)
  val dispRelease = UInt(log2Ceil(params.mshrs + 1).W)
  val dispDrop    = UInt(log2Ceil(params.mshrs + 1).W)
  val secC        = UInt(log2Ceil(params.mshrs + 1).W)
  val homeBranch  = UInt(log2Ceil(params.mshrs + 1).W)
}

// Outcome pulses (005 commit 1), as the number of MSHRs raising each one this cycle. Not gated by
// enableSetBalancing: primaryHit/probedHit are meaningful with SBC off too, and secondSearch/
// secondaryHit/secondaryMiss simply stay 0 there (the MSHR pulses that feed them never fire).
class OutcomePulses(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val primaryHit    = UInt(log2Ceil(params.mshrs + 1).W)
  val secondSearch  = UInt(log2Ceil(params.mshrs + 1).W)
  val secondaryHit  = UInt(log2Ceil(params.mshrs + 1).W)
  val probedHit     = UInt(log2Ceil(params.mshrs + 1).W)
  val secondaryMiss = UInt(log2Ceil(params.mshrs + 1).W)
}

class PerfCounters(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    // Outer A, sampled AT THE PORT (io.out.a), not inside SourceA: the outer A message is single
    // beat, so one fire is one memory access, and counting at the port means outerBuf cannot
    // double count.
    val aFire   = Input(Bool())
    val aOpcode = Input(UInt(3.W))
    val aParam  = Input(UInt(3.W))  // 005: classifies data miss (!= BtoT) vs upgrade miss (== BtoT)
    // Outer C, sampled at sourceC.io.req.fire - NOT io.out.c.fire, which is multi-beat for
    // ReleaseData and would count one eviction once per beat (req.fire is exactly once per release).
    val cFire   = Input(Bool())
    val cDirty  = Input(Bool())
    // Directory observation tap (result-aligned).
    val tap     = Flipped(Valid(new DirectoryTap(params)))
    // An inner-A request was accepted this cycle (005: L2_AccessA). Scheduler-level, not per-MSHR.
    val accessA = Input(Bool())
    // SBC event pulses from the MSHRs. Ignored in a NoSbc build.
    val sbc     = Input(new SBCEventPulses(params))
    // 005 commit 1: outcome pulses from the MSHRs (cache-terminology.md).
    val outcome = Input(new OutcomePulses(params))
    val clearStats = Input(Bool())  // SBC_StatsReset: every event counter
    val clearSbc   = Input(Bool())  // SBC_Reset: only the SBC event counters
    val hold       = Input(Bool())  // L2_StatsHold: freeze every event counter (not SBC_Parked)
    val stats   = Output(new PerfCounterStats)
  })

  println(s"[SBC][elab] PerfCounters: mshrs=${params.mshrs} secondary=${params.secondary} " +
    s"in-progress bound (mshrs-2)+secondary=${params.mshrs - 2 + params.secondary}")

  // SBC events read 0 in a NoSbc build.
  io.stats := 0.U.asTypeOf(new PerfCounterStats)

  val go = !io.hold  // T7: while held, every event counter (parked excepted) keeps its value

  // ---- outer port (006) ----
  val memReads    = RegInit(0.U(64.W))
  val memWrites   = RegInit(0.U(64.W))
  val memAcqPerm  = RegInit(0.U(64.W))
  val memRelClean = RegInit(0.U(64.W))

  when (go && io.aFire && io.aOpcode === TLMessages.AcquireBlock) { memReads    := memReads    + 1.U }
  when (go && io.aFire && io.aOpcode === TLMessages.AcquirePerm)  { memAcqPerm  := memAcqPerm  + 1.U }
  when (go && io.cFire &&  io.cDirty)                             { memWrites   := memWrites   + 1.U }
  when (go && io.cFire && !io.cDirty)                             { memRelClean := memRelClean + 1.U }

  // Free-running L2 clock. In the SBC_StatsReset list like the others, so `sbc_read --zero -- cmd`
  // yields exactly the cycles the child ran for. This is the L2/uncore clock, which is not
  // necessarily the core clock - valid as an SBC-on vs SBC-off ratio at a fixed frequency either way.
  val cycles = RegInit(0.U(64.W))
  when (go) { cycles := cycles + 1.U }

  // ---- legacy lookups (004) ----
  // The tap excludes internalRead: a second search / destination read has hit forced false, so
  // counting it would add misses to the SBC run only.
  val l2Accesses = RegInit(0.U(64.W))
  val l2Hits     = RegInit(0.U(64.W))
  when (go && io.tap.valid) {
    l2Accesses := l2Accesses + 1.U
    when (io.tap.bits.hit) { l2Hits := l2Hits + 1.U }
  }

  // ---- outcomes (005 commit 1) - cache-terminology.md. Not gated by enableSetBalancing. ----
  val accessA       = RegInit(0.U(64.W))
  val primaryHit    = RegInit(0.U(64.W))
  val secondaryHit  = RegInit(0.U(64.W))
  val probedHit     = RegInit(0.U(64.W))
  val dataMiss      = RegInit(0.U(64.W))
  val upgradeMiss   = RegInit(0.U(64.W))
  val secondSearch  = RegInit(0.U(64.W))
  val secondaryMiss = RegInit(0.U(64.W))

  when (go && io.accessA) { accessA := accessA + 1.U }
  when (go) {
    primaryHit    := primaryHit    + io.outcome.primaryHit
    secondaryHit  := secondaryHit  + io.outcome.secondaryHit
    probedHit     := probedHit     + io.outcome.probedHit
    secondSearch  := secondSearch  + io.outcome.secondSearch
    secondaryMiss := secondaryMiss + io.outcome.secondaryMiss
  }
  // Every outer A is exactly one data miss or one upgrade miss (TASK 005 §5.1 "why these hooks are
  // exact"): the serve block cancels the acquire armed on the A path unless secNeedPerm.
  when (go && io.aFire && io.aParam =/= TLPermissions.BtoT) { dataMiss    := dataMiss    + 1.U }
  when (go && io.aFire && io.aParam === TLPermissions.BtoT) { upgradeMiss := upgradeMiss + 1.U }

  // After the increments, so a same-cycle clear wins (drops that cycle's events). Clears ignore
  // `hold` - T7's freeze is for read stability, not to block a deliberate reset.
  when (io.clearStats) {
    memReads    := 0.U
    memWrites   := 0.U
    memAcqPerm  := 0.U
    memRelClean := 0.U
    cycles      := 0.U
    l2Accesses  := 0.U
    l2Hits      := 0.U
    accessA       := 0.U
    primaryHit    := 0.U
    secondaryHit  := 0.U
    probedHit     := 0.U
    dataMiss      := 0.U
    upgradeMiss   := 0.U
    secondSearch  := 0.U
    secondaryMiss := 0.U
  }

  io.stats.memReads    := memReads
  io.stats.memWrites   := memWrites
  io.stats.memAcqPerm  := memAcqPerm
  io.stats.memRelClean := memRelClean
  io.stats.cycles      := cycles
  io.stats.l2Accesses  := l2Accesses
  io.stats.l2Hits      := l2Hits
  io.stats.accessA       := accessA
  io.stats.primaryHit    := primaryHit
  io.stats.secondaryHit  := secondaryHit
  io.stats.probedHit     := probedHit
  io.stats.dataMiss      := dataMiss
  io.stats.upgradeMiss   := upgradeMiss
  io.stats.secondSearch  := secondSearch
  io.stats.secondaryMiss := secondaryMiss

  // ---- SBC events ----
  if (params.micro.enableSetBalancing) {
    val p = io.sbc
    val migrations  = RegInit(0.U(64.W))
    val attempted   = RegInit(0.U(64.W))
    val aborted     = RegInit(0.U(64.W))
    val secHits     = RegInit(0.U(64.W))
    val secMiss     = RegInit(0.U(64.W))
    val secPerm     = RegInit(0.U(64.W))
    val secWrite    = RegInit(0.U(64.W))
    val secProbe    = RegInit(0.U(64.W))
    val dispRelease = RegInit(0.U(64.W))
    val dispDrop    = RegInit(0.U(64.W))
    val secC        = RegInit(0.U(64.W))
    val homeBranch  = RegInit(0.U(64.W))
    when (go) {
      migrations  := migrations  + p.migCommit
      attempted   := attempted   + p.migAttempt
      aborted     := aborted     + p.migAbort
      secHits     := secHits     + p.secHit
      secMiss     := secMiss     + p.secMiss
      secPerm     := secPerm     + p.secPerm
      secWrite    := secWrite    + p.secWrite
      secProbe    := secProbe    + p.secProbe
      dispRelease := dispRelease + p.dispRelease
      dispDrop    := dispDrop    + p.dispDrop
      secC        := secC        + p.secC
      homeBranch  := homeBranch  + p.homeBranch
    }

    // A level, not an event: never held, never cleared by clearStats/clearSbc's `when` below except
    // its own arithmetic. A commit and an erase in one cycle cancel; never below 0.
    val parked  = RegInit(0.U(log2Ceil(params.cache.sets * params.cache.ways + 1).W))
    val parkInc = p.migCommit =/= 0.U
    val parkDec = p.dispRelease =/= 0.U || p.dispDrop =/= 0.U
    when (parkInc && !parkDec)                        { parked := parked + 1.U }
    .elsewhen (!parkInc && parkDec && parked =/= 0.U) { parked := parked - 1.U }

    // SBC_Reset while lines are parked orphans them - the AT is their only home-set record.
    assert (!io.clearSbc || parked === 0.U, "SBC_Reset issued while lines are still parked")

    when (io.clearStats || io.clearSbc) {
      migrations := 0.U; attempted := 0.U; aborted := 0.U
      secHits := 0.U; secMiss := 0.U; secPerm := 0.U
      secWrite := 0.U; secProbe := 0.U
      dispRelease := 0.U; dispDrop := 0.U; secC := 0.U; homeBranch := 0.U
    }

    io.stats.migrations  := migrations
    io.stats.attempted   := attempted
    io.stats.aborted     := aborted
    io.stats.secHits     := secHits
    io.stats.secMiss     := secMiss
    io.stats.secPerm     := secPerm
    io.stats.secWrite    := secWrite
    io.stats.secProbe    := secProbe
    io.stats.dispRelease := dispRelease
    io.stats.dispDrop    := dispDrop
    io.stats.secC        := secC
    io.stats.homeBranch  := homeBranch
    io.stats.parked      := parked
  }
}
