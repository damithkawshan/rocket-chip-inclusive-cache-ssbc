/*
 * Recency (task 008): PLRU victim choice for the L2. Built only when plruReplacement = true; the
 * L2_Replacement register (0x490) picks between this victim and the LFSR one at run time.
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._
import freechips.rocketchip.util.SetAssocLRU

// 008: a touch says "this line was just used". set = the SRAM row, way = the way in that row.
class RecencyTouch(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val set = UInt(params.setBits.W)
  val way = UInt(params.wayBits.W)
}

// 008: per-set tree PLRU (rocket-chip's SetAssocLRU). Learns from touches, gives back one victim way.
// Nothing else in the cache reads it.
class Recency(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val touch     = Flipped(Vec(2, Valid(new RecencyTouch(params))))  // 0 = access, 1 = install
    val querySet  = Input(UInt(params.setBits.W))
    val victimWay = Output(UInt(params.wayBits.W))
  })

  // One register stage, no bypass: a touch moves the victim from the cycle after this stage, so the
  // victim is always read from registers and the module cannot join a combinational loop.
  val touch = io.touch.map { t =>
    val q = Wire(Valid(new RecencyTouch(params)))
    q.valid := RegNext(t.valid, false.B)
    q.bits  := RegEnable(t.bits, t.valid)
    q
  }

  val plru = new SetAssocLRU(params.cache.sets, params.cache.ways, "plru")
  // Folds in order: the access first, then the install.
  plru.access(touch.map(_.bits.set), touch.map { t =>
    val w = Wire(Valid(UInt(params.wayBits.W)))
    w.valid := t.valid
    w.bits  := t.bits.way
    w
  })
  io.victimWay := plru.way(io.querySet)

  if (params.micro.sbcDebug) {
    val cyc = RegInit(0.U(64.W))
    cyc := cyc + 1.U
    touch.zipWithIndex.foreach { case (t, i) =>
      when (t.valid) { printf(p"[SBC] PLRU-TOUCH cyc=${cyc} set=${t.bits.set} way=${t.bits.way} src=${i}\n") }
    }
  }
}
