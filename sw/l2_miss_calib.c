/* l2_miss_calib.c - Linux user-space workload with a DESIGNED L2 miss rate (default 50%).
 *
 * Purpose: check the L2 hit/miss counters on the FPGA against a number we can predict. It prints the
 * designed counts next to the measured ones.
 *
 * Geometry. The L2 geometry is read from the control block's config word. The L1 D-cache is assumed
 * to be 8 KB = 32 sets x 4 ways with random replacement (the L18K configs; it cannot be read).
 *
 * How the design works:
 *   - A 64 B line at page offset o is in L2 set ((frame << 6) | (o >> 6)) & (sets - 1).
 *     64 sets  (L18K64K16W):  set = o >> 6. Every page reaches all 64 sets.
 *     256 sets (L18K256K16W): set = ((frame & 3) << 6) | (o >> 6). A page reaches only the 64 sets of
 *     its frame's group, so all pages are picked from one group, found via /proc/self/pagemap (root),
 *     and moved next to each other with mremap (the frame does not change).
 *     Within the group's 64 sets, "set k" below means the group's k-th set.
 *   - L1 D-cache set = page offset bits 10:6.
 *   - H ("hit") lines: sets 0..h-1 on HP = 3/4 x ways pages (12). 12 lines per 16-way set always fit,
 *     with 4 ways spare for stray OS lines. Between two touches of an H line its L1 set takes 23 other
 *     misses, so it has left the 4-way L1 (survives (3/4)^23 = 0.1%).
 *     Every H access: L1 miss -> L2 primary hit.
 *   - M ("miss") lines: sets 32..32+m-1 on MP = 8 x ways pages (128). A line survives the 127 misses
 *     between two of its touches with (15/16)^127 = 0.03%.
 *     Every M access: L1 miss -> L2 data miss.
 *   - Each step touches h H lines and m M lines, interleaved. Designed miss rate = m / (h + m).
 *
 * Reads only by default. L1 victims are then clean, and Rocket's D-cache drops clean victims silently
 * (acquireBeforeRelease = false), so there is no inner-C write-back: the legacy L2_Accesses / L2_Hits
 * should agree with the new counters. -w makes the M accesses stores. Dirty L1 victims then come
 * back as inner-C ReleaseData, which the legacy counters count as an access AND a hit.
 *
 * The design holds only on a clean cache with migration OFF. Lines parked by an earlier migrate-ON run
 * take ways in the H sets and push H lines out (the program warns). With migration ON, M sets are hot
 * and H sets cold, so migrations park M victims in H sets: the difference from 50% is SBC's effect.
 *
 * Noise the design does not remove: page-table walks (about one per step, for the M page), kernel
 * ticks, and this program's own start and finish.
 *
 * Build: riscv64-unknown-linux-gnu-gcc -O2 -static -o sw/build/l2_miss_calib sw/l2_miss_calib.c
 * Run:   ./l2_miss_calib [-s steps] [-W warmup] [-h hitLines] [-m missLines] [-w] [-n]
 *        -n: no MMIO at all (no counter reset, no read-back; assumes 64 sets x 16 ways x 64 B).
 *        Otherwise it zeroes the event counters (SBC_StatsReset) after warm-up and reads them under
 *        L2_StatsHold at the end, so its own output is an exact window. Safe inside sbc_read --zero --.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdint.h>
#include <time.h>
#include <sys/mman.h>

#define L2_CTRL_BASE 0x2010000UL
#define MAP_LEN      0x1000UL
#define PAGE         4096UL

/* control-block offsets (Control.scala, sw/sbc_mmio.h) */
#define OFF_CONFIG        0x000
#define OFF_STATUS        0x320
#define OFF_MIGRATIONS    0x328
#define OFF_SECHITS       0x330
#define OFF_PARKED        0x3A0
#define OFF_L2_ACCESSES   0x3A8
#define OFF_L2_HITS       0x3B0
#define OFF_STATSRESET    0x3B8
#define OFF_MIGRATEENABLE 0x3C0
#define OFF_MEMREADS      0x3C8
#define OFF_MEMWRITES     0x3D0
#define OFF_MEMACQPERM    0x3D8
#define OFF_MEMRELCLEAN   0x3E0
#define OFF_CYCLES        0x3E8
#define OFF_ACCESSA       0x3F0
#define OFF_PRIMARYHIT    0x3F8
#define OFF_SECONDARYHIT  0x400
#define OFF_PROBEDHIT     0x408
#define OFF_DATAMISS      0x410
#define OFF_UPGRADEMISS   0x418
#define OFF_SECONDSEARCH  0x420
#define OFF_SECONDARYMISS 0x428
#define OFF_STATSHOLD     0x438

