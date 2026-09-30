#!/usr/bin/env bash
# run_013_gate.sh <label> - task 013 gate: stress + switch tests on the SBC and NoSbc configs, PLRU.
# One simulator build per config (SBC_CLEAN=1 on the first config only). Label: base|c0|c1|c2|c3.
set -uo pipefail
L="${1:?usage: $0 base|c0|c1|c2|c3}"
HERE="$(cd "$(dirname "$0")" && pwd)"
RUN="$HERE/run_sbc.sh"
BOTH="sbc_migrate_switch_test migration_stress_test"
clean=1
for cfg in VerilatorRocket8KL116KL2Config VerilatorRocket8KL116KL2NoSbcConfig; do
  echo "######## 013-$L on $cfg (SBC_CLEAN=$clean) $(date)"
  SBC_CONFIGS="$cfg" SBC_TESTS="$BOTH" SBC_LABEL="013-$L" SBC_CLEAN=$clean \
    EXTRA_CFLAGS="-DL2_POLICY=1" "$RUN" || echo "######## run_sbc.sh FAILED ($cfg)"
  clean=0
done
echo "######## 013-$L gate finished $(date)"
