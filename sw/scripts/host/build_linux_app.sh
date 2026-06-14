#!/bin/bash
# Script to compile SSBC L2 Linux CLI tools for RISC-V Linux.
#
# Wrapper sources live in sw/wrappers/; the produced *_riscv binaries are
# written back to that directory.
#
# Supported apps:
#   l2_perf_wrapper   — L2 cache performance counter readout
#   l2_sat_wrapper    — L2 saturation counter control & histogram dump
#   l2_tldmon_wrapper — L2 TileLink+Directory event monitor control & history dump
#   l2_stream_stress  — L2 streaming stress workload
#
# Usage:
#   ./build_linux_app.sh l2_tldmon_wrapper
#
# Override the chipyard checkout via the CHIPYARD_DIR env var (default:
# the repo this script lives in, three levels up from sw/scripts/host/).

set -e

APP="${1:-}"
if [ -z "$APP" ]; then
  echo "usage: $0 <app_name>" >&2
  echo "  e.g. $0 l2_tldmon_wrapper" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# sw/scripts/host -> sw/wrappers
OUT_DIR="${SCRIPT_DIR}/../../wrappers"
# generator root = sw/scripts/host/../../..; chipyard root is two more above
CHIPYARD_DIR="${CHIPYARD_DIR:-${SCRIPT_DIR}/../../../../../}"
CHIPYARD_DIR="$(cd "${CHIPYARD_DIR}" && pwd)"

echo "Setting up Chipyard environment from ${CHIPYARD_DIR}..."
cd "${CHIPYARD_DIR}"
# shellcheck disable=SC1091
source env.sh

echo "Building $APP for RISC-V Linux in ${OUT_DIR}..."
cd "${OUT_DIR}"

# Remove any old binary
rm -f "${APP}_riscv"

# Compile the wrapper natively for RISC-V Linux
riscv64-unknown-linux-gnu-gcc -O2 -Wall -static \
  "${APP}.c" -o "${APP}_riscv"

echo "Successfully compiled ${APP}_riscv"
echo "Stripping binary..."
riscv64-unknown-linux-gnu-strip "${APP}_riscv"
ls -lh "${APP}_riscv"
echo
echo "Generated file is located at:"
echo "${OUT_DIR}/${APP}_riscv"

