import json
import pandas as pd
from pathlib import Path
import numpy as np

def load_data(results_dir: Path):
    """Loads data.csv and metadata.json from results_dir"""
    with open(results_dir / "metadata.json") as f:
        meta = json.load(f)
    
    df = pd.read_csv(results_dir / "data.csv")
    return df, meta

def get_time_axis(n_snaps, meta, clock_mhz=50.0):
    interval = meta.get("interval")
    if interval and interval > 0:
        ms_per_snap = (interval / (clock_mhz * 1e6)) * 1000.0
        xs = np.arange(n_snaps) * ms_per_snap
        x_label = f"Time (ms) [{interval} cyc/snap @ {clock_mhz} MHz]"
        return xs, x_label
    return np.arange(n_snaps), "Snapshot index"

STYLE = {
    "figure.facecolor": "white",
    "axes.facecolor":   "#f8f8f8",
    "axes.grid":        True,
    "grid.alpha":       0.4,
    "axes.spines.top":  False,
    "axes.spines.right": False,
}

BUCKET_NAMES = ["idle", "cold", "warm", "hot"]
BUCKET_COLORS = ["#dddddd", "#74c476", "#fdae6b", "#d62728"]
DIRTY_BUCKETS = ["0", "1-2", "3-5", "6+"]
