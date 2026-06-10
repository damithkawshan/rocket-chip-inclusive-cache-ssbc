/*
 * l2_tldmon_wrapper.c — CLI tool for L2 phase-detection cache resource monitor.
 *
 * Accesses the InclusiveCacheControl MMIO registers via /dev/mem.
 *
 * Register map (offsets from L2_CTRL_PHYS_BASE = 0x2010000):
 *   0x000  Cache config   : {lgBlockBytes[31:24], lgSets[23:16], ways[15:8], banks[7:0]}
 *   0x400  Control        : bit0=enable, bit1=reset (auto-clears), bit2=useMorris
 *   0x408  Interval       : sampling interval in cycles (32-bit)
 *   0x410  ThreshLo       : CSC activity bucket low threshold
 *   0x418  Status         : bit0=full, bit1=streaming
 *   0x420  Write count    : number of completed snapshots
 *   0x428  snapIdx        : snapshot index for indexed readout (write before reading)
 *   0x430  wordIdx        : 64-bit word index within snapshot  (write before reading)
 *   0x438  readData       : 64-bit word at (snapIdx, wordIdx)
 *   0x440  Geometry       : {nSetsLg2[31:24], nSrc[23:16], actWords[15:8], numWords[7:0]}
 *   0x448  GeomWide       : {numWords[31:16], actWords[15:0]}
 *   0x450  GeomExt        : {reserved[31:24], cscWidth[23:16], probeWords[15:8], ssWords[7:0]}
 *   0x458  ThreshHi       : CSC activity bucket high threshold
 *   0x460  ProbeThreshLo  : probeCsc bucket low threshold
 *   0x468  ProbeThreshHi  : probeCsc bucket high threshold
 *   0x470  DecayPeriod    : continuous-decay timer period (cycles); 0 = disabled
 *   0x478  DecayShift     : right-shift applied on each decay pulse
 *   0x480  MissedSnaps    : snapshots dropped due to streaming overlap (RO; cleared by reset)
 *
 * Snapshot RAM layout (64-bit words per snapshot):
 *   words [0                      .. act_words)              activity buckets
 *                                                            (2 bits per (set,src),
 *                                                             LSB-first index = set*N_SRC+src,
 *                                                             bucket: 0=idle,1=cold,2=warm,3=hot)
 *   words [act_words              .. act_words+ss_words)     setstate (5 bits/set:
 *                                                            {dirtyBkt[1:0],invCnt[2:0]})
 *   words [act_words+ss_words     .. num_words)              probe buckets
 *                                                            (2 bits per (set,src), same
 *                                                             quantization as activity)
 *
 * Commands:
 *   configure <interval> [threshLo [threshHi]]
 *   set-probe-thresh <lo> <hi>
 *   set-decay <period> <shift>
 *   set-morris <0|1>
 *   start / stop / reset / status
 *   dump [max_snapshots]
 *   devmem_check
 */

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

/* ---- L2 Control MMIO base ---- */
#define L2_CTRL_PHYS_BASE   0x2010000UL
#define L2_CTRL_MAP_SIZE    4096

/* ---- Register offsets (must match Control.scala) ---- */
#define L2_REG_CONFIG            0x000U
#define TLD_REG_CTRL             0x400U
#define TLD_REG_INTERVAL         0x408U
#define TLD_REG_THRESH_LO        0x410U
#define TLD_REG_STATUS           0x418U
#define TLD_REG_WRITE_COUNT      0x420U
#define TLD_REG_SNAP_IDX         0x428U
#define TLD_REG_WORD_IDX         0x430U
#define TLD_REG_READ_DATA        0x438U   /* 64-bit */
#define TLD_REG_GEOM             0x440U
#define TLD_REG_GEOM_WIDE        0x448U
#define TLD_REG_GEOM_EXT         0x450U
#define TLD_REG_THRESH_HI        0x458U
#define TLD_REG_PROBE_THRESH_LO  0x460U
#define TLD_REG_PROBE_THRESH_HI  0x468U
#define TLD_REG_DECAY_PERIOD     0x470U
#define TLD_REG_DECAY_SHIFT      0x478U
#define TLD_REG_MISSED_SNAPS     0x480U  /* RO: snaps dropped due to streaming overlap */

