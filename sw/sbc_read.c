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
 *   ./sbc_read --migrate=on    allow new migrations to start (SBC_MigrateEnable, 0x3C0)
 *   ./sbc_read --migrate=off   stop new migrations from starting
 *   ./sbc_read --reset-all     zero ALL SBC state (SBC_Reset, 0x358). Refuses while lines are
 *                              parked, because that would orphan them. --force overrides.
 *
 * The one-bitstream A/B, which is the reason the switch exists:
 *   ./sbc_read --migrate=off                    then run the workload   <- plain-L2 half
 *   ./sbc_read --reset-all --migrate=on         then run the workload   <- SBC half
 * Run the OFF half FIRST. It parks nothing, which is exactly what makes --reset-all legal before
 * the ON half. The other order needs a reboot: nothing in software can un-park a line.
 *
 * The migrate switch (006) defaults OFF at reset, so a board that has just booted has parked
 * nothing. It gates only the START of a new migration: lines already parked keep being searched,
 * served, written back and evicted either way, so flipping it off mid-run strands nothing.
 * Every dump prints the switch state, because a counter reading nobody can attribute to a switch
 * position is uninterpretable later.
 *
 * Two ways to get a clean window:
 *   --zero writes SBC_StatsReset (0x3B8), which zeroes ONLY the event/hit counters in hardware and
 *     never touches sat/AT/DSS/parkCount/nParked, so the SBC flow keeps running. One read after the
 *     workload then gives exact window counts with no subtraction and no wrap ambiguity.
 *   The bare delta form needs no reset at all: it subtracts two reads (the counters are 64-bit,
 *     so no wrap in any realistic window; the width-mask below still handles it).
 * SBC_Reset (0x358) is the bigger hammer and is only safe when NOTHING is parked - it wipes the
 * Association Table, which is what tells a parked line where it belongs. --reset-all checks
 * SBC_Parked and refuses rather than trusting the caller. For a routine counter window inside a
 * live SBC run, use --zero, never --reset-all.
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
#define SBC_STATUS_OFF     0x320UL   /* R: bit0 = SBC built into this bitstream */
#define SBC_MIGRATEENABLE_OFF 0x3C0UL /* R/W: 1 = a new migration may start. Default 0 at reset. */
#define SBC_RESET_OFF      0x358UL   /* W: zero ALL SBC state - UNSAFE while lines are parked */
#define SBC_PARKED_OFF     0x3A0UL   /* R: live displaced lines currently resident */

/* name, offset, hardware counter width. Every SBC counter is a 64-bit RegInit in
 * SetBalanceUnit.scala (widened 32->64 in the 006 counter change), same as L2_Accesses / L2_Hits.
 * The width still drives the delta mask so a rollover wraps, not goes hugely negative -- though a
 * 64-bit counter does not wrap in any realistic window. */
static const struct { const char *name; unsigned off; unsigned bits; } REGS[] = {
    { "migrations",  0x328, 64 }, { "secHits",     0x330, 64 }, { "secMiss",     0x338, 64 },
    { "attempted",   0x348, 64 }, { "aborted",     0x350, 64 }, { "secPerm",     0x360, 64 },
    { "secWrite",    0x368, 64 }, { "secProbe",    0x370, 64 }, { "dispRelease", 0x378, 64 },
    { "dispDrop",    0x380, 64 }, { "secC",        0x388, 64 }, { "homeBranch",  0x390, 64 },
    { "parked",      0x3A0, 64 }, { "L2_Accesses", 0x3A8, 64 }, { "L2_Hits",     0x3B0, 64 },
    /* 006 main-memory traffic. Appended, never inserted: show() indexes this table positionally. */
    { "memReads",    0x3C8, 64 }, { "memWrites",   0x3D0, 64 }, { "memUpgrades", 0x3D8, 64 },
    { "memRelClean", 0x3E0, 64 }, { "L2_Cycles",   0x3E8, 64 },
};
/* Positional indices into REGS, used by show(). Keep in step with the table above. */
#define I_MIGRATIONS 0
#define I_SECHITS    1
#define I_PARKED     12
#define I_ACCESSES   13
#define I_HITS       14
#define I_MEMREADS   15
#define I_MEMWRITES  16
#define I_CYCLES     19
#define NREG (sizeof(REGS)/sizeof(REGS[0]))

static volatile uint64_t *base;

static uint64_t mask_of(unsigned i) {
    return REGS[i].bits >= 64 ? ~(uint64_t)0 : ((uint64_t)1 << REGS[i].bits) - 1;
}

static void snap(uint64_t *v) {
    for (unsigned i = 0; i < NREG; i++)
        v[i] = base[REGS[i].off / 8] & mask_of(i);
}

/* SBC_MigrateEnable exists in every build - Control.scala is not gated by enableSetBalancing - but
 * only a build with SBC compiled in has a SetBalanceUnit to read it. Without that, a write succeeds
 * and read-back agrees while changing nothing, so report the switch as n/a rather than OFF. */
static int sbc_built(void)     { return (int)(base[SBC_STATUS_OFF / 8] & 1); }
static int migrate_state(void) { return (int)(base[SBC_MIGRATEENABLE_OFF / 8] & 1); }

/* Block size from the config word at 0x000, so "bytes moved" cannot drift from the hardware.
 * RegFieldGroup packs its Seq from the LSB up: banks[7:0], ways[15:8], lgSets[23:16],
 * lgBlockBytes[31:24]. If that ever stops being true the extracted value stops being a sane block
 * size, so sanity-check it rather than printing a wrong byte count. */
static unsigned block_bytes(void) {
    unsigned lg = (unsigned)((base[0] >> 24) & 0xff);
    if (lg < 4 || lg > 9) return 64;    /* not a plausible 16B..512B block - fall back */
    return 1u << lg;
}

