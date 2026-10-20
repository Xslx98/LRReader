#!/usr/bin/env bash
# Perf budget check (audit 2026-10-13 PERF-04): judges the perf.json written
# by scripts/ci/smoke-perf.sh against scripts/ci/perf-budget.txt. Each line is
# `<metric> <budget> tol <N>%` (relative) or `tol +<N>` (absolute), `#` starts
# a comment; a metric fails above budget + tolerance. The file's `mode` line decides what a breach
# does: `record` prints a warning and exits 0 (while the emulator's variance is
# being established), `gate` exits 1. Flip it in its own dated commit.
# Usage, from the repo root: bash scripts/ci/check-perf.sh [perf.json]
# `--print` prints the measured numbers in budget-file format instead.
set -euo pipefail
cd "$(dirname "$0")/../.."

MODE=check
if [ "${1:-}" = "--print" ]; then
  MODE=print
  shift
fi
PERF="${1:-build/perf/perf.json}"
BUDGET=scripts/ci/perf-budget.txt

if [ ! -f "$PERF" ]; then
  echo "::error::check-perf: no $PERF (run scripts/ci/smoke-perf.sh)"
  exit 1
fi

# perf.json is flat {"key": number|string}; one "key value" per line.
MEASURED=$(tr -d '\r\n' < "$PERF" | sed -e 's/[{}]//g' -e 's/,/\n/g' |
  sed -nE 's/^ *"([^"]+)": *"?([^"]*)"? *$/\1 \2/p')

if [ "$MODE" = "print" ]; then
  echo "$MEASURED" | awk '$2 ~ /^[0-9.]+$/ { printf "%-32s %10s   tol 25%%\n", $1, $2 }'
  exit 0
fi

GATE=$(tr -d '\r' < "$BUDGET" | awk '$1 == "mode" { print $2 }')
case "$GATE" in record|gate) ;; *)
  echo "::error::check-perf: $BUDGET needs a 'mode record' or 'mode gate' line"
  exit 1 ;;
esac

LEVEL=warning
[ "$GATE" = "gate" ] && LEVEL=error
FAIL=0
printf '%-32s %10s %10s %8s\n' metric measured budget delta
while read -r metric budget tolword tol; do
  case "$metric" in ''|'#'*|mode) continue ;; esac
  if [ "$tolword" != "tol" ]; then
    echo "::error::check-perf: malformed budget line for $metric"
    exit 1
  fi
  value=$(echo "$MEASURED" | awk -v m="$metric" '$1 == m { print $2 }')
  if [ -z "$value" ]; then
    echo "::error::check-perf: budget metric '$metric' was not measured"
    FAIL=1
    continue
  fi
  case "$tol" in
    *%) limit=$(awk -v b="$budget" -v t="${tol%\%}" 'BEGIN { printf "%.2f", b * (1 + t / 100) }') ;;
    +*) limit=$(awk -v b="$budget" -v t="${tol#+}" 'BEGIN { printf "%.2f", b + t }') ;;
    *) echo "::error::check-perf: tolerance for $metric must be N% or +N"; exit 1 ;;
  esac
  delta=$(awk -v v="$value" -v b="$budget" 'BEGIN { printf "%+.1f%%", (v - b) * 100 / b }')
  printf '%-32s %10s %10s %8s\n' "$metric" "$value" "$budget" "$delta"
  if awk -v v="$value" -v l="$limit" 'BEGIN { exit !(v > l) }'; then
    echo "::$LEVEL::check-perf: $metric = $value exceeds budget $budget (+$tol = $limit)"
    FAIL=1
  fi
done < <(tr -d '\r' < "$BUDGET" | sed 's/[[:space:]]*#.*//')

if [ "$FAIL" -ne 0 ]; then
  if [ "$GATE" = "gate" ]; then
    echo "Raise a budget in $BUDGET only in the commit that explains the regression."
    exit 1
  fi
  echo "check-perf: budget breached in record mode (not failing the job)."
  exit 0
fi
echo "OK: perf metrics within budget (mode $GATE)."
