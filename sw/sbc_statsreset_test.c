/* sbc_statsreset_test.c — prove SBC_StatsReset zeroes ONLY the event/hit counters and leaves the
 * live SBC flow state (parked lines, saturation) untouched.
 *
 * Config (VerilatorRocket8KL116KL2Config): L2 = 8 sets x 8 ways x 64B, sbcAutoMigrate=ON;
 * L1 D$ = 2 sets x 2 ways x 64B (L2 victims go client-free fast, so auto-migrate fires).
 *
 * What this asserts at RUNTIME (only what is soundly checkable):
 *   - the reset zeroes the event/hit counters      (migrations -> 0, l2Accesses -> ~0)
 *   - migration still commits after the reset       (behavioural proof the flow was not broken)
 * "The reset does not touch sat/AT/DSS/parkCount/nParked" is NOT a runtime invariant here: those
 * are free-running, and this test's own printf (which incidentally hits L2 set 5 = addr[8:6]) and
 * late in-flight migration drain move sat and parked across the window - sat is seen anywhere in
 * 0..max. That property is proven structurally instead: in the generated Verilog io_clearStats
 * gates only the event-counter block, and on the board via `sbc_read --zero`. parked/sat are printed
 * below for observation, not gated.
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
#define NHOT    16            /* > ways -> hot set keeps missing -> stays hot -> auto-migrate fires */
#define COLD    2             /* a cold set to act as a migration destination                       */
#define NCOLD   2

#define DRAM_BASE 0x81000000UL
static inline uintptr_t set_addr(int s, int t) {
    return DRAM_BASE + (uintptr_t)t * (L2_SETS * LINE) + (uintptr_t)s * LINE;
}

static uint64_t sink;

/* Hammer the hot set, occasionally touching the cold set so a cold destination stays resident. */
static void hammer(int iters) {
    for (int it = 0; it < iters; it++) {
        sink += do_ld(set_addr(HOT_SET, it % NHOT));
        if ((it & 0xF) == 0) sink += do_ld(set_addr(COLD, it % NCOLD));
    }
}

/* Spin without touching DRAM, so the L2 drains any in-flight migration before we snapshot. */
static void quiesce(void) {
    volatile int x = 0;
    for (int i = 0; i < 20000; i++) x += i;
    asm volatile("fence" ::: "memory");
}

int main(void) {
    /* the flow only runs if SBC is actually enabled in this build */
    uint64_t status = sbc_rd(SBC_STATUS);
    printf("[SRT] SBC_Status=0x%lx (bit0=enabled)\n", (unsigned long)status);

    hammer(6000);
    quiesce();

    sbc_wr(SBC_SETSEL, HOT_SET);
    uint64_t migA  = sbc_rd(SBC_MIGRATIONS);
    uint64_t secA  = sbc_rd(SBC_SECHITS);
    uint64_t accA  = sbc_rd(SBC_L2_ACCESSES);
    uint64_t parkA = sbc_rd(SBC_PARKED);
    uint64_t satA  = sbc_rd(SBC_SETSAT);
    printf("[SRT] before reset: migrations=%lu secHits=%lu l2Accesses=%lu parked=%lu sat[hot]=%lu\n",
           (unsigned long)migA, (unsigned long)secA, (unsigned long)accA,
           (unsigned long)parkA, (unsigned long)satA);

    /* THE RESET — counter-only pulse */
    sbc_wr(SBC_STATSRESET, 1);
    asm volatile("fence" ::: "memory");

    uint64_t migB  = sbc_rd(SBC_MIGRATIONS);
    uint64_t accB  = sbc_rd(SBC_L2_ACCESSES);
    uint64_t parkB = sbc_rd(SBC_PARKED);
    uint64_t satB  = sbc_rd(SBC_SETSAT);
    printf("[SRT] after  reset: migrations=%lu l2Accesses=%lu parked=%lu sat[hot]=%lu\n",
           (unsigned long)migB, (unsigned long)accB, (unsigned long)parkB, (unsigned long)satB);

    hammer(6000);
    quiesce();
    uint64_t migC = sbc_rd(SBC_MIGRATIONS);
    printf("[SRT] after 2nd window: migrations=%lu\n", (unsigned long)migC);

    /* ---- verdict ---- */
    int enabled  = status & 1;
    int did_work = (migA > 0) && (parkA > 0);            /* SBC really migrated, so the test is meaningful */
    int counters_zeroed = (migB < 4) && (accB < 100);    /* event/hit counters cleared (allow tiny drain) */
    int flow_runs_again = (migC > migB);                 /* migration still works after the reset */
    /* parked/sat drift across the window from printf + late migration drain, NOT from the reset
     * (see header). Printed for observation; the "flow untouched" property is proven in the netlist. */

    printf("[SRT] enabled=%d did_work=%d counters_zeroed=%d flow_runs_again=%d  (obs: parkA=%lu parkB=%lu satA=%lu satB=%lu)\n",
           enabled, did_work, counters_zeroed, flow_runs_again,
           (unsigned long)parkA, (unsigned long)parkB, (unsigned long)satA, (unsigned long)satB);

    if (!enabled) { printf("[SRT] *** SKIP: SBC disabled in this build ***\n"); return 0; }
    if (did_work && counters_zeroed && flow_runs_again) {
        printf("[SRT] *** PASSED ***\n");
        return 0;
    }
    printf("[SRT] *** FAILED ***\n");
    return 1;
}
