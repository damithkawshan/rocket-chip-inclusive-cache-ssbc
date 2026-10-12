/* hugepage_setmap.c - does the program control the L2 set index, or does Linux?
 *
 * At 1 MB / 8 ways / 2048 sets / 64 B lines the L2 set index is PA[16:6]. A 4 KB page gives the
 * program only PA[11:6] (64 sets); PA[16:12] comes from the physical frame number, which Linux
 * picks. A 2 MB hugepage puts the whole set index inside the page offset, so the program owns it.
 *
 * The test builds N addresses that SHOULD all land in ONE set (stride = sets * blockBytes), then:
 *   1. reports PFN contiguity / 2 MB alignment from /proc/self/pagemap
 *   2. computes, from the REAL physical addresses, which set each one actually lands in
 *   3. walks them to thrash that set and reads the hardware's own per-set saturation (0x300/0x308)
 *
 * Expect: -H (hugepage) concentrates ~100% of the addresses in one set; -4 (plain pages) smears
 * them over 2^(lgSets+lgBlock-12) set groups. Needs root: /dev/mem and /proc/self/pagemap.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>

#include "sbc_mmio.h"

#ifndef MAP_HUGETLB
#define MAP_HUGETLB 0x40000
#endif

#define HPAGE   (2UL << 20)     /* Hugepagesize: 2048 kB */
#define MAP_LEN 0x1000UL

static volatile uint64_t *ctl;
static uint64_t rd(unsigned long a) { return ctl[(a - L2_CTRL_BASE) / 8]; }
static void     wr(unsigned long a, uint64_t v) { ctl[(a - L2_CTRL_BASE) / 8] = v; }

/* Physical frame number backing a virtual address, or 0 if absent / not root. */
static uint64_t pfn_of(int fd, uintptr_t va)
{
    uint64_t e;
    if (pread(fd, &e, 8, (off_t)(va / 4096) * 8) != 8) return 0;
    if (!(e & (1ULL << 63))) return 0;              /* not present */
    return e & ((1ULL << 55) - 1);
}

