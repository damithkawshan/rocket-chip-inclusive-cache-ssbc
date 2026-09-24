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
      // SBC: master switch (SBC_MigrateEnable) out — gates only the start of a new migration
      val sbc_migrate_enable = Output(Bool())
      // SBC: SW-selected set index out, read-only stats in
      val sbc_satReadSet = Output(UInt(log2Ceil(outer.cache.sets).W))
      val sbc_stats      = Input(new SBCStats(log2Ceil(outer.cache.sets), outer.micro.satCounterBits))
      // SBC 005: every monitoring counter (PerfCounters). Reads 0 when enablePerfCounters = false.
      val perfStats  = Input(new PerfCounterStats)
      // SBC: SW arm pulse out (a write to SBC_BalanceSet → 1-cycle valid+set)
      val sbc_balanceSet = Valid(UInt(log2Ceil(outer.cache.sets).W))
      // SBC: SW reset pulse out (a write to SBC_Reset → 1-cycle high; zeroes all SBC observation state)
      val sbc_reset = Output(Bool())
      // SBC: SW counter-only reset pulse out (a write to SBC_StatsReset). Zeroes ONLY the event/hit
      // counters, never sat/AT/DSS/parkCount/SBC_Parked, so the SBC flow is untouched.
      val sbc_stats_reset = Output(Bool())
      // 005 commit 1: L2_StatsHold out - freezes every event counter (not SBC_Parked) for an exact
      // multi-register read.
      val stats_hold = Output(Bool())
      // 008: L2_Replacement out (1 = PLRU victim). Absent when plruReplacement = false.
      val l2_replacement = if (outer.micro.plruReplacement) Some(Output(Bool())) else None
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
    // SBC_SetSel only steers the read-back muxes, so it goes with them: reads 0 when the flag is off.
    val sbcSetSel: UInt = if (outer.micro.enablePerfCounters) RegInit(0.U(sbcSetBits.W)) else 0.U(sbcSetBits.W)
    io.sbc_satReadSet := sbcSetSel

    // SBC: master switch. R/W, level (not a pulse), default OFF. Gates only the START of a new
    // migration — every already-parked line is served/written-back/evicted the same either way.
    val sbcMigrateEnable = RegInit(false.B)
    io.sbc_migrate_enable := sbcMigrateEnable

    // SBC: arm pulse — a write to SBC_BalanceSet emits a 1-cycle valid+set downstream.
    val sbcArmPulse = WireInit(false.B)
    val sbcArmSet   = WireInit(0.U(sbcSetBits.W))
    io.sbc_balanceSet.valid := sbcArmPulse
    io.sbc_balanceSet.bits  := sbcArmSet

    // SBC: reset pulse — a write to SBC_Reset emits a 1-cycle high downstream.
    val sbcResetPulse = WireInit(false.B)
    io.sbc_reset := sbcResetPulse

    // SBC: counter-only reset pulse — a write to SBC_StatsReset emits a 1-cycle high downstream.
    val sbcStatsResetPulse = WireInit(false.B)
    io.sbc_stats_reset := sbcStatsResetPulse

    val sbcSetSelDesc  = RegFieldDesc("SBC_SetSel", "Set index selected for SBC saturation read-back")
    val sbcSetSelField = if (outer.micro.enablePerfCounters) RegField(sbcSetBits, sbcSetSel, sbcSetSelDesc)
                         else RegField.r(sbcSetBits, sbcSetSel, sbcSetSelDesc)
    val sbcSetSatField = RegField.r(sbcSatBits, io.sbc_stats.satReadValue,
      RegFieldDesc("SBC_SetSat", "Saturation counter of the selected set", volatile=true))
    val sbcColdestSetField = RegField.r(sbcSetBits, io.sbc_stats.coldestSet,
      RegFieldDesc("SBC_ColdestSet", "Current coldest (DSS) destination set", volatile=true))
    val sbcColdestLevelField = RegField.r(sbcSatBits, io.sbc_stats.coldestLevel,
      RegFieldDesc("SBC_ColdestLevel", "Saturation level of the coldest set", volatile=true))
    val sbcStatusField = RegField.r(8,
      Cat(io.sbc_stats.atValid, io.sbc_stats.coldestValid, outer.micro.enableSetBalancing.B),
      RegFieldDesc("SBC_Status", "bit0=enabled, bit1=coldestValid, bit2=selectedAtValid", volatile=true))
    val sbcMigrationsField = RegField.r(64, io.perfStats.migrations,
      RegFieldDesc("SBC_Migrations", "Migrations committed", volatile=true))
    val sbcSecHitsField = RegField.r(64, io.perfStats.secHits,
      RegFieldDesc("SBC_SecHits", "Secondary hits (0 in Phase 0)", volatile=true))
    val sbcSecMissField = RegField.r(64, io.perfStats.secMiss,
      RegFieldDesc("SBC_SecMiss", "Secondary misses (0 in Phase 0)", volatile=true))
    val sbcSecPermField = RegField.r(64, io.perfStats.secPerm,
      RegFieldDesc("SBC_SecPerm", "Secondary hits that had to acquire permission (subset of SecHits)", volatile=true))
    // SBC (003 §10.5): serve-in-place / displaced-eviction observability.
    val sbcSecWriteField = RegField.r(64, io.perfStats.secWrite,
      RegFieldDesc("SBC_SecWrite", "Serves where the requester needed T", volatile=true))
    val sbcSecProbeField = RegField.r(64, io.perfStats.secProbe,
      RegFieldDesc("SBC_SecProbe", "Serves that probed a client off the parked line first", volatile=true))
    val sbcDispReleaseField = RegField.r(64, io.perfStats.dispRelease,
      RegFieldDesc("SBC_DispRelease", "Dirty parked lines written back (addressed by lineHome)", volatile=true))
    val sbcDispDropField = RegField.r(64, io.perfStats.dispDrop,
      RegFieldDesc("SBC_DispDrop", "Clean parked lines released with no data", volatile=true))
    val sbcSecCField = RegField.r(64, io.perfStats.secC,
      RegFieldDesc("SBC_SecC", "Serves raised by a C-channel Release", volatile=true))
    val sbcHomeBranchField = RegField.r(64, io.perfStats.homeBranch,
      RegFieldDesc("SBC_HomeBranch", "Requests that found their own HOME line in BRANCH", volatile=true))
    // bits [7:0] = AT[sel].assocSet, bit 8 = sd (0 = source side). sd forced to bit 8 regardless of setBits.
    val sbcAtAssocField = RegField.r(9,
      Cat(io.sbc_stats.atSd, 0.U((8 - sbcSetBits).W), io.sbc_stats.atAssocSet),
      RegFieldDesc("SBC_AtAssoc", "AT[sel]: bits[7:0]=assocSet, bit8=sd", volatile=true))
    // 008 C2: destination-probe aborts by reason. Cleared by SBC_StatsReset and SBC_Reset, held by L2_StatsHold.
    val sbcDstAbortDirtyField = RegField.r(64, io.perfStats.dstAbortDirty,
      RegFieldDesc("SBC_DstAbortDirty", "Destination-probe aborts: the way was dirty, no client", volatile=true))
    val sbcDstAbortHeldField = RegField.r(64, io.perfStats.dstAbortHeld,
      RegFieldDesc("SBC_DstAbortHeld", "Destination-probe aborts: the way was clean, client-held", volatile=true))
    val sbcDstAbortBothField = RegField.r(64, io.perfStats.dstAbortBoth,
      RegFieldDesc("SBC_DstAbortBoth", "Destination-probe aborts: the way was dirty and client-held", volatile=true))
    val sbcParkedField = RegField.r(64, io.perfStats.parked,
      RegFieldDesc("SBC_Parked", "Live displaced lines currently resident", volatile=true))
    val sbcMigrateEnableField = RegField(1, sbcMigrateEnable,
      RegFieldDesc("SBC_MigrateEnable", "Master switch: gates only the START of a new migration. 0=off (default)"))
    // 008: L2_Replacement. Level, reset 0 = random. Not cleared by SBC_Reset/SBC_StatsReset, not held.
    // Safe to flip at any time: every way is a legal victim. Absent (reads 0) when the flag is off.
    val l2Replacement = if (outer.micro.plruReplacement) Some(RegInit(false.B)) else None
    io.l2_replacement.foreach { _ := l2Replacement.get }
    val l2ReplacementMap: Seq[RegField.Map] = l2Replacement.toSeq.map { r =>
      0x490 -> Seq(RegField(1, r, RegFieldDesc("L2_Replacement", "Victim policy: 0 = random, 1 = PLRU")))
    }
    // 005 commit 1: L2_StatsHold. Removed with enablePerfCounters (§4.3) - reads 0 when off.
    val sbcStatsHold: Bool = if (outer.micro.enablePerfCounters) RegInit(false.B) else false.B
    io.stats_hold := sbcStatsHold
    val sbcStatsHoldDesc = RegFieldDesc("L2_StatsHold",
      "R/W: while 1, every event counter (not SBC_Parked) keeps its value")
    val sbcStatsHoldField = if (outer.micro.enablePerfCounters) RegField(1, sbcStatsHold, sbcStatsHoldDesc)
                            else RegField.r(1, sbcStatsHold, sbcStatsHoldDesc)
    // SBC 006: main-memory traffic. reads+writes is the headline; report the two SEPARATELY in every
    // result table - SBC can trade one for the other (a parked dirty line that would have been
    // dropped now gets written back) and a combined figure would hide exactly that.
    val l2MemReadsField = RegField.r(64, io.perfStats.memReads,
      RegFieldDesc("L2_MemReads", "Outer AcquireBlock: blocks read from main memory", volatile=true))
    val l2MemWritesField = RegField.r(64, io.perfStats.memWrites,
      RegFieldDesc("L2_MemWrites", "Outer ReleaseData: dirty blocks written to main memory", volatile=true))
    val l2MemAcqPermField = RegField.r(64, io.perfStats.memAcqPerm,
      RegFieldDesc("L2_MemAcqPerm", "Outer AcquirePerm: requester overwrites the whole block, no bytes moved", volatile=true))
    val l2MemRelCleanField = RegField.r(64, io.perfStats.memRelClean,
      RegFieldDesc("L2_MemRelClean", "Outer Release without data: clean eviction, no bytes moved", volatile=true))
    val l2CyclesField = RegField.r(64, io.perfStats.cycles,
      RegFieldDesc("L2_Cycles", "Free-running L2 clock; reset by SBC_StatsReset", volatile=true))
    // SBC 004: free-running total L2 hit-rate counters (always active; not reset by SBC_Reset)
    val l2AccessesField = RegField.r(64, io.perfStats.l2Accesses,
      RegFieldDesc("L2_Accesses", "Total primary directory lookups (hit+miss), free-running", volatile=true))
    val l2HitsField = RegField.r(64, io.perfStats.l2Hits,
      RegFieldDesc("L2_Hits", "Total primary hits, free-running", volatile=true))

    // 005 commit 1: outcome counters that follow cache-terminology.md. Reads 0 when
    // enablePerfCounters = false (io.perfStats is zeroed at the source in that build).
    val l2AccessAField = RegField.r(64, io.perfStats.accessA,
      RegFieldDesc("L2_AccessA", "An inner-A request accepted (the terminology's access)", volatile=true))
    val l2PrimaryHitField = RegField.r(64, io.perfStats.primaryHit,
      RegFieldDesc("L2_PrimaryHit", "Home line hit with enough permission - no outer A", volatile=true))
    val l2SecondaryHitField = RegField.r(64, io.perfStats.secondaryHit,
      RegFieldDesc("L2_SecondaryHit", "Served from the partner set with enough permission - no outer A", volatile=true))
    val l2ProbedHitField = RegField.r(64, io.perfStats.probedHit,
      RegFieldDesc("L2_ProbedHit", "A primary or secondary hit that also probed a client", volatile=true))
    val l2DataMissField = RegField.r(64, io.perfStats.dataMiss,
      RegFieldDesc("L2_DataMiss", "Outer A with param != BtoT", volatile=true))
    val l2UpgradeMissField = RegField.r(64, io.perfStats.upgradeMiss,
      RegFieldDesc("L2_UpgradeMiss", "Outer A with param == BtoT (BRANCH needs TRUNK)", volatile=true))
    val l2SecondSearchField = RegField.r(64, io.perfStats.secondSearch,
      RegFieldDesc("L2_SecondSearch", "The plan armed a search of the partner set", volatile=true))
    val l2SecondaryMissField = RegField.r(64, io.perfStats.secondaryMiss,
      RegFieldDesc("L2_SecondaryMiss", "Partner set searched, line not found", volatile=true))

    // SBC: arm migration for a source set (write-only). A write pulses io.sbc_balanceSet.
    val sbcBalanceSetField = RegField.w(sbcSetBits, RegWriteFn((ivalid, oready, data) => {
      when (ivalid) { sbcArmPulse := true.B; sbcArmSet := data }
      (true.B, true.B)  // fire-and-forget: ovalid must not track ivalid, or the D beat never fires on real fabric (hangs on FPGA, not sim).
    }), RegFieldDesc("SBC_BalanceSet", "Arm SBC migration for the written source-set index"))
    val sbcAttemptedField = RegField.r(64, io.perfStats.attempted,
      RegFieldDesc("SBC_Attempted", "Migrations attempted (setup reached)", volatile=true))
    val sbcAbortedField = RegField.r(64, io.perfStats.aborted,
      RegFieldDesc("SBC_Aborted", "Migrations aborted (ineligible source/destination)", volatile=true))

    // SBC: zero all SBC observation state (write-only). A write of any value pulses io.sbc_reset.
    val sbcResetField = RegField.w(32, RegWriteFn((ivalid, oready, data) => {
      when (ivalid) { sbcResetPulse := true.B }
      (true.B, true.B)  // fire-and-forget: ovalid must not track ivalid, or the D beat never fires on real fabric (hangs on FPGA, not sim).
    }), RegFieldDesc("SBC_Reset", "Write any value to zero all SBC saturation counters, AT, DSS and event counters"))

    // SBC: zero ONLY the event/hit counters (write-only). Leaves sat/AT/DSS/parkCount/nParked intact,
    // so the SBC flow is unaffected — for clean measurement windows via a single read.
    val sbcStatsResetField = RegField.w(32, RegWriteFn((ivalid, oready, data) => {
      when (ivalid) { sbcStatsResetPulse := true.B }
      (true.B, true.B)  // fire-and-forget, same as SBC_Reset (or the D beat hangs on real fabric)
    }), RegFieldDesc("SBC_StatsReset", "Write any value to zero ONLY the SBC event/hit counters"))

    val regmap = ctrlnode.regmap((Seq[RegField.Map](
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
      0x338 -> Seq(sbcSecMissField),
      0x340 -> RegFieldGroup("SBC_Ctrl", Some("Set-Balancing Cache control/counters"), Seq(sbcBalanceSetField)),
      0x348 -> Seq(sbcAttemptedField),
      0x350 -> Seq(sbcAbortedField),
      0x358 -> RegFieldGroup("SBC_Reset", Some("Zero all SBC observation state"), Seq(sbcResetField)),
      0x360 -> Seq(sbcSecPermField),
      0x368 -> Seq(sbcSecWriteField),
      0x370 -> Seq(sbcSecProbeField),
      0x378 -> Seq(sbcDispReleaseField),
      0x380 -> Seq(sbcDispDropField),
      0x388 -> Seq(sbcSecCField),
      0x390 -> Seq(sbcHomeBranchField),
      0x398 -> Seq(sbcAtAssocField),
      0x3A0 -> Seq(sbcParkedField),
      0x3A8 -> Seq(l2AccessesField),
      0x3B0 -> Seq(l2HitsField),
      0x3B8 -> RegFieldGroup("SBC_StatsReset", Some("Zero only the SBC event/hit counters"), Seq(sbcStatsResetField)),
      0x3C0 -> Seq(sbcMigrateEnableField),
      0x3C8 -> RegFieldGroup("L2_MemTraffic", Some("Main-memory traffic seen at the outer port"),
                             Seq(l2MemReadsField)),
      0x3D0 -> Seq(l2MemWritesField),
      0x3D8 -> Seq(l2MemAcqPermField),
      0x3E0 -> Seq(l2MemRelCleanField),
      0x3E8 -> Seq(l2CyclesField),
      0x3F0 -> Seq(l2AccessAField),
      0x3F8 -> Seq(l2PrimaryHitField),
      0x400 -> Seq(l2SecondaryHitField),
      0x408 -> Seq(l2ProbedHitField),
      0x410 -> Seq(l2DataMissField),
      0x418 -> Seq(l2UpgradeMissField),
      0x420 -> Seq(l2SecondSearchField),
      0x428 -> Seq(l2SecondaryMissField),
      0x438 -> Seq(sbcStatsHoldField),
      0x498 -> Seq(sbcDstAbortDirtyField),
      0x4A0 -> Seq(sbcDstAbortHeldField),
      0x4A8 -> Seq(sbcDstAbortBothField)
    ) ++ l2ReplacementMap): _*)
  }
}
