import matplotlib
import matplotlib.pyplot as plt
from pathlib import Path
import argparse
import numpy as np

import sys
sys.path.append(str(Path(__file__).parent.parent))
from data_loader import load_data, get_time_axis, STYLE

def compute_phase_features(df, n_snaps, n_sets, n_src):
    n_fields = n_sets * n_src
    
    feats = []
    
    # Bucket fractions
    for b in range(4):
        # activity
        b_count = df[df['activity'] == b].groupby('snap_idx').size().reindex(np.arange(n_snaps), fill_value=0)
        feats.append(b_count.values / float(n_fields))
        # probe
        bp_count = df[df['probe'] == b].groupby('snap_idx').size().reindex(np.arange(n_snaps), fill_value=0)
        feats.append(bp_count.values / float(n_fields))
        
    # setstate (per set)
    per_set = df.groupby(['snap_idx', 'set_idx']).first().reset_index()
    inv_mean = per_set.groupby('snap_idx')['inv'].mean().reindex(np.arange(n_snaps), fill_value=0)
    dirty_mean = per_set.groupby('snap_idx')['dirty'].mean().reindex(np.arange(n_snaps), fill_value=0)
    
    feats.append(inv_mean.values / 7.0)
    feats.append(dirty_mean.values / 3.0)
    
    # per-source warm+hot
    for k in range(n_src):
        aw = df[(df['src_idx'] == k) & (df['activity'] >= 2)].groupby('snap_idx').size().reindex(np.arange(n_snaps), fill_value=0)
        feats.append(aw.values / float(n_sets))
        
    for k in range(n_src):
        pw = df[(df['src_idx'] == k) & (df['probe'] >= 2)].groupby('snap_idx').size().reindex(np.arange(n_snaps), fill_value=0)
        feats.append(pw.values / float(n_sets))
        
    return np.stack(feats, axis=1)

def compute_distances(features, metric="l1"):
    if features.shape[0] < 2:
        return np.zeros(features.shape[0], dtype=np.float64)
    d = np.diff(features, axis=0)
    if metric == "l1":
        dist = np.abs(d).sum(axis=1)
    elif metric == "l2":
        dist = np.sqrt((d * d).sum(axis=1))
    elif metric == "cosine":
        a = features[:-1]
        b = features[1:]
        num = (a * b).sum(axis=1)
        den = np.linalg.norm(a, axis=1) * np.linalg.norm(b, axis=1)
        with np.errstate(invalid="ignore", divide="ignore"):
            cos = np.where(den > 0, num / den, 1.0)
        dist = 1.0 - cos
    else:
        raise ValueError(f"unknown metric: {metric}")
    return np.concatenate([[0.0], dist])

def plot(df, meta, xs, x_label, output_dir, metric="l1", threshold=None, min_gap=1):
    matplotlib.rcParams.update(STYLE)
    
    n_snaps = meta['snapshots']
    n_sets = meta['n_sets']
    n_src = meta['n_src']
    
    features = compute_phase_features(df, n_snaps, n_sets, n_src)
    distances = compute_distances(features, metric)
    
    if threshold is None:
        active = distances[1:]
        if active.size == 0 or active.max() == 0:
            threshold = 0.0
            change_idx = np.empty(0, dtype=np.int64)
        else:
            threshold = float(active.mean() + 2.0 * active.std())
            raw = np.where(distances >= threshold)[0]
            if raw.size == 0 or min_gap <= 1:
                change_idx = raw
            else:
                pruned = [raw[0]]
                for i in raw[1:]:
                    if i - pruned[-1] >= min_gap:
                        pruned.append(i)
                change_idx = np.asarray(pruned, dtype=np.int64)
    else:
        raw = np.where(distances >= threshold)[0]
        if raw.size == 0 or min_gap <= 1:
            change_idx = raw
        else:
            pruned = [raw[0]]
            for i in raw[1:]:
                if i - pruned[-1] >= min_gap:
                    pruned.append(i)
            change_idx = np.asarray(pruned, dtype=np.int64)

    fig, axes = plt.subplots(3, 1, figsize=(13, 9), sharex=True,
                             gridspec_kw={"height_ratios": [2, 1, 2]})
    fig.suptitle(f"Phase-change detection (metric={metric})",
                 fontsize=13, fontweight="bold")

    # (top) distance trace
    ax = axes[0]
    ax.plot(xs, distances, color="#2c7fb8", linewidth=1.0, label="Δ(snap_t, snap_{t-1})")
    ax.axhline(threshold, color="#d62728", linestyle="--", linewidth=1.0,
               label=f"threshold = {threshold:.3f}")
    if change_idx.size > 0:
        ax.scatter(xs[change_idx], distances[change_idx], s=24,
                   color="#d62728", zorder=5, label=f"{change_idx.size} change(s)")
    ax.set_ylabel(f"{metric} distance")
    ax.set_title("Inter-snapshot feature distance")
    ax.legend(fontsize=8, loc="upper right")

    # (middle) cumulative
    ax = axes[1]
    cum = np.zeros_like(distances)
    if change_idx.size > 0:
        cum[change_idx] = 1
    ax.step(xs, np.cumsum(cum), where="post", color="#6a3d9a", linewidth=1.2)
    ax.set_ylabel("# phase changes")
    ax.set_title("Cumulative phase-change count")

    # (bottom) context
    ax = axes[2]
    n_fields = n_sets * n_src
    act_warm = df[df['activity'] >= 2].groupby('snap_idx').size().reindex(np.arange(n_snaps), fill_value=0).values
    probe_warm = df[df['probe'] >= 2].groupby('snap_idx').size().reindex(np.arange(n_snaps), fill_value=0).values
    
    ax.plot(xs, act_warm,   label="activity warm+",  color="#fdae6b", linewidth=1.2)
    ax.plot(xs, probe_warm, label="probe warm+",     color="#74c476", linewidth=1.2)
    for ci in change_idx:
        ax.axvline(xs[ci], color="#d62728", alpha=0.25, linewidth=0.8)
    ax.set_ylabel("# (set,src) fields")
    ax.set_xlabel(x_label)
    ax.set_title(f"Context (max possible = {n_fields})")
    ax.legend(fontsize=8, loc="upper right")

    fig.tight_layout()
    p = output_dir / "fig_phase_changes.png"
    fig.savefig(p, dpi=150, bbox_inches="tight")
    print(f"Saved {p}")

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("results_dir", help="Directory with data.csv and metadata.json")
    p.add_argument("--metric", default="l1")
    args = p.parse_args()
    
    results_dir = Path(args.results_dir)
    df, meta = load_data(results_dir)
    xs, x_label = get_time_axis(meta['snapshots'], meta)
    
    plot(df, meta, xs, x_label, results_dir, metric=args.metric)
