/* migration_stress_test.c — corner-case stress test for SBC migrate-on-eviction.
 *
 * Built on set_migration.c. Pure application code (loads/stores only, NO MMIO):
 * each case shapes the L2 so a different migration corner is exercised, then verifies
 * DATA CORRECTNESS. Whether/how a migration committed is read from the [SBC] log.
 *
 * Config (VerilatorRocket8KL116KL2Config): L2 = 8 sets x 8 ways x 64B, 1 bank,
 * sbcAutoMigrate=ON; L1 D$ = 2 sets x 2 ways x 64B (so L2 victims go client-free fast).
 *
 * One function per corner case (run sequentially, each on its own cold set so they
 * don't interfere). HOT_SET is shared but every case uses fresh tags.
 */

#include <stdio.h>
#include <stdint.h>
#include "sbc_mmio.h"   /* shared SBC MMIO register map */

/* real bus transactions, never optimized away */
static inline uint64_t do_ld(uintptr_t a) {
    uint64_t v; asm volatile("ld %0, 0(%1)" : "=r"(v) : "r"(a) : "memory"); return v;
}
static inline void do_st(uintptr_t a, uint64_t v) {
    asm volatile("sd %0, 0(%1)" :: "r"(v), "r"(a) : "memory");
}

/* L2: 8 sets x 8 ways x 64B, 1 bank. set index = addr bits [8:6]. */
#define L2_SETS   8
#define L2_WAYS   8
#define LINE      64
#define HOT_SET   5
#define NHOT      16          /* > ways -> hot set keeps missing -> stays hot (auto-migrate fires) */
#define BURST     1500        /* enough to commit several migrations, still a quick sim */
#define NSAT      32          /* saturation working set per side-set (>> ways -> always missing) */
#define SAT_BURST 20000       /* long run -> hundreds of copy windows under sustained bank load */

#define DRAM_BASE 0x81000000UL
/* address for L2 set `s`, distinct tag index `t` (tag sits above the 3 set bits) */
static inline uintptr_t set_addr(int s, int t) {
    return DRAM_BASE + (uintptr_t)t * (L2_SETS * LINE) + (uintptr_t)s * LINE;
}

static uint64_t sink;

/* SBC MMIO counters — offsets live in the shared header so the two test binaries cannot drift. */
static void sbc_summary(void) {
    printf("[SBC-COUNTERS] migrations=%lu attempted=%lu aborted=%lu secHits=%lu secMiss=%lu secPerm=%lu "
           "l2Accesses=%lu l2Hits=%lu\n",
           (unsigned long)sbc_rd(SBC_MIGRATIONS), (unsigned long)sbc_rd(SBC_ATTEMPTED),
           (unsigned long)sbc_rd(SBC_ABORTED),    (unsigned long)sbc_rd(SBC_SECHITS),
           (unsigned long)sbc_rd(SBC_SECMISS),    (unsigned long)sbc_rd(SBC_SECPERM),
           (unsigned long)sbc_rd(SBC_L2_ACCESSES),(unsigned long)sbc_rd(SBC_L2_HITS));
}

/* Hammer the hot set with loads, occasionally touching the cold set to keep it cold+resident.
 * `cold_set` < 0 means "don't touch any cold set". */
static void hammer(int cold_set, int ncold) {
    for (int it = 0; it < BURST; it++) {
        sink += do_ld(set_addr(HOT_SET, it % NHOT));
        if (cold_set >= 0 && (it & 0xF) == 0)
            sink += do_ld(set_addr(cold_set, it % ncold));
    }
}

/* Case 1 — FREE destination (2a path).
 * Cold set is left empty (INVALID ways), so a migration lands on a free way.
 * Expect: EVICT to invalid dWay, no abort. Verify hot lines survive. */
static int case_free_dst(int cold_set) {
    uint64_t golden[NHOT];
    for (int t = 0; t < NHOT; t++) golden[t] = do_ld(set_addr(HOT_SET, t));

    hammer(-1, 0);                       /* keep cold set empty -> free destination way */

    int ok = 1;
    for (int t = 0; t < NHOT; t++)
        if (do_ld(set_addr(HOT_SET, t)) != golden[t]) ok = 0;
    printf("case_free_dst (2a, empty cold set %d): %s\n", cold_set, ok ? "PASS" : "FAIL");
    return ok;
}

/* Case 2 — FULL CLEAN destination (2b evictable path).
 * Fill cold set with `ways` clean lines: no INVALID way, but every way is clean+client-free,
 * so a migration can only land by silently overwriting an evictable way.
 * Expect: EVICT-DST on a clean dWay, no abort. Verify hot AND cold lines survive. */
