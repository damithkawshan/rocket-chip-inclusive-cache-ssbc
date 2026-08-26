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
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import chisel3.experimental.dataview._

class InclusiveCacheBankScheduler(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val in = Flipped(TLBundle(params.inner.bundle))
    val out = TLBundle(params.outer.bundle)
    // Way permissions
    val ways = Flipped(Vec(params.allClients, UInt(params.cache.ways.W)))
    val divs = Flipped(Vec(params.allClients, UInt((InclusiveCacheParameters.lfsrBits + 1).W)))
    // Control port
    val req = Flipped(Decoupled(new SinkXRequest(params)))
    val resp = Decoupled(new SourceXRequest(params))
    // SBC MMIO: SW-selected set index in, read-only stats out
    val sbcSatReadSet = Input(UInt(params.setBits.W))
    val sbcStats      = Output(new SBCStats(params.setBits, params.micro.satCounterBits))
    // SBC MMIO: SW arm pulse in (a write to SBC_BalanceSet)
    val sbcBalanceSet = Flipped(Valid(UInt(params.setBits.W)))
    // SBC MMIO: SW reset pulse in (a write to SBC_Reset)
    val sbcReset      = Input(Bool())
  })

  val sourceA = Module(new SourceA(params))
  val sourceB = Module(new SourceB(params))
  val sourceC = Module(new SourceC(params))
  val sourceD = Module(new SourceD(params))
  val sourceE = Module(new SourceE(params))
  val sourceX = Module(new SourceX(params))

  io.out.a <> sourceA.io.a
  io.out.c <> sourceC.io.c
  io.out.e <> sourceE.io.e
  io.in.b <> sourceB.io.b
  io.in.d <> sourceD.io.d
  io.resp <> sourceX.io.x

  val sinkA = Module(new SinkA(params))
  val sinkC = Module(new SinkC(params))
  val sinkD = Module(new SinkD(params))
  val sinkE = Module(new SinkE(params))
  val sinkX = Module(new SinkX(params))

  sinkA.io.a <> io.in.a
  sinkC.io.c <> io.in.c
  sinkE.io.e <> io.in.e
  sinkD.io.d <> io.out.d

  // SBC Phase 2: the control port (io.req) carries only flushes now. Migration is the demand
  // MSHR's own eviction work (no injected requester), so the control port is wired straight
  // through to sinkX exactly as in the baseline.
  sinkX.io.x <> io.req

  io.out.b.ready := true.B // disconnected

  val directory = Module(new Directory(params))
  val bankedStore = Module(new BankedStore(params))
  val setCopyUnit = Module(new SetCopyUnit(params))
  val requests = Module(new ListBuffer(ListBufferParameters(new QueuedRequest(params), 3*params.mshrs, params.secondary, false)))
  val mshrs = Seq.fill(params.mshrs) { Module(new MSHR(params)) }
  val abc_mshrs = mshrs.init.init
  val bc_mshr = mshrs.init.last
  val c_mshr = mshrs.last
  val nestedwb = Wire(new NestedWriteback(params))

  // Deliver messages from Sinks to MSHRs
  mshrs.zipWithIndex.foreach { case (m, i) =>
    m.io.sinkc.valid := sinkC.io.resp.valid && sinkC.io.resp.bits.set === m.io.status.bits.set
    m.io.sinkd.valid := sinkD.io.resp.valid && sinkD.io.resp.bits.source === i.U
    m.io.sinke.valid := sinkE.io.resp.valid && sinkE.io.resp.bits.sink   === i.U
    m.io.sinkc.bits := sinkC.io.resp.bits
    m.io.sinkd.bits := sinkD.io.resp.bits
    m.io.sinke.bits := sinkE.io.resp.bits
    m.io.nestedwb := nestedwb
    // SBC Phase 1: deliver the SetCopyUnit done pulse to its owning MSHR (routed by mshrId)
    m.io.copy_done := setCopyUnit.io.done && setCopyUnit.io.doneId === i.U
  }

  // If the pre-emption BC or C MSHR have a matching set, the normal MSHR must be blocked
  val mshr_stall_abc = abc_mshrs.map { m =>
    (bc_mshr.io.status.valid && m.io.status.bits.set === bc_mshr.io.status.bits.set) ||
    ( c_mshr.io.status.valid && m.io.status.bits.set ===  c_mshr.io.status.bits.set)
  }
  val mshr_stall_bc =
    c_mshr.io.status.valid && bc_mshr.io.status.bits.set === c_mshr.io.status.bits.set
  val mshr_stall_c = false.B
  val mshr_stall = mshr_stall_abc :+ mshr_stall_bc :+ mshr_stall_c


  val stall_abc = (mshr_stall_abc zip abc_mshrs) map { case (s, m) => s && m.io.status.valid }
  if (!params.lastLevel || !params.firstLevel)
    params.ccover(stall_abc.reduce(_||_), "SCHEDULER_ABC_INTERLOCK", "ABC MSHR interlocked due to pre-emption")
  if (!params.lastLevel)
    params.ccover(mshr_stall_bc && bc_mshr.io.status.valid, "SCHEDULER_BC_INTERLOCK", "BC MSHR interlocked due to pre-emption")

  // Consider scheduling an MSHR only if all the resources it requires are available
  val mshr_request = Cat((mshrs zip mshr_stall).map { case (m, s) =>
    m.io.schedule.valid && !s &&
      (sourceA.io.req.ready || !m.io.schedule.bits.a.valid) &&
      (sourceB.io.req.ready || !m.io.schedule.bits.b.valid) &&
      (sourceC.io.req.ready || !m.io.schedule.bits.c.valid) &&
      (sourceD.io.req.ready || !m.io.schedule.bits.d.valid) &&
      (sourceE.io.req.ready || !m.io.schedule.bits.e.valid) &&
      (sourceX.io.req.ready || !m.io.schedule.bits.x.valid) &&
      (directory.io.write.ready || !m.io.schedule.bits.dir.valid) &&
      (setCopyUnit.io.idle || !m.io.schedule.bits.copy.valid)
  }.reverse)

  // Round-robin arbitration of MSHRs
  val robin_filter = RegInit(0.U(params.mshrs.W))
  val robin_request = Cat(mshr_request, mshr_request & robin_filter)
  val mshr_selectOH2 = ~(leftOR(robin_request) << 1) & robin_request
  val mshr_selectOH = mshr_selectOH2(2*params.mshrs-1, params.mshrs) | mshr_selectOH2(params.mshrs-1, 0)
  val mshr_select = OHToUInt(mshr_selectOH)
  val schedule = Mux1H(mshr_selectOH, mshrs.map(_.io.schedule.bits))
  val scheduleTag = Mux1H(mshr_selectOH, mshrs.map(_.io.status.bits.tag))
  val scheduleSet = Mux1H(mshr_selectOH, mshrs.map(_.io.status.bits.set))

  // When an MSHR wins the schedule, it has lowest priority next time
  when (mshr_request.orR) { robin_filter := ~rightOR(mshr_selectOH) }

  // Fill in which MSHR sends the request
  schedule.a.bits.source := mshr_select
  schedule.c.bits.source := Mux(schedule.c.bits.opcode(1), mshr_select, 0.U) // only set for Release[Data] not ProbeAck[Data]
  schedule.d.bits.sink   := mshr_select

  sourceA.io.req.valid := schedule.a.valid
  sourceB.io.req.valid := schedule.b.valid
  sourceC.io.req.valid := schedule.c.valid
  sourceD.io.req.valid := schedule.d.valid
  sourceE.io.req.valid := schedule.e.valid
  sourceX.io.req.valid := schedule.x.valid

  sourceA.io.req.bits.viewAsSupertype(chiselTypeOf(schedule.a.bits)) := schedule.a.bits
  sourceB.io.req.bits.viewAsSupertype(chiselTypeOf(schedule.b.bits)) := schedule.b.bits
  sourceC.io.req.bits.viewAsSupertype(chiselTypeOf(schedule.c.bits)) := schedule.c.bits
  sourceD.io.req.bits.viewAsSupertype(chiselTypeOf(schedule.d.bits)) := schedule.d.bits
  sourceE.io.req.bits.viewAsSupertype(chiselTypeOf(schedule.e.bits)) := schedule.e.bits
  sourceX.io.req.bits.viewAsSupertype(chiselTypeOf(schedule.x.bits)) := schedule.x.bits

  directory.io.write.valid := schedule.dir.valid
  directory.io.write.bits.viewAsSupertype(chiselTypeOf(schedule.dir.bits)) := schedule.dir.bits

  // Forward meta-data changes from nested transaction completion
  val select_c  = mshr_selectOH(params.mshrs-1)
  val select_bc = mshr_selectOH(params.mshrs-2)
  nestedwb.set   := Mux(select_c, c_mshr.io.status.bits.set, bc_mshr.io.status.bits.set)
  nestedwb.tag   := Mux(select_c, c_mshr.io.status.bits.tag, bc_mshr.io.status.bits.tag)
  nestedwb.b_toN       := select_bc && bc_mshr.io.schedule.bits.dir.valid && bc_mshr.io.schedule.bits.dir.bits.data.state === MetaData.INVALID
  nestedwb.b_toB       := select_bc && bc_mshr.io.schedule.bits.dir.valid && bc_mshr.io.schedule.bits.dir.bits.data.state === MetaData.BRANCH
  nestedwb.b_clr_dirty := select_bc && bc_mshr.io.schedule.bits.dir.valid
  nestedwb.c_set_dirty := select_c  &&  c_mshr.io.schedule.bits.dir.valid && c_mshr.io.schedule.bits.dir.bits.data.dirty

  // Pick highest priority request
  val request = Wire(Decoupled(new FullRequest(params)))
  request.valid := directory.io.ready && (sinkA.io.req.valid || sinkX.io.req.valid || sinkC.io.req.valid)
  request.bits := Mux(sinkC.io.req.valid, sinkC.io.req.bits,
                  Mux(sinkX.io.req.valid, sinkX.io.req.bits, sinkA.io.req.bits))
  sinkC.io.req.ready := directory.io.ready && request.ready
  sinkX.io.req.ready := directory.io.ready && request.ready && !sinkC.io.req.valid
  sinkA.io.req.ready := directory.io.ready && request.ready && !sinkC.io.req.valid && !sinkX.io.req.valid

  // If no MSHR has been assigned to this set, we need to allocate one
  val setMatches = Cat(mshrs.map { m => m.io.status.valid && m.io.status.bits.set === request.bits.set }.reverse)
  val alloc = !setMatches.orR // NOTE: no matches also means no BC or C pre-emption on this set
  // SBC Phase 2: migrate advice for the current allocating request. Driven by the SBC block below
  // (defaults keep the baseline path untouched when set-balancing is disabled).
  val adviceMigrate = WireInit(false.B)
  // SBC Phase 2.5b (late destination binding): the destination is published as a LIVE offer and taken
  // by the MSHR in the cycle it decides, instead of being latched at allocate and used cycles later.
  //   dstOffer - the candidate this cycle, already filtered (cold, and not owned by a live MSHR).
  //              The "no other migration in flight" term is applied PER MSHR below, because the
  //              asking MSHR must not be blocked by its own in-flight state.
  val dstOfferValid = WireInit(false.B)
  val dstOfferSet   = WireInit(0.U(params.setBits.W))
  // SBC Phase 3: "my set is a paired source, and this is my partner", latched by the allocating MSHR.
  // Lookup side only - staleness is harmless here (a search in the wrong set finds nothing).
  val pairInfoValid = WireInit(false.B)
  val pairInfoSet   = WireInit(0.U(params.setBits.W))
  // SBC Phase 2 (dst-collision fix): fence a live migration's destination set. A request whose set is
  // a migrant's dstSet must neither be consumed (request.ready) NOR allocate an MSHR / read the
  // directory for one — it is held at the sink until the migration retires and dstValid clears.
  // Gating request.ready alone is insufficient: the fresh-allocation path still committed. So the same
  // condition also gates allocation via `allocReady`. Bit-exact when SBC off (dstSetConflict is const-false).
  // SBC Phase 2.5b: the second term is the same-cycle claim. A migrant picks its destination and
  // claims it in one cycle, so the fence sees the claim immediately and the [pick -> fence] window is
  // zero. This is what replaces the migTokenPending/migPendCtr timer that used to paper over it.
  val dstSetConflict = mshrs.map { m =>
    (m.io.status.valid && m.io.status.bits.dstValid && m.io.status.bits.dstSet === request.bits.set) ||
    (m.io.dstClaim.valid && m.io.dstClaim.bits === request.bits.set)
  }.reduce(_ || _)
  val allocReady = alloc && !dstSetConflict
  // SBC Phase 2: one-migration-per-bank token — a migration is in flight while any MSHR holds a
  // destination reservation (dstValid).
  // SBC Phase 2.5: the token must also cover the deferred-probe window. A migrant that is waiting on
  // its eviction probe has not reserved a destination yet (dstValid is still false), so without
  // migPending a second MSHR could take the token and start its own migration inside that window,
  // breaking the one-migration-per-bank assumption the Phase-2 fences were designed under.
  // SBC Phase 3: one migration per bank, so this is one-hot. Also used per-MSHR in the offer fanout
  // below (that used to be a separate `migBusy` wire spelling the same expression).
  val migrantOH    = VecInit(mshrs.map(m => m.io.status.valid &&
                                    (m.io.status.bits.dstValid || m.io.status.bits.migPending))).asUInt
  val anyMigrating = migrantOH.orR
  assert (PopCount(migrantOH) <= 1.U, "SBC: more than one migration in flight")

  // If a same-set MSHR says that requests of this type must be blocked (for bounded time), do it
  val blockB = Mux1H(setMatches, mshrs.map(_.io.status.bits.blockB)) && request.bits.prio(1)
  val blockC = Mux1H(setMatches, mshrs.map(_.io.status.bits.blockC)) && request.bits.prio(2)
  // If a same-set MSHR says that requests of this type must be handled out-of-band, use special BC|C MSHR
  // ... these special MSHRs interlock the MSHR that said it should be pre-empted.
  val nestB  = Mux1H(setMatches, mshrs.map(_.io.status.bits.nestB))  && request.bits.prio(1)
  val nestC  = Mux1H(setMatches, mshrs.map(_.io.status.bits.nestC))  && request.bits.prio(2)
  // Prevent priority inversion; we may not queue to MSHRs beyond our level
  val prioFilter = Cat(request.bits.prio(2), !request.bits.prio(0), ~0.U((params.mshrs-2).W))
  val lowerMatches = setMatches & prioFilter
  // If we match an MSHR <= our priority that neither blocks nor nests us, queue to it.
  val queue = lowerMatches.orR && !nestB && !nestC && !blockB && !blockC

  if (!params.lastLevel) {
    params.ccover(request.valid && blockB, "SCHEDULER_BLOCKB", "Interlock B request while resolving set conflict")
    params.ccover(request.valid && nestB,  "SCHEDULER_NESTB", "Priority escalation from channel B")
  }
  if (!params.firstLevel) {
    params.ccover(request.valid && blockC, "SCHEDULER_BLOCKC", "Interlock C request while resolving set conflict")
    params.ccover(request.valid && nestC,  "SCHEDULER_NESTC", "Priority escalation from channel C")
  }
  params.ccover(request.valid && queue, "SCHEDULER_SECONDARY", "Enqueue secondary miss")

  // It might happen that lowerMatches has >1 bit if the two special MSHRs are in-use
  // We want to Q to the highest matching priority MSHR.
  val lowerMatches1 =
    Mux(lowerMatches(params.mshrs-1), 1.U << (params.mshrs-1),
    Mux(lowerMatches(params.mshrs-2), 1.U << (params.mshrs-2),
    lowerMatches))

  // If this goes to the scheduled MSHR, it may need to be bypassed
  // Alternatively, the MSHR may be refilled from a request queued in the ListBuffer
  val selected_requests = Cat(mshr_selectOH, mshr_selectOH, mshr_selectOH) & requests.io.valid
  val a_pop = selected_requests((0 + 1) * params.mshrs - 1, 0 * params.mshrs).orR
  val b_pop = selected_requests((1 + 1) * params.mshrs - 1, 1 * params.mshrs).orR
  val c_pop = selected_requests((2 + 1) * params.mshrs - 1, 2 * params.mshrs).orR
  val bypassMatches = (mshr_selectOH & lowerMatches1).orR &&
                      Mux(c_pop || request.bits.prio(2), !c_pop, Mux(b_pop || request.bits.prio(1), !b_pop, !a_pop))
  val may_pop = a_pop || b_pop || c_pop
  val bypass = request.valid && queue && bypassMatches
  val will_reload = schedule.reload && (may_pop || bypass)
  val will_pop = schedule.reload && may_pop && !bypass

  params.ccover(mshr_selectOH.orR && bypass, "SCHEDULER_BYPASS", "Bypass new request directly to conflicting MSHR")
  params.ccover(mshr_selectOH.orR && will_reload, "SCHEDULER_RELOAD", "Back-to-back service of two requests")
  params.ccover(mshr_selectOH.orR && will_pop, "SCHEDULER_POP", "Service of a secondary miss")

  // Repeat the above logic, but without the fan-in
  mshrs.zipWithIndex.foreach { case (m, i) =>
    val sel = mshr_selectOH(i)
    m.io.schedule.ready := sel
    val a_pop = requests.io.valid(params.mshrs * 0 + i)
    val b_pop = requests.io.valid(params.mshrs * 1 + i)
    val c_pop = requests.io.valid(params.mshrs * 2 + i)
    val bypassMatches = lowerMatches1(i) &&
                        Mux(c_pop || request.bits.prio(2), !c_pop, Mux(b_pop || request.bits.prio(1), !b_pop, !a_pop))
    val may_pop = a_pop || b_pop || c_pop
    val bypass = request.valid && queue && bypassMatches
    val will_reload = m.io.schedule.bits.reload && (may_pop || bypass)
    m.io.allocate.bits.viewAsSupertype(chiselTypeOf(requests.io.data)) := Mux(bypass, WireInit(new QueuedRequest(params), init = request.bits), requests.io.data)
    m.io.allocate.bits.set := m.io.status.bits.set
    m.io.allocate.bits.repeat := m.io.allocate.bits.tag === m.io.status.bits.tag
    m.io.allocate.valid := sel && will_reload
  }

  // SBC Phase 2: deliver migrate advice to every MSHR; each latches it only on its own allocate.
  // SBC Phase 2.5b: the advice is source-side only. The destination rides the live offer instead, and
  // comes back as a per-MSHR grant in the cycle the MSHR asks for it.
  // The one-migration rule is applied by masking each MSHR's offer with "is any OTHER MSHR already
  // mid-migration". Masking per MSHR rather than globally matters: a deferred migrant raises
  // migPending itself, so a global mask would take the offer away from exactly the MSHR that is about
  // to need it, and every deferred migration would decline.
  mshrs.zipWithIndex.foreach { case (m, i) =>
    m.io.migAdvice       := adviceMigrate
    m.io.migOffer.valid  := dstOfferValid && !(migrantOH & ~(1.U(params.mshrs.W) << i).asUInt).orR
    m.io.migOffer.bits   := dstOfferSet
    m.io.pairInfo.valid  := pairInfoValid
    m.io.pairInfo.bits   := pairInfoSet
  }
  // At most one MSHR may claim a destination per cycle. Holds by construction (one-hot directoryFanout
  // for the fast path; migPending masking for the deferred path), so this is a check, not a mechanism.
  assert (PopCount(VecInit(mshrs.map(_.io.dstClaim.valid)).asUInt) <= 1.U,
          "SBC: more than one MSHR claimed a migration destination in one cycle")

  // Determine which of the queued requests to pop (supposing will_pop)
  val prio_requests = ~(~requests.io.valid | (requests.io.valid >> params.mshrs) | (requests.io.valid >> 2*params.mshrs))
  val pop_index = OHToUInt(Cat(mshr_selectOH, mshr_selectOH, mshr_selectOH) & prio_requests)
  requests.io.pop.valid := will_pop
  requests.io.pop.bits  := pop_index

  // Reload from the Directory if the next MSHR operation changes tags
  val lb_tag_mismatch = scheduleTag =/= requests.io.data.tag
  // SBC Phase 1: the winning MSHR's 2nd dir-read (of dstSet) holds the directory read port this
  // cycle; treat it like a reload so no incoming request grabs the port or allocates concurrently.
  val mshr_uses_directory_for_dread = schedule.dread.valid
  val mshr_uses_directory_assuming_no_bypass = (schedule.reload && may_pop && lb_tag_mismatch) || mshr_uses_directory_for_dread
  val mshr_uses_directory_for_lb = will_pop && lb_tag_mismatch
  val mshr_uses_directory = will_reload && scheduleTag =/= Mux(bypass, request.bits.tag, requests.io.data.tag)

  // Is there an MSHR free for this request?
  val mshr_validOH = Cat(mshrs.map(_.io.status.valid).reverse)
  val mshr_free = (~mshr_validOH & prioFilter).orR

  // Fanout the request to the appropriate handler (if any)
  val bypassQueue = schedule.reload && bypassMatches
  val request_alloc_cases =
     (allocReady && !mshr_uses_directory_assuming_no_bypass && mshr_free) ||
     (nestB && !mshr_uses_directory_assuming_no_bypass && !bc_mshr.io.status.valid && !c_mshr.io.status.valid) ||
     (nestC && !mshr_uses_directory_assuming_no_bypass && !c_mshr.io.status.valid)
  request.ready := (request_alloc_cases || (queue && (bypassQueue || requests.io.push.ready))) && !dstSetConflict
  val alloc_uses_directory = request.valid && request_alloc_cases

  // When a request goes through, it will need to hit the Directory
  directory.io.read.valid := mshr_uses_directory || alloc_uses_directory || mshr_uses_directory_for_dread
  directory.io.read.bits.set := Mux(mshr_uses_directory_for_dread, schedule.dread.bits.set,
                                Mux(mshr_uses_directory_for_lb,    scheduleSet, request.bits.set))
  directory.io.read.bits.tag := Mux(mshr_uses_directory_for_dread, schedule.dread.bits.tag,
                                Mux(mshr_uses_directory_for_lb,    requests.io.data.tag, request.bits.tag))
  directory.io.read.bits.preferInvalid := mshr_uses_directory_for_dread // only the migration probe
  // SBC: the dread lane is the migrate probe - internal machinery, so no tag match and no tap.
  directory.io.read.bits.internalRead := mshr_uses_directory_for_dread
  // SBC Phase 2: a demand miss to a hot migration-source set prefers a clean, client-free victim
  // so the migrate-on-eviction gate in the MSHR finds an eligible line.
  // SBC Phase 2b (Bug A fix): the 2nd dir-read (the dstSet probe) must ALSO prefer an evictable way,
  // not just an invalid one. preferInvalid still wins when the dst set has a free way; when the dst
  // set is full, preferEvictable lets the directory return a clean / client-free / non-displaced way
  // we can silently overwrite. Without this term the dread fell back to the LFSR victim (usually
  // dirty or client-held) on a full dst set, so every migration hit the ABORT-DST path — which is
  // why zero migrations committed. The MSHR already requests preferEvictable on its dread bundle.
  // SBC Phase 2.5b: `adviceMigrate` lost its destination-side terms when the destination moved to a
  // live offer, so on its own it would raise this hint on evictions that then decline — perturbing
  // victim selection away from baseline for no gain. AND in the offer to keep the hint as rare as it
  // was before. It stays a hint either way (correctness never depends on it).
  // SBC Phase 3: the `&& dstOfferValid` term is gone. After the advice/destination split that wire
  // describes some OTHER MSHR's destination, so it says nothing about the allocating set. Hint only.
  directory.io.read.bits.preferEvictable := (alloc_uses_directory && adviceMigrate) ||
                                            mshr_uses_directory_for_dread
  if (params.micro.sbcDebug) {
    when (mshr_uses_directory_for_dread && mshr_selectOH.orR) {
      printf(p"[SBC][SCHED] DREAD-SCHED dstSet=${schedule.dread.bits.set} mshr=${mshr_select}\n")
    }
  }

  // Enqueue the request if not bypassed directly into an MSHR
  requests.io.push.valid := request.valid && queue && !bypassQueue
  requests.io.push.bits.data  := request.bits
  requests.io.push.bits.index := Mux1H(
    request.bits.prio, Seq(
      OHToUInt(lowerMatches1 << params.mshrs*0),
      OHToUInt(lowerMatches1 << params.mshrs*1),
      OHToUInt(lowerMatches1 << params.mshrs*2)))

  val mshr_insertOH = ~(leftOR(~mshr_validOH) << 1) & ~mshr_validOH & prioFilter
  (mshr_insertOH.asBools zip mshrs) map { case (s, m) =>
    when (request.valid && allocReady && s && !mshr_uses_directory_assuming_no_bypass) {
      m.io.allocate.valid := true.B
      m.io.allocate.bits.viewAsSupertype(chiselTypeOf(request.bits)) := request.bits
      m.io.allocate.bits.repeat := false.B
    }
  }

  when (request.valid && nestB && !bc_mshr.io.status.valid && !c_mshr.io.status.valid && !mshr_uses_directory_assuming_no_bypass) {
    bc_mshr.io.allocate.valid := true.B
    bc_mshr.io.allocate.bits.viewAsSupertype(chiselTypeOf(request.bits)) := request.bits
    bc_mshr.io.allocate.bits.repeat := false.B
    assert (!request.bits.prio(0))
  }
  bc_mshr.io.allocate.bits.prio(0) := false.B

  when (request.valid && nestC && !c_mshr.io.status.valid && !mshr_uses_directory_assuming_no_bypass) {
    c_mshr.io.allocate.valid := true.B
    c_mshr.io.allocate.bits.viewAsSupertype(chiselTypeOf(request.bits)) := request.bits
    c_mshr.io.allocate.bits.repeat := false.B
    assert (!request.bits.prio(0))
    assert (!request.bits.prio(1))
  }
  c_mshr.io.allocate.bits.prio(0) := false.B
  c_mshr.io.allocate.bits.prio(1) := false.B

  // Fanout the result of the Directory lookup
  val dirTarget = Mux(alloc, mshr_insertOH, Mux(nestB,(BigInt(1) << (params.mshrs-2)).U,(BigInt(1) << (params.mshrs-1)).U))
  val directoryFanout = params.dirReg(RegNext(
    Mux(mshr_uses_directory || mshr_uses_directory_for_dread, mshr_selectOH,
      Mux(alloc_uses_directory, dirTarget, 0.U))))
  mshrs.zipWithIndex.foreach { case (m, i) =>
    m.io.directory.valid := directoryFanout(i)
    m.io.directory.bits := directory.io.result.bits
  }

  // MSHR response meta-data fetch
  sinkC.io.way :=
    Mux(bc_mshr.io.status.valid && bc_mshr.io.status.bits.set === sinkC.io.set,
      bc_mshr.io.status.bits.way,
      Mux1H(abc_mshrs.map(m => m.io.status.valid && m.io.status.bits.set === sinkC.io.set),
            abc_mshrs.map(_.io.status.bits.way)))
  sinkD.io.way := VecInit(mshrs.map(_.io.status.bits.way))(sinkD.io.source)
  sinkD.io.set := VecInit(mshrs.map(_.io.status.bits.set))(sinkD.io.source)

  // Beat buffer connections between components
  sinkA.io.pb_pop <> sourceD.io.pb_pop
  sourceD.io.pb_beat := sinkA.io.pb_beat
  sinkC.io.rel_pop <> sourceD.io.rel_pop
  sourceD.io.rel_beat := sinkC.io.rel_beat

  // BankedStore ports
  bankedStore.io.sinkC_adr <> sinkC.io.bs_adr
  bankedStore.io.sinkC_dat := sinkC.io.bs_dat
  bankedStore.io.sinkD_adr <> sinkD.io.bs_adr
  bankedStore.io.sinkD_dat := sinkD.io.bs_dat
  bankedStore.io.sourceC_adr <> sourceC.io.bs_adr
  bankedStore.io.sourceD_radr <> sourceD.io.bs_radr
  bankedStore.io.sourceD_wadr <> sourceD.io.bs_wadr
  bankedStore.io.sourceD_wdat := sourceD.io.bs_wdat
  sourceC.io.bs_dat := bankedStore.io.sourceC_dat
  sourceD.io.bs_rdat := bankedStore.io.sourceD_rdat

  // SBC Phase 1: SetCopyUnit <-> BankedStore copy ports
  bankedStore.io.sourceCopy_radr <> setCopyUnit.io.bs_radr
  setCopyUnit.io.bs_rdat := bankedStore.io.sourceCopy_rdat
  bankedStore.io.sourceCopy_wadr <> setCopyUnit.io.bs_wadr
  bankedStore.io.sourceCopy_wdat := setCopyUnit.io.bs_wdat
  // SBC Phase 1: kick the SCU from the winning MSHR's copy lane (gated on SCU idle via mshr_request).
  // mshrId steers the done pulse back to the owning MSHR.
  setCopyUnit.io.start.valid       := schedule.copy.valid
  setCopyUnit.io.start.bits.srcSet := schedule.copy.bits.srcSet
  setCopyUnit.io.start.bits.srcWay := schedule.copy.bits.srcWay
  setCopyUnit.io.start.bits.dstSet := schedule.copy.bits.dstSet
  setCopyUnit.io.start.bits.dstWay := schedule.copy.bits.dstWay
  setCopyUnit.io.start.bits.mshrId := mshr_select
  if (params.micro.sbcDebug) {
    when (schedule.copy.valid && mshr_selectOH.orR) {
      printf(p"[SBC][SCHED] COPY-START srcSet=${schedule.copy.bits.srcSet} srcWay=${schedule.copy.bits.srcWay} dstSet=${schedule.copy.bits.dstSet} dstWay=${schedule.copy.bits.dstWay} mshr=${mshr_select}\n")
    }
  }

  // SourceD data hazard interlock
  sourceD.io.evict_req := sourceC.io.evict_req
  sourceD.io.grant_req := sinkD  .io.grant_req
  sourceC.io.evict_safe := sourceD.io.evict_safe
  sinkD  .io.grant_safe := sourceD.io.grant_safe

  // SBC Phase 1: SourceD <-> SetCopyUnit copy hazards (RaW on src read, WaR on dst write)
  sourceD.io.copy_req  := setCopyUnit.io.copy_req
  sourceD.io.copy_wreq := setCopyUnit.io.copy_wreq
  setCopyUnit.io.copy_safe  := sourceD.io.copy_safe
  setCopyUnit.io.copy_wsafe := sourceD.io.copy_wsafe

  // ---------------- Set-Balancing Cache (SBC) ----------------
  // The SBU watches the directory result via a read-only tap (it owns no data/SRAM ports) and,
  // when a set is armed + hot, emits a migrate request that is injected via the control port.
  // Stats are surfaced to the MMIO control block.
  if (params.micro.enableSetBalancing) {
    val sbu = Module(new SetBalanceUnit(params))
    sbu.io.dirTap     := directory.io.tap
    sbu.io.satReadSet := io.sbcSatReadSet
    sbu.io.arm        := io.sbcBalanceSet
    sbu.io.clear      := io.sbcReset
    // SBC Phase 2: migration counter pulses (OR across MSHRs; the token keeps ≤1 in flight)
    sbu.io.migAttempt := mshrs.map(_.io.migAttempt).reduce(_ || _)
    sbu.io.migAbort   := mshrs.map(_.io.migAbort).reduce(_ || _)
    // SBC: a destination that refused a migration is blocked in the DSS. One-hot by the token.
    val migRejectOH = VecInit(mshrs.map(_.io.migRejectDst.valid))
    sbu.io.migReject.valid := migRejectOH.asUInt.orR
    sbu.io.migReject.bits  := Mux1H(migRejectOH, mshrs.map(_.io.migRejectDst.bits))
    assert (PopCount(migRejectOH) <= 1.U)
    io.sbcStats       := sbu.io.stats

    // SBC Phase 2: migrate advice for the demand-allocating set. The SBU reports the source set is
    // hot and a cold destination exists; here we add the demand-A filter and the one-migration token.
    // SBC Phase 2.5b: the advice latched at allocate is now SOURCE-SIDE ONLY ("this set is hot").
    // Every destination-side condition moved to the live offer below, evaluated in the same cycle the
    // MSHR takes it. That deleted migTokenPending/migTokenDstSet/migPendCtr: all three existed purely
    // to survive the allocate→gate window with a pre-committed destination, and there is no such
    // window any more.
    val isDemandA = request.bits.prio(0) && !request.bits.control
    sbu.io.migrateQuery.valid := request.valid
    sbu.io.migrateQuery.bits  := request.bits.set
    // SBC Phase 3: who takes a destination this cycle - a migration already in flight (deferred path),
    // else the MSHR whose directory result lands now (fast path). `migrantOH` is all-zero in the exact
    // cycle a fast-path MSHR decides, which is why this needs both terms and not one query port.
    val decidingOH = Mux(anyMigrating, migrantOH, directoryFanout.asUInt)
    sbu.io.destQuery.valid := decidingOH.orR
    sbu.io.destQuery.bits  := Mux1H(decidingOH, mshrs.map(_.io.status.bits.set))
    // A claim can only happen in a cycle where that MSHR is the one the query was made for.
    val claimOH = VecInit(mshrs.map(_.io.dstClaim.valid)).asUInt
    assert ((claimOH & ~decidingOH) === 0.U,
            "SBC: destination claimed by an MSHR the destination query was not made for")
    // SBC debug repro: sbcForceDstSet (>=0) pins every migration's destination to a fixed set so the
    // dst-collision race is reproducible. Off (-1) = normal DSS pick (Scala if -> zero hardware off).
    // We override only WHERE a migration goes, not WHETHER — `migrateResp.migrate` (source must be
    // hot) and the `dstOfferOwned` guard below are preserved, so the natural race is unchanged.
    val coldDst = if (params.micro.sbcForceDstSet >= 0) params.micro.sbcForceDstSet.U(params.setBits.W)
                  else sbu.io.migrateResp.destSet
    // SBC Phase 2: source-side advice only — is this a demand miss on a hot set, with no migration
    // already in flight. Latched by the allocating MSHR; staleness here is harmless (it only means an
    // MSHR may ask for a destination and be told no).
    adviceMigrate := isDemandA && sbu.io.migrateResp.migrate && !anyMigrating

    // SBC Phase 2b (Q1 gate), now evaluated LIVE at the moment the destination is taken rather than
    // at allocate: never hand out a destination that an active MSHR already owns as its primary set,
    // and never one equal to the taker's own source set (checked inside the MSHR, which knows its own
    // set). With anyMigrating (≤1 migration) and the dstSetConflict fence, this still guarantees a
    // single writer per directory set for the whole migration window.
    // LOOP FREEDOM: dstOfferValid is built only from REGISTERED state (status.valid/dstValid/
    // migPending/set plus the SBU and DSS registers). Nothing on the
    // offer → dstClaim → dstSetConflict → allocReady path feeds back into the offer, and the MSHRs
    // must likewise keep io.allocate.bits.* out of their claim logic (see the note in MSHR.scala).
    val dstOfferOwned = mshrs.map { m => m.io.status.valid && m.io.status.bits.set === coldDst }.reduce(_ || _)
    dstOfferValid := sbu.io.migrateResp.destOk && !dstOfferOwned
    dstOfferSet   := coldDst

    if (params.micro.sbcDebug) {
      when (request.valid && request.ready && alloc && adviceMigrate) {
        printf(p"[SBC][SCHED] ADVICE-MIG srcSet=${request.bits.set} offerValid=${dstOfferValid} offerSet=${dstOfferSet}\n")
      }
      when (mshrs.map(_.io.dstClaim.valid).reduce(_ || _)) {
        printf(p"[SBC][SCHED] MIG-CLAIM dstSet=${dstOfferSet}\n")
      }
    }

    // SBC Phase 3: partner lookup for the allocating request. Gated to a FRESH allocate: the reload
    // path drives allocate.bits.set from the MSHR's own prior set, not request.bits.set, so an ungated
    // query would latch the wrong set's pairing on a secondary pop.
    sbu.io.assocQuery.valid   := request.valid && alloc
    sbu.io.assocQuery.bits    := request.bits.set
    pairInfoValid := sbu.io.assocQuery.valid && sbu.io.assocResp.activeSource
    pairInfoSet   := sbu.io.assocResp.assocSet
    // commit{MIGRATE} when a migration retires successfully (src=home set, dst=migDstSet).
    val migCommit = mshrs.map(_.io.migCommit)
    sbu.io.commit.valid     := migCommit.reduce(_ || _)
    sbu.io.commit.bits.kind := SBCCommitKind.MIGRATE
    sbu.io.commit.bits.src  := Mux1H(migCommit, mshrs.map(_.io.status.bits.set))
    sbu.io.commit.bits.dst  := Mux1H(migCommit, mshrs.map(_.io.status.bits.dstSet))
    if (params.micro.sbcDebug) {
      when (sbu.io.commit.valid) {
        printf(p"[SBC][SCHED] MIG-COMMIT srcSet=${sbu.io.commit.bits.src} dstSet=${sbu.io.commit.bits.dst}\n")
      }
    }
  } else {
    io.sbcStats := 0.U.asTypeOf(new SBCStats(params.setBits, params.micro.satCounterBits))
  }

  private def afmt(x: AddressSet) = s"""{"base":${x.base},"mask":${x.mask}}"""
  private def addresses = params.inner.manager.managers.flatMap(_.address).map(afmt _).mkString(",")
  private def setBits = params.addressMapping.drop(params.offsetBits).take(params.setBits).mkString(",")
  private def tagBits = params.addressMapping.drop(params.offsetBits + params.setBits).take(params.tagBits).mkString(",")
  private def simple = s""""reset":"${reset.pathName}","tagBits":[${tagBits}],"setBits":[${setBits}],"blockBytes":${params.cache.blockBytes},"ways":${params.cache.ways}"""
  def json: String = s"""{"addresses":[${addresses}],${simple},"directory":${directory.json},"subbanks":${bankedStore.json}}"""
}