/* ---- Control bits in TLD_REG_CTRL ---- */
#define TLD_CTRL_ENABLE     (1U << 0)
#define TLD_CTRL_RESET      (1U << 1)
#define TLD_CTRL_USE_MORRIS (1U << 2)

/* ---- MMIO mapped pointer ---- */
static uintptr_t mmio_base = 0;

static inline uint32_t mmio_rd32(uint32_t off)
{
    volatile uint32_t *p = (volatile uint32_t *)(mmio_base + off);
    return *p;
}

static inline uint64_t mmio_rd64(uint32_t off)
{
    volatile uint64_t *p = (volatile uint64_t *)(mmio_base + off);
    return *p;
}

static inline void mmio_wr32(uint32_t off, uint32_t val)
{
    volatile uint32_t *p = (volatile uint32_t *)(mmio_base + off);
    *p = val;
    __asm__ volatile ("fence" ::: "memory");
}

/* ---- Geometry ---- */
typedef struct {
    uint32_t n_sets;
    uint32_t n_sets_lg2;
    uint32_t n_src;
    uint32_t csc_width;
    uint32_t act_words;
    uint32_t ss_words;
    uint32_t probe_words;
    uint32_t num_words;
} Geom;

static Geom read_geom(void)
{
    uint32_t g     = mmio_rd32(TLD_REG_GEOM);
    uint32_t gwide = mmio_rd32(TLD_REG_GEOM_WIDE);
    uint32_t gext  = mmio_rd32(TLD_REG_GEOM_EXT);
    Geom geo;
    geo.n_sets_lg2 = (g >> 24) & 0xFF;
    geo.n_src      = (g >> 16) & 0xFF;
    geo.act_words  = gwide & 0xFFFF;
    geo.num_words  = (gwide >> 16) & 0xFFFF;
    geo.ss_words   =  gext        & 0xFF;
    geo.probe_words= (gext >>  8) & 0xFF;
    geo.csc_width  = (gext >> 16) & 0xFF;
    geo.n_sets     = 1U << geo.n_sets_lg2;
    return geo;
}

/* ---- Helpers ---- */

static uint32_t mask_csc(uint32_t v, uint32_t csc_width)
{
    if (csc_width == 0 || csc_width >= 32) return v;
    return v & ((1U << csc_width) - 1U);
}

/* Read-modify-write of TLD_REG_CTRL preserving the enable + useMorris bits.
 * Reset is a write-only auto-clearing pulse; enable + useMorris are sticky. */
static uint32_t read_ctrl_sticky(void)
{
    return mmio_rd32(TLD_REG_CTRL) & (TLD_CTRL_ENABLE | TLD_CTRL_USE_MORRIS);
}

/* ---- Commands ---- */

static void cmd_configure(uint32_t interval, int have_lo, uint32_t thresh_lo,
                          int have_hi, uint32_t thresh_hi)
{
    Geom geo = read_geom();
    /* Default thresholds when not supplied: lo=1 (any activity), hi=CSC_MAX/2 + 1.
     * The HW masks to csc_width so over-large values are fine, but we clamp here
     * so the printed status is accurate. */
    uint32_t csc_max = (geo.csc_width == 0 || geo.csc_width >= 32)
                     ? 0xFFFFFFFFU : ((1U << geo.csc_width) - 1U);
    if (!have_lo) thresh_lo = 1U;
    if (!have_hi) thresh_hi = (csc_max >> 1) + 1U;
    if (thresh_lo > csc_max) thresh_lo = csc_max;
    if (thresh_hi > csc_max) thresh_hi = csc_max;

    mmio_wr32(TLD_REG_INTERVAL,  interval);
    mmio_wr32(TLD_REG_THRESH_LO, thresh_lo);
    mmio_wr32(TLD_REG_THRESH_HI, thresh_hi);
    printf("Phase monitor configured:\n");
    printf("  interval  = %u cycles\n", interval);
    printf("  threshLo  = %u\n", thresh_lo);
    printf("  threshHi  = %u  (csc_max=%u)\n", thresh_hi, csc_max);
}

