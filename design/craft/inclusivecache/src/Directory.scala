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
import freechips.rocketchip.tilelink._
import MetaData._
import chisel3.experimental.dataview._
import freechips.rocketchip.util.DescribedSRAM

class DirectoryEntry(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val dirty   = Bool() // true => TRUNK or TIP
  val state   = UInt(params.stateBits.W)
  val clients = UInt(params.clientBits.W)
  val tag     = UInt(params.tagBits.W)
}

class DirectoryWrite(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val set  = UInt(params.setBits.W)
  val way  = UInt(params.wayBits.W)
  val data = new DirectoryEntry(params)
}

class DirectoryRead(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val set = UInt(params.setBits.W)
  val tag = UInt(params.tagBits.W)
}

class DirectoryResult(params: InclusiveCacheParameters) extends DirectoryEntry(params)
{
  val hit = Bool()
  val way = UInt(params.wayBits.W)
}

class Directory(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val write  = Flipped(Decoupled(new DirectoryWrite(params)))
    val read   = Flipped(Valid(new DirectoryRead(params))) // sees same-cycle write
    val result = Valid(new DirectoryResult(params))
    val ready  = Bool() // reset complete; can enable access
  })

  val codeBits = new DirectoryEntry(params).getWidth

  val cc_dir =  DescribedSRAM(
    name = "cc_dir",
    desc = "Directory RAM",
    size = params.cache.sets,
    data = Vec(params.cache.ways, UInt(codeBits.W))
  )

  val write = Queue(io.write, 1) // must inspect contents => max size 1
  // a flow Q creates a WaR hazard... this MIGHT not cause a problem
  // a pipe Q causes combinational loop through the scheduler

  // Wiping the Directory with 0s on reset has ultimate priority
  val wipeCount = RegInit(0.U((params.setBits + 1).W))
  val wipeOff = RegNext(false.B, true.B) // don't wipe tags during reset
  val wipeDone = wipeCount(params.setBits)
  val wipeSet = wipeCount(params.setBits - 1,0)

  io.ready := wipeDone
  when (!wipeDone && !wipeOff) { wipeCount := wipeCount + 1.U }
  assert (wipeDone || !io.read.valid)

  // Be explicit for dumb 1-port inference
  val ren = io.read.valid
  val wen = (!wipeDone && !wipeOff) || write.valid
  assert (!io.read.valid || wipeDone)

  require (codeBits <= 256)

  write.ready := !io.read.valid
  when (!ren && wen) {
    cc_dir.write(
      Mux(wipeDone, write.bits.set, wipeSet),
      VecInit.fill(params.cache.ways) { Mux(wipeDone, write.bits.data.asUInt, 0.U) },
      UIntToOH(write.bits.way, params.cache.ways).asBools.map(_ || !wipeDone))
  }

  val ren1 = RegInit(false.B)
  val ren2 = if (params.micro.dirReg) RegInit(false.B) else ren1
  ren2 := ren1
  ren1 := ren

  val bypass_valid = params.dirReg(write.valid)
  val bypass = params.dirReg(write.bits, ren1 && write.valid)
  val regout = params.dirReg(cc_dir.read(io.read.bits.set, ren), ren1)
  val tag = params.dirReg(RegEnable(io.read.bits.tag, ren), ren1)
  val set = params.dirReg(RegEnable(io.read.bits.set, ren), ren1)

  val ways = regout.map(d => d.asTypeOf(new DirectoryEntry(params)))

  // Compute the victim way in case of an eviction.
  // The LFSR draw is always computed; under "coherency" policy it picks the
  // tie-breaker among same-cost candidate ways, preserving randomization.
  val victimLFSR = random.LFSR(width = 16, params.dirReg(ren))(InclusiveCacheParameters.lfsrBits-1, 0)
  val victimSums = Seq.tabulate(params.cache.ways) { i => ((1 << InclusiveCacheParameters.lfsrBits)*i / params.cache.ways).U }
  val victimLTE  = Cat(victimSums.map { _ <= victimLFSR }.reverse)
  val victimSimp = Cat(0.U(1.W), victimLTE(params.cache.ways-1, 1), 1.U(1.W))
  val lfsrVictimOH = victimSimp(params.cache.ways-1,0) & ~(victimSimp >> 1)
  val lfsrVictimWay = OHToUInt(lfsrVictimOH)
  assert (!ren2 || victimLTE(0) === 1.U)
  assert (!ren2 || ((victimSimp >> 1) & ~victimSimp) === 0.U) // monotone
  assert (!ren2 || PopCount(lfsrVictimOH) === 1.U)

  val victimWayOH = if (params.micro.victimPolicy == "coherency") {
    // Tier each way by eviction cost (lower = cheaper):
    //   0 INVALID; 1 valid+no-clients+clean; 2 valid+no-clients+dirty;
    //   3 valid+clients+clean; 4 valid+clients+dirty.
    val tiers = VecInit(ways.map { w =>
      val invalid   = w.state === INVALID
      val noClients = !w.clients.orR
      Mux(invalid,                   0.U(3.W),
        Mux(noClients && !w.dirty,   1.U(3.W),
          Mux(noClients,             2.U(3.W),
            Mux(!w.dirty,            3.U(3.W),
                                     4.U(3.W)))))
    })
    val minTier = tiers.reduceTree((a, b) => Mux(a < b, a, b))
    val candidatesOH = Cat(tiers.map(_ === minTier).reverse)
    // Pick the candidate nearest (cyclically) to lfsrVictimWay, so that within
    // a tier the choice is randomized rather than always biased to way 0.
    val n = params.cache.ways
    val doubled = Cat(candidatesOH, candidatesOH)
    val rotated = (doubled >> lfsrVictimWay)(n-1, 0)
    val firstOH = PriorityEncoderOH(rotated)
    val doubledFirst = Cat(firstOH, firstOH)
    (doubledFirst << lfsrVictimWay)(2*n-1, n)
  } else {
    lfsrVictimOH
  }
  val victimWay = OHToUInt(victimWayOH)
  assert (!ren2 || PopCount(victimWayOH) === 1.U)

  val setQuash = bypass_valid && bypass.set === set
  val tagMatch = bypass.data.tag === tag
  val wayMatch = bypass.way === victimWay
  val hits = Cat(ways.zipWithIndex.map { case (w, i) =>
    w.tag === tag && w.state =/= INVALID && (!setQuash || i.U =/= bypass.way)
  }.reverse)
  val hit = hits.orR

  io.result.valid := ren2
  io.result.bits.viewAsSupertype(chiselTypeOf(bypass.data)) := Mux(hit, Mux1H(hits, ways), Mux(setQuash && (tagMatch || wayMatch), bypass.data, Mux1H(victimWayOH, ways)))
  io.result.bits.hit := hit || (setQuash && tagMatch && bypass.data.state =/= INVALID)
  io.result.bits.way := Mux(hit, OHToUInt(hits), Mux(setQuash && tagMatch, bypass.way, victimWay))

  params.ccover(ren2 && setQuash && tagMatch, "DIRECTORY_HIT_BYPASS", "Bypassing write to a directory hit")
  params.ccover(ren2 && setQuash && !tagMatch && wayMatch, "DIRECTORY_EVICT_BYPASS", "Bypassing a write to a directory eviction")

  def json: String = s"""{"clients":${params.clientBits},"mem":"${cc_dir.pathName}","clean":"${wipeDone.pathName}"}"""
}
