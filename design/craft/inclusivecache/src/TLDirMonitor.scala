/*
 * TLDirMonitor — phase-detection cache resource monitor.
 *
 * Implements the design described in sw/targeted_tests/docs/impl_4.md.
 *
 * Live state (per bank):
 *   csc[set][src]      : 3-bit composite saturation counter
 *                          A miss      → +1   (saturating at 7)
 *                          A hit       → -1   (clamped at 0)
 *                          B probe     → +2   (saturating at 7)
 *   invCnt[set]        : log2(WAYS+1)-bit count of ways currently in INVALID
 *   dirtyCnt[set]      : log2(WAYS+1)-bit count of ways currently in TIP|TRUNK
 *   wayState[set][way] : 2-bit shadow of directory state, used to compute
 *                        invCnt/dirtyCnt deltas on each directory write.
 *
 * Snapshot (every `interval` cycles):
 *   1. Compute activity bitmap A[set][src] = (csc[set][src] > THRESH).
 *   2. Compute setstate[set] = { dirtyBucket[1:0], invCapped[2:0] }
 *        invCapped     = min(invCnt, 7)
 *        dirtyBucket   = bucketize(dirtyCnt) into {0, 1-2, 3-5, 6+}
 *   3. Latch (1)+(2) into a wide shadow register (frozen for streaming).
 *   4. Halve every csc counter (csc >>= 1) — exponential decay.
 *   5. Stream the shadow word-by-word (64 bits/cycle) into the snapshot SRAM.
 *      Total streaming length = ceil(SETS*N_SRC/64) + ceil(SETS*5/64) cycles.
 *      Streaming finishes long before the next snap because interval >> N.
 *
 * Snapshot SRAM layout (single 64-bit-wide SyncReadMem):
 *   addr = snap_idx * NUM_WORDS + word_idx
 *   word_idx in [0 .. ACT_WORDS)             → activity bitmap
 *   word_idx in [ACT_WORDS .. NUM_WORDS)     → setstate array
 *
 * NOTE: This is a complete rewrite of the previous 29-event TLDirMonitor.
 *       The MMIO layout (Control.scala) and the C/Python tooling that read
 *       it have changed. The on-chip module name and `enableTLDirMonitor`
 *       parameter are preserved for integration continuity.
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._

object TLDirMonitor
{
  // -------- counter widths / encoding ----------
  val CSC_WIDTH         = 3
  val CSC_MAX           = (1 << CSC_WIDTH) - 1   // 7

  val INV_FIELD_WIDTH   = 3                       // 3 bits in snapshot word, capped at 7
  val DIRTY_FIELD_WIDTH = 2                       // 2-bit dirty bucket
  val SETSTATE_WIDTH    = INV_FIELD_WIDTH + DIRTY_FIELD_WIDTH  // 5

  // -------- snapshot RAM layout ----------------
  val WORD_WIDTH        = 64

  // -------- helpers parameterised by cache geometry ----------
  def numSrc (params: InclusiveCacheParameters): Int = math.max(1, params.clientBits)
  def numSets(params: InclusiveCacheParameters): Int = params.cache.sets
  def actBitsPerSnap  (params: InclusiveCacheParameters): Int = numSets(params) * numSrc(params)
  def ssBitsPerSnap   (params: InclusiveCacheParameters): Int = numSets(params) * SETSTATE_WIDTH
  def actWordsPerSnap (params: InclusiveCacheParameters): Int = (actBitsPerSnap(params) + WORD_WIDTH - 1) / WORD_WIDTH
  def ssWordsPerSnap  (params: InclusiveCacheParameters): Int = (ssBitsPerSnap (params) + WORD_WIDTH - 1) / WORD_WIDTH
  def numWordsPerSnap (params: InclusiveCacheParameters): Int = actWordsPerSnap(params) + ssWordsPerSnap(params)

  // dirty count → 2-bit bucket: {0}, {1,2}, {3-5}, {6+}
  def bucketDirty(cnt: UInt): UInt =
    Mux(cnt === 0.U,            0.U(DIRTY_FIELD_WIDTH.W),
    Mux(cnt <= 2.U,             1.U(DIRTY_FIELD_WIDTH.W),
    Mux(cnt <= 5.U,             2.U(DIRTY_FIELD_WIDTH.W),
                                3.U(DIRTY_FIELD_WIDTH.W))))
}

// ---------------------------------------------------------------------------
// Tap bundle wired up by InclusiveCacheBankScheduler.
// All valids are single-cycle pulses already pipeline-aligned with the
// directory result (for the A path) or with the channel fire (for B / dir
// writes). The monitor itself adds no further pipelining.
// ---------------------------------------------------------------------------
class TLDirMonitorTaps(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  private val nSrc = TLDirMonitor.numSrc(params)

  // A-channel directory result (only A primary lookups; pipelined to align
  // with directory.io.result.valid). Exactly one of a_hit_valid/a_miss_valid
  // is asserted on a given cycle, never both.
  val a_hit_valid  = Bool()
  val a_miss_valid = Bool()
  val a_set        = UInt(params.setBits.W)
  val a_srcOH      = UInt(nSrc.W)

  // Inner-B probe fire (master being probed identified by b_srcOH).
  val b_probe_valid = Bool()
  val b_set         = UInt(params.setBits.W)
  val b_srcOH       = UInt(nSrc.W)

  // Directory write commit (set + way + new state). Old state is shadowed
  // inside the monitor — no need to expose it on this bundle.
  val dw_fire   = Bool()
  val dw_set    = UInt(params.setBits.W)
  val dw_way    = UInt(params.wayBits.W)
  val dw_state  = UInt(params.stateBits.W)
}

// ---------------------------------------------------------------------------
// MMIO control / status interface (drives Control.scala regmap)
// ---------------------------------------------------------------------------
class TLDirMonitorCtrlIO(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  private val depth   = params.micro.tlDirHistoryDepth
  private val nWords  = TLDirMonitor.numWordsPerSnap(params)

  // SW → HW
  val enable    = Input(Bool())
  val reset_ctr = Input(Bool())
  val interval  = Input(UInt(32.W))
  val threshold = Input(UInt(TLDirMonitor.CSC_WIDTH.W))
  val snapIdx   = Input(UInt(log2Ceil(depth).W))
  val wordIdx   = Input(UInt(log2Ceil(nWords).W))

  // HW → SW
  val readData   = Output(UInt(TLDirMonitor.WORD_WIDTH.W))
  val writeCount = Output(UInt(log2Ceil(depth + 1).W))
  val full       = Output(Bool())
  val streaming  = Output(Bool())

  // Geometry (constant): handy for the SW driver to discover layout.
  val nSrc        = Output(UInt(8.W))
  val actWords    = Output(UInt(16.W))
  val numWords    = Output(UInt(16.W))
  val nSetsLg2    = Output(UInt(8.W))
}

// ---------------------------------------------------------------------------
// Core module
// ---------------------------------------------------------------------------
class InclusiveCacheTLDirMonitor(params: InclusiveCacheParameters) extends Module
{
  import TLDirMonitor._

  val SETS      = numSets(params)
  val N_SRC     = numSrc(params)
  val WAYS      = params.cache.ways
  val WAY_W     = log2Ceil(WAYS)
  val DEPTH     = params.micro.tlDirHistoryDepth
  val ACT_BITS  = actBitsPerSnap(params)
  val ACT_WORDS = actWordsPerSnap(params)
  val SS_BITS   = ssBitsPerSnap (params)
  val SS_WORDS  = ssWordsPerSnap(params)
  val NUM_WORDS = numWordsPerSnap(params)
  val CNT_W     = log2Ceil(WAYS + 1)              // exact way-count width

  println(s"InclusiveCache TL+Directory Phase Monitor:")
  println(s"  Sets / Sources / Ways : $SETS / $N_SRC / $WAYS")
  println(s"  Activity bits / words : $ACT_BITS bits  ($ACT_WORDS x 64-bit words)")
  println(s"  Setstate bits / words : $SS_BITS bits  ($SS_WORDS x 64-bit words)")
  println(s"  History depth         : $DEPTH snapshots")
  println(s"  Snapshot RAM size     : ${(NUM_WORDS.toLong * DEPTH * WORD_WIDTH) / 8} bytes")

  val io = IO(new Bundle {
    val taps    = Flipped(new TLDirMonitorTaps(params))
    val control = new TLDirMonitorCtrlIO(params)
    val bankId  = Input(UInt(8.W))
  })

  // -----------------------------------------------------------------------
  // Live state
  // -----------------------------------------------------------------------
  val csc      = RegInit(VecInit(Seq.fill(SETS)(VecInit(Seq.fill(N_SRC)(0.U(CSC_WIDTH.W))))))
  val invCnt   = RegInit(VecInit(Seq.fill(SETS)(0.U(CNT_W.W))))
  val dirtyCnt = RegInit(VecInit(Seq.fill(SETS)(0.U(CNT_W.W))))
  val wayState = RegInit(VecInit(Seq.fill(SETS)(VecInit(Seq.fill(WAYS)(0.U(params.stateBits.W))))))

  // -----------------------------------------------------------------------
  // Snapshot timing — interval down-counter
  // -----------------------------------------------------------------------
  val cycleCount   = RegInit(0.U(64.W))
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

  // Streaming FSM: snapPulse triggers an N-cycle write burst into snapMem.
  // We allow a snap to fire only when not currently streaming and history
  // is not yet full.
  val writePtr     = RegInit(0.U(log2Ceil(DEPTH + 1).W))
  val fullReg      = RegInit(false.B)

  val streamCtr    = RegInit(0.U(log2Ceil(NUM_WORDS + 1).W))   // counts down from NUM_WORDS to 0
  val streaming    = streamCtr =/= 0.U
  val canSnap      = !streaming && !fullReg
  val snapPulseReg = RegNext(ctrFires && canSnap, false.B)
  val snapPulse    = snapPulseReg                             // 1-cycle, registered

  // -----------------------------------------------------------------------
  // Live-state updates: csc, wayState, invCnt, dirtyCnt
  // -----------------------------------------------------------------------

  // ---- csc[set][src] ----
  // For every (s, k), determine the events that touch this slot this cycle
  // and apply +probe(2), +miss(1), -hit(1) with saturation. A snap halves it.
  for (s <- 0 until SETS; k <- 0 until N_SRC) {
    val cur        = csc(s)(k)
    val a_match    = (io.taps.a_set === s.U) && io.taps.a_srcOH(k)
    val b_match    = (io.taps.b_set === s.U) && io.taps.b_srcOH(k)
    val miss_here  = io.taps.a_miss_valid && a_match
    val hit_here   = io.taps.a_hit_valid  && a_match
    val probe_here = io.taps.b_probe_valid && b_match

    val incAmt = (Mux(miss_here, 1.U, 0.U) +& Mux(probe_here, 2.U, 0.U))(2,0)  // 0..3
    val decAmt = Mux(hit_here, 1.U(1.W), 0.U(1.W))

    val sumWide = cur +& incAmt
    val incd    = Mux(sumWide > CSC_MAX.U, CSC_MAX.U(CSC_WIDTH.W), sumWide(CSC_WIDTH-1, 0))
    val nxtEv   = Mux(decAmt > incd, 0.U(CSC_WIDTH.W), incd - decAmt)

    when (snapPulse) {
      csc(s)(k) := cur >> 1                         // decay; events on snap cycle dropped
    } .otherwise {
      csc(s)(k) := nxtEv
    }
  }

  // ---- wayState[set][way] + invCnt[set] + dirtyCnt[set] ----
  // Maintain incremental counts based on the (old → new) state transition
  // observed at every directory write.
  val dwSet   = io.taps.dw_set
  val dwWay   = io.taps.dw_way
  val dwNew   = io.taps.dw_state
  val dwOld   = wayState(dwSet)(dwWay)

  val INVALID = 0.U(params.stateBits.W)
  val BRANCH  = 1.U(params.stateBits.W)
  val TRUNK   = 2.U(params.stateBits.W)
  val TIP     = 3.U(params.stateBits.W)
  def isInv  (s: UInt): Bool = s === INVALID
  def isDirty(s: UInt): Bool = (s === TIP) || (s === TRUNK)

  val invDelta_pos   = !isInv(dwOld) &&  isInv(dwNew)
  val invDelta_neg   =  isInv(dwOld) && !isInv(dwNew)
  val dirtyDelta_pos = !isDirty(dwOld) &&  isDirty(dwNew)
  val dirtyDelta_neg =  isDirty(dwOld) && !isDirty(dwNew)

  val invCntCur   = invCnt(dwSet)
  val dirtyCntCur = dirtyCnt(dwSet)

  val invCntNxt = MuxCase(invCntCur, Seq(
    invDelta_pos -> Mux(invCntCur === WAYS.U, invCntCur, invCntCur + 1.U),
    invDelta_neg -> Mux(invCntCur === 0.U,    invCntCur, invCntCur - 1.U)
  ))
  val dirtyCntNxt = MuxCase(dirtyCntCur, Seq(
    dirtyDelta_pos -> Mux(dirtyCntCur === WAYS.U, dirtyCntCur, dirtyCntCur + 1.U),
    dirtyDelta_neg -> Mux(dirtyCntCur === 0.U,    dirtyCntCur, dirtyCntCur - 1.U)
  ))

  when (io.taps.dw_fire) {
    wayState(dwSet)(dwWay) := dwNew
    invCnt(dwSet)          := invCntNxt
    dirtyCnt(dwSet)        := dirtyCntNxt
  }

  // -----------------------------------------------------------------------
  // Snapshot capture: pack live state into a wide shadow register
  // -----------------------------------------------------------------------

  // Activity bitmap — bit_index = set * N_SRC + src, LSB-first.
  val activityBits = Wire(Vec(SETS * N_SRC, Bool()))
  for (s <- 0 until SETS; k <- 0 until N_SRC) {
    activityBits(s * N_SRC + k) := csc(s)(k) > io.control.threshold
  }
  val activityWide  = activityBits.asUInt   // SETS*N_SRC bits, LSB = (set=0, src=0)
  val actPad        = ACT_WORDS * WORD_WIDTH - ACT_BITS
  val activityFlat  = if (actPad == 0) activityWide else Cat(0.U(actPad.W), activityWide)

  // Setstate — entry s = { dirtyBucket(2), invCapped(3) }
  val ssEntries = Wire(Vec(SETS, UInt(SETSTATE_WIDTH.W)))
  for (s <- 0 until SETS) {
    val invCapped = Mux(invCnt(s) > 7.U, 7.U(INV_FIELD_WIDTH.W), invCnt(s)(INV_FIELD_WIDTH-1, 0))
    val dirtyB    = bucketDirty(dirtyCnt(s))
    ssEntries(s) := Cat(dirtyB, invCapped)
  }
  val ssWide = ssEntries.asUInt
  val ssPad  = SS_WORDS * WORD_WIDTH - SS_BITS
  val ssFlat = if (ssPad == 0) ssWide else Cat(0.U(ssPad.W), ssWide)

  val actShadow = Reg(Vec(ACT_WORDS, UInt(WORD_WIDTH.W)))
  val ssShadow  = Reg(Vec(SS_WORDS,  UInt(WORD_WIDTH.W)))

  when (snapPulse) {
    for (w <- 0 until ACT_WORDS) {
      actShadow(w) := activityFlat(w * WORD_WIDTH + WORD_WIDTH - 1, w * WORD_WIDTH)
    }
    for (w <- 0 until SS_WORDS) {
      ssShadow(w)  := ssFlat(w * WORD_WIDTH + WORD_WIDTH - 1, w * WORD_WIDTH)
    }
    streamCtr := NUM_WORDS.U
  } .elsewhen (streaming) {
    streamCtr := streamCtr - 1.U
  }

  // -----------------------------------------------------------------------
  // Snapshot RAM — single 64-bit-wide SyncReadMem, flat (snap, word) addr.
  // -----------------------------------------------------------------------
  val snapMem  = SyncReadMem(DEPTH * NUM_WORDS, UInt(WORD_WIDTH.W))

  // Write pointer & current sub-word index within the in-flight snapshot.
  // streamCtr counts NUM_WORDS → 1 over the streaming cycles, so the linear
  // word index is (NUM_WORDS - streamCtr).
  val streamWordIdx = Mux(streaming, (NUM_WORDS.U - streamCtr), 0.U)
  val writeAddr     = writePtr * NUM_WORDS.U + streamWordIdx

  // Word value to write: actShadow for the first ACT_WORDS positions, then
  // ssShadow for the rest.
  val isActWord = streamWordIdx < ACT_WORDS.U
  val actSel    = if (ACT_WORDS == 1) actShadow(0) else actShadow(streamWordIdx(log2Ceil(ACT_WORDS)-1, 0))
  val ssSel     = if (SS_WORDS  == 1) ssShadow(0)  else ssShadow ((streamWordIdx - ACT_WORDS.U)(log2Ceil(SS_WORDS)-1, 0))
  val writeData = Mux(isActWord, actSel, ssSel)

  when (streaming) {
    snapMem.write(writeAddr, writeData)
  }

  // When the streaming completes for this snapshot, advance writePtr.
  val streamDoneNext = streaming && streamCtr === 1.U
  when (streamDoneNext) {
    writePtr := writePtr + 1.U
    when (writePtr === (DEPTH - 1).U) {
      fullReg := true.B
    }
  }

  // -----------------------------------------------------------------------
  // Indexed read port (1-cycle SyncReadMem read latency)
  // -----------------------------------------------------------------------
  val readAddr = io.control.snapIdx * NUM_WORDS.U + io.control.wordIdx
  io.control.readData   := snapMem.read(readAddr)
  io.control.writeCount := writePtr
  io.control.full       := fullReg
  io.control.streaming  := streaming

  io.control.nSrc       := N_SRC.U
  io.control.actWords   := ACT_WORDS.U
  io.control.numWords   := NUM_WORDS.U
  io.control.nSetsLg2   := log2Ceil(SETS).U

  // -----------------------------------------------------------------------
  // Software-controlled reset: clears live counters and history index, but
  // intentionally does NOT clear wayState — it is a shadow of physical
  // directory contents and must remain in sync.
  // -----------------------------------------------------------------------
  when (io.control.reset_ctr) {
    for (s <- 0 until SETS; k <- 0 until N_SRC) {
      csc(s)(k) := 0.U
    }
    for (s <- 0 until SETS) {
      invCnt(s)   := 0.U
      dirtyCnt(s) := 0.U
    }
    writePtr    := 0.U
    fullReg     := false.B
    streamCtr   := 0.U
    cycleCount  := 0.U
    intervalCtr := Mux(intervalNonZero, io.control.interval - 1.U, 0.U)
  }

  // -----------------------------------------------------------------------
  // Diagnostic printf — visible in simulation; elided by SiliconCompilers.
  // -----------------------------------------------------------------------
  when (snapPulse) {
    printf(p"[TLDirMon] bank=${io.bankId} cycle=${cycleCount} snap=${writePtr}\n")
  }
}
