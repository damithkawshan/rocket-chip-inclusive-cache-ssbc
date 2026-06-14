import matplotlib
import matplotlib.pyplot as plt
from pathlib import Path
import argparse
import numpy as np

import sys
sys.path.append(str(Path(__file__).parent.parent))
from data_loader import load_data, get_time_axis, STYLE

def plot(df, meta, xs, x_label, output_dir):
    matplotlib.rcParams.update(STYLE)
    n_src = meta["n_src"]
    fig, axes = plt.subplots(2, 1, figsize=(13, 7), sharex=True)
    fig.suptitle("Per-source hot/warm set counts over time",
                 fontsize=13, fontweight="bold")

    # Count how many sets are >=2 (warm+hot) and ==3 (hot) per source, per snap
    # Activity
    act_warm = df[df['activity'] >= 2].groupby(['snap_idx', 'src_idx']).size().unstack(fill_value=0)
    act_hot  = df[df['activity'] == 3].groupby(['snap_idx', 'src_idx']).size().unstack(fill_value=0)
    
    # Probe
    pr_warm = df[df['probe'] >= 2].groupby(['snap_idx', 'src_idx']).size().unstack(fill_value=0)
    pr_hot  = df[df['probe'] == 3].groupby(['snap_idx', 'src_idx']).size().unstack(fill_value=0)

    n_snaps = meta['snapshots']
    act_warm = act_warm.reindex(np.arange(n_snaps), fill_value=0)
    act_hot  = act_hot.reindex(np.arange(n_snaps), fill_value=0)
    pr_warm  = pr_warm.reindex(np.arange(n_snaps), fill_value=0)
    pr_hot   = pr_hot.reindex(np.arange(n_snaps), fill_value=0)

    # Ensure all src_idx exist
    for k in range(n_src):
        if k not in act_warm: act_warm[k] = 0
        if k not in act_hot:  act_hot[k] = 0
        if k not in pr_warm:  pr_warm[k] = 0
        if k not in pr_hot:   pr_hot[k] = 0

    for ax, warm_df, hot_df, title in [(axes[0], act_warm, act_hot, "Activity"),
                                       (axes[1], pr_warm, pr_hot,  "Probe")]:
        for k in range(n_src):
            ax.plot(xs, warm_df[k].values, label=f"src{k} warm+hot", linewidth=1.3)
            ax.plot(xs, hot_df[k].values,  label=f"src{k} hot",      linewidth=0.8,
                    linestyle="--", alpha=0.7)
        ax.set_title(f"{title}: # sets >= warm  (and # hot, dashed)")
        ax.set_ylabel("# sets")
        ax.legend(fontsize=8, loc="upper right", ncol=n_src)
        
    axes[-1].set_xlabel(x_label)
    fig.tight_layout()
    
    p = output_dir / "fig_per_source.png"
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