int main(int argc, char **argv)
{
    int      huge = 1, iters = 200, window = 4;
    unsigned target = 100, regions = 4;

    for (int i = 1; i < argc; i++) {
        if      (!strcmp(argv[i], "-4")) huge = 0;
        else if (!strcmp(argv[i], "-H")) huge = 1;
        else if (!strcmp(argv[i], "-s") && i + 1 < argc) target  = (unsigned)atoi(argv[++i]);
        else if (!strcmp(argv[i], "-r") && i + 1 < argc) regions = (unsigned)atoi(argv[++i]);
        else if (!strcmp(argv[i], "-i") && i + 1 < argc) iters   = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-w") && i + 1 < argc) window  = atoi(argv[++i]);
        else {
            fprintf(stderr,
                "usage: %s [-H|-4] [-s set] [-r regions] [-i iters] [-w window]\n"
                "  -H  2 MB hugepages (MAP_HUGETLB, default)   -4  plain 4 KB pages (control)\n"
                "  -s  target L2 set (default 100)             -r  2 MB regions (default 4)\n"
                "  -i  walk repetitions (default 200)          -w  sets printed each side (default 4)\n",
                argv[0]);
            return 2;
        }
    }

    /* ---- geometry, read from the cache itself ---- */
    int memfd = open("/dev/mem", O_RDWR | O_SYNC);
    if (memfd < 0) { perror("open /dev/mem (need root)"); return 1; }
    void *cp = mmap(NULL, MAP_LEN, PROT_READ | PROT_WRITE, MAP_SHARED, memfd, L2_CTRL_BASE);
    if (cp == MAP_FAILED) { perror("mmap control block"); return 1; }
    ctl = (volatile uint64_t *)cp;

    uint64_t cfg = rd(L2_CTRL_BASE + 0x000);
    unsigned ways    = (unsigned)((cfg >> 8)  & 0xff);
    unsigned lgSets  = (unsigned)((cfg >> 16) & 0xff);
    unsigned lgBlock = (unsigned)((cfg >> 24) & 0xff);
    unsigned sets    = 1u << lgSets, line = 1u << lgBlock;
    unsigned long stride = (unsigned long)sets * line;        /* same-set stride */
    int extra = (int)(lgSets + lgBlock) - 12;                 /* set bits owned by the frame number */
    if (extra < 0) extra = 0;

    if (target >= sets) { fprintf(stderr, "set %u >= %u sets\n", target, sets); return 2; }
    if (stride > HPAGE) { fprintf(stderr, "stride %lu exceeds a 2 MB page\n", stride); return 2; }

    unsigned per_region = (unsigned)(HPAGE / stride);
    unsigned naddr      = per_region * regions;

    printf("L2: %u ways, %u sets, %u B lines -> set index PA[%u:%u], same-set stride %lu KB\n",
           ways, sets, line, lgSets + lgBlock - 1, lgBlock, stride >> 10);
    printf("    %d of %u set-index bits come from the physical frame (%u page groups)\n",
           extra, lgSets, 1u << extra);
    printf("mode: %s, %u x 2 MB regions, %u addresses aimed at set %u\n\n",
           huge ? "2 MB HUGEPAGES" : "plain 4 KB pages", regions, naddr, target);

    /* ---- allocate ---- */
    size_t len   = (size_t)regions * HPAGE;
    int    flags = MAP_PRIVATE | MAP_ANONYMOUS | (huge ? MAP_HUGETLB : 0);
    uint8_t *base = mmap(NULL, len, PROT_READ | PROT_WRITE, flags, -1, 0);
    if (base == MAP_FAILED) {
        perror(huge ? "mmap MAP_HUGETLB (reserve pool: echo N > /proc/sys/vm/nr_hugepages)"
                    : "mmap");
        return 1;
    }
    for (size_t o = 0; o < len; o += 4096) base[o] = 1;       /* fault every page in */

    /* ---- step 1+2: where do these addresses REALLY land? ---- */
    int pm = open("/proc/self/pagemap", O_RDONLY);
    if (pm < 0) { perror("open /proc/self/pagemap"); return 1; }

    unsigned *hist = calloc(sets, sizeof(unsigned));
    uint8_t **addr = malloc(naddr * sizeof(*addr));
    unsigned  resolved = 0, in_target = 0, distinct = 0;
    uint64_t  pfn0 = 0;
    int       contiguous = 1;

    for (unsigned r = 0, n = 0; r < regions; r++) {
        for (unsigned k = 0; k < per_region; k++, n++) {
            uint8_t *a = base + (size_t)r * HPAGE + (size_t)target * line + (size_t)k * stride;
            addr[n] = a;
            uint64_t pfn = pfn_of(pm, (uintptr_t)a);
            if (!pfn) continue;
            uint64_t pa = (pfn << 12) | ((uintptr_t)a & 0xFFF);
            unsigned s  = (unsigned)((pa >> lgBlock) & (sets - 1));
            hist[s]++; resolved++;
            if (s == target) in_target++;
        }
    }
    for (unsigned s = 0; s < sets; s++) if (hist[s]) distinct++;

    /* PFN contiguity across the first region (a real hugepage is 512 consecutive frames) */
    pfn0 = pfn_of(pm, (uintptr_t)base);
    for (unsigned p = 0; p < HPAGE / 4096; p++) {
        uint64_t want = pfn0 + p, got = pfn_of(pm, (uintptr_t)base + (size_t)p * 4096);
        if (!pfn0 || got != want) { contiguous = 0; break; }
    }

    printf("region 0 first PFN      : 0x%llx  (%s, %s2 MB-aligned)\n",
           (unsigned long long)pfn0,
           contiguous ? "512 frames CONTIGUOUS" : "frames NOT contiguous",
           (pfn0 % (HPAGE / 4096)) ? "NOT " : "");
    if (!resolved) {
        printf("!! no PFNs readable - are you root?\n");
        return 1;
    }
    printf("addresses resolved      : %u of %u\n", resolved, naddr);
    printf("landed in target set %-4u: %u  (%.1f%%)\n",
           target, in_target, 100.0 * in_target / resolved);
    printf("distinct sets touched   : %u  (ideal 1, scattered ~%u)\n\n",
           distinct, 1u << extra);

    /* ---- step 3: thrash the set, then read the hardware's saturation counters ---- */
    uint64_t mig0 = rd(SBC_MIGRATIONS);
    volatile uint64_t sink = 0;
    for (int i = 0; i < iters; i++)
        for (unsigned n = 0; n < naddr; n++) sink += *(volatile uint8_t *)addr[n];
    (void)sink;

    printf("per-set saturation after %d passes (T_hi = %u):\n", iters, 2 * ways - 1);
    int lo = (int)target - window, hi = (int)target + window;
    if (lo < 0) lo = 0;
    if (hi >= (int)sets) hi = (int)sets - 1;
    for (int s = lo; s <= hi; s++) {
        wr(SBC_SETSEL, (uint64_t)s);
        uint64_t sat = rd(SBC_SETSAT);
        printf("  set %-5d sat %-3llu %s%s\n", s, (unsigned long long)sat,
               sat ? "#" : "", (s == (int)target) ? "   <-- target" : "");
    }
    printf("\nmigrations %+lld, parked now %llu\n",
           (long long)(rd(SBC_MIGRATIONS) - mig0), (unsigned long long)rd(SBC_PARKED));

    printf("\nVERDICT: the program %s the set index (%.1f%% concentration, %u distinct sets)\n",
           (100.0 * in_target / resolved) > 90.0 ? "CONTROLS" : "does NOT control",
           100.0 * in_target / resolved, distinct);
    return 0;
}
