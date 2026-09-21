#!/usr/bin/env bash
# run_009_gate.sh <cN> - task 009 gate: PLRU mode only (the user dropped random mode for 009).
#   SBC   config: switch test + stress test, -DL2_POLICY=1   (V2, V3, V4)
#   NoSbc config: switch test + stress test, -DL2_POLICY=1   (V2, V5: same binaries as 008-c2-plru)
# The first run rebuilds (SBC_CLEAN=1); the NoSbc run rebuilds for its own config (make does that).
set -uo pipefail
C="${1:?usage: $0 c1|c2}"
HERE="$(cd "$(dirname "$0")" && pwd)"
RUN="$HERE/run_sbc.sh"
LOGS="$HERE/../verilator_logs"
BOTH="sbc_migrate_switch_test migration_stress_test"
clean=1
for cfg in VerilatorRocket8KL116KL2Config VerilatorRocket8KL116KL2NoSbcConfig; do
  echo "######## 009-$C-plru on $cfg (SBC_CLEAN=$clean) $(date)"
  SBC_CONFIGS="$cfg" SBC_TESTS="$BOTH" SBC_LABEL="009-$C-plru" SBC_CLEAN=$clean \
    EXTRA_CFLAGS="-DL2_POLICY=1" "$RUN" || echo "######## run_sbc.sh FAILED ($cfg)"
  clean=0
done
echo "######## V3 replay $(date)"
python3 "$HERE/plru_replay.py" "$LOGS"/*_009-$C-plru/ || true
echo "######## 009-$C gate finished $(date)"
