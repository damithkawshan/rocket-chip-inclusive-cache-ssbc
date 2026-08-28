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
import freechips.rocketchip.util.DescribedSRAM

import scala.math.{max, min}

abstract class BankedStoreAddress(val inner: Boolean, params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val noop = Bool() // do not actually use the SRAMs, just block their use
  val way  = UInt(params.wayBits.W)
  val set  = UInt(params.setBits.W)
  val beat = UInt((if (inner) params.innerBeatBits else params.outerBeatBits).W)
  val mask = UInt((if (inner) params.innerMaskBits else params.outerMaskBits).W)
  // SBC (003 Stage 1), sim-only: the block this port BELIEVES it is touching, as Cat(tag, homeSet).
  // `way`/`set` say WHERE in the SRAM; this says WHAT the port thinks lives there. A wrong-row access
  // is exactly a disagreement between the two. Option => the field does not exist when sbcShadow=false.
  val shadowAddr = if (params.micro.sbcShadow) Some(UInt((params.tagBits + params.setBits).W)) else None
  // SBC (003) diagnostic: 0 = ordinary port, 1 = SCU migration copy, 2 = SCU repatriation copy. The
  // two SCU jobs use the same port, and telling them apart is what attributes a shadow firing.
  val shadowKind = if (params.micro.sbcShadow) Some(UInt(2.W)) else None
}

trait BankedStoreRW
{
  val write = Bool()
}

class BankedStoreOuterAddress(params: InclusiveCacheParameters) extends BankedStoreAddress(false, params)
class BankedStoreInnerAddress(params: InclusiveCacheParameters) extends BankedStoreAddress(true, params)
class BankedStoreInnerAddressRW(params: InclusiveCacheParameters) extends BankedStoreInnerAddress(params) with BankedStoreRW

abstract class BankedStoreData(val inner: Boolean, params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val data = UInt(((if (inner) params.inner.manager.beatBytes else params.outer.manager.beatBytes)*8).W)
}

class BankedStoreOuterData(params: InclusiveCacheParameters) extends BankedStoreData(false, params)
class BankedStoreInnerData(params: InclusiveCacheParameters) extends BankedStoreData(true,  params)
class BankedStoreInnerPoison(params: InclusiveCacheParameters) extends BankedStoreInnerData(params)
class BankedStoreOuterPoison(params: InclusiveCacheParameters) extends BankedStoreOuterData(params)
class BankedStoreInnerDecoded(params: InclusiveCacheParameters) extends BankedStoreInnerData(params)
class BankedStoreOuterDecoded(params: InclusiveCacheParameters) extends BankedStoreOuterData(params)

