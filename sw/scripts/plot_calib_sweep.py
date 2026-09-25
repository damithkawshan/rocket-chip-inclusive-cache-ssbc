#!/usr/bin/env python3
"""plot_calib_sweep.py <calib_sweep.csv> [-o outdir]

Three figures from the l2_miss_calib sweep.

  1  envelope.png     does SBC help, over the whole (spare ways, overflow lines) plane
  2  cliffs.png       the cliff, one panel per spare value - does it move where the model says
  3  gain-source.png  where the gain comes from: the source set fitting, or parked lines being reused

Colour: blue/orange categorical pair and a blue-gray-red diverging ramp, both from the documented
palette, both validated (categorical pair: CVD dE 24.7, normal-vision 33.6, all checks PASS).
One y-axis per panel, no dual axes, no rainbow, gray neutral at zero.
"""
import csv, sys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap, TwoSlopeNorm
import numpy as np

SURFACE   = "#fcfcfb"
INK       = "#0b0b0b"
INK_2     = "#52514e"
GRID      = "#e3e2de"
SER_OFF   = "#eb6834"   # categorical slot 2, orange - migration OFF (the baseline)
SER_ON    = "#2a78d6"   # categorical slot 1, blue   - migration ON  (SBC)
BLUE_POLE = "#1c5cab"   # blue ramp 550
GRAY_MID  = "#f0efec"   # documented neutral midpoint
RED_POLE  = "#d03b3b"
DIVERGING = LinearSegmentedColormap.from_list("sbc_div", [BLUE_POLE, GRAY_MID, RED_POLE])

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
    "text.color": INK, "axes.labelcolor": INK, "axes.edgecolor": GRID,
    "xtick.color": INK_2, "ytick.color": INK_2,
    "axes.spines.top": False, "axes.spines.right": False,
    "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8,
    "font.size": 10, "axes.titlesize": 12, "axes.titleweight": "bold",
    "lines.linewidth": 2.0, "lines.markersize": 5,
    "figure.dpi": 140,
})

def load(path):
    with open(path) as f:
        return [{k: (float(v) if v not in ("", None) else 0.0) for k, v in r.items()} for r in csv.DictReader(f)]

def fig_envelope(rows, out):
    """Polarity over a 2-D grid -> diverging heatmap with a gray zero, plus the predicted boundary."""
    spares = sorted({int(r["spare"]) for r in rows}, reverse=True)
    overs  = sorted({int(r["overflow"]) for r in rows})
    grid = np.full((len(spares), len(overs)), np.nan)
    for r in rows:
        grid[spares.index(int(r["spare"])), overs.index(int(r["overflow"]))] = r["cycles_pct"]
    lim = max(3.0, float(np.nanmax(np.abs(grid))))
    fig, ax = plt.subplots(figsize=(1.05 + 0.52 * len(overs), 1.9 + 0.5 * len(spares)))
    im = ax.imshow(grid, cmap=DIVERGING, norm=TwoSlopeNorm(vmin=-lim, vcenter=0.0, vmax=lim),
                   aspect="auto", interpolation="nearest")
    ax.set_xticks(range(len(overs)), [str(o) for o in overs])
    ax.set_yticks(range(len(spares)), [str(s) for s in spares])
    ax.set_xlabel("overflow lines per hot set   (-p  minus  ways)")
    ax.set_ylabel("empty ways per cold set\n(ways minus -P)")
    ax.set_title("Does set balancing help?  L2 cycles, migration ON vs OFF", loc="left", pad=26)
    ax.text(0, 1.045, "blue = SBC faster · gray = no change · red = SBC slower       "
                      "the step line is the model: overflow = empty ways",
            transform=ax.transAxes, color=INK_2, fontsize=9)
    ax.set_axisbelow(False); ax.grid(False)
    # 2px surface gap between cells (the spacer rule) via minor-tick separators
    ax.set_xticks(np.arange(-0.5, len(overs), 1), minor=True)
    ax.set_yticks(np.arange(-0.5, len(spares), 1), minor=True)
    ax.grid(which="minor", color=SURFACE, linewidth=2)
    ax.tick_params(which="minor", length=0)
    # the model boundary: the last cell in each row where overflow <= spare
    xs, ys = [], []
    for i, s in enumerate(spares):
        k = [j for j, o in enumerate(overs) if o <= s]
        edge = (max(k) + 0.5) if k else -0.5
        xs += [edge, edge]; ys += [i - 0.5, i + 0.5]
    ax.plot(xs, ys, color=INK, linewidth=2.0, solid_joinstyle="miter", zorder=5)
    if grid.size <= 60:
        for i in range(len(spares)):
            for j in range(len(overs)):
                if not np.isnan(grid[i, j]):
                    ax.text(j, i, f"{grid[i, j]:+.1f}", ha="center", va="center", fontsize=7.5,
                            color=INK if abs(grid[i, j]) < 0.55 * lim else SURFACE)
    cb = fig.colorbar(im, ax=ax, pad=0.02, fraction=0.035)
    cb.set_label("L2 cycles, ON vs OFF (%)", color=INK_2)
    cb.outline.set_edgecolor(GRID)
    fig.tight_layout(); fig.savefig(out, bbox_inches="tight"); plt.close(fig)
    return out

