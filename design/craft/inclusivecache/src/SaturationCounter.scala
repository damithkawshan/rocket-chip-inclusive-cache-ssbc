/*
 * Per-set saturation counter with histogram & history memory.
 *
 * Each cache set maintains a saturation counter in [0, 2K-1] where K is the
 * number of ways.  The counter increments on a miss and decrements on a hit.
 *
 * A 3-bin histogram (LOW / MEDIUM / HIGH) is computed over all sets and
 * periodically (or on SW trigger) snapshot into a history memory that is
 * readable via MMIO.  The history memory depth is compile-time configurable
 * (satHistoryDepth); recording stops when full for safety.
 *
 * The module is instantiated inside the Scheduler and gated by
 * micro.enableSatCounter at elaboration time.  When the flag is false, no
 * hardware is generated.
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._

// ---------------------------------------------------------------------------
// Event taps surfaced by InclusiveCacheBankScheduler
// ---------------------------------------------------------------------------
class SatCounterTaps(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  // Directory-path event (new allocation + tag-mismatch reload)
  val dirLookupValid = Bool()                  // directory result is valid this cycle
  val dirLookupHit   = Bool()                  // was it a hit?
  val dirLookupSet   = UInt(params.setBits.W)  // which set?

  // Repeat-path event (tag-match reload — bypasses the Directory entirely)
  val repeatValid    = Bool()                  // a repeat-hit fired this cycle
  val repeatSet      = UInt(params.setBits.W)  // which set?
}

// ---------------------------------------------------------------------------
// MMIO control / status interface (directly wired to Control.scala regmap)
// ---------------------------------------------------------------------------
class SatCounterCtrlIO(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  // --- SW → HW (driven by MMIO write registers) ---
  val enable      = Input(Bool())                                              // sampling active
  val reset_ctr   = Input(Bool())                                              // pulse: reset counters + history
  val interval    = Input(UInt(32.W))                                          // snapshot period (cycles)
  val threshLow   = Input(UInt(params.satCounterBits.W))                       // ≤ this → LOW
  val threshHigh  = Input(UInt(params.satCounterBits.W))                       // ≥ this → HIGH
  val histIdx     = Input(UInt(log2Ceil(params.micro.satHistoryDepth).W))       // read index into history

  // --- HW → SW (readable via MMIO) ---
  val histIdle    = Output(UInt(log2Ceil(params.cache.sets + 1).W))            // IDLE count at histIdx (counter == 0)
  val histLow     = Output(UInt(log2Ceil(params.cache.sets + 1).W))            // LOW  count at histIdx (0 < counter <= threshLow)
  val histMed     = Output(UInt(log2Ceil(params.cache.sets + 1).W))            // MED  count at histIdx
  val histHigh    = Output(UInt(log2Ceil(params.cache.sets + 1).W))            // HIGH count at histIdx
  val writeCount  = Output(UInt(log2Ceil(params.micro.satHistoryDepth + 1).W)) // # snapshots recorded
  val full        = Output(Bool())                                             // history memory full
}

// ---------------------------------------------------------------------------
// Core module
// ---------------------------------------------------------------------------
class InclusiveCacheSatCounter(params: InclusiveCacheParameters) extends Module
{
  // --- Print parameters during Chisel elaboration ---
  println(s"InclusiveCache Saturation Counter Configuration:")
  println(s"  Sets          : ${params.cache.sets}")
  println(s"  Ways          : ${params.cache.ways}")
  println(s"  Counter Bits  : ${params.satCounterBits}")
  println(s"  Counter Max   : ${params.satCounterMax}")
  println(s"  History Depth : ${params.micro.satHistoryDepth}")

  val io = IO(new Bundle {
    val taps    = Flipped(new SatCounterTaps(params))
    val control = new SatCounterCtrlIO(params)
    val bankId  = Input(UInt(8.W))   // for printf identification
  })

  // -----------------------------------------------------------------------
  // Per-set saturating counters
  // -----------------------------------------------------------------------
  val counters = RegInit(VecInit(Seq.fill(params.cache.sets)(0.U(params.satCounterBits.W))))

  private def satInc(v: UInt): UInt = Mux(v === params.satCounterMax.U, v, v + 1.U)
  private def satDec(v: UInt): UInt = Mux(v === 0.U, v, v - 1.U)

  // Directory-path update: inc on miss, dec on hit
  when (io.taps.dirLookupValid) {
    val s = io.taps.dirLookupSet
    counters(s) := Mux(io.taps.dirLookupHit, satDec(counters(s)), satInc(counters(s)))
  }

  // Repeat-path update: always a hit → dec
  // (Cannot collide with dirLookup on the same set in the same cycle because
  //  one set can only have one MSHR, and the two events are mutually exclusive
  //  for a given MSHR in a given cycle.)
  when (io.taps.repeatValid) {
    val s = io.taps.repeatSet
    counters(s) := satDec(counters(s))
  }

  // -----------------------------------------------------------------------
  // Reset
  // -----------------------------------------------------------------------
  when (io.control.reset_ctr) {
    counters.foreach { c => c := 0.U }
  }

  // -----------------------------------------------------------------------
  // Histogram computation (combinational predicates → registered counts)
  // -----------------------------------------------------------------------
  val setsBitsW = log2Ceil(params.cache.sets + 1).W
  // 4 mutually exclusive bins: IDLE (==0), LOW (0 < c <= threshLow), HIGH (c >= threshHigh && !low), MEDIUM (rest)
  val isIdle    = counters.map(_ === 0.U)
  val isLow     = counters.zip(isIdle).map { case (c, idle) => c <= io.control.threshLow && !idle }
  val isHigh    = counters.zip(isLow).zip(isIdle).map { case ((c, lo), idle) => c >= io.control.threshHigh && !lo && !idle }

  // Pipeline the wide PopCount trees one cycle so they don't feed
  // combinationally into snapshot/write-enable logic.
  val idleCountReg = RegNext(PopCount(isIdle).asUInt, 0.U)
  val lowCountReg  = RegNext(PopCount(isLow).asUInt,  0.U)
  val highCountReg = RegNext(PopCount(isHigh).asUInt, 0.U)
  val medCountReg  = params.cache.sets.U(setsBitsW) - idleCountReg - lowCountReg - highCountReg

  // -----------------------------------------------------------------------
  // History memory — stores snapshots of {idle, low, med, high}
  // -----------------------------------------------------------------------
  val depth = params.micro.satHistoryDepth
  val depthBits = log2Ceil(depth)

  val histMemIdle = SyncReadMem(depth, UInt(log2Ceil(params.cache.sets + 1).W))
  val histMemLow  = SyncReadMem(depth, UInt(log2Ceil(params.cache.sets + 1).W))
  val histMemMed  = SyncReadMem(depth, UInt(log2Ceil(params.cache.sets + 1).W))
  val histMemHigh = SyncReadMem(depth, UInt(log2Ceil(params.cache.sets + 1).W))

  val writePtr   = RegInit(0.U(log2Ceil(depth + 1).W))
  val fullReg    = RegInit(false.B)

  // -----------------------------------------------------------------------
  // Snapshot timing — interval down-counter (avoids 32-bit runtime modulo)
  // -----------------------------------------------------------------------
  // Free-running cycle counter is kept only for the simulation printf.
  val cycleCount = RegInit(0.U(32.W))
  cycleCount := cycleCount + 1.U

  val intervalNonZero = io.control.interval =/= 0.U
  // Detect SW-side interval changes so we resync the down-counter.
  val intervalPrev    = RegNext(io.control.interval, 0.U)
  val intervalChanged = intervalPrev =/= io.control.interval

  // Down-counter: counts interval-1, interval-2, …, 0; fires snap on 0 then reloads.
  val intervalCtr = RegInit(0.U(32.W))
  val ctrFires    = io.control.enable && intervalNonZero && (intervalCtr === 0.U)

  when (intervalChanged || !io.control.enable || !intervalNonZero) {
    // Reload to (interval-1) so the first snap is exactly `interval` cycles after enable.
    intervalCtr := Mux(intervalNonZero, io.control.interval - 1.U, 0.U)
  } .elsewhen (ctrFires) {
    intervalCtr := io.control.interval - 1.U
  } .otherwise {
    intervalCtr := intervalCtr - 1.U
  }

  // Register the snap pulse and full check so the BRAM write-enable / fullReg
  // paths are short (single FF → BRAM port, single FF → fullReg).
  val periodicSnapReg = RegNext(ctrFires, false.B)
  val writePtrAtMaxReg = RegNext(writePtr === (depth - 1).U, false.B)
  val doSnap = periodicSnapReg && !fullReg

  when (doSnap) {
    histMemIdle.write(writePtr(depthBits - 1, 0), idleCountReg)
    histMemLow .write(writePtr(depthBits - 1, 0), lowCountReg)
    histMemMed .write(writePtr(depthBits - 1, 0), medCountReg)
    histMemHigh.write(writePtr(depthBits - 1, 0), highCountReg)
    writePtr := writePtr + 1.U
    when (writePtrAtMaxReg) {
      fullReg := true.B
    }
  }

  when (periodicSnapReg) {
    // Simulation printf — uses registered counts (matches what was written).
    printf(p"[SatCounter] bank=${io.bankId} cycle=${cycleCount}" +
           p" idle=${idleCountReg} low=${lowCountReg} med=${medCountReg} high=${highCountReg}\n")
  }

  // -----------------------------------------------------------------------
  // Reset logic for history
  // -----------------------------------------------------------------------
  when (io.control.reset_ctr) {
    writePtr := 0.U
    fullReg  := false.B
    cycleCount := 0.U
    intervalCtr := Mux(intervalNonZero, io.control.interval - 1.U, 0.U)
  }

  // -----------------------------------------------------------------------
  // MMIO read interface — indexed read from history memory
  // -----------------------------------------------------------------------
  io.control.histIdle := histMemIdle.read(io.control.histIdx)
  io.control.histLow  := histMemLow.read(io.control.histIdx)
  io.control.histMed  := histMemMed.read(io.control.histIdx)
  io.control.histHigh := histMemHigh.read(io.control.histIdx)
  io.control.writeCount := writePtr
  io.control.full       := fullReg
}
