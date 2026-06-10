/*
 * TLDirMonitor — phase-detection cache resource monitor.
 *
 * Live state (per bank):
 *   csc[set][src]      : CSC_WIDTH-bit composite saturation counter
 *                          CSC_WIDTH = log2Ceil(2 * ways)
 *                          Miss      → +1 (or Morris-gated when useMorris)
 *                          Hit       → -1 (always linear)
 *                          NOTE: probes no longer fold into csc.
 *   probeCsc[set][src] : CSC_WIDTH-bit probe-only saturation counter
 *                          B-probe   → +1 (or Morris-gated when useMorris)
 *   invCnt[set]        : log2(WAYS+1)-bit count of ways currently in INVALID
 *   dirtyCnt[set]      : log2(WAYS+1)-bit count of ways currently in TIP|TRUNK
 *   wayState[set][way] : 2-bit shadow of directory state.
 *
 * Decay (independent of snapshots):
 *   Every `decayPeriod` cycles a bank-wide `decayPulse` fires; on that cycle
 *   csc(s)(k)      := csc(s)(k)      >> decayShift
 *   probeCsc(s)(k) := probeCsc(s)(k) >> decayShift
 *   decayPeriod == 0 → decay disabled.
 *   Snapshots themselves DO NOT decay the counters.
 *
 * Snapshot (every `interval` cycles):
 *   1. Compute activity bucket A[set][src] (2 bits each) from csc and the
 *      two activity thresholds {threshLo, threshHi}:
 *        0 (idle) if csc == 0
 *        1 (cold) if csc <  threshLo
 *        2 (warm) if csc <  threshHi
 *        3 (hot)  otherwise
 *   2. Compute probe bucket P[set][src] (2 bits each) from probeCsc and the
 *      two probe thresholds {probeThreshLo, probeThreshHi} using the same
 *      quantization scheme.
 *   3. Compute setstate[set] = { dirtyBucket[1:0], invCapped[2:0] } (5 bits)
 *        invCapped     = min(invCnt, 7)
 *        dirtyBucket   = bucketize(dirtyCnt) into {0, 1-2, 3-5, 6+}
 *   4. Latch (1)+(2)+(3) into wide shadow registers (frozen for streaming).
 *   5. Stream the shadow word-by-word (64 bits/cycle) into the snapshot SRAM.
 *
 * Snapshot SRAM layout (single 64-bit-wide SyncReadMem):
 *   addr = snap_idx * NUM_WORDS + word_idx
 *   word_idx in [0                  .. ACT_WORDS)                 → activity
 *   word_idx in [ACT_WORDS          .. ACT_WORDS + SS_WORDS)       → setstate
 *   word_idx in [ACT_WORDS+SS_WORDS .. NUM_WORDS)                  → probe
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR

object TLDirMonitor
{
  // -------- snapshot-output field widths -------------------------------
  val ACT_FIELD_WIDTH    = 2                       // 2-bit activity bucket per (set, src)
  val PROBE_FIELD_WIDTH  = 2                       // 2-bit probe bucket per (set, src)

  val INV_FIELD_WIDTH    = 3                       // 3 bits in snapshot word, capped at 7
  val DIRTY_FIELD_WIDTH  = 2                       // 2-bit dirty bucket
  val SETSTATE_WIDTH     = INV_FIELD_WIDTH + DIRTY_FIELD_WIDTH  // 5

  // -------- snapshot RAM layout ----------------------------------------
  val WORD_WIDTH         = 64

  // -------- Morris LFSR ------------------------------------------------
  val LFSR_WIDTH         = 32

  // -------- counter widths parameterised by cache geometry -------------
  // CSC width = log2Ceil(2 * ways), giving range [0, 2K-1].
  def cscWidth(params: InclusiveCacheParameters): Int =
    math.max(1, log2Ceil(2 * params.cache.ways))
  def cscMax  (params: InclusiveCacheParameters): Int = (1 << cscWidth(params)) - 1

  // -------- helpers parameterised by cache geometry --------------------
  def numSrc (params: InclusiveCacheParameters): Int = math.max(1, params.clientBits)
  def numSets(params: InclusiveCacheParameters): Int = params.cache.sets

  def actBitsPerSnap   (params: InclusiveCacheParameters): Int = numSets(params) * numSrc(params) * ACT_FIELD_WIDTH
  def ssBitsPerSnap    (params: InclusiveCacheParameters): Int = numSets(params) * SETSTATE_WIDTH
  def probeBitsPerSnap (params: InclusiveCacheParameters): Int = numSets(params) * numSrc(params) * PROBE_FIELD_WIDTH

  def actWordsPerSnap  (params: InclusiveCacheParameters): Int = (actBitsPerSnap  (params) + WORD_WIDTH - 1) / WORD_WIDTH
  def ssWordsPerSnap   (params: InclusiveCacheParameters): Int = (ssBitsPerSnap   (params) + WORD_WIDTH - 1) / WORD_WIDTH
  def probeWordsPerSnap(params: InclusiveCacheParameters): Int = (probeBitsPerSnap(params) + WORD_WIDTH - 1) / WORD_WIDTH

  def numWordsPerSnap  (params: InclusiveCacheParameters): Int =
    actWordsPerSnap(params) + ssWordsPerSnap(params) + probeWordsPerSnap(params)

  // dirty count → 2-bit bucket: {0}, {1,2}, {3-5}, {6+}
  def bucketDirty(cnt: UInt): UInt =
    Mux(cnt === 0.U,            0.U(DIRTY_FIELD_WIDTH.W),
    Mux(cnt <= 2.U,             1.U(DIRTY_FIELD_WIDTH.W),
    Mux(cnt <= 5.U,             2.U(DIRTY_FIELD_WIDTH.W),
                                3.U(DIRTY_FIELD_WIDTH.W))))

  // {idle, cold, warm, hot} quantization shared by activity & probe outputs.
  def bucketCsc(cur: UInt, threshLo: UInt, threshHi: UInt): UInt =
    Mux(cur === 0.U,        0.U(ACT_FIELD_WIDTH.W),
    Mux(cur < threshLo,     1.U(ACT_FIELD_WIDTH.W),
    Mux(cur < threshHi,     2.U(ACT_FIELD_WIDTH.W),
                            3.U(ACT_FIELD_WIDTH.W))))
}

// ---------------------------------------------------------------------------
// Tap bundle wired up by InclusiveCacheBankScheduler.
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
  private val cscW    = TLDirMonitor.cscWidth(params)

  // SW → HW
  val enable         = Input(Bool())
  val reset_ctr      = Input(Bool())
  val interval       = Input(UInt(32.W))
  val useMorris      = Input(Bool())
  val decayPeriod    = Input(UInt(32.W))
  val decayShift     = Input(UInt(3.W))
  val threshLo       = Input(UInt(cscW.W))
  val threshHi       = Input(UInt(cscW.W))
  val probeThreshLo  = Input(UInt(cscW.W))
  val probeThreshHi  = Input(UInt(cscW.W))
  val snapIdx        = Input(UInt(log2Ceil(depth).W))
  val wordIdx        = Input(UInt(log2Ceil(nWords).W))

  // HW → SW
  val readData    = Output(UInt(TLDirMonitor.WORD_WIDTH.W))
  val writeCount  = Output(UInt(log2Ceil(depth + 1).W))
  val full        = Output(Bool())
  val streaming   = Output(Bool())
  val missedSnaps = Output(UInt(32.W))  // snapshots dropped due to streaming overlap

  // Geometry (constant): handy for the SW driver to discover layout.
  val nSrc        = Output(UInt(8.W))
  val cscWidthOut = Output(UInt(8.W))
  val actWords    = Output(UInt(16.W))
  val ssWords     = Output(UInt(16.W))
  val probeWords  = Output(UInt(16.W))
  val numWords    = Output(UInt(16.W))
  val nSetsLg2    = Output(UInt(8.W))
}

// ---------------------------------------------------------------------------
// Core module
// ---------------------------------------------------------------------------
class InclusiveCacheTLDirMonitor(params: InclusiveCacheParameters) extends Module
{
  import TLDirMonitor._

  val SETS        = numSets(params)
  val N_SRC       = numSrc(params)
  val WAYS        = params.cache.ways
  val WAY_W       = log2Ceil(WAYS)
  val DEPTH       = params.micro.tlDirHistoryDepth
  val CSC_W       = cscWidth(params)
  val CSC_MAX_V   = cscMax  (params)
  val ACT_BITS    = actBitsPerSnap   (params)
  val ACT_WORDS   = actWordsPerSnap  (params)
  val SS_BITS     = ssBitsPerSnap    (params)
  val SS_WORDS    = ssWordsPerSnap   (params)
  val PROBE_BITS  = probeBitsPerSnap (params)
  val PROBE_WORDS = probeWordsPerSnap(params)
  val NUM_WORDS   = numWordsPerSnap  (params)
  val CNT_W       = log2Ceil(WAYS + 1)

  println(s"InclusiveCache TL+Directory Phase Monitor:")
  println(s"  Sets / Sources / Ways : $SETS / $N_SRC / $WAYS")
  println(s"  CSC width             : $CSC_W bits  (max $CSC_MAX_V = 2K-1)")
  println(s"  Activity bits / words : $ACT_BITS bits  ($ACT_WORDS x 64-bit words)")
  println(s"  Setstate bits / words : $SS_BITS bits  ($SS_WORDS x 64-bit words)")
  println(s"  Probe bits / words    : $PROBE_BITS bits  ($PROBE_WORDS x 64-bit words)")
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
  val csc      = RegInit(VecInit(Seq.fill(SETS)(VecInit(Seq.fill(N_SRC)(0.U(CSC_W.W))))))
  val probeCsc = RegInit(VecInit(Seq.fill(SETS)(VecInit(Seq.fill(N_SRC)(0.U(CSC_W.W))))))
  val invCnt   = RegInit(VecInit(Seq.fill(SETS)(WAYS.U(CNT_W.W))))  // all ways start INVALID
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
  val writePtr     = RegInit(0.U(log2Ceil(DEPTH + 1).W))
  val fullReg      = RegInit(false.B)

  val streamCtr      = RegInit(0.U(log2Ceil(NUM_WORDS + 1).W))  // counts down from NUM_WORDS to 0
  val streaming      = streamCtr =/= 0.U
  // streamDoneNext is hoisted here so canSnap can use it (allows back-to-back snaps).
  val streamDoneNext = streaming && (streamCtr === 1.U)
  // A snap is allowed on the last streaming cycle because the write finishes this tick.
  val canSnap        = (!streaming || streamDoneNext) && !fullReg

  // Count snapshot fires that were suppressed by an in-progress stream.
  val missedSnaps = RegInit(0.U(32.W))
  when (ctrFires && streaming && !streamDoneNext && !fullReg) {
    missedSnaps := Mux(missedSnaps.andR, missedSnaps, missedSnaps + 1.U)
  }

  val snapPulseReg = RegNext(ctrFires && canSnap, false.B)
  val snapPulse    = snapPulseReg                             // 1-cycle, registered

  // -----------------------------------------------------------------------
  // Continuous leaky-decay timer — independent of snapshots.
  // -----------------------------------------------------------------------
  val decayNonZero  = io.control.decayPeriod =/= 0.U
  val decayPrev     = RegNext(io.control.decayPeriod, 0.U)
  val decayChanged  = decayPrev =/= io.control.decayPeriod

  val decayCtr      = RegInit(0.U(32.W))
  val decayFires    = io.control.enable && decayNonZero && (decayCtr === 0.U)

  when (decayChanged || !io.control.enable || !decayNonZero) {
    decayCtr := Mux(decayNonZero, io.control.decayPeriod - 1.U, 0.U)
  } .elsewhen (decayFires) {
    decayCtr := io.control.decayPeriod - 1.U
  } .otherwise {
    decayCtr := decayCtr - 1.U
  }
  val decayPulse = decayFires

  // -----------------------------------------------------------------------
  // Morris LFSR (one per bank) — supplies probabilistic-gate randomness.
  //   Pr[gate(cur)] = 2^(-cur), with cur saturating beyond the LFSR width.
  //   gate is true when the low `cur` bits of the LFSR are all zero.
  // -----------------------------------------------------------------------
  val lfsr = LFSR(LFSR_WIDTH, true.B)

  def morrisGate(cur: UInt): Bool = {
    val maxBits  = LFSR_WIDTH
    val curClamp = Mux(cur > maxBits.U, maxBits.U(log2Ceil(maxBits + 1).W), cur)
    val mask     = ((1.U((maxBits + 1).W) << curClamp) - 1.U)(maxBits - 1, 0)
    (lfsr & mask) === 0.U
  }

  // -----------------------------------------------------------------------
  // CSC / probeCsc live-state updates
  // Decay is applied first to obtain cscBase/probeBase; events are then
  // added on top.  The Morris gate uses the pre-decay value so that gate
  // probability reflects the true counter magnitude before the shift.
  // -----------------------------------------------------------------------
  for (s <- 0 until SETS; k <- 0 until N_SRC) {
    val cur        = csc(s)(k)
    val curProbe   = probeCsc(s)(k)
    val a_match    = (io.taps.a_set === s.U) && io.taps.a_srcOH(k)
    val b_match    = (io.taps.b_set === s.U) && io.taps.b_srcOH(k)
    val miss_here  = io.taps.a_miss_valid && a_match
    val hit_here   = io.taps.a_hit_valid  && a_match
    val probe_here = io.taps.b_probe_valid && b_match

    // Apply decay first; events are layered on the decayed base.
    val cscBase   = Mux(decayPulse, cur      >> io.control.decayShift, cur)
    val probeBase = Mux(decayPulse, curProbe >> io.control.decayShift, curProbe)

    // ---- CSC: miss (+1), hit (-1). Morris gate uses pre-decay cur. ----
    // Damith edit : ---- CSC: miss (+2), hitwithout probes (-2), hit with Probe (+1)). 
    val cscMissInc = Mux(io.control.useMorris, morrisGate(cur), true.B) && miss_here
    val cscIncAmt  = Mux(cscMissInc, 2.U(2.W), 0.U(2.W))
    val cscDecAmt  = Mux(hit_here && !probe_here,   2.U(2.W), Mux(hit_here && probe_here, 1.U(2.W), 0.U(2.W)))
    val cscSumWide = cscBase +& cscIncAmt
    val cscIncd    = Mux(cscSumWide > CSC_MAX_V.U, CSC_MAX_V.U(CSC_W.W), cscSumWide(CSC_W - 1, 0))
    val cscNxt     = Mux(cscDecAmt > cscIncd, 0.U(CSC_W.W), cscIncd - cscDecAmt)

    // ---- probeCsc: +1 per probe, no decrement. Morris gate uses pre-decay curProbe. ----
    val pIncEnable = Mux(io.control.useMorris, morrisGate(curProbe), true.B) && probe_here
    val pSumWide   = probeBase +& Mux(pIncEnable, 1.U(1.W), 0.U(1.W))
    val pNxt       = Mux(pSumWide > CSC_MAX_V.U, CSC_MAX_V.U(CSC_W.W), pSumWide(CSC_W - 1, 0))

    csc(s)(k)      := cscNxt
    probeCsc(s)(k) := pNxt

    // Print the saturation counters each cycle, showing the effect of decayPulse
    printf(p"[TLDirMon] set=${s} src=${k} decay=${decayPulse} csc: ${cur}->${cscBase}->${cscNxt} probe: ${curProbe}->${probeBase}->${pNxt}\n")
  }

  // -----------------------------------------------------------------------
  // wayState[set][way] + invCnt[set] + dirtyCnt[set]
  // -----------------------------------------------------------------------
  val dwSet   = io.taps.dw_set
  val dwWay   = io.taps.dw_way
  val dwNew   = io.taps.dw_state
  val dwOld   = wayState(dwSet)(dwWay)

  val INVALID = 0.U(params.stateBits.W)
  val BRANCH  = 1.U(params.stateBits.W)
  val TRUNK   = 2.U(params.stateBits.W)
  val TIP     = 3.U(params.stateBits.W)
  def isInv  (st: UInt): Bool = st === INVALID
  def isDirty(st: UInt): Bool = (st === TIP) || (st === TRUNK)

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
  // Snapshot capture: pack live state into wide shadow registers
  // -----------------------------------------------------------------------

  // Activity buckets — 2 bits per (set, src), LSB-first index = (set*N_SRC + src).
  val activityFields = Wire(Vec(SETS * N_SRC, UInt(ACT_FIELD_WIDTH.W)))
  for (s <- 0 until SETS; k <- 0 until N_SRC) {
    activityFields(s * N_SRC + k) := bucketCsc(csc(s)(k), io.control.threshLo, io.control.threshHi)
  }
  val activityWide  = activityFields.asUInt
  val actPad        = ACT_WORDS * WORD_WIDTH - ACT_BITS
  val activityFlat  = if (actPad == 0) activityWide else Cat(0.U(actPad.W), activityWide)

  // Probe buckets — 2 bits per (set, src), same shape as activity.
  val probeFields = Wire(Vec(SETS * N_SRC, UInt(PROBE_FIELD_WIDTH.W)))
  for (s <- 0 until SETS; k <- 0 until N_SRC) {
    probeFields(s * N_SRC + k) := bucketCsc(probeCsc(s)(k), io.control.probeThreshLo, io.control.probeThreshHi)
  }
  val probeWide = probeFields.asUInt
  val pPad      = PROBE_WORDS * WORD_WIDTH - PROBE_BITS
  val probeFlat = if (pPad == 0) probeWide else Cat(0.U(pPad.W), probeWide)

  // Setstate — entry s = { dirtyBucket(2), invCapped(3) }
  val ssEntries = Wire(Vec(SETS, UInt(SETSTATE_WIDTH.W)))
  for (s <- 0 until SETS) {
    val invCapped = Mux(invCnt(s) > 7.U, 7.U(INV_FIELD_WIDTH.W), invCnt(s)(INV_FIELD_WIDTH - 1, 0))
    val dirtyB    = bucketDirty(dirtyCnt(s))
    ssEntries(s) := Cat(dirtyB, invCapped)
  }
  val ssWide = ssEntries.asUInt
  val ssPad  = SS_WORDS * WORD_WIDTH - SS_BITS
  val ssFlat = if (ssPad == 0) ssWide else Cat(0.U(ssPad.W), ssWide)

  val actShadow   = Reg(Vec(ACT_WORDS,   UInt(WORD_WIDTH.W)))
  val ssShadow    = Reg(Vec(SS_WORDS,    UInt(WORD_WIDTH.W)))
  val probeShadow = Reg(Vec(PROBE_WORDS, UInt(WORD_WIDTH.W)))

  when (snapPulse) {
    for (w <- 0 until ACT_WORDS) {
      actShadow(w) := activityFlat(w * WORD_WIDTH + WORD_WIDTH - 1, w * WORD_WIDTH)
    }
    for (w <- 0 until SS_WORDS) {
      ssShadow(w)  := ssFlat(w * WORD_WIDTH + WORD_WIDTH - 1, w * WORD_WIDTH)
    }
    for (w <- 0 until PROBE_WORDS) {
      probeShadow(w) := probeFlat(w * WORD_WIDTH + WORD_WIDTH - 1, w * WORD_WIDTH)
    }
    streamCtr := NUM_WORDS.U
  } .elsewhen (streaming) {
    streamCtr := streamCtr - 1.U
  }

  // -----------------------------------------------------------------------
  // Snapshot RAM — single 64-bit-wide SyncReadMem, flat (snap, word) addr.
  // -----------------------------------------------------------------------
  val snapMem  = SyncReadMem(DEPTH * NUM_WORDS, UInt(WORD_WIDTH.W))

  //assert if syncMem width is greater than 64b, since the packing logic below won't work correctly (it assumes each word is 64 bits, and pads the last word of each region if necessary).
  assert(WORD_WIDTH <= 64, "TLDirMonitor snapshot RAM word width must be <= 64 bits")

  val streamWordIdx = Mux(streaming, (NUM_WORDS.U - streamCtr), 0.U)
  val writeAddr     = writePtr * NUM_WORDS.U + streamWordIdx

  // Region selection (act → ss → probe).
  val ACT_END   = ACT_WORDS
  val SS_END    = ACT_WORDS + SS_WORDS
  val isActWord   = streamWordIdx < ACT_END.U
  val isSsWord    = (streamWordIdx >= ACT_END.U) && (streamWordIdx < SS_END.U)
  val isProbeWord = streamWordIdx >= SS_END.U

  val actIdxRaw   = streamWordIdx
  val ssIdxRaw    = streamWordIdx - ACT_END.U
  val probeIdxRaw = streamWordIdx - SS_END.U

  val actSel   = if (ACT_WORDS   == 1) actShadow  (0) else actShadow  (actIdxRaw  (log2Ceil(ACT_WORDS  ) - 1, 0))
  val ssSel    = if (SS_WORDS    == 1) ssShadow   (0) else ssShadow   (ssIdxRaw   (log2Ceil(SS_WORDS   ) - 1, 0))
  val probeSel = if (PROBE_WORDS == 1) probeShadow(0) else probeShadow(probeIdxRaw(log2Ceil(PROBE_WORDS) - 1, 0))

  val writeData = Mux(isActWord, actSel, Mux(isSsWord, ssSel, probeSel))

  when (streaming) {
    snapMem.write(writeAddr, writeData)
  }

  // streamDoneNext is defined earlier (before canSnap) — used here for the write-pointer update.
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
  io.control.full        := fullReg
  io.control.streaming   := streaming
  io.control.missedSnaps := missedSnaps

  io.control.nSrc        := N_SRC.U
  io.control.cscWidthOut := CSC_W.U
  io.control.actWords    := ACT_WORDS.U
  io.control.ssWords     := SS_WORDS.U
  io.control.probeWords  := PROBE_WORDS.U
  io.control.numWords    := NUM_WORDS.U
  io.control.nSetsLg2    := log2Ceil(SETS).U

  // -----------------------------------------------------------------------
  // Software-controlled reset: clears live counters and history index, but
  // intentionally does NOT clear wayState — it is a shadow of physical
  // directory contents and must remain in sync.
  // -----------------------------------------------------------------------
  when (io.control.reset_ctr) {
    for (s <- 0 until SETS; k <- 0 until N_SRC) {
      csc(s)(k)      := 0.U
      probeCsc(s)(k) := 0.U
    }
    // Rebuild invCnt/dirtyCnt from wayState so they stay in sync with the
    // physical directory shadow (wayState is intentionally NOT cleared).
    for (s <- 0 until SETS) {
      invCnt(s)   := PopCount(wayState(s).map(w => isInv(w)))
      dirtyCnt(s) := PopCount(wayState(s).map(w => isDirty(w)))
    }
    writePtr    := 0.U
    fullReg     := false.B
    streamCtr   := 0.U
    missedSnaps := 0.U
    cycleCount  := 0.U
    intervalCtr := Mux(intervalNonZero, io.control.interval - 1.U, 0.U)
    decayCtr    := Mux(decayNonZero,    io.control.decayPeriod - 1.U, 0.U)
  }

  // -----------------------------------------------------------------------
  // Diagnostic printf — visible in simulation; elided by SiliconCompilers.
  // -----------------------------------------------------------------------
  when (snapPulse) {
    printf(p"[TLDirMon] bank=${io.bankId} cycle=${cycleCount} snap=${writePtr}\n")
  }
}
