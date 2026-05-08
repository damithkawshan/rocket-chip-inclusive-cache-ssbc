#!/bin/bash
# Script to compile SSBC L2 Linux CLI tools for RISC-V Linux
#
# Supported apps:
#   l2_perf_wrapper   — L2 cache performance counter readout
#   l2_sat_wrapper    — L2 saturation counter control & histogram dump
#
# Usage:
#   ./build_linux_app.sh l2_perf_wrapper
#   ./build_linux_app.sh l2_sat_wrapper

APP="${1:-x}" #l2_perf_wrapper}"
OUT_DIR="/home/damith/Research/repos/chipyard_performance_eval/chipyard/generators/rocket-chip-inclusive-cache/sw/targeted_tests/src"
CHIPYARD_DIR="/home/damith/Research/repos/chipyard_performance_eval/chipyard"

echo "Setting up Chipyard environment..."
cd $CHIPYARD_DIR
source env.sh

echo "Building $APP for RISC-V Linux..."
cd $OUT_DIR

# Remove any old binary
rm -f ${APP}_riscv

# Compile the wrapper natively for RISC-V Linux
riscv64-unknown-linux-gnu-gcc -O2 -Wall -static -I../ssbc_tests/platform \
  ${APP}.c -o ${APP}_riscv

if [ $? -eq 0 ]; then
  echo "Successfully compiled ${APP}_riscv"
  echo "Stripping binary..."
  riscv64-unknown-linux-gnu-strip ${APP}_riscv
  ls -lh ${APP}_riscv
  echo ""
  echo "Generated file is located at:"
  echo "$OUT_DIR/${APP}_riscv"
else
  echo "Compilation failed for $APP."
  exit 1
fi

