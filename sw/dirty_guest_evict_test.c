/* dirty_guest_evict_test.c - task 012 V4: drive the destination write-back on a dirty GUEST.
 *
 * 012 C2 writes back a dirty, client-free destination way W. When W is a GUEST (a parked line of the
 * source set S, sitting in D) its home is S, not the row it sits in, so the write-back must go to S's
 * address (dstHome := request.set). The stress test only ever met a native W.
 *
 * WHY THE PROGRAM IS "QUIET": the first version of this test never reached the branch. Set D held
 * seven native lines from the program itself (an expected-value array, code, stack) that were dirty or
 * stale-client-held, and its one evictable way was the clean guest the previous migration had just
 * installed, so every migration recycled that guest and C2 was never offered a dirty one. So here:
 *   - the hot code, its stack and its data are aligned to 512 B and sized to stay in L2 sets 0..4,
 *     never in HOT (5) or PARTNER (6); run_012_v4.sh checks that on the ELF before any sim is built
 *   - no printf while the test runs (printf's code sits in set 6); results are printed at the end
 *   - D is emptied first (flood + Flush64), so its ways start INVALID and fill with guests only
 *   - expected values are recomputed from pat(), not stored
 *
 * Sequence: fill D with 8 guests, write them all and push the L1 copies back (every way of D is now a
 * dirty, client-free guest), then per round make ONE migration commit. It finds no free way and no
 * clean way, so C2 must write a guest back to S's address. Then dirty the new guest and repeat.
 *
 * The RTL event is judged from the sim log ("[SBC] DST-RELEASE ... guest=true"). This program judges
 * the DATA: every line written is read back and must hold its last value, which fails if a write-back
 * went to the wrong memory address.
 *
 * Config: VerilatorRocket8KL116KL2SipTestPlruConfig. Build with -DL2_POLICY=1 (switches to PLRU).
 */
#include "sip_common.h"

typedef unsigned long ulong;
#define ROUNDS 8
#define TAGB(r) (100 + (r) * 40)
#define QFN __attribute__((noinline, aligned(512)))
#define FLOOD_N 32

/* One 512 B aligned block: occupies L2 sets 0..4 only (size is checked by the runner). */
struct qd { uint64_t sink, parked0, nbad, bad[3]; uint64_t st[ROUNDS + 1][3]; };
static struct qd Q __attribute__((aligned(512)));
static char qstack[512] __attribute__((aligned(512)));   /* only the low 256 B are used */

static inline uint64_t pat(int r, int i) {
    return 0x0D16E57000000000ULL | ((uint64_t)r << 16) | (uint64_t)i;
}

/* Empty D: fill it with lines nobody will use again, then invalidate every one of them. After this
 * no way of D holds a native line, dirty or client-held. */
static void QFN clear_dst(void) {
    for (int i = 0; i < FLOOD_N; i++) Q.sink += do_ld(addr(PARTNER, FLOOD_TAG_BASE + i));
    for (int i = 0; i < FLOOD_N; i++)
        sbc_wr(L2_CTRL_BASE + 0x200, (uint64_t)addr(PARTNER, FLOOD_TAG_BASE + i));   /* Flush64 */
    for (volatile int k = 0; k < 80000; k++) ;
}

/* Read the tags of round 0 until D holds `want` guests (checked after every load). */
static uint64_t QFN fill_dst(int want) {
    for (int loop = 0; loop < 600; loop++)
        for (int i = 0; i < NPARK; i++) {
            Q.sink += do_ld(addr(HOT_SET, TAGB(0) + i));
            if (sbc_rd(SBC_PARKED) >= (uint64_t)want) return sbc_rd(SBC_PARKED);
        }
    return sbc_rd(SBC_PARKED);
}

/* Read fresh tags until ONE migration commits. With every way of D a dirty guest that migration finds
 * no free way and no clean way, so C2 has to write a guest back. Stopping at the first commit keeps
 * the fresh guest it installs from absorbing a second migration. */
static uint64_t QFN trigger(int r) {
    uint64_t m0 = sbc_rd(SBC_MIGRATIONS);
    for (int loop = 0; loop < 600; loop++)
        for (int i = 0; i < NPARK; i++) {
            Q.sink += do_ld(addr(HOT_SET, TAGB(r) + i));
            if (sbc_rd(SBC_MIGRATIONS) - m0 >= 1) return sbc_rd(SBC_MIGRATIONS) - m0;
        }
    return 0;
}

/* Write every tag of round r (the parked ones become dirty guests in D), then push the dirty L1
 * copies back so the L2 line is dirty and no client holds it. */
