#!/usr/bin/env python3
"""v7_summarize.py <transcript.log ...> - turn a 012 V7 board transcript into the report table.

Parses the [SBC-COUNTERS] dump that closes each run plus the RUNEND line, then applies the identity
checks from 010 TASK §7. Reads and writes are printed SEPARATELY on purpose: SBC trades one for the
other, and a combined figure hides exactly that.

K3 (attempted - migrations == dstAbortDirty + Held + Both) is the behavioural fingerprint of WHICH
RTL is in the bitstream: it holds under 008-c2 semantics and MUST FAIL on 012 C2, because a dirty
destination way is now written back instead of aborting. A K3 that still holds means the image on
the board is not the one we think it is.
"""
import re, sys

FIELDS = ("migrations attempted aborted parked memReads memWrites memAcqPerm memRelClean L2_Cycles "
          "accessA primaryHit secondaryHit dataMiss upgradeMiss secondSearch secondaryMiss "
          "dstAbortDirty dstAbortHeld dstAbortBoth secHits secMiss dispRelease dispDrop").split()

def parse(path):
    runs, pend = [], {}
    for line in open(path, errors="replace"):
        # accept both forms: this script's "RUNEND ITER_RC=..." and the bare
        # "ITER_RC=... ITER_SECS=..." that 010 §4.3 typed by hand.
        m = re.search(r"ITER_RC=(\d+) ITER_SECS=(\d+)", line)
        if m:
            pend = {"rc": int(m.group(1)), "secs": int(m.group(2))}
        if "[SBC-COUNTERS]" in line and pend:
            d = {k: int(v) for k, v in re.findall(r"(\w+)=(\d+)", line)}
            if "accessA" not in d:
                continue
            pol = re.search(r"policy=(\w+)", line)
            d.update(pend); d["policy"] = pol.group(1) if pol else "?"
            runs.append(d); pend = {}
    return runs

def main(paths):
    runs = []
    for p in paths:
        runs += parse(p)
    if not runs:
        print("no completed runs found in:", " ".join(paths)); return 1
    print(f"{'run':>3} {'pol':>6} {'rc':>2} {'secs':>5} {'memReads':>12} {'memWrites':>11} "
          f"{'migrations':>11} {'parked':>7} {'hit%':>6}")
    for i, d in enumerate(runs, 1):
        hit = 100.0 * (d["primaryHit"] + d["secondaryHit"]) / d["accessA"] if d.get("accessA") else 0
        print(f"{i:>3} {d['policy']:>6} {d['rc']:>2} {d['secs']:>5} {d['memReads']:>12} "
              f"{d['memWrites']:>11} {d['migrations']:>11} {d['parked']:>7} {hit:>5.2f}%")

    print("\nidentity checks (010 TASK §7) - report any that fail")
    for i, d in enumerate(runs, 1):
        acc = d["accessA"] - (d["primaryHit"] + d["secondaryHit"] + d["dataMiss"] + d["upgradeMiss"])
        mis = (d["dataMiss"] + d["upgradeMiss"]) - (d["memReads"] + d["memAcqPerm"])
        cyc = d["L2_Cycles"] / 50e6
        drift = 100.0 * abs(cyc - d["secs"]) / d["secs"] if d["secs"] else 0
        k3l = d["attempted"] - d["migrations"]
        k3r = d["dstAbortDirty"] + d["dstAbortHeld"] + d["dstAbortBoth"]
        print(f"  run {i}: accessA-4outcomes={acc:+d} (want |x|<=38{'  OK' if abs(acc)<=38 else '  FAIL'}) | "
              f"misses-outerA={mis:+d} ({'OK' if mis==0 else 'FAIL'}) | "
              f"L2_Cycles/50e6={cyc:.1f}s vs {d['secs']}s ({drift:.2f}%{'  OK' if drift<2 else '  FAIL'})")
        print(f"         K3: attempted-migrations={k3l} vs dstAborts={k3r} -> "
              f"{'HOLDS - this is 008-c2 behaviour, NOT 012 C2' if k3l==k3r else 'BREAKS by design (012 C2 writes a dirty destination back instead of aborting)'}")

    if len(runs) > 1:
        s = [d["secs"] for d in runs]
        print(f"\nseconds: {s}  spread {max(s)-min(s)}s ({100.0*(max(s)-min(s))/min(s):.2f}% of the fastest)")
        print(f"reads  : {[d['memReads'] for d in runs]}")
        print(f"writes : {[d['memWrites'] for d in runs]}")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]) or 0)
