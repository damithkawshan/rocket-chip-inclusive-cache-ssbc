import matplotlib
import matplotlib.pyplot as plt
from pathlib import Path
import argparse

import sys
sys.path.append(str(Path(__file__).parent.parent))
from data_loader import load_data, get_time_axis, STYLE, BUCKET_NAMES, BUCKET_COLORS

def plot(df, meta, xs, x_label, output_dir):
    matplotlib.rcParams.update(STYLE)
    fig, axes = plt.subplots(2, 1, figsize=(14, 8), sharex=True)
    fig.suptitle("Per-set bucket over time (max across sources)",
                 fontsize=13, fontweight="bold")

    cmap = matplotlib.colors.ListedColormap(BUCKET_COLORS)
    norm = matplotlib.colors.BoundaryNorm([-0.5, 0.5, 1.5, 2.5, 3.5], cmap.N)

    # We need max across sources:
    # Group by snap_idx and set_idx, find max, then unstack to get snaps as rows and sets as columns
    per_set_act = df.groupby(['snap_idx', 'set_idx'])['activity'].max().unstack(level='set_idx').values.T
    per_set_pr  = df.groupby(['snap_idx', 'set_idx'])['probe'].max().unstack(level='set_idx').values.T

    extent = [xs[0], xs[-1] if len(xs) > 1 else xs[0] + 1, 0, meta["n_sets"]]

    for ax, arr, title in [(axes[0], per_set_act, "Activity"),
                           (axes[1], per_set_pr,  "Probe")]:
        im = ax.imshow(arr, aspect="auto", origin="lower",
                       extent=extent,
                       cmap=cmap, norm=norm, interpolation="nearest")
        ax.set_title(title)
        ax.set_ylabel("Set index")

        cbar = fig.colorbar(im, ax=ax, ticks=[0, 1, 2, 3], pad=0.01)
        cbar.ax.set_yticklabels(BUCKET_NAMES)

    axes[-1].set_xlabel(x_label)
    fig.tight_layout()
    
    p = output_dir / "fig_set_heatmap.png"
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
