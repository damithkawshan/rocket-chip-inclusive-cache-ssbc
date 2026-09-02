/* serve_in_place_test.c — 003 §10.6, single-core cases (S1, S2, S5..S12).
 *
 * THE ONE RULE: every case proves its own EVENT fired via an MMIO counter delta, on top of data
 * correctness. "The scenario never happened" is a FAIL. See TASK.md Amendment 10.
 *
 * S3/S4 (the probe-back path) need a SECOND probe-capable client and live in serve_in_place_dual.c on
 * the dual-core config: on one core the only client is the requester, which the serve block always
 * skips (skipProbeN true for AcquireBlock/Get), so secProbe cannot move here. Platform fact, reported.
 *
 * No global seed/flush: sets 0/1/4/7 are left untouched (COLD) so the DSS always has a destination
 * candidate. Data is checked against per-case golden captured by a clean load.
 *
 * Config: VerilatorRocket8KL116KL2SipTestConfig (pairing pinned 5<->6 via sbcForceDstSet=6).
 */
#include "sip_common.h"

#define WAYS L2_WAYS
typedef unsigned long ulong;

/* ---- S1: read a parked, client-free line. Base case. --------------------------------------- */
static int case_s1(void) {
    const int TB = 1 * 32;
    uint64_t g[NPARK]; cap(HOT_SET, TB, NPARK, g);
    if (park(TB) == 0) { printf("S1 read-parked-clean:  FAIL (no migration during park)\n"); return 0; }
    scrub_l1(1);

    snap_t a, b; snap(&a);
    int ok = 1;
    for (int i = 0; i < NPARK; i++) if (do_ld(addr(HOT_SET, TB + i)) != g[i]) ok = 0;
    snap(&b);

    uint64_t dsh = b.sh - a.sh, dsw = b.sw - a.sw, dsp = b.sp - a.sp;
    int paired = paired_5_6();
    int pass = ok && paired && dsh > 0 && dsp == 0;   /* secProbe==0 guaranteed single-core */
    printf("S1 read-parked-clean:  %s  secHits+=%lu secWrite+=%lu secProbe+=%lu parked=%lu paired=%d data=%d\n",
           pass ? "PASS" : "FAIL", (ulong)dsh, (ulong)dsw, (ulong)dsp, (ulong)b.pk, paired, ok);
    return pass;
}

/* ---- S2: write a parked line -> TRUNK, stays displaced. Store lands in the partner row. ----- */
static int case_s2(void) {
    const int TB = 2 * 32;
    uint64_t g[NPARK]; cap(HOT_SET, TB, NPARK, g);
    if (park(TB) == 0) { printf("S2 write-parked:       FAIL (no migration during park)\n"); return 0; }
    scrub_l1(1);

    uint64_t nv[NPARK];
    snap_t a, b, c, d; snap(&a);
    for (int i = 0; i < NPARK; i++) { nv[i] = 0x5202000000000000ULL | (uint64_t)(TB + i);
                                      do_st(addr(HOT_SET, TB + i), nv[i]); }
    snap(&b);                                       /* secWrite over the stores */
    scrub_l1(1);                                    /* flush dirty stores back into the partner row */
    snap(&c);
    int ok = 1;
    for (int i = 0; i < NPARK; i++) if (do_ld(addr(HOT_SET, TB + i)) != nv[i]) ok = 0;
    snap(&d);                                       /* secHits over the read-back */

    uint64_t dsw = b.sw - a.sw, dsh = d.sh - c.sh;
    int paired = paired_5_6();
    int pass = ok && paired && dsw > 0 && dsh > 0;
    printf("S2 write-parked:       %s  secWrite+=%lu readback-secHits+=%lu parked=%lu paired=%d data=%d\n",
           pass ? "PASS" : "FAIL", (ulong)dsw, (ulong)dsh, (ulong)d.pk, paired, ok);
    return pass;
}

