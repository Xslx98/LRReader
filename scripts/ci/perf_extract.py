"""Turn the perf smoke's raw numbers into perf.json + a Markdown table.

  perf_extract.py --trace TRACE --out perf.json --summary summary.md \
      --set startup.cold.total_ms=512 --set pss.list_kb=93986 ...

Trace-section metrics (count, p50, p99 in ms) come from the Perfetto trace
via the trace processor (pip package `perfetto`, run with
`uv run --with perfetto` / `pip install perfetto`); the shell passes every
metric it measured itself with --set. Section names: see
com.lanraragi.reader.util.TraceSections.
"""
import argparse
import json
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


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--trace")
    ap.add_argument("--out", required=True)
    ap.add_argument("--summary")
    ap.add_argument("--set", action="append", default=[], metavar="KEY=VALUE")
    args = ap.parse_args(argv)

    metrics = {}
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

    lines = ["| metric | value |", "|---|---:|"]
    lines += [f"| {k} | {metrics[k]} |" for k in sorted(metrics)]
    table = "\n".join(lines) + "\n"
    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as f:
            f.write("### Release smoke perf (emulator)\n\n" + table + "\n")
    sys.stdout.write(table)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
