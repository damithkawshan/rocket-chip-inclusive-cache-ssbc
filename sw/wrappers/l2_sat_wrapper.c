/*
 * l2_sat_wrapper.c — CLI tool for L2 saturation counter control & readout.
 *
 * Accesses the InclusiveCacheControl MMIO registers (0x300-0x350) via /dev/mem.
 *
 * Commands:
 *   configure <interval> <thresh_low> <thresh_high>
 *   start                      — enable sampling
 *   stop                       — disable sampling
 *   reset                      — reset all counters + history
 *   status                     — print current status
 *   dump [max_entries]         — read & print all history snapshots
 *
 * Usage:
 *   ./l2_sat_wrapper_riscv configure 500000 2 5
 *   ./l2_sat_wrapper_riscv start
 *   ... run workload ...
 *   ./l2_sat_wrapper_riscv stop
 *   ./l2_sat_wrapper_riscv dump
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

/* ---- Saturation counter register offsets (from Control.scala) ---- */
#define SAT_REG_CTRL        0x300U  /* bit0=enable, bit1=reset(auto-clear) */
#define SAT_REG_INTERVAL    0x308U  /* sampling interval in cycles */
#define SAT_REG_THRESH_LOW  0x310U  /* counter <= this → LOW */
#define SAT_REG_THRESH_HIGH 0x318U  /* counter >= this → HIGH */
#define SAT_REG_STATUS      0x320U  /* bit0=full */
#define SAT_REG_WRITE_COUNT 0x328U  /* # snapshots recorded */
#define SAT_REG_HIST_IDX    0x330U  /* index for history read */
#define SAT_REG_HIST_IDLE   0x338U  /* IDLE count at histIdx (counter==0) */
#define SAT_REG_HIST_LOW    0x340U  /* LOW count at histIdx (0<counter<=threshLow) */
#define SAT_REG_HIST_MED    0x348U  /* MED count at histIdx */
#define SAT_REG_HIST_HIGH   0x350U  /* HIGH count at histIdx */

/* ---- Cache config register (existing) ---- */
#define L2_REG_CONFIG       0x000U

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

static void cmd_configure(uint32_t interval, uint32_t thresh_low, uint32_t thresh_high)
{
    mmio_wr32(SAT_REG_INTERVAL,    interval);
    mmio_wr32(SAT_REG_THRESH_LOW,  thresh_low);
    mmio_wr32(SAT_REG_THRESH_HIGH, thresh_high);
    printf("Saturation counter configured:\n");
    printf("  interval    = %u cycles\n", interval);
    printf("  thresh_low  = %u\n", thresh_low);
    printf("  thresh_high = %u\n", thresh_high);
}

static void cmd_start(void)
{
    /* Set bit 0 (enable) */
    mmio_wr32(SAT_REG_CTRL, 0x1);
    printf("Saturation counter sampling started.\n");
}

static void cmd_stop(void)
{
    /* Clear enable bit */
    mmio_wr32(SAT_REG_CTRL, 0x0);
    printf("Saturation counter sampling stopped.\n");
}

static void cmd_reset(void)
{
    /* Set bit 1 (reset pulse) — auto-clears in HW */
    mmio_wr32(SAT_REG_CTRL, 0x2);
    printf("Saturation counter reset (counters + history cleared).\n");
}

static void cmd_status(void)
{
    uint32_t cfg        = mmio_rd32(L2_REG_CONFIG);
    uint32_t banks      = (cfg >>  0) & 0xFF;
    uint32_t ways       = (cfg >>  8) & 0xFF;
    uint32_t lg_sets    = (cfg >> 16) & 0xFF;
    uint32_t n_sets     = 1U << lg_sets;

    uint32_t ctrl       = mmio_rd32(SAT_REG_CTRL);
    uint32_t interval   = mmio_rd32(SAT_REG_INTERVAL);
    uint32_t thresh_low = mmio_rd32(SAT_REG_THRESH_LOW);
    uint32_t thresh_high= mmio_rd32(SAT_REG_THRESH_HIGH);
    uint32_t status     = mmio_rd32(SAT_REG_STATUS);
    uint32_t wcount     = mmio_rd32(SAT_REG_WRITE_COUNT);

    printf("==========================================\n");
    printf("  L2 Saturation Counter Status\n");
    printf("==========================================\n");
    printf("Cache config : banks=%u ways=%u sets=%u\n", banks, ways, n_sets);
    printf("Counter range: [0, %u]  (2*ways - 1)\n", 2 * ways - 1);
    printf("Enabled      : %s\n",  (ctrl & 0x1) ? "YES" : "NO");
    printf("Interval     : %u cycles\n", interval);
    printf("Thresh LOW   : <= %u\n", thresh_low);
    printf("Thresh HIGH  : >= %u\n", thresh_high);
    printf("History full : %s\n",  (status & 0x1) ? "YES" : "NO");
    printf("Snapshots    : %u\n",  wcount);
    printf("==========================================\n");
}

