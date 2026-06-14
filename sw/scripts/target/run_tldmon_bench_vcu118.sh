#!/bin/sh
# run_tldmon_bench_vcu118.sh — Run one or two benchmarks in parallel
# with TLDir monitor + saturation counter sampling.
#
# Usage:
#   ./run_tldmon_bench_vcu118.sh <config_file>
#
# The config file is a shell key=value file.  See run_tldmon_bench.conf.example
# for all available parameters and their defaults.
#
# Key parameters:
#   WL1_ID, WL2_ID          : workload IDs (0/none, 1/omnetpp, 2/mcf_r, 3/mcf_s, 4/perlbench, 5/memtest)
#   RUN_SECS                 : total benchmark duration in seconds
#   SAMPLE_DELAY_SECS        : seconds to wait before starting monitors (default: 0)
#   SAMPLE_DURATION_SECS     : how long to record monitor data (default: RUN_SECS - SAMPLE_DELAY_SECS)
#   OUTFILE                  : output filename base (default: tldmon_history.txt)
#   TLD_THRESH_LO/HI         : TLD activity thresholds (default: 3 / 6)
#   CPU_AFF1, CPU_AFF2       : CPU cores for taskset pinning (default: 1, 2)
#
# Example — sample 10 s window starting 30 min into a 1-hour run:
#   SAMPLE_DELAY_SECS=1800  SAMPLE_DURATION_SECS=10  RUN_SECS=3600
#   The interval is computed from SAMPLE_DURATION_SECS so snapshots are fine-grained.
#
# Note: no 'set -e' — kill/wait may fail if the benchmark exits early,
# and we must always reach the dump step.

TLDMON="./l2_tldmon_wrapper_riscv"
SAT="./l2_sat_wrapper_riscv"

setup_workload() {
    local wl_id=$1
    local prefix=$2
    local wdir
    local wload
    local wargs

    case "$wl_id" in
        0|none)
            wdir=""
            wload=""
            wargs=""
            ;;
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
        4 | perlbench)
            wdir="500.perlbench_r_run_train"
            wload="./perlbench_r_base.riscv-64"
            # SPEC2017 500.perlbench_r train: scripts live in workdir, modules in ./lib
            wargs="-I./lib diffmail.pl 4 800 10 17 19 300"
            ;;
        5|memtest)
            wdir="memtest_dir"
            wload="./memtest"
            wargs=""
            ;;
        6|stream)
            wdir="."
            wload="./l2_stream_stress"
            wargs=""
            [ -n "${STREAM_BUF_MB:-}" ] && wargs="$STREAM_BUF_MB"
            [ -n "${STREAM_PASSES:-}" ] && wargs="$wargs $STREAM_PASSES"
            ;;
        *)
            echo "Unknown workload ID: $wl_id. Valid options: 0/none, 1 (omnetpp), 2 (mcf_r), 3 (mcf_s), 4 (perlbench), 5 (memtest), 6/stream (l2_stream_stress)"
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

