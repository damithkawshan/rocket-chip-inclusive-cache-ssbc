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

static inline uint64_t sbc_rd(uintptr_t addr) {
    volatile uint64_t *p = (volatile uint64_t *)addr;
    return *p;
}
static inline void sbc_wr(uintptr_t addr, uint64_t v) {
    volatile uint64_t *p = (volatile uint64_t *)addr;
    *p = v;
}

#endif /* SBC_MMIO_H */