static volatile uint64_t *ctl;
static uint64_t rd(unsigned off) { return ctl[off / 8]; }
static void     wr(unsigned off, uint64_t v) { ctl[off / 8] = v; }

typedef unsigned long long ull;

static double now_s(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + 1e-9 * (double)t.tv_nsec;
}

static int cmp_u64(const void *a, const void *b) {
    uint64_t x = *(const uint64_t *)a, y = *(const uint64_t *)b;
    return x < y ? -1 : x > y;
}

/* Physical frame number of each page. Returns 0 if any is missing or hidden (not root). */
static int read_pfns(uint8_t *base, unsigned n, uint64_t *pfn) {
    int fd = open("/proc/self/pagemap", O_RDONLY);
    if (fd < 0) return 0;
    int ok = 1;
    for (unsigned i = 0; i < n; i++) {
        uint64_t e = 0;
        off_t o = (off_t)(((uintptr_t)base / PAGE + i) * 8);
        if (pread(fd, &e, 8, o) != 8 || !((e >> 63) & 1)) { pfn[i] = 0; ok = 0; continue; }
        pfn[i] = e & ((1ULL << 55) - 1);
        if (!pfn[i]) ok = 0;
    }
    close(fd);
    return ok;
}

/* Every page must be its own frame, and in the chosen set group, or the design is broken. */
static int report_frames(uint8_t *base, unsigned n, unsigned groups, unsigned q, uint64_t *out) {
    uint64_t *p = malloc(n * sizeof(uint64_t)), *s = malloc(n * sizeof(uint64_t));
    int ok = read_pfns(base, n, p);
    if (!ok) {
        printf("  frames     : frame numbers not readable (not root?) - not verified\n");
    } else {
        memcpy(s, p, n * sizeof(uint64_t));
        qsort(s, n, sizeof(uint64_t), cmp_u64);
        unsigned distinct = 1, wrong = 0;
        for (unsigned i = 1; i < n; i++) distinct += s[i] != s[i - 1];
        for (unsigned i = 0; i < n; i++) wrong += (unsigned)(p[i] & (groups - 1)) != q;
        printf("  frames     : %u pages, %u distinct physical frames, %u outside set group %u%s\n",
               n, distinct, wrong, q, (distinct == n && !wrong) ? "" : "  !!! design is broken");
        if (out) memcpy(out, p, n * sizeof(uint64_t));
    }
    free(p); free(s);
    return ok;
}

/* The workload. Pointers are computed, never loaded, and `sum` stays in a register, so the only
 * data-cache traffic is the designed lines. */
static uint64_t run_steps(uint8_t *hbase, uint8_t *mbase, uint64_t first, uint64_t n,
                          unsigned half, unsigned line, unsigned h, unsigned m,
                          unsigned hp, unsigned mp, int writes) {
    uint64_t sum = 0;
    for (uint64_t s = first; s < first + n; s++) {
        uint8_t *hpg = hbase + (s % hp) * PAGE;
        uint8_t *mpg = mbase + (s % mp) * PAGE;
        for (unsigned k = 0; k < half; k++) {
            if (k < h) sum += *(volatile uint64_t *)(hpg + (uintptr_t)k * line);
            if (k < m) {
                volatile uint64_t *a = (volatile uint64_t *)(mpg + (uintptr_t)(half + k) * line);
                if (writes) *a = s; else sum += *a;
            }
        }
    }
    return sum;
}

