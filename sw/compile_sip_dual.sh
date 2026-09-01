#!/usr/bin/env bash
# Build serve_in_place_dual.riscv with a custom crt that RELEASES hart 1 (the stock riscv-tests crt
# parks every hart with id >= 1). Only the `li a1, 1` core-count gate is changed to `li a1, 2`.
set -e
SW="$(cd "$(dirname "$0")" && pwd)"
CY="$(cd "$SW/../../.." && pwd)"
COMMON="$CY/toolchains/riscv-tools/riscv-tests/benchmarks/common"
ENV="$CY/toolchains/riscv-tools/riscv-tests/env"
source "$CY/env.sh"
mkdir -p "$SW/build"

# patched startup: allow 2 harts
sed 's/li a1, 1/li a1, 2/' "$COMMON/crt.S" > "$SW/build/crt_dual.S"

rm -f "$SW/build/serve_in_place_dual.riscv"
riscv64-unknown-elf-gcc \
  -mcmodel=medany -static -std=gnu99 -O2 \
  -fno-common -fno-builtin -fno-builtin-printf \
  -I "$COMMON" -I "$ENV" -I "$SW" \
  -o "$SW/build/serve_in_place_dual.riscv" \
  "$SW/serve_in_place_dual.c" \
  "$SW/build/crt_dual.S" "$COMMON/syscalls.c" \
  -nostdlib -nostartfiles -lm -lgcc -T "$COMMON/test.ld"
echo "Built: $SW/build/serve_in_place_dual.riscv"
