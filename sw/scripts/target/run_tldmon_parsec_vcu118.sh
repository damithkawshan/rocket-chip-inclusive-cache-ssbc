#!/bin/sh
# run_tldmon_parsec_vcu118.sh -- Run a PARSEC binary with TLDir monitor sampling.
#
# Usage:
#   ./run_tldmon_parsec_vcu118.sh parsec.config
#
# The config file is a shell key=value file.

TLDMON="./l2_tldmon_wrapper_riscv"
CLOCK_HZ=50000000
HISTORY_DEPTH=1024

log() { echo "[tldmon_parsec] $(date '+%H:%M:%S') $*"; }

extract_tldmon_stats() {
    dump_file="$1"
    stats_file="$2"

    dumped_rows="$(grep -c '^[0-9][0-9]*,' "$dump_file" 2>/dev/null || echo 0)"
    first_snap="$(grep -m1 '^[0-9][0-9]*,' "$dump_file" 2>/dev/null | cut -d',' -f1)"
    last_snap="$(grep '^[0-9][0-9]*,' "$dump_file" 2>/dev/null | tail -n1 | cut -d',' -f1)"

    [ -z "$first_snap" ] && first_snap="N/A"
    [ -z "$last_snap" ] && last_snap="N/A"

    {
        echo "# TLDir monitor extracted stats"
        echo "dump_file=$dump_file"
        echo "generated_at=$(date '+%Y-%m-%d %H:%M:%S')"
        awk '/^#/ {
            gsub(/^# */, "", $0)
            print
        }' "$dump_file"
        echo "dumped_rows=$dumped_rows"
        echo "first_snap_idx=$first_snap"
        echo "last_snap_idx=$last_snap"
    } > "$stats_file"
}

cleanup() {
    log "Caught interrupt signal. Stopping workload and monitor..."
    kill "$BENCH_PID" 2>/dev/null || true
    wait "$BENCH_PID" 2>/dev/null || true

    $TLDMON stop 2>/dev/null || true
    $TLDMON dump > "terminated_tldmon_parsec.dump" 2>/dev/null || true
    extract_tldmon_stats "terminated_tldmon_parsec.dump" "terminated_tldmon_parsec_stats.txt"
    log "Early dump saved: terminated_tldmon_parsec.dump"
    log "Early stats saved: terminated_tldmon_parsec_stats.txt"
    exit 1
}

trap cleanup SIGINT SIGTERM SIGTSTP

CONFIG_FILE="${1:?Usage: $0 <parsec.config>}"
[ -f "$CONFIG_FILE" ] || { echo "Config file not found: $CONFIG_FILE"; exit 1; }

