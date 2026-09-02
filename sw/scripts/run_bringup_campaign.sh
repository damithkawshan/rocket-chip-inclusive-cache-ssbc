#!/usr/bin/env bash
# run_bringup_campaign.sh — run bringup-bench benchmarks SBC-on vs SBC-off and tabulate the result.
#
# Wrapper around run_sbc.sh. For each benchmark it builds the binary with compile_bringup.sh,
# runs it on both configs via run_sbc.sh, then collects every run into
#   results/initial_bringup_results/
# and writes summary.csv + summary.md comparing the two configs.
#
# Usage:
#   ./run_bringup_campaign.sh                     # default shortlist
#   ./run_bringup_campaign.sh -b "matmult fft-int"
#   ./run_bringup_campaign.sh -b matmult -n 48    # override the benchmark's #define N
#   ./run_bringup_campaign.sh -r                  # re-tabulate existing logs, run nothing
#   ./run_bringup_campaign.sh -C                  # force a clean RTL rebuild first
set -euo pipefail

CY=/home/damith/Research/repos/chipyard_performance_eval/chipyard
GEN="$CY/generators/rocket-chip-inclusive-cache"
SW="$GEN/sw"
RESULTS="$GEN/results/initial_bringup_results"

SBC_CFG=VerilatorRocket8KL116KL2Config
NOSBC_CFG=VerilatorRocket8KL116KL2NoSbcConfig

# Shortlist, ordered by how likely the access pattern is to produce a HOT set next to a COLD one
# (which is the only situation SBC can exploit). Rationale is in summary.md.
BENCHES="matmult fft-int congrad checkers graph-tests huff-encode life distinctness dhrystone sieve idct-alg lu-decomp"
NOVR=""
LABEL="initial_bringup"
CLEAN=0
REPORT_ONLY=0
MAX_CYCLES=${MAX_CYCLES:-200000000}

while getopts "b:n:l:m:Crh" o; do
  case "$o" in
    b) BENCHES="$OPTARG" ;;
    n) NOVR="$OPTARG" ;;
    l) LABEL="$OPTARG" ;;
    m) MAX_CYCLES="$OPTARG" ;;
    C) CLEAN=1 ;;
    r) REPORT_ONLY=1 ;;
    h) sed -n '2,14p' "$0"; exit 0 ;;
    *) exit 1 ;;
  esac
done

mkdir -p "$RESULTS"

