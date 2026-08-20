/* dst_collision_repro.c — deterministic repro for the SBC dst-set collision bug.
 *
 * Built on migration_stress_test.c (same 8 sets x 8 ways x 64B, 1 bank, set = addr[8:6]).
 * Pure loads/stores, NO MMIO. Pair this with a config that sets sbcForceDstSet = DST, so EVERY
 * migration targets one known set. Then two interleaved streams collide on that set every burst:
 *
 *   Stream 1 — heat HOT_SRC: a rotating miss stream (tags > ways) keeps the source set hot, so
 *              migrations keep firing (each one forced to DST).
 *   Stream 2 — hammer DST:   continuous demand (load+store, rotating tags) keeps allocating MSHRs
 *              on DST, so a demand lands in the migrant's unfenced [advice->gate] window.
 *
 * Expect (BEFORE the fix): the inner-D TLMonitor assert fires every run; [SBC] log shows
 *   DST-COLLIDE migMshr!=otherMshr on dstSet=DST and an INNER-D offender whose sink != the migrant.
 * Expect (AFTER the gate-time yield-to-demand abort fix): no assert; each DST-COLLIDE is replaced by
 *   a clean ABORT-DST; data still PASS.
 *
 * Data integrity (secondary): the bug is protocol-illegal, not corrupting, so PASS is expected
 * both before and after — the assert is the real signal.
 */

#include <stdio.h>
#include <stdint.h>

static inline uint64_t do_ld(uintptr_t a) {
    uint64_t v; asm volatile("ld %0, 0(%1)" : "=r"(v) : "r"(a) : "memory"); return v;
}
static inline void do_st(uintptr_t a, uint64_t v) {
    asm volatile("sd %0, 0(%1)" :: "r"(v), "r"(a) : "memory");
}

#define L2_SETS   8
#define LINE      64
#define HOT_SRC   5          /* hot migration source set */
#define DST       0          /* forced migration destination — MUST equal sbcForceDstSet in the config */
#define NHOT      16         /* > ways -> source set keeps missing -> stays hot */
#define NDST      16         /* > ways -> dst demand keeps missing -> MSHRs keep allocating on DST */
#define BURST     30000      /* long burst -> thousands of [advice->gate] windows */

#define DRAM_BASE 0x81000000UL
static inline uintptr_t set_addr(int s, int t) {
    return DRAM_BASE + (uintptr_t)t * (L2_SETS * LINE) + (uintptr_t)s * LINE;
}

static uint64_t sink;

int main(void) {
    printf("==== SBC dst-collision repro (HOT_SRC=%d forced DST=%d) ====\n", HOT_SRC, DST);

    /* golden for the source set (loads keep it clean; migrated/displaced lines must read back intact) */
    uint64_t hot_g[NHOT];
    for (int t = 0; t < NHOT; t++) hot_g[t] = do_ld(set_addr(HOT_SRC, t));

    /* golden for the destination set: last value written per tag */
    uint64_t dst_g[NDST];
    for (int t = 0; t < NDST; t++) dst_g[t] = 0;

    /* Interleave the two streams so a DST demand lands in the migrant's unfenced window. */
    for (int it = 0; it < BURST; it++) {
        sink += do_ld(set_addr(HOT_SRC, it % NHOT));        /* stream 1: keep source hot */

        int dt = it % NDST;                                 /* stream 2: keep DST under demand */
        uint64_t v = 0xD00D ^ ((uint64_t)it << 3);
        do_st(set_addr(DST, dt), v);                        /* write-allocate miss on DST */
        dst_g[dt] = v;
        sink += do_ld(set_addr(DST, (dt + 1) % NDST));      /* extra DST demand */
    }

    /* Verify both regions survived migration + collision. */
    int ok = 1;
    for (int t = 0; t < NHOT; t++)
        if (do_ld(set_addr(HOT_SRC, t)) != hot_g[t]) {
            printf("HOT MISMATCH tag %d: got 0x%lx want 0x%lx\n", t, do_ld(set_addr(HOT_SRC, t)), hot_g[t]);
            ok = 0;
        }
    for (int t = 0; t < NDST; t++)
        if (do_ld(set_addr(DST, t)) != dst_g[t]) {
            printf("DST MISMATCH tag %d: got 0x%lx want 0x%lx\n", t, do_ld(set_addr(DST, t)), dst_g[t]);
            ok = 0;
        }

    printf(ok ? "PASS: data correct (the assert, not data, is the bug signal — see [SBC] log)\n"
              : "FAIL: data corrupted\n");
    return ok ? 0 : 1;
}
