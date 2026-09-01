/* sip_common.h — shared primitives for the serve-in-place tests (003 §10.6).
 *
 * Both sw/serve_in_place_test.c (single-core, S1/S2/S5..S12) and sw/serve_in_place_dual.c
 * (dual-core, S3/S4) include this so the address map, counters and helpers cannot drift.
 *
 * Geometry (VerilatorRocket8KL116KL2Sip*Config): L2 = 8 sets x 8 ways x 64B, 1 bank.
 * Pairing PINNED 5<->6 via sbcForceDstSet=6. D$ = 2 sets x 2 ways => D$ set = L2 set & 1.
 */
#ifndef SIP_COMMON_H
#define SIP_COMMON_H

#include <stdio.h>
#include <stdint.h>
#include "sbc_mmio.h"

/* ---- real bus transactions, never optimized away ---- */
static inline uint64_t do_ld(uintptr_t a) {
    uint64_t v; asm volatile("ld %0, 0(%1)" : "=r"(v) : "r"(a) : "memory"); return v;
}
static inline void do_st(uintptr_t a, uint64_t v) {
    asm volatile("sd %0, 0(%1)" :: "r"(v), "r"(a) : "memory");
}
static inline void fence_rw(void) { asm volatile("fence rw, rw" ::: "memory"); }

/* ---- geometry / set map ---- */
#define L2_SETS   8
#define L2_WAYS   8
#define LINE      64
#define HOT_SET   5    /* migration source        */
#define PARTNER   6    /* forced migration dest    */
#define SCRUB_ODD 3    /* evicts D$ set 1 (HOT parity)     */
#define SCRUB_EVEN 2   /* evicts D$ set 0 (PARTNER parity) */
#define DRAM_BASE 0x81000000UL

#define NPARK   16           /* park working set (> ways -> HOT stays hot) */
#define SCRUB_TAG_BASE 3000
#define FLOOD_TAG_BASE 6000
#define FLUSH_TAG_BASE 10000
#define INIT_TAGS 448        /* covers every case's tag range [k*32, k*32+32) */

/* address for L2 set s, tag t: tag == t, L2 set == s, D$ set == s & 1 */
static inline uintptr_t addr(int s, int t) {
    return DRAM_BASE + (uintptr_t)t * (L2_SETS * LINE) + (uintptr_t)s * LINE;
}
static uint64_t sink;

/* Capture the clean golden values of `n` tags in set `s` (a plain load leaves them clean, so they
 * stay migratable). Data checks compare against these, never against a synthesized constant - so no
 * global seed/flush is needed, and sets 0/1/4/7 stay COLD, which is what lets the DSS ever pick a
 * destination (a global flush would heat every set to max and migrations would never fire). */
static void cap(int s, int base, int n, uint64_t *g) {
    for (int i = 0; i < n; i++) g[i] = do_ld(addr(s, base + i));
}

/* ---- MMIO counter snapshot ---- */
typedef struct {
    uint64_t mig, sh, sm, sw, sp, dr, dd, sc, spm, hb, pk, att, abo;
} snap_t;
static void snap(snap_t *x) {
    x->mig = sbc_rd(SBC_MIGRATIONS);   x->sh  = sbc_rd(SBC_SECHITS);
    x->sm  = sbc_rd(SBC_SECMISS);      x->sw  = sbc_rd(SBC_SECWRITE);
    x->sp  = sbc_rd(SBC_SECPROBE);     x->dr  = sbc_rd(SBC_DISPRELEASE);
    x->dd  = sbc_rd(SBC_DISPDROP);     x->sc  = sbc_rd(SBC_SECC);
    x->spm = sbc_rd(SBC_SECPERM);      x->hb  = sbc_rd(SBC_HOMEBRANCH);
    x->pk  = sbc_rd(SBC_PARKED);       x->att = sbc_rd(SBC_ATTEMPTED);
    x->abo = sbc_rd(SBC_ABORTED);
}

/* AT[HOT_SET] must read {assocSet=PARTNER, sd=0}. */
static int paired_5_6(void) {
    sbc_wr(SBC_SETSEL, HOT_SET);
    fence_rw();
    uint64_t v = sbc_rd(SBC_ATASSOC);
    int assoc = (int)(v & 0xff);
    int sd    = (int)((v >> 8) & 1);
    return (assoc == PARTNER) && (sd == 0);
}

/* Hammer HOT_SET with `tagb..tagb+NPARK` until at least `want` migrations commit. Returns the delta.
 * sbcForceDstSet pins EVERY migration into ONE partner set (8 ways); left alone it fills with
 * displaced lines and migrations abort for lack of an evictable way. So top up a FEW evictable
 * (clean, native) ways in the partner row every several loops - just enough that a migration can land
 * by overwriting one (the dstEvictable path), without flooding out the displaced lines we just parked.
 * Displaced ways are last-resort victims, so they persist while the fresh natives absorb the eviction.
 * Top-up tags are far from every case's range so they never alias a parked line. */
static uint64_t park_n(int tagb, int want) {
    uint64_t m0 = sbc_rd(SBC_MIGRATIONS);
    for (int loop = 0; loop < 2000; loop++) {
        if ((loop % 6) == 0)
            for (int k = 0; k < 3; k++) sink += do_ld(addr(PARTNER, 50000 + (loop & 0x1f) * 3 + k));
        for (int i = 0; i < NPARK; i++) sink += do_ld(addr(HOT_SET, tagb + i));
        if (sbc_rd(SBC_MIGRATIONS) - m0 >= (uint64_t)want) break;
    }
    return sbc_rd(SBC_MIGRATIONS) - m0;
}
static uint64_t park(int tagb) { return park_n(tagb, 1); }

/* Evict a D$ set without touching HOT_SET or PARTNER. par=1 -> D$ set 1 (via SCRUB_ODD),
 * par=0 -> D$ set 0 (via SCRUB_EVEN). Touches 4 distinct lines (2x ways) so eviction is
 * deterministic under a non-LRU replacement. */
static void scrub_l1(int par) {
    int s = par ? SCRUB_ODD : SCRUB_EVEN;
    for (int i = 0; i < 4; i++) sink += do_ld(addr(s, SCRUB_TAG_BASE + i));
}

/* Flood an L2 set with n fresh distinct tags to evict its ways. */
static void flood(int s, int tagb, int n) {
    for (int i = 0; i < n; i++) sink += do_ld(addr(s, tagb + i));
}

#endif /* SIP_COMMON_H */