if [ "$REPORT_ONLY" = 0 ]; then
  set +u; source "$CY/env.sh" >/dev/null 2>&1; set -u

  first=1
  for b in $BENCHES; do
    echo "################ $b ################"
    # One build per benchmark; both configs run the same binary.
    if ! "$SW/scripts/compile_bringup.sh" "$b" ${NOVR:+$NOVR} > "$RESULTS/$b.build.log" 2>&1; then
      echo "!! BUILD FAILED for $b — see $RESULTS/$b.build.log, skipping"
      continue
    fi

    # CLEAN only on the very first benchmark: the RTL does not change between benchmarks, and a
    # rebuild per benchmark would cost hours for nothing.
    clean=0
    [ "$CLEAN" = 1 ] && [ "$first" = 1 ] && clean=1

    if ! SBC_CONFIGS="$SBC_CFG $NOSBC_CFG" \
         SBC_TESTS="bringup_$b" \
         SBC_LABEL="$LABEL" \
         SBC_CLEAN="$clean" \
         SBC_SKIP_BUILD=1 \
         SBC_MAX_CYCLES="$MAX_CYCLES" \
         "$SW/scripts/run_sbc.sh" > "$RESULTS/$b.run.log" 2>&1; then
      echo "!! RUN FAILED for $b — see $RESULTS/$b.run.log"
    fi
    first=0

    # Park each run's artefacts under results/, keeping the raw logs in verilator_logs/.
    for cfg in "$SBC_CFG" "$NOSBC_CFG"; do
      src="$SW/verilator_logs/bringup_${b}_${cfg}_${LABEL}"
      [ -d "$src" ] || continue
      tag=sbc; [ "$cfg" = "$NOSBC_CFG" ] && tag=nosbc
      mkdir -p "$RESULTS/$b/$tag"
      cp "$src/sbc_stats.txt" "$RESULTS/$b/$tag/" 2>/dev/null || true
      cp "$src"/*.log "$RESULTS/$b/$tag/" 2>/dev/null || true
    done
  done
fi

# ------------------------------------------------------------------ tabulate
python3 - "$RESULTS" <<'PYEOF'
import os, re, sys, csv

RESULTS = sys.argv[1]
RE_KV     = re.compile(r"([A-Za-z_][A-Za-z_0-9]*)=(\d+)")
RE_COUNT  = re.compile(r"\[(?:SBC-COUNTERS|SIP-TOTALS)\]\s+(.*)")
RE_CYCLES = re.compile(r"\[CYCLES\]\s+\w+=(\d+)")
RE_SIMCYC = re.compile(r"Completed after\s+(\d+)\s+simulation cycles")
RE_PASSED = re.compile(r"\*\*\* PASSED \*\*\*|PASSED|SUCCESS", re.I)
RE_FAILED = re.compile(r"\*\*\* FAILED \*\*\*|FAILED|MISMATCH", re.I)
RE_CRASH  = re.compile(r"Assertion failed|%Error")


def read_run(d):
    """Pull one run's numbers out of whatever console logs landed in d."""
    r = {"counters": {}, "cycles": None, "simcycles": None,
         "result": "?", "crash": 0}
    if not os.path.isdir(d):
        return None
    for fn in sorted(os.listdir(d)):
        if not fn.endswith(".log") or fn in ("sbc.log",):
            continue
        for line in open(os.path.join(d, fn), errors="replace"):
            m = RE_COUNT.search(line)
            if m:
                r["counters"] = {k: int(v) for k, v in RE_KV.findall(m.group(1))}
            m = RE_CYCLES.search(line)
            if m:
                r["cycles"] = int(m.group(1))
            m = RE_SIMCYC.search(line)
            if m:
                r["simcycles"] = int(m.group(1))
            if RE_CRASH.search(line):
                r["crash"] += 1
            if RE_FAILED.search(line):
                r["result"] = "FAIL"
            elif r["result"] == "?" and RE_PASSED.search(line):
                r["result"] = "PASS"
    return r


def g(run, key, default=0):
    return run["counters"].get(key, default) if run else default


rows = []
for b in sorted(os.listdir(RESULTS)):
    bd = os.path.join(RESULTS, b)
    if not os.path.isdir(bd):
        continue
    sbc, nos = read_run(os.path.join(bd, "sbc")), read_run(os.path.join(bd, "nosbc"))
    if not sbc and not nos:
        continue

    def rate(run):
        a, h = g(run, "L2_Accesses"), g(run, "L2_Hits")
        return (100.0 * h / a) if a else None

    sc = (sbc or {}).get("cycles") or (sbc or {}).get("simcycles")
    nc = (nos or {}).get("cycles") or (nos or {}).get("simcycles")
    rows.append({
        "bench": b,
        "sbc_result": (sbc or {}).get("result", "-"),
        "nosbc_result": (nos or {}).get("result", "-"),
        "migrations": g(sbc, "mig"),
        "secHits": g(sbc, "secHits"),
        "secMiss": g(sbc, "secMiss"),
        "sbc_acc": g(sbc, "L2_Accesses"),
        "sbc_hits": g(sbc, "L2_Hits"),
        "nosbc_acc": g(nos, "L2_Accesses"),
        "nosbc_hits": g(nos, "L2_Hits"),
        "sbc_hitrate": rate(sbc),
        "nosbc_hitrate": rate(nos),
        "sbc_cycles": sc,
        "nosbc_cycles": nc,
        "cycles_delta_pct": (100.0 * (sc - nc) / nc) if (sc and nc) else None,
        "asserts": (sbc or {}).get("crash", 0) + (nos or {}).get("crash", 0),
    })

if not rows:
    print("no completed runs found under", RESULTS)
    sys.exit(0)

cols = list(rows[0].keys())
with open(os.path.join(RESULTS, "summary.csv"), "w", newline="") as f:
    wr = csv.DictWriter(f, fieldnames=cols)
    wr.writeheader()
    wr.writerows(rows)


def fmt(v, pct=False, sign=False):
    if v is None:
        return "-"
    if isinstance(v, float):
        return f"{v:+.2f}%" if sign else (f"{v:.2f}%" if pct else f"{v:.2f}")
    return str(v)


out = ["# Initial bringup-bench campaign — SBC on vs off", "",
       "Generated by `sw/scripts/run_bringup_campaign.sh`. Raw logs per benchmark in this directory.",
       "",
       "`cycles delta` is the headline: **negative means SBC was faster.**",
       "",
       "| bench | SBC | base | migr | secHit | secMiss | hit% SBC | hit% base | cycles SBC | cycles base | cycles delta |",
       "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
for r in sorted(rows, key=lambda x: (x["cycles_delta_pct"] is None, x["cycles_delta_pct"] or 0)):
    out.append("| {bench} | {sr} | {nr} | {mig} | {sh} | {sm} | {hs} | {hn} | {cs} | {cn} | {cd} |".format(
        bench=r["bench"], sr=r["sbc_result"], nr=r["nosbc_result"], mig=r["migrations"],
        sh=r["secHits"], sm=r["secMiss"],
        hs=fmt(r["sbc_hitrate"], pct=True), hn=fmt(r["nosbc_hitrate"], pct=True),
        cs=fmt(r["sbc_cycles"]), cn=fmt(r["nosbc_cycles"]),
        cd=fmt(r["cycles_delta_pct"], sign=True)))

out += ["", "## Caveat on the hit% columns", "",
        "`L2_Accesses` currently counts SBC's own internal directory reads (the secondary search and",
        "the migration destination read). Those are marked `internalRead`, and `Directory.scala:230`",
        "forces `hit` false for every one of them, so they enter the denominator as guaranteed misses",
        "and drag the SBC hit rate down. **The hit% columns are not a like-for-like comparison until",
        "the counter is gated on `!internalRead`.** The cycles columns are unaffected and are the",
        "metric to trust.", ""]
open(os.path.join(RESULTS, "summary.md"), "w").write("\n".join(out) + "\n")
print("\n".join(out))
print(f"\n[wrote {RESULTS}/summary.csv and summary.md]")
PYEOF
