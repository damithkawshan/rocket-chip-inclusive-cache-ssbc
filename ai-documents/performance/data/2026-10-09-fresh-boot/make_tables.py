#!/usr/bin/env python3
"""Compute every numeric table of performance/fresh-boot-experiments-2026-10-09.md from windows.csv, and write them into
the report between its <!-- table:NAME --> ... <!-- /table:NAME --> markers.

usage: make_tables.py            (run build_windows_csv.py first when new windows exist)

Terms: memory accesses (memAcc) = memReads + memWrites at the L2; baseline = the programs alone, same SBC setting;
interference = pair - baseline; recovered = interference(off) - interference(on); share = recovered / interference(off).
Self-pairs (b64 + b64, b32 + b32) use the one alone window twice. Several windows of one setting are averaged.
"""
import csv, os, re, statistics as st
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
REPORT = os.path.join(HERE, "..", "..", "fresh-boot-experiments-2026-10-09.md")
W = list(csv.DictReader(open(os.path.join(HERE, "windows.csv"))))
for w in W:
    for k in ("memAcc", "accessA", "L2_Cycles", "migrations", "secondaryHit"):
        w[k] = int(w[k])
    w["hit"] = 100.0 * float(w["hit_rate"])

G = defaultdict(list)
for w in W:
    G[(w["experiment"], w["setting"], w["sbc"])].append(w)


def mean(exp, setting, sbc, key="memAcc"):
    g = G.get((exp, setting, sbc))
    return st.mean(x[key] for x in g) if g else None


def M(x, d=2):
    return "%.*f" % (d, x / 1e6)


def SM(x, d=2):
    return "%+.*f" % (d, x / 1e6)


def P(x, d=1):
    return "%+.*f%%" % (d, x)


def n0(x):
    return format(int(round(x)), ",")


LUA = "lu-alone-sweep"


def lu(b, sbc, n=512):
    return mean(LUA, "lu-p1-n%d-b%d-plru" % (n, b), sbc)


T = {}

# ───── LU alone sweep ─────
rows = ["| n | b | memory accesses, SBC off | SBC on | change | L2 hit rate off → on | migrations | L2 cycles change |", "|--:|--:|--:|--:|--:|--:|--:|--:|"]
pts = sorted({(int(m.group(1)), int(m.group(2))) for (e, s, _) in G if e == LUA for m in [re.match(r"lu-p1-n(\d+)-b(\d+)", s)]}, key=lambda p: (p[1] != 128 or p[0] == 512, p[0], p[1]))
for n, b in pts:
    s = "lu-p1-n%d-b%d-plru" % (n, b)
    off, on = G[(LUA, s, "off")][0], G[(LUA, s, "on")][0]
    rows.append("| %d | %d | %s | %s | **%s** | %.1f%% → %.1f%% | %s | %s |" % (
        n, b, n0(off["memAcc"]), n0(on["memAcc"]), P(100.0 * (on["memAcc"] - off["memAcc"]) / off["memAcc"]),
        off["hit"], on["hit"], n0(on["migrations"]), P(100.0 * (on["L2_Cycles"] - off["L2_Cycles"]) / off["L2_Cycles"], 2)))
T["lu_alone"] = "\n".join(rows)

# ───── pair.sh: LU b64 alone and LU b64 + streamcluster ─────
def arm(exp, setting, sbc):
    g = [x["memAcc"] for x in G[(exp, setting, sbc)]]
    sd = (100.0 * st.stdev(g) / st.mean(g)) if len(g) > 1 else None
    return st.mean(g), len(g), sd


