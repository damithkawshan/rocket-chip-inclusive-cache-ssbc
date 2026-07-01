/*
 * Set-Balancing Cache (SBC) — Destination Set Selector
 *
 * Tracks up to `d` cold candidate sets and reports the coldest one in O(d) compares, independent of
 * the total set count. Refreshed from saturation-counter updates. Phase 0: observation only.
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._

class DSSUpdate(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val set   = UInt(params.setBits.W)
  val level = UInt(params.micro.satCounterBits.W)
}

class DSS(params: InclusiveCacheParameters, d: Int) extends Module
{
  val io = IO(new Bundle {
    val update       = Flipped(Valid(new DSSUpdate(params)))
    val clear        = Input(Bool())
    val coldestValid = Output(Bool())
    val coldestSet   = Output(UInt(params.setBits.W))
    val coldestLevel = Output(UInt(params.micro.satCounterBits.W))
  })

  val idxW   = math.max(1, log2Ceil(d))
  val valid  = RegInit(VecInit(Seq.fill(d)(false.B)))
  val setIdx = Reg(Vec(d, UInt(params.setBits.W)))
  val level  = Reg(Vec(d, UInt(params.micro.satCounterBits.W)))

  // Is the updated set already a candidate?
  val matchOH  = VecInit((0 until d).map(i => valid(i) && setIdx(i) === io.update.bits.set))
  val hasMatch = matchOH.asUInt.orR

  // First free (invalid) slot, if any
  val freeOH  = PriorityEncoderOH(valid.map(!_))
  val hasFree = !valid.asUInt.andR

  // Hottest current candidate (max level among valid slots) — the DSS's own eviction victim
  val maxIdx = (1 until d).foldLeft(0.U(idxW.W)) { (best, i) =>
    Mux(valid(i) && (!valid(best) || level(i) > level(best)), i.U(idxW.W), best)
  }

  when (io.update.valid) {
    when (hasMatch) {
      (0 until d).foreach { i => when (matchOH(i)) { level(i) := io.update.bits.level } }
    } .elsewhen (hasFree) {
      (0 until d).foreach { i => when (freeOH(i)) {
        valid(i)  := true.B
        setIdx(i) := io.update.bits.set
        level(i)  := io.update.bits.level
      } }
    } .elsewhen (io.update.bits.level < level(maxIdx)) {
      // The updated set is colder than our hottest candidate — keep the colder one.
      setIdx(maxIdx) := io.update.bits.set
      level(maxIdx)  := io.update.bits.level
    }
  }

  // SBC reset: drop all candidates. setIdx/level become don't-care once invalid.
  // Placed after the update block so it wins on a same-cycle collision.
  when (io.clear) {
    valid.foreach(_ := false.B)
  }

  // Coldest candidate = min level among valid slots
  val minIdx = (1 until d).foldLeft(0.U(idxW.W)) { (best, i) =>
    Mux(valid(i) && (!valid(best) || level(i) < level(best)), i.U(idxW.W), best)
  }
  io.coldestValid := valid.asUInt.orR
  io.coldestSet   := setIdx(minIdx)
  io.coldestLevel := level(minIdx)
}
