#!/usr/bin/env bash
# run_018_gate.sh <stage> - task 018 gate: the destination setting (SBC_DstMode 0x4B0, SBC_DstRescue 0x4B8).
# One stage per call; never two at once. SBC_CLEAN=1 only on the first run after an RTL edit (it runs
# `make clean` and wipes every simulator build): pass CLEAN=1 in the environment for that run.
#   g0       before any edit: L2Config + NoSbc, -DL2_POLICY=1                    (the baseline)
#   g1       new RTL, flag off: L2Config + NoSbc, -DL2_POLICY=1                  (I1: == g0)
#   g2       DstConfig, register untouched (setting 0), -DL2_POLICY=1            (I2: == g0 L2Config)
#   g3/g4/g5 DstConfig, -DL2_POLICY=1 -DSBC_DSTMODE_SET=0/1/2 (+ the directed case)
#   g6a/g6b  DstConfig, -DL2_POLICY=0 -DSBC_DSTMODE_SET=0/2 (random policy)       (I3: equal but dstmode=)
set -uo pipefail
S="${1:?usage: $0 g0|g1|g2|g3|g4|g5|g6a|g6b}"
HERE="$(cd "$(dirname "$0")" && pwd)"
RUN="$HERE/run_sbc.sh"
BOTH="sbc_migrate_switch_test migration_stress_test"
case "$S" in
  g0|g1) CFGS="VerilatorRocket8KL116KL2Config VerilatorRocket8KL116KL2NoSbcConfig"; FL="-DL2_POLICY=1" ;;
  g2)    CFGS="VerilatorRocket8KL116KL2DstConfig"; FL="-DL2_POLICY=1" ;;
  g3)    CFGS="VerilatorRocket8KL116KL2DstConfig"; FL="-DL2_POLICY=1 -DSBC_DSTMODE_SET=0" ;;
  g4)    CFGS="VerilatorRocket8KL116KL2DstConfig"; FL="-DL2_POLICY=1 -DSBC_DSTMODE_SET=1" ;;
  g5)    CFGS="VerilatorRocket8KL116KL2DstConfig"; FL="-DL2_POLICY=1 -DSBC_DSTMODE_SET=2" ;;
  g6a)   CFGS="VerilatorRocket8KL116KL2DstConfig"; FL="-DL2_POLICY=0 -DSBC_DSTMODE_SET=0" ;;
  g6b)   CFGS="VerilatorRocket8KL116KL2DstConfig"; FL="-DL2_POLICY=0 -DSBC_DSTMODE_SET=2" ;;
  *) echo "unknown stage $S"; exit 2 ;;
esac
echo "######## 018-$S on $CFGS (SBC_CLEAN=${CLEAN:-0}, EXTRA_CFLAGS=$FL) $(date)"
SBC_CONFIGS="$CFGS" SBC_TESTS="$BOTH" SBC_LABEL="018-$S" SBC_CLEAN="${CLEAN:-0}" \
  EXTRA_CFLAGS="$FL" "$RUN" || { echo "######## run_sbc.sh FAILED ($S)"; exit 1; }
echo "######## 018-$S gate finished $(date)"