static void cmd_set_probe_thresh(uint32_t lo, uint32_t hi)
{
    Geom geo = read_geom();
    uint32_t csc_max = (geo.csc_width == 0 || geo.csc_width >= 32)
                     ? 0xFFFFFFFFU : ((1U << geo.csc_width) - 1U);
    if (lo > csc_max) lo = csc_max;
    if (hi > csc_max) hi = csc_max;
    mmio_wr32(TLD_REG_PROBE_THRESH_LO, lo);
    mmio_wr32(TLD_REG_PROBE_THRESH_HI, hi);
    printf("Probe thresholds set: lo=%u hi=%u  (csc_max=%u)\n", lo, hi, csc_max);
}

static void cmd_set_decay(uint32_t period, uint32_t shift)
{
    if (shift > 7U) shift = 7U;
    mmio_wr32(TLD_REG_DECAY_PERIOD, period);
    mmio_wr32(TLD_REG_DECAY_SHIFT,  shift);
    if (period == 0)
        printf("Decay disabled (period=0).\n");
    else
        printf("Decay configured: period=%u cycles, shift=%u (csc >>= shift each fire)\n",
               period, shift);
}

static void cmd_set_morris(uint32_t use)
{
    uint32_t ctrl = mmio_rd32(TLD_REG_CTRL) & ~TLD_CTRL_USE_MORRIS;
    if (use) ctrl |= TLD_CTRL_USE_MORRIS;
    mmio_wr32(TLD_REG_CTRL, ctrl);
    printf("Morris probabilistic increment: %s\n", use ? "ENABLED" : "DISABLED");
}

static void cmd_start(void)
{
    uint32_t ctrl = (mmio_rd32(TLD_REG_CTRL) & TLD_CTRL_USE_MORRIS) | TLD_CTRL_ENABLE;
    mmio_wr32(TLD_REG_CTRL, ctrl);
    printf("Phase monitor started (enable=1).\n");
}

static void cmd_stop(void)
{
    uint32_t ctrl = mmio_rd32(TLD_REG_CTRL) & TLD_CTRL_USE_MORRIS;
    mmio_wr32(TLD_REG_CTRL, ctrl);
    printf("Phase monitor stopped (enable=0).\n");
}

static void cmd_reset(void)
{
    /* Reset is a single-cycle pulse; preserve enable/useMorris sticky bits. */
    uint32_t sticky = read_ctrl_sticky();
    mmio_wr32(TLD_REG_CTRL, sticky | TLD_CTRL_RESET);
    __asm__ volatile ("fence" ::: "memory");
    (void)mmio_rd32(TLD_REG_CTRL);  /* flush */
    mmio_wr32(TLD_REG_CTRL, sticky);
    printf("Phase monitor reset (CSC + probe counters + history + missed-snap count cleared;\n"
           "  invCnt/dirtyCnt rebuilt from live directory shadow).\n");
}

static void cmd_status(void)
{
    uint32_t cfg       = mmio_rd32(L2_REG_CONFIG);
    uint32_t banks     = (cfg >>  0) & 0xFF;
    uint32_t ways      = (cfg >>  8) & 0xFF;
    uint32_t lg_sets   = (cfg >> 16) & 0xFF;
    uint32_t ctrl      = mmio_rd32(TLD_REG_CTRL);
    uint32_t interval  = mmio_rd32(TLD_REG_INTERVAL);
    uint32_t thresh_lo = mmio_rd32(TLD_REG_THRESH_LO);
    uint32_t thresh_hi = mmio_rd32(TLD_REG_THRESH_HI);
    uint32_t pthr_lo   = mmio_rd32(TLD_REG_PROBE_THRESH_LO);
    uint32_t pthr_hi   = mmio_rd32(TLD_REG_PROBE_THRESH_HI);
    uint32_t decay_p   = mmio_rd32(TLD_REG_DECAY_PERIOD);
    uint32_t decay_s   = mmio_rd32(TLD_REG_DECAY_SHIFT);
    uint32_t status    = mmio_rd32(TLD_REG_STATUS);
    uint32_t wcount    = mmio_rd32(TLD_REG_WRITE_COUNT);
    Geom geo           = read_geom();

    printf("==========================================\n");
    printf("  L2 Phase-Detection Monitor Status\n");
    printf("==========================================\n");
    printf("Cache config    : banks=%u ways=%u sets=%u\n", banks, ways, 1U << lg_sets);
    printf("Monitor geometry: n_sets=%u n_src=%u csc_width=%u\n",
           geo.n_sets, geo.n_src, geo.csc_width);
    printf("Snapshot layout : act_words=%u ss_words=%u probe_words=%u num_words=%u\n",
           geo.act_words, geo.ss_words, geo.probe_words, geo.num_words);
    printf("Enabled         : %s\n",      (ctrl & TLD_CTRL_ENABLE)     ? "YES" : "NO");
    printf("Morris (log)    : %s\n",      (ctrl & TLD_CTRL_USE_MORRIS) ? "YES" : "NO");
    printf("Interval        : %u cycles\n", interval);
    printf("Activity thresh : lo=%u hi=%u\n", thresh_lo, thresh_hi);
    printf("Probe thresh    : lo=%u hi=%u\n", pthr_lo,   pthr_hi);
    if (decay_p == 0)
        printf("Leaky decay     : disabled\n");
    else
        printf("Leaky decay     : period=%u cycles, shift=%u\n", decay_p, decay_s);
    printf("Status          : full=%s streaming=%s\n",
           (status & 0x1) ? "yes" : "no",
           (status & 0x2) ? "yes" : "no");
    printf("Snapshots taken : %u\n",      wcount);    printf("Snapshots missed: %u\n",      mmio_rd32(TLD_REG_MISSED_SNAPS));    printf("==========================================\n");
}