int main(int argc, char **argv) {
    uint64_t steps = 50000, warm = 2048;
    unsigned h = 32, m = 32, user_mp = 0;
    int writes = 0, nommio = 0;
    for (int i = 1; i < argc; i++) {
        if      (!strcmp(argv[i], "-s") && i + 1 < argc) steps = strtoull(argv[++i], 0, 0);
        else if (!strcmp(argv[i], "-W") && i + 1 < argc) warm  = strtoull(argv[++i], 0, 0);
        else if (!strcmp(argv[i], "-h") && i + 1 < argc) h = (unsigned)strtoul(argv[++i], 0, 0);
        else if (!strcmp(argv[i], "-m") && i + 1 < argc) m = (unsigned)strtoul(argv[++i], 0, 0);
        else if (!strcmp(argv[i], "-p") && i + 1 < argc) user_mp = (unsigned)strtoul(argv[++i], 0, 0);
        else if (!strcmp(argv[i], "-w")) writes = 1;
        else if (!strcmp(argv[i], "-n")) nommio = 1;
        else {
            fprintf(stderr, "usage: %s [-s steps] [-W warmup] [-h hitLines] [-m missLines] [-p missPages] [-w] [-n]\n",
                    argv[0]);
            return 2;
        }
    }

    /* ---- geometry ---- */
    unsigned ways = 16, lgSets = 6, lgBlock = 6;
    uint64_t parked0 = 0, mig0 = 0;
    if (!nommio) {
        int fd = open("/dev/mem", O_RDWR | O_SYNC);
        if (fd < 0) { perror("open /dev/mem (use -n to run without counters)"); return 1; }
        void *p = mmap(NULL, MAP_LEN, PROT_READ | PROT_WRITE, MAP_SHARED, fd, L2_CTRL_BASE);
        if (p == MAP_FAILED) { perror("mmap control block"); return 1; }
        ctl = (volatile uint64_t *)p;
        uint64_t cfg = rd(OFF_CONFIG);   /* banks[7:0] ways[15:8] lgSets[23:16] lgBlockBytes[31:24] */
        ways    = (unsigned)((cfg >> 8)  & 0xff);
        lgSets  = (unsigned)((cfg >> 16) & 0xff);
        lgBlock = (unsigned)((cfg >> 24) & 0xff);
        parked0 = rd(OFF_PARKED);
        mig0    = rd(OFF_MIGRATEENABLE) & 1;
    }
    int extra = (int)(lgSets + lgBlock) - 12;   /* set-index bits taken from the frame number */
    if (extra < 0) extra = 0;
    if (ways < 4 || lgSets < 2 || lgBlock < 3 || lgBlock > 11 || extra > 4) {
        fprintf(stderr, "l2_miss_calib: unsupported L2 geometry ways=%u lgSets=%u lgBlockBytes=%u\n",
                ways, lgSets, lgBlock);
        return 2;
    }
    unsigned groups = 1u << extra;               /* page groups by frame number */
    unsigned span = 1u << (lgSets - extra);      /* sets one page reaches */
    unsigned sets = 1u << lgSets, half = span / 2, line = 1u << lgBlock;
    if (h > half) h = half;
    if (m > half) m = half;
    unsigned hp = ways * 3 / 4, mp = user_mp ? user_mp : (ways * 8), need = hp + mp;

    /* ---- memory: HP hit pages then MP miss pages, each its own frame, all in one set group ---- */
    size_t len = (size_t)need * PAGE;
    uint8_t *base;
    unsigned q = 0;
    if (groups == 1) {
        base = mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (base == MAP_FAILED) { perror("mmap workload"); return 1; }
#ifdef MADV_NOHUGEPAGE
        madvise(base, len, MADV_NOHUGEPAGE);
#endif
        /* One write per page allocates a private frame (an untouched page maps the shared zero page).
         * Write inside the page's own kind of set, so setup does not litter the other kind. */
        for (unsigned i = 0; i < hp; i++) *(uint64_t *)(base + (size_t)i * PAGE) = 0x4800000000ULL + i;
        for (unsigned i = 0; i < mp; i++)
            *(uint64_t *)(base + (size_t)(hp + i) * PAGE + (size_t)half * line) = 0x4d00000000ULL + i;
    } else {
        unsigned pool = need * groups * 2 + 64;
        uint8_t *pb = mmap(NULL, (size_t)pool * PAGE, PROT_READ | PROT_WRITE,
                           MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (pb == MAP_FAILED) { perror("mmap page pool"); return 1; }
#ifdef MADV_NOHUGEPAGE
        madvise(pb, (size_t)pool * PAGE, MADV_NOHUGEPAGE);
#endif
        for (unsigned i = 0; i < pool; i++)
            *(uint64_t *)(pb + (size_t)i * PAGE + (size_t)half * line) = 0x5000000000ULL + i;
        uint64_t *pfn = malloc(pool * sizeof(uint64_t));
        if (!read_pfns(pb, pool, pfn)) {
            fprintf(stderr, "l2_miss_calib: %u L2 sets use %d bits of the physical frame number; reading "
                            "frame numbers from /proc/self/pagemap failed (run as root)\n", sets, extra);
            return 1;
        }
        unsigned cnt[16] = {0};
        for (unsigned i = 0; i < pool; i++) cnt[pfn[i] & (groups - 1)]++;
        for (unsigned g = 1; g < groups; g++) if (cnt[g] > cnt[q]) q = g;
        if (cnt[q] < need) {
            fprintf(stderr, "l2_miss_calib: only %u of %u pool pages share a set group (need %u)\n",
                    cnt[q], pool, need);
            return 1;
        }
        base = mmap(NULL, len, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);   /* reserve */
        if (base == MAP_FAILED) { perror("mmap reserve"); return 1; }
        unsigned j = 0;
        for (unsigned i = 0; i < pool && j < need; i++) {
            if ((pfn[i] & (groups - 1)) != q) continue;
            if (mremap(pb + (size_t)i * PAGE, PAGE, PAGE, MREMAP_MAYMOVE | MREMAP_FIXED,
                       base + (size_t)j * PAGE) == MAP_FAILED) { perror("mremap"); return 1; }
            j++;
        }
        munmap(pb, (size_t)pool * PAGE);   /* the pages that were not kept */
        free(pfn);
    }
    uint8_t *hbase = base, *mbase = base + (size_t)hp * PAGE;
    if (mlock(base, len) != 0) perror("mlock (continuing)");

    unsigned sb = q * span;   /* first L2 set of the chosen group */
    double designRate = (h + m) ? 100.0 * m / (h + m) : 0.0;
    printf("==== l2_miss_calib ====\n");
    printf("  L2         : %u sets x %u ways x %u B lines%s\n", sets, ways, line,
           nommio ? "  (assumed, -n)" : "  (from config word)");
    printf("  design     : per step %u hit lines (L2 sets %u..%u, %u pages) + %u miss lines "
           "(L2 sets %u..%u, %u pages), %s\n",
           h, sb, sb + (h ? h - 1 : 0), hp, m, sb + half, sb + half + (m ? m - 1 : 0), mp,
           writes ? "miss lines STORED" : "reads only");
    uint64_t *f0 = malloc(need * sizeof(uint64_t));
    int framesOk = report_frames(base, need, groups, q, f0);
    if (parked0)
        printf("  !!! %llu lines are parked from an earlier migrate-ON run. They take ways in the hit sets,\n"
               "  !!! so the 50%% design does not hold here. Reboot for a clean calibration.\n", (ull)parked0);
    if (mig0)
        printf("  !!! migration is ON - the design assumes OFF\n");

    /* ---- warm-up: fill the H sets and cycle the M pages ---- */
    uint64_t chk = run_steps(hbase, mbase, 0, warm, half, line, h, m, hp, mp, writes);

    /* ---- measured window ---- */
    if (!nommio) wr(OFF_STATSRESET, 1);
    double t0 = now_s();
    chk += run_steps(hbase, mbase, warm, steps, half, line, h, m, hp, mp, writes);
    double t1 = now_s();

    uint64_t v[24] = {0};
    if (!nommio) {
        wr(OFF_STATSHOLD, 1);     /* one instant for every register (no-op on an older bitstream) */
        v[0]  = rd(OFF_ACCESSA);      v[1]  = rd(OFF_PRIMARYHIT);   v[2]  = rd(OFF_SECONDARYHIT);
        v[3]  = rd(OFF_PROBEDHIT);    v[4]  = rd(OFF_DATAMISS);     v[5]  = rd(OFF_UPGRADEMISS);
        v[6]  = rd(OFF_SECONDSEARCH); v[7]  = rd(OFF_SECONDARYMISS);
        v[8]  = rd(OFF_L2_ACCESSES);  v[9]  = rd(OFF_L2_HITS);
        v[10] = rd(OFF_MEMREADS);     v[11] = rd(OFF_MEMWRITES);    v[12] = rd(OFF_MEMACQPERM);
        v[13] = rd(OFF_MEMRELCLEAN);  v[14] = rd(OFF_CYCLES);
        v[15] = rd(OFF_MIGRATIONS);   v[16] = rd(OFF_SECHITS);
        wr(OFF_STATSHOLD, 0);
        v[17] = rd(OFF_PARKED);       /* a level, never held */
        v[18] = rd(OFF_MIGRATEENABLE) & 1;
        v[19] = rd(OFF_STATUS) & 1;
    }

    if (framesOk) {
        uint64_t *f1 = malloc(need * sizeof(uint64_t));
        unsigned moved = 0;
        if (read_pfns(base, need, f1))
            for (unsigned i = 0; i < need; i++) moved += f1[i] != f0[i];
        printf("  frames     : %s\n", moved ? "!!! pages moved to other frames during the run" :
                                              "unchanged during the run");
        free(f1);
    }

    uint64_t dAcc = steps * (h + m), dMiss = steps * m;
    printf("[L2MISS-DESIGN] sets=%u ways=%u line=%u group=%u firstSet=%u h=%u m=%u hpages=%u mpages=%u "
           "steps=%llu warmup=%llu writes=%d designedAccesses=%llu designedMisses=%llu "
           "designedMissRate=%.2f%% parkedAtStart=%llu seconds=%.2f checksum=%llx\n",
           sets, ways, line, q, sb, h, m, hp, mp, (ull)steps, (ull)warm, writes, (ull)dAcc, (ull)dMiss,
           designRate, (ull)parked0, t1 - t0, (ull)chk);
    if (nommio) {
        printf("  (-n: no counters read; wrap with sbc_read --zero -- to measure)\n");
        return 0;
    }
    printf("[L2MISS-MEASURED] accessA=%llu primaryHit=%llu secondaryHit=%llu probedHit=%llu dataMiss=%llu "
           "upgradeMiss=%llu secondSearch=%llu secondaryMiss=%llu legacyAccesses=%llu legacyHits=%llu "
           "memReads=%llu memWrites=%llu memAcqPerm=%llu memRelClean=%llu cycles=%llu migrations=%llu "
           "secHits=%llu parked=%llu migrate=%llu sbcBuilt=%llu\n",
           (ull)v[0], (ull)v[1], (ull)v[2], (ull)v[3], (ull)v[4], (ull)v[5], (ull)v[6], (ull)v[7],
           (ull)v[8], (ull)v[9], (ull)v[10], (ull)v[11], (ull)v[12], (ull)v[13], (ull)v[14],
           (ull)v[15], (ull)v[16], (ull)v[17], (ull)v[18], (ull)v[19]);

    uint64_t acc = v[0], ph = v[1], sh = v[2], dm = v[4], um = v[5];
    uint64_t portMiss = v[10] + v[12];
    if (acc) {
        long long inprog = (long long)acc - (long long)(ph + sh + dm + um);
        printf("  terminology: accesses %llu (designed %llu, %+.2f%%)\n", (ull)acc, (ull)dAcc,
               dAcc ? 100.0 * ((double)acc - (double)dAcc) / (double)dAcc : 0.0);
        printf("               miss rate %.2f%% (designed %.2f%%)   hit rate %.2f%% "
               "(primary %llu + secondary %llu)\n",
               100.0 * (double)(dm + um) / (double)acc, designRate, 100.0 * (double)(ph + sh) / (double)acc,
               (ull)ph, (ull)sh);
        printf("               data misses %llu (designed %llu), upgrade misses %llu, "
               "second searches %llu, in progress %lld\n",
               (ull)dm, (ull)dMiss, (ull)um, (ull)v[6], inprog);
        printf("  port check : dataMiss + upgradeMiss = %llu, memReads + memAcqPerm = %llu %s\n",
               (ull)(dm + um), (ull)portMiss, (dm + um) == portMiss ? "(equal)" : "!!! NOT EQUAL");
    } else {
        printf("  terminology: L2_AccessA reads 0 - this bitstream predates the task-005 counters\n");
        printf("  port misses: memReads + memAcqPerm = %llu (designed %llu)\n", (ull)portMiss, (ull)dMiss);
    }
    if (v[8])
        printf("  legacy     : lookups %llu, lookup miss rate %.2f%% (counts inner-C write-backs as hits)\n",
               (ull)v[8], 100.0 * (double)(v[8] - v[9]) / (double)v[8]);
    printf("  memory     : reads %llu, writes %llu, clean releases %llu, L2 cycles %llu\n",
           (ull)v[10], (ull)v[11], (ull)v[13], (ull)v[14]);
    if (v[19])
        printf("  SBC        : migrate %s, migrations %llu, secHits %llu, parked %llu%s\n",
               v[18] ? "ON" : "OFF", (ull)v[15], (ull)v[16], (ull)v[17],
               v[18] ? "  (design assumes migrate OFF)" : "");
    return 0;
}
