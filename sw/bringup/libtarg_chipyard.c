/*
 * bringup-bench target shim for Chipyard bare-metal (HTIF via riscv-tests syscalls.c).
 * Implements the four interfaces libmin needs: putc, success, fail, sbrk.
 */
#include "libtarg.h"
#include "sbc_mmio.h"

extern int  putchar(int ch);
extern int  printf(const char *, ...);
extern void exit(int code) __attribute__((noreturn));

void libtarg_putc(char c) { putchar((int)c); }

static inline unsigned long rd_cycle(void) {
  unsigned long c; asm volatile("rdcycle %0" : "=r"(c)); return c;
}

/* Cycle count at the first libmin call, so [CYCLES] excludes crt/boot. Set in libtarg_putc's
   first use is unreliable (some benchmarks print nothing), so anchor it in the exit path only:
   report total cycles since reset, which is what the SBC-on vs SBC-off delta needs anyway. */

/* Every bringup benchmark exits through here, so this is the one place that makes the L2
   counters visible without touching upstream benchmark source. Format matches what
   sw/scripts/sbc_stats.py already parses. */
static void sbc_dump_counters(void)
{
  printf("[CYCLES] total=%lu\n", rd_cycle());
  printf("[SBC-COUNTERS] mig=%lu att=%lu abo=%lu secHits=%lu secMiss=%lu "
         "secWrite=%lu secProbe=%lu dispRel=%lu dispDrop=%lu secC=%lu secPerm=%lu "
         "homeBranch=%lu parked=%lu L2_Accesses=%lu L2_Hits=%lu\n",
         (unsigned long)sbc_rd(SBC_MIGRATIONS), (unsigned long)sbc_rd(SBC_ATTEMPTED),
         (unsigned long)sbc_rd(SBC_ABORTED),    (unsigned long)sbc_rd(SBC_SECHITS),
         (unsigned long)sbc_rd(SBC_SECMISS),    (unsigned long)sbc_rd(SBC_SECWRITE),
         (unsigned long)sbc_rd(SBC_SECPROBE),   (unsigned long)sbc_rd(SBC_DISPRELEASE),
         (unsigned long)sbc_rd(SBC_DISPDROP),   (unsigned long)sbc_rd(SBC_SECC),
         (unsigned long)sbc_rd(SBC_SECPERM),    (unsigned long)sbc_rd(SBC_HOMEBRANCH),
         (unsigned long)sbc_rd(SBC_PARKED),     (unsigned long)sbc_rd(SBC_L2_ACCESSES),
         (unsigned long)sbc_rd(SBC_L2_HITS));
}

__attribute__((noreturn)) void libtarg_success(void) { sbc_dump_counters(); exit(0); }

__attribute__((noreturn)) void libtarg_fail(int code) { sbc_dump_counters(); exit(code ? code : 1); }

/* Static heap. 64KB: several bringup benchmarks (graph-tests, longdiv, huff-encode,
   checkers) malloc; 8KB exhausted it. Untouched pages never enter the cache, so the
   larger arena costs nothing in the measurement. */
#define MAX_HEAP (64 * 1024)
static unsigned char __heap[MAX_HEAP] __attribute__((aligned(16)));
/* Initialised to an address so it lands in .data: crt.S does not zero .bss and the sim
   randomises DRAM, so a 0-initialised counter here would start as garbage. */
static unsigned char *__heap_cur = __heap;

void *libtarg_sbrk(size_t inc)
{
  if (__heap_cur + inc > __heap + MAX_HEAP) return (void *)0;
  void *p = __heap_cur;
  __heap_cur += inc;
  return p;
}
