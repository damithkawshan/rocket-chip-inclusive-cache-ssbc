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
import chisel3.experimental.SourceInfo
import freechips.rocketchip.tilelink._
import TLPermissions._
import TLMessages._
import MetaData._
import chisel3.PrintableHelper
import chisel3.experimental.dataview._

class SetCopyRequest(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val srcSet = UInt(params.setBits.W)
  val srcWay = UInt(params.wayBits.W)
  val dstSet = UInt(params.setBits.W)
  val dstWay = UInt(params.wayBits.W)
}

class ScheduleRequest(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val a = Valid(new SourceARequest(params))
  val b = Valid(new SourceBRequest(params))
  val c = Valid(new SourceCRequest(params))
  val d = Valid(new SourceDRequest(params))
  val e = Valid(new SourceERequest(params))
  val x = Valid(new SourceXRequest(params))
  val dir = Valid(new DirectoryWrite(params))
  // SBC Phase 1: copy lane — kick the SetCopyUnit (parallel to a/b/c/d/e/x/dir)
  val copy = Valid(new SetCopyRequest(params))
  // SBC Phase 1: 2nd directory-read lane — probe dstSet (preferInvalid) for a free way
  val dread = Valid(new DirectoryRead(params))
  val reload = Bool() // get next request via allocate (if any)
}

class MSHRStatus(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val set = UInt(params.setBits.W)
  val tag = UInt(params.tagBits.W)
  val way = UInt(params.wayBits.W)
  val blockB = Bool()
  val nestB  = Bool()
  val blockC = Bool()
  val nestC  = Bool()
  // SBC Phase 1: migration destination reservation
  val dstValid = Bool()
  val dstSet   = UInt(params.setBits.W)
  val dstWay   = UInt(params.wayBits.W)
  // SBC Phase 2.5: this MSHR intends to migrate but is still waiting for its eviction probe.
  // No destination is reserved yet (dstValid is still false), but the one-migration-per-bank
  // token must already be held, or a second MSHR could start a migration in this window.
  val migPending = Bool()
}

class NestedWriteback(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val set = UInt(params.setBits.W)
  val tag = UInt(params.tagBits.W)
  val b_toN       = Bool() // nested Probes may unhit us
  val b_toB       = Bool() // nested Probes may demote us
  val b_clr_dirty = Bool() // nested Probes clear dirty
  val c_set_dirty = Bool() // nested Releases MAY set dirty
}

sealed trait CacheState
{
  val code = CacheState.index.U
  CacheState.index = CacheState.index + 1
}

object CacheState
{
  var index = 0
}

case object S_INVALID  extends CacheState
case object S_BRANCH   extends CacheState
case object S_BRANCH_C extends CacheState
case object S_TIP      extends CacheState
case object S_TIP_C    extends CacheState
case object S_TIP_CD   extends CacheState
case object S_TIP_D    extends CacheState
case object S_TRUNK_C  extends CacheState
case object S_TRUNK_CD extends CacheState

