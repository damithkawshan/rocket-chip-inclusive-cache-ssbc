#!/usr/bin/env bash
# Build a bringup-bench benchmark for Chipyard bare-metal Verilator.
# Usage: ./compile_bringup.sh <benchmark> [N]
#   <benchmark>  directory name under bringup-bench (e.g. matmult)
#   [N]          optional: override the benchmark's `#define N` (working-set size)
set -e

BENCH="${1:?Usage: $0 <benchmark> [N]}"
NOVR="${2:-}"

BB="${BRINGUP_BENCH:-/home/damith/Research/repos/benchmarks/bringup-bench}"
SW="$(cd "$(dirname "$0")/.." && pwd)"
CY="$(cd "$SW/../../.." && pwd)"
COMMON="$CY/toolchains/riscv-tools/riscv-tests/benchmarks/common"
ENV="$CY/toolchains/riscv-tools/riscv-tests/env"

OUT="$SW/build"
TMP="$OUT/bringup_$BENCH"
mkdir -p "$TMP"

# Copy the benchmark source so the upstream repo stays pristine; apply the N override there.
cp "$BB/$BENCH"/*.c "$TMP"/
[ -n "$(ls "$BB/$BENCH"/*.h 2>/dev/null)" ] && cp "$BB/$BENCH"/*.h "$TMP"/ || true
if [ -n "$NOVR" ]; then
  sed -i -E "s/^#define[[:space:]]+N[[:space:]]+[0-9]+/#define N $NOVR/" "$TMP"/*.c
  echo "N overridden to $NOVR"
fi

riscv64-unknown-elf-gcc \
  -mcmodel=medany -static -std=gnu99 -O2 \
  -fno-common -fno-builtin -fno-builtin-printf \
  -DTARGET_SPIKE \
  -I "$BB/common" -I "$BB/target" -I "$COMMON" -I "$ENV" \
  -o "$OUT/bringup_${BENCH}.riscv" \
  "$TMP"/*.c \
  "$BB"/common/libmin_*.c \
  "$SW/bringup/libtarg_chipyard.c" \
  "$COMMON/crt.S" "$COMMON/syscalls.c" \
  -nostdlib -nostartfiles -lm -lgcc -T "$COMMON/test.ld"

echo "Built: $OUT/bringup_${BENCH}.riscv"
riscv64-unknown-elf-size "$OUT/bringup_${BENCH}.riscv"