static void show(const char *tag, uint64_t *v) {
    printf("[%s]", tag);
    for (unsigned i = 0; i < NREG; i++)
        printf(" %s=%llu", REGS[i].name, (unsigned long long)v[i]);
    printf("\n");
    printf("  migrate          : %s\n",
           !sbc_built() ? "n/a (SBC not built into this bitstream)" :
           migrate_state() ? "ON" : "OFF");
    uint64_t acc = v[I_ACCESSES], hit = v[I_HITS], sh = v[I_SECHITS], mig = v[I_MIGRATIONS];
    if (acc) {
        printf("  primary hit rate : %llu/%llu = %.2f%%\n",
               (unsigned long long)hit, (unsigned long long)acc, 100.0 * hit / acc);
        printf("  total hit rate   : %llu/%llu = %.2f%%  (primary + secondary)\n",
               (unsigned long long)(hit + sh), (unsigned long long)acc, 100.0 * (hit + sh) / acc);
    }
    if (mig)
        printf("  hits per park    : %llu/%llu = %.2f   (break-even is about 1.0)\n",
               (unsigned long long)sh, (unsigned long long)mig, (double)sh / mig);

    /* The headline. Reads and writes stay separate above; this is the one number that has no
     * denominator to argue about - a count of things that physically happened at the memory port. */
    uint64_t rd = v[I_MEMREADS], wr = v[I_MEMWRITES], cyc = v[I_CYCLES];
    if (rd || wr) {
        printf("  MEMORY ACCESSES  : %llu  (reads %llu + writes %llu)\n",
               (unsigned long long)(rd + wr), (unsigned long long)rd, (unsigned long long)wr);
        printf("  bytes moved      : %llu  (x%u B/block)\n",
               (unsigned long long)(rd + wr) * block_bytes(), block_bytes());
    }
    if (cyc) {
        printf("  L2 cycles        : %llu\n", (unsigned long long)cyc);
        if (rd || wr)
            printf("  mem accesses/kcyc: %.3f\n", 1000.0 * (double)(rd + wr) / (double)cyc);
    }
}

int main(int argc, char **argv) {
    int fd = open("/dev/mem", O_RDWR | O_SYNC);
    if (fd < 0) { perror("open /dev/mem"); return 1; }
    void *m = mmap(NULL, MAP_LEN, PROT_READ | PROT_WRITE, MAP_SHARED, fd, L2_CTRL_BASE);
    if (m == MAP_FAILED) { perror("mmap"); return 1; }
    base = (volatile uint64_t *)m;

    int cmd = 0, zero = 0, migsw = -1, reset_all = 0, force = 0;
    /* migsw: -1 = leave the switch alone, 0 = off, 1 = on */
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--zero")) zero = 1;
        else if (!strcmp(argv[i], "--migrate=on"))  migsw = 1;
        else if (!strcmp(argv[i], "--migrate=off")) migsw = 0;
        else if (!strcmp(argv[i], "--reset-all")) reset_all = 1;
        else if (!strcmp(argv[i], "--force")) force = 1;
        else if (!strcmp(argv[i], "--")) { cmd = i + 1; break; }
    }

    /* --reset-all: SBC_Reset (0x358) wipes saturation, DSS, the AT and the counters together. Use it
     * to drop heat the cache accumulated before the measurement started - otherwise the instant the
     * switch goes on, a burst of migrations fires from boot-time heat rather than from the benchmark.
     *
     * The AT is the ONLY record of where a parked line's home set is, so doing this while lines are
     * parked orphans them. Refuse rather than corrupt. It is safe exactly when nothing is parked,
     * which is what running the migrate-OFF half of an A/B first guarantees - and this refusal is
     * what catches the mistake if the halves are ever run the other way round. */
    if (reset_all) {
        uint64_t parked = base[SBC_PARKED_OFF / 8];
        if (parked && !force) {
            fprintf(stderr,
                    "sbc_read: refusing --reset-all: %llu line(s) still parked.\n"
                    "  Resetting now orphans them - the AT is their only home-set record.\n"
                    "  Reboot for a clean slate, run the migrate-off half first, or pass --force.\n",
                    (unsigned long long)parked);
            return 1;
        }
        if (parked)
            fprintf(stderr, "sbc_read: --force: resetting with %llu line(s) parked - "
                            "those lines are now orphaned\n", (unsigned long long)parked);
        base[SBC_RESET_OFF / 8] = 1;
    }

    /* Set the switch after --reset-all and before --zero: the reset clears the slate, the switch
     * takes its position, then the counter window opens with both already settled. */
    if (migsw >= 0) {
        if (!sbc_built())
            fprintf(stderr, "sbc_read: warning: SBC is not built into this bitstream - "
                            "--migrate=%s writes a register nothing reads\n", migsw ? "on" : "off");
        base[SBC_MIGRATEENABLE_OFF / 8] = (uint64_t)migsw;
        /* Read-back is not optional: a benchmark run on the wrong side of this switch produces
         * numbers that look fine and mean nothing. */
        if (migrate_state() != migsw) {
            fprintf(stderr, "sbc_read: SBC_MigrateEnable read back %d after writing %d - aborting\n",
                    migrate_state(), migsw);
            return 1;
        }
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
     * subtract the two snapshots and mask each counter to its width so a wrap stays correct. */
    for (unsigned i = 0; i < NREG; i++) d[i] = zero ? b[i] : ((b[i] - a[i]) & mask_of(i));
    d[I_PARKED] = b[I_PARKED];          /* parked is a level, not a count - report the final value */
    show(zero ? "SBC-WINDOW" : "SBC-DELTA", d);
    return WIFEXITED(st) ? WEXITSTATUS(st) : 1;
}
