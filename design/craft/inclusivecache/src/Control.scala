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
      // Optional phase-detection monitor MMIO — raw register signals wired to
      // the Scheduler's TLDirMonitorCtrlIO in InclusiveCache.scala.
      val tld = if (outer.micro.enableTLDirMonitor) Some(new Bundle {
        // HW → SW (readable)
        val readData    = Input(UInt(64.W))
        val writeCount  = Input(UInt(32.W))
        val full        = Input(Bool())
        val streaming   = Input(Bool())
        val nSrc        = Input(UInt(8.W))
        val cscWidthOut = Input(UInt(8.W))
        val actWords    = Input(UInt(16.W))
        val ssWords     = Input(UInt(16.W))
        val probeWords  = Input(UInt(16.W))
        val numWords    = Input(UInt(16.W))
        val nSetsLg2    = Input(UInt(8.W))
        val missedSnaps = Input(UInt(32.W))  // snapshots dropped due to streaming overlap
        // SW → HW (writable)
        val enable        = Output(Bool())
        val reset_ctr     = Output(Bool())
        val interval      = Output(UInt(32.W))
        val useMorris     = Output(Bool())
        val decayPeriod   = Output(UInt(32.W))
        val decayShift    = Output(UInt(8.W))
        val threshLo      = Output(UInt(32.W))
        val threshHi      = Output(UInt(32.W))
        val probeThreshLo = Output(UInt(32.W))
        val probeThreshHi = Output(UInt(32.W))
        val snapIdx       = Output(UInt(32.W))
        val wordIdx       = Output(UInt(32.W))
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

    // ---- Phase-detection monitor MMIO registers (offset 0x400 – 0x488) ----
    val tldRegmap: Seq[(Int, Seq[RegField])] = if (outer.micro.enableTLDirMonitor) {
      val tld = io.tld.get

      // 0x400: Control — bit 0 = enable, bit 1 = reset (auto-clears), bit 2 = useMorris
      val tldEnableReg    = RegInit(false.B)
      val tldUseMorrisReg = RegInit(false.B)
      val tldResetReg     = WireInit(false.B)
      val tldCtrlField = RegField(32,
        RegReadFn(_ => (true.B, Cat(0.U(29.W), tldUseMorrisReg, false.B, tldEnableReg))),
        RegWriteFn((valid, data) => {
          when (valid) {
            tldEnableReg    := data(0)
            tldResetReg     := data(1)
            tldUseMorrisReg := data(2)
          }
          true.B
        }), RegFieldDesc("tldCtrl", "Phase monitor control: bit0=enable, bit1=reset, bit2=useMorris"))
      tld.enable    := tldEnableReg
      tld.reset_ctr := tldResetReg
      tld.useMorris := tldUseMorrisReg

      // 0x408: Sampling interval (cycles)
      val tldIntervalReg = RegInit(0.U(32.W))
      val tldIntervalField = RegField(32, tldIntervalReg,
        RegFieldDesc("tldInterval", "Snapshot interval (cycles)"))
      tld.interval := tldIntervalReg

      // 0x410: Activity low threshold (CSC < threshLo → bucket 1)
      val tldThreshLoReg = RegInit(1.U(32.W))
      val tldThreshLoField = RegField(32, tldThreshLoReg,
        RegFieldDesc("tldThreshLo", "Activity low threshold (CSC bucket boundary)"))
      tld.threshLo := tldThreshLoReg

      // 0x418: Status — bit 0 = full, bit 1 = streaming
      val tldStatusField = RegField.r(32, Cat(0.U(30.W), tld.streaming, tld.full),
        RegFieldDesc("tldStatus", "bit0=full, bit1=streaming"))

      // 0x420: Snapshot count
      val tldWriteCountField = RegField.r(32, tld.writeCount,
        RegFieldDesc("tldWriteCount", "Number of completed snapshots"))

      // 0x428: Snapshot index for indexed readout
      val tldSnapIdxReg = RegInit(0.U(32.W))
      val tldSnapIdxField = RegField(32, tldSnapIdxReg,
        RegFieldDesc("tldSnapIdx", "Snapshot index (0..depth-1)"))
      tld.snapIdx := tldSnapIdxReg

      // 0x430: Word index within a snapshot
      val tldWordIdxReg = RegInit(0.U(32.W))
      val tldWordIdxField = RegField(32, tldWordIdxReg,
        RegFieldDesc("tldWordIdx", "Word index within snapshot (0..numWords-1)"))
      tld.wordIdx := tldWordIdxReg

      // 0x438: 64-bit read data at (snapIdx, wordIdx). 1-cycle SRAM latency.
      val tldReadDataField = RegField.r(64, tld.readData,
        RegFieldDesc("tldReadData", "64-bit data at (snapIdx, wordIdx)"))

      // 0x440: Geometry — packed { nSetsLg2[31:24], nSrc[23:16], actWords[15:8], numWords[7:0] }
      val tldGeomField = RegField.r(32,
        Cat(tld.nSetsLg2,
            tld.nSrc,
            tld.actWords(7, 0),
            tld.numWords(7, 0)),
        RegFieldDesc("tldGeom", "Layout: {nSetsLg2,nSrc,actWords,numWords}"))

      // 0x448: Wider geometry (16-bit fields) for SW that needs full numWords.
      val tldGeomWideField = RegField.r(32,
        Cat(tld.numWords, tld.actWords),
        RegFieldDesc("tldGeomWide", "{numWords[31:16], actWords[15:0]}"))

      // 0x450: Extended geometry — { reserved[31:24], cscWidth[23:16], probeWords[15:8], ssWords[7:0] }
      val tldGeomExtField = RegField.r(32,
        Cat(0.U(8.W), tld.cscWidthOut, tld.probeWords(7, 0), tld.ssWords(7, 0)),
        RegFieldDesc("tldGeomExt", "{reserved[31:24], cscWidth[23:16], probeWords[15:8], ssWords[7:0]}"))

      // 0x458: Activity high threshold (CSC < threshHi → bucket 2; otherwise 3)
      val tldThreshHiReg = RegInit(3.U(32.W))
      val tldThreshHiField = RegField(32, tldThreshHiReg,
        RegFieldDesc("tldThreshHi", "Activity high threshold (CSC bucket boundary)"))
      tld.threshHi := tldThreshHiReg

      // 0x460: Probe low threshold
      val tldProbeThreshLoReg = RegInit(1.U(32.W))
      val tldProbeThreshLoField = RegField(32, tldProbeThreshLoReg,
        RegFieldDesc("tldProbeThreshLo", "Probe low threshold (probeCsc bucket boundary)"))
      tld.probeThreshLo := tldProbeThreshLoReg

      // 0x468: Probe high threshold
      val tldProbeThreshHiReg = RegInit(3.U(32.W))
      val tldProbeThreshHiField = RegField(32, tldProbeThreshHiReg,
        RegFieldDesc("tldProbeThreshHi", "Probe high threshold (probeCsc bucket boundary)"))
      tld.probeThreshHi := tldProbeThreshHiReg

      // 0x470: Continuous-decay timer period (cycles); 0 disables decay.
      val tldDecayPeriodReg = RegInit(0.U(32.W))
      val tldDecayPeriodField = RegField(32, tldDecayPeriodReg,
        RegFieldDesc("tldDecayPeriod", "Cycles between leaky-decay pulses; 0 = disabled"))
      tld.decayPeriod := tldDecayPeriodReg

      // 0x478: Decay right-shift amount.
      val tldDecayShiftReg = RegInit(1.U(32.W))
      val tldDecayShiftField = RegField(32, tldDecayShiftReg,
        RegFieldDesc("tldDecayShift", "Right-shift amount applied on each decay pulse"))
      tld.decayShift := tldDecayShiftReg

      // 0x480: Missed-snapshot counter (snapshots dropped due to streaming overlap).
      // Read-only; cleared by a reset pulse on bit 1 of 0x400.
      val tldMissedSnapsField = RegField.r(32, tld.missedSnaps,
        RegFieldDesc("tldMissedSnaps", "Snapshots dropped due to streaming overlap; cleared by reset"))

      Seq(
        0x400 -> Seq(tldCtrlField),
        0x408 -> Seq(tldIntervalField),
        0x410 -> Seq(tldThreshLoField),
        0x418 -> Seq(tldStatusField),
        0x420 -> Seq(tldWriteCountField),
        0x428 -> Seq(tldSnapIdxField),
        0x430 -> Seq(tldWordIdxField),
        0x438 -> Seq(tldReadDataField),
        0x440 -> Seq(tldGeomField),
        0x448 -> Seq(tldGeomWideField),
        0x450 -> Seq(tldGeomExtField),
        0x458 -> Seq(tldThreshHiField),
        0x460 -> Seq(tldProbeThreshLoField),
        0x468 -> Seq(tldProbeThreshHiField),
        0x470 -> Seq(tldDecayPeriodField),
        0x478 -> Seq(tldDecayShiftField),
        0x480 -> Seq(tldMissedSnapsField)
      )
    } else Nil

    val regmap = ctrlnode.regmap(
      Seq(
        0x000 -> RegFieldGroup("Config", Some("Information about the Cache Configuration"), Seq(banksR, waysR, lgSetsR, lgBlockBytesR)),
        0x200 -> (if (control.beatBytes >= 8) Seq(flush64) else Nil),
        0x240 -> Seq(flush32)
      ) ++ satRegmap ++ tldRegmap: _*
    )
  }
}
