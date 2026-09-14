#!/bin/sh
# run_sbc_window.sh - take a fixed-duration SBC/L2 counter window while a long benchmark runs.
#
# Modelled directly on TL_signal_analysis's run_tldmon_bench_vcu118.sh: launch the workload,
# wait out a warm-up delay, open the counter window, sample for a fixed time, then kill the
# workload. This is a fixed-TIME observation window, so the benchmark need not finish - handy for
# watching counters move during a long run. It is NOT valid for an on/off comparison: a fixed time
# can simply mean less work got done. For an A/B, use `sbc_read --zero -- <cmd>` instead, so both
# halves do the same work.
#
# Usage:
#   ./run_sbc_window.sh omnetpp            # 520.omnetpp_r, ref input
#   ./run_sbc_window.sh omnetpp_test       # small input, its own ini default
#   ./run_sbc_window.sh omnetpp_train
#   WINDOW_SECS=1800 ./run_sbc_window.sh omnetpp
#   ./run_sbc_window.sh -- <workdir> <binary> [args...]     # anything else
#
# Env:
#   DELAY_SECS   warm-up before the window opens   (default 60)
#   WINDOW_SECS  length of the measured window     (default 600)
#   CPU_AFF      core to pin to                    (default 0; this config has ONE core)
#   WL_BASE      dir the benchmarks were copied into (default test_dir)
#   SBC_READ     path to sbc_read                  (default ./sbc_read)
#
# No 'set -e': kill/wait may fail if the benchmark exits early, and we must always reach the
# final counter read.

WL_BASE="${WL_BASE:-test_dir}"   # where the benchmark dirs were copied to on the board
DELAY_SECS="${DELAY_SECS:-60}"
WINDOW_SECS="${WINDOW_SECS:-600}"
CPU_AFF="${CPU_AFF:-0}"
SBC_READ="${SBC_READ:-./sbc_read}"

log() { echo "[sbc_window] $(date '+%H:%M:%S') $*"; }

# ---- workload table (same shape, and same omnetpp args, as the TLD branch) ----
case "$1" in
    # `ref` carries --sim-time-limit=1s, overriding its omnetpp.ini (2.25s).
    # `train` and `test` keep their own ini defaults (0.15s / 0.003s).
    omnetpp|omnetpp_ref)
        WDIR="$WL_BASE/520.omnetpp_r_run_ref";   WBIN="./omnetpp_r_base.riscv-64"; WARGS="-c General -r 0 --sim-time-limit=1s" ;;
    omnetpp_train)
        WDIR="$WL_BASE/520.omnetpp_r_run_train"; WBIN="./omnetpp_r_base.riscv-64"; WARGS="-c General -r 0" ;;
    omnetpp_test)
        WDIR="$WL_BASE/520.omnetpp_r_run_test";  WBIN="./omnetpp_r_base.riscv-64"; WARGS="-c General -r 0" ;;
    mcf)
        WDIR="$WL_BASE/505.mcf_r_run_ref";       WBIN="./mcf_r_base.riscv-64";     WARGS="inp.in" ;;
    --)
        shift
        WDIR="$1"; WBIN="$2"; shift 2; WARGS="$*" ;;
    *)
        echo "usage: $0 {omnetpp|omnetpp_train|omnetpp_test|mcf} | -- <workdir> <binary> [args]"
        exit 2 ;;
esac

[ -d "$WDIR" ] || { echo "no such workdir: $WDIR"; exit 1; }
[ -x "$SBC_READ" ] || { echo "not executable: $SBC_READ"; exit 1; }

cleanup() {
    log "interrupted - stopping workload and taking a final reading"
    kill "$BENCH_PID" 2>/dev/null
    wait "$BENCH_PID" 2>/dev/null
    $SBC_READ
    exit 1
}
trap cleanup INT TERM

log "============================================"
log "workload   : $WDIR/$WBIN $WARGS"
log "pinned to  : cpu $CPU_AFF"
log "warm-up    : ${DELAY_SECS}s   window: ${WINDOW_SECS}s"
log "============================================"

( cd "$WDIR" && busybox taskset -c "$CPU_AFF" $WBIN $WARGS > wl.out 2> wl.err ) &
BENCH_PID=$!
log "started (pid $BENCH_PID)"

if [ "$DELAY_SECS" -gt 0 ]; then
    log "warm-up ${DELAY_SECS}s (skips startup transient - omnetpp parses its config first)"
    sleep "$DELAY_SECS"
fi

# Did it die during warm-up? A missing input file would show up here.
if ! kill -0 "$BENCH_PID" 2>/dev/null; then
    log "!! workload exited during warm-up - check $WDIR/wl.err"
    tail -5 "$WDIR/wl.err" 2>/dev/null
    exit 1
fi

log "opening counter window"
$SBC_READ --zero > /dev/null          # zero the event counters, start of window
sleep "$WINDOW_SECS"

# A short input (test/train) can finish INSIDE the window. The counters would then include
# idle time and understate the real rates - so say so loudly rather than report a clean-looking
# but diluted number. `ref` exists precisely to avoid this.
if ! kill -0 "$BENCH_PID" 2>/dev/null; then
    log "!! WARNING: workload finished BEFORE the window closed."
    log "!! The counters below include idle time. Use a longer input (omnetpp = ref) or a"
    log "!! shorter WINDOW_SECS. Do not compare this against a window that stayed busy."
fi

log "window closed - reading counters"
# With --zero having run, the absolute reading IS the window (see sbc_read.c: no wrap arithmetic).
$SBC_READ

log "stopping workload"
kill "$BENCH_PID" 2>/dev/null
wait "$BENCH_PID" 2>/dev/null
log "done. workload stdout/stderr: $WDIR/wl.out, $WDIR/wl.err"