static int case_full_clean_dst(int cold_set) {
    uint64_t hot_g[NHOT], cold_g[L2_WAYS];
    for (int t = 0; t < L2_WAYS; t++) cold_g[t] = do_ld(set_addr(cold_set, t));
    for (int t = 0; t < NHOT; t++)    hot_g[t]  = do_ld(set_addr(HOT_SET, t));

    hammer(cold_set, L2_WAYS);

    int ok = 1;
    for (int t = 0; t < NHOT; t++)
        if (do_ld(set_addr(HOT_SET, t)) != hot_g[t]) ok = 0;
    for (int t = 0; t < L2_WAYS; t++)
        if (do_ld(set_addr(cold_set, t)) != cold_g[t]) ok = 0;
    printf("case_full_clean_dst (2b, full clean cold set %d): %s\n", cold_set, ok ? "PASS" : "FAIL");
    return ok;
}

/* Case 3 — DIRTY victims in the hot set (migration must be SKIPPED).
 * Stores make the hot lines dirty, so they are NOT migration-eligible. Migration must
 * skip them and fall back to a normal eviction/writeback — no corruption.
 * Expect: eligible=0 on the hot victims, few/no migrations. Verify stored values survive. */
static int case_dirty_victims(int cold_set) {
    uint64_t golden[NHOT];
    for (int t = 0; t < NHOT; t++) {
        golden[t] = 0xD117 ^ ((uint64_t)t << 8);
        do_st(set_addr(HOT_SET, t), golden[t]);   /* dirty the hot lines */
    }

    /* keep the set hot with read-modify traffic so victims stay dirty as they evict */
    for (int it = 0; it < BURST; it++) {
        uintptr_t a = set_addr(HOT_SET, it % NHOT);
        do_st(a, do_ld(a) + 0);                   /* touch + re-dirty */
        if (cold_set >= 0 && (it & 0xF) == 0) sink += do_ld(set_addr(cold_set, it % L2_WAYS));
    }

    int ok = 1;
    for (int t = 0; t < NHOT; t++)
        if (do_ld(set_addr(HOT_SET, t)) != golden[t]) ok = 0;
    printf("case_dirty_victims (skip-migrate, dirty hot): %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

/* Case 4 — FULL DIRTY destination (ABORT-DST path).
 * Fill the cold set with dirty lines: no INVALID way and no evictable way, so the 2nd
 * dir-read finds no eligible destination -> migration aborts, normal eviction proceeds.
 * Expect: ABORT-DST fires, no corruption. Verify hot AND dirty-cold lines survive. */
static int case_full_dirty_dst(int cold_set) {
    uint64_t hot_g[NHOT], cold_g[L2_WAYS];
    for (int t = 0; t < L2_WAYS; t++) {
        cold_g[t] = 0xC01D ^ ((uint64_t)t << 4);
        do_st(set_addr(cold_set, t), cold_g[t]);  /* dirty -> not evictable */
    }
    for (int t = 0; t < NHOT; t++) hot_g[t] = do_ld(set_addr(HOT_SET, t));

    hammer(cold_set, L2_WAYS);

    int ok = 1;
    for (int t = 0; t < NHOT; t++)
        if (do_ld(set_addr(HOT_SET, t)) != hot_g[t]) ok = 0;
    for (int t = 0; t < L2_WAYS; t++)
        if (do_ld(set_addr(cold_set, t)) != cold_g[t]) ok = 0;
    printf("case_full_dirty_dst (abort-dst, full dirty cold set %d): %s\n", cold_set, ok ? "PASS" : "FAIL");
    return ok;
}

/* Case 5 — RE-ACCESS a migrated (displaced) line.
 * After migration, keep reading the SAME hot tags. A migrated line is `displaced` in its
 * new set and must still return correct data via the coherence path (no false hit/miss).
 * Expect: secondary hits/coherence-correct reads after MIG-COMMIT. Verify final values. */
static int case_reaccess_migrated(int cold_set) {
    uint64_t golden[NHOT];
    for (int t = 0; t < NHOT; t++) golden[t] = do_ld(set_addr(HOT_SET, t));

    hammer(cold_set, L2_WAYS);                    /* commit some migrations */
    /* now re-touch the same lines repeatedly: forces lookups against displaced entries */
    for (int rep = 0; rep < 4; rep++)
        for (int t = 0; t < NHOT; t++)
            if (do_ld(set_addr(HOT_SET, t)) != golden[t]) golden[t] = ~golden[t]; /* poison on mismatch */

    int ok = 1;
    for (int t = 0; t < NHOT; t++)
        if (do_ld(set_addr(HOT_SET, t)) != golden[t]) ok = 0;
    printf("case_reaccess_migrated (displaced re-read): %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

/* Case 6 — WRITE soon after a line migrates (copy_safe / copy_wsafe hazard stress).
 * Interleave stores into the hot set with the load hammer so SourceD read/write traffic
 * overlaps SCU copy reads/writes. The RaW/WaR interlocks must keep data coherent.
 * Expect: no copy-verify mismatch assert. Verify last-written values survive. */
static int case_hazard_rw(int cold_set) {
    uint64_t golden[NHOT];
    for (int it = 0; it < BURST; it++) {
        int t = it % NHOT;
        uint64_t v = 0xA5A5 ^ ((uint64_t)it << 1);
        do_st(set_addr(HOT_SET, t), v);           /* write into the hot set every iter */
        golden[t] = v;                            /* remember the last write per tag */
        sink += do_ld(set_addr(HOT_SET, (t + 1) % NHOT));
        if ((it & 0xF) == 0) sink += do_ld(set_addr(cold_set, it % L2_WAYS));
    }

    int ok = 1;
    for (int t = 0; t < NHOT; t++)
        if (do_ld(set_addr(HOT_SET, t)) != golden[t]) ok = 0;
    printf("case_hazard_rw (RaW/WaR interlock stress): %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

/* Case 7 — BANKEDSTORE SATURATION (the real copy-port stressor).
 * Goal: keep the migration source set (HOT_SET) missing hard so copies fire continuously,
 * while flooding the OTHER sets with demand misses + hot-set stores so high-priority
 * SinkC/SourceD/SinkD traffic occupies the sub-banks on the exact cycles the SCU copy is
 * reading srcSet (s_read) and writing dstSet (s_write).
 *
 * Sub-bank index = low bits of Cat(way,set,beat) -> BEAT-DOMINATED. So a demand block transfer
 * to ANY set collides on sub-banks with the copy's beats, even though the dstSet fence blocks
 * new requests to dstSet itself. That is what pressures the FENCED s_write: not new dstSet
 * traffic, but other sets' demand hitting the same physical sub-banks. cold_set is left lightly
 * touched so the DSS keeps choosing it as the (fenced) migration destination.
 *
 * Read the new [SBC] [SetCopyUnit] STALL lines: ARB-STALL growing unbounded => H1 starvation;
 * bounded-then-reset => bounded delay. */
static int case_bankstore_saturation(int cold_set) {
    /* the side-sets we flood with continuous miss streams (NOT the hot src, NOT the cold dst) */
    static const int sat_sets[] = {0, 1, 2, 4, 6};
    const int NSIDE = (int)(sizeof(sat_sets) / sizeof(sat_sets[0]));

    /* goldens: hot set tracked by last store; cold + side sets by their clean DRAM values */
    uint64_t hot_g[NHOT];
    uint64_t cold_g[L2_WAYS];
    for (int t = 0; t < L2_WAYS; t++) cold_g[t] = do_ld(set_addr(cold_set, t));
    for (int t = 0; t < NHOT; t++)    hot_g[t]  = 0;   /* set on first store below */

    for (int it = 0; it < SAT_BURST; it++) {
        /* (1) hammer the hot/source set with a STORE -> dirty traffic (SinkC/SourceC, top priority)
         *     overlapping the copy, plus keeps the set hot so migrations keep firing. */
        int ht = it % NHOT;
        uint64_t v = 0x5A5A ^ ((uint64_t)it << 1);
        do_st(set_addr(HOT_SET, ht), v);
        hot_g[ht] = v;

        /* (2) flood every side-set with a fresh missing line this iteration -> sustained demand
         *     SourceD/SinkD beats churning the sub-banks while the copy reads/writes. */
        for (int s = 0; s < NSIDE; s++)
            sink += do_ld(set_addr(sat_sets[s], it % NSAT));

        /* (3) keep cold_set cold but resident: occasional light hit so the DSS picks it as dst. */
        if ((it & 0x3F) == 0) sink += do_ld(set_addr(cold_set, it % L2_WAYS));
    }

    int ok = 1;
    for (int t = 0; t < NHOT; t++)
        if (do_ld(set_addr(HOT_SET, t)) != hot_g[t]) ok = 0;
    for (int t = 0; t < L2_WAYS; t++)
        if (do_ld(set_addr(cold_set, t)) != cold_g[t]) ok = 0;
    printf("case_bankstore_saturation (max bank load, %d iters): %s\n", SAT_BURST, ok ? "PASS" : "FAIL");
    return ok;
}

int main(void) {
    printf("==== SBC migration stress test (HOT_SET=%d, ways=%d) ====\n", HOT_SET, L2_WAYS);

    int ok = 1;
    ok &= case_free_dst(0);         /* 2a: free destination way             */
    ok &= case_full_clean_dst(1);   /* 2b: full clean destination           */
    ok &= case_dirty_victims(2);    /* skip-migrate: dirty hot victims      */
    ok &= case_full_dirty_dst(3);   /* abort-dst: full dirty destination    */
    ok &= case_reaccess_migrated(4);/* re-read displaced lines              */
    ok &= case_hazard_rw(6);        /* RaW/WaR interlock stress             */
    ok &= case_bankstore_saturation(7); /* SLOW: max bank load over copy window (run last) */

    sbc_summary();
    printf(ok ? "PASS: all migration corner cases data-correct (see [SBC] log)\n"
              : "FAIL: data corrupted in at least one case\n");
    return ok ? 0 : 1;
}
