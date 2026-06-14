import matplotlib
import matplotlib.pyplot as plt
from pathlib import Path
import argparse
import numpy as np

import sys
sys.path.append(str(Path(__file__).parent.parent))
from data_loader import load_data, get_time_axis

def plot(df, meta, output_dir):
    n_sets = meta['n_sets']
    n_src = meta['n_src']
    n_snaps = meta['snapshots']
    
    threshold = meta.get("threshold", "unknown")
    interval = meta.get("interval", None)
    clock_mhz = 50.0

    src_names = [f"src{i}" for i in range(n_src)]

    # Activity stats
    active = df['activity'] > 0
    # Mean active fraction per source
    src_active_frac = active.groupby(df['src_idx']).mean().reindex(np.arange(n_src), fill_value=0).values
    
    # Active sets per source per snapshot
    active_sets_per_snap = active.groupby([df['snap_idx'], df['src_idx']]).sum().unstack(fill_value=0).reindex(np.arange(n_snaps), fill_value=0)
    for c in range(n_src):
        if c not in active_sets_per_snap: active_sets_per_snap[c] = 0

    # Transitions
    src_transitions = np.diff(active_sets_per_snap.values, axis=0)
    src_phase_changes = (np.abs(src_transitions) > n_sets * 0.1).sum(axis=0)

    total_active = active.groupby(df['snap_idx']).sum().mean() / n_sets # mean active-src count per set per snap
    
    # Set state stats
    per_set = df.groupby(['snap_idx', 'set_idx']).first()
    mean_inv = per_set['inv'].mean()
    mean_dirty = per_set['dirty'].mean()

    rows = []
    for i, name in enumerate(src_names):
        rows.append([
            name,
            f"{src_active_frac[i]*100:.1f}%",
            f"{active_sets_per_snap[i].mean():.1f} / {n_sets}",
            f"{src_phase_changes[i]}",
        ])

    col_labels = ["Source", "Active frac\n(all sets×snaps)", "Mean active\nsets/snap",
                  "Major phase\nchanges (>10%)"]
    col_widths  = [0.18, 0.22, 0.22, 0.22]

    if interval and interval > 0:
        total_ms = n_snaps * interval / (clock_mhz * 1e3)
        time_str = f"{total_ms:.1f} ms  ({n_snaps} snaps × {interval} cyc @ {clock_mhz} MHz)"
    else:
        time_str = f"{n_snaps} snapshots (interval unknown)"

    fig, ax = plt.subplots(figsize=(10, 3 + n_src * 0.4))
    ax.axis('off')

    tbl = ax.table(
        cellText=rows, colLabels=col_labels, colWidths=col_widths,
        loc='center', cellLoc='center')
    tbl.auto_set_font_size(False)
    tbl.set_fontsize(10)
    tbl.scale(1.4, 1.6)

    summary_lines = [
        f"Trace length   : {time_str}",
        f"CSC threshold  : {threshold}",
        f"Cache geometry : {n_sets} sets × {n_src} sources",
        f"Mean active src/set/snap : {total_active:.2f}",
        f"Mean invCapped (all sets) : {mean_inv:.2f} / 7",
        f"Mean dirty bucket (all sets): {mean_dirty:.2f} / 3",
    ]
    ax.set_title("Phase-detection monitor — summary statistics\n" +
                 "\n".join(summary_lines),
                 fontsize=9, loc='left', pad=14)

    fig.tight_layout()
    p = output_dir / "fig_summary_table.png"
    fig.savefig(p, dpi=150, bbox_inches="tight")
    print(f"Saved {p}")

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("results_dir", help="Directory with data.csv and metadata.json")
    args = p.parse_args()
    
    results_dir = Path(args.results_dir)
    df, meta = load_data(results_dir)
    plot(df, meta, results_dir)
