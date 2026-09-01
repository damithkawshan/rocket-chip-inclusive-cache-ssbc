/* serve_in_place_dual.c — 003 §10.6 S3/S4, the probe-back path, on TWO cores.
 *
 * secProbe (the 9a probe-then-serve path) can only fire when a client OTHER than the requester holds
 * the parked line: the serve block always skips the requester's own client (skipProbeN true for
 * AcquireBlock/Get), and one core has exactly one probe-capable client. So core 1 HOLDS the parked
 * line and core 0 accesses it, forcing a real probe.
 *
 * Roles: hart 0 = coordinator (parks, drives S3/S4, checks counters). hart 1 = helper (loads the
 * parked tags on command so its client bit sits on them, then idles). Sync is plain shared globals +
 * fences (coherent system). Built with a custom crt that releases hart 1 (the stock crt parks it).
 *
 * Config: VerilatorRocket8KL116KL2SipTestDualConfig.
 */
#include "sip_common.h"

typedef unsigned long ulong;

enum { CMD_NONE = 0, CMD_HOLD = 1, CMD_EXIT = 2 };
static volatile uint64_t g_go  = 0;   /* core0 -> core1: init done, you may touch memory */
static volatile uint64_t g_cmd = CMD_NONE;
static volatile uint64_t g_ack = 0;
static volatile uint64_t g_tb  = 0;

static inline uint64_t hartid(void) { uint64_t h; asm volatile("csrr %0, mhartid" : "=r"(h)); return h; }

/* core0: ask core1 to load [tb, tb+NPARK) so ITS client bit lands on the parked lines. */
static void hold_on_core1(int tb) {
    g_tb = (uint64_t)tb; fence_rw();
    g_cmd = CMD_HOLD;    fence_rw();
    while (g_ack == 0) { }         /* wait for core1 */
    g_ack = 0; fence_rw();
}

/* S3 — write a parked line that a DIFFERENT client holds -> probe it back, then serve. */
static int s3_dual(void) {
    const int TB = 3 * 32;
    for (int i = 0; i < NPARK; i++) sink += do_ld(addr(HOT_SET, TB + i));
    uint64_t md = park(TB);
    if (md == 0) { printf("S3 write-parked-held:  FAIL (no migration during park)\n"); return 0; }
    hold_on_core1(TB);                         /* core1 now holds the parked tags (clients=core1) */

    uint64_t nv[NPARK];
    snap_t a, b; snap(&a);
    for (int i = 0; i < NPARK; i++) { nv[i] = 0x5203000000000000ULL | (uint64_t)(TB + i);
                                      do_st(addr(HOT_SET, TB + i), nv[i]); }   /* core0 store -> probe core1 */
    snap(&b);
    int ok = 1;
    for (int i = 0; i < NPARK; i++) if (do_ld(addr(HOT_SET, TB + i)) != nv[i]) ok = 0;

    uint64_t dsp = b.sp - a.sp;
    int paired = paired_5_6();
    int pass = ok && paired && dsp > 0;
    printf("S3 write-parked-held:  %s  secProbe+=%lu secWrite+=%lu data=%d paired=%d parked=%lu\n",
           pass ? "PASS" : "FAIL", (ulong)dsp, (ulong)(b.sw - a.sw), ok, paired, (ulong)b.pk);
    return pass;
}

/* S4 — read a parked line that a DIFFERENT client holds in TRUNK -> probe it back, then serve. */
static int s4_dual(void) {
    const int TB = 4 * 32;
    uint64_t GH[NPARK]; cap(HOT_SET, TB, NPARK, GH);
    uint64_t md = park(TB);
    if (md == 0) { printf("S4 read-parked-held:   FAIL (no migration during park)\n"); return 0; }
    hold_on_core1(TB);                         /* core1 holds them exclusive (TRUNK) */

    snap_t a, b; snap(&a);
    int ok = 1;
    for (int i = 0; i < NPARK; i++)            /* core0 LOAD of a TRUNK line held by core1 -> probe */
        if (do_ld(addr(HOT_SET, TB + i)) != GH[i]) ok = 0;
    snap(&b);

    uint64_t dsp = b.sp - a.sp;
    int paired = paired_5_6();
    int pass = ok && paired && dsp > 0;
    printf("S4 read-parked-held:   %s  secProbe+=%lu data=%d paired=%d parked=%lu\n",
           pass ? "PASS" : "FAIL", (ulong)dsp, ok, paired, (ulong)b.pk);
    return pass;
}

static void core1_helper(void) {
    while (g_go == 0) { }              /* wait until core0 has seeded DRAM */
    fence_rw();
    for (;;) {
        while (g_cmd == CMD_NONE) { }
        fence_rw();
        uint64_t cmd = g_cmd;
        if (cmd == CMD_EXIT) { g_cmd = CMD_NONE; g_ack = 1; fence_rw(); return; }
        if (cmd == CMD_HOLD) {
            int tb = (int)g_tb;
            for (int i = 0; i < NPARK; i++) sink += do_ld(addr(HOT_SET, tb + i));
            fence_rw();
            g_cmd = CMD_NONE; g_ack = 1; fence_rw();
        }
    }
}

int main(void) {
    if (hartid() != 0) {              /* hart 1: helper, then idle forever (core0 ends the sim) */
        core1_helper();
        for (;;) asm volatile("wfi");
    }

    printf("=== serve_in_place_dual (dual-core, 003 GATE 5 S3/S4) ===\n");
    fence_rw(); g_go = 1; fence_rw();  /* release core1 (no global seed/flush: keep sets 0/1/4/7 cold) */

    int ok = 1;
    ok &= s3_dual();
    ok &= s4_dual();

    g_cmd = CMD_EXIT; fence_rw();       /* tell core1 to stop touching memory */
    while (g_ack == 0) { }

    snap_t f; snap(&f);
    printf("[SIP-DUAL-TOTALS] mig=%lu secHits=%lu secProbe=%lu secWrite=%lu secC=%lu parked=%lu\n",
           (ulong)f.mig,(ulong)f.sh,(ulong)f.sp,(ulong)f.sw,(ulong)f.sc,(ulong)f.pk);
    printf("GATE5 dual-core: %s\n", ok ? "*** PASSED ***" : "*** FAILED ***");
    return ok ? 0 : 1;
}
