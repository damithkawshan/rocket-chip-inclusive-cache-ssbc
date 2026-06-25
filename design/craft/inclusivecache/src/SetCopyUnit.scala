/*
 * Copyright 2019 SiFive, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._

// Dumb data-mover slave: copies one full cache block from (srcSet, srcWay) to (dstSet, dstWay).
// The MSHR says "copy", waits for done, then issues directory writes.
// All coherence sequencing stays in the MSHR; this module only moves data.
class SetCopyUnit(params: InclusiveCacheParameters) extends Module {
  val nBeats = params.cache.blockBytes / params.inner.manager.beatBytes

  val io = IO(new Bundle {
    // MSHR drives start for one cycle; SCU pulses done when copy is complete
    val start = Flipped(Valid(new Bundle {
      val srcSet = UInt(params.setBits.W)
      val srcWay = UInt(params.wayBits.W)
      val dstSet = UInt(params.setBits.W)
      val dstWay = UInt(params.wayBits.W)
      val mshrId = UInt(log2Ceil(params.mshrs).W)
    }))
    val done   = Output(Bool())
    // Which MSHR owns the in-flight copy (latched at start); steers done back in the Scheduler
    val doneId = Output(UInt(log2Ceil(params.mshrs).W))
    // High when no copy is in flight; Scheduler gates start on this (≤1 copy per bank)
    val idle   = Output(Bool())

    // BankedStore read port (added last in reqs → lowest priority)
    val bs_radr = Decoupled(new BankedStoreInnerAddress(params))
    val bs_rdat = Flipped(new BankedStoreInnerDecoded(params))

    // BankedStore write port (added last in reqs → lowest priority)
    val bs_wadr = Decoupled(new BankedStoreInnerAddress(params))
    val bs_wdat = new BankedStoreInnerPoison(params)

    // RaW hazard: SourceD must not write (srcSet, srcWay) while we read it
    val copy_req  = Output(new SourceDHazard(params))
    val copy_safe = Input(Bool())

    // WaR hazard: SourceD must not read (dstSet, dstWay) while we write it
    val copy_wreq  = Output(new SourceDHazard(params))
    val copy_wsafe = Input(Bool())
  })

  // FSM states
  val s_idle :: s_wsafe :: s_read :: s_write :: s_verify :: s_done :: Nil = Enum(6)
  val state = RegInit(s_idle)

  val srcSet = Reg(UInt(params.setBits.W))
  val srcWay = Reg(UInt(params.wayBits.W))
  val dstSet = Reg(UInt(params.setBits.W))
  val dstWay = Reg(UInt(params.wayBits.W))
  val mshrId = Reg(UInt(log2Ceil(params.mshrs).W))

  val blockBuf = Reg(Vec(nBeats, UInt((params.inner.manager.beatBytes * 8).W)))

  // rdAdrBeat: how many read addresses issued; rdDatBeat: how many data beats received
  val rdAdrBeat = Reg(UInt(log2Ceil(nBeats + 1).W))
  val rdDatBeat = Reg(UInt(log2Ceil(nBeats + 1).W))
  val wrBeat    = Reg(UInt(log2Ceil(nBeats + 1).W))
  // SBC Phase 2: verify pass beat counters (re-read of dst for copy-correctness check)
  val vrAdrBeat = Reg(UInt(log2Ceil(nBeats + 1).W))
  val vrDatBeat = Reg(UInt(log2Ceil(nBeats + 1).W))

  // BankedStore has 2 reg stages; data is valid 2 cycles after bs_radr.fire
  val rdat_valid = RegNext(RegNext(io.bs_radr.fire))
  // Edge-detect for stall logging: only fire on the first cycle of each stall / resume
  val prev_copy_safe  = RegNext(io.copy_safe,  true.B)
  val prev_copy_wsafe = RegNext(io.copy_wsafe, true.B)

  // Defaults
  io.done           := false.B
  io.doneId         := mshrId
  io.idle           := state === s_idle
  io.bs_radr.valid  := false.B
  io.bs_radr.bits   := 0.U.asTypeOf(new BankedStoreInnerAddress(params))
  io.bs_wadr.valid  := false.B
  io.bs_wadr.bits   := 0.U.asTypeOf(new BankedStoreInnerAddress(params))
  io.bs_wdat.data   := 0.U
  io.copy_req.set   := srcSet
  io.copy_req.way   := srcWay
  io.copy_wreq.set  := dstSet
  io.copy_wreq.way  := dstWay

  switch (state) {
    is (s_idle) {
      when (io.start.valid) {
        srcSet    := io.start.bits.srcSet
        srcWay    := io.start.bits.srcWay
        dstSet    := io.start.bits.dstSet
        dstWay    := io.start.bits.dstWay
        mshrId    := io.start.bits.mshrId
        rdAdrBeat := 0.U
        rdDatBeat := 0.U
        wrBeat    := 0.U
        state     := s_wsafe
        if (params.micro.sbcDebug) {
          printf("SetCopyUnit: SCU-START src(%d,%d) -> dst(%d,%d) mshr=%d\n",
            io.start.bits.srcSet, io.start.bits.srcWay,
            io.start.bits.dstSet, io.start.bits.dstWay,
            io.start.bits.mshrId)
        }
      }
    }

    // SBC Phase 2b (Bug B fix): a just-retired MSHR for dstSet may leave SourceD still draining
    // GrantData beats out of (dstSet,dstWay). Starting the copy now would buffer the source block
    // and then stall mid-write on copy_wsafe while the MSHR's A2 interlock holds the outer Acquire.
    // Instead, wait HERE for the WaR hazard to clear before we read the source. The destination
    // fence (dstSetConflict, raised with `migrating`) prevents any *new* request to dstSet from
    // allocating, so no fresh SourceD read of dstSet can start once we proceed — copy_wsafe stays
    // high for the whole copy.
    is (s_wsafe) {
      when (io.copy_wsafe) {
        state := s_read
      }
      if (params.micro.sbcDebug) {
        when (!io.copy_wsafe && prev_copy_wsafe) {
          printf("SetCopyUnit: WSAFE-STALL dst(%d,%d) SourceD still draining, copy start held\n", dstSet, dstWay)
        }
        when (io.copy_wsafe && !prev_copy_wsafe) {
          printf("SetCopyUnit: WSAFE-RESUME dst(%d,%d) WaR clear, starting copy\n", dstSet, dstWay)
        }
      }
    }

    is (s_read) {
      // Issue one read address per cycle, gated by RaW hazard check
      when (rdAdrBeat < nBeats.U) {
        io.bs_radr.valid        := io.copy_safe
        io.bs_radr.bits.noop   := false.B
        io.bs_radr.bits.way    := srcWay
        io.bs_radr.bits.set    := srcSet
        io.bs_radr.bits.beat   := rdAdrBeat(params.innerBeatBits - 1, 0)
        io.bs_radr.bits.mask   := Fill(params.innerMaskBits, 1.U(1.W))
        when (io.bs_radr.fire) { rdAdrBeat := rdAdrBeat + 1.U }
        if (params.micro.sbcDebug) {
          when (!io.copy_safe && prev_copy_safe) {
            printf("SetCopyUnit: READ-STALL src(%d,%d) beat=%d copy_safe went low\n", srcSet, srcWay, rdAdrBeat)
          }
          when (io.copy_safe && !prev_copy_safe) {
            printf("SetCopyUnit: READ-RESUME src(%d,%d) beat=%d\n", srcSet, srcWay, rdAdrBeat)
          }
        }
      }

      // Collect returning data beats in order
      when (rdat_valid && rdDatBeat < nBeats.U) {
        blockBuf(rdDatBeat) := io.bs_rdat.data
        rdDatBeat := rdDatBeat + 1.U
      }

      when (rdDatBeat === nBeats.U) {
        if (params.micro.sbcDebug) {
          printf("SetCopyUnit: READ-DONE src(%d,%d) -> s_write\n", srcSet, srcWay)
        }
        state := s_write
      }
    }

    is (s_write) {
      when (wrBeat < nBeats.U) {
        // Gate write by WaR hazard check so SourceD won't see stale dst data
        io.bs_wadr.valid       := io.copy_wsafe
        io.bs_wadr.bits.noop  := false.B
        io.bs_wadr.bits.way   := dstWay
        io.bs_wadr.bits.set   := dstSet
        io.bs_wadr.bits.beat  := wrBeat(params.innerBeatBits - 1, 0)
        io.bs_wadr.bits.mask  := Fill(params.innerMaskBits, 1.U(1.W))
        io.bs_wdat.data       := blockBuf(wrBeat)
        when (io.bs_wadr.fire) { wrBeat := wrBeat + 1.U }
        if (params.micro.sbcDebug) {
          when (!io.copy_wsafe && prev_copy_wsafe) {
            printf("SetCopyUnit: WRITE-STALL dst(%d,%d) beat=%d copy_wsafe went low\n", dstSet, dstWay, wrBeat)
          }
          when (io.copy_wsafe && !prev_copy_wsafe) {
            printf("SetCopyUnit: WRITE-RESUME dst(%d,%d) beat=%d\n", dstSet, dstWay, wrBeat)
          }
        }
      } .otherwise {
        vrAdrBeat := 0.U
        vrDatBeat := 0.U
        if (params.micro.sbcDebug) {
          printf("SetCopyUnit: WRITE-DONE dst(%d,%d) -> s_verify\n", dstSet, dstWay)
        }
        state     := s_verify
      }
    }

    // SBC Phase 2: verify pass — re-read (dstSet,dstWay) and assert it matches what we wrote.
    is (s_verify) {
      when (vrAdrBeat < nBeats.U) {
        io.bs_radr.valid      := true.B
        io.bs_radr.bits.noop  := false.B
        io.bs_radr.bits.way   := dstWay
        io.bs_radr.bits.set   := dstSet
        io.bs_radr.bits.beat  := vrAdrBeat(params.innerBeatBits - 1, 0)
        io.bs_radr.bits.mask  := Fill(params.innerMaskBits, 1.U(1.W))
        when (io.bs_radr.fire) { vrAdrBeat := vrAdrBeat + 1.U }
      }
      when (rdat_valid && vrDatBeat < nBeats.U) {
        assert(io.bs_rdat.data === blockBuf(vrDatBeat),
               "SetCopyUnit: copy verify mismatch dst(%d,%d) beat %d", dstSet, dstWay, vrDatBeat)
        vrDatBeat := vrDatBeat + 1.U
      }
      when (vrDatBeat === nBeats.U) { state := s_done }
    }

    is (s_done) {
      io.done := true.B
      if (params.micro.sbcDebug) {
      printf("SetCopyUnit: copy done src(%d,%d) -> dst(%d,%d)\n",
               srcSet, srcWay, dstSet, dstWay)
      }
      state := s_idle
    }
  }
}