class MSHR(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val allocate  = Flipped(Valid(new AllocateRequest(params))) // refills MSHR for next cycle
    val directory = Flipped(Valid(new DirectoryResult(params))) // triggers schedule setup
    val status    = Valid(new MSHRStatus(params))
    val schedule  = Decoupled(new ScheduleRequest(params))
    val sinkc     = Flipped(Valid(new SinkCResponse(params)))
    val sinkd     = Flipped(Valid(new SinkDResponse(params)))
    val sinke     = Flipped(Valid(new SinkEResponse(params)))
    val nestedwb  = Flipped(new NestedWriteback(params))
    // SBC Phase 1: SetCopyUnit done pulse for this MSHR (routed by mshrId in Scheduler)
    val copy_done = Input(Bool())
    // SBC Phase 2: migrate advice latched at allocate. SOURCE-SIDE ONLY: "my set is a hot migration
    // source". The destination used to ride along here and was read many cycles later; it does not
    // any more (see migOffer/migWant/migGrant below).
    val migAdvice = Input(Bool())
    // SBC Phase 2.5b (late destination binding). The destination is read at the moment the migration
    // actually starts, never latched at allocate.
    //   migOffer - the live candidate the Scheduler publishes to THIS MSHR this cycle, already
    //              filtered: a cold set exists, no live MSHR owns it, and no OTHER MSHR is mid
    //              migration. The "other" masking is what makes an arbiter unnecessary — see the
    //              one-asker-per-cycle argument on dstClaim below.
    val migOffer = Flipped(Valid(UInt(params.setBits.W)))
    // SBC Phase 3: "my set is a paired source, and this is my partner". Latched at allocate; the
    // lookup side tolerates staleness (a search in the wrong set just finds nothing).
    val pairInfo = Flipped(Valid(UInt(params.setBits.W)))
    // SBC Phase 2.5b: same-cycle destination claim, consumed ONLY by the Scheduler's dstSetConflict
    // fence, so the [claim -> fence] window is zero cycles. Deliberately kept separate from
    // status.dstValid: dstValid feeds the one-migration masking, and folding a combinational claim
    // into it would close the loop claim -> offer -> claim.
    //
    // No arbiter guards this, because at most one MSHR can claim in a cycle by construction:
    //   * the fast path needs io.directory.valid, and directoryFanout is one-hot (Scheduler ~:389),
    //     so only one MSHR is assessing a victim in any cycle;
    //   * the deferred path needs migPending, which masks the offer away from every other MSHR.
    // The Scheduler asserts PopCount(dstClaim) <= 1 to keep that argument honest.
    val dstClaim = Valid(UInt(params.setBits.W))
    // SBC Phase 1: migration counter pulses to the SBU (attempted at alloc, aborted/committed at retire)
    val migAttempt = Output(Bool())
    val migAbort   = Output(Bool())
    val migCommit  = Output(Bool())
    // SBC: the destination set this MSHR probed and found unusable. Blocks it in the DSS so the
    // next migration picks a different set.
    val migRejectDst = Valid(UInt(params.setBits.W))
  })

  val request_valid = RegInit(false.B)
  val request = Reg(new FullRequest(params))
  val meta_valid = RegInit(false.B)
  val meta = Reg(new DirectoryResult(params))

  // Define which states are valid
  when (meta_valid) {
    when (meta.state === INVALID) {
      assert (!meta.clients.orR)
      assert (!meta.dirty)
    }
    when (meta.state === BRANCH) {
      assert (!meta.dirty)
    }
    when (meta.state === TRUNK) {
      assert (meta.clients.orR)
      assert ((meta.clients & (meta.clients - 1.U)) === 0.U) // at most one
    }
    when (meta.state === TIP) {
      // noop
    }
  }

  // Completed transitions (s_ = scheduled), (w_ = waiting)
  val s_rprobe         = RegInit(true.B) // B
  val w_rprobeackfirst = RegInit(true.B)
  val w_rprobeacklast  = RegInit(true.B)
  val s_release        = RegInit(true.B) // CW w_rprobeackfirst
  val w_releaseack     = RegInit(true.B)
  val s_pprobe         = RegInit(true.B) // B
  val s_acquire        = RegInit(true.B) // A  s_release, s_pprobe [1]
  val s_flush          = RegInit(true.B) // X  w_releaseack
  val w_grantfirst     = RegInit(true.B)
  val w_grantlast      = RegInit(true.B)
  val w_grant          = RegInit(true.B) // first | last depending on wormhole
  val w_pprobeackfirst = RegInit(true.B)
  val w_pprobeacklast  = RegInit(true.B)
  val w_pprobeack      = RegInit(true.B) // first | last depending on wormhole
  val s_probeack       = RegInit(true.B) // C  w_pprobeackfirst (mutually exclusive with next two s_*)
  val s_grantack       = RegInit(true.B) // E  w_grantfirst ... CAN require both outE&inD to service outD
  val s_execute        = RegInit(true.B) // D  w_pprobeack, w_grant
  val w_grantack       = RegInit(true.B)
  val s_writeback      = RegInit(true.B) // W  w_*

  // SBC Phase 2: migration scoreboard (mirrors s_release/w_releaseack). Inert by default;
  // cleared only when the A-channel eviction decides to migrate (migrate gate below).
  val s_copy           = RegInit(true.B)  // kick the SetCopyUnit via the copy lane
  val w_copy           = RegInit(true.B)  // SetCopyUnit reported done (io.copy_done)
  val s_dmeta          = RegInit(true.B)  // dir-write #1: install displaced @ (dstSet,dstWay)
  val migrating        = RegInit(false.B) // this MSHR owns an in-flight migration
  val migDstSet        = Reg(UInt(params.setBits.W))
  val migDstWay        = Reg(UInt(params.wayBits.W))
  val migSrcWay        = Reg(UInt(params.wayBits.W))
  val s_dread          = RegInit(true.B)  // schedule the 2nd dir-read (dstSet, preferInvalid)
  val w_dread          = RegInit(true.B)  // waiting for the 2nd dir-read result
  // SBC Phase 2.5 (probe-then-migrate): the victim is clean but the directory says a client still
  // holds it. That bit is CONSERVATIVE - rocket's L1 drops clean lines silently (silentDrop=true),
  // so it is usually stale. Rather than reject the victim, run the eviction probe we would have
  // sent anyway and decide afterwards. While this is set the migrate/release decision is still
  // open: neither `migrating` nor `s_release` has been committed.
  val migDeferred      = RegInit(false.B)
  // SBC Phase 2: migrate advice latched at allocate. Source-side only — "this set is hot".
  val migAdviceValidReg = RegInit(false.B)
  // SBC Phase 3: this set's pairing, latched at allocate alongside the advice.
  val pairValidReg      = RegInit(false.B)
  val pairSetReg        = Reg(UInt(params.setBits.W))

  // SBC Phase 2.5b (late destination binding): two decide points can ask for a destination — the
  // fast path (victim was already client-free) and the deferred path (post-probe). They are mutually
  // exclusive; the assert in the deferred-window check below holds that.
  // exclusive; the assert in the deferred-window check below holds that.
  //
  // LOOP FREEDOM (this is load-bearing — an earlier version of this deadlocked elaboration): every
  // term feeding these two wires must be a REGISTER or a register-derived Scheduler signal. In
  // particular they must not touch io.allocate.bits.*, which is combinationally tied to allocReady,
  // which is what dstClaim feeds. That is why the fast-path condition below is spelled out from
  // io.directory.bits / request rather than reusing new_meta / new_request.
  val migFastWantW  = WireInit(false.B)
  val migDeferWantW = WireInit(false.B)
  val migStartNow   = migFastWantW || migDeferWantW
  val migStartDst   = io.migOffer.bits
  io.dstClaim.valid := migStartNow
  io.dstClaim.bits  := migStartDst

  // SBC Phase 1: migration counter pulses (driven false here; asserted in the setup/retire logic).
  val migAttempt = WireInit(false.B)
  val migAbort   = WireInit(false.B)
  val migCommit  = WireInit(false.B)
  val migRejectDst = WireInit(false.B)
  io.migAttempt := migAttempt
  io.migAbort   := migAbort
  io.migCommit  := migCommit
  io.migRejectDst.valid := migRejectDst
  io.migRejectDst.bits  := migDstSet
  // [1]: We cannot issue outer Acquire while holding blockB (=> outA can stall)
  // However, inB and outC are higher priority than outB, so s_release and s_pprobe
  // may be safely issued while blockB. Thus we must NOT try to schedule the
  // potentially stuck s_acquire with either of them (scheduler is all or none).

  // Meta-data that we discover underway
  val sink = Reg(UInt(params.outer.bundle.sinkBits.W))
  val gotT = Reg(Bool())
  val bad_grant = Reg(Bool())
  val probes_done = Reg(UInt(params.clientBits.W))
  val probes_toN = Reg(UInt(params.clientBits.W))
  val probes_noT = Reg(Bool())

  // When a nested transaction completes, update our meta data
  when (meta_valid && meta.state =/= INVALID &&
        io.nestedwb.set === request.set && io.nestedwb.tag === meta.tag) {
    when (io.nestedwb.b_clr_dirty) { meta.dirty := false.B }
    when (io.nestedwb.c_set_dirty) { meta.dirty := true.B }
    when (io.nestedwb.b_toB) { meta.state := BRANCH }
    when (io.nestedwb.b_toN) { meta.hit := false.B }
  }

  // Scheduler status
  io.status.valid := request_valid
  io.status.bits.set    := request.set
  io.status.bits.tag    := request.tag
  io.status.bits.way    := meta.way
  io.status.bits.blockB := !meta_valid || ((!w_releaseack || !w_rprobeacklast || !w_pprobeacklast) && !w_grantfirst)
  io.status.bits.nestB  := meta_valid && w_releaseack && w_rprobeacklast && w_pprobeacklast && !w_grantfirst
  // The above rules ensure we will block and not nest an outer probe while still doing our
  // own inner probes. Thus every probe wakes exactly one MSHR.
  io.status.bits.blockC := !meta_valid
  io.status.bits.nestC  := meta_valid && (!w_rprobeackfirst || !w_pprobeackfirst || !w_grantfirst)
  // SBC Phase 2.5: hold the migration token across the deferred probe window (see Scheduler's
  // anyMigrating). Deliberately NOT folded into dstValid - no destination is reserved yet, and
  // fencing the destination set before the probe completes would add a hold-and-wait edge
  // ("migration holds set d while waiting on the L1") that the baseline does not have.
  io.status.bits.migPending := migDeferred
  // SBC Phase 1: drive the migration reservation while this MSHR owns a migration
  if (params.micro.sbcGateStallCycles > 0) {
    // SBC debug repro FALLBACK: deliberately hold the destination fence (dstValid) low for the first
    // N cycles after `migrating` rises, widening the unfenced [advice->gate] window so a hammering
    // demand reliably collides with the migrant. Internal migrate sequencing uses `migrating`, not
    // this status bit, so only the external fence is delayed. Zero hardware unless the knob is set.
    val gateCtr = RegInit(0.U(log2Ceil(params.micro.sbcGateStallCycles + 1).W))
    when (!migrating)                                        { gateCtr := 0.U }
    .elsewhen (gateCtr =/= params.micro.sbcGateStallCycles.U) { gateCtr := gateCtr + 1.U }
    io.status.bits.dstValid := migrating && (gateCtr === params.micro.sbcGateStallCycles.U)
  } else {
    io.status.bits.dstValid := migrating
  }
  io.status.bits.dstSet   := migDstSet
  io.status.bits.dstWay   := migDstWay
  // The w_grantfirst in nestC is necessary to deal with:
  //   acquire waiting for grant, inner release gets queued, outer probe -> inner probe -> deadlock
  // ... this is possible because the release+probe can be for same set, but different tag

  // We can only demand: block, nest, or queue
  assert (!io.status.bits.nestB || !io.status.bits.blockB)
  assert (!io.status.bits.nestC || !io.status.bits.blockC)

  // Scheduler requests
  val no_wait = w_rprobeacklast && w_releaseack && w_grantlast && w_pprobeacklast && w_grantack && w_copy
  // SBC Phase 1: migration dir-write sequencing. #1 installs the displaced entry at
  // (dstSet,dstWay) once the copy is done; #2 reuses the writeback step to invalidate the
  // home way. mig_ready holds the home-invalidate (and retire) until #1 has gone out.
  val mig_dir1  = migrating && !s_dmeta && w_copy
  val mig_ready = !migrating || (s_dmeta && w_dread)
  // SBC Phase 2: while migrating, hold the demand Acquire until the victim copy has been fully
  // read (w_copy). This sequences the 2nd dir-read + copy first and provides the A2 copy<->refill
  // interlock (the memory refill of (s,vWay) cannot precede copy-read-done).
  // SBC Phase 2.5 (R1 - REQUIRED FOR DEADLOCK FREEDOM, DO NOT REMOVE): while migDeferred is set we
  // have committed to neither path, so `s_release` is still true and `migrating` still false - both
  // existing guards read as "nothing to wait for" and this gate would open mid-probe. Two failures
  // follow: (a) the refill overwrites the victim before the copy engine reads it (silent data loss),
  // and (b) per note [1] above, the scheduler is all-or-none, so firing s_acquire alongside the
  // pending rprobe stalls BOTH (outA cannot make progress while we hold blockB, and the probe is the
  // only thing that clears blockB) - a circular wait. On the baseline path `s_release := false` is
  // what keeps this gate shut during a probe; here that register is not available yet.
  io.schedule.bits.a.valid := !s_acquire && s_release && s_pprobe && (!migrating || w_copy) && !migDeferred
  io.schedule.bits.b.valid := !s_rprobe || !s_pprobe
  io.schedule.bits.c.valid := (!s_release && w_rprobeackfirst) || (!s_probeack && w_pprobeackfirst)
  io.schedule.bits.d.valid := !s_execute && w_pprobeack && w_grant
  io.schedule.bits.e.valid := !s_grantack && w_grantfirst
  io.schedule.bits.x.valid := !s_flush && w_releaseack
  io.schedule.bits.dir.valid := (!s_release && w_rprobeackfirst) || (!s_writeback && no_wait && mig_ready) || mig_dir1
  // SBC: must match the retire condition below. Phase 2 added mig_ready there but not here, so an
  // MSHR advertised itself free mid-migration and got reset without retiring.
  io.schedule.bits.reload := no_wait && mig_ready
  // SBC Phase 1: copy lane — driven only while this MSHR owns a migration whose copy is pending.
  io.schedule.bits.copy.valid       := migrating && !s_copy
  io.schedule.bits.copy.bits.srcSet := request.set
  io.schedule.bits.copy.bits.srcWay := migSrcWay
  io.schedule.bits.copy.bits.dstSet := migDstSet
  io.schedule.bits.copy.bits.dstWay := migDstWay
  // SBC Phase 2b: 2nd dir-read of dstSet — prefer a free (INVALID) way, else a clean/client-free
  // evictable way we can silently overwrite (no writeback, no probe). Falls back if neither exists.
  io.schedule.bits.dread.valid         := migrating && !s_dread
  io.schedule.bits.dread.bits.set      := migDstSet
  io.schedule.bits.dread.bits.tag      := 0.U
  io.schedule.bits.dread.bits.preferInvalid := true.B
  io.schedule.bits.dread.bits.preferEvictable := true.B  // 2b: accept a clean evictable dst way
  io.schedule.bits.dread.bits.internalRead    := true.B  // probe, not a demand access
  // NOTE: tag is 0 here. internalRead suppresses the comparison, so it no longer matters.
  io.schedule.valid := io.schedule.bits.a.valid || io.schedule.bits.b.valid || io.schedule.bits.c.valid ||
                       io.schedule.bits.d.valid || io.schedule.bits.e.valid || io.schedule.bits.x.valid ||
                       io.schedule.bits.dir.valid || io.schedule.bits.copy.valid || io.schedule.bits.dread.valid

  // Schedule completions
  when (io.schedule.ready) {
    if (params.micro.sbcDebug) {
      when (migrating) {
        printf(p"[SBC] SCHED-FIRE srcSet=${request.set} dstSet=${migDstSet} a_valid=${io.schedule.bits.a.valid} dread=${migrating && !s_dread} copy=${migrating && !s_copy} dir1=${mig_dir1} retire=${no_wait && mig_ready} s_acq=${s_acquire} w_copy=${w_copy} w_dread=${w_dread}\n")
      }
      // BUG-A smoke detector: a pending refill that the gate is holding for a reason we did NOT
      // anticipate. The two legitimate holds (deferred probe, and the A2 copy interlock) are excluded,
      // so anything this prints is a new drift between a.valid and the rest of the FSM.
      when (s_release && s_pprobe && !s_acquire && !io.schedule.bits.a.valid &&
            !migDeferred && (!migrating || w_copy)) {
        printf(p"[SBC] BUG-A-DETECT srcSet=${request.set} s_acquire SET WITHOUT ACQUIRE FIRING mig=${migrating} w_copy=${w_copy} w_dread=${w_dread}\n")
      }
    }
                                    s_rprobe     := true.B
    when (w_rprobeackfirst)       { s_release    := true.B }
                                    s_pprobe     := true.B
    // SBC Phase 2.5 (BUG-A fix): retire s_acquire only when the Acquire ACTUALLY issued. The old
    // condition `s_release && s_pprobe` mirrored a.valid by hand and silently drifted every time a
    // term was added to that gate: with `!migDeferred` (and, before it, `!migrating || w_copy`) the
    // schedule can fire for the B-channel probe while a.valid is low, and this would then mark the
    // refill "done" without sending it - the MSHR waits forever for a grant that never comes.
    // Keying off a.valid itself cannot drift. Baseline-identical: with SBC off a.valid reduces to
    // `!s_acquire && s_release && s_pprobe`, and re-setting an already-true s_acquire was a no-op.
    when (io.schedule.bits.a.valid) { s_acquire    := true.B }
    when (w_releaseack)           { s_flush      := true.B }
    when (w_pprobeackfirst)       { s_probeack   := true.B }
    when (w_grantfirst)           { s_grantack   := true.B }
    when (w_pprobeack && w_grant) { s_execute    := true.B }
    // SBC Phase 1: migration scoreboard advances (one schedule item at a time)
    when (migrating && !s_dread)  { s_dread      := true.B }
    when (migrating && !s_copy)   { s_copy       := true.B }
    when (mig_dir1)               { s_dmeta      := true.B }
    when (no_wait && mig_ready)   { s_writeback  := true.B }
    // Await the next operation
    when (no_wait && mig_ready) {
      request_valid := false.B
      meta_valid := false.B
      // SBC Phase 2: a still-migrating MSHR at retire committed its migration (commit{MIGRATE}).
      // A dst-full abort already cleared `migrating` (and pulsed migAbort) on the fallback path.
      migCommit := migrating
      migrating := false.B
    }
  }

  // Resulting meta-data
  val final_meta_writeback = WireInit(meta)

  val req_clientBit = params.clientBit(request.source)
  val req_needT = needT(request.opcode, request.param)
  val req_acquire = request.opcode === AcquireBlock || request.opcode === AcquirePerm
  val meta_no_clients = !meta.clients.orR
  val req_promoteT = req_acquire && Mux(meta.hit, meta_no_clients && meta.state === TIP, gotT)

  when (request.prio(2) && (!params.firstLevel).B) { // always a hit
    final_meta_writeback.dirty   := meta.dirty || request.opcode(0)
    final_meta_writeback.state   := Mux(request.param =/= TtoT && meta.state === TRUNK, TIP, meta.state)
    final_meta_writeback.clients := meta.clients & ~Mux(isToN(request.param), req_clientBit, 0.U)
    final_meta_writeback.hit     := true.B // chained requests are hits
  } .elsewhen (request.control && params.control.B) { // request.prio(0)
    when (meta.hit) {
      final_meta_writeback.dirty   := false.B
      final_meta_writeback.state   := INVALID
      final_meta_writeback.clients := meta.clients & ~probes_toN
    }
    final_meta_writeback.hit := false.B
  } .otherwise {
    final_meta_writeback.dirty := (meta.hit && meta.dirty) || !request.opcode(2)
    final_meta_writeback.state := Mux(req_needT,
                                    Mux(req_acquire, TRUNK, TIP),
                                    Mux(!meta.hit, Mux(gotT, Mux(req_acquire, TRUNK, TIP), BRANCH),
                                      MuxLookup(meta.state, 0.U(2.W))(Seq(
                                        INVALID -> BRANCH,
                                        BRANCH  -> BRANCH,
                                        TRUNK   -> TIP,
                                        TIP     -> Mux(meta_no_clients && req_acquire, TRUNK, TIP)))))
    final_meta_writeback.clients := Mux(meta.hit, meta.clients & ~probes_toN, 0.U) |
                                    Mux(req_acquire, req_clientBit, 0.U)
    final_meta_writeback.tag := request.tag
    final_meta_writeback.hit := true.B
    // SBC: a (re)filled native line is not displaced; migration phases set this explicitly.
    final_meta_writeback.displaced := false.B
  }

  when (bad_grant) {
    when (meta.hit) {
      // upgrade failed (B -> T)
      assert (!meta_valid || meta.state === BRANCH)
      final_meta_writeback.hit     := true.B
      final_meta_writeback.dirty   := false.B
      final_meta_writeback.state   := BRANCH
      final_meta_writeback.clients := meta.clients & ~probes_toN
    } .otherwise {
      // failed N -> (T or B)
      final_meta_writeback.hit     := false.B
      final_meta_writeback.dirty   := false.B
      final_meta_writeback.state   := INVALID
      final_meta_writeback.clients := 0.U
    }
  }

  val invalid = Wire(new DirectoryEntry(params))
  invalid.dirty   := false.B
  invalid.state   := INVALID
  invalid.clients := 0.U
  invalid.tag     := 0.U
  invalid.displaced := false.B // SBC: invalidated entries are never displaced

  // SBC Phase 1: the displaced entry installed at (dstSet,dstWay) by dir-write #1. It carries
  // the migrated victim's tag/state, is clean + client-free, and is flagged displaced.
  val displacedEntry = Wire(new DirectoryEntry(params))
  displacedEntry.dirty     := meta.dirty
  // SBC Phase 2.5: the parked entry must be SELF-CONSISTENT after the probe. TRUNK encodes "exactly
  // one client owns this exclusively", and the directory asserts `state === TRUNK => clients =/= 0`
  // (MSHR.scala:139). Probe-then-migrate can now migrate a victim that was TRUNK, and since we zero
  // the client mask below, leaving the state at TRUNK installs an impossible entry that trips that
  // assert the next time the way is read. The client relinquished the line (toN, no data - a
  // ProbeAckData would have set meta.dirty and aborted the migration), so the L2 copy is current and
  // unshared: TRUNK collapses to TIP. Same rule the ordinary path uses at MSHR.scala:357.
  displacedEntry.state     := Mux(meta.state === TRUNK, TIP, meta.state)
  // SBC Phase 2.5 (R2): `meta.clients` is the mask latched at allocate and is NOT updated by probes -
  // the post-probe set is `meta.clients & ~probes_toN`. Under probe-then-migrate the raw mask would
  // mark the parked copy as client-held, breaking the displaced => clean+client-free invariant that
  // lets the displaced-reclaim tier drop a parked way silently (no probe, no release). Always use the
  // post-probe value; on the migrate path it is provably zero (the eviction rprobe asks toN).
  displacedEntry.clients   := meta.clients & ~probes_toN
  displacedEntry.tag       := meta.tag
  displacedEntry.displaced := true.B
  assert(!mig_dir1 || (!meta.dirty && (meta.clients & ~probes_toN) === 0.U), "migrate source must be clean+client-free")
  assert(!mig_dir1 || displacedEntry.state =/= TRUNK, "SBC: displaced entry must not be TRUNK (TRUNK implies a client, displaced has none)")
  // SBC: a displaced victim must never be RELEASED — its address maps to a different set than the one
  // it sits in, so a Release would carry the wrong address. It is dropped silently instead (see the
  // displaced-reclaim branch in the eviction logic). The directory writeback IS allowed: reclaim
  // overwrites the displaced way with a fresh native line (final_meta_writeback.displaced = false).
  assert(!(meta_valid && meta.displaced && !s_release), "SBC: release of a displaced victim (wrong address); displaced victims must be dropped silently")

  // Just because a client says BtoT, by the time we process the request he may be N.
  // Therefore, we must consult our own meta-data state to confirm he owns the line still.
  val honour_BtoT = meta.hit && (meta.clients & req_clientBit).orR

  // The client asking us to act is proof they don't have permissions.
  val excluded_client = Mux(meta.hit && request.prio(0) && skipProbeN(request.opcode, params.cache.hintsSkipProbe), req_clientBit, 0.U)
  io.schedule.bits.a.bits.tag     := request.tag
  io.schedule.bits.a.bits.set     := request.set
  io.schedule.bits.a.bits.param   := Mux(req_needT, Mux(meta.hit, BtoT, NtoT), NtoB)
  io.schedule.bits.a.bits.block   := request.size =/= log2Ceil(params.cache.blockBytes).U ||
                                     !(request.opcode === PutFullData || request.opcode === AcquirePerm)
  io.schedule.bits.a.bits.source  := 0.U
  if (params.micro.sbcDebug) {
    when (io.schedule.bits.a.valid && io.schedule.ready) {
      printf(p"[SBC] OUTER-A addr=0x${Hexadecimal(params.expandAddress(io.schedule.bits.a.bits.tag, io.schedule.bits.a.bits.set, 0.U))} " +
             p"set=${io.schedule.bits.a.bits.set} perm=${!io.schedule.bits.a.bits.block} param=${io.schedule.bits.a.bits.param} hit=${meta.hit} " +
             p"ctrl=${request.control} mig=${migrating} op=${request.opcode} prio=${request.prio.asUInt}\n")
    }
  }
  io.schedule.bits.b.bits.param   := Mux(!s_rprobe, toN, Mux(request.prio(1), request.param, Mux(req_needT, toN, toB)))
  io.schedule.bits.b.bits.tag     := Mux(!s_rprobe, meta.tag, request.tag)
  io.schedule.bits.b.bits.set     := request.set
  io.schedule.bits.b.bits.clients := meta.clients & ~excluded_client
  io.schedule.bits.c.bits.opcode  := Mux(meta.dirty, ReleaseData, Release)
  io.schedule.bits.c.bits.param   := Mux(meta.state === BRANCH, BtoN, TtoN)
  io.schedule.bits.c.bits.source  := 0.U
  io.schedule.bits.c.bits.tag     := meta.tag
  io.schedule.bits.c.bits.set     := request.set
  io.schedule.bits.c.bits.way     := meta.way
  io.schedule.bits.c.bits.dirty   := meta.dirty
  io.schedule.bits.d.bits.viewAsSupertype(chiselTypeOf(request)) := request
  io.schedule.bits.d.bits.param   := Mux(!req_acquire, request.param,
                                       MuxLookup(request.param, request.param)(Seq(
                                         NtoB -> Mux(req_promoteT, NtoT, NtoB),
                                         BtoT -> Mux(honour_BtoT,  BtoT, NtoT),
                                         NtoT -> NtoT)))
  io.schedule.bits.d.bits.sink    := 0.U
  io.schedule.bits.d.bits.way     := meta.way
  io.schedule.bits.d.bits.bad     := bad_grant
  io.schedule.bits.e.bits.sink    := sink
  io.schedule.bits.x.bits.fail    := false.B
  io.schedule.bits.dir.bits.set   := Mux(mig_dir1, migDstSet, request.set)
  io.schedule.bits.dir.bits.way   := Mux(mig_dir1, migDstWay, meta.way)
  // SBC Phase 2: dir-write #1 (mig_dir1) installs the displaced copy at (dstSet,dstWay); dir-write
  // #2 is the ordinary demand refill that rewrites the freed home way (s,vWay) with the new line.
  io.schedule.bits.dir.bits.data  := Mux(mig_dir1, displacedEntry,
                                     Mux(!s_release, invalid, WireInit(new DirectoryEntry(params), init = final_meta_writeback)))

  // Coverage of state transitions
  def cacheState(entry: DirectoryEntry, hit: Bool) = {
    val out = WireDefault(0.U)
    val c = entry.clients.orR
    val d = entry.dirty
    switch (entry.state) {
      is (BRANCH)  { out := Mux(c, S_BRANCH_C.code, S_BRANCH.code) }
      is (TRUNK)   { out := Mux(d, S_TRUNK_CD.code, S_TRUNK_C.code) }
      is (TIP)     { out := Mux(c, Mux(d, S_TIP_CD.code, S_TIP_C.code), Mux(d, S_TIP_D.code, S_TIP.code)) }
      is (INVALID) { out := S_INVALID.code }
    }
    when (!hit) { out := S_INVALID.code }
    out
  }

  val p = !params.lastLevel  // can be probed
  val c = !params.firstLevel // can be acquired
  val m = params.inner.client.clients.exists(!_.supports.probe)   // can be written (or read)
  val r = params.outer.manager.managers.exists(!_.alwaysGrantsT) // read-only devices exist
  val f = params.control     // flush control register exists
  val cfg = (p, c, m, r, f)
  val b = r || p // can reach branch state (via probe downgrade or read-only device)

  // The cache must be used for something or we would not be here
  require(c || m)

  val evict = cacheState(meta, !meta.hit)
  val before = cacheState(meta, meta.hit)
  val after  = cacheState(final_meta_writeback, true.B)

  def eviction(from: CacheState, cover: Boolean)(implicit sourceInfo: SourceInfo) {
    if (cover) {
      params.ccover(evict === from.code, s"MSHR_${from}_EVICT", s"State transition from ${from} to evicted ${cfg}")
    } else {
      assert(!(evict === from.code), cf"State transition from ${from} to evicted should be impossible ${cfg}")
    }
    if (cover && f) {
      params.ccover(before === from.code, s"MSHR_${from}_FLUSH", s"State transition from ${from} to flushed ${cfg}")
    } else {
      assert(!(before === from.code), cf"State transition from ${from} to flushed should be impossible ${cfg}")
    }
  }

  def transition(from: CacheState, to: CacheState, cover: Boolean)(implicit sourceInfo: SourceInfo) {
    if (cover) {
      params.ccover(before === from.code && after === to.code, s"MSHR_${from}_${to}", s"State transition from ${from} to ${to} ${cfg}")
    } else {
      assert(!(before === from.code && after === to.code), cf"State transition from ${from} to ${to} should be impossible ${cfg}")
    }
  }

  when ((!s_release && w_rprobeackfirst) && io.schedule.ready) {
    eviction(S_BRANCH,    b)      // MMIO read to read-only device
    eviction(S_BRANCH_C,  b && c) // you need children to become C
    eviction(S_TIP,       true)   // MMIO read || clean release can lead to this state
    eviction(S_TIP_C,     c)      // needs two clients || client + mmio || downgrading client
    eviction(S_TIP_CD,    c)      // needs two clients || client + mmio || downgrading client
    eviction(S_TIP_D,     true)   // MMIO write || dirty release lead here
    eviction(S_TRUNK_C,   c)      // acquire for write
    eviction(S_TRUNK_CD,  c)      // dirty release then reacquire
  }

  when ((!s_writeback && no_wait) && io.schedule.ready) {
    transition(S_INVALID,  S_BRANCH,   b && m) // only MMIO can bring us to BRANCH state
    transition(S_INVALID,  S_BRANCH_C, b && c) // C state is only possible if there are inner caches
    transition(S_INVALID,  S_TIP,      m)      // MMIO read
    transition(S_INVALID,  S_TIP_C,    false)  // we would go S_TRUNK_C instead
    transition(S_INVALID,  S_TIP_CD,   false)  // acquire does not cause dirty immediately
    transition(S_INVALID,  S_TIP_D,    m)      // MMIO write
    transition(S_INVALID,  S_TRUNK_C,  c)      // acquire
    transition(S_INVALID,  S_TRUNK_CD, false)  // acquire does not cause dirty immediately

    transition(S_BRANCH,   S_INVALID,  b && p) // probe can do this (flushes run as evictions)
    transition(S_BRANCH,   S_BRANCH_C, b && c) // acquire
    transition(S_BRANCH,   S_TIP,      b && m) // prefetch write
    transition(S_BRANCH,   S_TIP_C,    false)  // we would go S_TRUNK_C instead
    transition(S_BRANCH,   S_TIP_CD,   false)  // acquire does not cause dirty immediately
    transition(S_BRANCH,   S_TIP_D,    b && m) // MMIO write
    transition(S_BRANCH,   S_TRUNK_C,  b && c) // acquire
    transition(S_BRANCH,   S_TRUNK_CD, false)  // acquire does not cause dirty immediately

    transition(S_BRANCH_C, S_INVALID,  b && c && p)
    transition(S_BRANCH_C, S_BRANCH,   b && c)      // clean release (optional)
    transition(S_BRANCH_C, S_TIP,      b && c && m) // prefetch write
    transition(S_BRANCH_C, S_TIP_C,    false)       // we would go S_TRUNK_C instead
    transition(S_BRANCH_C, S_TIP_D,    b && c && m) // MMIO write
    transition(S_BRANCH_C, S_TIP_CD,   false)       // going dirty means we must shoot down clients
    transition(S_BRANCH_C, S_TRUNK_C,  b && c)      // acquire
    transition(S_BRANCH_C, S_TRUNK_CD, false)       // acquire does not cause dirty immediately

    transition(S_TIP,      S_INVALID,  p)
    transition(S_TIP,      S_BRANCH,   p)      // losing TIP only possible via probe
    transition(S_TIP,      S_BRANCH_C, false)  // we would go S_TRUNK_C instead
    transition(S_TIP,      S_TIP_C,    false)  // we would go S_TRUNK_C instead
    transition(S_TIP,      S_TIP_D,    m)      // direct dirty only via MMIO write
    transition(S_TIP,      S_TIP_CD,   false)  // acquire does not make us dirty immediately
    transition(S_TIP,      S_TRUNK_C,  c)      // acquire
    transition(S_TIP,      S_TRUNK_CD, false)  // acquire does not make us dirty immediately

    transition(S_TIP_C,    S_INVALID,  c && p)
    transition(S_TIP_C,    S_BRANCH,   c && p) // losing TIP only possible via probe
    transition(S_TIP_C,    S_BRANCH_C, c && p) // losing TIP only possible via probe
    transition(S_TIP_C,    S_TIP,      c)      // probed while MMIO read || clean release (optional)
    transition(S_TIP_C,    S_TIP_D,    c && m) // direct dirty only via MMIO write
    transition(S_TIP_C,    S_TIP_CD,   false)  // going dirty means we must shoot down clients
    transition(S_TIP_C,    S_TRUNK_C,  c)      // acquire
    transition(S_TIP_C,    S_TRUNK_CD, false)  // acquire does not make us immediately dirty

    transition(S_TIP_D,    S_INVALID,  p)
    transition(S_TIP_D,    S_BRANCH,   p)      // losing D is only possible via probe
    transition(S_TIP_D,    S_BRANCH_C, p && c) // probed while acquire shared
    transition(S_TIP_D,    S_TIP,      p)      // probed while MMIO read || outer probe.toT (optional)
    transition(S_TIP_D,    S_TIP_C,    false)  // we would go S_TRUNK_C instead
    transition(S_TIP_D,    S_TIP_CD,   false)  // we would go S_TRUNK_CD instead
    transition(S_TIP_D,    S_TRUNK_C,  p && c) // probed while acquired
    transition(S_TIP_D,    S_TRUNK_CD, c)      // acquire

    transition(S_TIP_CD,   S_INVALID,  c && p)
    transition(S_TIP_CD,   S_BRANCH,   c && p) // losing D is only possible via probe
    transition(S_TIP_CD,   S_BRANCH_C, c && p) // losing D is only possible via probe
    transition(S_TIP_CD,   S_TIP,      c && p) // probed while MMIO read || outer probe.toT (optional)
    transition(S_TIP_CD,   S_TIP_C,    false)  // we would go S_TRUNK_C instead
    transition(S_TIP_CD,   S_TIP_D,    c)      // MMIO write || clean release (optional)
    transition(S_TIP_CD,   S_TRUNK_C,  c && p) // probed while acquire
    transition(S_TIP_CD,   S_TRUNK_CD, c)      // acquire

    transition(S_TRUNK_C,  S_INVALID,  c && p)
    transition(S_TRUNK_C,  S_BRANCH,   c && p) // losing TIP only possible via probe
    transition(S_TRUNK_C,  S_BRANCH_C, c && p) // losing TIP only possible via probe
    transition(S_TRUNK_C,  S_TIP,      c)      // MMIO read || clean release (optional)
    transition(S_TRUNK_C,  S_TIP_C,    c)      // bounce shared
    transition(S_TRUNK_C,  S_TIP_D,    c)      // dirty release
    transition(S_TRUNK_C,  S_TIP_CD,   c)      // dirty bounce shared
    transition(S_TRUNK_C,  S_TRUNK_CD, c)      // dirty bounce

    transition(S_TRUNK_CD, S_INVALID,  c && p)
    transition(S_TRUNK_CD, S_BRANCH,   c && p) // losing D only possible via probe
    transition(S_TRUNK_CD, S_BRANCH_C, c && p) // losing D only possible via probe
    transition(S_TRUNK_CD, S_TIP,      c && p) // probed while MMIO read || outer probe.toT (optional)
    transition(S_TRUNK_CD, S_TIP_C,    false)  // we would go S_TRUNK_C instead
    transition(S_TRUNK_CD, S_TIP_D,    c)      // dirty release
    transition(S_TRUNK_CD, S_TIP_CD,   c)      // bounce shared
    transition(S_TRUNK_CD, S_TRUNK_C,  c && p) // probed while acquire
  }

  // Handle response messages
  val probe_bit = params.clientBit(io.sinkc.bits.source)
  val last_probe = (probes_done | probe_bit) === (meta.clients & ~excluded_client)
  val probe_toN = isToN(io.sinkc.bits.param)
  if (!params.firstLevel) when (io.sinkc.valid) {
    params.ccover( probe_toN && io.schedule.bits.b.bits.param === toB, "MSHR_PROBE_FULL", "Client downgraded to N when asked only to do B")
    params.ccover(!probe_toN && io.schedule.bits.b.bits.param === toB, "MSHR_PROBE_HALF", "Client downgraded to B when asked only to do B")
    // Caution: the probe matches us only in set.
    // We would never allow an outer probe to nest until both w_[rp]probeack complete, so
    // it is safe to just unguardedly update the probe FSM.
    probes_done := probes_done | probe_bit
    probes_toN := probes_toN | Mux(probe_toN, probe_bit, 0.U)
    probes_noT := probes_noT || io.sinkc.bits.param =/= TtoT
    w_rprobeackfirst := w_rprobeackfirst || last_probe
    w_rprobeacklast := w_rprobeacklast || (last_probe && io.sinkc.bits.last)
    w_pprobeackfirst := w_pprobeackfirst || last_probe
    w_pprobeacklast := w_pprobeacklast || (last_probe && io.sinkc.bits.last)
    // Allow wormhole routing from sinkC if the first request beat has offset 0
    val set_pprobeack = last_probe && (io.sinkc.bits.last || request.offset === 0.U)
    w_pprobeack := w_pprobeack || set_pprobeack
    params.ccover(!set_pprobeack && w_rprobeackfirst, "MSHR_PROBE_SERIAL", "Sequential routing of probe response data")
    params.ccover( set_pprobeack && w_rprobeackfirst, "MSHR_PROBE_WORMHOLE", "Wormhole routing of probe response data")
    // However, meta-data updates need to be done more cautiously
    when (meta.state =/= INVALID && io.sinkc.bits.tag === meta.tag && io.sinkc.bits.data) { meta.dirty := true.B } // !!!
  }
  // SBC Phase 2.5: probe-then-migrate decision point. Runs the cycle AFTER the last probe ack, so
  // both `meta.dirty` (set by a ProbeAckData in the SinkC block above) and `probes_toN` have settled.
  // The eviction rprobe always asks toN (see b.bits.param), so by here every client is at N and the
  // victim is client-free for real - a stronger guarantee than the stale bit we used to test.
  // SBC Phase 2.5b: ask for a destination in THIS cycle rather than acting on one picked back at
  // allocate. The probe round-trip is unbounded, so an allocate-time pick is arbitrarily stale here.
  val migDeferWant = migDeferred && w_rprobeacklast && !meta.dirty &&
                     io.migOffer.valid && io.migOffer.bits =/= request.set
  when (migDeferWant) { migDeferWantW := true.B }

  // SBC Phase 2.5b: the fast path (victim was already client-free) takes its destination late too.
  // The eviction assess block runs on the directory RESULT, not on the allocate cycle, so a pick
  // latched at allocate is already stale there by the dir-read latency.
  //
  // Spelled out from io.directory.bits / request instead of new_meta / new_request ON PURPOSE: those
  // two are Muxes on io.allocate.*, and BOTH io.allocate.valid and io.allocate.bits.* are
  // combinationally tied to allocReady — which is what dstClaim feeds. Touching either closes a
  // combinational loop (elaboration catches it; both forms were tried).
  //   * request.set is exact, not an approximation: the Scheduler forces
  //     allocate.bits.set := status.bits.set (= request.set), so new_request.set === request.set.
  //   * In the rare cycle where this MSHR is reloaded (io.allocate.valid) at the same time a
  //     directory result lands, new_meta/new_request may disagree with the io.directory.bits/request
  //     form used here. That can only produce a SPURIOUS want: the migrate action below is guarded by
  //     `!(io.allocate.valid && io.allocate.bits.repeat)` so it never fires on mismatched metadata,
  //     and the cost is one wasted cycle of destination fencing. Using io.allocate inside the action
  //     is safe — only this wire feeds dstClaim.
  migFastWantW := params.micro.enableSetBalancing.B && migAdviceValidReg &&
                  io.directory.valid &&
                  !(migrating && !w_dread) &&                                   // not the 2nd dir-read branch
                  request.prio(0) && !request.control &&                        // A-channel demand
                  !io.directory.bits.hit && io.directory.bits.state =/= INVALID && // eviction needed
                  !io.directory.bits.dirty && !io.directory.bits.displaced &&   // migClean
                  !io.directory.bits.clients.orR &&                             // migEligible (fast path)
                  io.migOffer.valid && io.migOffer.bits =/= request.set

  // Declined: the victim was migratable but no destination was on offer. The assess block's
  // when-chain falls through to its normal-eviction `.otherwise` on its own, so nothing else is
  // needed here — approved decline-and-skip.
  val migFastDecline = params.micro.enableSetBalancing.B && migAdviceValidReg &&
                       io.directory.valid &&
                       !(migrating && !w_dread) &&
                       request.prio(0) && !request.control &&
                       !io.directory.bits.hit && io.directory.bits.state =/= INVALID &&
                       !io.directory.bits.dirty && !io.directory.bits.displaced &&
                       !io.directory.bits.clients.orR &&
                       !io.migOffer.valid
  when (migFastDecline) {
    migAbort := true.B
    if (params.micro.sbcDebug) {
      printf(p"[SBC] MIG-DECLINE srcSet=${request.set} srcWay=${io.directory.bits.way} reason=no-offer\n")
    }
  }
  when (migDeferred && w_rprobeacklast) {
    migDeferred := false.B
    when (migDeferWantW) {
      // The client did not return data: the line is still clean and now provably unheld. Migrate.
      migrating  := true.B
      migDstSet  := migStartDst
      s_dread    := false.B  // 2nd dir-read of dstSet picks dstWay or falls back
      w_dread    := false.B
      migAttempt := true.B
      if (params.micro.sbcDebug) {
        printf(p"[SBC] MIG-PROBE-CLEAR srcSet=${request.set} srcWay=${migSrcWay}\n")
        printf(p"[SBC] MIG-START srcSet=${request.set} srcWay=${migSrcWay} dstSet=${migStartDst}\n")
      }
    } .otherwise {
      // Two ways to land here, both falling back to a normal eviction - w_rprobeackfirst is already
      // true, so SourceC fires immediately and c.bits.opcode picks ReleaseData/Release for us.
      //   dirty   - ProbeAckData came back, so the line is dirty now and can never be migrated
      //             (a displaced copy must be clean).
      //   decline - the line IS migratable, but no destination was on offer this cycle (none cold,
      //             one already owned, another migration in flight, or another MSHR won the grant).
      //             Approved Phase-3 semantics: decline and skip, never re-pick or stall.
      s_release    := false.B
      w_releaseack := false.B
      migAbort     := !meta.dirty   // count declines apart from dirty rejects
      if (params.micro.sbcDebug) {
        when (meta.dirty) {
          printf(p"[SBC] MIG-PROBE-DIRTY srcSet=${request.set} srcWay=${migSrcWay}\n")
        } .otherwise {
          printf(p"[SBC] MIG-DECLINE srcSet=${request.set} srcWay=${migSrcWay} reason=post-probe offerValid=${io.migOffer.valid} offerSet=${io.migOffer.bits}\n")
        }
      }
    }
  }

  // SBC Phase 2.5: liveness + invariant checks for the deferred window. migDeferred clears on exactly
  // one condition (w_rprobeacklast), so the only way to hang is a probe that never returns. The
  // watchdog turns that hang - which in Verilator looks like a run that simply never finishes - into
  // a named assert with a cycle count.
  if (params.micro.enableSetBalancing) {
    assert (!(migDeferred && io.schedule.bits.a.valid), "SBC: outer Acquire issued during deferred probe (R1 gate broken)")
    assert (!(migDeferred && io.status.bits.dstValid),  "SBC: destination fenced before the probe completed (adds hold-and-wait)")
    assert (!(migDeferred && migrating),                "SBC: migDeferred and migrating are mutually exclusive")
    assert (!(migDeferred && !s_release),               "SBC: release committed while the migrate decision was still open")
    // SBC Phase 2.5b: the two decide points must never both ask in one cycle - they would both act on
    // the single grant and both set `migrating`.
    assert (!(migFastWantW && migDeferWantW),           "SBC: both migrate decide points fired in one cycle")
    assert (!migStartNow || io.migOffer.valid,          "SBC: migration started off an invalid destination offer")
    assert (!migStartNow || migStartDst =/= request.set, "SBC: migration destination equals its own source set")
    // SBC Phase 3 (1f): a paired source may only ever spill into its own partner.
    if (params.micro.sbcForceDstSet < 0) {
      assert (!io.dstClaim.valid || !pairValidReg || io.dstClaim.bits === pairSetReg,
              "SBC: paired source migrated outside its partner set")
    }
    val migDeferCtr = RegInit(0.U(16.W))
    when (!migDeferred) { migDeferCtr := 0.U } .otherwise { migDeferCtr := migDeferCtr + 1.U }
    assert (migDeferCtr < 1000.U, "SBC: migDeferred stuck - eviction probe never completed")
  }

  when (io.sinkd.valid) {
    when (io.sinkd.bits.opcode === Grant || io.sinkd.bits.opcode === GrantData) {
      sink := io.sinkd.bits.sink
      w_grantfirst := true.B
      w_grantlast := io.sinkd.bits.last
      // Record if we need to prevent taking ownership
      bad_grant := io.sinkd.bits.denied
      // Allow wormhole routing for requests whose first beat has offset 0
      w_grant := request.offset === 0.U || io.sinkd.bits.last
      params.ccover(io.sinkd.bits.opcode === GrantData && request.offset === 0.U, "MSHR_GRANT_WORMHOLE", "Wormhole routing of grant response data")
      params.ccover(io.sinkd.bits.opcode === GrantData && request.offset =/= 0.U, "MSHR_GRANT_SERIAL", "Sequential routing of grant response data")
      gotT := io.sinkd.bits.param === toT
    }
    .elsewhen (io.sinkd.bits.opcode === ReleaseAck) {
      w_releaseack := true.B
    }
  }
  when (io.sinke.valid) {
    w_grantack := true.B
  }
  // SBC Phase 1: the SetCopyUnit finished the block copy for this MSHR's migration
  when (io.copy_done) {
    w_copy := true.B
    if (params.micro.sbcDebug) {
      printf(p"[SBC] COPY-DONE srcSet=${request.set} srcWay=${migSrcWay} dstSet=${migDstSet} dstWay=${migDstWay}\n")
    }
  }

  // Bootstrap new requests
  val allocate_as_full = WireInit(new FullRequest(params), init = io.allocate.bits)
  val new_meta = Mux(io.allocate.valid && io.allocate.bits.repeat, final_meta_writeback, io.directory.bits)
  val new_request = Mux(io.allocate.valid, allocate_as_full, request)
  val new_needT = needT(new_request.opcode, new_request.param)
  val new_clientBit = params.clientBit(new_request.source)
  val new_skipProbe = Mux(skipProbeN(new_request.opcode, params.cache.hintsSkipProbe), new_clientBit, 0.U)

  val prior = cacheState(final_meta_writeback, true.B)
  def bypass(from: CacheState, cover: Boolean)(implicit sourceInfo: SourceInfo) {
    if (cover) {
      params.ccover(prior === from.code, s"MSHR_${from}_BYPASS", s"State bypass transition from ${from} ${cfg}")
    } else {
      assert(!(prior === from.code), cf"State bypass from ${from} should be impossible ${cfg}")
    }
  }

  when (io.allocate.valid && io.allocate.bits.repeat) {
    bypass(S_INVALID,   f || p) // Can lose permissions (probe/flush)
    bypass(S_BRANCH,    b)      // MMIO read to read-only device
    bypass(S_BRANCH_C,  b && c) // you need children to become C
    bypass(S_TIP,       true)   // MMIO read || clean release can lead to this state
    bypass(S_TIP_C,     c)      // needs two clients || client + mmio || downgrading client
    bypass(S_TIP_CD,    c)      // needs two clients || client + mmio || downgrading client
    bypass(S_TIP_D,     true)   // MMIO write || dirty release lead here
    bypass(S_TRUNK_C,   c)      // acquire for write
    bypass(S_TRUNK_CD,  c)      // dirty release then reacquire
  }

  when (io.allocate.valid) {
    assert (!request_valid || (no_wait && io.schedule.fire))
    request_valid := true.B
    request := io.allocate.bits
    // SBC Phase 2: latch migrate advice for this set (suppressed on repeat allocations).
    migAdviceValidReg := io.migAdvice && !io.allocate.bits.repeat
    // SBC Phase 3: latch this set's partner the same way.
    pairValidReg      := io.pairInfo.valid && !io.allocate.bits.repeat
    pairSetReg        := io.pairInfo.bits
  }

  // Create execution plan
  when (io.directory.valid && migrating && !w_dread) {
    // SBC Phase 2: 2nd dir-read (dstSet) result. Decide proceed vs fall back; do NOT touch meta —
    // it still holds the srcSet victim needed for the displaced install + the copy source.
    if (params.micro.sbcDebug) {
      printf(p"[SBC] DREAD-RESULT srcSet=${request.set} dstSet=${migDstSet} dstWay=${io.directory.bits.way} state=${io.directory.bits.state} dirty=${io.directory.bits.dirty} clients=${io.directory.bits.clients} displaced=${io.directory.bits.displaced}\n")
    }
    w_dread := true.B
    // 2b: accept a free (INVALID) way, or a clean/client-free/non-displaced way we silently overwrite
    // (coherence-identical to a normal clean-victim eviction — no writeback, no probe). The dst set is
    // fenced at allocation (Scheduler dstSetConflict → allocReady), so no other MSHR can be on it during
    // the copy — a collision can no longer reach here.
    val dstFree      = io.directory.bits.state === INVALID
    val dstEvictable = io.directory.bits.state =/= INVALID && !io.directory.bits.dirty &&
                       !io.directory.bits.clients.orR && !io.directory.bits.displaced
    when (dstFree || dstEvictable) {
      migDstWay   := io.directory.bits.way
      s_copy      := false.B  // now run: copy → dir-write #1 (displaced) → dir-write #2 (refill)
      w_copy      := false.B
      s_dmeta     := false.B
      s_writeback := false.B
      if (params.micro.sbcDebug) {
        when (!dstFree) { printf(p"[SBC] EVICT-DST srcSet=${request.set} dstSet=${migDstSet} dstWay=${io.directory.bits.way}\n") }
      }
    } .otherwise {                               // dst set has no free or evictable way → fall back
      migrating    := false.B
      migAbort     := true.B   // aborted++
      migRejectDst := true.B   // block this dst in the DSS so the next pick rotates
      s_release    := false.B  // release the (clean, client-free) victim and refill normally
      w_releaseack := false.B
      if (params.micro.sbcDebug) { printf(p"[SBC] ABORT-DST srcSet=${request.set} dstSet=${migDstSet}\n") }
    }
  } .elsewhen (io.directory.valid || (io.allocate.valid && io.allocate.bits.repeat)) {
    meta_valid := true.B
    meta := new_meta
    probes_done := 0.U
    probes_toN := 0.U
    probes_noT := false.B
    gotT := false.B
    bad_grant := false.B

    // These should already be either true or turning true
    // We clear them here explicitly to simplify the mux tree
    s_rprobe         := true.B
    w_rprobeackfirst := true.B
    w_rprobeacklast  := true.B
    s_release        := true.B
    w_releaseack     := true.B
    s_pprobe         := true.B
    s_acquire        := true.B
    s_flush          := true.B
    w_grantfirst     := true.B
    w_grantlast      := true.B
    w_grant          := true.B
    w_pprobeackfirst := true.B
    w_pprobeacklast  := true.B
    w_pprobeack      := true.B
    s_probeack       := true.B
    s_grantack       := true.B
    s_execute        := true.B
    w_grantack       := true.B
    s_writeback      := true.B
    // SBC: reload now waits for mig_ready, so a migration can never be reset away here.
    assert (!migrating, "SBC: migration dropped at assess-reset without retiring")
    // SBC Phase 1: migration scoreboard defaults (inert unless cleared by a migrate request)
    s_copy           := true.B
    w_copy           := true.B
    s_dmeta          := true.B
    s_dread          := true.B
    w_dread          := true.B
    migrating        := false.B

    // For C channel requests (ie: Release[Data])
    when (new_request.prio(2) && (!params.firstLevel).B) {
      s_execute := false.B
      // Do we need to go dirty?
      when (new_request.opcode(0) && !new_meta.dirty) {
        s_writeback := false.B
      }
      // Does our state change?
      when (isToB(new_request.param) && new_meta.state === TRUNK) {
        s_writeback := false.B
      }
      // Do our clients change?
      when (isToN(new_request.param) && (new_meta.clients & new_clientBit) =/= 0.U) {
        s_writeback := false.B
      }
      assert (new_meta.hit)
    }
    // For X channel requests (ie: flush)
    .elsewhen (new_request.control && params.control.B) { // new_request.prio(0)
      s_flush := false.B
      // Do we need to actually do something?
      when (new_meta.hit) {
        s_release := false.B
        w_releaseack := false.B
        // Do we need to shoot-down inner caches?
        when ((!params.firstLevel).B && (new_meta.clients =/= 0.U)) {
          s_rprobe := false.B
          w_rprobeackfirst := false.B
          w_rprobeacklast := false.B
        }
      }
    }
    // For A channel requests
    .otherwise { // new_request.prio(0) && !new_request.control
      s_execute := false.B
      // Do we need an eviction?
      when (!new_meta.hit && new_meta.state =/= INVALID) {
        // SBC Phase 2: migrate-on-eviction. If this set is a hot migration source (advice latched
        // at allocate) and the victim is clean, client-free, and not already displaced, migrate the
        // victim to a cold set instead of releasing it; the demand refill then reuses the freed way.
        // Otherwise fall through to a normal eviction (bit-identical to baseline).
        // SBC Phase 2.5 (probe-then-migrate): the test is split around the eviction probe.
        //   migClean    - what we can decide up front. Dirty and already-displaced victims can never
        //                 migrate, and no probe changes that.
        //   migEligible - the fast path: the victim is already client-free, so migrate at once with
        //                 no probe. This is exactly the Phase-2 behaviour and must stay free.
        //   migProbe    - NEW: clean, but the directory claims a client holds it. That bit is stale
        //                 far more often than not (rocket's L1 drops clean lines silently), and a
        //                 normal eviction of this victim would send the very probe that settles it.
        //                 So send it, and decide when it comes back.
        val migClean    = !new_meta.dirty && !new_meta.displaced
        val migEligible = migClean && !new_meta.clients.orR
        val migProbe    = migClean && (!params.firstLevel).B && new_meta.clients.orR
        if (params.micro.sbcDebug) {
          printf(p"[SBC] EVICT-ASSESS srcSet=${new_request.set} way=${new_meta.way} adviceValid=${migAdviceValidReg} offerValid=${io.migOffer.valid} offerSet=${io.migOffer.bits} eligible=${migEligible} dirty=${new_meta.dirty} clients=${new_meta.clients} displaced=${new_meta.displaced}\n")
        }
        // The `!(allocate.valid && repeat)` guard makes new_meta === io.directory.bits, which is the
        // form migFastWantW was evaluated from — see the loop-freedom note at its definition.
        when (migFastWantW && !(io.allocate.valid && io.allocate.bits.repeat)) {
          if (params.micro.sbcDebug) {
            printf(p"[SBC] MIG-START srcSet=${new_request.set} srcWay=${new_meta.way} dstSet=${migStartDst}\n")
          }
          migrating  := true.B
          migDstSet  := migStartDst
          migSrcWay  := new_meta.way
          s_dread    := false.B  // 2nd dir-read of dstSet (preferInvalid) picks dstWay or falls back
          w_dread    := false.B
          migAttempt := true.B   // attempted++
        } .elsewhen (params.micro.enableSetBalancing.B && migAdviceValidReg && migProbe) {
          // Schedule the eviction probe and STOP. Deliberately do NOT set s_release/w_releaseack here
          // (that would commit to throwing the line away) and do NOT set migrating (that would reserve
          // a destination before we know the victim is really migratable). The decision block above
          // resumes on w_rprobeacklast. `new_meta.way` is only valid this cycle, so latch it now.
          migDeferred      := true.B
          migSrcWay        := new_meta.way
          s_rprobe         := false.B
          w_rprobeackfirst := false.B
          w_rprobeacklast  := false.B
          if (params.micro.sbcDebug) {
            printf(p"[SBC] MIG-DEFER srcSet=${new_request.set} srcWay=${new_meta.way} clients=${new_meta.clients}\n")
          }
        } .elsewhen (new_meta.displaced) {
          // SBC Phase 2: reclaim a displaced victim (the last-resort directory victim). A displaced
          // line is clean + client-free by construction and its address maps to a DIFFERENT set than
          // the one it sits in, so it can be neither written back nor released — a Release would carry
          // the wrong address (this is what the displaced-victim assert below guards). Drop it
          // silently: no release, no probe. The demand refill (s_acquire/s_writeback, set in the
          // acquire block) overwrites this way with the demanded line.
          if (params.micro.sbcDebug) {
            printf(p"[SBC] EVICT-DISPLACED-RECLAIM srcSet=${new_request.set} srcWay=${new_meta.way}\n")
          }
        } .otherwise {
          if (params.micro.sbcDebug) {
            printf(p"[SBC] EVICT-NORMAL srcSet=${new_request.set} srcWay=${new_meta.way}\n")
          }
          s_release := false.B
          w_releaseack := false.B
          // Do we need to shoot-down inner caches?
          when ((!params.firstLevel).B & (new_meta.clients =/= 0.U)) {
            s_rprobe := false.B
            w_rprobeackfirst := false.B
            w_rprobeacklast := false.B
          }
        }
      }
      // Do we need an acquire?
      when (!new_meta.hit || (new_meta.state === BRANCH && new_needT)) {
        s_acquire := false.B
        w_grantfirst := false.B
        w_grantlast := false.B
        w_grant := false.B
        s_grantack := false.B
        s_writeback := false.B
      }
      // Do we need a probe?
      when ((!params.firstLevel).B && (new_meta.hit &&
            (new_needT || new_meta.state === TRUNK) &&
            (new_meta.clients & ~new_skipProbe) =/= 0.U)) {
        s_pprobe := false.B
        w_pprobeackfirst := false.B
        w_pprobeacklast := false.B
        w_pprobeack := false.B
        s_writeback := false.B
      }
      // Do we need a grantack?
      when (new_request.opcode === AcquireBlock || new_request.opcode === AcquirePerm) {
        w_grantack := false.B
        s_writeback := false.B
      }
      // Becomes dirty?
      when (!new_request.opcode(2) && new_meta.hit && !new_meta.dirty) {
        s_writeback := false.B
      }
    }
  }
}
