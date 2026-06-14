#!/usr/bin/env python3
"""
generate_timeline_html.py — Generate an interactive HTML timeline visualisation
of TLDirMonitor activity buckets.

Reads a v2-format dump and produces a self-contained HTML file with:
  - Full heatmap grid (X=snapshot, Y=set) per source, coloured by bucket
  - Timeline slider / play button to animate through snapshots
  - Cross-section panel showing per-set bucket bars for the selected snapshot
  - Hover tooltips with set/snapshot/bucket details
  - Per-source tab switching

Usage:
    python3 generate_timeline_html.py <dump_file> [options]

    --n-sets N         Cache sets  (required if dump has no # header)
    --n-src  N         TL sources  (required if dump has no # header)
    --src-names S      Comma-separated source labels
    --interval CYC     Snapshot interval in cycles
    --clock-mhz F      Clock frequency MHz (default 50)
    --output FILE      Output HTML file (default: <dump_dir>/timeline.html)
"""

import sys
import json
import argparse
import re
from pathlib import Path

import numpy as np

# Reuse decoding helpers from TLDirMonitor parser module
_SCRIPT_DIR = Path(__file__).parent
_MODULES_DIR = _SCRIPT_DIR / "TLDirMonitor" / "modules"
sys.path.insert(0, str(_MODULES_DIR))
from parser import decode_buckets, decode_setstate  # noqa: E402


_RE_KV = re.compile(r"(\w+)\s*=\s*(\S+)")


def _default_act_words(n_sets, n_src):
  return ((n_sets * n_src * 2) + 63) // 64


def _default_ss_words(n_sets):
  return ((n_sets * 5) + 63) // 64


def parse_dump(dump_path: Path, cli_n_sets=None, cli_n_src=None):
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
        # Standard v2 dump CSV header.
        if line.startswith("snap_idx"):
          header_seen = True
          continue
        # Some captures may start directly with numeric rows.
        if line.split(",", 1)[0].isdigit():
          header_seen = True
          data_lines.append(line)
        continue

      data_lines.append(line)

  if not data_lines:
    sys.exit(f"ERROR: No CSV data rows found in {dump_path}.")

  num_words = meta.get("num_words")
  if num_words is None:
    first_parts = data_lines[0].split(",")
    num_words = len(first_parts) - 1
    meta["num_words"] = num_words

  rows = []
  for line in data_lines:
    parts = line.split(",")
    if len(parts) != num_words + 1:
      continue
    rows.append([int(p, 0) for p in parts[1:]])

  if not rows:
    sys.exit(f"ERROR: All rows were malformed/truncated in {dump_path}.")

  data = np.array(rows, dtype=np.uint64)

  n_sets = int(cli_n_sets if cli_n_sets is not None else meta.get("n_sets", 0))
  n_src = int(cli_n_src if cli_n_src is not None else meta.get("n_src", 0))
  if n_sets <= 0 or n_src <= 0:
    sys.exit("ERROR: n_sets/n_src unavailable. Pass --n-sets and --n-src or include dump metadata header.")

  aw = int(meta.get("act_words", _default_act_words(n_sets, n_src)))
  sw = int(meta.get("ss_words", _default_ss_words(n_sets)))
  pw = int(meta.get("probe_words", aw))
  required_words = aw + sw + pw

  if data.shape[1] < required_words:
    sys.exit(
      f"ERROR: dump row width ({data.shape[1]}) is smaller than expected words ({required_words}). "
      "Check --n-sets/--n-src or dump format."
    )

  n_fields = n_sets * n_src
  act_words = data[:, :aw]
  ss_words = data[:, aw:aw + sw]
  probe_words = data[:, aw + sw:aw + sw + pw]

  act = decode_buckets(act_words, n_fields).reshape(-1, n_sets, n_src)
  probe = decode_buckets(probe_words, n_fields).reshape(-1, n_sets, n_src)
  inv, dirty = decode_setstate(ss_words, n_sets)

  # Optional interval extraction from common filename pattern (tlmon_*_interval_*).
  stem = dump_path.stem
  if "interval" not in meta and stem.startswith("tlmon_"):
    parts = stem.split("_")
    if len(parts) >= 6:
      try:
        meta["interval"] = int(parts[3])
      except ValueError:
        pass

  meta["n_snaps"] = int(act.shape[0])
  meta["n_sets"] = n_sets
  meta["n_src"] = n_src
  return meta, act, inv, dirty, probe


