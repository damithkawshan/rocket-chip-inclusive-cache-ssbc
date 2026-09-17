#!/usr/bin/env bash
# run_008_gate.sh <cN> - task 008 gate: PLRU and random on the SAME simulator build, per config.
# The first run rebuilds (SBC_CLEAN=1); every later run reuses the build (SBC_CLEAN=0).
set -uo pipefail
C="${1:?usage: $0 c1|c2}"
RUN="$(dirname "$0")/run_sbc.sh"
TESTS="sbc_migrate_switch_test migration_stress_test"
clean=1
for cfg in VerilatorRocket8KL116KL2Config VerilatorRocket8KL116KL2NoSbcConfig; do
  for mode in plru random; do
    flags=""; [ "$mode" = plru ] && flags="-DL2_POLICY=1"
    echo "######## 008-$C-$mode on $cfg (SBC_CLEAN=$clean, EXTRA_CFLAGS='$flags') $(date)"
    SBC_CONFIGS="$cfg" SBC_TESTS="$TESTS" SBC_LABEL="008-$C-$mode" SBC_CLEAN=$clean \
      EXTRA_CFLAGS="$flags" "$RUN" || echo "######## run_sbc.sh FAILED ($mode, $cfg)"
    clean=0
  done
done
echo "######## 008-$C gate finished $(date)"
