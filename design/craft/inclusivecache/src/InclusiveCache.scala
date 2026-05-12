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

import freechips.rocketchip.subsystem.{SubsystemBankedCoherenceKey}
import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink._

class InclusiveCache(
  val cache: CacheParameters,
  val micro: InclusiveCacheMicroParameters,
  control: Option[InclusiveCacheControlParameters] = None
  )(implicit p: Parameters)
    extends LazyModule
{
  val access = TransferSizes(1, cache.blockBytes)
  val xfer = TransferSizes(cache.blockBytes, cache.blockBytes)
  val atom = TransferSizes(1, cache.beatBytes)

  var resourcesOpt: Option[ResourceBindings] = None

  val device: SimpleDevice = new SimpleDevice("cache-controller", Seq("sifive,inclusivecache0", "cache")) {
    def ofInt(x: Int) = Seq(ResourceInt(BigInt(x)))

    override def describe(resources: ResourceBindings): Description = {
      resourcesOpt = Some(resources)

      val Description(name, mapping) = super.describe(resources)
      // Find the outer caches
      val outer = node.edges.out
        .flatMap(_.manager.managers)
        .filter(_.supportsAcquireB)
        .flatMap(_.resources.headOption)
        .map(_.owner.label)
        .distinct
      val nextlevel: Option[(String, Seq[ResourceValue])] =
        if (outer.isEmpty) {
          None
        } else {
          Some("next-level-cache" -> outer.map(l => ResourceReference(l)).toList)
        }

      val extra = Map(
        "cache-level"            -> ofInt(2),
        "cache-unified"          -> Nil,
        "cache-size"             -> ofInt(cache.sizeBytes * node.edges.in.size),
        "cache-sets"             -> ofInt(cache.sets * node.edges.in.size),
        "cache-block-size"       -> ofInt(cache.blockBytes),
        "sifive,mshr-count"      -> ofInt(InclusiveCacheParameters.all_mshrs(cache, micro)))
      Description(name, mapping ++ extra ++ nextlevel)
    }
  }

  val node: TLAdapterNode = TLAdapterNode(
    clientFn  = { _ => TLClientPortParameters(Seq(TLClientParameters(
      name          = s"L${cache.level} InclusiveCache",
      sourceId      = IdRange(0, InclusiveCacheParameters.out_mshrs(cache, micro)),
      supportsProbe = xfer)))
    },
    managerFn = { m => TLManagerPortParameters(
      managers = m.managers.map { m => m.copy(
        regionType         = if (m.regionType >= RegionType.UNCACHED) RegionType.CACHED else m.regionType,
        resources          = Resource(device, "caches") +: m.resources,
        supportsAcquireB   = xfer,
        supportsAcquireT   = if (m.supportsAcquireT) xfer else TransferSizes.none,
        supportsArithmetic = if (m.supportsAcquireT) atom else TransferSizes.none,
        supportsLogical    = if (m.supportsAcquireT) atom else TransferSizes.none,
        supportsGet        = access,
        supportsPutFull    = if (m.supportsAcquireT) access else TransferSizes.none,
        supportsPutPartial = if (m.supportsAcquireT) access else TransferSizes.none,
        supportsHint       = access,
        alwaysGrantsT      = false,
        fifoId             = None)
      },
      beatBytes  = cache.beatBytes,
      endSinkId  = InclusiveCacheParameters.all_mshrs(cache, micro),
      minLatency = 2)
    })

  val ctrls = control.map { c =>
    val nCtrls = if (c.bankedControl) p(SubsystemBankedCoherenceKey).nBanks else 1
    Seq.tabulate(nCtrls) { i => LazyModule(new InclusiveCacheControl(this,
      c.copy(address = c.address + i * InclusiveCacheParameters.L2ControlSize))) }
  }.getOrElse(Nil)

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    // If you have a control port, you must have at least one cache port
    require (ctrls.isEmpty || !node.edges.in.isEmpty)

    // Extract the client IdRanges; must be the same on all ports!
    val clientIds = node.edges.in.headOption.map(_.client.clients.map(_.sourceId).sortBy(_.start))
    node.edges.in.foreach { e => require(e.client.clients.map(_.sourceId).sortBy(_.start) == clientIds.get) }

    // Use the natural ordering of clients (just like in Directory)
    node.edges.in.headOption.foreach { n =>
      println(s"L${cache.level} InclusiveCache Client Map:")
      n.client.clients.zipWithIndex.foreach { case (c,i) =>
        println(s"\t${i} <= ${c.name} (sourceId ${c.sourceId.start}-${c.sourceId.end})")
      }
      println("")
    }

    // Create the L2 Banks
    val mods = (node.in zip node.out).zipWithIndex map { case (((in, edgeIn), (out, edgeOut)), i) =>
      edgeOut.manager.managers.foreach { m =>
        require (m.supportsAcquireB.contains(xfer),
          s"All managers behind the L2 must support acquireB($xfer) " +
          s"but ${m.name} only supports (${m.supportsAcquireB})!")
        if (m.supportsAcquireT) require (m.supportsAcquireT.contains(xfer),
          s"Any probing managers behind the L2 must support acquireT($xfer) " +
          s"but ${m.name} only supports (${m.supportsAcquireT})!")
      }

      val params = InclusiveCacheParameters(cache, micro, !ctrls.isEmpty, edgeIn, edgeOut)
      val scheduler = Module(new InclusiveCacheBankScheduler(params)).suggestName("inclusive_cache_bank_sched")

      scheduler.io.in <> in
      out <> scheduler.io.out
      scheduler.io.ways := DontCare
      scheduler.io.divs := DontCare

      // Tie down default values in case there is no controller
      scheduler.io.req.valid := false.B
      scheduler.io.req.bits.address := 0.U
      scheduler.io.resp.ready := true.B


      // Fix-up the missing addresses. We do this here so that the Scheduler can be
      // deduplicated by Firrtl to make hierarchical place-and-route easier.
      out.a.bits.address := params.restoreAddress(scheduler.io.out.a.bits.address)
      in .b.bits.address := params.restoreAddress(scheduler.io.in .b.bits.address)
      out.c.bits.address := params.restoreAddress(scheduler.io.out.c.bits.address)

      // Sim-only TL channel probe: per-bank fire counters + per-fire printfs.
      // Entirely elided from the generated FIRRTL when enablePerfProbe is false.
      if (micro.enablePerfProbe) {
        InclusiveCachePerfProbe(i, params, in, out, edgeIn, edgeOut, micro.perfProbeDumpPeriod)
      }
      scheduler.io.perf.foreach { p =>
        InclusiveCacheHitMissProbe(i, params, p, micro.perfProbeDumpPeriod)
      }

      // Saturation counter: set bank ID and tie down defaults for MMIO control.
      // Actual MMIO wiring happens below in the ctrl loop.
      scheduler.io.satBankId.foreach { _ := i.U }
      scheduler.io.satControl.foreach { sc =>
        sc.enable     := false.B
        sc.reset_ctr  := false.B
        sc.interval   := 0.U
        sc.threshLow  := 0.U
        sc.threshHigh := 0.U
        sc.histIdx    := 0.U
      }

      // TL+Directory monitor: bank ID + tied-down defaults; MMIO wired below.
      scheduler.io.tldBankId.foreach { _ := i.U }
      scheduler.io.tldControl.foreach { tc =>
        tc.enable    := false.B
        tc.reset_ctr := false.B
        tc.interval  := 0.U
        tc.threshold := 0.U
        tc.snapIdx   := 0.U
        tc.wordIdx   := 0.U
      }

      scheduler
    }

    ctrls.foreach { ctrl =>
      ctrl.module.io.flush_req.ready := false.B
      ctrl.module.io.flush_resp := false.B
      ctrl.module.io.flush_match := false.B
      // Tie down sat counter inputs from HW side when no scheduler is connected yet
      ctrl.module.io.sat.foreach { sat =>
        sat.histIdle   := 0.U
        sat.histLow    := 0.U
        sat.histMed    := 0.U
        sat.histHigh   := 0.U
        sat.writeCount := 0.U
        sat.full       := false.B
      }
      ctrl.module.io.tld.foreach { tld =>
        tld.readData   := 0.U
        tld.writeCount := 0.U
        tld.full       := false.B
        tld.streaming  := false.B
        tld.nSrc       := 0.U
        tld.actWords   := 0.U
        tld.numWords   := 0.U
        tld.nSetsLg2   := 0.U
      }
    }

    mods.zip(node.edges.in).zipWithIndex.foreach { case ((sched, edgeIn), i) =>
      val ctrl = if (ctrls.size > 1) Some(ctrls(i)) else ctrls.headOption
      ctrl.foreach { ctrl => {
        val contained = edgeIn.manager.managers.flatMap(_.address)
          .map(_.contains(ctrl.module.io.flush_req.bits)).reduce(_||_)
        when (contained) { ctrl.module.io.flush_match := true.B }

        sched.io.req.valid := contained && ctrl.module.io.flush_req.valid
        sched.io.req.bits.address := ctrl.module.io.flush_req.bits
        when (contained && sched.io.req.ready) { ctrl.module.io.flush_req.ready := true.B }

        when (sched.io.resp.valid) { ctrl.module.io.flush_resp := true.B }
        sched.io.resp.ready := true.B

        // Wire saturation counter MMIO ↔ Scheduler
        for {
          sc  <- sched.io.satControl
          sat <- ctrl.module.io.sat
        } {
          // SW → HW (MMIO writes → Scheduler/SatCounter)
          sc.enable     := sat.enable
          sc.reset_ctr  := sat.reset_ctr
          sc.interval   := sat.interval
          sc.threshLow  := sat.threshLow
          sc.threshHigh := sat.threshHigh
          sc.histIdx    := sat.histIdx
          // HW → SW (SatCounter outputs → MMIO reads)
          sat.histIdle   := sc.histIdle
          sat.histLow    := sc.histLow
          sat.histMed    := sc.histMed
          sat.histHigh   := sc.histHigh
          sat.writeCount := sc.writeCount
          sat.full       := sc.full
        }
        // Wire TL+Directory monitor MMIO ↔ Scheduler
        for {
          tc  <- sched.io.tldControl
          tld <- ctrl.module.io.tld
        } {
          // SW → HW
          tc.enable    := tld.enable
          tc.reset_ctr := tld.reset_ctr
          tc.interval  := tld.interval
          tc.threshold := tld.threshold
          tc.snapIdx   := tld.snapIdx
          tc.wordIdx   := tld.wordIdx
          // HW → SW
          tld.readData   := tc.readData
          tld.writeCount := tc.writeCount
          tld.full       := tc.full
          tld.streaming  := tc.streaming
          tld.nSrc       := tc.nSrc
          tld.actWords   := tc.actWords
          tld.numWords   := tc.numWords
          tld.nSetsLg2   := tc.nSetsLg2
        }
      }}
    }

    def json = s"""{"banks":[${mods.map(_.json).mkString(",")}]}"""
  }
}
