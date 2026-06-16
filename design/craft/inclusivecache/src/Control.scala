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
    val io = IO(new Bundle {
      val flush_match = Input(Bool())
      val flush_req = Decoupled(UInt(64.W))
      val flush_resp = Input(Bool())
      // SBC: SW-selected set index out, read-only stats in
      val sbc_satReadSet = Output(UInt(log2Ceil(outer.cache.sets).W))
      val sbc_stats      = Input(new SBCStats(log2Ceil(outer.cache.sets), outer.micro.satCounterBits))
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

    // Set-Balancing Cache (SBC) observation block (read-only stats + a SW set-select).
    val sbcSetBits = log2Ceil(outer.cache.sets)
    val sbcSatBits = outer.micro.satCounterBits
    val sbcSetSel  = RegInit(0.U(sbcSetBits.W))
    io.sbc_satReadSet := sbcSetSel

    val sbcSetSelField = RegField(sbcSetBits, sbcSetSel,
      RegFieldDesc("SBC_SetSel", "Set index selected for SBC saturation read-back"))
    val sbcSetSatField = RegField.r(sbcSatBits, io.sbc_stats.satReadValue,
      RegFieldDesc("SBC_SetSat", "Saturation counter of the selected set", volatile=true))
    val sbcColdestSetField = RegField.r(sbcSetBits, io.sbc_stats.coldestSet,
      RegFieldDesc("SBC_ColdestSet", "Current coldest (DSS) destination set", volatile=true))
    val sbcColdestLevelField = RegField.r(sbcSatBits, io.sbc_stats.coldestLevel,
      RegFieldDesc("SBC_ColdestLevel", "Saturation level of the coldest set", volatile=true))
    val sbcStatusField = RegField.r(8,
      Cat(io.sbc_stats.atValid, io.sbc_stats.coldestValid, outer.micro.enableSetBalancing.B),
      RegFieldDesc("SBC_Status", "bit0=enabled, bit1=coldestValid, bit2=selectedAtValid", volatile=true))
    val sbcMigrationsField = RegField.r(32, io.sbc_stats.migrations,
      RegFieldDesc("SBC_Migrations", "Migrations performed (0 in Phase 0)", volatile=true))
    val sbcSecHitsField = RegField.r(32, io.sbc_stats.secHits,
      RegFieldDesc("SBC_SecHits", "Secondary hits (0 in Phase 0)", volatile=true))
    val sbcSecMissField = RegField.r(32, io.sbc_stats.secMiss,
      RegFieldDesc("SBC_SecMiss", "Secondary misses (0 in Phase 0)", volatile=true))

    val regmap = ctrlnode.regmap(
      0x000 -> RegFieldGroup("Config", Some("Information about the Cache Configuration"), Seq(banksR, waysR, lgSetsR, lgBlockBytesR)),
      0x200 -> (if (control.beatBytes >= 8) Seq(flush64) else Nil),
      0x240 -> Seq(flush32),
      0x300 -> RegFieldGroup("SBC", Some("Set-Balancing Cache observation/stats"), Seq(sbcSetSelField)),
      0x308 -> Seq(sbcSetSatField),
      0x310 -> Seq(sbcColdestSetField),
      0x318 -> Seq(sbcColdestLevelField),
      0x320 -> Seq(sbcStatusField),
      0x328 -> Seq(sbcMigrationsField),
      0x330 -> Seq(sbcSecHitsField),
      0x338 -> Seq(sbcSecMissField)
    )
  }
}
