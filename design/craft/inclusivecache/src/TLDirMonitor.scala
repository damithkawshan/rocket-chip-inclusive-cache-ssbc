/*
 * Bank-global TileLink + Directory activity monitor.
 *
 * Tracks coherence-protocol activity at L2 with a fixed set of saturating
 * 32-bit event counters. On each snapshot tick, every counter's value is
 * committed to a per-counter history memory (SyncReadMem) and reset to 0,
 * so each history row is the *delta* over one sampling interval.
 *
 * Storage cost: N counters x 32 bits x depth (one BRAM block per counter).
 * Timing strategy mirrors InclusiveCacheSatCounter:
 *   - one saturating-add per counter per cycle (single-event/cycle limit)
 *   - registered snap pulse drives BRAM write-enables and counter resets
 *   - history memory uses SyncReadMem (1-cycle read latency, BRAM-friendly)
 *
 * Disabled at elaboration time via micro.enableTLDirMonitor.
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._

// ---------------------------------------------------------------------------
// Counter ordering (single source of truth — used by HW + matched in C wrapper)
// ---------------------------------------------------------------------------
object TLDirMonitor
{
  // Group 1 — TL channel fires (9)
  val IDX_C_INA            = 0
  val IDX_C_INB            = 1
  val IDX_C_INC            = 2
  val IDX_C_IND            = 3
  val IDX_C_INE            = 4
  val IDX_C_OUTA           = 5
  val IDX_C_OUTC           = 6
  val IDX_C_OUTD           = 7
  val IDX_C_OUTE           = 8
  // Group 2 — Inner-A opcode breakdown (3)
  val IDX_A_ACQUIREBLOCK   = 9
  val IDX_A_ACQUIREPERM    = 10
  val IDX_A_GETPUT         = 11
  // Group 3 — Inner-C opcode breakdown (4)
  val IDX_C_RELEASE        = 12
  val IDX_C_RELEASEDATA    = 13
  val IDX_C_PROBEACK       = 14
  val IDX_C_PROBEACKDATA   = 15
  // Group 4 — Inner-D opcode breakdown (2)
  val IDX_D_GRANT          = 16
  val IDX_D_GRANTDATA      = 17
  // Group 5 — Directory hit/miss + eviction (4)
  val IDX_DIR_HIT          = 18
  val IDX_DIR_MISS         = 19
  val IDX_EVICT_CLEAN      = 20
  val IDX_EVICT_DIRTY      = 21
  // Group 6 — Directory write target state (4)
  val IDX_WRITE_TO_INVALID = 22
  val IDX_WRITE_TO_BRANCH  = 23
  val IDX_WRITE_TO_TRUNK   = 24
  val IDX_WRITE_TO_TIP     = 25
  // Group 7 — MSHR / scheduler pressure (3)
  val IDX_MSHR_ALLOC       = 26
  val IDX_MSHR_NO_FREE     = 27
  val IDX_SECONDARY_HIT    = 28

  val N_COUNTERS = 29
  val CTR_WIDTH  = 32
}

// ---------------------------------------------------------------------------
// Event tap bundle wired up by InclusiveCacheBankScheduler.
// All booleans are pulses — at most one assertion per cycle per tap.
// ---------------------------------------------------------------------------
class TLDirMonitorTaps(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  // Group 1 — channel fire pulses (inner = towards core, outer = towards mem)
  val inA_fire   = Bool()
  val inB_fire   = Bool()
  val inC_fire   = Bool()
  val inD_fire   = Bool()
  val inE_fire   = Bool()
  val outA_fire  = Bool()
  val outC_fire  = Bool()
  val outD_fire  = Bool()
  val outE_fire  = Bool()

  // Group 2 — Inner-A opcode bins (mutually exclusive on inA_fire cycle)
  val a_acquireBlock = Bool()
  val a_acquirePerm  = Bool()
  val a_getPut       = Bool()

  // Group 3 — Inner-C opcode bins (mutually exclusive on inC_fire cycle)
  val c_release       = Bool()
  val c_releaseData   = Bool()
  val c_probeAck      = Bool()
  val c_probeAckData  = Bool()

  // Group 4 — Inner-D opcode bins (mutually exclusive on inD_fire cycle)
  val d_grant      = Bool()
  val d_grantData  = Bool()

  // Group 5 — Directory lookup result (pipelined to align with directory.io.result.valid)
  val dir_hit      = Bool()
  val dir_miss     = Bool()
  val evict_clean  = Bool()
  val evict_dirty  = Bool()

  // Group 6 — Directory write target state (one-hot decoded against MetaData.{INVALID,BRANCH,TRUNK,TIP})
  val write_to_invalid = Bool()
  val write_to_branch  = Bool()
  val write_to_trunk   = Bool()
  val write_to_tip     = Bool()

  // Group 7 — MSHR / scheduler pressure
  val mshr_alloc    = Bool()  // a fresh MSHR allocation fired this cycle
  val mshr_no_free  = Bool()  // request waiting because no MSHR is free (cycle counter)
  val secondary_hit = Bool()  // request enqueued/bypassed via queue path (no fresh dir lookup)
}

// ---------------------------------------------------------------------------
// MMIO control / status interface (drives Control.scala regmap, fan-out from
// per-bank tied defaults in InclusiveCache.scala)
// ---------------------------------------------------------------------------
class TLDirMonitorCtrlIO(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  // SW → HW
  val enable    = Input(Bool())
  val reset_ctr = Input(Bool())
  val interval  = Input(UInt(32.W))
  val histIdx   = Input(UInt(log2Ceil(params.micro.tlDirHistoryDepth).W))

  // HW → SW
  val histReads  = Output(Vec(TLDirMonitor.N_COUNTERS, UInt(TLDirMonitor.CTR_WIDTH.W)))
  val writeCount = Output(UInt(log2Ceil(params.micro.tlDirHistoryDepth + 1).W))
  val full       = Output(Bool())
}

// ---------------------------------------------------------------------------
// Core module
// ---------------------------------------------------------------------------
class InclusiveCacheTLDirMonitor(params: InclusiveCacheParameters) extends Module
{
  import TLDirMonitor._

  println(s"InclusiveCache TL+Directory Monitor Configuration:")
  println(s"  Counters      : $N_COUNTERS")
  println(s"  Counter Width : $CTR_WIDTH bits (saturating)")
  println(s"  History Depth : ${params.micro.tlDirHistoryDepth}")

  val io = IO(new Bundle {
    val taps    = Flipped(new TLDirMonitorTaps(params))
    val control = new TLDirMonitorCtrlIO(params)
    val bankId  = Input(UInt(8.W))   // for printf identification
  })

  // -----------------------------------------------------------------------
  // Per-event saturating counter live registers
  // -----------------------------------------------------------------------
  val counters = RegInit(VecInit(Seq.fill(N_COUNTERS)(0.U(CTR_WIDTH.W))))

  // Pack tap pulses into a Vec with the same indexing as the counter array.
  val events = Wire(Vec(N_COUNTERS, Bool()))
  events(IDX_C_INA)            := io.taps.inA_fire
  events(IDX_C_INB)            := io.taps.inB_fire
  events(IDX_C_INC)            := io.taps.inC_fire
  events(IDX_C_IND)            := io.taps.inD_fire
  events(IDX_C_INE)            := io.taps.inE_fire
  events(IDX_C_OUTA)           := io.taps.outA_fire
  events(IDX_C_OUTC)           := io.taps.outC_fire
  events(IDX_C_OUTD)           := io.taps.outD_fire
  events(IDX_C_OUTE)           := io.taps.outE_fire
  events(IDX_A_ACQUIREBLOCK)   := io.taps.a_acquireBlock
  events(IDX_A_ACQUIREPERM)    := io.taps.a_acquirePerm
  events(IDX_A_GETPUT)         := io.taps.a_getPut
  events(IDX_C_RELEASE)        := io.taps.c_release
  events(IDX_C_RELEASEDATA)    := io.taps.c_releaseData
  events(IDX_C_PROBEACK)       := io.taps.c_probeAck
  events(IDX_C_PROBEACKDATA)   := io.taps.c_probeAckData
  events(IDX_D_GRANT)          := io.taps.d_grant
  events(IDX_D_GRANTDATA)      := io.taps.d_grantData
  events(IDX_DIR_HIT)          := io.taps.dir_hit
  events(IDX_DIR_MISS)         := io.taps.dir_miss
  events(IDX_EVICT_CLEAN)      := io.taps.evict_clean
  events(IDX_EVICT_DIRTY)      := io.taps.evict_dirty
  events(IDX_WRITE_TO_INVALID) := io.taps.write_to_invalid
  events(IDX_WRITE_TO_BRANCH)  := io.taps.write_to_branch
  events(IDX_WRITE_TO_TRUNK)   := io.taps.write_to_trunk
  events(IDX_WRITE_TO_TIP)     := io.taps.write_to_tip
  events(IDX_MSHR_ALLOC)       := io.taps.mshr_alloc
  events(IDX_MSHR_NO_FREE)     := io.taps.mshr_no_free
  events(IDX_SECONDARY_HIT)    := io.taps.secondary_hit

  private val ctrMax = ((BigInt(1) << CTR_WIDTH) - 1).U(CTR_WIDTH.W)
  private def satInc(v: UInt): UInt = Mux(v === ctrMax, v, v + 1.U)

  // -----------------------------------------------------------------------
  // History memory — one SyncReadMem per counter
  // -----------------------------------------------------------------------
  val depth     = params.micro.tlDirHistoryDepth
  val depthBits = log2Ceil(depth)

  val histMem = Seq.fill(N_COUNTERS)(SyncReadMem(depth, UInt(CTR_WIDTH.W)))

  val writePtr = RegInit(0.U(log2Ceil(depth + 1).W))
  val fullReg  = RegInit(false.B)

  // -----------------------------------------------------------------------
  // Snapshot timing — interval down-counter (mirrors SatCounter pattern)
  // -----------------------------------------------------------------------
  val cycleCount = RegInit(0.U(32.W))
  cycleCount := cycleCount + 1.U

  val intervalNonZero = io.control.interval =/= 0.U
  val intervalPrev    = RegNext(io.control.interval, 0.U)
  val intervalChanged = intervalPrev =/= io.control.interval

  val intervalCtr = RegInit(0.U(32.W))
  val ctrFires    = io.control.enable && intervalNonZero && (intervalCtr === 0.U)

  when (intervalChanged || !io.control.enable || !intervalNonZero) {
    intervalCtr := Mux(intervalNonZero, io.control.interval - 1.U, 0.U)
  } .elsewhen (ctrFires) {
    intervalCtr := io.control.interval - 1.U
  } .otherwise {
    intervalCtr := intervalCtr - 1.U
  }

  // Register snap pulse so BRAM write-enable / counter-reset path is FF→logic.
  val periodicSnapReg  = RegNext(ctrFires, false.B)
  val writePtrAtMaxReg = RegNext(writePtr === (depth - 1).U, false.B)
  val doSnap           = periodicSnapReg && !fullReg

  // -----------------------------------------------------------------------
  // Counter update: apply saturating-inc on the same cycle, but if a snap
  // is happening this cycle, commit the *current* value to BRAM and reset
  // the live counter to (event ? 1 : 0) so no events are lost during snap.
  // -----------------------------------------------------------------------
  for (i <- 0 until N_COUNTERS) {
    val nextOnEvent = satInc(counters(i))
    val ctrNext     = Mux(events(i), nextOnEvent, counters(i))
    when (doSnap) {
      // BRAM write captures pre-reset value (with this cycle's event folded in)
      histMem(i).write(writePtr(depthBits - 1, 0), ctrNext)
      // Live counter resets to 0 (event for this cycle was already snapshotted)
      counters(i) := 0.U
    } .elsewhen (events(i)) {
      counters(i) := nextOnEvent
    }
  }

  when (doSnap) {
    writePtr := writePtr + 1.U
    when (writePtrAtMaxReg) {
      fullReg := true.B
    }
  }

  when (periodicSnapReg) {
    printf(p"[TLDirMon] bank=${io.bankId} cycle=${cycleCount} snap=${writePtr}\n")
  }

  // -----------------------------------------------------------------------
  // Reset
  // -----------------------------------------------------------------------
  when (io.control.reset_ctr) {
    counters.foreach { c => c := 0.U }
    writePtr    := 0.U
    fullReg     := false.B
    cycleCount  := 0.U
    intervalCtr := Mux(intervalNonZero, io.control.interval - 1.U, 0.U)
  }

  // -----------------------------------------------------------------------
  // MMIO read interface — indexed read from history memory
  // -----------------------------------------------------------------------
  for (i <- 0 until N_COUNTERS) {
    io.control.histReads(i) := histMem(i).read(io.control.histIdx)
  }
  io.control.writeCount := writePtr
  io.control.full       := fullReg
}
