/* sbc_mmio.h — single source of truth for the SBC MMIO register map (Control.scala).
 *
 * CLAUDE.md rule: if you change any register offset in Control.scala, update this header in the same
 * change. Both sw/migration_stress_test.c and sw/serve_in_place_test.c include this file so the two
 * binaries cannot drift.
 */
#ifndef SBC_MMIO_H
#define SBC_MMIO_H

#include <stdint.h>

/* Control block base — InclusiveCacheParameters.L2ControlAddress. */
#define L2_CTRL_BASE   0x2010000UL

/* Read-only stats + control (offsets from L2_CTRL_BASE). */
#define SBC_SETSEL       (L2_CTRL_BASE + 0x300)  /* R/W set index for saturation / AT read-back        */
#define SBC_SETSAT       (L2_CTRL_BASE + 0x308)  /* R   saturation of the selected set                 */
#define SBC_COLDESTSET   (L2_CTRL_BASE + 0x310)  /* R   DSS coldest candidate set                      */
#define SBC_COLDESTLEVEL (L2_CTRL_BASE + 0x318)  /* R   saturation of the coldest candidate            */
#define SBC_STATUS       (L2_CTRL_BASE + 0x320)  /* R   bit0=enabled, bit1=coldestValid, bit2=AT.valid */
#define SBC_MIGRATIONS   (L2_CTRL_BASE + 0x328)  /* R   migrations committed                           */
#define SBC_SECHITS      (L2_CTRL_BASE + 0x330)  /* R   secondary hits                                 */
#define SBC_SECMISS      (L2_CTRL_BASE + 0x338)  /* R   secondary misses                               */
#define SBC_BALANCESET   (L2_CTRL_BASE + 0x340)  /* W   arm migration for the written source set       */
#define SBC_ATTEMPTED    (L2_CTRL_BASE + 0x348)  /* R   migrations attempted                           */
#define SBC_ABORTED      (L2_CTRL_BASE + 0x350)  /* R   migrations aborted                             */
#define SBC_RESET        (L2_CTRL_BASE + 0x358)  /* W   zero all SBC state — UNSAFE while parked (§10.9)*/
#define SBC_SECPERM      (L2_CTRL_BASE + 0x360)  /* R   secondary hits that had to acquire permission  */
/* 003 §10.5 additions: */
#define SBC_SECWRITE     (L2_CTRL_BASE + 0x368)  /* R   serves where the requester needed T            */
#define SBC_SECPROBE     (L2_CTRL_BASE + 0x370)  /* R   serves that probed a client off first          */
#define SBC_DISPRELEASE  (L2_CTRL_BASE + 0x378)  /* R   dirty parked lines written back                */
#define SBC_DISPDROP     (L2_CTRL_BASE + 0x380)  /* R   clean parked lines released with no data        */
#define SBC_SECC         (L2_CTRL_BASE + 0x388)  /* R   serves raised by a C-channel Release           */
#define SBC_HOMEBRANCH   (L2_CTRL_BASE + 0x390)  /* R   requests that found their HOME line in BRANCH  */
#define SBC_ATASSOC      (L2_CTRL_BASE + 0x398)  /* R   AT[sel]: bits[7:0]=assocSet, bit8=sd           */
#define SBC_PARKED       (L2_CTRL_BASE + 0x3A0)  /* R   live displaced lines currently resident        */
/* 004 total L2 hit-rate counters — free-running, always active (NOT SBC-gated), NOT reset by SBC_Reset */
#define SBC_L2_ACCESSES  (L2_CTRL_BASE + 0x3A8)  /* R   total primary directory lookups (hit+miss)     */
#define SBC_L2_HITS      (L2_CTRL_BASE + 0x3B0)  /* R   total primary hits                             */
#define SBC_STATSRESET   (L2_CTRL_BASE + 0x3B8)  /* W   zero ONLY event/hit counters — flow untouched  */
/* 006: migration on/off switch + main-memory traffic counters */
#define SBC_MIGRATEENABLE (L2_CTRL_BASE + 0x3C0) /* R/W master switch: gates only the START of a new
                                                     migration. 0=off (default)                       */
#define SBC_L2_MEMREADS   (L2_CTRL_BASE + 0x3C8) /* R   outer AcquireBlock — blocks read from memory   */
#define SBC_L2_MEMWRITES  (L2_CTRL_BASE + 0x3D0) /* R   outer ReleaseData — dirty blocks written       */
#define SBC_L2_MEMACQPERM  (L2_CTRL_BASE + 0x3D8)/* R   outer AcquirePerm — whole-block write, no bytes (was MEMUPGRADES) */
#define SBC_L2_MEMRELCLEAN (L2_CTRL_BASE + 0x3E0)/* R   outer Release, no data — clean eviction        */
#define SBC_L2_CYCLES      (L2_CTRL_BASE + 0x3E8)/* R   free-running L2 clock, reset by SBC_StatsReset */
/* 005 commit 1: outcome counters that follow cache-terminology.md, plus L2_StatsHold. */
#define SBC_L2_ACCESSA       (L2_CTRL_BASE + 0x3F0) /* R   an inner-A request accepted (the access)     */
#define SBC_L2_PRIMARYHIT    (L2_CTRL_BASE + 0x3F8) /* R   home line hit, enough permission             */
#define SBC_L2_SECONDARYHIT  (L2_CTRL_BASE + 0x400) /* R   served from partner set, enough permission   */
#define SBC_L2_PROBEDHIT     (L2_CTRL_BASE + 0x408) /* R   primary/secondary hit that also probed       */
#define SBC_L2_DATAMISS      (L2_CTRL_BASE + 0x410) /* R   outer A, param != BtoT                       */
#define SBC_L2_UPGRADEMISS   (L2_CTRL_BASE + 0x418) /* R   outer A, param == BtoT                       */
#define SBC_L2_SECONDSEARCH  (L2_CTRL_BASE + 0x420) /* R   partner search armed                         */
#define SBC_L2_SECONDARYMISS (L2_CTRL_BASE + 0x428) /* R   partner searched, line not found              */
#define SBC_L2_STATSHOLD     (L2_CTRL_BASE + 0x438) /* R/W freeze every event counter (not SBC_Parked)  */
/* 008: victim policy. Reads 0 (random) when plruReplacement is not built. */
#define SBC_L2_REPLACEMENT   (L2_CTRL_BASE + 0x490) /* R/W 0 = random (reset), 1 = PLRU              */

static inline uint64_t sbc_rd(uintptr_t addr) {
    volatile uint64_t *p = (volatile uint64_t *)addr;
    return *p;
}
static inline void sbc_wr(uintptr_t addr, uint64_t v) {
    volatile uint64_t *p = (volatile uint64_t *)addr;
    *p = v;
}

#ifdef L2_POLICY
/* 008 (sim tests, -DL2_POLICY=0|1): set the victim policy first thing in main. The value sits in a
 * volatile pinned to .sdata (a 0 would otherwise go to .sbss), so 0 and 1 give the same layout and
 * the two binaries differ in one data byte only. */
static volatile uint64_t sbc_l2_policy __attribute__((section(".sdata"))) = L2_POLICY;
static inline void sbc_set_policy(void) {
    sbc_wr(SBC_L2_REPLACEMENT, sbc_l2_policy);
    printf("[SBC] policy=%llu\n", (unsigned long long)sbc_rd(SBC_L2_REPLACEMENT));
}
#endif

#endif /* SBC_MMIO_H */