static void cmd_dump(uint32_t max_entries)
{
    uint32_t cfg     = mmio_rd32(L2_REG_CONFIG);
    uint32_t lg_sets = (cfg >> 16) & 0xFF;
    uint32_t n_sets  = 1U << lg_sets;

    uint32_t wcount  = mmio_rd32(SAT_REG_WRITE_COUNT);
    uint32_t status  = mmio_rd32(SAT_REG_STATUS);
    uint32_t entries = wcount;

    if (max_entries > 0 && max_entries < entries)
        entries = max_entries;

    printf("==========================================\n");
    printf("  L2 Saturation Counter History Dump\n");
    printf("==========================================\n");
    printf("Total sets     : %u\n", n_sets);
    printf("Snapshots      : %u (full=%s)\n", wcount, (status & 0x1) ? "yes" : "no");
    printf("Dumping        : %u entries\n", entries);
    printf("------------------------------------------\n");
    printf("%6s  %6s  %6s  %6s  %6s\n", "idx", "idle", "low", "med", "high");
    printf("------  ------  ------  ------  ------\n");

    for (uint32_t i = 0; i < entries; i++) {
        /* Write the index, then read the four bins.
         * SyncReadMem has 1-cycle latency; the MMIO read after the
         * index write provides enough delay on a real bus. */
        mmio_wr32(SAT_REG_HIST_IDX, i);

        /* A small fence + re-read ensures the SyncReadMem output has
         * propagated through the TileLink register read path. */
        __asm__ volatile ("fence" ::: "memory");

        uint32_t idle = mmio_rd32(SAT_REG_HIST_IDLE);
        uint32_t low  = mmio_rd32(SAT_REG_HIST_LOW);
        uint32_t med  = mmio_rd32(SAT_REG_HIST_MED);
        uint32_t high = mmio_rd32(SAT_REG_HIST_HIGH);

        printf("%6u  %6u  %6u  %6u  %6u\n", i, idle, low, med, high);
    }
    printf("==========================================\n");
}

/* ---- Usage ---- */

static void print_usage(const char *prog)
{
    printf("Usage: %s <command> [args...]\n", prog);
    printf("Commands:\n");
    printf("  configure <interval> <thresh_low> <thresh_high>\n");
    printf("      Set sampling interval (cycles) and histogram thresholds.\n");
    printf("  start       — Enable sampling (begin recording snapshots).\n");
    printf("  stop        — Disable sampling.\n");
    printf("  reset       — Reset all counters and clear history memory.\n");
    printf("  status      — Print current configuration and snapshot count.\n");
    printf("  dump [N]    — Dump history (optionally limit to N entries).\n");
    printf("\nExample:\n");
    printf("  %s reset\n", prog);
    printf("  %s configure 500000 2 5\n", prog);
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

    /* Open /dev/mem for MMIO access */
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
        if (argc < 5) {
            printf("Usage: %s configure <interval> <thresh_low> <thresh_high>\n", argv[0]);
            ret = 1;
        } else {
            uint32_t interval   = (uint32_t)strtoul(argv[2], NULL, 0);
            uint32_t thresh_low = (uint32_t)strtoul(argv[3], NULL, 0);
            uint32_t thresh_high= (uint32_t)strtoul(argv[4], NULL, 0);
            cmd_configure(interval, thresh_low, thresh_high);
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

    /* Clean up */
    if (munmap(map, L2_CTRL_MAP_SIZE) == -1)
        perror("munmap failed");
    close(fd);

    return ret;
}