/*
 * cmd_dump — read completed snapshots from the snapshot RAM.
 *
 * CSV output format:
 *   # comment header with geometry
 *   snap_idx,w0,w1,...,wN   (hex 64-bit words)
 *
 * Word layout within each snapshot (decoded offline by Python tool):
 *   words [0                      .. act_words)              : activity buckets (2 bits each)
 *   words [act_words              .. act_words+ss_words)     : setstate
 *   words [act_words+ss_words     .. num_words)              : probe buckets (2 bits each)
 */
static void cmd_dump(uint32_t max_snaps)
{
    uint32_t wcount = mmio_rd32(TLD_REG_WRITE_COUNT);
    uint32_t status = mmio_rd32(TLD_REG_STATUS);
    Geom geo        = read_geom();
    uint32_t snaps  = wcount;

    if (max_snaps > 0 && max_snaps < snaps)
        snaps = max_snaps;

    printf("# l2_tldmon phase-detection dump  format=v2\n");
    printf("# n_sets=%u n_src=%u csc_width=%u\n", geo.n_sets, geo.n_src, geo.csc_width);
    printf("# act_words=%u ss_words=%u probe_words=%u num_words=%u\n",
           geo.act_words, geo.ss_words, geo.probe_words, geo.num_words);
    printf("# act_bits_per_field=2 probe_bits_per_field=2 ss_bits_per_set=5\n");
    printf("# snapshots_available=%u full=%s\n",
           wcount, (status & 0x1) ? "yes" : "no");
    printf("# dumping=%u\n", snaps);

    /* Wait for any in-flight snapshot stream to finish before reading the RAM.
     * The streaming bit clears within NUM_WORDS cycles (~18 cycles at 50 MHz),
     * but a stop-then-immediate-dump race can still catch it mid-flight. */
    {
        unsigned int retries = 0;
        while ((mmio_rd32(TLD_REG_STATUS) & 0x2) && retries++ < 100000)
            __asm__ volatile ("" ::: "memory");  /* compiler fence; no-op spin */
    }

    printf("snap_idx");
    for (uint32_t w = 0; w < geo.num_words; w++)
        printf(",w%u", w);
    printf("\n");

    for (uint32_t i = 0; i < snaps; i++) {
        mmio_wr32(TLD_REG_SNAP_IDX, i);
        __asm__ volatile ("fence" ::: "memory");

        printf("%u", i);
        for (uint32_t w = 0; w < geo.num_words; w++) {
            mmio_wr32(TLD_REG_WORD_IDX, w);
            __asm__ volatile ("fence" ::: "memory");
            uint64_t v = mmio_rd64(TLD_REG_READ_DATA);
            printf(",0x%016llx", (unsigned long long)v);
        }
        printf("\n");
    }
}

/*
 * cmd_devmem_check — Quick sanity check without a workload.
 * Reads geometry + a sample word; prints raw register values.
 * Useful immediately after FPGA boot or bitstream load.
 */
