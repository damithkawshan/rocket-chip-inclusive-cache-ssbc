#!/usr/bin/env python3
"""Task 007 commit 4 (C4, guest cap). Staged while the commit-3 gate was reading the sources.
Every replacement asserts it matches exactly once, so a moved line fails loudly instead of silently."""
import os, sys
DRY = bool(os.environ.get("DRY"))

GEN = "/home/damith/Research/repos/chipyard_performance_eval/chipyard/generators/rocket-chip-inclusive-cache"
SRC = GEN + "/design/craft/inclusivecache/src/"
CY_CFG = "/home/damith/Research/repos/chipyard_performance_eval/chipyard/generators/chipyard/src/main/scala/config/RocketConfigs.scala"

def patch(path, pairs):
    s = open(path).read()
    for old, new in pairs:
        n = s.count(old)
        if n != 1:
            sys.exit(f"FAIL {path}: expected 1 match, found {n}:\n{old[:200]}")
        s = s.replace(old, new)
    if not DRY: open(path, "w").write(s)
    print(("dry-ok " if DRY else "patched ") + path)

# ---- Parameters.scala: the one tunable ------------------------------------------------------------
patch(SRC + "Parameters.scala", [
("""                                            //     migration starts, widening the [advice->gate] collision window
""",
"""                                            //     migration starts, widening the [advice->gate] collision window
  // 007 C4: most guests one source may have parked in its partner; a migration will not start at the cap.
  // The paper's measured 2.15 lines per pairing. 0 = no cap, nothing elaborated.
  guestCap:                Int = 2,
"""),
("""  require (sbcGateStallCycles >= 0)
""",
"""  require (sbcGateStallCycles >= 0)
  require (guestCap >= 0)
"""),
])

# ---- Configs.scala: expose it on WithInclusiveCache -----------------------------------------------
patch(SRC + "Configs.scala", [
("""  sbcGateStallCycles: Int = 0,
  // 005: all measurement hardware""",
"""  sbcGateStallCycles: Int = 0,
  guestCap: Int = 2,               // 007 C4: most guests parked per source; 0 = no cap
  // 005: all measurement hardware"""),
("""        sbcGateStallCycles = sbcGateStallCycles,
        enablePerfCounters = enablePerfCounters),""",
"""        sbcGateStallCycles = sbcGateStallCycles,
        guestCap = guestCap,
        enablePerfCounters = enablePerfCounters),"""),
])

# ---- SetBalanceUnit.scala: one comparator on the index mayHold already uses ------------------------
patch(SRC + "SetBalanceUnit.scala", [
("""      // Paper section 2.3, the "sc" bit: my partner currently holds at least one line of mine.
      val mayHold      = Bool()
""",
"""      // Paper section 2.3, the "sc" bit: my partner currently holds at least one line of mine.
      val mayHold      = Bool()
      // SBC (007 C4): I already have guestCap guests parked - do not start another migration.
      val parkFull     = Bool()
"""),
("""  io.assocResp.mayHold := parkCount(io.assocQuery.bits) =/= 0.U
""",
"""  io.assocResp.mayHold := parkCount(io.assocQuery.bits) =/= 0.U
  // 007 C4: same index as mayHold, so no new sets-wide mux. guestCap = 0 elaborates no comparator.
  require (params.micro.guestCap <= params.cache.ways)
  io.assocResp.parkFull := (if (params.micro.guestCap > 0) parkCount(io.assocQuery.bits) >= params.micro.guestCap.U
                            else false.B)
"""),
])

# ---- Scheduler.scala: carry it to the MSHRs beside mayHold ---------------------------------------
patch(SRC + "Scheduler.scala", [
("""  val pairInfoMayHold = WireInit(false.B)   // paper section 2.3 sc bit
""",
"""  val pairInfoMayHold = WireInit(false.B)   // paper section 2.3 sc bit
  val pairInfoParkFull = WireInit(false.B)  // 007 C4: source is at guestCap
"""),
("""    m.io.pairInfo.bits.mayHold := pairInfoMayHold
""",
"""    m.io.pairInfo.bits.mayHold := pairInfoMayHold
    m.io.pairInfo.bits.parkFull := pairInfoParkFull
"""),
("""    pairInfoMayHold := sbu.io.assocResp.mayHold
""",
"""    pairInfoMayHold := sbu.io.assocResp.mayHold
    pairInfoParkFull := sbu.io.assocResp.parkFull
"""),
])