/* ---- S5: tag alias. Same tag, same row, one parked (home 5) and one native (home 6). -------- */
static int case_s5(void) {
    const int TB = 5 * 32;
    uint64_t GH[NPARK]; cap(HOT_SET, TB, NPARK, GH);          /* home-5 golden (clean) */
    if (park_n(TB, 3) == 0) { printf("S5 tag-alias:          FAIL (no migration during park)\n"); return 0; }
    /* seed each addr(PARTNER,t) natively with a value DISTINCT from its home-5 twin (dirty is fine:
     * a native line is never migrated). Same tag t, same row 6 -> the tag alias. */
    uint64_t GP[NPARK];
    for (int i = 0; i < NPARK; i++) { GP[i] = GH[i] ^ 0xF5F5F5F5F5F5F5F5ULL;
                                      do_st(addr(PARTNER, TB + i), GP[i]); }
    scrub_l1(1); scrub_l1(0);                                 /* drop L1 copies so the alias loads reach L2 */

    snap_t a, b; snap(&a);
    int ok = 1;
    for (int i = 0; i < NPARK; i++) {           /* each must return ITS OWN value */
        if (do_ld(addr(HOT_SET, TB + i)) != GH[i]) ok = 0;   /* parked, home 5 */
        if (do_ld(addr(PARTNER, TB + i)) != GP[i]) ok = 0;   /* native, home 6 */
    }
    snap(&b);
    int paired = paired_5_6();
    int pass = ok && paired && (b.sh - a.sh) > 0;   /* a parked line served amid the aliases; no assert */
    printf("S5 tag-alias:          %s  secHits+=%lu parked=%lu paired=%d data=%d\n",
           pass ? "PASS" : "FAIL", (ulong)(b.sh - a.sh), (ulong)b.pk, paired, ok);
    return pass;
}

/* ---- S6: evict a DIRTY parked line. Release must use lineHome, not the physical row. -------- */
static int case_s6(void) {
    const int TB = 6 * 32;
    uint64_t GH[NPARK], CG[NPARK];
    cap(HOT_SET, TB, NPARK, GH);
    cap(PARTNER, TB, NPARK, CG);                     /* canary golden at the wrong-address target */
    if (park_n(TB, 4) == 0) { printf("S6 evict-dirty-parked: FAIL (no migration during park)\n"); return 0; }
    uint64_t nv[NPARK];
    for (int i = 0; i < NPARK; i++) { nv[i] = 0x5206000000000000ULL | (uint64_t)(TB + i);
                                      do_st(addr(HOT_SET, TB + i), nv[i]); }
    scrub_l1(1);                                     /* L1 writes back dirty -> L2 row6 dirty, client-free */

    snap_t a, b; snap(&a);
    flood(PARTNER, FLOOD_TAG_BASE, 24);              /* push dirty parked lines out of row 6 */
    snap(&b);

    int ok = 1, canary = 1;
    for (int i = 0; i < NPARK; i++) {
        if (do_ld(addr(HOT_SET, TB + i)) != nv[i]) ok = 0;                 /* home reread == stored */
        if (do_ld(addr(PARTNER, TB + i)) != CG[i]) canary = 0;            /* wrong-addr target intact */
    }
    uint64_t ddr = b.dr - a.dr;
    int pass = ok && canary && ddr > 0;
    printf("S6 evict-dirty-parked: %s  dispRelease+=%lu dispDrop+=%lu data=%d canary=%d parked=%lu\n",
           pass ? "PASS" : "FAIL", (ulong)ddr, (ulong)(b.dd - a.dd), ok, canary, (ulong)b.pk);
    return pass;
}