static void cmd_devmem_check(void)
{
    uint32_t cfg    = mmio_rd32(L2_REG_CONFIG);
    uint32_t geom   = mmio_rd32(TLD_REG_GEOM);
    uint32_t gext   = mmio_rd32(TLD_REG_GEOM_EXT);
    uint32_t status = mmio_rd32(TLD_REG_STATUS);
    uint32_t wcount = mmio_rd32(TLD_REG_WRITE_COUNT);
    Geom geo        = read_geom();

    printf("=== devmem quick check ===\n");
    printf("0x%08lx  L2 config  : 0x%08x  (banks=%u ways=%u lg_sets=%u)\n",
           L2_CTRL_PHYS_BASE + L2_REG_CONFIG, cfg,
           cfg & 0xFF, (cfg >> 8) & 0xFF, (cfg >> 16) & 0xFF);
    printf("0x%08lx  TLD geom   : 0x%08x  (n_sets_lg2=%u n_src=%u act_words=%u num_words=%u)\n",
           L2_CTRL_PHYS_BASE + TLD_REG_GEOM, geom,
           geo.n_sets_lg2, geo.n_src, geo.act_words, geo.num_words);
    printf("0x%08lx  TLD geomExt: 0x%08x  (csc_width=%u probe_words=%u ss_words=%u)\n",
           L2_CTRL_PHYS_BASE + TLD_REG_GEOM_EXT, gext,
           geo.csc_width, geo.probe_words, geo.ss_words);
    printf("0x%08lx  TLD status : 0x%08x  (bit0=full bit1=streaming)\n",
           L2_CTRL_PHYS_BASE + TLD_REG_STATUS, status);
    printf("0x%08lx  Snap count : %u\n",
           L2_CTRL_PHYS_BASE + TLD_REG_WRITE_COUNT, wcount);

    if (geo.n_src == 0 || geo.num_words == 0 || geo.csc_width == 0) {
        printf("WARN: geometry reads as zero — monitor may not be synthesized "
               "or MMIO map mismatch.\n");
        return;
    }
    printf("OK: geometry looks valid.\n");

    /* Basic RAM read: snap[0][0] */
    mmio_wr32(TLD_REG_SNAP_IDX, 0);
    mmio_wr32(TLD_REG_WORD_IDX, 0);
    __asm__ volatile ("fence" ::: "memory");
    uint64_t d = mmio_rd64(TLD_REG_READ_DATA);
    printf("0x%08lx  snap[0][0] : 0x%016llx\n",
           L2_CTRL_PHYS_BASE + TLD_REG_READ_DATA, (unsigned long long)d);
    printf("==========================\n");
}

/* Suppress -Wunused-function if mask_csc isn't called in a given build. */
static uint32_t __attribute__((unused)) _silence_mask_csc(uint32_t v, uint32_t w)
{ return mask_csc(v, w); }

/* ---- Usage ---- */

static void print_usage(const char *prog)
{
    printf("Usage: %s <command> [args...]\n", prog);
    printf("Commands:\n");
    printf("  configure <interval> [threshLo [threshHi]]\n");
    printf("      Interval in cycles. Activity buckets:\n");
    printf("        idle  : csc == 0\n");
    printf("        cold  : csc <  threshLo\n");
    printf("        warm  : csc <  threshHi\n");
    printf("        hot   : otherwise\n");
    printf("      Default thresholds: threshLo=1, threshHi=(csc_max/2 + 1).\n");
    printf("  set-probe-thresh <lo> <hi>\n");
    printf("      Same quantization as activity, applied to per-(set,src) probe counter.\n");
    printf("  set-decay <period> <shift>\n");
    printf("      Continuous-decay timer. period=0 disables. Otherwise every <period>\n");
    printf("      cycles: csc >>= shift, probeCsc >>= shift. Snapshots no longer decay.\n");
    printf("  set-morris <0|1>\n");
    printf("      0 = linear +1 per event; 1 = Morris probabilistic increment (log-scale).\n");
    printf("  start            Enable sampling.\n");
    printf("  stop             Disable sampling.\n");
    printf("  reset            Clear CSC + probe counters + history index.\n");
    printf("  status           Print configuration and snapshot count.\n");
    printf("  dump [N]         Dump snapshots as CSV (default: all).\n");
    printf("  devmem_check     Quick sanity check of hardware registers.\n");
    printf("\nTypical workflow:\n");
    printf("  %s devmem_check\n", prog);
    printf("  %s reset\n", prog);
    printf("  %s configure 48828           # 1ms @ 50MHz, default thresholds\n", prog);
    printf("  %s set-decay 6103 1          # decay every ~125us (interval/8)\n", prog);
    printf("  %s set-morris 1              # log-scale counter\n", prog);
    printf("  %s start\n", prog);
    printf("  ... workload ...\n");
    printf("  %s stop\n", prog);
    printf("  %s status\n", prog);
    printf("  %s dump > phase_history.csv\n", prog);
}

