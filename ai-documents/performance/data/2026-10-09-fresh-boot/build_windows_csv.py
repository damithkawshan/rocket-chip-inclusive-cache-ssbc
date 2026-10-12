#!/usr/bin/env python3
"""Collect every VALID fresh-boot measurement window of the 2026-10-05..09 experiments into windows.csv (one row per window).

usage: build_windows_csv.py            (re-run it when a campaign adds windows; it only reads scripts/logs/clean-runs)

Sources (all under chipyard/scripts/logs/clean-runs/):
  lu-sweep-single-core-20261008/{off,on}/*/stage_result.json          LU alone, run by the harness directly
  lu-pair-single-core-20261008/{,r2/}{off,on}/*/stage_result.json     LU b32 + b128 pair, 2 runs
  lu-pair-search-20261008/{off,on}/*/stage_result.json                LU + LU pairs
  lu-fft-search-20261009/{off,on}/*/stage_result.json                 fft alone and LU + fft pairs
  solo-lu-b64/{off,on}/pair_runs_*.csv  (FIRST row only: the only fresh-boot window of those sessions; README there)
  pair-lu-streamcluster-b64/{off,on}/pair_runs_*.csv                  LU b64 + streamcluster, one window per session
Only windows a checker judged VALID (stage_result.json verdict) or, for the pair.sh sessions (no checker existed), status OK
are kept. memAcc = memReads + memWrites; hit_rate = (primaryHit + secondaryHit) / accessA.
"""
import csv, glob, json, os, re

HERE = os.path.dirname(os.path.abspath(__file__))
CY = os.path.abspath(os.path.join(HERE, *[".."] * 6))
CR = os.path.join(CY, "scripts", "logs", "clean-runs")
KEYS = ["memReads", "memWrites", "accessA", "primaryHit", "secondaryHit", "dataMiss", "L2_Cycles", "L2_Accesses", "L2_Hits",
        "migrations", "secHits", "secMiss", "attempted", "aborted", "parked", "dispDrop", "dispRelease", "secondSearch",
        "secondaryMiss", "probedHit", "upgradeMiss", "memAcqPerm", "memRelClean", "dstAbortDirty", "dstAbortHeld", "dstAbortBoth"]
EXP = {"lu-sweep-single-core-20261008": "lu-alone-sweep", "lu-pair-single-core-20261008": "lu-b32+b128-pair",
       "lu-pair-search-20261008": "lu-pair-search", "lu-fft-search-20261009": "lu-fft-grid"}
rows = []


def stamp(path):
    m = re.search(r"_(\d{8})-(\d{6})\.(log|csv)$", path or "")
    return "%s-%s-%s %s:%s" % (m.group(1)[:4], m.group(1)[4:6], m.group(1)[6:8], m.group(2)[:2], m.group(2)[2:4]) if m else ""


for f in sorted(glob.glob(os.path.join(CR, "*", "off", "*", "stage_result.json")) + glob.glob(os.path.join(CR, "*", "on", "*", "stage_result.json")) +
                glob.glob(os.path.join(CR, "*", "r2", "*", "*", "stage_result.json"))):
    rel = os.path.relpath(f, CR)
    top = rel.split(os.sep)[0]
    if top not in EXP:
        continue
    j = json.load(open(f))
    if j.get("verdict") != "VALID":
        continue
    c = j["counters"]
    run = "2" if (os.sep + "r2" + os.sep) in rel else "1"
    rows.append(dict(experiment=EXP[top], setting=j["label"], sbc=j["phase"], run=run, started=stamp(j.get("log")),
                     source=os.path.dirname(rel), **{k: c.get(k, "") for k in KEYS}))

for sub, exp in (("solo-lu-b64", "lu-b64-alone-pairsh"), ("pair-lu-streamcluster-b64", "lu-b64+streamcluster-pairsh")):
    for f in sorted(glob.glob(os.path.join(CR, sub, "*", "pair_runs_*.csv"))):
        r = next(csv.DictReader(open(f)))                      # first row = the first window after the boot
        if r.get("status") != "OK":
            continue
        rel = os.path.relpath(f, CR)
        rows.append(dict(experiment=exp, setting=r["label"], sbc=r["migrate"], run=str(len([x for x in rows if x["experiment"] == exp and x["sbc"] == r["migrate"]]) + 1),
                         started=stamp(f), source=rel, **{k: int(r[k]) if r.get(k, "") not in ("", None) else "" for k in KEYS}))

for r in rows:
    r["memAcc"] = int(r["memReads"]) + int(r["memWrites"])
    r["hit_rate"] = "%.4f" % ((int(r["primaryHit"]) + int(r["secondaryHit"])) / int(r["accessA"]))
cols = ["experiment", "setting", "sbc", "run", "started", "memAcc", "hit_rate"] + KEYS + ["source"]
with open(os.path.join(HERE, "windows.csv"), "w", newline="") as fh:
    w = csv.DictWriter(fh, fieldnames=cols)
    w.writeheader()
    for r in sorted(rows, key=lambda r: (r["experiment"], r["setting"], r["sbc"] != "off", r["run"])):
        w.writerow({k: r[k] for k in cols})
print("windows.csv: %d valid windows" % len(rows))
for e in sorted({r["experiment"] for r in rows}):
    print("  %-28s %d" % (e, sum(1 for r in rows if r["experiment"] == e)))
