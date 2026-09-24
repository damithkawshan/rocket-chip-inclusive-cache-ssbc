#!/usr/bin/env bash
# run_012_v4.sh - task 012 V4: directed test for a dirty GUEST destination way.
# Builds the SipTest+PLRU simulator, runs sw/dirty_guest_evict_test.c, then counts the events from the log.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LOGS="$HERE/../verilator_logs"
CFG=VerilatorRocket8KL116KL2SipTestPlruConfig
# V4_POLICY=1 (default): PLRU, the mode C2 is gated on. V4_POLICY=0: the random-mode CONTROL - the same
# test must produce zero DST-RELEASE (the gate reads the live L2_Replacement register, not a flag).
POLICY=${V4_POLICY:-1}
if [ "$POLICY" = 1 ]; then LABEL=012-v4; PFLAG="-DL2_POLICY=1"; else LABEL=012-v4-random; PFLAG=""; fi
echo "######## V4 start $(date)"
# Build the test, check its memory layout FIRST (a hot line in set 5/6/7 defeats the test), then simulate.
set +u; source "$HERE/../../../../env.sh"; set -u
EXTRA_CFLAGS="$PFLAG" "$HERE/compile_app.sh" dirty_guest_evict_test || exit 1
"$HERE/check_v4_layout.sh" "$HERE/../build/dirty_guest_evict_test.riscv" || { echo "######## V4 ABORT: bad layout"; exit 1; }
# SBC_CLEAN=0 reuses an existing simulator build (the RTL did not change); pass SBC_CLEAN=1 after an RTL edit.
SBC_CONFIGS="$CFG" SBC_TESTS=dirty_guest_evict_test SBC_LABEL="$LABEL" SBC_CLEAN=${SBC_CLEAN:-0} SBC_SKIP_BUILD=1 \
  SBC_THREADS=${SBC_THREADS:-8} SBC_JOBS=${SBC_JOBS:-12} "$HERE/run_sbc.sh"
echo "######## V4 run rc=$? $(date)"
D="$LOGS/dirty_guest_evict_test_${CFG}_${LABEL}"
echo "=== verdict ==="; grep -h "V4 dirty-guest-evict\|\[V4\] readback\|DATA MISMATCH" "$D"/*.log "$D"/*.out 2>/dev/null | sort -u
echo "=== events (sbc.log) ==="
echo "DST-RELEASE total      : $(grep -c 'DST-RELEASE' "$D/sbc.log")"
echo "DST-RELEASE guest=1    : $(grep 'DST-RELEASE' "$D/sbc.log" | grep -c 'guest=1')"
echo "DST-RELEASE guest=0    : $(grep 'DST-RELEASE' "$D/sbc.log" | grep -c 'guest=0')"
echo "H3-HOLD                : $(grep -c 'H3-HOLD' "$D/sbc.log")"
echo "ABORT-DST              : $(grep -c 'ABORT-DST' "$D/sbc.log")"
echo "--- guest releases (home must equal srcSet, never dstSet):"
grep 'DST-RELEASE' "$D/sbc.log" | grep 'guest=1' | head -12
echo "=== bad signals in the whole output (expect none) ==="
grep -ciE 'Assertion failed|TLMonitor|SBC shadow|panic' "$D"/*.out | head
echo "######## V4 finished $(date)"