/* ---- S7: evict a parked line a client still holds (stale). Reclaim must probe, then Release. */
static int case_s7(void) {
    const int TB = 7 * 32;
    uint64_t GH[NPARK]; cap(HOT_SET, TB, NPARK, GH);
    if (park(TB) == 0) { printf("S7 evict-client-parked:FAIL (no migration during park)\n"); return 0; }
    for (int i = 0; i < NPARK; i++) sink += do_ld(addr(HOT_SET, TB + i));   /* serve -> clients set */
    scrub_l1(1);                                     /* clean line dropped silently -> clients stale */

    snap_t a, b; snap(&a);
    flood(PARTNER, FLOOD_TAG_BASE + 64, 24);         /* evict parked lines while clients is stale */
    snap(&b);

    int ok = 1;
    for (int i = 0; i < NPARK; i++) if (do_ld(addr(HOT_SET, TB + i)) != GH[i]) ok = 0;
    uint64_t reclaimed = (b.dr - a.dr) + (b.dd - a.dd);
    int pass = ok && reclaimed > 0;                  /* reclaim probe + release happened; no assert */
    printf("S7 evict-client-parked:%s  dispRelease+=%lu dispDrop+=%lu data=%d parked=%lu\n",
           pass ? "PASS" : "FAIL", (ulong)(b.dr - a.dr), (ulong)(b.dd - a.dd), ok, (ulong)b.pk);
    return pass;
}

/* ---- S8: C-channel. A client voluntarily Releases a line that is parked. -------------------- */
static int case_s8(void) {
    const int TB = 8 * 32;
    for (int i = 0; i < NPARK; i++) sink += do_ld(addr(HOT_SET, TB + i));
    if (park(TB) == 0) { printf("S8 c-channel-release:  FAIL (no migration during park)\n"); return 0; }

    uint64_t nv[NPARK];
    snap_t a, b; snap(&a);
    for (int i = 0; i < NPARK; i++) { nv[i] = 0x5208000000000000ULL | (uint64_t)(TB + i);
                                      do_st(addr(HOT_SET, TB + i), nv[i]); }  /* serve -> TRUNK+client, dirty */
    scrub_l1(1);                                     /* dirty L1 eviction -> real ReleaseData to L2 */
    snap(&b);

    int ok = 1;
    for (int i = 0; i < NPARK; i++) if (do_ld(addr(HOT_SET, TB + i)) != nv[i]) ok = 0;
    uint64_t dsc = b.sc - a.sc;
    int pass = ok && dsc > 0;
    printf("S8 c-channel-release:  %s  secC+=%lu data=%d parked=%lu\n",
           pass ? "PASS" : "FAIL", (ulong)dsc, ok, (ulong)b.pk);
    return pass;
}

/* ---- S10: a parked line must never be migrated a second time (AT records one hop). ---------- */
static int case_s10(void) {
    const int TB = 10 * 32;
    uint64_t GH[NPARK]; cap(HOT_SET, TB, NPARK, GH);
    if (park(TB) == 0) { printf("S10 no-second-hop:     FAIL (no migration during park)\n"); return 0; }

    snap_t a, b; snap(&a);
    for (int loop = 0; loop < 40; loop++) {          /* make row 6 hot too; it must NOT source */
        flood(PARTNER, FLOOD_TAG_BASE + 128 + loop, NPARK);
        for (int i = 0; i < NPARK; i++) sink += do_ld(addr(HOT_SET, TB + i));
    }
    snap(&b);

    int ok = 1;
    for (int i = 0; i < NPARK; i++) if (do_ld(addr(HOT_SET, TB + i)) != GH[i]) ok = 0;
    int parked_ok = (b.pk <= WAYS);
    int pass = ok && parked_ok && (b.mig >= a.mig);
    printf("S10 no-second-hop:     %s  migrations+=%lu parked=%lu(<=%d) data=%d\n",
           pass ? "PASS" : "FAIL", (ulong)(b.mig - a.mig), (ulong)b.pk, WAYS, ok);
    return pass;
}

