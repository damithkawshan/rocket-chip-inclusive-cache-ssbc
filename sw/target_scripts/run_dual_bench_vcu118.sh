#!/bin/bash
# run_dual_bench_vcu118.sh — Run multiple benchmarks in parallel
# with auto-tuned saturation counter sampling.
#
# Usage:
#   ./run_dual_bench_vcu118.sh <run_seconds> [output_file] [thresh_low] [thresh_high]
#
# Note: no 'set -e' — kill/wait may fail if the benchmark exits early,
# and we must always reach the dump step.

SAT="./l2_sat_wrapper_riscv"

# Workload 1: OMNeT++
WORKDIR1="520.omnetpp_r_run_ref"
WORKLOAD1="./omnetpp_r_base.riscv-64"
WORK_ARGS1="-c General -r 0"

# Workload 2: MCF
WORKDIR2="505.mcf_r_run_ref"
WORKLOAD2="./mcf_r_base.riscv-64"
WORK_ARGS2="inp.in"

CLOCK_HZ=50000000       # 50 MHz
HISTORY_DEPTH=512       # compile-time satHistoryDepth

RUN_SECS="${1:?Usage: $0 <run_seconds> [output_file] [thresh_low] [thresh_high]}"
OUTFILE="${2:-sat_history.txt}"
THRESH_LOW="${3:-4}"
THRESH_HIGH="${4:-9}"

# ---- Calculate interval ----
INTERVAL=$(( (RUN_SECS * CLOCK_HZ) / HISTORY_DEPTH ))
SNAP_PERIOD_MS=$(( (INTERVAL * 1000) / CLOCK_HZ ))

# ---- helpers ----
log() { echo "[sat_bench] $(date '+%H:%M:%S') $*"; }

cleanup() {
    log "Caught interrupt signal! Stopping workloads..."
    kill "$BENCH_PID1" "$BENCH_PID2" 2>/dev/null || true
    wait "$BENCH_PID1" "$BENCH_PID2" 2>/dev/null || true

    $SAT stop
    log "Counters stopped. Dumping early stats to terminated_dump.dump..."
    $SAT dump > "terminated_dump.dump"
    log "Done. Early output saved to: terminated_dump.dump"
    exit 1
}

trap cleanup SIGINT SIGTERM SIGTSTP

# ---- main ----
log "============================================"
log "  Saturation Counter Multi-Benchmark Runner "
log "============================================"
log "Run duration   : ${RUN_SECS}s"
log "Clock          : ${CLOCK_HZ} Hz"
log "History depth  : ${HISTORY_DEPTH}"
log "Interval       : ${INTERVAL} cycles (~${SNAP_PERIOD_MS}ms per snapshot)"
log "Thresholds     : low=${THRESH_LOW} high=${THRESH_HIGH}"
log "Output         : ${OUTFILE}"
log "Workload 1     : ${WORKDIR1}/${WORKLOAD1}"
log "Workload 2     : ${WORKDIR2}/${WORKLOAD2}"
log "============================================"

# Reset, configure, start
$SAT reset
$SAT configure "$INTERVAL" "$THRESH_LOW" "$THRESH_HIGH"
$SAT start
log "Counters started"

# Launch workload 1
(
    cd "$WORKDIR1" || exit 1
    $WORKLOAD1 $WORK_ARGS1 > omnetpp.out 2> omnetpp.err
) &
BENCH_PID1=$!

# Launch workload 2
(
    cd "$WORKDIR2" || exit 1
    $WORKLOAD2 $WORK_ARGS2 > inp.out 2>> inp.er
) &
BENCH_PID2=$!

log "Workloads started (PID1=$BENCH_PID1, PID2=$BENCH_PID2)"

# Wait for run duration
sleep "$RUN_SECS"

# Stop benchmarks
log "Time's up. Stopping workloads..."
kill "$BENCH_PID1" "$BENCH_PID2" 2>/dev/null || true
wait "$BENCH_PID1" "$BENCH_PID2" 2>/dev/null || true

# Stop and dump
$SAT stop
log "Counters stopped. Dumping..."
$SAT status
$SAT dump > "$OUTFILE"

log "Done. Output saved to: $OUTFILE"
log "Snapshots recorded: $($SAT status 2>/dev/null | grep 'Snapshots' | awk '{print $3}')"