# ---- Load config file ----
CONFIG_FILE="${1:?Usage: $0 <config_file>}"
[ -f "$CONFIG_FILE" ] || { echo "Config file not found: $CONFIG_FILE"; exit 1; }
# BusyBox sh's '.' searches only $PATH; prefix with ./ if no directory component.
case "$CONFIG_FILE" in
    */*) ;;
    *) CONFIG_FILE="./$CONFIG_FILE" ;;
esac
# shellcheck source=/dev/null
. "$CONFIG_FILE"

# ---- Validate required fields ----
: "${WL1_ID:?WL1_ID not set in config}"
: "${WL2_ID:?WL2_ID not set in config}"
: "${RUN_SECS:?RUN_SECS not set in config}"

# ---- Apply defaults for optional fields ----
# OUTFILE is derived from config params after setup_workload; see below.
TLD_THRESH_LO="${TLD_THRESH_LO:-3}"          # cold if csc < lo
TLD_THRESH_HI="${TLD_THRESH_HI:-6}"          # hot  if csc >= hi
CPU_AFF1="${CPU_AFF1:-1}"                    # CPU core for workload 1
CPU_AFF2="${CPU_AFF2:-2}"                    # CPU core for workload 2
SAMPLE_DELAY_SECS="${SAMPLE_DELAY_SECS:-0}"  # delay before starting monitors
# Default: sample for the remainder of the run after the delay
SAMPLE_DURATION_SECS="${SAMPLE_DURATION_SECS:-$(( RUN_SECS - SAMPLE_DELAY_SECS ))}"

# ---- Sanity checks ----
if [ "$SAMPLE_DELAY_SECS" -lt 0 ]; then
    echo "Error: SAMPLE_DELAY_SECS must be >= 0"; exit 1
fi
if [ "$SAMPLE_DURATION_SECS" -le 0 ]; then
    echo "Error: SAMPLE_DURATION_SECS must be > 0 (SAMPLE_DELAY_SECS=${SAMPLE_DELAY_SECS} >= RUN_SECS=${RUN_SECS}?)"; exit 1
fi
if [ "$(( SAMPLE_DELAY_SECS + SAMPLE_DURATION_SECS ))" -gt "$RUN_SECS" ]; then
    echo "Error: SAMPLE_DELAY_SECS(${SAMPLE_DELAY_SECS}) + SAMPLE_DURATION_SECS(${SAMPLE_DURATION_SECS}) > RUN_SECS(${RUN_SECS})"; exit 1
fi

setup_workload "$WL1_ID" 1
setup_workload "$WL2_ID" 2

# ---- Auto-derive OUTFILE from config params (override by setting OUTFILE in config) ----
if [ -z "${OUTFILE:-}" ]; then
    # Extract leading numeric label from workdir (e.g. "520" from "520.omnetpp_r_run_ref")
    WL1_NUM="${WORKDIR1%%.*}"
    WL1_NUM="${WL1_NUM:-none}"
    if [ -n "$WORKDIR2" ]; then
        WL2_NUM="${WORKDIR2%%.*}"
    else
        WL2_NUM="none"
    fi
    _BASE="tlmon_${WL1_NUM}_${WL2_NUM}_${RUN_SECS}_${TLD_THRESH_LO}_${TLD_THRESH_HI}"
    if [ "$SAMPLE_DELAY_SECS" -gt 0 ]; then
        OUTFILE="${_BASE}_d${SAMPLE_DELAY_SECS}_w${SAMPLE_DURATION_SECS}.txt"
    else
        OUTFILE="${_BASE}.txt"
    fi
fi
SAT_OUTFILE="${OUTFILE%.txt}_sat.txt"

# ---- Calculate interval (based on sample window, not total run) ----
INTERVAL=$(( (SAMPLE_DURATION_SECS * CLOCK_HZ) / HISTORY_DEPTH ))
SNAP_PERIOD_MS=$(( (INTERVAL * 1000) / CLOCK_HZ ))

# ---- helpers ----
log() { echo "[tldmon_bench] $(date '+%H:%M:%S') $*"; }

cleanup() {
    log "Caught interrupt signal! Stopping workloads..."
    kill "$BENCH_PID1" 2>/dev/null || true
    [ -n "$BENCH_PID2" ] && kill "$BENCH_PID2" 2>/dev/null || true
    wait "$BENCH_PID1" 2>/dev/null || true
    [ -n "$BENCH_PID2" ] && wait "$BENCH_PID2" 2>/dev/null || true

    $TLDMON stop 2>/dev/null || true
    $SAT stop 2>/dev/null || true
    log "Counters stopped. Dumping early stats..."
    $TLDMON dump > "terminated_tldmon_dump.dump" 2>/dev/null || true
    $SAT dump > "terminated_sat_dump.dump" 2>/dev/null || true
    log "Done. Early output saved to: terminated_tldmon_dump.dump, terminated_sat_dump.dump"
    exit 1
}

trap cleanup SIGINT SIGTERM SIGTSTP

# ---- main ----
log "============================================"
log "  TLDir + SAT Counter Multi-Benchmark Runner"
log "============================================"
log "Config file    : ${CONFIG_FILE}"
log "Run duration   : ${RUN_SECS}s"
log "Sample delay   : ${SAMPLE_DELAY_SECS}s  (monitors start at t+${SAMPLE_DELAY_SECS}s)"
log "Sample window  : ${SAMPLE_DURATION_SECS}s  (monitors stop at t+$(( SAMPLE_DELAY_SECS + SAMPLE_DURATION_SECS ))s)"
log "Clock          : ${CLOCK_HZ} Hz"
log "History depth  : ${HISTORY_DEPTH}"
log "Interval       : ${INTERVAL} cycles (~${SNAP_PERIOD_MS}ms per snapshot)"
log "TLD thresholds : lo=${TLD_THRESH_LO} hi=${TLD_THRESH_HI}  (idle=0, cold=[1,lo), warm=[lo,hi), hot=[hi,15])"
log "SAT thresholds : lo=${TLD_THRESH_LO} hi=${TLD_THRESH_HI}"
log "TLDMON output  : ${OUTFILE}"
log "SAT output     : ${SAT_OUTFILE}"
log "Workload 1     : ${WORKDIR1}/${WORKLOAD1}  [pinned to CPU ${CPU_AFF1}]"
if [ -n "$WORKLOAD2" ]; then
    log "Workload 2     : ${WORKDIR2}/${WORKLOAD2}  [pinned to CPU ${CPU_AFF2}]"
else
    log "Workload 2     : none (single-workload mode)"
fi
log "============================================"

# Reset, configure, start — both monitors
$TLDMON reset
$TLDMON configure "$INTERVAL" "$TLD_THRESH_LO" "$TLD_THRESH_HI"
$TLDMON status
$SAT reset
$SAT configure "$INTERVAL" "$TLD_THRESH_LO" "$TLD_THRESH_HI"
$SAT status
# Launch workload 1 — pinned to CPU core $CPU_AFF1
(
    cd "$WORKDIR1" || exit 1
    busybox taskset -c "$CPU_AFF1" $WORKLOAD1 $WORK_ARGS1 > wl1.out 2> wl1.err
) &
BENCH_PID1=$!

# Launch workload 2 — pinned to CPU core $CPU_AFF2 (skipped if wl2=none)
BENCH_PID2=""
if [ -n "$WORKLOAD2" ]; then
    (
        cd "$WORKDIR2" || exit 1
        busybox taskset -c "$CPU_AFF2" $WORKLOAD2 $WORK_ARGS2 > wl2.out 2> wl2.err
    ) &
    BENCH_PID2=$!
    log "Workloads started (PID1=$BENCH_PID1 cpu${CPU_AFF1}, PID2=$BENCH_PID2 cpu${CPU_AFF2})"
else
    log "Workload started  (PID1=$BENCH_PID1 cpu${CPU_AFF1}, single-workload mode)"
fi

# ---- Wait for sample delay, then start monitors ----
if [ "$SAMPLE_DELAY_SECS" -gt 0 ]; then
    log "Waiting ${SAMPLE_DELAY_SECS}s before starting monitors (warm-up / delay period)..."
    sleep "$SAMPLE_DELAY_SECS"
    log "Delay elapsed. Starting monitors at t+${SAMPLE_DELAY_SECS}s."
fi

$TLDMON start
$SAT start
log "TLDMON + SAT counters started (sample window: ${SAMPLE_DURATION_SECS}s)"

# ---- Sample for the configured window ----
sleep "$SAMPLE_DURATION_SECS"

# Stop and dump — both monitors
$TLDMON stop
$SAT stop
log "Sample window complete. Counters stopped. Dumping..."
$TLDMON status
$SAT status
$TLDMON dump > "$OUTFILE"
$SAT dump > "$SAT_OUTFILE"
log "Done. TLDMON output saved to: $OUTFILE"
log "Done. SAT output saved to: $SAT_OUTFILE"
log "TLDMON snapshots: $($TLDMON status 2>/dev/null | grep 'Snapshots' | awk '{print $3}')"
log "SAT snapshots: $($SAT status 2>/dev/null | grep 'Snapshots' | awk '{print $3}')"

# ---- Wait out the remainder of the run, then kill workloads ----
REMAINING=$(( RUN_SECS - SAMPLE_DELAY_SECS - SAMPLE_DURATION_SECS ))
if [ "$REMAINING" -gt 0 ]; then
    log "Waiting ${REMAINING}s for remainder of run before stopping workloads..."
    sleep "$REMAINING"
fi

log "Time's up. Stopping workloads..."
kill "$BENCH_PID1" 2>/dev/null || true
[ -n "$BENCH_PID2" ] && kill "$BENCH_PID2" 2>/dev/null || true
wait "$BENCH_PID1" 2>/dev/null || true
[ -n "$BENCH_PID2" ] && wait "$BENCH_PID2" 2>/dev/null || true
log "All workloads stopped."
