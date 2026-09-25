#!/usr/bin/env python3
"""parse_calib_sweep.py <transcript.log ...> [-o out.csv]

Turn a run_calib_sweep.exp transcript into one CSV row per (spare, overflow) point, with the
migrate-OFF and migrate-ON arms side by side.

Each point in the transcript looks like:
    SWEEP-POINT P=12 p=19 migrate=on drainedParked=0
    [L2MISS-DESIGN]   ... spare=4 overflow=3 combined=20 fits=1 seconds=6.64 ...
    [L2MISS-MEASURED] ... accessA=.. primaryHit=.. secondaryHit=.. dataMiss=.. cycles=.. ...

drainedParked is carried through deliberately: a point whose drain did not reach 0 did not start
cold, and that has to stay visible rather than being averaged away.
"""
import csv, re, sys

def kv(line):
    return {k: (float(v) if '.' in v else int(v))
            for k, v in re.findall(r"(\w+)=([0-9]+\.?[0-9]*)", line)}

def parse(paths):
    pts, cur = [], None
    for p in paths:
        for line in open(p, errors="replace"):
            m = re.search(r"SWEEP-POINT P=(\d+) p=(\d+) migrate=(\w+) drainedParked=(\d*)", line)
            if m:
                cur = {"P": int(m.group(1)), "mp": int(m.group(2)), "arm": m.group(3),
                       "drainedParked": int(m.group(4) or -1)}
                continue
            if cur is None:
                continue
            if "[L2MISS-DESIGN]" in line:
                cur.update({("d_" + k): v for k, v in kv(line).items()})
            elif "[L2MISS-MEASURED]" in line:
                cur.update({("m_" + k): v for k, v in kv(line).items()})
                if "m_accessA" in cur:
                    pts.append(cur)
                cur = None
    return pts

def pivot(pts):
    by = {}
    for r in pts:
        by.setdefault((r["P"], r["mp"]), {})[r["arm"]] = r
    rows = []
    for (P, mp), arms in sorted(by.items()):
        off, on = arms.get("off"), arms.get("on")
        if not off or not on:
            continue
        ways = int(off.get("d_ways", 16))
        acc_o, acc_n = off["m_accessA"], on["m_accessA"]
        def rate(r, acc, *keys):
            return 100.0 * sum(r.get(k, 0) for k in keys) / acc if acc else 0.0
        row = {
            "P": P, "mp": mp,
            "spare": int(off.get("d_spare", max(0, ways - P))),
            "overflow": int(off.get("d_overflow", max(0, mp - ways))),
            "fits_predicted": int(off.get("d_fits", 0)),
            "drained_off": off["drainedParked"], "drained_on": on["drainedParked"],
            "cycles_off": off["m_cycles"], "cycles_on": on["m_cycles"],
            "cycles_pct": 100.0 * (on["m_cycles"] - off["m_cycles"]) / off["m_cycles"] if off["m_cycles"] else 0.0,
            "reads_off": off["m_memReads"], "reads_on": on["m_memReads"],
            "reads_pct": 100.0 * (on["m_memReads"] - off["m_memReads"]) / off["m_memReads"] if off["m_memReads"] else 0.0,
            "writes_off": off["m_memWrites"], "writes_on": on["m_memWrites"],
            "missrate_off": rate(off, acc_o, "m_dataMiss", "m_upgradeMiss"),
            "missrate_on": rate(on, acc_n, "m_dataMiss", "m_upgradeMiss"),
            "primary_off": rate(off, acc_o, "m_primaryHit"),
            "primary_on": rate(on, acc_n, "m_primaryHit"),
            "secondary_on": rate(on, acc_n, "m_secondaryHit"),
            "migrations_on": on.get("m_migrations", 0),
            "parked_on": on.get("m_parked", 0),
            "secperpark_on": (on.get("m_secondaryHit", 0) / on["m_migrations"]) if on.get("m_migrations") else 0.0,
            "seconds_off": off.get("d_seconds", 0), "seconds_on": on.get("d_seconds", 0),
            "accessA_off": acc_o, "accessA_on": acc_n,
        }
        row["missrate_delta"] = row["missrate_on"] - row["missrate_off"]
        row["primary_gain"] = row["primary_on"] - row["primary_off"]
        rows.append(row)
    return rows

def main(argv):
    out = "calib_sweep.csv"
    paths = []
    i = 0
    while i < len(argv):
        if argv[i] == "-o":
            i += 1; out = argv[i]
        else:
            paths.append(argv[i])
        i += 1
    if not paths:
        print(__doc__); return 2
    pts = parse(paths)
    rows = pivot(pts)
    if not rows:
        print(f"no complete (off,on) pairs found in {len(pts)} parsed arms"); return 1
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader(); w.writerows(rows)
    print(f"{len(pts)} arms -> {len(rows)} points -> {out}")
    print(f"{'spare':>5} {'over':>5} {'pred':>5} {'cyc%':>7} {'reads%':>7} {'miss off':>9} {'miss on':>8} {'prim+':>7} {'sec':>6}")
    for r in rows:
        print(f"{r['spare']:>5} {r['overflow']:>5} {'win' if r['fits_predicted'] else 'lose':>5} "
              f"{r['cycles_pct']:>+7.2f} {r['reads_pct']:>+7.2f} {r['missrate_off']:>9.2f} "
              f"{r['missrate_on']:>8.2f} {r['primary_gain']:>+7.2f} {r['secondary_on']:>6.2f}")
    bad = [r for r in rows if r["drained_off"] or r["drained_on"]]
    if bad:
        print(f"\n!!! {len(bad)} point(s) did not start cold (drain left lines parked) - listed in the CSV")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
