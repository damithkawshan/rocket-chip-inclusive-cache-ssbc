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
    /* 006 gate G5: memReads must equal the [SBC] OUTER-A AcquireBlock tally in the same run. */
    printf("[SBC-MEM] memReads=%lu memWrites=%lu memAcqPerm=%lu memRelClean=%lu cycles=%lu\n",
           (unsigned long)sbc_rd(SBC_L2_MEMREADS),   (unsigned long)sbc_rd(SBC_L2_MEMWRITES),
           (unsigned long)sbc_rd(SBC_L2_MEMACQPERM), (unsigned long)sbc_rd(SBC_L2_MEMRELCLEAN),
           (unsigned long)sbc_rd(SBC_L2_CYCLES));
    /* 005 commit 1: outcome counters that follow cache-terminology.md. */
    printf("[SBC-OUTCOMES] accessA=%lu primaryHit=%lu secondaryHit=%lu probedHit=%lu dataMiss=%lu "
           "upgradeMiss=%lu secondSearch=%lu secondaryMiss=%lu\n",
           (unsigned long)sbc_rd(SBC_L2_ACCESSA),      (unsigned long)sbc_rd(SBC_L2_PRIMARYHIT),
           (unsigned long)sbc_rd(SBC_L2_SECONDARYHIT), (unsigned long)sbc_rd(SBC_L2_PROBEDHIT),
           (unsigned long)sbc_rd(SBC_L2_DATAMISS),     (unsigned long)sbc_rd(SBC_L2_UPGRADEMISS),
           (unsigned long)sbc_rd(SBC_L2_SECONDSEARCH), (unsigned long)sbc_rd(SBC_L2_SECONDARYMISS));
    /* 007 commit 0: the parked level and the two erase counters. parked is a level, so it is read
     * outside L2_StatsHold on purpose. At rest parked == migrations - dispRelease - dispDrop. */
    printf("[SBC-PARK] parked=%lu dispRelease=%lu dispDrop=%lu\n",
           (unsigned long)sbc_rd(SBC_PARKED), (unsigned long)sbc_rd(SBC_DISPRELEASE),
           (unsigned long)sbc_rd(SBC_DISPDROP));
#ifdef L2_POLICY
    /* 008 C2 (V6): the three abort reasons must add up to the ABORT-DST printfs. Only in -DL2_POLICY
     * builds, so the flag-free binary stays byte-identical for V1. */
    printf("[SBC-DSTABORT] dirty=%lu held=%lu both=%lu\n",
           (unsigned long)sbc_rd(SBC_DSTABORT_DIRTY), (unsigned long)sbc_rd(SBC_DSTABORT_HELD),
           (unsigned long)sbc_rd(SBC_DSTABORT_BOTH));
#endif
}

/* C5: read the four outcome counters and accessA under L2_StatsHold, so they are one instant, and
 * report "in progress" = accessA - (primaryHit+secondaryHit+dataMiss+upgradeMiss). TASK 005 T5: on
 * this config (mshrs=7, secondary=33) that is bounded 0..38 fresh from reset, -38..38 after --zero. */
