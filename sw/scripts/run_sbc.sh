#!/usr/bin/env bash
# run_sbc.sh — build + run SBC tests on Verilator, collect logs, summarize.
# Edit the config block, then: ./run_sbc.sh
set -euo pipefail

# ----------------------- edit this block -----------------------
CHIPYARD=/home/damith/Research/repos/chipyard_performance_eval/chipyard
CONFIGS=(VerilatorRocket8KL116KL2Config) # VerilatorRocket8KL116KL2DstCollisionConfig)  # forced dst=0 → collision fires every run
TESTS=(migration_stress_test) # dst_collision_repro migration_stress_test)     # directed repro + the run that crashed @426us
LABEL="phase2-verification-test"                  # optional suffix on the output dir name (e.g. "no_bug_003")
MAX_CYCLES=100000000  # sim iteration cap (+max-cycles)
THREADS=19            # VERILATOR_THREADS
JOBS=20               # make -j
CLEAN=1               # 1 = make clean + rebuild RTL (needed after RTL edits); 0 = reuse build
# ---------------------------------------------------------------

SW="$CHIPYARD/generators/rocket-chip-inclusive-cache/sw"
LOGS="$SW/verilator_logs"

set +u; source "$CHIPYARD/env.sh"; set -u  # env.sh's conda hooks reference unbound vars
cd "$CHIPYARD/sims/verilator"

if [ "$CLEAN" = 1 ]; then make clean && rm -rf output/*; fi

for cfg in "${CONFIGS[@]}"; do
  for test in "${TESTS[@]}"; do
    echo "==== build $test ===="
    "$SW/scripts/compile_app.sh" "$test"

    echo "==== run $test on $cfg ===="
    make -j"$JOBS" run-binary-debug \
      BINARY="$SW/build/$test.riscv" \
      CONFIG="$cfg" \
      VERILATOR_THREADS="$THREADS" \
      "SIM_FLAGS=+max-cycles=$MAX_CYCLES"

    out="output/chipyard.harness.TestHarness.$cfg"
    dst="$LOGS/${test}_${cfg}${LABEL:+_$LABEL}"
    echo "==== collect -> $dst ===="
    mkdir -p "$dst"
    cp "$out/$test".{log,out,dump} "$dst"/ 2>/dev/null || true
    grep '\[SBC\]' "$out/$test.out" > "$dst/sbc.log" || true
    python3 "$SW/scripts/sbc_stats.py" "$dst"
  done
done
