#!/usr/bin/env python3
"""sbc_stats.py — summarize an SBC migration run from its [SBC] log.

Parses the per-run directory in sw/verilator_logs/ (or a single sbc.log) and
emits the migration summary I keep asking for, so the full log doesn't have to
be pasted every time.

Usage:
    python3 sbc_stats.py <run-dir | sbc.log> [more ...]
    python3 sbc_stats.py                       # defaults to ./sbc.log

Writes <run-dir>/sbc_stats.txt next to the log and prints the same to stdout.
"""

import os
import re
import sys
from collections import Counter

STATE = {0: "INVALID", 1: "BRANCH", 2: "TRUNK", 3: "TIP"}

# [SBC] line parsers
RE_TAG      = re.compile(r"\[SBC\](?:\[[A-Z]+\])?\s+([A-Z][A-Z-]*)")
RE_MIGREQ   = re.compile(r"\[SBC\] MIGREQ src=(\d+) \(sat=(\d+)\) dst=(\d+) \(coldLevel=(\d+)\)")
RE_ABRT_SRC = re.compile(r"\[SBC\] ABORT-SRC set=(\d+) state=(\d+) dirty=(\d+) clients=(\d+) disp=(\d+)")
RE_ABRT_DST = re.compile(r"\[SBC\] ABORT-DST srcSet=(\d+) dstSet=(\d+)")
RE_COMMIT   = re.compile(r"\[SBC\](?:\[[A-Z]+\])?\s+MIG-COMMIT\b.*?srcSet=(\d+).*?dstSet=(\d+)")

# correctness / crash markers (looked for in sibling *.log / *.out)
RE_PASS  = re.compile(r"\bPASS\b.*", re.I)
RE_FAIL  = re.compile(r"\bFAIL\b.*|MISMATCH.*", re.I)
RE_CRASH = re.compile(r"Assertion failed.*|acknowledged for nothing inflight.*|%Error.*", re.I)


def find_log(path):
    """Resolve an arg to (sbc_log_path, run_dir)."""
    if os.path.isdir(path):
        return os.path.join(path, "sbc.log"), path
    return path, os.path.dirname(path) or "."


def scan_correctness(run_dir):
    """Look in sibling .log/.out for PASS/FAIL and crash lines."""
    result, crashes = None, []
    for fn in sorted(os.listdir(run_dir)) if os.path.isdir(run_dir) else []:
        if not (fn.endswith(".log") or fn.endswith(".out")) or fn == "sbc_stats.txt":
            continue
        with open(os.path.join(run_dir, fn), errors="replace") as f:
            for line in f:
                if result is None and RE_PASS.search(line):
                    result = ("PASS", line.strip())
                if RE_FAIL.search(line):
                    result = ("FAIL", line.strip())
                if RE_CRASH.search(line):
                    crashes.append(f"{fn}: {line.strip()}")
    return result, crashes


def parse(sbc_log):
    tags = Counter()
    migreq_pairs = Counter()
    abort_src_reason = Counter()   # (state,dirty,clients,disp) -> n
    abort_src_set = Counter()
    abort_dst_pairs = Counter()
    commits = Counter()
    with open(sbc_log, errors="replace") as f:
        for line in f:
            m = RE_TAG.search(line)
            if m:
                tags[m.group(1)] += 1
            m = RE_MIGREQ.search(line)
            if m:
                migreq_pairs[(int(m.group(1)), int(m.group(3)))] += 1
            m = RE_ABRT_SRC.search(line)
            if m:
                s, st, d, c, dp = map(int, m.groups())
                abort_src_reason[(st, d, c, dp)] += 1
                abort_src_set[s] += 1
            m = RE_ABRT_DST.search(line)
            if m:
                abort_dst_pairs[(int(m.group(1)), int(m.group(2)))] += 1
            m = RE_COMMIT.search(line)
            if m:
                commits[(int(m.group(1)), int(m.group(2)))] += 1
    return dict(tags=tags, migreq_pairs=migreq_pairs,
                abort_src_reason=abort_src_reason, abort_src_set=abort_src_set,
                abort_dst_pairs=abort_dst_pairs, commits=commits)


def render(sbc_log, run_dir, d, correctness, crashes):
    out = []
    w = out.append
    tags = d["tags"]
    nstart = tags.get("MIG-START", 0) or tags.get("MIGREQ", 0)  # Phase 2 / Phase 1
    nsrc = tags.get("ABORT-SRC", 0)
    ndst = tags.get("ABORT-DST", 0)
    ncom = sum(d["commits"].values())

    w(f"==== SBC run summary : {os.path.relpath(run_dir)} ====")
    if correctness:
        w(f"result        : {correctness[0]}  ({correctness[1]})")
    else:
        w("result        : (no PASS/FAIL line found)")
    w(f"crash/asserts : {'NONE' if not crashes else len(crashes)}")
    for c in crashes[:3]:
        w(f"   ! {c}")
    w("")

    w("---- migration summary ----")
    w(f"  MIG-START         : {nstart}")
    w(f"  COMMIT            : {ncom}")
    w(f"  ABORT-SRC         : {nsrc}")
    w(f"  ABORT-DST         : {ndst}")
    if nstart:
        w(f"  commit rate       : {ncom}/{nstart} = {100.0*ncom/nstart:.1f}%")
    w("")

    w("---- all [SBC] event tags ----")
    for tag, n in tags.most_common():
        w(f"  {n:6d}  {tag}")
    w("")

    if d["abort_src_reason"]:
        w("---- ABORT-SRC reason (why source victim ineligible) ----")
        w(f"  {'count':>6}  state    dirty clients disp")
        for (st, dy, cl, dp), n in d["abort_src_reason"].most_common():
            w(f"  {n:6d}  {STATE.get(st,st):<7} {dy:>5} {cl:>7} {dp:>4}")
        w("")
        w("---- ABORT-SRC by set ----")
        for s, n in sorted(d["abort_src_set"].items()):
            w(f"  set {s}: {n}")
        w("")

    if d["abort_dst_pairs"]:
        w("---- ABORT-DST by (src->dst) ----")
        for (s, t), n in d["abort_dst_pairs"].most_common():
            w(f"  {s}->{t}: {n}")
        w("")

    if d["migreq_pairs"]:
        w("---- MIGREQ by (src->dst) ----")
        for (s, t), n in d["migreq_pairs"].most_common(12):
            w(f"  {s}->{t}: {n}")
        w("")

    if d["commits"]:
        w("---- COMMIT by (src->dst) ----")
        for (s, t), n in d["commits"].most_common():
            w(f"  {s}->{t}: {n}")
        w("")

    return "\n".join(out)


def main():
    args = sys.argv[1:] or ["sbc.log"]
    for arg in args:
        sbc_log, run_dir = find_log(arg)
        if not os.path.isfile(sbc_log):
            print(f"!! no sbc.log at {sbc_log}", file=sys.stderr)
            continue
        d = parse(sbc_log)
        correctness, crashes = scan_correctness(run_dir)
        text = render(sbc_log, run_dir, d, correctness, crashes)
        print(text)
        if os.path.isdir(run_dir):
            dest = os.path.join(run_dir, "sbc_stats.txt")
            with open(dest, "w") as f:
                f.write(text + "\n")
            print(f"\n[wrote {os.path.relpath(dest)}]")


if __name__ == "__main__":
    main()
