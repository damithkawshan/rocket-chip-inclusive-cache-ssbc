/*
 * Main-memory traffic counters (coder task 006 Part B + E): what the L2 moves to and from main
 * memory, counted at the outer port. NOT gated by enableSetBalancing - they must exist and count
 * identically in an SBC build and a non-SBC one, because they are what an A/B compares.
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._

class PerfCounterStats extends Bundle
{
  val memReads    = UInt(64.W)  // outer AcquireBlock  - a block was READ from main memory
  val memWrites   = UInt(64.W)  // outer ReleaseData   - a dirty block was WRITTEN to main memory
  val memUpgrades = UInt(64.W)  // outer AcquirePerm   - permission round trip, moves no bytes
  val memRelClean = UInt(64.W)  // outer Release       - clean eviction announced, moves no bytes
  val cycles      = UInt(64.W)  // free-running L2 clock
}

class PerfCounters(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    // Outer A, sampled AT THE PORT (io.out.a), not inside SourceA: the outer A message is single
    // beat, so one fire is one memory access, and counting at the port means outerBuf cannot
    // double count.
    val aFire   = Input(Bool())
    val aOpcode = Input(UInt(3.W))
    // Outer C, sampled at sourceC.io.req.fire - NOT io.out.c.fire, which is multi-beat for
    // ReleaseData and would count one eviction once per beat (req.fire is exactly once per release).
    val cFire   = Input(Bool())
    val cDirty  = Input(Bool())
    val clearStats = Input(Bool())
    val stats   = Output(new PerfCounterStats)
  })

  val memReads    = RegInit(0.U(64.W))
  val memWrites   = RegInit(0.U(64.W))
  val memUpgrades = RegInit(0.U(64.W))
  val memRelClean = RegInit(0.U(64.W))

  when (io.aFire && io.aOpcode === TLMessages.AcquireBlock) { memReads    := memReads    + 1.U }
  when (io.aFire && io.aOpcode === TLMessages.AcquirePerm)  { memUpgrades := memUpgrades + 1.U }
  when (io.cFire &&  io.cDirty)                             { memWrites   := memWrites   + 1.U }
  when (io.cFire && !io.cDirty)                             { memRelClean := memRelClean + 1.U }

  // Free-running L2 clock. In the SBC_StatsReset list like the others, so `sbc_read --zero -- cmd`
  // yields exactly the cycles the child ran for. This is the L2/uncore clock, which is not
  // necessarily the core clock - valid as an SBC-on vs SBC-off ratio at a fixed frequency either way.
  val cycles = RegInit(0.U(64.W))
  cycles := cycles + 1.U

  // After the increments, so a same-cycle clear wins (drops one event, harmless) - same rule as the
  // L2_Accesses/L2_Hits pair in Directory.scala.
  when (io.clearStats) {
    memReads    := 0.U
    memWrites   := 0.U
    memUpgrades := 0.U
    memRelClean := 0.U
    cycles      := 0.U
  }

  io.stats.memReads    := memReads
  io.stats.memWrites   := memWrites
  io.stats.memUpgrades := memUpgrades
  io.stats.memRelClean := memRelClean
  io.stats.cycles      := cycles
}
