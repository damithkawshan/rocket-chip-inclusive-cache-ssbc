#!/usr/bin/env python3
"""sbc_stats.py — summarize an SBC run from its [SBC] log + console output.

Parses a run directory in sw/verilator_logs/ (or a single sbc.log) and emits the
migration + serve-in-place summary, so the full log doesn't have to be pasted.

Two sources are read and cross-checked:
  * sbc.log            — the [SBC] RTL printfs (grep'd out by run_sbc.sh)
  * <test>.log / .out  — console stdout: PASS/FAIL, asserts, and the authoritative
                         [SBC-COUNTERS] / [SIP-TOTALS] MMIO counter line

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

# ---------------------------------------------------------------- [SBC] parsers
# Tag extractor. `[SBC] FOO ...` or `[SBC][SCHED] FOO ...`; `[SBC][elab]` is skipped.
RE_TAG = re.compile(r"\[SBC\](?:\[([A-Z]+)\])?\s+([A-Z][A-Z0-9-]*)")

# --- Phase 2 migrate-on-eviction ---
RE_ASSESS = re.compile(r"EVICT-ASSESS srcSet=(\d+) way=(\d+) adviceValid=(\d+) offerValid=(\d+) "
                       r"offerSet=(\d+) eligible=(\d+) dirty=(\d+) clients=(\d+) displaced=(\d+)")
RE_DECLINE = re.compile(r"MIG-DECLINE srcSet=(\d+) srcWay=(\d+) reason=(\S+)")
RE_START   = re.compile(r"MIG-START srcSet=(\d+) srcWay=(\d+) dstSet=(\d+)")
RE_DREAD   = re.compile(r"DREAD-RESULT srcSet=(\d+) dstSet=(\d+) dstWay=(\d+) "
                        r"state=(\d+) dirty=(\d+) clients=(\d+) displaced=(\d+)")
RE_ABRT_DST = re.compile(r"ABORT-DST srcSet=(\d+) dstSet=(\d+)")
RE_COMMIT  = re.compile(r"MIG-COMMIT srcSet=(\d+) dstSet=(\d+)")
RE_COPYD   = re.compile(r"COPY-DONE srcSet=(\d+) srcWay=(\d+) dstSet=(\d+) dstWay=(\d+)")

# --- Phase 3R serve-in-place ---
RE_SEC_SERVE = re.compile(r"SEC-SERVE set=(\d+) partner=(\d+) way=(\d+) state=(\d+) "
                          r"clients=(\d+) needT=(\d+) needPerm=(\d+)")
RE_SEC_MISS  = re.compile(r"SEC-MISS set=(\d+) partner=(\d+)")
RE_SEC_DEFER = re.compile(r"SEC-DEFER srcSet=(\d+) way=(\d+) partner=(\d+)")

# --- Phase 1 legacy (kept so old logs still parse; absent from current RTL) ---
RE_MIGREQ   = re.compile(r"MIGREQ src=(\d+) \(sat=(\d+)\) dst=(\d+) \(coldLevel=(\d+)\)")
RE_ABRT_SRC = re.compile(r"ABORT-SRC set=(\d+) state=(\d+) dirty=(\d+) clients=(\d+) disp=(\d+)")

# Tags that mean something went wrong even if the test still printed PASS.
TRIPWIRES = ("BUG-A-DETECT", "SEC-STUCK", "C-HEAD-STALL")

# ---------------------------------------------------------- console parsers
# Authoritative hardware counters, printed by the test just before it exits.
# Generic key=value scrape so new counters are picked up without editing this file.
RE_COUNTERS = re.compile(r"\[(SBC-COUNTERS|SIP-TOTALS)\]\s+(.*)")
RE_KV       = re.compile(r"([A-Za-z_][A-Za-z_0-9]*)=(\d+)")
RE_CHECKSUM = re.compile(r"Checksum:\s*(0x[0-9a-fA-F]+|\d+)")
RE_CYCLES   = re.compile(r"\[CYCLES\]\s+(\w+)=(\d+)")
RE_VERDICT  = re.compile(r"\*\*\* (PASSED|FAILED) \*\*\*|^(PASS|FAIL):\s*(.*)")
RE_CASE     = re.compile(r"^(\S.*?):\s+(PASS|FAIL)\b(.*)$")
RE_CRASH    = re.compile(r"Assertion failed.*|acknowledged for nothing inflight.*|%Error.*", re.I)

# Counter name (as printed) -> the [SBC] tag it should agree with.
COUNTER_VS_TAG = [
    ("migrations", "MIG-COMMIT"), ("mig", "MIG-COMMIT"),
    ("attempted",  "MIG-START"),  ("att", "MIG-START"),
    ("secHits",    "SEC-SERVE"),
    ("secMiss",    "SEC-MISS"),
]


# Total-L2 counters (coder task 004). Not built yet at time of writing — the lookup accepts the
# likely spellings so this section starts working the moment the counters land, with no edit here.
TOTAL_ALIASES = {
    "accesses": ("L2_Accesses", "l2Accesses", "L2Accesses", "accesses", "acc", "l2acc"),
    "hits":     ("L2_Hits", "l2Hits", "L2Hits", "hits", "l2hits"),
    "misses":   ("L2_Misses", "l2Misses", "L2Misses", "misses", "l2miss"),
}


def pick(counters, which):
    """First matching alias from the printed counter line, case-insensitively."""
    low = {k.lower(): v for k, v in counters.items()}
    for name in TOTAL_ALIASES[which]:
        if name.lower() in low:
            return low[name.lower()]
    return None


def find_log(path):
    """Resolve an arg to (sbc_log_path, run_dir)."""
    if os.path.isdir(path):
        return os.path.join(path, "sbc.log"), path
    return path, os.path.dirname(path) or "."


def scan_console(run_dir):
    """Read the sibling .log/.out for verdict, per-case results, asserts, MMIO counters."""
    verdict, cases, crashes, counters, checksum, cycles = None, [], [], {}, None, {}
    files = sorted(os.listdir(run_dir)) if os.path.isdir(run_dir) else []
    for fn in files:
        # sbc.log is a filtered copy of .out — skip it, it holds no console lines.
        if fn in ("sbc.log", "sbc_stats.txt") or not fn.endswith((".log", ".out")):
            continue
        with open(os.path.join(run_dir, fn), errors="replace") as f:
            for line in f:
                line = line.rstrip("\n")
                m = RE_COUNTERS.search(line)
                if m:
                    counters = {k: int(v) for k, v in RE_KV.findall(m.group(2))}
                m = RE_CHECKSUM.search(line)
                if m:
                    checksum = m.group(1)
                for k, v in RE_CYCLES.findall(line):
                    cycles[k] = int(v)
                m = RE_CASE.match(line)
                if m and "[" not in m.group(1):
                    cases.append((m.group(1).strip(), m.group(2), m.group(3).strip()))
                m = RE_VERDICT.search(line)
                if m:
                    # Last verdict wins — the final summary line, not the first case.
                    verdict = ("PASS" if (m.group(1) or m.group(2)) in ("PASSED", "PASS")
                               else "FAIL", line.strip())
                if RE_CRASH.search(line):
                    crashes.append(f"{fn}: {line.strip()}")
    if verdict is None and cases:
        bad = [c for c in cases if c[1] == "FAIL"]
        verdict = ("FAIL", f"{len(bad)} case(s) failed") if bad else \
                  ("PASS", f"all {len(cases)} cases passed")
    return verdict, cases, crashes, counters, checksum, cycles


def parse(sbc_log):
    """One pass over sbc.log. Tag is extracted once, then dispatched — the log is ~100MB."""
    d = dict(tags=Counter(), start_pairs=Counter(), commits=Counter(), copies=Counter(),
             abort_dst_pairs=Counter(), decline_reason=Counter(),
             assess_reason=Counter(), assess_set=Counter(), assess_eligible=Counter(),
             dread_outcome=Counter(), dread_cause=Counter(),
             dread_presence=Counter(), dread_reject_set=Counter(),
             sec_serve_kind=Counter(), sec_serve_state=Counter(), sec_pairs=Counter(),
             sec_miss_pairs=Counter(), sec_defer_pairs=Counter(),
             migreq_pairs=Counter(), abort_src_reason=Counter(), abort_src_set=Counter())

    with open(sbc_log, errors="replace") as f:
        for line in f:
            m = RE_TAG.search(line)
            if not m:
                continue
            grp, tag = m.group(1), m.group(2)
            # `[SBC][SCU] START` collides with nothing else, but the bare name is opaque.
            if grp == "SCU" and tag == "START":
                tag = "SCU-START"
            d["tags"][tag] += 1

            if tag == "EVICT-ASSESS":
                mm = RE_ASSESS.search(line)
                if mm:
                    s, _w, _av, ov, _os, el, dy, cl, dp = map(int, mm.groups())
                    d["assess_set"][s] += 1
                    # `eligible` in the printf is migEligible = the FAST path only (clean AND
                    # client-free). A clean line whose clients bit is set still reaches migration
                    # via probe-then-migrate (cbb3837), so it is NOT a reject.
                    if dp:
                        cls = "hard reject: already parked"
                    elif dy:
                        cls = "hard reject: dirty"
                    elif el:
                        cls = "fast path: clean + client-free"
                    elif cl:
                        cls = "probe path: clean, clients bit set"
                    else:
                        cls = "other"
                    d["assess_eligible"][cls] += 1
                    d["assess_reason"]["destination offered" if ov else "no destination offered"] += 1
            elif tag == "MIG-DECLINE":
                mm = RE_DECLINE.search(line)
                if mm:
                    d["decline_reason"][mm.group(3)] += 1
            elif tag == "MIG-START":
                mm = RE_START.search(line)
                if mm:
                    d["start_pairs"][(int(mm.group(1)), int(mm.group(3)))] += 1
            elif tag == "DREAD-RESULT":
                mm = RE_DREAD.search(line)
                if mm:
                    _src, dst, _way, st, dy, cl, dp = map(int, mm.groups())
                    bits = [n for n, v in (("dirty", dy), ("clients", cl), ("displaced", dp)) if v]
                    if st == 0:
                        d["dread_outcome"]["accept-free"] += 1
                    elif not bits:
                        d["dread_outcome"]["accept-evictable"] += 1
                    else:
                        d["dread_outcome"]["reject"] += 1
                        d["dread_cause"]["+".join(bits)] += 1
                        for n in bits:
                            d["dread_presence"][n] += 1
                        d["dread_reject_set"][dst] += 1
            elif tag == "ABORT-DST":
                mm = RE_ABRT_DST.search(line)
                if mm:
                    d["abort_dst_pairs"][(int(mm.group(1)), int(mm.group(2)))] += 1
            elif tag == "MIG-COMMIT":
                mm = RE_COMMIT.search(line)
                if mm:
                    d["commits"][(int(mm.group(1)), int(mm.group(2)))] += 1
            elif tag == "COPY-DONE":
                mm = RE_COPYD.search(line)
                if mm:
                    d["copies"][(int(mm.group(1)), int(mm.group(3)))] += 1
            elif tag == "SEC-SERVE":
                mm = RE_SEC_SERVE.search(line)
                if mm:
                    s, p, _w, st, cl, nt, np_ = map(int, mm.groups())
                    d["sec_pairs"][(s, p)] += 1
                    d["sec_serve_state"][STATE.get(st, st)] += 1
                    d["sec_serve_kind"]["write (needT)" if nt else "read"] += 1
                    if cl:
                        d["sec_serve_kind"]["had clients -> probe"] += 1
                    if np_:
                        d["sec_serve_kind"]["needed permission"] += 1
            elif tag == "SEC-MISS":
                mm = RE_SEC_MISS.search(line)
                if mm:
                    d["sec_miss_pairs"][(int(mm.group(1)), int(mm.group(2)))] += 1
            elif tag == "SEC-DEFER":
                mm = RE_SEC_DEFER.search(line)
                if mm:
                    d["sec_defer_pairs"][(int(mm.group(1)), int(mm.group(3)))] += 1
            elif tag == "MIGREQ":                                   # Phase 1 legacy
                mm = RE_MIGREQ.search(line)
                if mm:
                    d["migreq_pairs"][(int(mm.group(1)), int(mm.group(3)))] += 1
            elif tag == "ABORT-SRC":                                # Phase 1 legacy
                mm = RE_ABRT_SRC.search(line)
                if mm:
                    s, st, dy, cl, dp = map(int, mm.groups())
                    d["abort_src_reason"][(st, dy, cl, dp)] += 1
                    d["abort_src_set"][s] += 1
    return d


def agree(a, b):
    """Counter-vs-tag verdict. A small tail is expected: the RTL keeps logging after
    the test's final MMIO read, so the printf count runs slightly ahead."""
    delta = a - b
    if delta == 0:
        return "ok"
    if abs(delta) <= max(8, int(0.001 * max(abs(a), abs(b)))):
        return f"~ delta {delta:+d} (tail after final MMIO read)"
    return f"!! MISMATCH delta {delta:+d}"


