/*
 * l2_tldmon_wrapper.c — CLI tool for L2 TileLink+Directory event monitor.
 *
 * Accesses the InclusiveCacheControl MMIO registers (0x400-0x508) via /dev/mem.
 *
 * Each row of the history is the *delta* of every counter over one sampling
 * interval; counters auto-reset when a snapshot is committed.
 *
 * Counter layout (must match TLDirMonitor.scala IDX_* constants):
 *   0  c_inA            5  c_outA           10 a_acquirePerm     15 c_probeAckData
 *   1  c_inB            6  c_outC           11 a_getPut          16 d_grant
 *   2  c_inC            7  c_outD           12 c_release         17 d_grantData
 *   3  c_inD            8  c_outE           13 c_releaseData     18 dir_hit
 *   4  c_inE            9  a_acquireBlock   14 c_probeAck        19 dir_miss
 *  20 evict_clean      23 write_to_branch  26 mshr_alloc        28 secondary_hit
 *  21 evict_dirty      24 write_to_trunk   27 mshr_no_free
 *  22 write_to_invalid 25 write_to_tip
 *
 * Commands:
 *   configure <interval>
 *   start                      — enable sampling
 *   stop                       — disable sampling
 *   reset                      — reset all counters + history
 *   status                     — print current configuration / snapshot count
 *   dump [max_entries]         — read & print all history snapshots (CSV-ish)
 *
 * Usage:
 *   ./l2_tldmon_wrapper_riscv reset
 *   ./l2_tldmon_wrapper_riscv configure 500000
 *   ./l2_tldmon_wrapper_riscv start
 *   ... run workload ...
 *   ./l2_tldmon_wrapper_riscv stop
 *   ./l2_tldmon_wrapper_riscv dump
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

/* ---- TLDir monitor register offsets (must match Control.scala) ---- */
#define TLD_REG_CTRL        0x400U   /* bit0=enable, bit1=reset(auto-clear) */
#define TLD_REG_INTERVAL    0x408U   /* sampling interval in cycles */
#define TLD_REG_STATUS      0x410U   /* bit0=full */
#define TLD_REG_WRITE_COUNT 0x418U   /* # snapshots recorded */
#define TLD_REG_HIST_IDX    0x420U   /* index for history read */
#define TLD_REG_HIST_BASE   0x428U   /* first per-counter readback reg */
#define TLD_HIST_STRIDE     0x008U   /* 8 bytes between readback regs */

#define TLD_N_COUNTERS      29

/* ---- Cache config register (existing) ---- */
#define L2_REG_CONFIG       0x000U

static const char *TLD_FIELD_NAMES[TLD_N_COUNTERS] = {
    "c_inA",            "c_inB",           "c_inC",          "c_inD",          "c_inE",
    "c_outA",           "c_outC",          "c_outD",         "c_outE",
    "a_acquireBlock",   "a_acquirePerm",   "a_getPut",
    "c_release",        "c_releaseData",   "c_probeAck",     "c_probeAckData",
    "d_grant",          "d_grantData",
    "dir_hit",          "dir_miss",        "evict_clean",    "evict_dirty",
    "write_to_invalid", "write_to_branch", "write_to_trunk", "write_to_tip",
    "mshr_alloc",       "mshr_no_free",    "secondary_hit"
};

/* ---- MMIO mapped pointer ---- */
static uintptr_t mmio_base = 0;

static inline uint32_t mmio_rd32(uint32_t off)
{
    volatile uint32_t *p = (volatile uint32_t *)(mmio_base + off);
    return *p;
}

static inline void mmio_wr32(uint32_t off, uint32_t val)
{
    volatile uint32_t *p = (volatile uint32_t *)(mmio_base + off);
    *p = val;
    __asm__ volatile ("fence" ::: "memory");
}

/* ---- Commands ---- */

static void cmd_configure(uint32_t interval)
{
    mmio_wr32(TLD_REG_INTERVAL, interval);
    printf("TL+Dir monitor configured:\n");
    printf("  interval = %u cycles\n", interval);
}

static void cmd_start(void)
{
    mmio_wr32(TLD_REG_CTRL, 0x1);
    printf("TL+Dir monitor sampling started.\n");
}

static void cmd_stop(void)
{
    mmio_wr32(TLD_REG_CTRL, 0x0);
    printf("TL+Dir monitor sampling stopped.\n");
}

static void cmd_reset(void)
{
    mmio_wr32(TLD_REG_CTRL, 0x2);
    printf("TL+Dir monitor reset (counters + history cleared).\n");
}

