import matplotlib
import matplotlib.pyplot as plt
from pathlib import Path
import argparse
import numpy as np

import sys
sys.path.append(str(Path(__file__).parent.parent))
from data_loader import load_data, get_time_axis, STYLE, DIRTY_BUCKETS, BUCKET_COLORS

def plot(df, meta, xs, x_label, output_dir):
    matplotlib.rcParams.update(STYLE)
    fig, axes = plt.subplots(2, 2, figsize=(14, 8))
    fig.suptitle("Cache state over time (per-set invalid count & dirty bucket)",
                 fontsize=13, fontweight="bold")

    # Since inv and dirty are duplicated per source, we just need the first occurrence for each set.
    per_set = df.groupby(['snap_idx', 'set_idx']).first()
    
    inv = per_set['inv'].unstack(level='set_idx').values.T # shape (n_sets, n_snaps)
    dirty = per_set['dirty'].unstack(level='set_idx').values.T

    extent = [xs[0], xs[-1] if len(xs) > 1 else xs[0] + 1, 0, meta["n_sets"]]

    im0 = axes[0, 0].imshow(inv, aspect="auto", origin="lower",
                            extent=extent, cmap="viridis", vmin=0, vmax=7,
                            interpolation="nearest")
    axes[0, 0].set_title("Invalid ways per set (capped at 7)")
    axes[0, 0].set_ylabel("Set index"); axes[0, 0].set_xlabel(x_label)
    fig.colorbar(im0, ax=axes[0, 0], pad=0.01)

    dirty_cmap = matplotlib.colors.ListedColormap(BUCKET_COLORS)
    dirty_norm = matplotlib.colors.BoundaryNorm([-0.5, 0.5, 1.5, 2.5, 3.5], dirty_cmap.N)
    im1 = axes[0, 1].imshow(dirty, aspect="auto", origin="lower",
                            extent=extent, cmap=dirty_cmap, norm=dirty_norm,
                            interpolation="nearest")
    axes[0, 1].set_title("Dirty bucket per set")
    axes[0, 1].set_ylabel("Set index"); axes[0, 1].set_xlabel(x_label)
    cbar = fig.colorbar(im1, ax=axes[0, 1], ticks=[0, 1, 2, 3], pad=0.01)
    cbar.ax.set_yticklabels(DIRTY_BUCKETS)

    inv_mean = inv.mean(axis=0)
    inv_max = inv.max(axis=0)
    axes[1, 0].plot(xs, inv_mean, label="mean inv/set",  linewidth=1.4)
    axes[1, 0].plot(xs, inv_max,  label="max inv/set",   linewidth=1.0,
                    alpha=0.7, linestyle="--")
    axes[1, 0].set_title("Invalid-way count aggregated across sets")
    axes[1, 0].set_xlabel(x_label); axes[1, 0].set_ylabel("Invalid ways")
    axes[1, 0].legend(fontsize=8)

    dirty_mean = dirty.mean(axis=0)
    dirty_max = dirty.max(axis=0)
    axes[1, 1].plot(xs, dirty_mean, label="mean dirty bucket", linewidth=1.4)
    axes[1, 1].plot(xs, dirty_max,  label="max dirty bucket",  linewidth=1.0,
                    alpha=0.7, linestyle="--")
    axes[1, 1].set_title("Dirty bucket aggregated across sets")
    axes[1, 1].set_xlabel(x_label); axes[1, 1].set_ylabel("Bucket (0..3)")
    axes[1, 1].set_ylim(-0.1, 3.1)
    axes[1, 1].legend(fontsize=8)

    fig.tight_layout()
    p = output_dir / "fig_setstate.png"
    fig.savefig(p, dpi=150, bbox_inches="tight")
    print(f"Saved {p}")

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("results_dir", help="Directory with data.csv and metadata.json")
    args = p.parse_args()
    
    results_dir = Path(args.results_dir)
    df, meta = load_data(results_dir)
    xs, x_label = get_time_axis(meta['snapshots'], meta)
    
    plot(df, meta, xs, x_label, results_dir)
