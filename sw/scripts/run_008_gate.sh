#!/usr/bin/env bash
# run_008_gate.sh <cN> - task 008 gate: every policy mode on the SAME simulator build, per config.
#   plru    -DL2_POLICY=1          (V2, V3, V6)
#   random  no flag                (V1: binary identical to the 007-b71-c3 one)
#   random0 -DL2_POLICY=0          (V4: same binary layout as plru, one data byte apart; NoSbc only -
#                                   on the SBC config this binary stops at bug B8-1)
# The first run rebuilds (SBC_CLEAN=1); every later run reuses the build (SBC_CLEAN=0).
set -uo pipefail
C="${1:?usage: $0 c1|c2}"
RUN="$(dirname "$0")/run_sbc.sh"
clean=1
run() {  # run <cfg> <mode> <tests>
  local cfg=$1 mode=$2 tests=$3 flags=""
  [ "$mode" = plru ] && flags="-DL2_POLICY=1"
  [ "$mode" = random0 ] && flags="-DL2_POLICY=0"
  echo "######## 008-$C-$mode on $cfg (SBC_CLEAN=$clean, EXTRA_CFLAGS='$flags') $(date)"
  SBC_CONFIGS="$cfg" SBC_TESTS="$tests" SBC_LABEL="008-$C-$mode" SBC_CLEAN=$clean \
    EXTRA_CFLAGS="$flags" "$RUN" || echo "######## run_sbc.sh FAILED ($mode, $cfg)"
  clean=0
}
BOTH="sbc_migrate_switch_test migration_stress_test"
run VerilatorRocket8KL116KL2Config      plru    "$BOTH"
run VerilatorRocket8KL116KL2Config      random  "$BOTH"
run VerilatorRocket8KL116KL2NoSbcConfig plru    "$BOTH"
run VerilatorRocket8KL116KL2NoSbcConfig random  "$BOTH"
run VerilatorRocket8KL116KL2NoSbcConfig random0 migration_stress_test
echo "######## 008-$C gate finished $(date)"
