/*
 * Sim-only TL channel probe for the SiFive InclusiveCache.
 *
 * Emits one printf per channel fire (matching the per-opcode format in
 * tmp.md) and maintains 64-bit fire counters per channel that are
 * periodically dumped to the simulation log. The whole block is gated by
 * micro.enablePerfProbe at Scala-elaboration time, so when the flag is
 * false nothing is added to the generated FIRRTL/Verilog and there is no
 * functional or timing impact on the cache.
 *
 * Counters are exposed as named registers (cnt_inA ... cnt_outE) so they
 * can be wired into InclusiveCacheControl's regmap in the future.
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._

// Optional perf taps surfaced by InclusiveCacheBankScheduler when
// micro.enablePerfProbe is true. All signals are 1-cycle pulses.
class SchedulerPerf(params: InclusiveCacheParameters) extends Bundle
{
  val acceptA     = Bool()                  // inner.A request fired into scheduler (total accesses)
  val secondaryA  = Bool()                  // accepted via queue path (no fresh dir lookup)
  val lookupValid = Bool()                  // dir result attributable to a new inner.A primary lookup
  val lookupHit   = Bool()                  // hit (only meaningful when lookupValid)
  val lookupSet   = UInt(params.setBits.W)  // cache set of the lookup
  val lookupWay   = UInt(params.wayBits.W)  // hit way (or victim way on miss)
  val lookupTag   = UInt(params.tagBits.W)  // tag of the lookup
}

// Sim-only L2 hit/miss probe. Counts inner.A primary lookups and emits
// per-event + periodic summary printfs.
object InclusiveCacheHitMissProbe
{
  def apply(bank: Int, params: InclusiveCacheParameters, perf: SchedulerPerf, dumpPeriod: Int): Unit = {
    val cycleCount = RegInit(0.U(64.W)); cycleCount := cycleCount + 1.U
    def mk(name: String): UInt = { val r = RegInit(0.U(64.W)); r.suggestName(name); r }
    val total     = mk(s"perf_bank${bank}_l2_total")
    val hits      = mk(s"perf_bank${bank}_l2_hits")
    val misses    = mk(s"perf_bank${bank}_l2_misses")
    val secondary = mk(s"perf_bank${bank}_l2_secondary")
    when (perf.acceptA)                       { total     := total     + 1.U }
    when (perf.lookupValid &&  perf.lookupHit){ hits      := hits      + 1.U }
    when (perf.lookupValid && !perf.lookupHit){ misses    := misses    + 1.U }
    when (perf.secondaryA)                    { secondary := secondary + 1.U }

    val i = bank
    when (perf.acceptA) {
      printf(p"[InclusiveCache] L2 bank=$i ACCESS cycle=${cycleCount}\n")
    }
    when (perf.lookupValid) {
      val addr = params.expandAddress(perf.lookupTag, perf.lookupSet, 0.U)
      val locSuffix = p" set=${perf.lookupSet} way=${perf.lookupWay} " +
        p"tag=0x${Hexadecimal(perf.lookupTag)} address=0x${Hexadecimal(addr)} cycle=${cycleCount}\n"
      when (perf.lookupHit) {
        printf(p"[InclusiveCache] L2 bank=$i HIT " + locSuffix)
      }.otherwise {
        printf(p"[InclusiveCache] L2 bank=$i MISS" + locSuffix)
      }
    }
    if (dumpPeriod > 0) {
      val tick = (cycleCount % dumpPeriod.U) === 0.U && cycleCount =/= 0.U
      when (tick) {
        printf(p"[InclusiveCache] L2 bank=$i HITMISS cycle=${cycleCount} " +
          p"total=${total} hits=${hits} misses=${misses} secondary=${secondary}\n")
      }
    }
  }
}

object InclusiveCachePerfProbe
{
  def apply(
    bank:       Int,
    params:     InclusiveCacheParameters,
    in:         TLBundle,
    out:        TLBundle,
    edgeIn:     TLEdgeIn,
    edgeOut:    TLEdgeOut,
    dumpPeriod: Int): Unit =
  {
    val cycleCount = RegInit(0.U(64.W)); cycleCount := cycleCount + 1.U

    def mkCounter(name: String): UInt = {
      val r = RegInit(0.U(64.W)); r.suggestName(name); r
    }
    val cnt_inA  = mkCounter(s"perf_bank${bank}_inA")
    val cnt_inB  = mkCounter(s"perf_bank${bank}_inB")
    val cnt_inC  = mkCounter(s"perf_bank${bank}_inC")
    val cnt_inD  = mkCounter(s"perf_bank${bank}_inD")
    val cnt_inE  = mkCounter(s"perf_bank${bank}_inE")
    val cnt_outA = mkCounter(s"perf_bank${bank}_outA")
    val cnt_outB = mkCounter(s"perf_bank${bank}_outB")
    val cnt_outC = mkCounter(s"perf_bank${bank}_outC")
    val cnt_outD = mkCounter(s"perf_bank${bank}_outD")
    val cnt_outE = mkCounter(s"perf_bank${bank}_outE")

    when (in.a.fire)  { cnt_inA  := cnt_inA  + 1.U }
    when (in.b.fire)  { cnt_inB  := cnt_inB  + 1.U }
    when (in.c.fire)  { cnt_inC  := cnt_inC  + 1.U }
    when (in.d.fire)  { cnt_inD  := cnt_inD  + 1.U }
    when (in.e.fire)  { cnt_inE  := cnt_inE  + 1.U }
    when (out.a.fire) { cnt_outA := cnt_outA + 1.U }
    when (out.b.fire) { cnt_outB := cnt_outB + 1.U }
    when (out.c.fire) { cnt_outC := cnt_outC + 1.U }
    when (out.d.fire) { cnt_outD := cnt_outD + 1.U }
    when (out.e.fire) { cnt_outE := cnt_outE + 1.U }

    val i = bank

    // ---- INNER.A ----
    when (in.a.fire) {
      val (_, last, _, beat) = edgeIn.count(in.a)
      val prefix = p"[InclusiveCache] L2 bank=$i INNER.A opcode="
      val (tag,set,_) = params.parseAddress(in.a.bits.address)
      val userSuffix = if (in.a.bits.user.elements.nonEmpty) {
        p" user=0x${Hexadecimal(in.a.bits.user.asUInt)}"
      } else {
        p" user=none"
      }
      val base = p" param=${in.a.bits.param} size=${in.a.bits.size} source=${in.a.bits.source} " +
        p"address=0x${Hexadecimal(in.a.bits.address)} tag=0x${Hexadecimal(tag)} set=${set} " +
        p"beat=${beat} last=${last} mask=0x${Hexadecimal(in.a.bits.mask)} cycle=${cycleCount}${userSuffix}"
      val suffix   = base + "\n"
      val dataLine = base + p" data=0x${Hexadecimal(in.a.bits.data)}\n"
      when (in.a.bits.opcode === TLMessages.AcquireBlock) {
        printf(prefix + "AcquireBlock" + suffix)
      }.elsewhen (in.a.bits.opcode === TLMessages.AcquirePerm) {
        printf(prefix + "AcquirePerm" + suffix)
      }.elsewhen (in.a.bits.opcode === TLMessages.ArithmeticData) {
        printf(prefix + "ArithmeticData" + dataLine)
      }.elsewhen (in.a.bits.opcode === TLMessages.LogicalData) {
        printf(prefix + "LogicalData" + dataLine)
      }.elsewhen (in.a.bits.opcode === TLMessages.Get) {
        printf(prefix + "Get" + suffix)
        printf(p"[InclusiveCache] L2 bank=$i INNER.A GET_DETAIL cycle=${cycleCount} source=${in.a.bits.source} " +
          p"address=0x${Hexadecimal(in.a.bits.address)} size=${in.a.bits.size} " +
          p"mask=0x${Hexadecimal(in.a.bits.mask)} opcode=${in.a.bits.opcode} param=${in.a.bits.param}${userSuffix}\n")
      }.elsewhen (in.a.bits.opcode === TLMessages.Hint) {
        printf(prefix + "Hint" + suffix)
      }.elsewhen (in.a.bits.opcode === TLMessages.PutFullData) {
        printf(prefix + "PutFullData" + dataLine)
      }.elsewhen (in.a.bits.opcode === TLMessages.PutPartialData) {
        printf(prefix + "PutPartialData" + dataLine)
      }.otherwise {
        printf(prefix + "unknown" + suffix)
      }
    }

    // ---- INNER.B ----
    when (in.b.fire) {
      val (_, last, _, beat) = edgeIn.count(in.b)
      val prefix = p"[InclusiveCache] L2 bank=$i INNER.B opcode="
      val (tag,set,_) = params.parseAddress(in.b.bits.address)
      val base = p" param=${in.b.bits.param} size=${in.b.bits.size} source=${in.b.bits.source} " +
        p"address=0x${Hexadecimal(in.b.bits.address)} tag=0x${Hexadecimal(tag)} set=${set} beat=${beat} last=${last}"
      val suffix   = base + "\n"
      val dataLine = base + p" data=0x${Hexadecimal(in.b.bits.data)}\n"
      when (in.b.bits.opcode === TLMessages.PutFullData) {
        printf(prefix + "PutFullData" + dataLine)
      }.elsewhen (in.b.bits.opcode === TLMessages.PutPartialData) {
        printf(prefix + "PutPartialData" + dataLine)
      }.elsewhen (in.b.bits.opcode === TLMessages.ArithmeticData) {
        printf(prefix + "ArithmeticData" + suffix)
      }.elsewhen (in.b.bits.opcode === TLMessages.LogicalData) {
        printf(prefix + "LogicalData" + suffix)
      }.elsewhen (in.b.bits.opcode === TLMessages.Get) {
        printf(prefix + "Get" + suffix)
      }.elsewhen (in.b.bits.opcode === TLMessages.Hint) {
        printf(prefix + "Hint" + suffix)
      }.elsewhen (in.b.bits.opcode === TLMessages.Probe) {
        printf(prefix + "Probe" + suffix)
      }.otherwise {
        printf(prefix + "unknown" + suffix)
      }
    }

    // ---- INNER.C ----
    when (in.c.fire) {
      val (_, last, _, beat) = edgeIn.count(in.c)
      val (tag,set,_) = params.parseAddress(in.c.bits.address)
      val prefix = p"[InclusiveCache] L2 bank=$i INNER.C opcode="
      val base = p" param=${in.c.bits.param} size=${in.c.bits.size} source=${in.c.bits.source} " +
        p"address=0x${Hexadecimal(in.c.bits.address)} tag=0x${Hexadecimal(tag)} set=${set} beat=${beat} last=${last}"
      val suffix   = base + "\n"
      val dataLine = base + p" data=0x${Hexadecimal(in.c.bits.data)}\n"
      when (in.c.bits.opcode === TLMessages.AccessAck) {
        printf(prefix + "AccessAck" + suffix)
      }.elsewhen (in.c.bits.opcode === TLMessages.AccessAckData) {
        printf(prefix + "AccessAckData" + dataLine)
      }.elsewhen (in.c.bits.opcode === TLMessages.HintAck) {
        printf(prefix + "HintAck" + suffix)
      }.elsewhen (in.c.bits.opcode === TLMessages.ProbeAck) {
        printf(prefix + "ProbeAck" + suffix)
      }.elsewhen (in.c.bits.opcode === TLMessages.ProbeAckData) {
        printf(prefix + "ProbeAckData" + dataLine)
      }.elsewhen (in.c.bits.opcode === TLMessages.Release) {
        printf(prefix + "Release" + suffix)
      }.elsewhen (in.c.bits.opcode === TLMessages.ReleaseData) {
        printf(prefix + "ReleaseData" + dataLine)
      }.otherwise {
        printf(prefix + "unknown" + suffix)
      }
    }

    // ---- INNER.D ----
    when (in.d.fire) {
      val (_, last, _, beat) = edgeIn.count(in.d)
      val prefix = p"[InclusiveCache] L2 bank=$i INNER.D opcode="
      val base = p" param=${in.d.bits.param} size=${in.d.bits.size} source=${in.d.bits.source} " +
        p"sink=${in.d.bits.sink} beat=${beat} last=${last} cycle=${cycleCount}"
      val suffix   = base + "\n"
      val dataLine = base + p" data=0x${Hexadecimal(in.d.bits.data)}\n"
      when (in.d.bits.opcode === TLMessages.AccessAck) {
        printf(prefix + "AccessAck" + suffix)
      }.elsewhen (in.d.bits.opcode === TLMessages.AccessAckData) {
        printf(prefix + "AccessAckData" + dataLine)
      }.elsewhen (in.d.bits.opcode === TLMessages.Grant) {
        printf(prefix + "Grant" + suffix)
      }.elsewhen (in.d.bits.opcode === TLMessages.GrantData) {
        printf(prefix + "GrantData" + dataLine)
      }.elsewhen (in.d.bits.opcode === TLMessages.ReleaseAck) {
        printf(prefix + "ReleaseAck" + suffix)
      }.elsewhen (in.d.bits.opcode === TLMessages.HintAck) {
        printf(prefix + "HintAck" + suffix)
      }.otherwise {
        printf(prefix + "unknown" + suffix)
      }
    }

    // ---- INNER.E ----
    when (in.e.fire) {
      val (_, last, _, beat) = edgeIn.count(in.e)
      printf(p"[InclusiveCache] L2 bank=$i INNER.E opcode=GrantAck sink=${in.e.bits.sink} beat=${beat} last=${last}\n")
    }

    // ---- OUTER.A ----
    when (out.a.fire) {
      val (_, last, _, beat) = edgeOut.count(out.a)
      val prefix = p"[InclusiveCache] L2 bank=$i OUTER.A opcode="
      val (tag,set,_) = params.parseAddress(out.a.bits.address)
      val base = p" param=${out.a.bits.param} size=${out.a.bits.size} source=${out.a.bits.source} " +
        p"address=0x${Hexadecimal(out.a.bits.address)} tag=0x${Hexadecimal(tag)} set=${set} beat=${beat} last=${last}"
      val suffix   = base + "\n"
      val dataLine = base + p" data=0x${Hexadecimal(out.a.bits.data)}\n"
      when (out.a.bits.opcode === TLMessages.AcquireBlock) {
        printf(prefix + "AcquireBlock" + suffix)
      }.elsewhen (out.a.bits.opcode === TLMessages.AcquirePerm) {
        printf(prefix + "AcquirePerm" + suffix)
      }.elsewhen (out.a.bits.opcode === TLMessages.PutFullData) {
        printf(prefix + "PutFullData" + dataLine)
      }.elsewhen (out.a.bits.opcode === TLMessages.PutPartialData) {
        printf(prefix + "PutPartialData" + dataLine)
      }.elsewhen (out.a.bits.opcode === TLMessages.ArithmeticData) {
        printf(prefix + "ArithmeticData" + dataLine)
      }.elsewhen (out.a.bits.opcode === TLMessages.LogicalData) {
        printf(prefix + "LogicalData" + dataLine)
      }.elsewhen (out.a.bits.opcode === TLMessages.Get) {
        printf(prefix + "Get" + suffix)
      }.elsewhen (out.a.bits.opcode === TLMessages.Hint) {
        printf(prefix + "Hint" + suffix)
      }.otherwise {
        printf(prefix + "unknown" + suffix)
      }
    }

    // ---- OUTER.B ----
    when (out.b.fire) {
      val (_, last, _, beat) = edgeOut.count(out.b)
      val prefix = p"[InclusiveCache] L2 bank=$i OUTER.B opcode="
      val (tag,set,_) = params.parseAddress(out.b.bits.address)
      val base = p" param=${out.b.bits.param} size=${out.b.bits.size} source=${out.b.bits.source} " +
        p"address=0x${Hexadecimal(out.b.bits.address)} tag=0x${Hexadecimal(tag)} set=${set} beat=${beat} last=${last}"
      val suffix   = base + "\n"
      val dataLine = base + p" data=0x${Hexadecimal(out.b.bits.data)}\n"
      when (out.b.bits.opcode === TLMessages.PutFullData) {
        printf(prefix + "PutFullData" + suffix)
      }.elsewhen (out.b.bits.opcode === TLMessages.PutPartialData) {
        printf(prefix + "PutPartialData" + dataLine)
      }.elsewhen (out.b.bits.opcode === TLMessages.ArithmeticData) {
        printf(prefix + "ArithmeticData" + suffix)
      }.elsewhen (out.b.bits.opcode === TLMessages.LogicalData) {
        printf(prefix + "LogicalData" + suffix)
      }.elsewhen (out.b.bits.opcode === TLMessages.Get) {
        printf(prefix + "Get" + suffix)
      }.elsewhen (out.b.bits.opcode === TLMessages.Hint) {
        printf(prefix + "Hint" + suffix)
      }.elsewhen (out.b.bits.opcode === TLMessages.Probe) {
        printf(prefix + "Probe" + suffix)
      }.otherwise {
        printf(prefix + "unknown" + suffix)
      }
    }

    // ---- OUTER.C ----
    when (out.c.fire) {
      val (_, last, _, beat) = edgeOut.count(out.c)
      val (tag,set,_) = params.parseAddress(out.c.bits.address)
      val prefix = p"[InclusiveCache] L2 bank=$i OUTER.C opcode="
      val base = p" param=${out.c.bits.param} size=${out.c.bits.size} source=${out.c.bits.source} " +
        p"address=0x${Hexadecimal(out.c.bits.address)} tag=0x${Hexadecimal(tag)} set=${set} beat=${beat} last=${last}"
      val suffix   = base + "\n"
      val dataLine = base + p" data=0x${Hexadecimal(out.c.bits.data)}\n"
      when (out.c.bits.opcode === TLMessages.AccessAck) {
        printf(prefix + "AccessAck" + suffix)
      }.elsewhen (out.c.bits.opcode === TLMessages.AccessAckData) {
        printf(prefix + "AccessAckData" + suffix)
      }.elsewhen (out.c.bits.opcode === TLMessages.HintAck) {
        printf(prefix + "HintAck" + suffix)
      }.elsewhen (out.c.bits.opcode === TLMessages.ProbeAck) {
        printf(prefix + "ProbeAck" + suffix)
      }.elsewhen (out.c.bits.opcode === TLMessages.ProbeAckData) {
        printf(prefix + "ProbeAckData" + dataLine)
      }.elsewhen (out.c.bits.opcode === TLMessages.Release) {
        printf(prefix + "Release" + suffix)
      }.elsewhen (out.c.bits.opcode === TLMessages.ReleaseData) {
        printf(prefix + "ReleaseData" + dataLine)
      }.otherwise {
        printf(prefix + "unknown" + suffix)
      }
    }

    // ---- OUTER.D ----
    when (out.d.fire) {
      val (_, last, _, beat) = edgeOut.count(out.d)
      val prefix = p"[InclusiveCache] L2 bank=$i OUTER.D opcode="
      val base = p" param=${out.d.bits.param} size=${out.d.bits.size} source=${out.d.bits.source} sink=${out.d.bits.sink} beat=${beat} last=${last}"
      val suffix   = base + "\n"
      val dataLine = base + p" data=0x${Hexadecimal(out.d.bits.data)}\n"
      when (out.d.bits.opcode === TLMessages.AccessAck) {
        printf(prefix + "AccessAck" + suffix)
      }.elsewhen (out.d.bits.opcode === TLMessages.AccessAckData) {
        printf(prefix + "AccessAckData" + dataLine)
      }.elsewhen (out.d.bits.opcode === TLMessages.HintAck) {
        printf(prefix + "HintAck" + suffix)
      }.elsewhen (out.d.bits.opcode === TLMessages.Grant) {
        printf(prefix + "Grant" + suffix)
      }.elsewhen (out.d.bits.opcode === TLMessages.GrantData) {
        printf(prefix + "GrantData" + dataLine)
      }.elsewhen (out.d.bits.opcode === TLMessages.ReleaseAck) {
        printf(prefix + "ReleaseAck" + suffix)
      }.otherwise {
        printf(prefix + "unknown" + suffix)
      }
    }

    // ---- OUTER.E ----
    when (out.e.fire) {
      val (_, last, _, beat) = edgeOut.count(out.e)
      printf(p"[InclusiveCache] L2 bank=$i OUTER.E opcode=GrantAck sink=${out.e.bits.sink} beat=${beat} last=${last}\n")
    }

    // ---- Periodic counter dump ----
    if (dumpPeriod > 0) {
      val dumpTick = (cycleCount % dumpPeriod.U) === 0.U && cycleCount =/= 0.U
      when (dumpTick) {
        printf(p"[InclusiveCache] L2 bank=$i PERF cycle=${cycleCount} " +
          p"inA=${cnt_inA} inB=${cnt_inB} inC=${cnt_inC} inD=${cnt_inD} inE=${cnt_inE} " +
          p"outA=${cnt_outA} outB=${cnt_outB} outC=${cnt_outC} outD=${cnt_outD} outE=${cnt_outE}\n")
      }
    }
  }
}
