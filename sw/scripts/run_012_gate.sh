#!/usr/bin/env bash
# run_012_gate.sh <cN> - task 012 gate.
#
# BOTH policies, both configs, on the SAME simulator build per config:
#   plru    -DL2_POLICY=1   the mode C2's destination write-back is gated on
#   random  no flag         MUST be unchanged - C2 reads the LIVE L2_Replacement register, so a
#                           random run is a true control. Task 009's F1 gated the same feature on the
#                           COMPILE-TIME flag, which silently changed random mode too and destroyed
#                           the only control we had. Keeping random in this gate is how that stays fixed.
#
# The random runs are flag-free so their binaries match the 008/007 gate binaries byte for byte
# (007 F4: a -D changes the layout, which changes the traffic, which changes every counter).
set -uo pipefail
C="${1:?usage: $0 c1|c2}"
HERE="$(cd "$(dirname "$0")" && pwd)"
RUN="$HERE/run_sbc.sh"
LOGS="$HERE/../verilator_logs"
BOTH="sbc_migrate_switch_test migration_stress_test"
clean=1
run() {  # run <cfg> <mode>
  local cfg=$1 mode=$2 flags=""
  [ "$mode" = plru ] && flags="-DL2_POLICY=1"
  echo "######## 012-$C-$mode on $cfg (SBC_CLEAN=$clean, EXTRA_CFLAGS='$flags') $(date)"
  SBC_CONFIGS="$cfg" SBC_TESTS="$BOTH" SBC_LABEL="012-$C-$mode" SBC_CLEAN=$clean \
    EXTRA_CFLAGS="$flags" "$RUN" || echo "######## run_sbc.sh FAILED ($mode, $cfg)"
  clean=0
}
run VerilatorRocket8KL116KL2Config      plru
run VerilatorRocket8KL116KL2Config      random
run VerilatorRocket8KL116KL2NoSbcConfig plru
run VerilatorRocket8KL116KL2NoSbcConfig random
echo "######## V3 replay $(date)"
python3 "$HERE/plru_replay.py" "$LOGS"/*_012-$C-plru/ || true
echo "######## 012-$C gate finished $(date)"