static void cmd_status(void)
{
    uint32_t cfg      = mmio_rd32(L2_REG_CONFIG);
    uint32_t banks    = (cfg >>  0) & 0xFF;
    uint32_t ways     = (cfg >>  8) & 0xFF;
    uint32_t lg_sets  = (cfg >> 16) & 0xFF;
    uint32_t n_sets   = 1U << lg_sets;

    uint32_t ctrl     = mmio_rd32(TLD_REG_CTRL);
    uint32_t interval = mmio_rd32(TLD_REG_INTERVAL);
    uint32_t status   = mmio_rd32(TLD_REG_STATUS);
    uint32_t wcount   = mmio_rd32(TLD_REG_WRITE_COUNT);

    printf("==========================================\n");
    printf("  L2 TileLink+Directory Monitor Status\n");
    printf("==========================================\n");
    printf("Cache config : banks=%u ways=%u sets=%u\n", banks, ways, n_sets);
    printf("Counters     : %d (32-bit, saturating, delta-per-snapshot)\n", TLD_N_COUNTERS);
    printf("Enabled      : %s\n", (ctrl   & 0x1) ? "YES" : "NO");
    printf("Interval     : %u cycles\n", interval);
    printf("History full : %s\n", (status & 0x1) ? "YES" : "NO");
    printf("Snapshots    : %u\n", wcount);
    printf("==========================================\n");
}

static void cmd_dump(uint32_t max_entries)
{
    uint32_t wcount  = mmio_rd32(TLD_REG_WRITE_COUNT);
    uint32_t status  = mmio_rd32(TLD_REG_STATUS);
    uint32_t entries = wcount;

    if (max_entries > 0 && max_entries < entries)
        entries = max_entries;

    printf("==========================================\n");
    printf("  L2 TileLink+Directory Monitor Dump\n");
    printf("==========================================\n");
    printf("Snapshots  : %u (full=%s)\n", wcount, (status & 0x1) ? "yes" : "no");
    printf("Dumping    : %u entries\n", entries);
    printf("------------------------------------------\n");

    /* CSV-style header */
    printf("idx");
    for (int j = 0; j < TLD_N_COUNTERS; j++)
        printf(",%s", TLD_FIELD_NAMES[j]);
    printf("\n");

    for (uint32_t i = 0; i < entries; i++) {
        /* Write the index, then read all readback regs.
         * SyncReadMem has 1-cycle latency; the MMIO read after the
         * index write provides enough delay on a real bus. */
        mmio_wr32(TLD_REG_HIST_IDX, i);
        __asm__ volatile ("fence" ::: "memory");

        printf("%u", i);
        for (int j = 0; j < TLD_N_COUNTERS; j++) {
            uint32_t v = mmio_rd32(TLD_REG_HIST_BASE + j * TLD_HIST_STRIDE);
            printf(",%u", v);
        }
        printf("\n");
    }
    printf("==========================================\n");
}

/* ---- Usage ---- */

static void print_usage(const char *prog)
{
    printf("Usage: %s <command> [args...]\n", prog);
    printf("Commands:\n");
    printf("  configure <interval>\n");
    printf("      Set sampling interval (cycles).\n");
    printf("  start       — Enable sampling (begin recording snapshots).\n");
    printf("  stop        — Disable sampling.\n");
    printf("  reset       — Reset all counters and clear history memory.\n");
    printf("  status      — Print current configuration and snapshot count.\n");
    printf("  dump [N]    — Dump history as CSV (optionally limit to N entries).\n");
    printf("\nExample:\n");
    printf("  %s reset\n", prog);
    printf("  %s configure 500000\n", prog);
    printf("  %s start\n", prog);
    printf("  ... run workload ...\n");
    printf("  %s stop\n", prog);
    printf("  %s dump\n", prog);
}

/* ---- main ---- */

int main(int argc, char *argv[])
{
    if (argc < 2) {
        print_usage(argv[0]);
        return 1;
    }

    const char *cmd = argv[1];

    int fd = open("/dev/mem", O_RDWR | O_SYNC);
    if (fd < 0) {
        perror("Failed to open /dev/mem. Are you running as root?");
        return 1;
    }

    void *map = mmap(NULL, L2_CTRL_MAP_SIZE, PROT_READ | PROT_WRITE,
                     MAP_SHARED, fd, L2_CTRL_PHYS_BASE);
    if (map == MAP_FAILED) {
        perror("mmap failed");
        close(fd);
        return 1;
    }
    mmio_base = (uintptr_t)map;

    int ret = 0;

    if (strcmp(cmd, "configure") == 0) {
        if (argc < 3) {
            printf("Usage: %s configure <interval>\n", argv[0]);
            ret = 1;
        } else {
            uint32_t interval = (uint32_t)strtoul(argv[2], NULL, 0);
            cmd_configure(interval);
        }
    } else if (strcmp(cmd, "start") == 0) {
        cmd_start();
    } else if (strcmp(cmd, "stop") == 0) {
        cmd_stop();
    } else if (strcmp(cmd, "reset") == 0) {
        cmd_reset();
    } else if (strcmp(cmd, "status") == 0) {
        cmd_status();
    } else if (strcmp(cmd, "dump") == 0) {
        uint32_t max_entries = 0; /* 0 = dump all */
        if (argc >= 3)
            max_entries = (uint32_t)strtoul(argv[2], NULL, 0);
        cmd_dump(max_entries);
    } else {
        printf("Unknown command: %s\n", cmd);
        print_usage(argv[0]);
        ret = 1;
    }

    if (munmap(map, L2_CTRL_MAP_SIZE) == -1)
        perror("munmap failed");
    close(fd);

    return ret;
}
