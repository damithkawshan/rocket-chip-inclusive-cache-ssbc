/*
 * bringup-bench target shim for Chipyard bare-metal (HTIF via riscv-tests syscalls.c).
 * Implements the four interfaces libmin needs: putc, success, fail, sbrk.
 */
#include "libtarg.h"

extern int  putchar(int ch);
extern void exit(int code) __attribute__((noreturn));

void libtarg_putc(char c) { putchar((int)c); }

__attribute__((noreturn)) void libtarg_success(void) { exit(0); }

__attribute__((noreturn)) void libtarg_fail(int code) { exit(code ? code : 1); }

/* Static heap: matmult uses static arrays, but libmin_printf/malloc may want a little. */
#define MAX_HEAP (8 * 1024)
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
