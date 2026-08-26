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
    // SBC: a set that refused a migration (no free or evictable way) is blocked as a destination
    // until every candidate has been tried. Saturation alone cannot see that a set is full.
    val reject       = Flipped(Valid(UInt(params.setBits.W)))
    // SBC Phase 3: both halves of a new pairing leave the candidate pool permanently (1:1 pinning).
    // Unlike `reject` this never wears off; teardown lets a set back in via the normal update path.
    val remove       = Flipped(Valid(new Bundle {
      val src = UInt(params.setBits.W)
      val dst = UInt(params.setBits.W)
    }))
    val clear        = Input(Bool())
    val coldestValid = Output(Bool())
    val coldestSet   = Output(UInt(params.setBits.W))
    val coldestLevel = Output(UInt(params.micro.satCounterBits.W))
  })

  val idxW   = math.max(1, log2Ceil(d))
  val valid  = RegInit(VecInit(Seq.fill(d)(false.B)))
  val setIdx = Reg(Vec(d, UInt(params.setBits.W)))
  val level  = Reg(Vec(d, UInt(params.micro.satCounterBits.W)))
  val blocked = RegInit(VecInit(Seq.fill(d)(false.B)))

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
        valid(i)   := true.B
        setIdx(i)  := io.update.bits.set
        level(i)   := io.update.bits.level
        blocked(i) := false.B
      } }
    } .elsewhen (io.update.bits.level < level(maxIdx)) {
      // The updated set is colder than our hottest candidate — keep the colder one.
      setIdx(maxIdx)  := io.update.bits.set
      level(maxIdx)   := io.update.bits.level
      blocked(maxIdx) := false.B
    }
  }

  // A refused destination stays blocked so the next pick rotates to another set. Without this the
  // coldest set never changes: a set whose lines all hit decays to level 0 and stays there, while
  // being exactly the set with no spare way.
  when (io.reject.valid) {
    (0 until d).foreach { i => when (valid(i) && setIdx(i) === io.reject.bits) { blocked(i) := true.B } }
  }

  // Eligible = a valid candidate we have not just been refused by.
  val elig = VecInit((0 until d).map(i => valid(i) && !blocked(i)))
  // Two ways a blocked set gets another chance:
  //   all refused - immediate new round, the common case.
  //   retry timer - safety net. The consumer also requires coldestLevel < T_lo, so if no eligible
  //                 candidate is cold enough nothing is ever offered, nothing is ever refused, and
  //                 the all-refused clear would never fire.
  val retry = RegInit(0.U(10.W))
  retry := retry + 1.U
  when (!elig.asUInt.orR || retry === 0.U) { blocked.foreach(_ := false.B) }

  // SBC Phase 3: a set that just entered a pairing is no longer a destination candidate. Placed after
  // the update block so a same-cycle update loses to the removal.
  when (io.remove.valid) {
    (0 until d).foreach { i =>
      when (valid(i) && (setIdx(i) === io.remove.bits.src || setIdx(i) === io.remove.bits.dst)) {
        valid(i) := false.B
      }
    }
  }

  // SBC reset: drop all candidates. setIdx/level become don't-care once invalid.
  // Placed after the update block so it wins on a same-cycle collision.
  when (io.clear) {
    valid.foreach(_ := false.B)
    blocked.foreach(_ := false.B)
  }

  // Coldest candidate = min level among eligible slots
  val minIdx = (1 until d).foldLeft(0.U(idxW.W)) { (best, i) =>
    Mux(elig(i) && (!elig(best) || level(i) < level(best)), i.U(idxW.W), best)
  }
  io.coldestValid := elig.asUInt.orR
  io.coldestSet   := setIdx(minIdx)
  io.coldestLevel := level(minIdx)
}