/* ---- main ---- */

int main(int argc, char *argv[])
{
    if (argc < 2) { print_usage(argv[0]); return 1; }

    const char *cmd = argv[1];

    int fd = open("/dev/mem", O_RDWR | O_SYNC);
    if (fd < 0) { perror("open /dev/mem"); return 1; }

    void *map = mmap(NULL, L2_CTRL_MAP_SIZE, PROT_READ | PROT_WRITE,
                     MAP_SHARED, fd, L2_CTRL_PHYS_BASE);
    if (map == MAP_FAILED) { perror("mmap"); close(fd); return 1; }
    mmio_base = (uintptr_t)map;

    int ret = 0;

    if (strcmp(cmd, "configure") == 0) {
        if (argc < 3) {
            printf("Usage: %s configure <interval> [threshLo [threshHi]]\n", argv[0]);
            ret = 1;
        } else {
            uint32_t interval  = (uint32_t)strtoul(argv[2], NULL, 0);
            int have_lo = (argc >= 4);
            int have_hi = (argc >= 5);
            uint32_t thresh_lo = have_lo ? (uint32_t)strtoul(argv[3], NULL, 0) : 0U;
            uint32_t thresh_hi = have_hi ? (uint32_t)strtoul(argv[4], NULL, 0) : 0U;
            cmd_configure(interval, have_lo, thresh_lo, have_hi, thresh_hi);
        }
    } else if (strcmp(cmd, "set-probe-thresh") == 0) {
        if (argc < 4) {
            printf("Usage: %s set-probe-thresh <lo> <hi>\n", argv[0]);
            ret = 1;
        } else {
            uint32_t lo = (uint32_t)strtoul(argv[2], NULL, 0);
            uint32_t hi = (uint32_t)strtoul(argv[3], NULL, 0);
            cmd_set_probe_thresh(lo, hi);
        }
    } else if (strcmp(cmd, "set-decay") == 0) {
        if (argc < 4) {
            printf("Usage: %s set-decay <period> <shift>\n", argv[0]);
            ret = 1;
        } else {
            uint32_t period = (uint32_t)strtoul(argv[2], NULL, 0);
            uint32_t shift  = (uint32_t)strtoul(argv[3], NULL, 0);
            cmd_set_decay(period, shift);
        }
    } else if (strcmp(cmd, "set-morris") == 0) {
        if (argc < 3) {
            printf("Usage: %s set-morris <0|1>\n", argv[0]);
            ret = 1;
        } else {
            uint32_t use = (uint32_t)strtoul(argv[2], NULL, 0);
            cmd_set_morris(use ? 1U : 0U);
        }
    } else if (strcmp(cmd, "start")        == 0) { cmd_start();
    } else if (strcmp(cmd, "stop")         == 0) { cmd_stop();
    } else if (strcmp(cmd, "reset")        == 0) { cmd_reset();
    } else if (strcmp(cmd, "status")       == 0) { cmd_status();
    } else if (strcmp(cmd, "dump")         == 0) {
        uint32_t max = (argc >= 3) ? (uint32_t)strtoul(argv[2], NULL, 0) : 0U;
        cmd_dump(max);
    } else if (strcmp(cmd, "devmem_check") == 0) { cmd_devmem_check();
    } else { printf("Unknown command: %s\n", cmd); print_usage(argv[0]); ret = 1; }

    munmap(map, L2_CTRL_MAP_SIZE);
    close(fd);
    return ret;
}
