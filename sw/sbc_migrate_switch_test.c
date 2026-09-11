/* sbc_migrate_switch_test.c — prove SBC_MigrateEnable (0x3C0) does exactly what it claims:
 * it gates the START of a new migration and nothing else.
 *
 * Config (VerilatorRocket8KL116KL2Config): L2 = 8 sets x 8 ways x 64B, sbcAutoMigrate=ON;
 * L1 D$ = 2 sets x 2 ways x 64B (L2 victims go client-free fast, so auto-migrate fires).
 *
 * What this asserts at RUNTIME:
 *   T1 read-back  - the register returns what was written, both ways. `sbc_read --migrate=` aborts
 *                   on a read-back mismatch, so this is the property that tool depends on.
 *   T2 gate off   - with the switch OFF from reset, a hammer that reliably migrates commits NOTHING.
 *   T3 gate on    - flipping it ON makes the same hammer commit migrations. Proves T2 was the switch
 *                   doing its job and not the workload failing to trigger.
 *   T4 no strand  - flipping OFF again while lines are parked leaves the parked-line machinery
 *                   running: no new migrations, but serves/retires keep happening. (Gate G4.)
 *
 * T4's tolerance: at most one migration is in flight at a time (the one-migration-per-bank token),
 * so after flipping OFF and quiescing, the committed count must be EXACTLY stable - not "roughly".
 */
#include <stdio.h>
#include <stdint.h>
#include "sbc_mmio.h"

static inline uint64_t do_ld(uintptr_t a) {
    uint64_t v; asm volatile("ld %0, 0(%1)" : "=r"(v) : "r"(a) : "memory"); return v;
}

/* L2: 8 sets x 8 ways x 64B, 1 bank. set index = addr bits [8:6]. */
#define L2_SETS 8
#define LINE    64
#define HOT_SET 5
#define NHOT    16          /* > ways -> hot set keeps missing -> stays hot -> auto-migrate fires */
#define COLD    2           /* a cold set to act as a migration destination                       */
#define NCOLD   2
#define BURST   1500
#define QUIESCE 200         /* long enough to drain an in-flight migration before a baseline read  */

#define DRAM_BASE 0x81000000UL
static inline uintptr_t set_addr(int s, int t) {
    return DRAM_BASE + (uintptr_t)t * (L2_SETS * LINE) + (uintptr_t)s * LINE;
}

static uint64_t sink;

static void hammer(int iters) {
    for (int it = 0; it < iters; it++) {
        sink += do_ld(set_addr(HOT_SET, it % NHOT));
        if ((it & 0xF) == 0) sink += do_ld(set_addr(COLD, it % NCOLD));
    }
}

/* Write the switch and read it straight back. Returns 1 on agreement. */
static int set_migrate(int on) {
    sbc_wr(SBC_MIGRATEENABLE, (uint64_t)on);
    uint64_t rb = sbc_rd(SBC_MIGRATEENABLE) & 1;
    if (rb != (uint64_t)on) {
        printf("  read-back MISMATCH: wrote %d, read %lu\n", on, (unsigned long)rb);
        return 0;
    }
    return 1;
}

static void dump(const char *tag) {
    printf("  [%s] migrateEnable=%lu migrations=%lu parked=%lu secHits=%lu dispRelease=%lu dispDrop=%lu\n",
           tag,
           (unsigned long)(sbc_rd(SBC_MIGRATEENABLE) & 1), (unsigned long)sbc_rd(SBC_MIGRATIONS),
           (unsigned long)sbc_rd(SBC_PARKED),      (unsigned long)sbc_rd(SBC_SECHITS),
           (unsigned long)sbc_rd(SBC_DISPRELEASE), (unsigned long)sbc_rd(SBC_DISPDROP));
}

int main(void) {
    printf("==== SBC_MigrateEnable switch test ====\n");
    if (!(sbc_rd(SBC_STATUS) & 1)) {
        printf("SKIP: SBC is not built into this config (SBC_Status bit0 = 0)\n");
        return 0;
    }
    int ok = 1;

    /* T1 - read-back, both directions, before any traffic. */
    int t1 = set_migrate(1) && set_migrate(0) && set_migrate(1) && set_migrate(0);
    printf("T1 read-back (write 1/0/1/0, read each): %s\n", t1 ? "PASS" : "FAIL");
    ok &= t1;

    /* T2 - switch OFF (it is off now, and was off from reset): nothing may commit. */
    dump("T2 before");
    hammer(BURST);
    uint64_t mig_off = sbc_rd(SBC_MIGRATIONS), parked_off = sbc_rd(SBC_PARKED);
    dump("T2 after");
    int t2 = (mig_off == 0) && (parked_off == 0);
    printf("T2 gate OFF (no migration may start): %s  (migrations=%lu parked=%lu)\n",
           t2 ? "PASS" : "FAIL", (unsigned long)mig_off, (unsigned long)parked_off);
    ok &= t2;

    /* T3 - same workload, switch ON: migrations must now commit. */
    ok &= set_migrate(1);
    hammer(BURST);
    uint64_t mig_on = sbc_rd(SBC_MIGRATIONS), parked_on = sbc_rd(SBC_PARKED);
    dump("T3 after");
    int t3 = mig_on > 0;
    printf("T3 gate ON (same hammer now migrates): %s  (migrations=%lu parked=%lu)\n",
           t3 ? "PASS" : "FAIL", (unsigned long)mig_on, (unsigned long)parked_on);
    ok &= t3;

    /* T4 - flip OFF with lines parked. New migrations must stop dead; parked lines must keep being
     * served and retired. Quiesce first so an in-flight migration commits before the baseline. */
    ok &= set_migrate(0);
    hammer(QUIESCE);
    uint64_t mig_base  = sbc_rd(SBC_MIGRATIONS);
    uint64_t live_base = sbc_rd(SBC_SECHITS) + sbc_rd(SBC_DISPRELEASE) + sbc_rd(SBC_DISPDROP);
    uint64_t parked_base = sbc_rd(SBC_PARKED);
    dump("T4 baseline");
    hammer(BURST);
    uint64_t mig_end  = sbc_rd(SBC_MIGRATIONS);
    uint64_t live_end = sbc_rd(SBC_SECHITS) + sbc_rd(SBC_DISPRELEASE) + sbc_rd(SBC_DISPDROP);
    dump("T4 after");

    int t4_frozen = (mig_end == mig_base);          /* not one new migration */
    int t4_live   = (live_end > live_base) ||       /* parked lines still worked on ... */
                    (parked_base == 0);             /* ... unless there were none left to work on */
    printf("T4 flip OFF while parked: migrations %lu -> %lu (%s), "
           "serve/retire events %lu -> %lu (%s), parked at flip=%lu\n",
           (unsigned long)mig_base, (unsigned long)mig_end, t4_frozen ? "frozen, PASS" : "MOVED, FAIL",
           (unsigned long)live_base, (unsigned long)live_end, t4_live ? "still running, PASS" : "STALLED, FAIL",
           (unsigned long)parked_base);
    ok &= t4_frozen && t4_live;

    printf(ok ? "PASS: SBC_MigrateEnable gates migration start only\n"
              : "FAIL: see the failing sub-test above\n");
    return ok ? 0 : 1;
}