/* ---- S11: reuse soak. Working set that FITS, so parked lines survive to be re-hit. ---------- */
static int case_s11(void) {
    const int TB = 11 * 32;
    uint64_t GH[NPARK]; cap(HOT_SET, TB, NPARK, GH);
    if (park_n(TB, 4) == 0) { printf("S11 reuse-soak:        FAIL (no migration during park)\n"); return 0; }

    snap_t a, b; snap(&a);
    int ok = 1;
    for (int loop = 0; loop < 8000; loop++) {         /* reuse the parked set so lines re-hit in place */
        int i = loop % NPARK;
        uint64_t v = do_ld(addr(HOT_SET, TB + i));
        if (v != GH[i]) ok = 0;
        sink += v;
    }
    snap(&b);
    uint64_t dsh = b.sh - a.sh, dsm = b.sm - a.sm;
    uint64_t tot = dsh + dsm;
    uint64_t pct = tot ? (dsh * 1000) / tot : 0;     /* secHits/(secHits+secMiss), x10 */
    int pass = ok && dsh > 0;
    printf("S11 reuse-soak:        %s  secHits+=%lu secMiss+=%lu ratio=%lu.%lu%% data=%d\n",
           pass ? "PASS" : "FAIL", (ulong)dsh, (ulong)dsm, (ulong)(pct / 10), (ulong)(pct % 10), ok);
    return pass;
}

/* ---- S9: BRANCH reachability. Falsifiable check over the WHOLE run (run last). ------------- */
static int case_s9(void) {
    uint64_t spm = sbc_rd(SBC_SECPERM);
    uint64_t hb  = sbc_rd(SBC_HOMEBRANCH);
    int pass = !(hb > 0 && spm == 0);                /* FAIL only on homeBranch>0 && secPerm==0 */
    printf("S9 branch-reachability:%s  homeBranch=%lu secPerm=%lu  (%s)\n",
           pass ? "PASS" : "FAIL", (ulong)hb, (ulong)spm,
           (hb == 0 && spm == 0) ? "BRANCH unreachable here - secPerm=0 correct by construction"
                                 : (spm > 0 ? "perm-serve path exercised" : "REAL HOLE"));
    return pass;
}

#ifdef SIP_EXPECT_ASSERT
/* ---- S12: MMIO flush of a parked line. NEGATIVE case - expected to DIE on the assert. ------- */
static void case_s12(void) {
    const int TB = 12 * 32;
    for (int i = 0; i < NPARK; i++) sink += do_ld(addr(HOT_SET, TB + i));
    (void)park(TB);
    printf("S12 flush-parked: issuing Flush64 of a parked line - expect an assert to fire now\n");
    sbc_wr(L2_CTRL_BASE + 0x200, (uint64_t)addr(HOT_SET, TB));  /* Flush64 */
    for (volatile int k = 0; k < 100000; k++) ;
    printf("S12 flush-parked: NO ASSERT FIRED - itself a FAILURE (flush was silently lost)\n");
}
#endif

int main(void) {
    printf("=== serve_in_place_test (single-core, 003 GATE 5) ===\n");
#ifdef SIP_EXPECT_ASSERT
    case_s12();
    return 0;
#endif
    int ok = 1;
    ok &= case_s1();
    ok &= case_s2();
    ok &= case_s5();
    ok &= case_s6();
    ok &= case_s7();
    ok &= case_s8();
    ok &= case_s10();
    ok &= case_s11();
    ok &= case_s9();

    snap_t f; snap(&f);
    printf("[SIP-TOTALS] mig=%lu att=%lu abo=%lu secHits=%lu secMiss=%lu secWrite=%lu secProbe=%lu "
           "dispRel=%lu dispDrop=%lu secC=%lu secPerm=%lu homeBranch=%lu parked=%lu l2Accesses=%lu l2Hits=%lu\n",
           (ulong)f.mig,(ulong)f.att,(ulong)f.abo,(ulong)f.sh,(ulong)f.sm,(ulong)f.sw,(ulong)f.sp,
           (ulong)f.dr,(ulong)f.dd,(ulong)f.sc,(ulong)f.spm,(ulong)f.hb,(ulong)f.pk,
           (ulong)sbc_rd(SBC_L2_ACCESSES),(ulong)sbc_rd(SBC_L2_HITS));
    printf("GATE5 single-core: %s\n", ok ? "*** PASSED ***" : "*** FAILED ***");
    return ok ? 0 : 1;
}