def fig_cliffs(rows, out):
    """Change over a continuous x, two series -> lines, small multiples by spare, legend + labels."""
    spares = sorted({int(r["spare"]) for r in rows}, reverse=True)
    ways = 16
    fig, axes = plt.subplots(1, len(spares), figsize=(2.9 * len(spares), 3.5), sharey=True)
    axes = np.atleast_1d(axes)
    for ax, s in zip(axes, spares):
        pts = sorted([r for r in rows if int(r["spare"]) == s], key=lambda r: r["mp"])
        x = [r["mp"] for r in pts]
        ax.plot(x, [r["missrate_off"] for r in pts], color=SER_OFF, marker="o", label="migration OFF")
        ax.plot(x, [r["missrate_on"] for r in pts], color=SER_ON, marker="o", label="migration ON")
        cliff = 2 * ways - (ways - s)          # = ways + spare
        if x and min(x) <= cliff <= max(x):
            ax.axvline(cliff + 0.5, color=INK_2, linestyle=(0, (4, 3)), linewidth=1.2)
            ax.text(cliff + 0.7, ax.get_ylim()[1], " model cliff", color=INK_2, fontsize=8,
                    va="top", ha="left")
        ax.set_title(f"{s} empty way{'' if s == 1 else 's'}", loc="left")
        ax.set_xlabel("lines per hot set  (-p)")
        ax.set_axisbelow(True)
    axes[0].set_ylabel("L2 miss rate (%)")
    axes[0].legend(frameon=False, loc="lower right", fontsize=9)
    fig.suptitle("The cliff moves with the space available - miss rate vs hot-set working set",
                 x=0.005, ha="left", fontsize=12, fontweight="bold")
    fig.tight_layout(rect=(0, 0, 1, 0.94)); fig.savefig(out, bbox_inches="tight"); plt.close(fig)
    return out

def fig_gain_source(rows, out):
    """Composition -> stacked bars, 2px surface gap between segments."""
    win = [r for r in rows if r["fits_predicted"] and r["missrate_delta"] < 0]
    pool = win or [r for r in rows if r["missrate_delta"] < 0]
    if not pool:
        print("  (no point improved the miss rate - skipping gain-source.png)"); return None
    pool = sorted(pool, key=lambda r: (int(r["spare"]), r["mp"]))[:14]
    lbl = [f"{int(r['spare'])}/{int(r['mp'])}" for r in pool]
    prim = [max(0.0, r["primary_gain"]) for r in pool]
    sec  = [max(0.0, r["secondary_on"]) for r in pool]
    x = np.arange(len(pool))
    fig, ax = plt.subplots(figsize=(1.6 + 0.85 * len(pool), 3.9))
    ax.bar(x, prim, color=SER_ON, width=0.68, label="source set stopped thrashing (primary hits)")
    ax.bar(x, sec, bottom=[p + 0.06 for p in prim], color=SER_OFF, width=0.68,
           label="parked lines served (secondary hits)")
    ax.set_xticks(x, lbl)
    ax.set_xlabel("empty ways / lines per hot set")
    ax.set_ylabel("hit rate gained (percentage points)")
    ax.set_title("Where the gain actually comes from", loc="left", pad=22)
    tot_p, tot_s = sum(prim), sum(sec)
    if tot_p + tot_s:
        ax.text(0, 1.035, f"across these points, {100 * tot_p / (tot_p + tot_s):.0f}% of the gain is the "
                          f"source set fitting, {100 * tot_s / (tot_p + tot_s):.0f}% is reusing parked lines",
                transform=ax.transAxes, color=INK_2, fontsize=9)
    ax.legend(frameon=False, fontsize=9)
    ax.set_axisbelow(True)
    fig.tight_layout(); fig.savefig(out, bbox_inches="tight"); plt.close(fig)
    return out

def main(argv):
    if not argv:
        print(__doc__); return 2
    csv_path, outdir = argv[0], "."
    if "-o" in argv:
        outdir = argv[argv.index("-o") + 1]
    rows = load(csv_path)
    print(f"{len(rows)} points from {csv_path}")
    for f in (fig_envelope(rows, f"{outdir}/envelope.png"),
              fig_cliffs(rows, f"{outdir}/cliffs.png"),
              fig_gain_source(rows, f"{outdir}/gain-source.png")):
        if f: print("  wrote", f)
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
