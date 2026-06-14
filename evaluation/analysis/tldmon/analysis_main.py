import argparse
import os
import sys
from pathlib import Path

# Add modules to path so we can import parser and plots
sys.path.append(str(Path(__file__).parent))

from parser import parse_and_save
import plots.plot_activity_heatmap
import plots.plot_active_set_count
import plots.plot_bucket_histogram
import plots.plot_per_source_activity
import plots.plot_phase_changes
import plots.plot_set_heatmap
import plots.plot_setstate
import plots.plot_summary_table
from data_loader import load_data, get_time_axis

AVAILABLE_PLOTS = {
    "activity_heatmap": plots.plot_activity_heatmap.plot,
    "active_set_count": plots.plot_active_set_count.plot,
    "bucket_histogram": plots.plot_bucket_histogram.plot,
    "per_source_activity": plots.plot_per_source_activity.plot,
    "phase_changes": plots.plot_phase_changes.plot,
    "set_heatmap": plots.plot_set_heatmap.plot,
    "setstate": plots.plot_setstate.plot,
    "summary_table": plots.plot_summary_table.plot
}

def main():
    p = argparse.ArgumentParser(description="Wrapper to parse TLDirMonitor dumps and generate specific plots.")
    p.add_argument("dump_file", help="Raw dump file (e.g. tlmon_..._stat.txt.0)")
    p.add_argument("--output-dir", default=None,
                   help="Output directory.  Defaults to "
                        "$INCLUSIVECACHE_RESULTS/<filename_no_ext> if set, "
                        "otherwise ./results/<filename_no_ext> (CWD-relative)")
    p.add_argument("--plots", default="all", help="Comma-separated list of plots to generate, or 'all'")
    
    args = p.parse_args()
    
    dump_path = Path(args.dump_file)
    if not dump_path.exists():
        sys.exit(f"ERROR: {dump_path} not found")

    if args.output_dir:
        output_dir = Path(args.output_dir)
    else:
        filename_no_ext = dump_path.name.split('.')[0]
        base_dir = Path(os.environ.get("INCLUSIVECACHE_RESULTS", "results"))
        output_dir = base_dir / filename_no_ext
        
    print(f"Parsing {dump_path} into {output_dir}...")
    parse_and_save(dump_path, output_dir)
    
    print("Loading decoded data...")
    df, meta = load_data(output_dir)
    xs, x_label = get_time_axis(meta['snapshots'], meta)
    
    if args.plots.lower() == "all":
        plots_to_run = list(AVAILABLE_PLOTS.keys())
    else:
        plots_to_run = [x.strip() for x in args.plots.split(',')]
        
    print(f"Generating plots: {', '.join(plots_to_run)}")
    
    for plot_name in plots_to_run:
        if plot_name not in AVAILABLE_PLOTS:
            print(f"WARNING: Plot '{plot_name}' not recognized. Skipping.")
            continue
            
        print(f"-> Generating {plot_name}...")
        func = AVAILABLE_PLOTS[plot_name]
        
        # Determine the signature of the plot function (some take xs, x_label, some don't)
        import inspect
        sig = inspect.signature(func)
        if "xs" in sig.parameters:
            func(df, meta, xs, x_label, output_dir)
        else:
            func(df, meta, output_dir)
            
    print("\nAll done!")

if __name__ == "__main__":
    main()
