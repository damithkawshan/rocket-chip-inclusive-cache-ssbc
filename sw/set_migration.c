/* set_migration.c — directed workload to exercise SBC migration with eligible (clean) victims.
 *
 * Pure application code: ld only, NO MMIO control-register access (same style as matmult_float).
 * Migration commit/abort counts are observed from the [SBC] log printfs, not from software.
 * This test only proves DATA CORRECTNESS (migration must not corrupt the line); whether a
 * migration committed is read from the log.
 *
 * Config (VerilatorRocket8KL116KL2Config): L2 = 8 sets x 8 ways x 64B (1 bank, sbcAutoMigrate=ON);
 * L1 D$ = 2 sets x 2 ways x 64B.
 *
 * Idea: hammer ONE set with LOADS only (clean lines) across NTAGS > ways. The set stays hot
 * (auto-migrate fires) and the tiny 2-way L1 keeps cycling the lines out of L1, so the L2 copies
 * are clean migration candidates. Other sets stay cold+empty -> valid migration destinations.
 * Verify: re-read the lines; values must be unchanged (migration must preserve correctness).
 */

#include <stdio.h>
#include <stdint.h>

/* direct 64-bit load (real bus transaction, not optimized away) */
static inline uint64_t do_ld(uintptr_t a) {
    uint64_t v; asm volatile("ld %0, 0(%1)" : "=r"(v) : "r"(a) : "memory"); return v;
}

/* L2: 8 sets x 4 ways x 64B, 1 bank. set index = addr bits [8:6]. */
#define L2_SETS   8
#define LINE      64
#define HOT_SET   5
#define COLD_SET  0
#define NHOT      16          /* > 16 ways -> the hot set keeps missing -> stays hot */
#define NCOLD     8          /* == ways -> fill COLD_SET with clean lines (no INVALID way) so a
                                migration into it can only land via the 2b evictable path */
#define BURST     1500       /* enough to commit several migrations, still a quick sim */

#define DRAM_BASE 0x81000000UL
/* address mapping to L2 set `s`, distinct tag index t (tag sits above the 3 set bits) */
static inline uintptr_t set_addr(int s, int t) {
    return DRAM_BASE + (uintptr_t)t * (L2_SETS * LINE) + (uintptr_t)s * LINE;
}
static inline uintptr_t hot_addr(int t)  { return set_addr(HOT_SET,  t); }
static inline uintptr_t cold_addr(int t) { return set_addr(COLD_SET, t); }

static uint64_t sink;

int main(void) {
    printf("==== SBC 2b migration workload (HOT_SET=%d COLD_SET=%d) ====\n", HOT_SET, COLD_SET);

    /* Fill COLD_SET with NCOLD (== ways) clean lines so it has NO invalid way: a migration into it
     * can then only succeed via the 2b evictable path (silently overwrite a clean, client-free way).
     * Loads keep them clean; the 2-way L1 cycles them out -> client-free in L2. */
    uint64_t cold_golden[NCOLD];
    for (int t = 0; t < NCOLD; t++) cold_golden[t] = do_ld(cold_addr(t));

    /* golden values for the hot region (loads keep the lines clean) */
    uint64_t golden[NHOT];
    for (int t = 0; t < NHOT; t++) golden[t] = do_ld(hot_addr(t));

    /* Hammer the hot set so it stays hot and keeps evicting clean victims. Every 16th iteration give
     * COLD_SET a hit so its saturation stays low (the DSS keeps choosing it as the cold destination)
     * while its lines remain clean + resident -> valid 2b evictable destinations. */
    for (int it = 0; it < BURST; it++) {
        sink += do_ld(hot_addr(it % NHOT));
        if ((it & 0xF) == 0) sink += do_ld(cold_addr(it % NCOLD));
    }

    /* Verify both regions. Migrated/displaced hot lines and any silently-evicted cold lines must
     * still read their original values (migration and clean-eviction are coherence-preserving). */
    int ok = 1;
    for (int t = 0; t < NHOT; t++) {
        uint64_t v = do_ld(hot_addr(t));
        if (v != golden[t]) {
            printf("HOT MISMATCH tag %d: got 0x%lx want 0x%lx\n", t, v, golden[t]);
            ok = 0;
        }
    }
    for (int t = 0; t < NCOLD; t++) {
        uint64_t v = do_ld(cold_addr(t));
        if (v != cold_golden[t]) {
            printf("COLD MISMATCH tag %d: got 0x%lx want 0x%lx\n", t, v, cold_golden[t]);
            ok = 0;
        }
    }

    printf(ok ? "PASS: data correct (see [SBC] log for migr/abort counts)\n"
              : "FAIL: data corrupted\n");
    return ok ? 0 : 1;
}
