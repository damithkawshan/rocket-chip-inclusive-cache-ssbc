/*
 * l2_stream_stress.c — L2 cache streaming stress test (Linux, RISC-V).
 *
 * Stresses a 256 KB / 16-way L2 cache by streaming reads and writes through
 * a buffer that is significantly larger than the cache.  Every pass touches
 * every cache line at least once, forcing continuous eviction and refill.
 *
 * Usage:
 *   ./l2_stream_stress [buf_mb [passes]]
 *     buf_mb  — working-set size in MiB (default: 8)
 *     passes  — number of full read+write sweeps (default: 10)
 *
 * Build:
 *   riscv64-unknown-linux-gnu-gcc -O2 -o l2_stream_stress l2_stream_stress.c
 */

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <time.h>

/* ---- Cache geometry (256 KB, 16-way, 64-byte lines) ---- */
#define L2_SIZE_BYTES   (256 * 1024)
#define L2_WAYS         16
#define CACHE_LINE_B    64

/* ---- Defaults ---- */
#define DEFAULT_BUF_MB  8
#define DEFAULT_PASSES  10

/* Wall-clock helper */
static double now_sec(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec * 1e-9;
}

/* ------------------------------------------------------------------ */
/* Streaming kernels                                                    */
/* ------------------------------------------------------------------ */

/* Sequential write: fills every element with its index. */
static void stream_write(volatile uint64_t *buf, size_t n_words)
{
    for (size_t i = 0; i < n_words; i++)
        buf[i] = (uint64_t)i;
}

/* Sequential read: accumulates a checksum to prevent dead-code removal. */
static uint64_t stream_read(volatile uint64_t *buf, size_t n_words)
{
    uint64_t sum = 0;
    for (size_t i = 0; i < n_words; i++)
        sum += buf[i];
    return sum;
}

/* ------------------------------------------------------------------ */
/* Main                                                                 */
/* ------------------------------------------------------------------ */
int main(int argc, char *argv[])
{
    int buf_mb = DEFAULT_BUF_MB;
    int passes = DEFAULT_PASSES;

    if (argc > 1) buf_mb = atoi(argv[1]);
    if (argc > 2) passes = atoi(argv[2]);

    if (buf_mb <= 0 || passes <= 0) {
        fprintf(stderr, "Usage: %s [buf_mb] [passes]\n", argv[0]);
        return 1;
    }

    size_t buf_bytes = (size_t)buf_mb * 1024 * 1024;
    size_t n_words   = buf_bytes / sizeof(uint64_t);

    printf("L2 stream stress\n");
    printf("  L2 cache    : %d KB, %d ways, %d-byte lines\n",
           L2_SIZE_BYTES / 1024, L2_WAYS, CACHE_LINE_B);
    printf("  Buffer      : %d MiB  (%zu x uint64_t)  — %.1fx L2 size\n",
           buf_mb, n_words, (double)buf_bytes / L2_SIZE_BYTES);
    printf("  Passes      : %d\n\n", passes);

    volatile uint64_t *buf = (volatile uint64_t *)malloc(buf_bytes);
    if (!buf) {
        perror("malloc");
        return 1;
    }

    /* Warm up: write once to fault in all pages before timing. */
    stream_write(buf, n_words);

    uint64_t checksum = 0;
    double   t_start  = now_sec();

    for (int p = 0; p < passes; p++) {
        stream_write(buf, n_words);
        checksum += stream_read(buf, n_words);
    }

    double t_total = now_sec() - t_start;

    /* Each pass = 1 write sweep + 1 read sweep over buf_bytes. */
    double bytes_transferred = (double)passes * 2.0 * buf_bytes;
    double bw_gbps = bytes_transferred / t_total / 1e9;

    printf("Results\n");
    printf("  Passes completed : %d\n", passes);
    printf("  Wall time        : %.3f s\n", t_total);
    printf("  Bandwidth        : %.2f GB/s\n", bw_gbps);
    printf("  Checksum         : 0x%016lx  (verify non-zero)\n",
           (unsigned long)checksum);

    free((void *)buf);
    return 0;
}
