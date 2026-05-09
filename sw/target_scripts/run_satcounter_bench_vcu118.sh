#!/bin/bash
# run_satcounter_bench_vcu118.sh — Run a benchmark with auto-tuned saturation counter sampling.
#
# Auto-calculates the sampling interval to fill all 512 history slots
# evenly across the run duration. No periodic draining needed.
#
# Usage:
#   ./run_satcounter_bench_vcu118.sh <run_seconds> <workload: 505|520|605> [output_file] [thresh_low] [thresh_high]
#
# Examples:
#   ./run_satcounter_bench_vcu118.sh 3600 520                              # 1 hour OMNeT++, defaults
#   ./run_satcounter_bench_vcu118.sh 3600 505 sat_mcf_1hr.txt 4 9          # 1 hour MCF, custom thresholds
#   ./run_satcounter_bench_vcu118.sh 1800 520 sat_30min.txt                # 30 minutes OMNeT++

# Note: no 'set -e' — kill/wait may fail if the benchmark exits early,
# and we must always reach the dump step.

SAT="./l2_sat_wrapper_riscv"

CLOCK_HZ=50000000       # 50 MHz
HISTORY_DEPTH=1024       # compile-time satHistoryDepth

RUN_SECS="${1:?Usage: $0 <run_seconds> <workload: 505|520|605> [output_file] [thresh_low] [thresh_high]}"
WL_SEL="${2:?Missing workload selection (505, 520 or 605)}"
OUTFILE="${3:-sat_history_${WL_SEL}.txt}"
THRESH_LOW="${4:-4}"
THRESH_HIGH="${5:-9}"

if [ "$WL_SEL" = "505" ]; then
    WORKDIR="505.mcf_r_run_ref"
    WORKLOAD="./mcf_r_base.riscv-64"
    WORK_ARGS="inp.in"
elif [ "$WL_SEL" = "520" ]; then
    WORKDIR="520.omnetpp_r_run_ref"
    WORKLOAD="./omnetpp_r_base.riscv-64"
    WORK_ARGS="-c General -r 0"
elif [ "$WL_SEL" = "605" ]; then
    WORKDIR="605.mcf_s_run_ref"
    WORKLOAD="./mcf_s_base.riscv-64"
    WORK_ARGS="inp.in"
else
    echo "Error: Unknown workload '$WL_SEL'. Choose 505, 520 or 605."
    exit 1
fi

# ---- Calculate interval ----
# interval = (run_seconds * clock_hz) / history_depth
INTERVAL=$(( (RUN_SECS * CLOCK_HZ) / HISTORY_DEPTH ))
SNAP_PERIOD_MS=$(( (INTERVAL * 1000) / CLOCK_HZ ))

# ---- helpers ----
log() { echo "[sat_bench] $(date '+%H:%M:%S') $*"; }

cleanup() {
    log "Caught interrupt signal! Stopping workload..."
    kill "$BENCH_PID" 2>/dev/null || true
    wait "$BENCH_PID" 2>/dev/null || true

    $SAT stop
    log "Counters stopped. Dumping early stats to terminated_dump.dump..."
    $SAT dump > "terminated_dump.dump"
    log "Done. Early output saved to: terminated_dump.dump"
    exit 1
}

trap cleanup SIGINT SIGTERM SIGTSTP

# ---- main ----
log "============================================"
log "  Saturation Counter Benchmark Runner"
log "============================================"
log "Run duration   : ${RUN_SECS}s"
log "Clock          : ${CLOCK_HZ} Hz"
log "History depth  : ${HISTORY_DEPTH}"
log "Interval       : ${INTERVAL} cycles (~${SNAP_PERIOD_MS}ms per snapshot)"
log "Thresholds     : low=${THRESH_LOW} high=${THRESH_HIGH}"
log "Output         : ${OUTFILE}"
log "Workload       : ${WORKDIR}/${WORKLOAD}"
log "============================================"

# Reset, configure, start
$SAT reset
$SAT status
$SAT configure "$INTERVAL" "$THRESH_LOW" "$THRESH_HIGH"
$SAT status
$SAT start
log "Counters started"

# Launch workload
(
    cd "$WORKDIR" || exit 1
    $WORKLOAD $WORK_ARGS > run.out 2> run.err
) &
BENCH_PID=$!
log "Workload started (PID=$BENCH_PID)"

# Wait for run duration
sleep "$RUN_SECS"

# Stop benchmark
log "Time's up. Stopping workload..."
kill "$BENCH_PID" 2>/dev/null || true
wait "$BENCH_PID" 2>/dev/null || true

# Stop and dump
$SAT stop
log "Counters stopped. Dumping..."
$SAT status
$SAT dump > "$OUTFILE"

log "Done. Output saved to: $OUTFILE"
log "Snapshots recorded: $($SAT status 2>/dev/null | grep 'Snapshots' | awk '{print $3}')"
