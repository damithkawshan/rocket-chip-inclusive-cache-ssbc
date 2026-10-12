/* sat_snap.c - one-pass snapshot of every set's saturation counter (SBC_SetSel 0x300 / SBC_SetSat 0x308).
 *
 * A shell loop of devmem calls cannot do this: every exec touches memory and moves the very counters
 * it is reading (measured 2026-10-01: sets 0-1 read 7 and 13 after a run, ~0 a few minutes of devmem
 * later). This reads all sets in one tight loop, optionally the instant a child command exits.
 *
 *   ./sat_snap                 snapshot now
 *   ./sat_snap -- <cmd...>     run cmd, snapshot as soon as it exits (returns the child's exit code)
 *
 * Prints a histogram, then the full map: one base-36 digit per set, 64 sets per line.
 * Build: riscv64-unknown-linux-gnu-gcc -O2 -static -o sat_snap sw/sat_snap.c
 */
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include "sbc_mmio.h"

#define SETSEL_OFF 0x300
#define SETSAT_OFF 0x308

static uint8_t v[1 << 16];

int main(int argc, char **argv) {
    int cmd = 0;
    if (argc > 1) {
        if (strcmp(argv[1], "--") || argc < 3) { fprintf(stderr, "usage: sat_snap [-- cmd args...]\n"); return 2; }
        cmd = 2;
    }
    int fd = open("/dev/mem", O_RDWR | O_SYNC);
    if (fd < 0) { perror("open /dev/mem"); return 1; }
    void *m = mmap(NULL, 0x1000, PROT_READ | PROT_WRITE, MAP_SHARED, fd, L2_CTRL_BASE);
    if (m == MAP_FAILED) { perror("mmap"); return 1; }
    volatile uint64_t *base = (volatile uint64_t *)m;

    uint64_t cfg = base[0];
    unsigned ways = (unsigned)((cfg >> 8) & 0xff), lgsets = (unsigned)((cfg >> 16) & 0xff);
    if (ways == 0 || lgsets > 16) { fprintf(stderr, "sat_snap: implausible config word 0x%llx\n", (unsigned long long)cfg); return 1; }
    unsigned sets = 1u << lgsets, tmax = 2 * ways - 1, tlo = ways;

    int rc = 0;
    if (cmd) {
        pid_t p = fork();
        if (p == 0) { execvp(argv[cmd], &argv[cmd]); perror("exec"); _exit(127); }
        int st; waitpid(p, &st, 0);
        rc = WIFEXITED(st) ? WEXITSTATUS(st) : 1;
    }
    for (unsigned s = 0; s < sets; s++) {
        base[SETSEL_OFF / 8] = s;
        __sync_synchronize();
        v[s] = (uint8_t)base[SETSAT_OFF / 8];
    }

    unsigned hist[256] = {0}, hot = 0, cold = 0;
    unsigned long sum = 0;
    for (unsigned s = 0; s < sets; s++) {
        hist[v[s]]++; sum += v[s];
        if (v[s] >= tmax) hot++;
        if (v[s] < tlo) cold++;
    }
    printf("[SAT-SNAP] sets=%u ways=%u max=%u hot(>=%u)=%u cold(<%u)=%u mean=%.2f\n",
           sets, ways, tmax, tmax, hot, tlo, cold, (double)sum / sets);
    printf("[SAT-HIST]");
    for (unsigned k = 0; k <= tmax && k < 256; k++) printf(" %u:%u", k, hist[k]);
    printf("\n");
    const char *dig = "0123456789abcdefghijklmnopqrstuvwxyz";
    for (unsigned s = 0; s < sets; s += 64) {
        printf("[SAT-MAP %04u]", s);
        putchar(' ');
        for (unsigned j = s; j < s + 64 && j < sets; j++) putchar(v[j] < 36 ? dig[v[j]] : '?');
        putchar('\n');
    }
    return rc;
}
