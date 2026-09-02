#!/usr/bin/env bash
# run_sbc.sh — build + run SBC tests on Verilator, collect logs, summarize.
# Edit the config block, then: ./run_sbc.sh
set -euo pipefail

# ----------------------- edit this block -----------------------
CHIPYARD=/home/damith/Research/repos/chipyard_performance_eval/chipyard
# Every value below can be overridden from the environment (SBC_*), which is how
# run_bringup_campaign.sh drives this script without editing it. Edit here for manual runs.
CONFIGS=(${SBC_CONFIGS:-VerilatorRocket8KL116KL2Config VerilatorRocket8KL116KL2NoSbcConfig})
TESTS=(${SBC_TESTS:-migration_stress_test})
LABEL="${SBC_LABEL:-l2-hitrate-counters-004}"
MAX_CYCLES=${SBC_MAX_CYCLES:-100000000}  # sim iteration cap (+max-cycles)
THREADS=${SBC_THREADS:-19}               # VERILATOR_THREADS
JOBS=${SBC_JOBS:-20}                     # make -j
CLEAN=${SBC_CLEAN:-1}                    # 1 = make clean + rebuild RTL (needed after RTL edits); 0 = reuse
# Set SBC_SKIP_BUILD=1 when the caller has already built $test.riscv itself
# (run_bringup_campaign.sh builds via compile_bringup.sh, which compile_app.sh cannot do).
SKIP_BUILD=${SBC_SKIP_BUILD:-0}
# ---------------------------------------------------------------

SW="$CHIPYARD/generators/rocket-chip-inclusive-cache/sw"
LOGS="$SW/verilator_logs"

set +u; source "$CHIPYARD/env.sh"; set -u  # env.sh's conda hooks reference unbound vars
cd "$CHIPYARD/sims/verilator"

if [ "$CLEAN" = 1 ]; then make clean && rm -rf output/*; fi

for cfg in "${CONFIGS[@]}"; do
  for test in "${TESTS[@]}"; do
    if [ "$SKIP_BUILD" = 1 ]; then
      echo "==== build $test (skipped, caller supplied the binary) ===="
    else
      echo "==== build $test ===="
      "$SW/scripts/compile_app.sh" "$test"
    fi

    echo "==== run $test on $cfg ===="
    # run-binary, NOT run-binary-debug: the -debug target builds the traced simulator and dumps a
    # VCD/FST per run, which costs a lot of time and disk for waveforms we never open. Both targets
    # still write $test.out (the [SBC] printfs) and $test.log (stdout), which is all we parse.
    make -j"$JOBS" run-binary \
      BINARY="$SW/build/$test.riscv" \
      CONFIG="$cfg" \
      VERILATOR_THREADS="$THREADS" \
      "SIM_FLAGS=+max-cycles=$MAX_CYCLES"

    out="output/chipyard.harness.TestHarness.$cfg"
    dst="$LOGS/${test}_${cfg}${LABEL:+_$LABEL}"
    echo "==== collect -> $dst ===="
    mkdir -p "$dst"
    cp "$out/$test".{log,out} "$dst"/ 2>/dev/null || true  # no .dump: that was a run-binary-debug artefact
    grep '\[SBC\]' "$out/$test.out" > "$dst/sbc.log" || true
    python3 "$SW/scripts/sbc_stats.py" "$dst"
  done
done
