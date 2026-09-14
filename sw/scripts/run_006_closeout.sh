#!/usr/bin/env bash
# 006 close-out verification: migration_stress_test on both configs, then the switch test on SBC.
# Not committed; a scratch runner for the close-out sims.
set -uo pipefail
GEN=/home/damith/Research/repos/chipyard_performance_eval/chipyard/generators/rocket-chip-inclusive-cache
cd "$GEN"

echo "############ RUN 1: migration_stress_test on SBC + NoSbc (rebuilds both simulators) ############"
date
SBC_LABEL=006-closeout ./sw/scripts/run_sbc.sh
r1=$?
echo "############ RUN 1 finished rc=$r1 ############"
date

echo "############ RUN 2: sbc_migrate_switch_test on SBC config (reuse built sim) ############"
SBC_TESTS=sbc_migrate_switch_test SBC_CONFIGS=VerilatorRocket8KL116KL2Config \
  SBC_CLEAN=0 SBC_LABEL=006-closeout ./sw/scripts/run_sbc.sh
r2=$?
echo "############ RUN 2 finished rc=$r2 ############"
date

echo "############ ALL DONE  run1_rc=$r1  run2_rc=$r2 ############"