def render(run_dir, d, verdict, cases, crashes, counters, checksum, cycles):
    out = []
    w = out.append
    tags = d["tags"]
    t = tags.get

    nstart = t("MIG-START", 0) or t("MIGREQ", 0)          # Phase 2 / Phase 1
    ncom   = t("MIG-COMMIT", 0) or sum(d["commits"].values())
    ndst   = t("ABORT-DST", 0)
    src_tag = "MIG-DECLINE" if t("MIG-DECLINE") else ("ABORT-SRC" if t("ABORT-SRC") else "MIG-DECLINE")
    nsrc   = t(src_tag, 0)
    nserve = t("SEC-SERVE", 0)
    nsmiss = t("SEC-MISS", 0)

    w(f"==== SBC run summary : {os.path.relpath(run_dir)} ====")
    w(f"result        : {verdict[0]}  ({verdict[1]})" if verdict
      else "result        : (no PASS/FAIL line found)")
    if checksum:
        w(f"checksum      : {checksum}   <- compare against the NoSbc oracle run")
    for k, v in cycles.items():
        w(f"cycles ({k})   : {v}   <- compare against the NoSbc oracle run")
    w(f"crash/asserts : {'NONE' if not crashes else len(crashes)}")
    for c in crashes[:5]:
        w(f"   ! {c}")
    if any("SBC shadow" in c for c in crashes):
        w("   NOTE: a BankedStore shadow assert is a FALSE POSITIVE if the sim was driven without")
        w("         +dramsim (bare $SIM invocation). The checker is beat-blind, so under ideal-memory")
        w("         timing an evict-read and refill-write collide on one (set,way). Re-run through")
        w("         make run-binary / run_sbc.sh before calling it corruption. See CLAUDE.md.")
    fired = [(x, t(x)) for x in TRIPWIRES if t(x)]
    if fired:
        w("tripwires     : " + ", ".join(f"{k}={n}" for k, n in fired))
        w("                (debug tripwires fired - investigate even if the test says PASS)")
    w("")

    if cases:
        w("---- per-case results ----")
        for name, res, extra in cases:
            w(f"  {res:<4}  {name}" + (f"   {extra}" if extra else ""))
        nfail = sum(1 for c in cases if c[1] == "FAIL")
        w(f"  ---> {len(cases)-nfail}/{len(cases)} passed")
        w("")

    if counters:
        w("---- MMIO hardware counters (authoritative, read by the test) ----")
        for k, v in counters.items():
            w(f"  {k:<12}: {v}")
        w("")
        w("---- cross-check : MMIO counter vs [SBC] printf count ----")
        for name, tag in COUNTER_VS_TAG:
            if name in counters and tag in tags:
                w(f"  {name:<12} {counters[name]:>9}  vs  {tag:<12} {tags[tag]:>9}   "
                  f"{agree(tags[tag], counters[name])}")
        # aborted is the sum of both abort paths; the destination one alone under-reports.
        for nm in ("aborted", "abo"):
            if nm in counters:
                w(f"  {nm:<12} {counters[nm]:>9}  vs  ABORT-DST+MIG-DECLINE {ndst+nsrc:>9}   "
                  f"{agree(ndst + nsrc, counters[nm])}")
        w("")

    # ---------------------------------------------------------------- hit / miss
    acc  = pick(counters, "accesses")
    hit  = pick(counters, "hits")
    miss = pick(counters, "misses")
    if acc is not None and hit is not None and miss is None:
        miss = acc - hit
    elif hit is not None and miss is not None and acc is None:
        acc = hit + miss
    elif acc is not None and miss is not None and hit is None:
        hit = acc - miss

    sec_hit  = counters.get("secHits", nserve)
    sec_miss = counters.get("secMiss", nsmiss)
    sec_tot  = sec_hit + sec_miss

    w("---- hit / miss summary ----")
    w("  TOTAL L2 (every primary directory lookup)")
    if acc is not None:
        w(f"    accesses  : {acc}")
        w(f"    hits      : {hit}   ({100.0*hit/acc:.2f}%)" if acc else f"    hits      : {hit}")
        w(f"    misses    : {miss}  ({100.0*miss/acc:.2f}%)" if acc else f"    misses    : {miss}")
    else:
        w("    accesses  : n/a")
        w("    hits      : n/a")
        w("    misses    : n/a")
        w("    ^ no L2_Accesses / L2_Hits counter in this build.")
        w("      Build coder task 004 (ai-documents/coder/004-l2-hitrate-counters/) to fill this in.")
        outer = t("OUTER-A", 0)
        if outer:
            w(f"    proxy     : {outer} outer Acquires issued = misses that reached DRAM,")
            w("                but this OVER-counts (permission upgrades hit and still send an A)")
            w("                and UNDER-counts (a secondary hit never sends one). Not a miss rate.")
    w("")
    w("  SECONDARY (partner-set search, only reached after a home-set miss)")
    w(f"    searches  : {sec_tot}")
    if sec_tot:
        w(f"    hits      : {sec_hit}   ({100.0*sec_hit/sec_tot:.2f}%)")
        w(f"    misses    : {sec_miss}  ({100.0*sec_miss/sec_tot:.2f}%)")
    else:
        w(f"    hits      : {sec_hit}")
        w(f"    misses    : {sec_miss}")
    w("")
    if acc is not None and miss:
        # The research number: of everything that missed at home, how much did SBC rescue?
        w("  WHAT SBC RECOVERED")
        w(f"    secondary hits as a share of all misses : {sec_hit}/{miss} = "
          f"{100.0*sec_hit/miss:.2f}%")
        eff = hit + sec_hit
        w(f"    effective hit rate with SBC             : ({hit}+{sec_hit})/{acc} = "
          f"{100.0*eff/acc:.2f}%   (baseline {100.0*hit/acc:.2f}%)")
        w("")

    w("---- migration summary ----")
    w(f"  MIG-START (attempted)        : {nstart}")
    w(f"  MIG-COMMIT (committed)       : {ncom}")
    w(f"  ABORT-DST  (dst unusable)    : {ndst}")
    w(f"  {src_tag+' (src withdrawn)':<28} : {nsrc}")
    if nstart:
        w(f"  commit rate                  : {ncom}/{nstart} = {100.0*ncom/nstart:.1f}%")
    w("")
    w("  copy/commit invariant (must be equal - a copy with no commit overwrites data):")
    for k in ("SCU-START", "SCU-DONE", "COPY-DONE", "MIG-COMMIT"):
        w(f"    {k:<12}: {t(k, 0)}")
    if t("COPY-DONE", 0) != ncom:
        w(f"    !! {t('COPY-DONE',0)} copies vs {ncom} commits - "
          f"{abs(t('COPY-DONE',0)-ncom)} copies did not commit")
    else:
        w("    ok - every completed copy committed")
    w("")

    if nserve or nsmiss:
        tot = nserve + nsmiss
        w("---- serve-in-place (Phase 3R) ----")
        w(f"  SEC-SERVE (secondary hit)    : {nserve}")
        w(f"  SEC-MISS  (searched, absent) : {nsmiss}")
        w(f"  SEC-DEFER (eviction held)    : {t('SEC-DEFER', 0)}")
        if tot:
            w(f"  secondary hit rate           : {nserve}/{tot} = {100.0*nserve/tot:.2f}%")
        if d["sec_serve_kind"]:
            w("  serve kind (overlapping):")
            for k, n in d["sec_serve_kind"].most_common():
                w(f"    {k:<26} {n:8d}  ({100.0*n/max(nserve,1):.2f}% of serves)")
        if d["sec_serve_state"]:
            w("  parked-line state at serve:")
            for k, n in d["sec_serve_state"].most_common():
                w(f"    {k:<26} {n:8d}")
        if d["sec_pairs"]:
            w("  serves by (home -> partner):")
            for (s, p), n in d["sec_pairs"].most_common(12):
                w(f"    {s}->{p}: {n}")
        w("")

    if d["assess_eligible"]:
        el = d["assess_eligible"]
        tot = sum(el.values())
        w("---- EVICT-ASSESS : can the source victim migrate? (drives p) ----")
        w(f"  victims assessed  : {tot}")
        w("  victim class (fast + probe are both routes TO migration):")
        for k in ("fast path: clean + client-free", "probe path: clean, clients bit set",
                  "hard reject: dirty", "hard reject: already parked", "other"):
            if el.get(k):
                w(f"    {k:<36} {el[k]:8d}  ({100.0*el[k]/tot:.2f}%)")
        if d["assess_reason"]:
            w("  destination availability at assess time (independent axis):")
            for cause, n in d["assess_reason"].most_common():
                w(f"    {cause:<36} {n:8d}  ({100.0*n/tot:.2f}%)")
        if d["assess_set"]:
            w("  assessed by set:")
            for s, n in sorted(d["assess_set"].items()):
                w(f"    set {s}: {n}")
        w("")

    if d["decline_reason"]:
        w("---- MIG-DECLINE reason (source withdrew after assess) ----")
        for r, n in d["decline_reason"].most_common():
            w(f"  {r:<26} {n:8d}")
        w("")

    if d["dread_outcome"]:
        o = d["dread_outcome"]
        tot = sum(o.values())
        rej = o.get("reject", 0)
        w("---- DREAD-RESULT : why destination probes fail ----")
        w(f"  probes            : {tot}")
        for k in ("accept-free", "accept-evictable", "reject"):
            n = o.get(k, 0)
            w(f"  {k:<18}: {n:8d}  ({100.0*n/tot:.2f}%)")
        if rej:
            w("  reject cause (exact combination):")
            for cause, n in d["dread_cause"].most_common():
                w(f"    {cause:<26} {n:8d}  ({100.0*n/rej:.2f}% of rejects)")
            w("  reject cause (presence, overlapping):")
            for cause, n in d["dread_presence"].most_common():
                w(f"    {cause:<26} {n:8d}  ({100.0*n/rej:.2f}%)")
            w("  rejects by dstSet:")
            for st, n in sorted(d["dread_reject_set"].items()):
                w(f"    set {st}: {n}")
        w("")

    w("---- all [SBC] event tags ----")
    for tag, n in tags.most_common():
        mark = "  <-- tripwire" if tag in TRIPWIRES else ""
        w(f"  {n:6d}  {tag}{mark}")
    w("")

    for title, key, limit in (("MIG-START by (src->dst)", "start_pairs", 12),
                              ("ABORT-DST by (src->dst)", "abort_dst_pairs", 12),
                              ("COMMIT by (src->dst)",    "commits", 12),
                              ("SEC-MISS by (home->partner)", "sec_miss_pairs", 12),
                              ("MIGREQ by (src->dst) [phase 1]", "migreq_pairs", 12)):
        if d[key]:
            w(f"---- {title} ----")
            for (s, x), n in d[key].most_common(limit):
                w(f"  {s}->{x}: {n}")
            w("")

    if d["abort_src_reason"]:                                       # Phase 1 legacy
        w("---- ABORT-SRC reason [phase 1 logs only] ----")
        w(f"  {'count':>6}  state    dirty clients disp")
        for (st, dy, cl, dp), n in d["abort_src_reason"].most_common():
            w(f"  {n:6d}  {STATE.get(st,st):<7} {dy:>5} {cl:>7} {dp:>4}")
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
        verdict, cases, crashes, counters, checksum, cycles = scan_console(run_dir)
        text = render(run_dir, d, verdict, cases, crashes, counters, checksum, cycles)
        print(text)
        if os.path.isdir(run_dir):
            dest = os.path.join(run_dir, "sbc_stats.txt")
            with open(dest, "w") as f:
                f.write(text + "\n")
            print(f"\n[wrote {os.path.relpath(dest)}]")


if __name__ == "__main__":
    main()
