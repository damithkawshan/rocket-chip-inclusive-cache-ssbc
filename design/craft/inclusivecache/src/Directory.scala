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
  // SBC: this line was spilled here from a foreign (home) set; its real home set is
  // AT[physicalSet].assocSet. Always false unless Set-Balancing migration is active.
  val displaced = Bool()
}

// SBC: result-aligned observation tap for the SetBalanceUnit (read-only, never affects datapath)
class DirectoryTap(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val set = UInt(params.setBits.W)
  val hit = Bool()
  val way = UInt(params.wayBits.W)
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
  // SBC Phase 1: when set, the victim selection returns an invalid way if one exists (used by the
  // migration destination probe). Baseline reads leave this false and get the LFSR victim.
  val preferInvalid = Bool()
  // SBC Phase 1: when set, prefer a migration-eligible way (valid, clean, no clients, not displaced)
  // as the victim (used by the migration source read). Baseline reads leave this false.
  val preferEvictable = Bool()
  // SBC: this read is cache-internal machinery (migrate probe), not a demand access. It must not
  // tag-match and must not reach the observation tap. Baseline reads leave this false.
  val internalRead = Bool()
  // SBC Phase 3: match a DISPLACED way by tag - the mirror of the normal hit, which excludes them.
  val secondarySearch = Bool()
}

class DirectoryResult(params: InclusiveCacheParameters) extends DirectoryEntry(params)
{
  val hit = Bool()
  val way = UInt(params.wayBits.W)
  // SBC Phase 3 (secondary search): the mirror of `hit` - a DISPLACED way whose tag matches. Only
  // meaningful when the read asked for it; constant false on every other read.
  val secondaryHit   = Bool()
  val secondaryWay   = UInt(params.wayBits.W)
  val secondaryEntry = new DirectoryEntry(params)
  // SBC Phase 3 (teardown): does this set still hold a displaced way OTHER than secondaryWay? Read as
  // "once the way this read identified is vacated, is the parked pool empty?"
  val displacedOther = Bool()
}