class BankedStore(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val sinkC_adr = Flipped(Decoupled(new BankedStoreInnerAddress(params)))
    val sinkC_dat = Flipped(new BankedStoreInnerPoison(params))
    val sinkD_adr = Flipped(Decoupled(new BankedStoreOuterAddress(params)))
    val sinkD_dat = Flipped(new BankedStoreOuterPoison(params))
    val sourceC_adr = Flipped(Decoupled(new BankedStoreOuterAddress(params)))
    val sourceC_dat = new BankedStoreOuterDecoded(params)
    val sourceD_radr = Flipped(Decoupled(new BankedStoreInnerAddress(params)))
    val sourceD_rdat = new BankedStoreInnerDecoded(params)
    val sourceD_wadr = Flipped(Decoupled(new BankedStoreInnerAddress(params)))
    val sourceD_wdat = Flipped(new BankedStoreInnerPoison(params))
    // SBC Phase 1: set-to-set copy ports (lowest priority); driven by SetCopyUnit
    val sourceCopy_radr = Flipped(Decoupled(new BankedStoreInnerAddress(params)))
    val sourceCopy_rdat = new BankedStoreInnerDecoded(params)
    val sourceCopy_wadr = Flipped(Decoupled(new BankedStoreInnerAddress(params)))
    val sourceCopy_wdat = Flipped(new BankedStoreInnerPoison(params))
  })

  // SBC (003 Stage 1): the shadow address model. One entry per (set,way): what the last writer said
  // it was putting there. Every reader checks its own belief against it. A `valid` bit keeps the
  // check quiet until a line's data has actually been written once.
  if (params.micro.sbcShadow) {
    val shEntries = params.cache.sets * params.cache.ways
    val shValid = RegInit(VecInit(Seq.fill(shEntries)(false.B)))
    val shAddr  = Reg(Vec(shEntries, UInt((params.tagBits + params.setBits).W)))
    // Who wrote it and when. A mismatch without these is a symptom; with them it names the port and
    // the gap, which is the difference between a lead and a diagnosis.
    val shWho   = Reg(Vec(shEntries, UInt(2.W)))   // 0=sinkC 1=sinkD 2=sourceD_w 3=copy_w
    val shKind  = Reg(Vec(shEntries, UInt(2.W)))   // the writer's SCU job kind (0 if not the SCU)
    val shTime  = Reg(Vec(shEntries, UInt(32.W)))
    val shClock = RegInit(0.U(32.W)); shClock := shClock + 1.U
    def shIdx(b: BankedStoreAddress) = Cat(b.way, b.set)
    def shWrite(b: DecoupledIO[_ <: BankedStoreAddress], who: String, id: Int): Unit = {
      when (b.fire && !b.bits.noop) {
        shValid(shIdx(b.bits)) := true.B
        shAddr (shIdx(b.bits)) := b.bits.shadowAddr.get
        shWho  (shIdx(b.bits)) := id.U
        shKind (shIdx(b.bits)) := b.bits.shadowKind.get
        shTime (shIdx(b.bits)) := shClock
      }
    }
    def shRead(b: DecoupledIO[_ <: BankedStoreAddress], who: String): Unit = {
      when (b.fire && !b.bits.noop) {
        // The values are in the message on purpose: a bare assert here costs a full re-run to learn
        // WHICH row and which two addresses disagreed, and the trace is ~300MB.
        assert (!shValid(shIdx(b.bits)) || shAddr(shIdx(b.bits)) === b.bits.shadowAddr.get,
                cf"SBC shadow: ${who} touched the wrong row: set=${b.bits.set}%d way=${b.bits.way}%d " +
                cf"stored=0x${shAddr(shIdx(b.bits))}%x believed=0x${b.bits.shadowAddr.get}%x " +
                cf"lastWriter=${shWho(shIdx(b.bits))}%d(0=sinkC,1=sinkD,2=sourceDw,3=copyw) " +
                cf"wKind=${shKind(shIdx(b.bits))}%d rKind=${b.bits.shadowKind.get}%d(0=other,1=mig,2=sec) " +
                cf"writtenAt=${shTime(shIdx(b.bits))}%d now=${shClock}%d")
      }
    }
    shWrite(io.sinkC_adr,       "sinkC",     0)
    shWrite(io.sinkD_adr,       "sinkD",     1)
    shWrite(io.sourceD_wadr,    "sourceD_w", 2)
    shWrite(io.sourceCopy_wadr, "copy_w",    3)
    shRead (io.sourceC_adr,     "sourceC")
    shRead (io.sourceD_radr,    "sourceD_r")
    shRead (io.sourceCopy_radr, "copy_r")
  }

  val innerBytes = params.inner.manager.beatBytes
  val outerBytes = params.outer.manager.beatBytes
  val rowBytes = params.micro.portFactor * max(innerBytes, outerBytes)
  require (rowBytes < params.cache.sizeBytes)
  val rowEntries = params.cache.sizeBytes / rowBytes
  val rowBits = log2Ceil(rowEntries)
  val numBanks = rowBytes / params.micro.writeBytes
  val codeBits = 8*params.micro.writeBytes

  val cc_banks = Seq.tabulate(numBanks) {
    i =>
      DescribedSRAM(
        name = s"cc_banks_$i",
        desc = "Banked Store",
        size = rowEntries,
        data = UInt(codeBits.W)
      )
  }
  // These constraints apply on the port priorities:
  //  sourceC > sinkD     outgoing Release > incoming Grant      (we start eviction+refill concurrently)
  //  sinkC > sourceC     incoming ProbeAck > outgoing ProbeAck  (we delay probeack writeback by 1 cycle for QoR)
  //  sinkC > sourceDr    incoming ProbeAck > SourceD read       (we delay probeack writeback by 1 cycle for QoR)
  //  sourceDw > sourceDr modified data visible on next cycle    (needed to ensure SourceD forward progress)
  //  sinkC > sourceC     inner ProbeAck > outer ProbeAck        (make wormhole routing possible [not yet implemented])
  //  sinkC&D > sourceD*  beat arrival > beat read|update        (make wormhole routing possible [not yet implemented])

  // Combining these restrictions yields a priority scheme of:
  //  sinkC > sourceC > sinkD > sourceDw > sourceDr
  //          ^^^^^^^^^^^^^^^ outer interface

  // Requests have different port widths, but we don't want to allow cutting in line.
  // Suppose we have requests A > B > C requesting ports --A-, --BB, ---C.
  // The correct arbitration is to allow --A- only, not --AC.
  // Obviously --A-, BB--, ---C should still be resolved to BBAC.

  class Request extends Bundle {
    val wen      = Bool()
    val index    = UInt(rowBits.W)
    val bankSel  = UInt(numBanks.W)
    val bankSum  = UInt(numBanks.W) // OR of all higher priority bankSels
    val bankEn   = UInt(numBanks.W) // ports actually activated by request
    val data     = Vec(numBanks, UInt(codeBits.W))
  }

  def req[T <: BankedStoreAddress](b: DecoupledIO[T], write: Bool, d: UInt): Request = {
    val beatBytes = if (b.bits.inner) innerBytes else outerBytes
    val ports = beatBytes / params.micro.writeBytes
    val bankBits = log2Ceil(numBanks / ports)
    val words = Seq.tabulate(ports) { i =>
      val data = d((i + 1) * 8 * params.micro.writeBytes - 1, i * 8 * params.micro.writeBytes)
      data
    }
    val a = if (params.cache.blockBytes == beatBytes) Cat(b.bits.way, b.bits.set) else Cat(b.bits.way, b.bits.set, b.bits.beat)
    val m = b.bits.mask
    val out = Wire(new Request)

    val select = UIntToOH(a(bankBits-1, 0), numBanks/ports)
    val ready  = Cat(Seq.tabulate(numBanks/ports) { i => !(out.bankSum((i+1)*ports-1, i*ports) & m).orR } .reverse)
    b.ready := ready(a(bankBits-1, 0))

    out.wen      := write
    out.index    := a >> bankBits
    out.bankSel  := Mux(b.valid, FillInterleaved(ports, select) & Fill(numBanks/ports, m), 0.U)
    out.bankEn   := Mux(b.bits.noop, 0.U, out.bankSel & FillInterleaved(ports, ready))
    out.data     := Seq.fill(numBanks/ports) { words }.flatten

    out
  }

  val innerData = 0.U((8*innerBytes).W)
  val outerData = 0.U((8*outerBytes).W)
  val W = true.B
  val R = false.B

  val sinkC_req    = req(io.sinkC_adr,    W, io.sinkC_dat.data)
  val sinkD_req    = req(io.sinkD_adr,    W, io.sinkD_dat.data)
  val sourceC_req  = req(io.sourceC_adr,  R, outerData)
  val sourceD_rreq = req(io.sourceD_radr, R, innerData)
  val sourceD_wreq = req(io.sourceD_wadr, W, io.sourceD_wdat.data)
  val sourceCopy_rreq = req(io.sourceCopy_radr, R, innerData)
  val sourceCopy_wreq = req(io.sourceCopy_wadr, W, io.sourceCopy_wdat.data)

  // See the comments above for why this prioritization is used.
  // SBC copy ports are appended last (lowest priority); write before read mirrors sourceDw > sourceDr.
  val reqs = Seq(sinkC_req, sourceC_req, sinkD_req, sourceD_wreq, sourceD_rreq, sourceCopy_wreq, sourceCopy_rreq)

  // Connect priorities; note that even if a request does not go through due to failing
  // to obtain a needed subbank, it still blocks overlapping lower priority requests.
  reqs.foldLeft(0.U) { case (sum, req) =>
    req.bankSum := sum
    req.bankSel | sum
  }
  // Access the banks
  val regout = VecInit(cc_banks.zipWithIndex.map { case (b, i) =>
    val en  = reqs.map(_.bankEn(i)).reduce(_||_)
    val sel = reqs.map(_.bankSel(i))
    val wen = PriorityMux(sel, reqs.map(_.wen))
    val idx = PriorityMux(sel, reqs.map(_.index))
    val data= PriorityMux(sel, reqs.map(_.data(i)))

    when (wen && en) { b.write(idx, data) }
    RegEnable(b.read(idx, !wen && en), RegNext(!wen && en))
  })

  val regsel_sourceC = RegNext(RegNext(sourceC_req.bankEn))
  val regsel_sourceD = RegNext(RegNext(sourceD_rreq.bankEn))
  val regsel_sourceCopy = RegNext(RegNext(sourceCopy_rreq.bankEn))

  val decodeC = regout.zipWithIndex.map {
    case (r, i) => Mux(regsel_sourceC(i), r, 0.U)
  }.grouped(outerBytes/params.micro.writeBytes).toList.transpose.map(s => s.reduce(_|_))

  io.sourceC_dat.data := Cat(decodeC.reverse)

  val decodeD = regout.zipWithIndex.map {
    // Intentionally not Mux1H and/or an indexed-mux b/c we want it 0 when !sel to save decode power
    case (r, i) => Mux(regsel_sourceD(i), r, 0.U)
  }.grouped(innerBytes/params.micro.writeBytes).toList.transpose.map(s => s.reduce(_|_))

  io.sourceD_rdat.data := Cat(decodeD.reverse)

  val decodeCopy = regout.zipWithIndex.map {
    case (r, i) => Mux(regsel_sourceCopy(i), r, 0.U)
  }.grouped(innerBytes/params.micro.writeBytes).toList.transpose.map(s => s.reduce(_|_))

  io.sourceCopy_rdat.data := Cat(decodeCopy.reverse)

  private def banks = cc_banks.map("\"" + _.pathName + "\"").mkString(",")
  def json: String = s"""{"widthBytes":${params.micro.writeBytes},"mem":[${banks}]}"""
}