static void report_inprogress(const char *case_name) {
    sbc_wr(SBC_L2_STATSHOLD, 1);
    uint64_t acc = sbc_rd(SBC_L2_ACCESSA);
    uint64_t ph  = sbc_rd(SBC_L2_PRIMARYHIT);
    uint64_t sh  = sbc_rd(SBC_L2_SECONDARYHIT);
    uint64_t dm  = sbc_rd(SBC_L2_DATAMISS);
    uint64_t um  = sbc_rd(SBC_L2_UPGRADEMISS);
    sbc_wr(SBC_L2_STATSHOLD, 0);
    uint64_t outcomes = ph + sh + dm + um;
    long long inprog = (long long)acc - (long long)outcomes;
    printf("[SBC-INPROGRESS] case=%s accessA=%lu outcomes=%lu inprogress=%lld\n",
           case_name, (unsigned long)acc, (unsigned long)outcomes, inprog);
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

/* Case 8 — TEARDOWN (task 007 C3). A pairing must break when its source's last guest leaves.
 * Form a pairing, write every source line (the parked ones are served in place and turn into DIRTY
 * guests), then flood ONLY the partner with fresh misses until the source<->partner pairing is gone
 * from both AT entries. Checks the pairing itself, not "unpaired": a flooded partner turns hot and
 * may legitimately start migrating elsewhere the moment it is free. Every written line must read back. */
#define TD_TAG0   4096        /* fresh tags - no earlier case touched these addresses */
#define TD_NSRC   16          /* > ways, so the source keeps missing and migrating */
#define TD_BUILD  4000        /* bound on source loads to form a pairing */
#define TD_DRAIN  4000        /* bound on partner misses to empty it */

static void at_entry(int s, int *valid, int *sd, int *assoc) {
    sbc_wr(SBC_SETSEL, (uint64_t)s);
    uint64_t as = sbc_rd(SBC_ATASSOC);
    *valid = (int)((sbc_rd(SBC_STATUS) >> 2) & 1);
    *sd    = SBC_ATASSOC_SD(as);
    *assoc = (int)SBC_ATASSOC_SET(as);
}

static int case_teardown(void) {
    if (!(sbc_rd(SBC_STATUS) & 1)) { printf("case_teardown: SKIP (SBC not built)\n"); return 1; }
#ifdef SBC_MIGRATE_OFF
    printf("case_teardown: SKIP (migration off)\n");
    return 1;
#endif
    int v, sd, as, src = -1, dst = -1;
    /* 1. form a pairing on the first set that is not already a destination (destinations never source) */
    for (int s = 0; s < L2_SETS && src < 0; s++) {
        at_entry(s, &v, &sd, &as);
        if (v && sd) continue;
        for (int it = 0; it < TD_BUILD; it++) {
            sink += do_ld(set_addr(s, TD_TAG0 + it % TD_NSRC));
            if ((it & 0x3F) == 0x3F) {
                at_entry(s, &v, &sd, &as);
                if (v && !sd) { src = s; dst = as; break; }
            }
        }
    }
    if (src < 0) { printf("case_teardown: FAIL (no pairing formed)\n"); return 0; }

    /* 2. write every source line: parked ones are served in place and become dirty guests */
    uint64_t g[TD_NSRC];
    for (int t = 0; t < TD_NSRC; t++) {
        g[t] = 0x7EA2D00DULL ^ ((uint64_t)t << 20);
        do_st(set_addr(src, TD_TAG0 + t), g[t]);
    }

    /* 3. leave the source alone; flood only the partner until the src<->dst pairing is gone */
    int gone = 0, n = 0, sv = 0, ssd = 0, sas = 0, dv = 0, dsd = 0, das = 0;
    for (; n < TD_DRAIN && !gone; n++) {
        sink += do_ld(set_addr(dst, TD_TAG0 + TD_NSRC + n));
        if ((n & 0xF) == 0xF) {
            at_entry(src, &sv, &ssd, &sas);
            at_entry(dst, &dv, &dsd, &das);
            gone = !(sv && !ssd && sas == dst) && !(dv && dsd && das == src);
        }
    }

    /* 4. every written line must read back - home lines and dirty guests written back alike */
    int data_ok = 1;
    for (int t = 0; t < TD_NSRC; t++)
        if (do_ld(set_addr(src, TD_TAG0 + t)) != g[t]) data_ok = 0;

    printf("case_teardown (007 C3): src=%d dst=%d pairing_gone=%s after %d partner misses "
           "src(v=%d sd=%d assoc=%d) dst(v=%d sd=%d assoc=%d) data=%s: %s\n",
           src, dst, gone ? "yes" : "NO", n, sv, ssd, sas, dv, dsd, das,
           data_ok ? "ok" : "BAD", (gone && data_ok) ? "PASS" : "FAIL");
    return gone && data_ok;
}

#ifdef SBC_DSTMODE_SET
static int case_held_lru_dirty_alt(void);   /* 018, at the end of the file */
#endif

int main(void) {
#ifdef L2_POLICY
    sbc_set_policy();   /* 008 */
#endif
    printf("==== SBC migration stress test (HOT_SET=%d, ways=%d) ====\n", HOT_SET, L2_WAYS);

#ifndef SBC_MIGRATE_OFF
    /* SBC_MigrateEnable defaults OFF in hardware (006) — arm it so sbcAutoMigrate can fire.
     * Build with -DSBC_MIGRATE_OFF to get the G3 control run (switch stays off). */
    sbc_wr(SBC_MIGRATEENABLE, 1);
#endif

    int ok = 1;
    ok &= case_free_dst(0);         /* 2a: free destination way             */
    report_inprogress("case_free_dst");
    ok &= case_full_clean_dst(1);   /* 2b: full clean destination           */
    report_inprogress("case_full_clean_dst");
    ok &= case_dirty_victims(2);    /* skip-migrate: dirty hot victims      */
    report_inprogress("case_dirty_victims");
    ok &= case_full_dirty_dst(3);   /* abort-dst: full dirty destination    */
    report_inprogress("case_full_dirty_dst");
    ok &= case_reaccess_migrated(4);/* re-read displaced lines              */
    report_inprogress("case_reaccess_migrated");
    ok &= case_hazard_rw(6);        /* RaW/WaR interlock stress             */
    report_inprogress("case_hazard_rw");
    ok &= case_bankstore_saturation(7); /* SLOW: max bank load over copy window */
    report_inprogress("case_bankstore_saturation");
    ok &= case_teardown();          /* 007 C3: pairing breaks when the last guest leaves */
    report_inprogress("case_teardown");
#ifdef SBC_DSTMODE_SET
    ok &= case_held_lru_dirty_alt();    /* 018: held least-recent dst way, dirty client-free way beside it */
    report_inprogress("case_held_lru_dirty_alt");
#endif

    sbc_summary();
    printf(ok ? "PASS: all migration corner cases data-correct (see [SBC] log)\n"
              : "FAIL: data corrupted in at least one case\n");
    return ok ? 0 : 1;
}

#ifdef SBC_DSTMODE_SET
/* Case 9 - 018: a migration into a destination D whose least-recent way is HELD by the L1, while another
 * way is DIRTY with no L1 bit and no way is clean with no L1 bit. Expected (read SBC_DstMode):
 *   setting 0:   the migration aborts on the held way (dstAbortHeld +1), and SBC_DstRescue +1
 *   setting 1/2: the dirty way is written back and the migration commits (migrations +1), SBC_DstRescue +1
 * State built in D (8 ways): G = a guest of S, read through S so it is held; N1..N6 = fresh D lines read
 * once (the tiny L1 drops them silently, so their bit stays set: held); N7 = a fresh D line stored, then
 * pushed out of the L1 (ReleaseData: dirty, no bit). PLRU learns only from grants and installs.
 *
 * Why the critical part is assembly, in 8 copies. The L2 has 8 sets and the L1 I$ is 4 lines, so the
 * test's own instruction fetches land in the L2: a code line in D is a clean way with no L1 bit (the I$ is
 * not a TL-C client), and a code line in S is a clean victim that would migrate before C. Each copy is at
 * most 3 lines; copy k sits on L2 sets k..k+2, and the case runs a copy whose lines avoid S and D. The
 * block touches only its own addresses (no stack) and is straight-line apart from short loops.
 *
 * Why G must be a guest: D's guests of S keep the pairing alive (teardown fires when the last one
 * leaves), so the 7 fresh D lines must not push them all out. They are fetched right after a migration
 * installs C as G (MRU), and tree PLRU then replaces the other 7 ways first.
 *   B1  8 store misses in S: S = 8 dirty lines, so no S victim migrates until a clean one comes up
 *   B2  load C (clean) into S
 *   B3  store misses in S until migrations moves: C was S's victim and is now G, MRU in D
 *   B4  N1..N6 loads, N7 store: fresh D misses, which replace every way but G
 *   B5  load C through S: a secondary hit, G is now held and MRU
 *   B6  8 loads in P (same L1 set as D, not S or D): N7 leaves the L1 as a ReleaseData
 *   B7  load C2 (clean) into S, snapshot the counters, then store misses in S until a migration is
 *       attempted: C2 is S's victim, and its destination read sees the state above
 * Sim only: the host polls tohost / fromhost through the L2 every ~250 cycles. Each poll is an L2 hit that
 * re-touches that line's way, so the PLRU of those two sets never settles (attempt 1 of setting 2 found C
 * stuck beside the tohost line, never the victim). S and D are never those sets.
 * Returns a0 = dstAbortHeld << 32 | dstRescue before B7's attempt, a1 = migrations before it; or
 * a0 = -1 (B3: C never migrated) / -2 (B7: no attempt). s0/s1 are saved in t0/t1. */
#define DH_HAM_TAG  16384   /* phase A: fresh S loads that heat S and pair it */
#define DH_STR_TAG  24576   /* the block's S store stream */
#define DH_C_TAG    32000   /* C, the guest G */
#define DH_C2_TAG   32001   /* C2, the migration whose destination read is the test */
#define DH_D_TAG    40960   /* N1..N7 in D */
#define DH_P_TAG    49152   /* the pushes in P */
#define DH_HAMMER   4000
#define DH_PAT_C    0xC0DE0018C0DE0018ULL
#define DH_PAT_C2   0xC2C20018C2C20018ULL

extern volatile uint64_t tohost, fromhost;   /* riscv-tests syscalls.c / test.ld */
typedef struct { uint64_t a, b; } dh_ret_t;
typedef dh_ret_t (*dh_blk_t)(uintptr_t sstream, uintptr_t c, uintptr_t c2, uintptr_t dbase,
                             uintptr_t pbase, uintptr_t mmio);
extern dh_ret_t dst018_blk0(), dst018_blk1(), dst018_blk2(), dst018_blk3(),
                dst018_blk4(), dst018_blk5(), dst018_blk6(), dst018_blk7();
static const dh_blk_t dh_blk[8] = {
    (dh_blk_t)dst018_blk0, (dh_blk_t)dst018_blk1, (dh_blk_t)dst018_blk2, (dh_blk_t)dst018_blk3,
    (dh_blk_t)dst018_blk4, (dh_blk_t)dst018_blk5, (dh_blk_t)dst018_blk6, (dh_blk_t)dst018_blk7 };

__asm__(
"  .pushsection .text\n"
"  .option push\n"
"  .option norelax\n"
"  .macro DST018_BLK name\n"
"  .globl \\name\n"
"  .type \\name, @function\n"
"\\name:\n"
"  mv   t0, s0\n"
"  mv   t1, s1\n"
"  li   t6, 512\n"
"  li   s1, 8\n"                 /* B1 */
"1: sd   a0, 0(a0)\n"
"  ld   s0, 0(a0)\n"
"  add  a0, a0, t6\n"
"  addi s1, s1, -1\n"
"  bnez s1, 1b\n"
"  ld   s0, 0(a1)\n"             /* B2: C */
"  ld   t2, 0x328(a5)\n"         /* migrations */
"  li   s1, 31\n"                /* B3 */
"2: sd   a0, 0(a0)\n"
"  ld   s0, 0(a0)\n"
"  add  a0, a0, t6\n"
"  ld   t3, 0x328(a5)\n"
"  bne  t3, t2, 3f\n"
"  addi s1, s1, -1\n"
"  bnez s1, 2b\n"
"  li   a0, -1\n"
"  j    9f\n"
"3: li   s1, 6\n"                /* B4: N1..N6 loads */
"4: ld   s0, 0(a3)\n"
"  add  a3, a3, t6\n"
"  addi s1, s1, -1\n"
"  bnez s1, 4b\n"
"  sd   a3, 0(a3)\n"             /* N7 store */
"  ld   s0, 0(a1)\n"             /* B5: G through S */
"  li   s1, 8\n"                 /* B6: pushes */
"5: ld   s0, 0(a4)\n"
"  add  a4, a4, t6\n"
"  addi s1, s1, -1\n"
"  bnez s1, 5b\n"
"  ld   s0, 0(a2)\n"             /* B7: C2 */
"  ld   t4, 0x4A0(a5)\n"         /* dstAbortHeld */
"  ld   t5, 0x4B8(a5)\n"         /* dstRescue */
"  ld   a6, 0x328(a5)\n"         /* migrations */
"  ld   t2, 0x348(a5)\n"         /* attempted */
"  li   s1, 31\n"
"6: sd   a0, 0(a0)\n"
"  ld   s0, 0(a0)\n"
"  add  a0, a0, t6\n"
"  ld   t3, 0x348(a5)\n"
"  bne  t3, t2, 7f\n"
"  addi s1, s1, -1\n"
"  bnez s1, 6b\n"
"  li   a0, -2\n"
"  j    9f\n"
"7: li   s1, 2000\n"             /* settle: the destination read lands */
"8: addi s1, s1, -1\n"
"  bnez s1, 8b\n"
"  mv   a0, t4\n"
"  slli a0, a0, 32\n"
"  or   a0, a0, t5\n"
"  mv   a1, a6\n"
"9: mv   s0, t0\n"
"  mv   s1, t1\n"
"  ret\n"
"  .size \\name, . - \\name\n"
"  .endm\n"
"  .balign 512\n"
"  DST018_BLK dst018_blk0\n"
"  .balign 512\n  .skip 64\n"
"  DST018_BLK dst018_blk1\n"
"  .balign 512\n  .skip 128\n"
"  DST018_BLK dst018_blk2\n"
"  .balign 512\n  .skip 192\n"
"  DST018_BLK dst018_blk3\n"
"  .balign 512\n  .skip 256\n"
"  DST018_BLK dst018_blk4\n"
"  .balign 512\n  .skip 320\n"
"  DST018_BLK dst018_blk5\n"
"  .balign 512\n  .skip 384\n"
"  DST018_BLK dst018_blk6\n"
"  .balign 512\n  .skip 448\n"
"  DST018_BLK dst018_blk7\n"
"  .option pop\n"
"  .popsection\n");

static int case_held_lru_dirty_alt(void) {
    if (!(sbc_rd(SBC_STATUS) & 1)) { printf("case_held_lru_dirty_alt: SKIP (SBC not built)\n"); return 1; }
#ifdef SBC_MIGRATE_OFF
    printf("case_held_lru_dirty_alt: SKIP (migration off)\n");
    return 1;
#endif
    /* Random policy: the Directory ignores SBC_DstMode, and the PLRU state below means nothing. */
    if (!(sbc_rd(SBC_L2_REPLACEMENT) & 1)) {
        printf("case_held_lru_dirty_alt: SKIP (random policy, the setting is ignored)\n");
        return 1;
    }
    int mode = (int)(sbc_rd(SBC_DST_MODE) & 3);
    if (mode == 3) mode = 0;

    /* A. a source S and its partner D. C and C2 get known values now; the hammer writes them back, so
     *    B2 and B7 fetch them clean. At least 16 S misses even when S is already a source: S must be hot. */
    int v, sd, as, S = -1, D = -1;
    int th = (int)(((uintptr_t)&tohost >> 6) & (L2_SETS - 1)), fh = (int)(((uintptr_t)&fromhost >> 6) & (L2_SETS - 1));
    uint64_t acc = 0;
    for (int s = 0; s < L2_SETS && S < 0; s++) {
        if (s == th || s == fh) continue;           /* the host polls these lines (see above) */
        at_entry(s, &v, &sd, &as);
        if (v && sd) continue;                      /* a destination never sources */
        do_st(set_addr(s, DH_C_TAG),  DH_PAT_C);
        do_st(set_addr(s, DH_C2_TAG), DH_PAT_C2);
        for (int it = 0; it < DH_HAMMER; it++) {
            acc += do_ld(set_addr(s, DH_HAM_TAG + it));
            if (it >= 16 && (it & 7) == 7) {
                at_entry(s, &v, &sd, &as);
                if (v && !sd) { if (as != th && as != fh) { S = s; D = as; } break; }   /* else: next s */
            }
        }
    }
    sink += acc;
    if (S < 0) { printf("case_held_lru_dirty_alt: FAIL (no pairing formed)\n"); return 0; }

    /* P: the same L1 set as D (address bit 6), neither S nor D. K: a copy whose 3 lines avoid S and D. */
    int P = -1, K = -1;
    for (int p = 0; p < L2_SETS && P < 0; p++)
        if (p != S && p != D && ((p ^ D) & 1) == 0) P = p;
    for (int k = 0; k < 8 && K < 0; k++) {
        int s0 = (int)(((uintptr_t)dh_blk[k] >> 6) & (L2_SETS - 1)), bad = 0;
        for (int j = 0; j < 3; j++) { int cs = (s0 + j) & (L2_SETS - 1); if (cs == S || cs == D) bad = 1; }
        if (!bad) K = k;
    }
    if (P < 0 || K < 0) { printf("case_held_lru_dirty_alt: FAIL (no P or no block copy, S=%d D=%d)\n", S, D); return 0; }

    uintptr_t sstr = set_addr(S, DH_STR_TAG), c = set_addr(S, DH_C_TAG), c2 = set_addr(S, DH_C2_TAG);
    uintptr_t dbase = set_addr(D, DH_D_TAG), n7 = set_addr(D, DH_D_TAG + 6);
    dh_ret_t r = dh_blk[K](sstr, c, c2, dbase, set_addr(P, DH_P_TAG), (uintptr_t)L2_CTRL_BASE);
    uint64_t held1 = sbc_rd(SBC_DSTABORT_HELD), resc1 = sbc_rd(SBC_DST_RESCUE), mig1 = sbc_rd(SBC_MIGRATIONS);

    if ((int64_t)r.a < 0) {
        printf("case_held_lru_dirty_alt (018, mode %d): S=%d D=%d P=%d copy=%d: FAIL (%s)\n", mode, S, D, P, K,
               r.a == (uint64_t)-1 ? "B3: C never migrated" : "B7: no migration attempt");
        return 0;
    }
    uint64_t dHeld = held1 - (r.a >> 32), dResc = resc1 - (r.a & 0xffffffffULL), dMig = mig1 - r.b;

    /* Every line the case wrote or loaded with a known value must read back. */
    int data_ok = do_ld(c) == DH_PAT_C && do_ld(c2) == DH_PAT_C2 && do_ld(n7) == n7;
    for (int t = 0; t < 8; t++)
        if (do_ld(set_addr(S, DH_STR_TAG + t)) != set_addr(S, DH_STR_TAG + t)) data_ok = 0;

    int ok = data_ok && dResc == 1 &&
             (mode == 0 ? (dHeld == 1 && dMig == 0) : (dHeld == 0 && dMig == 1));
    printf("case_held_lru_dirty_alt (018, mode %d): S=%d D=%d P=%d copy=%d (host sets %d/%d) heldAbort+%lu rescue+%lu "
           "migrations+%lu (expect %s) data=%s: %s\n", mode, S, D, P, K, th, fh, (unsigned long)dHeld,
           (unsigned long)dResc, (unsigned long)dMig, mode == 0 ? "abort held, rescue" : "commit, rescue",
           data_ok ? "ok" : "BAD", ok ? "PASS" : "FAIL");
    return ok;
}
#endif /* SBC_DSTMODE_SET */
