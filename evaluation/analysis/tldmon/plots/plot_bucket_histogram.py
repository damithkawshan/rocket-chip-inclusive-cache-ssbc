import matplotlib
import matplotlib.pyplot as plt
from pathlib import Path
import numpy as np
import argparse

import sys
sys.path.append(str(Path(__file__).parent.parent))
from data_loader import load_data, get_time_axis, STYLE, BUCKET_NAMES, BUCKET_COLORS

def plot(df, meta, xs, x_label, output_dir):
    matplotlib.rcParams.update(STYLE)
    n_snaps = meta['snapshots']
    
    # -------------------------------------------------------------------
    # 1) Overall Bucket Distribution (Activity & Probe)
    # -------------------------------------------------------------------
    fig1, axes1 = plt.subplots(2, 1, figsize=(13, 7), sharex=True)
    fig1.suptitle("Bucket distribution per snapshot (across all (set,src))",
                  fontsize=13, fontweight="bold")

    # For activity
    act_counts = df.groupby(['snap_idx', 'activity']).size().unstack(fill_value=0)
    act_counts = act_counts.reindex(columns=[0, 1, 2, 3], fill_value=0)
    act_counts = act_counts.reindex(index=np.arange(n_snaps), fill_value=0).values.T # shape (4, n_snaps)

    # For probe
    pr_counts = df.groupby(['snap_idx', 'probe']).size().unstack(fill_value=0)
    pr_counts = pr_counts.reindex(columns=[0, 1, 2, 3], fill_value=0)
    pr_counts = pr_counts.reindex(index=np.arange(n_snaps), fill_value=0).values.T # shape (4, n_snaps)

    for ax, counts, title in [(axes1[0], act_counts, "Activity CSC buckets"),
                              (axes1[1], pr_counts,  "Probe CSC buckets")]:
        ax.stackplot(xs, counts, labels=BUCKET_NAMES, colors=BUCKET_COLORS, alpha=0.85)
        ax.set_title(title)
        ax.set_ylabel("# (set,src) fields")
        ax.legend(fontsize=8, loc="upper right")
        
    axes1[-1].set_xlabel(x_label)
    fig1.tight_layout()
    p1 = output_dir / "fig_bucket_histogram.png"
    fig1.savefig(p1, dpi=150, bbox_inches="tight")
    print(f"Saved {p1}")

    # -------------------------------------------------------------------
    # 2) Per-Source Activity Bucket Distribution
    # -------------------------------------------------------------------
    n_src = meta.get('n_src', 4)
    rows = 2 if n_src > 2 else 1
    cols = 2 if n_src > 1 else 1
    
    fig2, axes2 = plt.subplots(rows, cols, figsize=(13, 3.5 * rows), sharex=True, sharey=True)
    fig2.suptitle("Activity Bucket distribution per snapshot (separated by source)",
                  fontsize=13, fontweight="bold")
    
    # Flatten axes array for easy iteration if it's 2D or 1D
    axes_flat = np.array(axes2).flatten() if n_src > 1 else [axes2]

    for src in range(n_src):
        ax = axes_flat[src]
        
        # Filter for just this source, then group by snapshot and activity
        src_df = df[df['src_idx'] == src]
        src_counts = src_df.groupby(['snap_idx', 'activity']).size().unstack(fill_value=0)
        
        # Ensure all columns and snaps exist
        src_counts = src_counts.reindex(columns=[0, 1, 2, 3], fill_value=0)
        src_counts = src_counts.reindex(index=np.arange(n_snaps), fill_value=0).values.T # shape (4, n_snaps)
        
        ax.stackplot(xs, src_counts, labels=BUCKET_NAMES, colors=BUCKET_COLORS, alpha=0.85)
        ax.set_title(f"Activity CSC buckets (src={src})")
        ax.set_ylabel("# sets")
        if src == 0:
            ax.legend(fontsize=8, loc="upper right")
            
    if n_src > 1:
        for c in range(cols):
            if (rows - 1) * cols + c < len(axes_flat):
                axes_flat[(rows - 1) * cols + c].set_xlabel(x_label)
    else:
        axes_flat[0].set_xlabel(x_label)
        
    fig2.tight_layout()
    p2 = output_dir / "fig_bucket_histogram_per_src.png"
    fig2.savefig(p2, dpi=150, bbox_inches="tight")
    print(f"Saved {p2}")

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("results_dir", help="Directory with data.csv and metadata.json")
    args = p.parse_args()
    
    results_dir = Path(args.results_dir)
    df, meta = load_data(results_dir)

    xs, x_label = get_time_axis(meta['snapshots'], meta)
    
    plot(df, meta, xs, x_label, results_dir)
