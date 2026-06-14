import matplotlib
import matplotlib.pyplot as plt
from pathlib import Path
import argparse
import numpy as np

import sys
sys.path.append(str(Path(__file__).parent.parent))
from data_loader import load_data, STYLE

def plot(df, meta, output_dir):
    matplotlib.rcParams.update(STYLE)
    
    n_sets = meta['n_sets']
    n_src = meta['n_src']
    
    active = df['activity'] > 0
    frac_df = active.groupby([df['set_idx'], df['src_idx']]).mean().unstack(level='src_idx', fill_value=0)
    
    # Ensure all columns exist
    for c in range(n_src):
        if c not in frac_df: frac_df[c] = 0.0
    frac = frac_df[[i for i in range(n_src)]].values

    fig, ax = plt.subplots(figsize=(max(4, n_src * 1.5), max(6, n_sets * 0.06 + 2)))
    im = ax.imshow(frac, aspect='auto', vmin=0, vmax=1,
                   cmap='hot', origin='lower',
                   extent=[-0.5, n_src - 0.5, -0.5, n_sets - 0.5])
    fig.colorbar(im, ax=ax, label='Active fraction (snapshots)')
    ax.set_xlabel("TL Source")
    ax.set_ylabel("Set index")
    ax.set_title("Activity heatmap — fraction of snapshots each (set, src) exceeded threshold")
    
    src_names = [f"src{i}" for i in range(n_src)]
    ax.set_xticks(range(n_src))
    ax.set_xticklabels(src_names)

    # Annotate overall per-src activity rate
    for src_i in range(n_src):
        rate = frac[:, src_i].mean() * 100
        ax.text(src_i, n_sets + 0.5, f"{rate:.0f}%", ha='center', va='bottom',
                fontsize=8, color='steelblue')
    ax.text(-0.8, n_sets + 0.5, "mean:", ha='right', va='bottom', fontsize=8)

    fig.tight_layout()
    p = output_dir / "fig_activity_heatmap.png"
    fig.savefig(p, dpi=150, bbox_inches="tight")
    print(f"Saved {p}")

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("results_dir", help="Directory with data.csv and metadata.json")
    args = p.parse_args()
    
    results_dir = Path(args.results_dir)
    df, meta = load_data(results_dir)
    plot(df, meta, results_dir)