rows = ["| setting | SBC off: mean (n, spread) | SBC on: mean (n, spread) | change | migrations (on) |", "|---|--:|--:|--:|--:|"]
for exp, s, name in (("lu-b64-alone-pairsh", "solo-lu", "LU b64 alone"), ("lu-b64+streamcluster-pairsh", "pair-lu+streamcluster", "LU b64 + streamcluster")):
    mo, no, so = arm(exp, s, "off")
    mn, nn, sn = arm(exp, s, "on")
    mig = st.mean(x["migrations"] for x in G[(exp, s, "on")])
    f = lambda m, n, sd: "%s M (n=%d%s)" % (M(m), n, ", sd %.2f%%" % sd if sd is not None else "")
    rows.append("| %s | %s | %s | **%s M (%s)** | %s |" % (name, f(mo, no, so), f(mn, nn, sn), SM(mn - mo), P(100.0 * (mn - mo) / mo), n0(mig)))
T["pairsh"] = "\n".join(rows)

# ───── all pairs: interference, SBC change, recovered ─────
pairs = []          # (group, name, pair_off, pair_on, base_off, base_on, migrations_on)


def lupair(a, b, exp, run=None):
    s = "lupair-p1-n512-b%d+b%d-plru" % (a, b)
    g_off = [x for x in G.get((exp, s, "off"), []) if run is None or x["run"] == run]
    g_on = [x for x in G.get((exp, s, "on"), []) if run is None or x["run"] == run]
    if not g_off:
        return None
    po = st.mean(x["memAcc"] for x in g_off)
    pn = st.mean(x["memAcc"] for x in g_on) if g_on else None
    mig = st.mean(x["migrations"] for x in g_on) if g_on else None
    return po, pn, lu(a, "off") + lu(b, "off"), lu(a, "on") + lu(b, "on"), mig


for (a, b) in ((64, 64), (32, 32), (64, 256), (64, 128), (128, 128)):
    r = lupair(a, b, "lu-pair-search")
    if r:
        pairs.append(("LU", "b%d + b%d" % (a, b)) + r)
for run in ("1", "2"):
    r = lupair(32, 128, "lu-b32+b128-pair", run)
    if r:
        pairs.append(("LU", "b32 + b128, run %s" % run) + r)
FG = "lu-fft-grid"
tags = sorted({m.group(1) for (e, s, _) in G if e == FG for m in [re.match(r"fft-p1-(m\d+(?:x\d+)?)-l4", s)] if m}, key=lambda t: int(re.match(r"m(\d+)", t).group(1)))
fft_name = lambda t: "m%s" % t[1:].replace("x", " ×")
for b in (32, 64, 128):
    for t in tags:
        s = "lufft-p1-n512-b%d+%s-l4-plru" % (b, t)
        if not G.get((FG, s, "off")):
            continue
        fo, fn = mean(FG, "fft-p1-%s-l4-plru" % t, "off"), mean(FG, "fft-p1-%s-l4-plru" % t, "on")
        po, pn = mean(FG, s, "off"), mean(FG, s, "on")
        mig = mean(FG, s, "on", "migrations")
        pairs.append(("FFT", "b%d + %s" % (b, fft_name(t)), po, pn, lu(b, "off") + fo, (lu(b, "on") + fn) if fn is not None else None, mig))

rows = ["| partner | LU + partner | interference, SBC off | SBC's change on the pair | SBC's change on the two alone (sum) | recovered | share |", "|---|---|--:|--:|--:|--:|--:|"]
done = []
mem_rows = ["| partner | LU + partner | pair, SBC off (M) | pair, SBC on (M) | pair, on vs off (M, %) | two alone (sum), SBC off (M) | two alone (sum), SBC on (M) | two alone, on vs off (M, %) |", "|---|---|--:|--:|--:|--:|--:|--:|"]
chg = lambda a, b: "%s M (%s)" % (SM(b - a), P(100.0 * (b - a) / a))
for grp, name, po, pn, bo, bn, mig in pairs:
    mem_rows.append("| %s | %s | %s | %s | %s | %s | %s | %s |" % (
        grp, name, M(po), M(pn) if pn is not None else "not run", ("**%s**" % chg(po, pn)) if pn is not None else "not run",
        M(bo), M(bn) if bn is not None else "-", chg(bo, bn) if bn is not None else "-"))
