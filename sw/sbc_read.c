/* sbc_read.c - read the L2 / SBC counters from Linux userspace on the FPGA.
 *
 * There is no driver, so nothing appears under /proc or /sys. The control block is plain MMIO,
 * so we mmap it through /dev/mem and read 64-bit registers directly.
 *
 * Offsets must match design/craft/inclusivecache/src/Control.scala and sw/sbc_mmio.h.
 *
 * Build (from the chipyard root, after sourcing env.sh):
 *   riscv64-unknown-linux-gnu-gcc -O2 -static -o sbc_read sw/sbc_read.c
 *
 * Use:
 *   ./sbc_read                 print the counters now
 *   ./sbc_read -- <cmd...>     snapshot, run cmd, snapshot again, print the DELTA
 *
 * The delta form is the one you want: the counters are free-running since power-on, so a bare
 * reading includes the whole Linux boot. Do NOT use SBC_Reset to zero them - it is documented
 * unsafe while lines are parked.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/wait.h>

#define L2_CTRL_BASE 0x2010000UL
#define MAP_LEN      0x1000UL

/* name, offset */
static const struct { const char *name; unsigned off; } REGS[] = {
    { "migrations",  0x328 }, { "secHits",     0x330 }, { "secMiss",     0x338 },
    { "attempted",   0x348 }, { "aborted",     0x350 }, { "secPerm",     0x360 },
    { "secWrite",    0x368 }, { "secProbe",    0x370 }, { "dispRelease", 0x378 },
    { "dispDrop",    0x380 }, { "secC",        0x388 }, { "homeBranch",  0x390 },
    { "parked",      0x3A0 }, { "L2_Accesses", 0x3A8 }, { "L2_Hits",     0x3B0 },
};
#define NREG (sizeof(REGS)/sizeof(REGS[0]))

static volatile uint64_t *base;

static void snap(uint64_t *v) {
    for (unsigned i = 0; i < NREG; i++)
        v[i] = base[REGS[i].off / 8];
}

static void show(const char *tag, uint64_t *v) {
    printf("[%s]", tag);
    for (unsigned i = 0; i < NREG; i++)
        printf(" %s=%llu", REGS[i].name, (unsigned long long)v[i]);
    printf("\n");
    uint64_t acc = v[13], hit = v[14], sh = v[1], mig = v[0];
    if (acc) {
        printf("  primary hit rate : %llu/%llu = %.2f%%\n",
               (unsigned long long)hit, (unsigned long long)acc, 100.0 * hit / acc);
        printf("  total hit rate   : %llu/%llu = %.2f%%  (primary + secondary)\n",
               (unsigned long long)(hit + sh), (unsigned long long)acc, 100.0 * (hit + sh) / acc);
    }
    if (mig)
        printf("  hits per park    : %llu/%llu = %.2f   (break-even is about 1.0)\n",
               (unsigned long long)sh, (unsigned long long)mig, (double)sh / mig);
}

int main(int argc, char **argv) {
    int fd = open("/dev/mem", O_RDONLY | O_SYNC);
    if (fd < 0) { perror("open /dev/mem"); return 1; }
    void *m = mmap(NULL, MAP_LEN, PROT_READ, MAP_SHARED, fd, L2_CTRL_BASE);
    if (m == MAP_FAILED) { perror("mmap"); return 1; }
    base = (volatile uint64_t *)m;

    int cmd = 0;
    for (int i = 1; i < argc; i++) if (!strcmp(argv[i], "--")) { cmd = i + 1; break; }

    if (!cmd) { uint64_t v[NREG]; snap(v); show("SBC-COUNTERS", v); return 0; }

    uint64_t a[NREG], b[NREG], d[NREG];
    snap(a);
    pid_t p = fork();
    if (p == 0) { execvp(argv[cmd], &argv[cmd]); perror("exec"); _exit(127); }
    int st; waitpid(p, &st, 0);
    snap(b);
    for (unsigned i = 0; i < NREG; i++) d[i] = b[i] - a[i];
    d[12] = b[12];                      /* parked is a level, not a count - report the final value */
    show("SBC-DELTA", d);
    return WIFEXITED(st) ? WEXITSTATUS(st) : 1;
}
