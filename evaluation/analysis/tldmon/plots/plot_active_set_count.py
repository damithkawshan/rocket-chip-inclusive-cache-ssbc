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
    
    n_sets = meta['n_sets']
    n_src = meta['n_src']
    n_snaps = meta['snapshots']
    
    # How many sets are active per source per snapshot
    active_count = df[df['activity'] > 0].groupby(['snap_idx', 'src_idx']).size().unstack(fill_value=0)
    # Ensure all sources and snaps exist
    active_count = active_count.reindex(np.arange(n_snaps), fill_value=0)
    for c in range(n_src):
        if c not in active_count: active_count[c] = 0
        
    src_names = [f"src{i}" for i in range(n_src)]

    fig, ax = plt.subplots(figsize=(12, 4))
    cmap = plt.get_cmap('tab10')
    
    for src_i in range(n_src):
        ax.plot(xs, active_count[src_i].values,
                label=src_names[src_i], color=cmap(src_i), linewidth=1)

    ax.set_xlabel(x_label)
    ax.set_ylabel("Active sets")
    ax.set_ylim(0, n_sets)
    ax.set_title("Active-set count per TL source over time")
    ax.axhline(n_sets, color='grey', linestyle=':', linewidth=0.8, label=f"max ({n_sets})")
    ax.legend(loc='upper right', fontsize=8)
    ax.grid(True, linestyle=':', linewidth=0.5)
    
    fig.tight_layout()
    p = output_dir / "fig_active_set_count.png"
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