for grp, name, po, pn, bo, bn, mig in pairs:
    i_off = po - bo
    if pn is None or bn is None:
        rows.append("| %s | %s | %s M (%s) | not run | %s M | - | - |" % (grp, name, SM(i_off), P(100.0 * i_off / bo, 0), SM(bn - bo) if bn else "-"))
        continue
    rec = i_off - (pn - bn)
    share = 100.0 * rec / i_off
    done.append((grp, name, share, rec))
    rows.append("| %s | %s | %s M (%s) | %s M | %s M | %s M | **%.0f%%** |" % (grp, name, SM(i_off), P(100.0 * i_off / bo, 0), SM(pn - po), SM(bn - bo), M(rec), share))
mo, _, _ = arm("lu-b64+streamcluster-pairsh", "pair-lu+streamcluster", "off")
mn, _, _ = arm("lu-b64+streamcluster-pairsh", "pair-lu+streamcluster", "on")
lo, _, _ = arm("lu-b64-alone-pairsh", "solo-lu", "off")
ln, _, _ = arm("lu-b64-alone-pairsh", "solo-lu", "on")
mem_rows.append("| streamcluster | b64 (through `pair.sh`) | %s | %s | **%s** | %s* | %s* | %s* |" % (M(mo), M(mn), chg(mo, mn), M(lo), M(ln), chg(lo, ln)))
T["all_pairs_mem"] = "\n".join(mem_rows)
rows.append("| streamcluster | b64 (through `pair.sh`) | not measurable* | %s M | LU alone %s M; streamcluster alone not measured | - | - |" % (SM(mn - mo), SM(ln - lo)))
T["all_pairs"] = "\n".join(rows)
below = sum(1 for d in done if d[2] < 10)
T["share_count"] = "%d of %d recovered values are below 10%%. At 10%% or more: %s." % (
    below, len(done), ", ".join("LU %s (%.0f%%)" % (d[1].replace(",", "") if d[0] == "LU" else d[1].replace(" + ", " + FFT ", 1), d[2]) for d in done if d[2] >= 10) or "none")

# largest run-time change (L2 cycles) between the SBC-off and SBC-on windows of one setting
cyc = []
for (e, s_, sbc), g in G.items():
    if sbc == "off" and (e, s_, "on") in G:
        co = st.mean(x["L2_Cycles"] for x in g)
        cn = st.mean(x["L2_Cycles"] for x in G[(e, s_, "on")])
        cyc.append((abs(100.0 * (cn - co) / co), 100.0 * (cn - co) / co, s_))
cyc.sort(reverse=True)
T["cycles_max"] = "largest run-time change (L2 cycles, SBC on vs off, same setting) over all %d settings: %s (%s); the next largest is %s." % (
    len(cyc), P(cyc[0][1], 2), cyc[0][2], P(cyc[1][1], 2))

# ───── LU + FFT grids ─────
def grid(fn):
    rows = ["| LU \\ FFT | " + " | ".join(fft_name(t) for t in tags) + " |", "|---|" + "--:|" * len(tags)]
    for b in (32, 64, 128):
        cells = []
        for t in tags:
            r = next((p for p in pairs if p[0] == "FFT" and p[1] == "b%d + %s" % (b, fft_name(t))), None)
            cells.append(fn(r) if r else "pending")
        rows.append("| b%d | " % b + " | ".join(cells) + " |")
    return "\n".join(rows)