# ---- MSHR.scala: latch it, refuse to START a migration at the cap (all three decide points) --------
patch(SRC + "MSHR.scala", [
("""  // False means skip the second search entirely - there is provably nothing to find.
  val mayHold = Bool()
""",
"""  // False means skip the second search entirely - there is provably nothing to find.
  val mayHold = Bool()
  // SBC (007 C4): this source already has guestCap guests parked in its partner.
  val parkFull = Bool()
"""),
("""  val pairIsSrcReg      = RegInit(false.B)
""",
"""  val pairIsSrcReg      = RegInit(false.B)
  val parkFullReg       = RegInit(false.B)   // 007 C4: latched with the pairing
"""),
# live on a directory result (plan, search answer), the latch otherwise (post-probe). Register-derived
# on both arms, so it cannot close the allocReady/dstClaim loop.
("""  val pairLive          = io.directory.valid && io.pairInfo.valid && io.pairInfo.bits.isSrc
""",
"""  val pairLive          = io.directory.valid && io.pairInfo.valid && io.pairInfo.bits.isSrc
  // SBC (007 C4): at the cap, a migration may not start. Live on a directory result, latched otherwise.
  val parkFullNow       = Mux(io.directory.valid, io.pairInfo.bits.parkFull, parkFullReg)
"""),
("""    pairIsSrcReg := io.pairInfo.bits.isSrc
  }
""",
"""    pairIsSrcReg := io.pairInfo.bits.isSrc
    parkFullReg  := io.pairInfo.bits.parkFull
  }
"""),
("""  val migDeferWant = migDeferred && w_rprobeacklast && !meta.dirty &&
                     io.migOffer.valid && io.migOffer.bits =/= physSet
""",
"""  val migDeferWant = migDeferred && w_rprobeacklast && !meta.dirty &&
                     io.migOffer.valid && io.migOffer.bits =/= physSet && !parkFullNow
"""),
("""    (base && io.migOffer.valid && io.migOffer.bits =/= physSet, base && !io.migOffer.valid)
""",
"""    // 007 C4: at the cap it is a decline (counted in SBC_Aborted), exactly like "nothing on offer".
    (base && io.migOffer.valid && io.migOffer.bits =/= physSet && !parkFullNow,
     base && (!io.migOffer.valid || parkFullNow))
"""),
("""      printf(p"[SBC] MIG-DECLINE srcSet=${request.set} srcWay=${io.directory.bits.way} reason=no-offer\\n")
""",
"""      when (parkFullNow) {
        printf(p"[SBC] MIG-DECLINE srcSet=${request.set} srcWay=${io.directory.bits.way} reason=cap\\n")
      } .otherwise {
        printf(p"[SBC] MIG-DECLINE srcSet=${request.set} srcWay=${io.directory.bits.way} reason=no-offer\\n")
      }
"""),
("""reason=post-probe offerValid=${io.migOffer.valid} offerSet=${io.migOffer.bits}\\n")""",
"""reason=post-probe offerValid=${io.migOffer.valid} offerSet=${io.migOffer.bits} atCap=${parkFullNow}\\n")"""),
])

# ---- chipyard RocketConfigs.scala: T-CAP config = SIP pinning 5<->6 plus guestCap = 1 -------------
patch(CY_CFG, [
("""// SBC serve-in-place DUAL-core test config (003 §10.6 S3/S4)""",
"""// SBC 007 C4 (T-CAP): the SIP pinning 5<->6 plus guestCap = 1, so sw/sbc_guest_cap_test.c can read the
// one pairing's guest count straight off SBC_Parked. cap 1 is the easiest to see; the comparator is the
// same at any cap.
class VerilatorRocket8KL116KL2GuestCap1Config extends Config(
  new freechips.rocketchip.rocket.WithL1ICacheSets(2) ++
  new freechips.rocketchip.rocket.WithL1ICacheWays(2) ++
  new freechips.rocketchip.rocket.WithL1DCacheSets(2) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(2) ++
  new freechips.rocketchip.rocket.WithNBigCores(1) ++
  new freechips.rocketchip.subsystem.WithInclusiveCache(nWays = 8, capacityKB = 4, subBankingFactor = 2, sbcAutoMigrate = true, sbcShadow = true, sbcDebug = true, sbcForceDstSet = 6, guestCap = 1) ++
  new chipyard.config.AbstractConfig)

// SBC serve-in-place DUAL-core test config (003 §10.6 S3/S4)"""),
])
print("C4 applied")
