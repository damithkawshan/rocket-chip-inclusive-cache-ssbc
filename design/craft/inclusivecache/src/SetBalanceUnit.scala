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
  val migrations   = UInt(32.W)
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

  // Advisory queries — migration OFF in Phase 0.
  io.migrateResp.migrate    := false.B
  io.migrateResp.destSet    := dss.io.coldestSet
  io.assocResp.activeSource := at(io.assocQuery.bits).valid && !at(io.assocQuery.bits).sd
  io.assocResp.assocSet     := at(io.assocQuery.bits).assocSet

  // No migrations happen in Phase 0, so the AT must stay inert.
  assert(!io.commit.valid, "SBC Phase 0: unexpected migration commit")

  // Read-only stats for MMIO.
  io.stats.coldestValid := dss.io.coldestValid
  io.stats.coldestSet   := dss.io.coldestSet
  io.stats.coldestLevel := dss.io.coldestLevel
  io.stats.satReadValue := sat(io.satReadSet)
  io.stats.atValid      := at(io.satReadSet).valid
  io.stats.migrations   := 0.U
  io.stats.secHits      := 0.U
  io.stats.secMiss      := 0.U

  // ---- sim-only debug printfs (Scala-gated; nothing elaborated when sbcDebug=false) ----
  if (params.micro.sbcDebug) {
    val cyc = RegInit(0.U(64.W)); cyc := cyc + 1.U

    // Per directory-lookup: set, hit/miss, resulting saturation level.
    when (io.dirTap.valid) {
      printf(p"[SBC] TAP set=${io.dirTap.bits.set} hit=${io.dirTap.bits.hit} sat=${nxt} cycle=${cyc}\n")
    }

    // A set heating past the migration threshold (the "hot set detected" event).
    val crossedHot = io.dirTap.valid && !io.dirTap.bits.hit &&
                     cur < params.micro.migrationThreshold.U && nxt >= params.micro.migrationThreshold.U
    when (crossedHot) {
      printf(p"[SBC] HOT set=${io.dirTap.bits.set} reached T_hi sat=${nxt} cycle=${cyc}\n")
    }

    // Periodic snapshot of the SW-selected set and the current DSS coldest candidate.
    val dumpPeriod = 2048
    when ((cyc % dumpPeriod.U) === 0.U && cyc =/= 0.U) {
      printf(p"[SBC] SUMMARY cycle=${cyc} selSet=${io.satReadSet} selSat=${io.stats.satReadValue} " +
             p"coldestValid=${io.stats.coldestValid} coldestSet=${io.stats.coldestSet} " +
             p"coldestLevel=${io.stats.coldestLevel}\n")
    }
  }
}