T["fft_interference"] = grid(lambda r: "%s M (%s)" % (SM(r[2] - r[4]), P(100.0 * (r[2] - r[4]) / r[4], 0)))
T["fft_recovered"] = grid(lambda r: ("%s M (%.0f%%)" % (M((r[2] - r[4]) - (r[3] - r[5])), 100.0 * ((r[2] - r[4]) - (r[3] - r[5])) / (r[2] - r[4]))) if r[3] is not None and r[5] is not None else "SBC on pending")
rows = ["| FFT alone | runs back to back | memory accesses, SBC off | SBC on | change | L2 hit rate | window (s) |", "|---|--:|--:|--:|--:|--:|--:|"]
for t in tags:
    s = "fft-p1-%s-l4-plru" % t
    off, on = G.get((FG, s, "off")), G.get((FG, s, "on"))
    if not off:
        continue
    k = t.split("x")[1] if "x" in t else "1"
    o = off[0]
    if on:
        n_ = on[0]
        rows.append("| m%s | %s | %s | %s | %s | %.1f%% | %.1f |" % (re.match(r"m(\d+)", t).group(1), k, n0(o["memAcc"]), n0(n_["memAcc"]), P(100.0 * (n_["memAcc"] - o["memAcc"]) / o["memAcc"]), o["hit"], o["L2_Cycles"] / 50e6))
T["fft_alone"] = "\n".join(rows)

# ───── migrations: how much each one saves ─────
rows = ["| setting | migrations (SBC on) | memory accesses saved by SBC | saved per migration |", "|---|--:|--:|--:|"]


def mig_row(name, mig, saved):
    rows.append("| %s | %s | %s M | %.1f |" % (name, n0(mig), M(saved), saved / mig if mig else 0))


mig_row("LU b64 alone (sweep)", G[(LUA, "lu-p1-n512-b64-plru", "on")][0]["migrations"], lu(64, "off") - lu(64, "on"))
mig_row("LU b64 alone (`pair.sh`)", st.mean(x["migrations"] for x in G[("lu-b64-alone-pairsh", "solo-lu", "on")]), lo - ln)
for grp, name, po, pn, bo, bn, mig in pairs:
    if pn is not None and mig and "b64" in name:
        mig_row("%s pair: %s" % (grp, name), mig, po - pn)
mig_row("streamcluster pair: b64 (`pair.sh`)", st.mean(x["migrations"] for x in G[("lu-b64+streamcluster-pairsh", "pair-lu+streamcluster", "on")]), mo - mn)
mig_row("LU b128 alone (sweep)", G[(LUA, "lu-p1-n512-b128-plru", "on")][0]["migrations"], lu(128, "off") - lu(128, "on"))
for grp, name, po, pn, bo, bn, mig in pairs:
    if pn is not None and mig and "b128" in name:
        mig_row("%s pair: %s" % (grp, name), mig, po - pn)
T["migrations"] = "\n".join(rows)

# ───── write into the report ─────
if os.path.exists(REPORT):
    doc = open(REPORT).read()
    for k in T:                                       # refuse to write if any marker pair is broken
        if doc.count("<!-- table:%s -->" % k) != doc.count("<!-- /table:%s -->" % k):
            raise SystemExit("marker pair for %s is unbalanced in the report: nothing written" % k)
    before = len(re.sub(r"<!-- table:(\w+) -->.*?<!-- /table:\1 -->", "", doc, flags=re.S))
    for k, v in T.items():
        # opening marker, anything (also nothing), the FIRST closing marker of the same name after it
        doc, n = re.subn(r"(<!-- table:%s -->).*?(<!-- /table:%s -->)" % (k, k), lambda m: m.group(1) + "\n" + v + "\n" + m.group(2), doc, flags=re.S)
        if n == 0:
            print("marker missing in report: %s" % k)
    after = len(re.sub(r"<!-- table:(\w+) -->.*?<!-- /table:\1 -->", "", doc, flags=re.S))
    if after != before:                               # text outside the markers must be untouched
        raise SystemExit("text outside the table markers would change (%d -> %d chars): nothing written" % (before, after))
    open(REPORT, "w").write(doc)
    print("tables written into %s" % os.path.relpath(REPORT, HERE))
else:
    for k, v in T.items():
        print("\n<!-- table:%s -->\n%s\n<!-- /table:%s -->" % (k, v, k))
