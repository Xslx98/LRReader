#!/usr/bin/env bash
# Coverage ratchet (audit 2026-10-04 C47 / REL-23): line coverage of each
# package tree listed in scripts/ci/coverage-floor.txt must not drop more
# than TOLERANCE percentage points below its floor. Raise a floor when a
# change raises coverage; never lower one to make a change pass.
#
# Needs the JaCoCo XML report: ./gradlew app:jacocoTestReport
# Usage, from the repo root: bash scripts/ci/check-coverage.sh [report.xml]
# `--print` prints the current numbers in floor-file format instead.
set -euo pipefail
cd "$(dirname "$0")/../.."

MODE=check
if [ "${1:-}" = "--print" ]; then
  MODE=print
  shift
fi
REPORT="${1:-app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml}"
FLOOR=scripts/ci/coverage-floor.txt
# Robolectric timing can move a handful of lines between runs.
TOLERANCE=0.5

if [ ! -f "$REPORT" ]; then
  echo "check-coverage: no JaCoCo report at $REPORT (run ./gradlew app:jacocoTestReport)" >&2
  exit 1
fi

# One package element per line; the package's own LINE counter is the last
# LINE counter before </package>. Plain awk (CI's mawk has no match arrays).
per_package() {
  sed -e 's/<package /\n<package /g' -e 's/<\/package>/<\/package>\n/g' "$REPORT" |
    awk '
      /^<package name="/ {
        name = $0
        sub(/^<package name="/, "", name)
        sub(/".*/, "", name)
        rest = $0
        missed = ""; covered = ""
        while (match(rest, /<counter type="LINE" missed="[0-9]+" covered="[0-9]+"\/>/)) {
          c = substr(rest, RSTART, RLENGTH)
          rest = substr(rest, RSTART + RLENGTH)
          m = c; sub(/.*missed="/, "", m); sub(/".*/, "", m)
          v = c; sub(/.*covered="/, "", v); sub(/".*/, "", v)
          missed = m; covered = v
        }
        if (missed != "") print name, missed, covered
      }'
}

PACKAGES="$(per_package)"

# Line coverage (percent, one decimal) of a package and its sub-packages.
coverage_of() {
  printf '%s\n' "$PACKAGES" | awk -v p="$1" '
    $1 == p || index($1, p "/") == 1 { m += $2; c += $3 }
    END { if (m + c == 0) print "none"; else printf "%.1f\n", 100 * c / (m + c) }'
}

if [ "$MODE" = print ]; then
  grep -v '^\s*#' "$FLOOR" | while read -r pkg _; do
    [ -n "$pkg" ] && echo "$pkg $(coverage_of "$pkg")"
  done
  exit 0
fi

fail=0
while read -r pkg floor; do
  case "$pkg" in ''|'#'*) continue ;; esac
  now="$(coverage_of "$pkg")"
  if [ "$now" = none ]; then
    echo "check-coverage: $pkg has no classes in the report" >&2
    fail=1
    continue
  fi
  if awk -v n="$now" -v f="$floor" -v t="$TOLERANCE" 'BEGIN { exit !(n + t < f) }'; then
    echo "check-coverage: $pkg line coverage $now% is below its floor $floor%" >&2
    fail=1
  else
    echo "check-coverage: $pkg $now% (floor $floor%)"
  fi
done < "$FLOOR"

if [ "$fail" -ne 0 ]; then
  echo "check-coverage: add tests for the code you changed; see CONTRIBUTING.md" >&2
  exit 1
fi
