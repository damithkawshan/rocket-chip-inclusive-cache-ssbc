#!/bin/bash
# run_tldmon_bench_vcu118.sh — Run multiple benchmarks in parallel
# with TLDir monitor sampling.
#
# Usage:
#   ./run_tldmon_bench_vcu118.sh <wl1> <wl2> <run_seconds> [output_file]
#
# Available workloads (wl1, wl2):
#   1 or omnetpp : 520.omnetpp_r_run_ref
#   2 or mcf_r   : 505.mcf_r_run_ref
#   3 or mcf_s   : 605.mcf_s_run_ref
#
# Note: no 'set -e' — kill/wait may fail if the benchmark exits early,
# and we must always reach the dump step.

TLDMON="./l2_tldmon_wrapper_riscv"

setup_workload() {
    local wl_id=$1
    local prefix=$2
    local wdir
    local wload
    local wargs

    case "$wl_id" in
        1|omnetpp)
            wdir="520.omnetpp_r_run_ref"
            wload="./omnetpp_r_base.riscv-64"
            wargs="-c General -r 0"
            ;;
        2|mcf_r)
            wdir="505.mcf_r_run_ref"
            wload="./mcf_r_base.riscv-64"
            wargs="inp.in"
            ;;
        3|mcf_s)
            wdir="605.mcf_s_run_ref"
            wload="./mcf_s_base.riscv-64"
            wargs="inp.in"
            ;;
        *)
            echo "Unknown workload ID: $wl_id. Valid options: 1 (omnetpp), 2 (mcf_r), 3 (mcf_s)"
            exit 1
            ;;
    esac

    if [ "$prefix" = "1" ]; then
        WORKDIR1="$wdir"
        WORKLOAD1="$wload"
        WORK_ARGS1="$wargs"
    else
        WORKDIR2="$wdir"
        WORKLOAD2="$wload"
        WORK_ARGS2="$wargs"
    fi
}

CLOCK_HZ=50000000       # 50 MHz
HISTORY_DEPTH=1024      # compile-time TLD history depth (approx)

WL1_ID="${1:?Usage: $0 <wl1> <wl2> <run_seconds> [output_file]}"
WL2_ID="${2:?Usage: $0 <wl1> <wl2> <run_seconds> [output_file]}"
RUN_SECS="${3:?Usage: $0 <wl1> <wl2> <run_seconds> [output_file]}"
OUTFILE="${4:-tldmon_history.txt}"

setup_workload "$WL1_ID" 1
setup_workload "$WL2_ID" 2

# ---- Calculate interval ----
INTERVAL=$(( (RUN_SECS * CLOCK_HZ) / HISTORY_DEPTH ))
SNAP_PERIOD_MS=$(( (INTERVAL * 1000) / CLOCK_HZ ))

# ---- helpers ----
log() { echo "[tldmon_bench] $(date '+%H:%M:%S') $*"; }

cleanup() {
    log "Caught interrupt signal! Stopping workloads..."
    kill "$BENCH_PID1" "$BENCH_PID2" 2>/dev/null || true
    wait "$BENCH_PID1" "$BENCH_PID2" 2>/dev/null || true

    $TLDMON stop
    log "Counters stopped. Dumping early stats to terminated_dump.dump..."
    $TLDMON dump > "terminated_dump.dump"
    log "Done. Early output saved to: terminated_dump.dump"
    exit 1
}

trap cleanup SIGINT SIGTERM SIGTSTP

# ---- main ----
log "============================================"
log "  TLDir Monitor Multi-Benchmark Runner "
log "============================================"
log "Run duration   : ${RUN_SECS}s"
log "Clock          : ${CLOCK_HZ} Hz"
log "History depth  : ${HISTORY_DEPTH}"
log "Interval       : ${INTERVAL} cycles (~${SNAP_PERIOD_MS}ms per snapshot)"
log "Output         : ${OUTFILE}"
log "Workload 1     : ${WORKDIR1}/${WORKLOAD1}"
log "Workload 2     : ${WORKDIR2}/${WORKLOAD2}"
log "============================================"

# Reset, configure, start
$TLDMON reset
$TLDMON configure "$INTERVAL"
$TLDMON start
log "Counters started"

# Launch workload 1
(
    cd "$WORKDIR1" || exit 1
    $WORKLOAD1 $WORK_ARGS1 > wl1.out 2> wl1.err
) &
BENCH_PID1=$!

# Launch workload 2
(
    cd "$WORKDIR2" || exit 1
    $WORKLOAD2 $WORK_ARGS2 > wl2.out 2> wl2.err
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
$TLDMON stop
log "Counters stopped. Dumping..."
$TLDMON status
$TLDMON dump > "$OUTFILE"

log "Done. Output saved to: $OUTFILE"
log "Snapshots recorded: $($TLDMON status 2>/dev/null | grep 'Snapshots' | awk '{print $3}')"