class Directory(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val write  = Flipped(Decoupled(new DirectoryWrite(params)))
    val read   = Flipped(Valid(new DirectoryRead(params))) // sees same-cycle write
    val result = Valid(new DirectoryResult(params))
    val ready  = Bool() // reset complete; can enable access
    val tap    = Valid(new DirectoryTap(params)) // SBC: result-aligned observation tap
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
  val preferInvalid = params.dirReg(RegEnable(io.read.bits.preferInvalid, ren), ren1)
  val preferEvictable = params.dirReg(RegEnable(io.read.bits.preferEvictable, ren), ren1)
  val internalRead = params.dirReg(RegEnable(io.read.bits.internalRead, ren), ren1)
  val secondarySearch = params.dirReg(RegEnable(io.read.bits.secondarySearch, ren), ren1)

  val ways = regout.map(d => d.asTypeOf(new DirectoryEntry(params)))

  // Compute the victim way in case of an evicition
  val victimLFSR = random.LFSR(width = 16, params.dirReg(ren))(InclusiveCacheParameters.lfsrBits-1, 0)
  val victimSums = Seq.tabulate(params.cache.ways) { i => ((1 << InclusiveCacheParameters.lfsrBits)*i / params.cache.ways).U }
  val victimLTE  = Cat(victimSums.map { _ <= victimLFSR }.reverse)
  val victimSimp = Cat(0.U(1.W), victimLTE(params.cache.ways-1, 1), 1.U(1.W))
  val victimWayOHLFSR = victimSimp(params.cache.ways-1,0) & ~(victimSimp >> 1)
  // SBC: prefer invalid, else the plain LFSR victim (preferInvalid makes "destination set full?"
  // a precise test).
  val invalidWayOH   = Cat(ways.map(_.state === INVALID).reverse)
  val nonDisplacedOH = Cat(ways.map(!_.displaced).reverse)
  // SBC Phase 3: displaced ways compete for the LFSR victim like any other way. They used to be
  // masked out (`& nonDisplacedOH`), which quarantined them: unable to hit AND unable to be evicted,
  // so a partner set clogged and teardown could never fire. See TASK 001 Amendment 1 A3.
  val lfsrVictimOH   = victimWayOHLFSR
  // SBC Phase 1: a migration-eligible victim moves with no protocol work — valid, clean (no
  // writeback), no clients (no probe), not displaced. The migration source read prefers one.
  val evictableOH    = Cat(ways.map(w => w.state =/= INVALID && !w.displaced && !w.dirty && !w.clients.orR).reverse)
  // SBC: displaced-reclaim backstop. The LFSR tier above is always one-hot, so these last two Mux
  // arms are unreachable today; they stay as the guarantee that victimWayOH can never be zero and
  // trip the PopCount assert below. Safe either way: a displaced entry is clean + client-free by
  // construction (the MSHR install invariant), so the MSHR drops it silently (no writeback, no probe).
  val displacedOH = ~nonDisplacedOH
  val victimWayOH = Mux(preferInvalid && invalidWayOH.orR, PriorityEncoderOH(invalidWayOH),
                    Mux(preferEvictable && evictableOH.orR, PriorityEncoderOH(evictableOH),
                    Mux(lfsrVictimOH.orR, lfsrVictimOH,
                    Mux(nonDisplacedOH.orR, PriorityEncoderOH(nonDisplacedOH),
                    PriorityEncoderOH(displacedOH)))))
  val victimWay = OHToUInt(victimWayOH)
  assert (!ren2 || victimLTE(0) === 1.U)
  assert (!ren2 || ((victimSimp >> 1) & ~victimSimp) === 0.U) // monotone
  assert (!ren2 || PopCount(victimWayOH) === 1.U)

  val setQuash = bypass_valid && bypass.set === set
  val tagMatch = !internalRead && bypass.data.tag === tag
  val wayMatch = bypass.way === victimWay

  val hits = Cat(ways.zipWithIndex.map { case (w, i) =>
    !internalRead && w.tag === tag && w.state =/= INVALID && !w.displaced && (!setQuash || i.U =/= bypass.way)
  }.reverse)
  val hit = hits.orR

  // SBC Phase 3: secondary search - the exact mirror of `hits`. Displaced ways are INCLUDED and
  // native ones excluded. Under strict 1:1 pinning every displaced way in the partner set belongs to
  // the searching set, so a tag match here IS the line we are looking for.
  val secHits = Cat(ways.zipWithIndex.map { case (w, i) =>
    secondarySearch && w.tag === tag && w.state =/= INVALID && w.displaced && (!setQuash || i.U =/= bypass.way)
  }.reverse)
  // A displaced entry written this cycle is not in `ways` yet. Missing it would let the refill install
  // a second copy of the same line - the stale-twin hole - so match the write bypass too.
  val secBypassHit = secondarySearch && setQuash && bypass.data.tag === tag &&
                     bypass.data.state =/= INVALID && bypass.data.displaced
  // Displaced ways still parked here after the matched way is vacated (the teardown test).
  val displacedValidOH = Cat(ways.zipWithIndex.map { case (w, i) =>
    w.state =/= INVALID && w.displaced && (!setQuash || i.U =/= bypass.way)
  }.reverse)
  // SBC Phase 3: two parked copies of one line is the stale-twin hole - the search would serve a copy
  // another path can still write. Mux1H(secHits) needs one-hot anyway.
  assert (!ren2 || PopCount(secHits) <= 1.U, "SBC: two displaced copies of the same line in one set")
  // SBC: `displaced => clean + client-free` is load-bearing - a parked line sits at the wrong physical
  // set, so it can be neither written back nor probed. Checked on read, not only at install.
  val displacedOwedOH = Cat(ways.map(w => w.dirty || w.clients.orR).reverse)
  assert (!ren2 || (displacedValidOH & displacedOwedOH) === 0.U,
          "SBC: displaced way is dirty or client-held (its address cannot be reconstructed)")

  io.result.valid := ren2
  io.result.bits.viewAsSupertype(chiselTypeOf(bypass.data)) := Mux(hit, Mux1H(hits, ways), Mux(setQuash && (tagMatch || wayMatch), bypass.data, Mux1H(victimWayOH, ways)))
  io.result.bits.hit := hit || (setQuash && tagMatch && bypass.data.state =/= INVALID && !bypass.data.displaced)
  io.result.bits.way := Mux(hit, OHToUInt(hits), Mux(setQuash && tagMatch, bypass.way, victimWay))
  io.result.bits.secondaryHit   := secHits.orR || secBypassHit
  io.result.bits.secondaryWay   := Mux(secHits.orR, OHToUInt(secHits), bypass.way)
  io.result.bits.secondaryEntry := Mux(secHits.orR, Mux1H(secHits, ways), bypass.data)
  io.result.bits.displacedOther := (displacedValidOH & ~secHits).orR ||
                                   (setQuash && bypass.data.state =/= INVALID && bypass.data.displaced && !secBypassHit)

  // SBC observation tap: aligned to the result (uses the already result-aligned `set` wire so the
  // SetBalanceUnit gets a correct (set, hit) pair without re-deriving the read->result latency).
  io.tap.valid    := ren2 && !internalRead
  io.tap.bits.set := set
  io.tap.bits.hit := io.result.bits.hit
  io.tap.bits.way := io.result.bits.way

  // SBC Phase 1 debug: trace every preferEvictable read so we can see whether the flag arrives and
  // whether the set held an eligible (clean, client-free) way for it to pick.
  if (params.micro.sbcDebug) {
    when (ren2 && preferEvictable) {
      printf(p"[SBC] DIR-EVICT set=${set} evictableAvail=${evictableOH.orR} hit=${io.result.bits.hit} victimWay=${victimWay}\n")
    }
  }

  params.ccover(ren2 && setQuash && tagMatch, "DIRECTORY_HIT_BYPASS", "Bypassing write to a directory hit")
  params.ccover(ren2 && setQuash && !tagMatch && wayMatch, "DIRECTORY_EVICT_BYPASS", "Bypassing a write to a directory eviction")

  def json: String = s"""{"clients":${params.clientBits},"mem":"${cc_dir.pathName}","clean":"${wipeDone.pathName}"}"""
}
