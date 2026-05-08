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
      // Optional saturation counter MMIO — raw register signals wired to
      // the Scheduler's SatCounterCtrlIO in InclusiveCache.scala.
      val sat = if (outer.micro.enableSatCounter) Some(new Bundle {
        // HW → SW (readable)
        val histIdle   = Input(UInt(32.W))
        val histLow    = Input(UInt(32.W))
        val histMed    = Input(UInt(32.W))
        val histHigh   = Input(UInt(32.W))
        val writeCount = Input(UInt(32.W))
        val full       = Input(Bool())
        // SW → HW (writable, directly drive SatCounterCtrlIO inputs)
        val enable     = Output(Bool())
        val reset_ctr  = Output(Bool())
        val interval   = Output(UInt(32.W))
        val threshLow  = Output(UInt(32.W))
        val threshHigh = Output(UInt(32.W))
        val histIdx    = Output(UInt(32.W))
      }) else None
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

    // ---- Saturation counter MMIO registers (offset 0x300 – 0x348) ----
    val satRegmap: Seq[(Int, Seq[RegField])] = if (outer.micro.enableSatCounter) {
      val sat = io.sat.get

      // 0x300: Control register — bit 0 = enable, bit 1 = reset (auto-clears)
      val satEnableReg = RegInit(true.B)
      val satResetReg  = WireInit(false.B)
      val satCtrlField = RegField(32, RegReadFn(_ => (true.B, Cat(0.U(30.W), false.B, satEnableReg))),
        RegWriteFn((valid, data) => {
          when (valid) {
            satEnableReg := data(0)
            satResetReg  := data(1)
          }
          true.B
        }), RegFieldDesc("satCtrl", "Saturation counter control: bit0=enable, bit1=reset"))
      sat.enable    := satEnableReg
      sat.reset_ctr := satResetReg

      // 0x308: Sampling interval
      val satIntervalReg = RegInit(500000.U(32.W))
      val satIntervalField = RegField(32, satIntervalReg,
        RegFieldDesc("satInterval", "Histogram sampling interval in cycles"))
      sat.interval := satIntervalReg

      // 0x310: Low threshold
      val satThreshLowReg = RegInit(2.U(32.W))
      val satThreshLowField = RegField(32, satThreshLowReg,
        RegFieldDesc("satThreshLow", "Counter value <= this is LOW"))
      sat.threshLow := satThreshLowReg

      // 0x318: High threshold
      val satThreshHighReg = RegInit(5.U(32.W))
      val satThreshHighField = RegField(32, satThreshHighReg,
        RegFieldDesc("satThreshHigh", "Counter value >= this is HIGH"))
      sat.threshHigh := satThreshHighReg

      // 0x320: Status — bit 0 = full
      val satStatusField = RegField.r(32, Cat(0.U(31.W), sat.full),
        RegFieldDesc("satStatus", "Bit 0: history memory full"))

      // 0x328: Write count (# snapshots recorded)
      val satWriteCountField = RegField.r(32, sat.writeCount,
        RegFieldDesc("satWriteCount", "Number of histogram snapshots recorded"))

      // 0x330: History read index
      val satHistIdxReg = RegInit(0.U(32.W))
      val satHistIdxField = RegField(32, satHistIdxReg,
        RegFieldDesc("satHistIdx", "Index for reading history memory"))
      sat.histIdx := satHistIdxReg

      // 0x338-0x350: History read data (1-cycle read latency from SyncReadMem)
      val satHistIdleField = RegField.r(32, sat.histIdle,
        RegFieldDesc("satHistIdle", "IDLE bin count at satHistIdx (counter==0)"))
      val satHistLowField = RegField.r(32, sat.histLow,
        RegFieldDesc("satHistLow", "LOW bin count at satHistIdx (0<counter<=threshLow)"))
      val satHistMedField = RegField.r(32, sat.histMed,
        RegFieldDesc("satHistMed", "MEDIUM bin count at satHistIdx"))
      val satHistHighField = RegField.r(32, sat.histHigh,
        RegFieldDesc("satHistHigh", "HIGH bin count at satHistIdx"))

      Seq(
        0x300 -> Seq(satCtrlField),
        0x308 -> Seq(satIntervalField),
        0x310 -> Seq(satThreshLowField),
        0x318 -> Seq(satThreshHighField),
        0x320 -> Seq(satStatusField),
        0x328 -> Seq(satWriteCountField),
        0x330 -> Seq(satHistIdxField),
        0x338 -> Seq(satHistIdleField),
        0x340 -> Seq(satHistLowField),
        0x348 -> Seq(satHistMedField),
        0x350 -> Seq(satHistHighField)
      )
    } else Nil

    val regmap = ctrlnode.regmap(
      Seq(
        0x000 -> RegFieldGroup("Config", Some("Information about the Cache Configuration"), Seq(banksR, waysR, lgSetsR, lgBlockBytesR)),
        0x200 -> (if (control.beatBytes >= 8) Seq(flush64) else Nil),
        0x240 -> Seq(flush32)
      ) ++ satRegmap: _*
    )
  }
}
