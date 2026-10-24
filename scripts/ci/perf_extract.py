"""Turn the perf smoke's raw numbers into perf.json + a Markdown table.

  perf_extract.py --trace TRACE --out perf.json --summary summary.md \
      --set startup.cold.total_ms=512 --set pss.list_kb=93986 ...
  perf_extract.py --median run-1/perf.json --median run-2/perf.json ... \
      --out perf.json --summary summary.md

Trace-section metrics (count, p50, p99 in ms) come from the Perfetto trace
via the trace processor (pip package `perfetto`, run with
`uv run --with perfetto` / `pip install perfetto`); the shell passes every
metric it measured itself with --set. Section names: see
com.lanraragi.reader.util.TraceSections.

--median combines the perf.json of several measurement passes: every numeric
metric becomes the median over the passes (what check-perf.sh gates on, so a
single outlier pass cannot fail the job), and `<metric>.runs` keeps the
per-pass values ("a/b/c") for the record.
"""
import argparse
import json
import statistics
import sys

SECTIONS = {
    "LRR.reader.decode": "reader.page_decode",
    "LRR.reader.glFrame": "reader.gl_frame",
    "LRR.reader.upload": "reader.upload",
    "LRR.list.bind": "list.bind",
}


def percentile(values, p):
    if not values:
        return None
    values = sorted(values)
    k = max(0, min(len(values) - 1, round((len(values) - 1) * p)))
    return values[k]


def section_metrics(trace_path):
    from perfetto.trace_processor import TraceProcessor
    tp = TraceProcessor(trace=trace_path)
    out = {}
    for name, key in SECTIONS.items():
        rows = tp.query(f"select dur from slice where name = '{name}'")
        ms = [r.dur / 1e6 for r in rows]
        out[f"{key}.count"] = len(ms)
        if ms:
            out[f"{key}.p50_ms"] = round(percentile(ms, 0.50), 1)
            out[f"{key}.p99_ms"] = round(percentile(ms, 0.99), 1)
    tp.close()
    return out


def is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def median_of_runs(runs):
    """Median per numeric metric over the passes; a metric missing from a
    pass is judged on the passes that measured it."""
    keys = sorted({k for run in runs for k in run})
    out = {}
    for key in keys:
        values = [run[key] for run in runs if key in run]
        if all(is_number(v) for v in values):
            med = statistics.median(values)
            if all(isinstance(v, int) for v in values) and float(med).is_integer():
                med = int(med)
            elif isinstance(med, float):
                med = round(med, 2)
            out[key] = med
            out[f"{key}.runs"] = "/".join(str(v) for v in values)
        else:
            out[f"{key}.runs"] = " ; ".join(str(v) for v in values)
    return out


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--trace")
    ap.add_argument("--out", required=True)
    ap.add_argument("--summary")
    ap.add_argument("--title", default="Release smoke perf (emulator)")
    ap.add_argument("--set", action="append", default=[], metavar="KEY=VALUE")
    ap.add_argument("--median", action="append", default=[], metavar="PERF_JSON")
    args = ap.parse_args(argv)

    metrics = {}
    if args.median:
        runs = []
        for path in args.median:
            with open(path, encoding="utf-8") as f:
                runs.append(json.load(f))
        metrics.update(median_of_runs(runs))
    for item in args.set:
        key, _, value = item.partition("=")
        try:
            metrics[key] = float(value) if "." in value else int(value)
        except ValueError:
            metrics[key] = value
    if args.trace:
        metrics.update(section_metrics(args.trace))

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2, sort_keys=True)
        f.write("\n")

    if args.median:
        lines = ["| metric | median | per pass |", "|---|---:|---:|"]
        lines += [f"| {k} | {metrics.get(k, '')} | {metrics[k + '.runs']} |"
                  for k in sorted(metrics) if not k.endswith(".runs")]
    else:
        lines = ["| metric | value |", "|---|---:|"]
        lines += [f"| {k} | {metrics[k]} |" for k in sorted(metrics)]
    table = "\n".join(lines) + "\n"
    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as f:
            f.write(f"### {args.title}\n\n" + table + "\n")
    sys.stdout.write(table)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