case "$CONFIG_FILE" in
    */*) ;;
    *) CONFIG_FILE="./$CONFIG_FILE" ;;
esac

# shellcheck source=/dev/null
. "$CONFIG_FILE"

: "${BIN:?BIN not set in config}"
: "${RUN_SECS:?RUN_SECS not set in config}"

TLD_THRESH_LO="${TLD_THRESH_LO:-3}"
TLD_THRESH_HI="${TLD_THRESH_HI:-6}"
CPU_AFF="${CPU_AFF:-1}"
SAMPLE_DELAY_SECS="${SAMPLE_DELAY_SECS:-0}"
SAMPLE_DURATION_SECS="${SAMPLE_DURATION_SECS:-$(( RUN_SECS - SAMPLE_DELAY_SECS ))}"

if [ "$SAMPLE_DELAY_SECS" -lt 0 ]; then
    echo "Error: SAMPLE_DELAY_SECS must be >= 0"; exit 1
fi
if [ "$SAMPLE_DURATION_SECS" -le 0 ]; then
    echo "Error: SAMPLE_DURATION_SECS must be > 0"; exit 1
fi
if [ "$(( SAMPLE_DELAY_SECS + SAMPLE_DURATION_SECS ))" -gt "$RUN_SECS" ]; then
    echo "Error: SAMPLE_DELAY_SECS + SAMPLE_DURATION_SECS cannot exceed RUN_SECS"; exit 1
fi

if [ -z "${WORKDIR:-}" ]; then
    WORKDIR="$(dirname "$BIN")"
fi

if [ -z "${OUTFILE:-}" ]; then
    BIN_BASE="$(basename "$BIN")"
    OUTFILE="tlmon_parsec_${BIN_BASE}_${RUN_SECS}_${TLD_THRESH_LO}_${TLD_THRESH_HI}_d${SAMPLE_DELAY_SECS}_w${SAMPLE_DURATION_SECS}.dump"
fi
STATS_OUTFILE="${OUTFILE%.dump}_stats.txt"

INTERVAL=$(( (SAMPLE_DURATION_SECS * CLOCK_HZ) / HISTORY_DEPTH ))
SNAP_PERIOD_MS=$(( (INTERVAL * 1000) / CLOCK_HZ ))

set -- "$BIN"
[ -n "${THREADS:-}" ] && set -- "$@" "$THREADS"
[ -n "${FRAMES:-}" ] && set -- "$@" "$FRAMES"
[ -n "${INPUT_FILE:-}" ] && set -- "$@" "$INPUT_FILE"
[ -n "${OUTPUT_FILE:-}" ] && set -- "$@" "$OUTPUT_FILE"
# EXTRA_ARGS is intentionally split by shell for convenience in config files.
[ -n "${EXTRA_ARGS:-}" ] && set -- "$@" $EXTRA_ARGS

log "============================================"
log "  TLDir Monitor PARSEC Runner"
log "============================================"
log "Config file    : ${CONFIG_FILE}"
log "Workdir        : ${WORKDIR}"
log "Binary         : ${BIN}"
log "CPU affinity   : ${CPU_AFF}"
log "Run duration   : ${RUN_SECS}s"
log "Sample delay   : ${SAMPLE_DELAY_SECS}s"
log "Sample window  : ${SAMPLE_DURATION_SECS}s"
log "Interval       : ${INTERVAL} cycles (~${SNAP_PERIOD_MS}ms per snapshot)"
log "TLD thresholds : lo=${TLD_THRESH_LO} hi=${TLD_THRESH_HI}"
log "Raw dump       : ${OUTFILE}"
log "Stats file     : ${STATS_OUTFILE}"
log "============================================"

$TLDMON reset
$TLDMON configure "$INTERVAL" "$TLD_THRESH_LO" "$TLD_THRESH_HI"
$TLDMON status

(
    cd "$WORKDIR" || exit 1
    busybox taskset -c "$CPU_AFF" "$@" > parsec.out 2> parsec.err
) &
BENCH_PID=$!
log "PARSEC workload started (PID=${BENCH_PID})"

if [ "$SAMPLE_DELAY_SECS" -gt 0 ]; then
    log "Waiting ${SAMPLE_DELAY_SECS}s before starting TLDMON..."
    sleep "$SAMPLE_DELAY_SECS"
fi

$TLDMON start
log "TLDMON started"
sleep "$SAMPLE_DURATION_SECS"

$TLDMON stop
$TLDMON dump > "$OUTFILE"
extract_tldmon_stats "$OUTFILE" "$STATS_OUTFILE"

log "TLDMON stopped and dumped"
log "Raw dump saved : $OUTFILE"
log "Stats saved    : $STATS_OUTFILE"
log "Snapshots      : $($TLDMON status 2>/dev/null | grep 'Snapshots' | awk '{print $3}')"

REMAINING=$(( RUN_SECS - SAMPLE_DELAY_SECS - SAMPLE_DURATION_SECS ))
if [ "$REMAINING" -gt 0 ]; then
    log "Waiting ${REMAINING}s before stopping PARSEC workload..."
    sleep "$REMAINING"
fi

kill "$BENCH_PID" 2>/dev/null || true
wait "$BENCH_PID" 2>/dev/null || true
log "PARSEC workload stopped"
