import sys
import re
import argparse
from pathlib import Path
import json
import numpy as np
import pandas as pd

_RE_KV = re.compile(r"(\w+)\s*=\s*(\S+)")

def _unpack_bits_le(words: np.ndarray) -> np.ndarray:
    n_snaps, n_words = words.shape
    bytes_view = np.ascontiguousarray(words).view(np.uint8).reshape(n_snaps, n_words * 8)
    return np.unpackbits(bytes_view, axis=1, bitorder="little")

def decode_buckets(words: np.ndarray, n_fields: int) -> np.ndarray:
    bits = _unpack_bits_le(words)
    lsb = bits[:, 0::2]
    msb = bits[:, 1::2]
    return ((msb << 1) | lsb)[:, :n_fields].astype(np.uint8)

def decode_setstate(words: np.ndarray, n_sets: int):
    bits = _unpack_bits_le(words).astype(np.uint16)
    inv   = np.zeros((bits.shape[0], n_sets), dtype=np.uint8)
    dirty = np.zeros((bits.shape[0], n_sets), dtype=np.uint8)
    for s in range(n_sets):
        b = s * 5
        inv[:, s]   = (bits[:, b]) | (bits[:, b+1] << 1) | (bits[:, b+2] << 2)
        dirty[:, s] = (bits[:, b+3]) | (bits[:, b+4] << 1)
    return inv, dirty

def parse_and_save(dump_path: Path, output_dir: Path):
    meta = {}
    header_seen = False
    data_lines = []

    with open(dump_path) as fh:
        for raw in fh:
            line = raw.rstrip()
            if not line:
                continue

            if line.startswith("#"):
                for k, v in _RE_KV.findall(line):
                    if v.lower() in ("yes", "no"):
                        meta[k] = (v.lower() == "yes")
                    else:
                        try:
                            meta[k] = int(v)
                        except ValueError:
                            meta[k] = v
                continue

            if not header_seen:
                if line.startswith("snap_idx"):
                    header_seen = True
                continue

            data_lines.append(line)

    if not header_seen or not data_lines:
        sys.exit(f"ERROR: No CSV data found in {dump_path}.")

    # Attempt to read num_words
    num_words = meta.get("num_words")
    if num_words is None:
        first_parts = data_lines[0].split(',')
        num_words = len(first_parts) - 1
        meta['num_words'] = num_words

    rows = []
    for line in data_lines:
        parts = line.split(",")
        if len(parts) != num_words + 1:
            continue # Skip truncated rows
        rows.append([int(p, 0) for p in parts[1:]])

    data = np.array(rows, dtype=np.uint64)
    meta["snapshots"] = len(data)
    meta["source_file"] = dump_path.name

    n_sets = meta.get("n_sets", 128)
    n_src  = meta.get("n_src", 4)
    aw     = meta.get("act_words", 16)
    sw     = meta.get("ss_words", 10)
    pw     = meta.get("probe_words", 16)
    n_fields = n_sets * n_src

    act_words   = data[:, :aw]
    ss_words    = data[:, aw:aw + sw]
    probe_words = data[:, aw + sw:aw + sw + pw]

    activity = decode_buckets(act_words,   n_fields).reshape(-1, n_sets, n_src)
    probe    = decode_buckets(probe_words, n_fields).reshape(-1, n_sets, n_src)
    inv, dirty = decode_setstate(ss_words, n_sets)

    # Extract info from filename if possible
    # e.g., tlmon_520_505_3600_5_10_stat.txt.0
    stem = dump_path.stem
    if stem.startswith("tlmon_"):
        parts = stem.split("_")
        if len(parts) >= 6:
            meta["interval"] = int(parts[3])
            meta["threshold"] = int(parts[4])

    # Save Metadata JSON
    output_dir.mkdir(parents=True, exist_ok=True)
    with open(output_dir / "metadata.json", "w") as f:
        json.dump(meta, f, indent=4)

    # Save Data CSV
    n_snaps = activity.shape[0]
    snap_indices = np.repeat(np.arange(n_snaps), n_sets * n_src)
    set_indices = np.tile(np.repeat(np.arange(n_sets), n_src), n_snaps)
    src_indices = np.tile(np.arange(n_src), n_snaps * n_sets)

    df = pd.DataFrame({
        'snap_idx': snap_indices,
        'src_idx': src_indices,
        'set_idx': set_indices,
        'activity': activity.flatten(),
        'probe': probe.flatten(),
        'inv': np.repeat(inv.flatten(), n_src),
        'dirty': np.repeat(dirty.flatten(), n_src)
    })
    
    df.to_csv(output_dir / "data.csv", index=False)
    print(f"Saved metadata.json and data.csv to {output_dir}")

if __name__ == "__main__":
    import os
    p = argparse.ArgumentParser(description="Parse TLDirMonitor dump to fully decoded CSV.")
    p.add_argument("dump_file", help="Raw dump file (e.g. tlmon_..._stat.txt.0)")
    p.add_argument("--output-dir", default=None,
                   help="Output directory.  Defaults to "
                        "$INCLUSIVECACHE_RESULTS/<filename_no_ext> if set, "
                        "otherwise ./results/<filename_no_ext> (CWD-relative)")
    args = p.parse_args()

    dump_path = Path(args.dump_file)
    if args.output_dir:
        output_dir = Path(args.output_dir)
    else:
        filename_no_ext = dump_path.name.split('.')[0]
        base_dir = Path(os.environ.get("INCLUSIVECACHE_RESULTS", "results"))
        output_dir = base_dir / filename_no_ext

    parse_and_save(dump_path, output_dir)