def parse_args():
    p = argparse.ArgumentParser(description="Generate interactive HTML timeline")
    p.add_argument("dump_file", help="v2-format TLDirMonitor dump CSV")
    p.add_argument("--n-sets",    type=int,   default=None)
    p.add_argument("--n-src",     type=int,   default=None)
    p.add_argument("--src-names", default=None)
    p.add_argument("--interval",  type=int,   default=None)
    p.add_argument("--clock-mhz", type=float, default=50.0)
    p.add_argument("--output",    default=None,
                   help="Output HTML file (default: timeline.html in dump dir)")
    return p.parse_args()


HTML_TEMPLATE = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>TLDirMonitor Activity Timeline</title>
<style>
  @import url('https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap');

  :root {
    --bg:          #0f1117;
    --surface:     #1a1d27;
    --surface2:    #252833;
    --border:      #2e3140;
    --text:        #e0e0e6;
    --text-dim:    #8b8fa0;
    --accent:      #6c7aed;
    --accent-glow: rgba(108, 122, 237, 0.25);

    --idle:  #2a2d38;
    --cold:  #2d6a4f;
    --warm:  #e67e22;
    --hot:   #e63946;
  }

  * { margin: 0; padding: 0; box-sizing: border-box; }

  body {
    font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
    background: var(--bg);
    color: var(--text);
    min-height: 100vh;
    overflow-x: hidden;
  }

  /* ---- Header ---- */
  .header {
    padding: 20px 32px;
    background: linear-gradient(135deg, #1a1d27 0%, #1f2233 100%);
    border-bottom: 1px solid var(--border);
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .header h1 {
    font-size: 1.3rem;
    font-weight: 600;
    background: linear-gradient(90deg, #6c7aed, #a78bfa);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
  }
  .header .meta {
    font-size: 0.78rem;
    color: var(--text-dim);
  }

  /* ---- Source tabs ---- */
  .tabs {
    display: flex;
    gap: 4px;
    padding: 12px 32px 0;
    background: var(--surface);
    border-bottom: 1px solid var(--border);
  }
  .tab {
    padding: 8px 20px;
    border-radius: 8px 8px 0 0;
    cursor: pointer;
    font-size: 0.82rem;
    font-weight: 500;
    color: var(--text-dim);
    background: transparent;
    border: 1px solid transparent;
    border-bottom: none;
    transition: all 0.2s;
  }
  .tab:hover { color: var(--text); background: var(--surface2); }
  .tab.active {
    color: var(--accent);
    background: var(--bg);
    border-color: var(--border);
  }

  /* ---- Main layout ---- */
  .main {
    display: grid;
    grid-template-columns: 1fr 260px;
    gap: 0;
    height: calc(100vh - 130px);
  }

  /* ---- Heatmap panel ---- */
  .heatmap-panel {
    padding: 16px 24px;
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }
  .heatmap-container {
    position: relative;
    flex: 1;
    overflow: auto;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--surface);
  }
  #heatmapCanvas {
    display: block;
    image-rendering: pixelated;
  }
  .heatmap-label-x {
    text-align: center;
    font-size: 0.72rem;
    color: var(--text-dim);
    margin-top: 6px;
  }
  .heatmap-label-y {
    position: absolute;
    left: -40px;
    top: 50%;
    transform: rotate(-90deg) translateX(-50%);
    font-size: 0.72rem;
    color: var(--text-dim);
    white-space: nowrap;
  }

  /* ---- Timeline controls ---- */
  .controls {
    padding: 10px 24px 6px;
    display: flex;
    align-items: center;
    gap: 12px;
  }
  .play-btn {
    width: 36px;
    height: 36px;
    border-radius: 50%;
    border: 1px solid var(--accent);
    background: var(--accent-glow);
    color: var(--accent);
    cursor: pointer;
    font-size: 14px;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all 0.2s;
  }
  .play-btn:hover { background: var(--accent); color: #fff; }

  .slider-container { flex: 1; }
  #timeSlider {
    width: 100%;
    accent-color: var(--accent);
    cursor: pointer;
  }
  .time-label {
    font-size: 0.78rem;
    color: var(--text-dim);
    min-width: 140px;
    text-align: right;
    font-variant-numeric: tabular-nums;
  }

  /* speed control */
  .speed-select {
    background: var(--surface2);
    border: 1px solid var(--border);
    color: var(--text-dim);
    padding: 4px 8px;
    border-radius: 4px;
    font-size: 0.72rem;
    cursor: pointer;
  }

  /* ---- Cross-section panel ---- */
  .xsec-panel {
    background: var(--surface);
    border-left: 1px solid var(--border);
    padding: 16px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .xsec-title {
    font-size: 0.82rem;
    font-weight: 600;
    color: var(--accent);
    margin-bottom: 4px;
  }
  .xsec-stats {
    font-size: 0.72rem;
    color: var(--text-dim);
    margin-bottom: 8px;
    line-height: 1.5;
  }
  .xsec-bar-row {
    display: flex;
    align-items: center;
    gap: 4px;
    height: 4px;
    margin-bottom: 1px;
  }
  .xsec-set-label {
    font-size: 0.55rem;
    color: var(--text-dim);
    width: 24px;
    text-align: right;
    flex-shrink: 0;
  }
  .xsec-bar {
    height: 100%;
    border-radius: 1px;
    transition: width 0.15s;
  }

  /* ---- Legend ---- */
  .legend {
    display: flex;
    gap: 16px;
    padding: 6px 24px 10px;
    font-size: 0.72rem;
    color: var(--text-dim);
  }
  .legend-item {
    display: flex;
    align-items: center;
    gap: 5px;
  }
  .legend-swatch {
    width: 14px;
    height: 10px;
    border-radius: 2px;
  }

  /* ---- Tooltip ---- */
  #tooltip {
    position: fixed;
    background: var(--surface2);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 8px 12px;
    font-size: 0.75rem;
    color: var(--text);
    pointer-events: none;
    opacity: 0;
    transition: opacity 0.12s;
    z-index: 100;
    box-shadow: 0 4px 16px rgba(0,0,0,0.4);
  }
  #tooltip.show { opacity: 1; }
</style>
</head>
<body>

<div class="header">
  <h1>TLDirMonitor Activity Timeline</h1>
  <div class="meta" id="headerMeta"></div>
</div>

<div class="tabs" id="tabsBar"></div>

<div class="controls">
  <button class="play-btn" id="playBtn" title="Play / Pause">&#9654;</button>
  <div class="slider-container">
    <input type="range" id="timeSlider" min="0" max="0" value="0">
  </div>
  <select class="speed-select" id="speedSelect" title="Playback speed">
    <option value="200">5 fps</option>
    <option value="100" selected>10 fps</option>
    <option value="50">20 fps</option>
    <option value="33">30 fps</option>
  </select>
  <div class="time-label" id="timeLabel">Snapshot 0</div>
</div>

<div class="legend">
  <div class="legend-item"><div class="legend-swatch" style="background:var(--idle)"></div> 0 idle</div>
  <div class="legend-item"><div class="legend-swatch" style="background:var(--cold)"></div> 1 cold</div>
  <div class="legend-item"><div class="legend-swatch" style="background:var(--warm)"></div> 2 warm</div>
  <div class="legend-item"><div class="legend-swatch" style="background:var(--hot)"></div>  3 hot</div>
</div>

<div class="main">
  <div class="heatmap-panel">
    <div class="heatmap-container" id="heatmapBox">
      <canvas id="heatmapCanvas"></canvas>
    </div>
    <div class="heatmap-label-x">Snapshot →</div>
  </div>
  <div class="xsec-panel" id="xsecPanel">
    <div class="xsec-title">Cross-section</div>
    <div class="xsec-stats" id="xsecStats">Select a snapshot</div>
    <div id="xsecBars"></div>
  </div>
</div>

<div id="tooltip"></div>

<script>
// ---- Embedded data (injected by Python) ----
const DATA = __DATA_JSON__;
const META = __META_JSON__;

const BUCKET_COLORS = ['#2a2d38', '#2d6a4f', '#e67e22', '#e63946'];
const BUCKET_NAMES  = ['idle', 'cold', 'warm', 'hot'];

const nSnaps = META.n_snaps;
const nSets  = META.n_sets;
const nSrc   = META.n_src;
const srcNames  = META.src_names;
const intervalCyc = META.interval || null;
const clockMhz    = META.clock_mhz || 50;

let activeSrc = 0;
let playing = false;
let playTimer = null;
let currentSnap = 0;

// ---- Header meta ----
document.getElementById('headerMeta').textContent =
  `${nSnaps} snapshots · ${nSets} sets · ${nSrc} sources` +
  (intervalCyc ? ` · ${intervalCyc} cyc/snap @ ${clockMhz} MHz` : '');

// ---- Build tabs ----
const tabsBar = document.getElementById('tabsBar');
srcNames.forEach((name, i) => {
  const tab = document.createElement('div');
  tab.className = 'tab' + (i === 0 ? ' active' : '');
  tab.textContent = name;
  tab.onclick = () => switchSource(i);
  tabsBar.appendChild(tab);
});

function switchSource(idx) {
  activeSrc = idx;
  document.querySelectorAll('.tab').forEach((t, i) =>
    t.classList.toggle('active', i === idx));
  drawHeatmap();
  drawCrossSection(currentSnap);
}

// ---- Heatmap ----
const canvas = document.getElementById('heatmapCanvas');
const ctx = canvas.getContext('2d');
const heatmapBox = document.getElementById('heatmapBox');

// Cell size
const cellW = Math.max(1, Math.min(4, Math.floor(1200 / nSnaps)));
const cellH = Math.max(3, Math.min(6, Math.floor(500 / nSets)));

canvas.width  = nSnaps * cellW;
canvas.height = nSets  * cellH;

function drawHeatmap() {
  const srcData = DATA[activeSrc]; // srcData[snap][set]
  const imgData = ctx.createImageData(canvas.width, canvas.height);
  const pixels  = imgData.data;

  for (let snap = 0; snap < nSnaps; snap++) {
    const row = srcData[snap];
    for (let set = 0; set < nSets; set++) {
      const bucket = row[set];
      const hex = BUCKET_COLORS[bucket];
      const r = parseInt(hex.slice(1,3), 16);
      const g = parseInt(hex.slice(3,5), 16);
      const b = parseInt(hex.slice(5,7), 16);

      // Fill cellW × cellH block
      for (let dy = 0; dy < cellH; dy++) {
        for (let dx = 0; dx < cellW; dx++) {
          const px = snap * cellW + dx;
          const py = (nSets - 1 - set) * cellH + dy;  // set 0 at bottom
          const idx = (py * canvas.width + px) * 4;
          pixels[idx]     = r;
          pixels[idx + 1] = g;
          pixels[idx + 2] = b;
          pixels[idx + 3] = 255;
        }
      }
    }
  }

  ctx.putImageData(imgData, 0, 0);
  drawCursor(currentSnap);
}

function drawCursor(snap) {
  // Redraw heatmap then overlay cursor line
  // (for perf, we just draw the line on top)
  const x = snap * cellW;
  ctx.save();
  ctx.strokeStyle = 'rgba(108, 122, 237, 0.8)';
  ctx.lineWidth = Math.max(1, cellW);
  ctx.beginPath();
  ctx.moveTo(x + cellW / 2, 0);
  ctx.lineTo(x + cellW / 2, canvas.height);
  ctx.stroke();
  ctx.restore();
}

// ---- Cross-section panel ----
const xsecBars = document.getElementById('xsecBars');
const xsecStats = document.getElementById('xsecStats');

function buildCrossSectionDOM() {
  xsecBars.innerHTML = '';
  for (let set = nSets - 1; set >= 0; set--) {
    const row = document.createElement('div');
    row.className = 'xsec-bar-row';
    row.innerHTML = `<span class="xsec-set-label">${set}</span>
                     <div class="xsec-bar" id="bar-${set}" style="width:0;background:var(--idle)"></div>`;
    xsecBars.appendChild(row);
  }
}
buildCrossSectionDOM();

function drawCrossSection(snap) {
  const srcData = DATA[activeSrc];
  const row = srcData[snap];
  let counts = [0, 0, 0, 0];

  for (let set = 0; set < nSets; set++) {
    const b = row[set];
    counts[b]++;
    const bar = document.getElementById(`bar-${set}`);
    if (bar) {
      const widths = [0, 33, 66, 100];
      bar.style.width = widths[b] + '%';
      bar.style.background = BUCKET_COLORS[b];
    }
  }

  let timeStr = `Snapshot ${snap}`;
  if (intervalCyc) {
    const ms = snap * intervalCyc / (clockMhz * 1e3);
    timeStr += ` (${ms.toFixed(1)} ms)`;
  }

  xsecStats.innerHTML =
    `<strong>${timeStr}</strong><br>` +
    `<span style="color:${BUCKET_COLORS[0]}">■</span> idle: ${counts[0]} &nbsp; ` +
    `<span style="color:${BUCKET_COLORS[1]}">■</span> cold: ${counts[1]} &nbsp; ` +
    `<span style="color:${BUCKET_COLORS[2]}">■</span> warm: ${counts[2]} &nbsp; ` +
    `<span style="color:${BUCKET_COLORS[3]}">■</span> hot: ${counts[3]}`;
}

// ---- Slider ----
const slider = document.getElementById('timeSlider');
const timeLabel = document.getElementById('timeLabel');
slider.max = nSnaps - 1;

slider.addEventListener('input', () => {
  currentSnap = parseInt(slider.value);
  updateView();
});

function updateView() {
  let label = `Snapshot ${currentSnap} / ${nSnaps - 1}`;
  if (intervalCyc) {
    const ms = currentSnap * intervalCyc / (clockMhz * 1e3);
    label += `  (${ms.toFixed(1)} ms)`;
  }
  timeLabel.textContent = label;
  slider.value = currentSnap;

  // Redraw heatmap (full redraw) then cursor
  drawHeatmap();
  drawCrossSection(currentSnap);
}

// ---- Play / Pause ----
const playBtn = document.getElementById('playBtn');
const speedSelect = document.getElementById('speedSelect');

playBtn.onclick = () => {
  playing = !playing;
  playBtn.innerHTML = playing ? '&#9646;&#9646;' : '&#9654;';
  if (playing) startPlay();
  else stopPlay();
};

function startPlay() {
  const ms = parseInt(speedSelect.value);
  playTimer = setInterval(() => {
    currentSnap++;
    if (currentSnap >= nSnaps) currentSnap = 0;
    updateView();
  }, ms);
}

function stopPlay() {
  clearInterval(playTimer);
  playTimer = null;
}

speedSelect.onchange = () => {
  if (playing) { stopPlay(); startPlay(); }
};

// ---- Canvas hover tooltip ----
const tooltip = document.getElementById('tooltip');

canvas.addEventListener('mousemove', (e) => {
  const rect = canvas.getBoundingClientRect();
  const scaleX = canvas.width / rect.width;
  const scaleY = canvas.height / rect.height;
  const cx = (e.clientX - rect.left) * scaleX;
  const cy = (e.clientY - rect.top) * scaleY;

  const snap = Math.floor(cx / cellW);
  const set  = nSets - 1 - Math.floor(cy / cellH);

  if (snap >= 0 && snap < nSnaps && set >= 0 && set < nSets) {
    const b = DATA[activeSrc][snap][set];
    let text = `<strong>Set ${set}</strong> · Snap ${snap}`;
    if (intervalCyc) {
      const ms = snap * intervalCyc / (clockMhz * 1e3);
      text += ` (${ms.toFixed(1)} ms)`;
    }
    text += `<br>Bucket: <strong style="color:${BUCKET_COLORS[b]}">${b} ${BUCKET_NAMES[b]}</strong>`;
    text += `<br>Source: ${srcNames[activeSrc]}`;
    tooltip.innerHTML = text;
    tooltip.style.left = (e.clientX + 14) + 'px';
    tooltip.style.top  = (e.clientY - 10) + 'px';
    tooltip.classList.add('show');
  } else {
    tooltip.classList.remove('show');
  }
});

canvas.addEventListener('mouseleave', () => tooltip.classList.remove('show'));

// Click on heatmap to jump to that snapshot
canvas.addEventListener('click', (e) => {
  const rect = canvas.getBoundingClientRect();
  const scaleX = canvas.width / rect.width;
  const cx = (e.clientX - rect.left) * scaleX;
  const snap = Math.floor(cx / cellW);
  if (snap >= 0 && snap < nSnaps) {
    currentSnap = snap;
    updateView();
  }
});

// ---- Keyboard shortcuts ----
document.addEventListener('keydown', (e) => {
  if (e.key === ' ') { e.preventDefault(); playBtn.click(); }
  if (e.key === 'ArrowRight') { currentSnap = Math.min(nSnaps-1, currentSnap+1); updateView(); }
  if (e.key === 'ArrowLeft')  { currentSnap = Math.max(0, currentSnap-1); updateView(); }
  if (e.key === 'ArrowUp')    { currentSnap = Math.min(nSnaps-1, currentSnap+10); updateView(); }
  if (e.key === 'ArrowDown')  { currentSnap = Math.max(0, currentSnap-10); updateView(); }
});

// ---- Initial draw ----
updateView();
</script>
</body>
</html>"""


def main():
    args = parse_args()
    path = Path(args.dump_file)
    if not path.exists():
        sys.exit(f"ERROR: {path} not found")

    print(f"Parsing {path} ...")
    meta, act, inv, dirty, probe = parse_dump(path,
                                               cli_n_sets=args.n_sets,
                                               cli_n_src=args.n_src)

    n_snaps = meta['n_snaps']
    n_sets  = meta['n_sets']
    n_src   = meta['n_src']
    print(f"  {n_snaps} snapshots, {n_sets} sets, {n_src} sources")

    # Source names
    if args.src_names:
        src_names = [s.strip() for s in args.src_names.split(',')]
        if len(src_names) < n_src:
            src_names += [f"src{i}" for i in range(len(src_names), n_src)]
    else:
        src_names = [f"src{i}" for i in range(n_src)]

    interval_cycles = args.interval or meta.get('interval', None)

    # Build data: list of src → list of snapshots → list of set bucket values
    # To keep JSON compact, store as list[src][snap][set] of ints (0-3)
    print("Building JSON data ...")
    data = []
    for s in range(n_src):
        src_data = []
        for snap in range(n_snaps):
            src_data.append(act[snap, :, s].tolist())
        data.append(src_data)

    meta_json = {
        "n_snaps":    n_snaps,
        "n_sets":     n_sets,
        "n_src":      n_src,
        "src_names":  src_names,
        "interval":   interval_cycles,
        "clock_mhz":  args.clock_mhz,
    }

    # Generate HTML
    print("Generating HTML ...")
    html = HTML_TEMPLATE
    html = html.replace('__DATA_JSON__', json.dumps(data))
    html = html.replace('__META_JSON__', json.dumps(meta_json))

    # Output path
    if args.output:
        out_path = Path(args.output)
    else:
        out_path = path.parent / "timeline.html"

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(html)
    print(f"Saved: {out_path}")
    print(f"  Open in browser: file://{out_path.resolve()}")


if __name__ == '__main__':
    main()
