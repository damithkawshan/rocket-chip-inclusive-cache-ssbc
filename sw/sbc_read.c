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
 *   ./sbc_read --zero          pulse SBC_StatsReset (0x3B8), then print the (now near-zero) counters
 *   ./sbc_read --zero -- <cmd> zero, run cmd, print ABSOLUTE counts = exact window, single read
 *   ./sbc_read -- <cmd...>     snapshot, run cmd, snapshot again, print the DELTA (no reset needed)
 *
 * Two ways to get a clean window:
 *   --zero writes SBC_StatsReset (0x3B8), which zeroes ONLY the event/hit counters in hardware and
 *     never touches sat/AT/DSS/parkCount/nParked, so the SBC flow keeps running. One read after the
 *     workload then gives exact window counts with no subtraction and no 32-bit wrap ambiguity.
 *   The bare delta form needs no reset at all: it subtracts two reads (32-bit wrap handled below).
 * Do NOT use SBC_Reset (0x358): it wipes the Association Table while lines are still parked, which
 * orphans them (TODO(phase3) in SetBalanceUnit.scala). SBC_StatsReset is the safe counter reset.
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
#define SBC_STATSRESET_OFF 0x3B8UL   /* W: zero ONLY the event/hit counters (see sbc_mmio.h) */

/* name, offset, hardware counter width. Every SBC counter is a 32-bit RegInit in
 * SetBalanceUnit.scala; only L2_Accesses / L2_Hits are 64-bit. The width matters for the delta:
 * a 32-bit counter that rolls over mid-window must wrap, not go hugely negative. */
static const struct { const char *name; unsigned off; unsigned bits; } REGS[] = {
    { "migrations",  0x328, 32 }, { "secHits",     0x330, 32 }, { "secMiss",     0x338, 32 },
    { "attempted",   0x348, 32 }, { "aborted",     0x350, 32 }, { "secPerm",     0x360, 32 },
    { "secWrite",    0x368, 32 }, { "secProbe",    0x370, 32 }, { "dispRelease", 0x378, 32 },
    { "dispDrop",    0x380, 32 }, { "secC",        0x388, 32 }, { "homeBranch",  0x390, 32 },
    { "parked",      0x3A0, 32 }, { "L2_Accesses", 0x3A8, 64 }, { "L2_Hits",     0x3B0, 64 },
};
#define NREG (sizeof(REGS)/sizeof(REGS[0]))

static volatile uint64_t *base;

static uint64_t mask_of(unsigned i) {
    return REGS[i].bits >= 64 ? ~(uint64_t)0 : ((uint64_t)1 << REGS[i].bits) - 1;
}

static void snap(uint64_t *v) {
    for (unsigned i = 0; i < NREG; i++)
        v[i] = base[REGS[i].off / 8] & mask_of(i);
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
    int fd = open("/dev/mem", O_RDWR | O_SYNC);
    if (fd < 0) { perror("open /dev/mem"); return 1; }
    void *m = mmap(NULL, MAP_LEN, PROT_READ | PROT_WRITE, MAP_SHARED, fd, L2_CTRL_BASE);
    if (m == MAP_FAILED) { perror("mmap"); return 1; }
    base = (volatile uint64_t *)m;

    int cmd = 0, zero = 0;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--zero")) zero = 1;
        else if (!strcmp(argv[i], "--")) { cmd = i + 1; break; }
    }

    /* --zero: pulse the hardware counter-only reset. Safe for the SBC flow - it touches nothing but
     * the event/hit counters. Any written value works; the hardware makes it a 1-cycle pulse. */
    if (zero) base[SBC_STATSRESET_OFF / 8] = 1;

    if (!cmd) { uint64_t v[NREG]; snap(v); show(zero ? "SBC-ZEROED" : "SBC-COUNTERS", v); return 0; }

    uint64_t a[NREG], b[NREG], d[NREG];
    snap(a);                            /* ~0 already if --zero was given */
    pid_t p = fork();
    if (p == 0) { execvp(argv[cmd], &argv[cmd]); perror("exec"); _exit(127); }
    int st; waitpid(p, &st, 0);
    snap(b);
    /* With --zero the window is just the absolute post-run reading (no wrap possible); without it,
     * subtract the two snapshots and mask each counter to its width so a 32-bit wrap stays correct. */
    for (unsigned i = 0; i < NREG; i++) d[i] = zero ? b[i] : ((b[i] - a[i]) & mask_of(i));
    d[12] = b[12];                      /* parked is a level, not a count - report the final value */
    show(zero ? "SBC-WINDOW" : "SBC-DELTA", d);
    return WIFEXITED(st) ? WEXITSTATUS(st) : 1;
}
