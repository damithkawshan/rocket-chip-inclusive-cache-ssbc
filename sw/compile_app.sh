#!/usr/bin/env bash
# Usage: ./compile_app.sh <app_name>   (no .c or .riscv suffix needed)
# Must be run from the Chipyard root after 'source env.sh'
set -e

APP="${1:?Usage: $0 <app_name>}"
SW="$(cd "$(dirname "$0")" && pwd)"
CY="$(cd "$SW/../../.." && pwd)"
COMMON="$CY/toolchains/riscv-tools/riscv-tests/benchmarks/common"
ENV="$CY/toolchains/riscv-tools/riscv-tests/env"

source "$CY/env.sh"

mkdir -p "$SW/build"

# clear previous build artifacts
rm -f "$SW/build/${APP}.riscv"

riscv64-unknown-elf-gcc \
  -mcmodel=medany -static -std=gnu99 -O2 \
  -fno-common -fno-builtin -fno-builtin-printf \
  -I "$COMMON" -I "$ENV" \
  -o "$SW/build/${APP}.riscv" \
  "$SW/${APP}.c" \
  "$COMMON/crt.S" "$COMMON/syscalls.c" \
  -nostdlib -nostartfiles -lm -lgcc -T "$COMMON/test.ld"

echo "Built: $SW/build/${APP}.riscv"
