/*
 * Set-Balancing Cache (SBC) — SetBalanceUnit
 *
 * Advisory + bookkeeping only: owns the per-set saturation counters, the Association Table (AT),
 * and the DSS. It answers queries and exposes read-only stats, but holds NO BankedStore/Directory
 * ports — the migration datapath lives in MSHR/Scheduler/BankedStore. See ai-documents/.
 *
 * Phase 0: pure observation. Migration is OFF (migrateResp.migrate == false), the AT is inert.
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._

// SBC commit kinds carried on SetBalanceUnit.io.commit.kind.
object SBCCommitKind {
  def MIGRATE = 1.U(2.W) // a migration committed (src→dst)
}

// One Association Table entry, per set. (Inert in Phase 0.)
class ATEntry(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val valid    = Bool()
  val sd       = Bool()                    // 0 = source, 1 = destination
  val assocSet = UInt(params.setBits.W)    // destination set (if source) / home set (if destination)
}

// Read-only stats surfaced to the MMIO control block. Parameterised by widths only so Control.scala
// can build it without a full InclusiveCacheParameters.
class SBCStats(setBits: Int, satBits: Int) extends Bundle
{
  val coldestValid = Bool()
  val coldestSet   = UInt(setBits.W)
  val coldestLevel = UInt(satBits.W)
  val satReadValue = UInt(satBits.W)       // saturation of the SW-selected set
  val atValid      = Bool()                // AT[selected set].valid (Phase 0: always 0)
  val migrations   = UInt(32.W)            // committed migrations
  val attempted    = UInt(32.W)            // migrations attempted (setup reached)
  val aborted      = UInt(32.W)            // migrations aborted (ineligible src/dst)
  val secHits      = UInt(32.W)
  val secMiss      = UInt(32.W)
}

class SetBalanceUnit(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val dirTap = Flipped(Valid(new DirectoryTap(params)))
    // advisory queries (stubbed in Phase 0)
    val migrateQuery = Flipped(Valid(UInt(params.setBits.W)))
    val migrateResp  = Output(new Bundle {
      val migrate = Bool()
      val destSet = UInt(params.setBits.W)
    })
    val assocQuery = Flipped(Valid(UInt(params.setBits.W)))
    val assocResp  = Output(new Bundle {
      val activeSource = Bool()
      val assocSet     = UInt(params.setBits.W)
    })
    val commit = Flipped(Valid(new Bundle {
      val kind = UInt(2.W)
      val src  = UInt(params.setBits.W)
      val dst  = UInt(params.setBits.W)
    }))
    // SBC Phase 1: SW arm pulse from MMIO SBC_BalanceSet (1-cycle valid+set).
    val arm = Flipped(Valid(UInt(params.setBits.W)))
    // SBC Phase 2: migration counter pulses from the MSHRs (attempted at the migrate decision,
    // aborted at the dst-full fallback).
    val migAttempt = Input(Bool())
    val migAbort   = Input(Bool())
    // SBC reset: SW pulse from MMIO SBC_Reset — zeroes all counters, saturation, AT and the DSS.
    val clear = Input(Bool())
    // MMIO
    val satReadSet = Input(UInt(params.setBits.W))
    val stats      = Output(new SBCStats(params.setBits, params.micro.satCounterBits))
  })

  val sets    = params.cache.sets
  val satBits = params.micro.satCounterBits
  val satMin  = BigInt(0)
  val satMaxN = (BigInt(1) << satBits) - 1
  println(s"[SBC][elab] satCounter min=${satMin} max=${satMaxN} " +
    s"T_hi=${params.micro.migrationThreshold} T_lo=${params.micro.migrationClearThreshold}")
  val satMax  = ((BigInt(1) << satBits) - 1).U(satBits.W)

  

  val sat = RegInit(VecInit(Seq.fill(sets)(0.U(satBits.W))))
  val at  = RegInit(VecInit(Seq.fill(sets)(0.U.asTypeOf(new ATEntry(params)))))

  // Pluggable saturation delta. Phase 0: hit -> -1, miss -> +1 (saturating). Probe/release terms
  // (the future probe-integration work) plug in here without touching the datapath.
  val tapSet = io.dirTap.bits.set
  val cur    = sat(tapSet)
  val nxt    = Mux(io.dirTap.bits.hit,
                   Mux(cur === 0.U,    0.U,    cur - 1.U),
                   Mux(cur === satMax, satMax, cur + 1.U))
  when (io.dirTap.valid) { sat(tapSet) := nxt }

  // DSS, fed with the post-update level of the touched set.
  val dss = Module(new DSS(params, params.micro.dssEntries))
  dss.io.update.valid      := io.dirTap.valid
  dss.io.update.bits.set   := tapSet
  dss.io.update.bits.level := nxt
  dss.io.clear             := io.clear
  // Advisory query responses. migrateResp is the Phase-2 migrate advice; it is assigned below,
  // after `armed`/thresholds are declared.
  io.assocResp.activeSource := at(io.assocQuery.bits).valid && !at(io.assocQuery.bits).sd
  io.assocResp.assocSet     := at(io.assocQuery.bits).assocSet

  // ---- SBC Phase 1: arm-and-fire migration trigger ------------------------------------------
  // SW arms a source set via SBC_BalanceSet. While that set is armed AND hot (sat >= T_hi) we
  // emit a migrate request to the injection path; the throttle keeps at most one in flight.
  // Hysteresis: the armed bit self-clears once the set cools below T_lo.
  val armed = RegInit(VecInit(Seq.fill(sets)(false.B)))
  val tHi   = params.micro.migrationThreshold.U
  val tLo   = params.micro.migrationClearThreshold.U
  when (io.dirTap.valid && nxt < tLo) { armed(tapSet)     := false.B } // cooled -> disarm
  when (io.arm.valid)                 { armed(io.arm.bits) := true.B }  // SW arm (wins same-cycle)

  // ---- SBC Phase 2: migrate advice for the demand-allocating set ------------------------------
  // The Scheduler queries with the allocating set; the MSHR re-checks victim eligibility and the
  // Scheduler ANDs in the one-migration token. "migrate" = source is hot (armed/auto + sat>=T_hi)
  // AND a genuinely cold destination exists.
  val qSet = io.migrateQuery.bits
  io.migrateResp.migrate := (params.micro.sbcAutoMigrate.B || armed(qSet)) && (sat(qSet) >= tHi) &&
                            dss.io.coldestValid && (dss.io.coldestLevel < tLo)
  io.migrateResp.destSet := dss.io.coldestSet

  // ---- SBC Phase 1: migration counters + AT commit (step 7) -----------------------------------
  // attempted/aborted come from dedicated MSHR pulses; migrations from commit{MIGRATE}.
  val nAttempt = RegInit(0.U(32.W))
  val nAbort   = RegInit(0.U(32.W))
  val nCommit  = RegInit(0.U(32.W))
  when (io.migAttempt) { nAttempt := nAttempt + 1.U }
  when (io.migAbort)   { nAbort   := nAbort + 1.U }
  // A committed migration records its src<->dst pairing in the AT (read by Phase-3 secondary search).
  // It can't be unwound, so the write is unconditional (overwrite if already set).
  when (io.commit.valid && io.commit.bits.kind === SBCCommitKind.MIGRATE) {
    nCommit := nCommit + 1.U
    at(io.commit.bits.src).valid    := true.B
    at(io.commit.bits.src).sd       := false.B            // source side
    at(io.commit.bits.src).assocSet := io.commit.bits.dst
    at(io.commit.bits.dst).valid    := true.B
    at(io.commit.bits.dst).sd       := true.B             // destination side
    at(io.commit.bits.dst).assocSet := io.commit.bits.src
  }

  // SBC reset: a write to MMIO SBC_Reset zeroes every piece of SBC observation state in one cycle.
  // Placed after all update logic above so a same-cycle dirTap update / commit loses to the clear.
  when (io.clear) {
    sat.foreach   (_ := 0.U)
    armed.foreach (_ := false.B)
    // TODO(phase3): once the AT is wired into the live secondary-search/teardown path, clearing it
    // while displaced lines still exist would orphan them (the AT is their home-set recovery info).
    at.foreach    (_ := 0.U.asTypeOf(new ATEntry(params)))
    nAttempt := 0.U
    nAbort   := 0.U
    nCommit  := 0.U
  }

  // Read-only stats for MMIO.
  io.stats.coldestValid := dss.io.coldestValid
  io.stats.coldestSet   := dss.io.coldestSet
  io.stats.coldestLevel := dss.io.coldestLevel
  io.stats.satReadValue := sat(io.satReadSet)
  io.stats.atValid      := at(io.satReadSet).valid
  io.stats.migrations   := nCommit
  io.stats.attempted    := nAttempt
  io.stats.aborted      := nAbort
  io.stats.secHits      := 0.U
  io.stats.secMiss      := 0.U

  // ---- sim-only debug printfs (Scala-gated; nothing elaborated when sbcDebug=false) ----
  if (params.micro.sbcDebug) {
    val cyc = RegInit(0.U(64.W)); cyc := cyc + 1.U

    // ---- threshold crossing events (one print per set per transition) ----

    // Set heats past T_hi (miss drove sat from below to at/above threshold).
    val crossedHot = io.dirTap.valid && !io.dirTap.bits.hit &&
                     cur < params.micro.migrationThreshold.U && nxt >= params.micro.migrationThreshold.U
    when (crossedHot) {
      printf(p"[SBC] HOT   set=${io.dirTap.bits.set} sat ${cur}->${nxt} (T_hi=${params.micro.migrationThreshold.U}) cycle=${cyc}\n")
    }

    // Set cools below T_lo (hit drove sat from at/above down to below clear threshold).
    val crossedCold = io.dirTap.valid && io.dirTap.bits.hit &&
                      cur >= params.micro.migrationClearThreshold.U && nxt < params.micro.migrationClearThreshold.U
    when (crossedCold) {
      printf(p"[SBC] COOL  set=${io.dirTap.bits.set} sat ${cur}->${nxt} (T_lo=${params.micro.migrationClearThreshold.U}) cycle=${cyc}\n")
    }

    // ---- ARM / migrate-request events ----
    when (io.arm.valid) {
      printf(p"[SBC] ARM   set=${io.arm.bits} sat=${sat(io.arm.bits)} cycle=${cyc}\n")
    }

    // ---- periodic per-set saturation dump + DSS snapshot ----
    // Print every `dumpPeriod` cycles; period is large enough to avoid log explosion.
    val dumpPeriod = 50000
    val doDump = (cyc % dumpPeriod.U) === 0.U && cyc =/= 0.U
    // when (doDump) {
    //   printf(p"[SBC] DUMP  cycle=${cyc} coldestValid=${io.stats.coldestValid} " +
    //          p"coldestSet=${io.stats.coldestSet} coldestLevel=${io.stats.coldestLevel} " +
    //          p"migr=${io.stats.migrations} attempted=${io.stats.attempted} aborted=${io.stats.aborted}\n")
    //   // Unroll per-set sat print (sets is a Scala Int, known at elaboration time).
    //   for (s <- 0 until sets) {
    //     printf(p"[SBC] DUMP    set[${s.U}] sat=${sat(s)} armed=${armed(s)}\n")
    //   }
    // }
  }
}