static void QFN dirty_round(int r) {
    for (int i = 0; i < NPARK; i++) do_st(addr(HOT_SET, TAGB(r) + i), pat(r, i));
    fence_rw();
    for (int k = 0; k < 2; k++)
        for (int i = 0; i < 4; i++) Q.sink += do_ld(addr(SCRUB_ODD, SCRUB_TAG_BASE + i));
}

static int QFN readback(void) {
    int bad = 0;
    for (int r = 0; r <= ROUNDS; r++)
        for (int i = 0; i < NPARK; i++) {
            uint64_t v = do_ld(addr(HOT_SET, TAGB(r) + i));
            if (v != pat(r, i)) {
                if (bad == 0) { Q.bad[0] = ((uint64_t)r << 8) | (uint64_t)i; Q.bad[1] = v; Q.bad[2] = pat(r, i); }
                bad++;
            }
        }
    return bad;
}

static int QFN body(void) {
    clear_dst();
    sbc_wr(SBC_MIGRATEENABLE, 1);
    Q.parked0 = fill_dst(L2_WAYS);
    dirty_round(0);
    int misfired = 0;
    for (int r = 1; r <= ROUNDS; r++) {
        uint64_t d = trigger(r);
        Q.st[r][0] = sbc_rd(SBC_PARKED);
        Q.st[r][1] = sbc_rd(SBC_MIGRATIONS);
        Q.st[r][2] = sbc_rd(SBC_DSTABORT_DIRTY) + sbc_rd(SBC_DSTABORT_HELD) + sbc_rd(SBC_DSTABORT_BOTH);
        if (d == 0) misfired++;
        dirty_round(r);
    }
    Q.nbad = (uint64_t)readback();
    return (int)Q.nbad + 1000 * misfired;
}

/* Run fn on the private stack so its frames stay in sets 0..3; the caller's stack is untouched. */
extern int run_on_stack(int (*fn)(void), void *sp);
__asm__(".text\n.balign 512\n.globl run_on_stack\n.type run_on_stack,@function\nrun_on_stack:\n"
        "  addi sp, sp, -16\n  sd ra, 8(sp)\n  sd s0, 0(sp)\n  mv s0, sp\n  mv sp, a1\n  jalr a0\n"
        "  mv sp, s0\n  ld s0, 0(sp)\n  ld ra, 8(sp)\n  addi sp, sp, 16\n  ret\n"
        ".size run_on_stack, .-run_on_stack\n");

int main(void) {
#ifdef L2_POLICY
    sbc_set_policy();
#endif
    printf("==== dirty_guest_evict_test (HOT_SET=%d PARTNER=%d rounds=%d) ====\n", HOT_SET, PARTNER, ROUNDS);
    int rc = run_on_stack(body, qstack + 256);
    int bad = rc % 1000, misfired = rc / 1000;

    printf("[V4] fill: parked=%lu (want %d)\n", (ulong)Q.parked0, L2_WAYS);
    for (int r = 1; r <= ROUNDS; r++)
        printf("[V4] round %d: parked=%lu mig=%lu dstAborts=%lu\n", r, (ulong)Q.st[r][0],
               (ulong)Q.st[r][1], (ulong)Q.st[r][2]);
    printf("[V4] final: secWrite=%lu dispRel=%lu dispDrop=%lu abortDirty=%lu abortHeld=%lu abortBoth=%lu\n",
           (ulong)sbc_rd(SBC_SECWRITE), (ulong)sbc_rd(SBC_DISPRELEASE), (ulong)sbc_rd(SBC_DISPDROP),
           (ulong)sbc_rd(SBC_DSTABORT_DIRTY), (ulong)sbc_rd(SBC_DSTABORT_HELD), (ulong)sbc_rd(SBC_DSTABORT_BOTH));
    if (bad)
        printf("[V4] DATA MISMATCH first r=%lu i=%lu got=%lx want=%lx\n", (ulong)(Q.bad[0] >> 8),
               (ulong)(Q.bad[0] & 0xff), (ulong)Q.bad[1], (ulong)Q.bad[2]);
    printf("[V4] readback lines=%d mismatches=%d triggers-without-migration=%d\n",
           (ROUNDS + 1) * NPARK, bad, misfired);
    int pass = (bad == 0) && (misfired == 0) && (Q.parked0 >= (uint64_t)L2_WAYS);
    printf("V4 dirty-guest-evict: %s\n", pass ? "PASS (data only; guest events are counted from the log)" : "FAIL");
    return pass ? 0 : 1;
}
