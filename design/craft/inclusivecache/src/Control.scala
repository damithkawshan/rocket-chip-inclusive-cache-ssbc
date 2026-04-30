/*
 * Copyright 2019 SiFive, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You should have received a copy of LICENSE.Apache2 along with
 * this software. If not, you may obtain a copy at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._


import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink._

class InclusiveCacheControl(outer: InclusiveCache, control: InclusiveCacheControlParameters)(implicit p: Parameters) extends LazyModule()(p) {
  val ctrlnode = TLRegisterNode(
    address     = Seq(AddressSet(control.address, InclusiveCacheParameters.L2ControlSize-1)),
    device      = outer.device,
    concurrency = 1, // Only one flush at a time (else need to track who answers)
    beatBytes   = control.beatBytes)

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val nSets        = scala.math.min(outer.cache.sets, 128)
    val setBits      = log2Ceil(outer.cache.sets)
    val nPerfStreams = if (control.bankedControl) 1 else outer.node.edges.in.size

    val io = IO(new Bundle {
      val flush_match = Input(Bool())
      val flush_req = Decoupled(UInt(64.W))
      val flush_resp = Input(Bool())
      val perf = Input(Vec(nPerfStreams, new L2PerfEvents(setBits)))
    })
    // Flush directive
    val flushInValid   = RegInit(false.B)
    val flushInAddress = Reg(UInt(64.W))
    val flushOutValid  = RegInit(false.B)
    val flushOutReady  = WireInit(init = false.B)

    when (flushOutReady) { flushOutValid := false.B }
    when (io.flush_resp) { flushOutValid := true.B }
    when (io.flush_req.ready) { flushInValid := false.B }
    io.flush_req.valid := flushInValid
    io.flush_req.bits := flushInAddress

    when (!io.flush_match && flushInValid) {
      flushInValid := false.B
      flushOutValid := true.B
    }

    val flush32 = RegField.w(32, RegWriteFn((ivalid, oready, data) => {
      when (oready) { flushOutReady := true.B }
      when (ivalid) { flushInValid := true.B }
      when (ivalid && !flushInValid) { flushInAddress := data << 4 }
      (!flushInValid, flushOutValid)
    }), RegFieldDesc("Flush32", "Flush the physical address equal to the 32-bit written data << 4 from the cache"))

    val flush64 = RegField.w(64, RegWriteFn((ivalid, oready, data) => {
      when (oready) { flushOutReady := true.B }
      when (ivalid) { flushInValid := true.B }
      when (ivalid && !flushInValid) { flushInAddress := data }
      (!flushInValid, flushOutValid)
    }), RegFieldDesc("Flush64", "Flush the phsyical address equal to the 64-bit written data from the cache"))


    // Information about the cache configuration
    val banksR  = RegField.r(8, outer.node.edges.in.size.U,         RegFieldDesc("Banks",
      "Number of banks in the cache", reset=Some(outer.node.edges.in.size)))
    val waysR   = RegField.r(8, outer.cache.ways.U,                 RegFieldDesc("Ways",
      "Number of ways per bank", reset=Some(outer.cache.ways)))
    val lgSetsR = RegField.r(8, log2Ceil(outer.cache.sets).U,       RegFieldDesc("lgSets",
      "Base-2 logarithm of the sets per bank", reset=Some(log2Ceil(outer.cache.sets))))
    val lgBlockBytesR = RegField.r(8, log2Ceil(outer.cache.blockBytes).U, RegFieldDesc("lgBlockBytes",
      "Base-2 logarithm of the bytes per cache block", reset=Some(log2Ceil(outer.cache.blockBytes))))

    // Performance counters
    val perSetReq   = RegInit(VecInit(Seq.fill(nSets)(0.U(64.W))))
    val perSetMiss  = RegInit(VecInit(Seq.fill(nSets)(0.U(64.W))))
    val totalAccess = RegInit(0.U(64.W))
    val missCount   = RegInit(0.U(64.W))

    val clearPerSet = WireDefault(false.B)

    when (clearPerSet) {
      perSetReq.foreach(_ := 0.U)
      perSetMiss.foreach(_ := 0.U)
    } .otherwise {
      for (s <- 0 until nSets) {
        val rIncs = io.perf.map(p => p.req_valid  && p.req_set  === s.U)
        val mIncs = io.perf.map(p => p.miss_valid && p.miss_set === s.U)
        perSetReq(s)  := perSetReq(s)  + PopCount(rIncs)
        perSetMiss(s) := perSetMiss(s) + PopCount(mIncs)
      }
    }

    totalAccess := totalAccess + PopCount(io.perf.map(_.req_valid))
    missCount   := missCount   + PopCount(io.perf.map(_.miss_valid))

    val perSetHit = Wire(Vec(nSets, UInt(64.W)))
    for (s <- 0 until nSets) { perSetHit(s) := perSetReq(s) - perSetMiss(s) }

    val clearReg = RegField.w(64, RegWriteFn((ivalid, oready, data) => {
      when (ivalid) { clearPerSet := true.B }
      (true.B, true.B)
    }), RegFieldDesc("ClearPerSet", "Write any value to clear all per-set counters"))

    val perSetEntries: Seq[(Int, Seq[RegField])] = (0 until nSets).flatMap { s =>
      val base = 0x400 + s * 24
      Seq(
        base       -> Seq(RegField.r(64, perSetHit(s),  RegFieldDesc(s"PerSetHits$s",   s"Hits for set $s"))),
        (base + 8) -> Seq(RegField.r(64, perSetMiss(s), RegFieldDesc(s"PerSetMisses$s", s"Misses for set $s"))),
        (base + 16)-> Seq(RegField.r(64, 0.U(64.W),     RegFieldDesc(s"PerSetSecHits$s", s"Secondary hits for set $s (always 0)")))
      )
    }

    val baseEntries: Seq[(Int, Seq[RegField])] = Seq(
      0x000 -> Seq(banksR, waysR, lgSetsR, lgBlockBytesR),
      0x108 -> Seq(RegField.r(64, missCount,   RegFieldDesc("MissCount",   "Total memory-bound misses"))),
      0x110 -> Seq(RegField.r(64, totalAccess, RegFieldDesc("TotalAccess", "Total L1 cacheline requests"))),
      0x200 -> (if (control.beatBytes >= 8) Seq(flush64) else Nil),
      0x240 -> Seq(flush32),
      0x3F8 -> Seq(clearReg)
    )

    ctrlnode.regmap((baseEntries ++ perSetEntries): _*)
  }
}
