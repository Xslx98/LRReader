#!/usr/bin/env bash
# APK size ratchet (audit 2026-10-13 PERF-04): the arm64 release APK's total,
# download-estimate, per-category compressed bytes and DEX method references
# must stay within scripts/ci/size-budget.txt (+ tolerance). A new dependency
# or an unshrunk resource then fails on its own line instead of showing up as
# "the APK grew" at release time. Raise a budget in the commit that needs it,
# saying why; never raise one to make an unrelated change pass.
#
# Needs a prior `./gradlew :app:assembleAppReleaseRelease` (CI signs with a
# throwaway key; a worktree without the keystore needs RELEASE_STORE_FILE).
# Usage, from the repo root: bash scripts/ci/check-apk-size.sh [apk]
# `--print` prints the current numbers in budget-file format instead.
set -euo pipefail
cd "$(dirname "$0")/../.."

MODE=check
if [ "${1:-}" = "--print" ]; then
  MODE=print
  shift
fi
APK="${1:-app/build/outputs/apk/appRelease/release/app-appRelease-release.apk}"
BUDGET=scripts/ci/size-budget.txt

if [ ! -f "$APK" ]; then
  echo "::error::check-apk-size: no APK at $APK (run ./gradlew :app:assembleAppReleaseRelease)"
  exit 1
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
APKANALYZER=$(ls "$SDK"/cmdline-tools/*/bin/apkanalyzer 2>/dev/null | sort -V | tail -n1 || true)
[ -n "$APKANALYZER" ] || APKANALYZER=$(ls "$SDK"/cmdline-tools/*/bin/apkanalyzer.bat 2>/dev/null | sort -V | tail -n1 || true)
if [ -z "$APKANALYZER" ]; then
  echo "::error::check-apk-size: apkanalyzer not found under $SDK/cmdline-tools"
  exit 1
fi

# Compressed bytes per category from the zip directory; the sum of the
# categories plus the signing block is the file size, so a growth shows up in
# exactly one line.
measure() {
  echo "apk.file $(stat -c %s "$APK")"
  echo "apk.download $("$APKANALYZER" apk download-size "$APK" | tr -d '\r' | tail -n1)"
  unzip -lv "$APK" | awk '
    NR > 3 && $NF != "" && $1 ~ /^[0-9]+$/ {
      name = $NF; size = $3
      if (name ~ /^classes[0-9]*\.dex$/) cat = "dex"
      else if (name ~ /^lib\//) cat = "lib"
      else if (name ~ /^res\// || name == "resources.arsc") cat = "res"
      else if (name ~ /^assets\//) cat = "assets"
      else cat = "other"
      sum[cat] += size
    }
    END {
      n = split("dex lib res assets other", cats, " ")
      for (i = 1; i <= n; i++) printf "%s %d\n", cats[i], sum[cats[i]] + 0
    }'
  echo "dex.method_refs $("$APKANALYZER" dex references "$APK" | tr -d '\r' | awk '{ s += $NF } END { print s + 0 }')"
}

MEASURED=$(measure)

if [ "$MODE" = "print" ]; then
  echo "$MEASURED"
  exit 0
fi

# The budget file is text=auto: strip CR so a Windows checkout parses too.
TOLERANCE=$(tr -d '\r' < "$BUDGET" | awk '$1 == "tolerance.percent" { print $2 }')
if [ -z "$TOLERANCE" ]; then
  echo "::error::check-apk-size: $BUDGET has no tolerance.percent line"
  exit 1
fi

FAIL=0
printf '%-18s %12s %12s %8s\n' metric measured budget delta
while read -r metric budget; do
  case "$metric" in ''|'#'*|tolerance.percent) continue ;; esac
  value=$(echo "$MEASURED" | awk -v m="$metric" '$1 == m { print $2 }')
  if [ -z "$value" ]; then
    echo "::error::check-apk-size: budget metric '$metric' is not measured"
    exit 1
  fi
  limit=$(awk -v b="$budget" -v t="$TOLERANCE" 'BEGIN { printf "%d", b * (1 + t / 100) }')
  delta=$(awk -v v="$value" -v b="$budget" 'BEGIN { printf "%+.1f%%", (v - b) * 100 / b }')
  printf '%-18s %12s %12s %8s\n' "$metric" "$value" "$budget" "$delta"
  if [ "$value" -gt "$limit" ]; then
    echo "::error::check-apk-size: $metric = $value exceeds budget $budget (+${TOLERANCE}% = $limit)"
    FAIL=1
  fi
done < <(tr -d '\r' < "$BUDGET")

if [ "$FAIL" -ne 0 ]; then
  echo "Raise the budget in $BUDGET only in the commit that explains the growth."
  exit 1
fi
echo "OK: release APK within size budget (tolerance ${TOLERANCE}%)."
