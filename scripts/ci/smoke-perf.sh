#!/usr/bin/env bash
# Perf measurement of the release APK on the emulator (audit 2026-10-13
# PERF-04): fresh install + connect to the mock LANraragi, then cold-start
# median, PSS at three checkpoints, HWUI jank on list flings and the reader's
# trace sections from a Perfetto trace. Writes perf.json, the trace and the
# mock log into $OUT_DIR and a table into $GITHUB_STEP_SUMMARY when set.
# scripts/ci/check-perf.sh judges perf.json against perf-budget.txt.
# Usage: smoke-perf.sh <apk> [out-dir]
# Env: PYTHON (default python3). The trace needs the `perfetto` package:
#      PYTHON="uv run --with perfetto python" locally; CI pip-installs it.
set -euo pipefail
cd "$(dirname "$0")/../.."

APK="${1:?usage: smoke-perf.sh <apk> [out-dir]}"
OUT_DIR="${2:-build/perf}"
PKG="com.lanraragi.reader"
LAUNCHER="$PKG/$PKG.ui.splash.SplashActivity"
PYTHON="${PYTHON:-python3}"
MOCK_PORT=3939
MOCK_URL="http://127.0.0.1:$MOCK_PORT"
COLD_STARTS=5
FLINGS=6
PAGE_TURNS=20
TRACE_DEV=/data/misc/perfetto-traces/lrr-smoke.perfetto-trace
TRACE_SECONDS=120

ui() { $PYTHON scripts/ci/smoke_ui.py "$@"; }
# Git Bash would rewrite /data/... device paths into Windows paths.
export MSYS_NO_PATHCONV=1
mkdir -p "$OUT_DIR"
SETS=()
metric() { SETS+=(--set "$1=$2"); echo "perf: $1 = $2"; }
pss_kb() { adb shell dumpsys meminfo -s "$PKG" | tr -d '\r' | awk '/TOTAL PSS:/ { print $3; exit }'; }

if (exec 3<>"/dev/tcp/127.0.0.1/$MOCK_PORT") 2>/dev/null; then
  echo "::error::smoke-perf: port $MOCK_PORT is already in use on the host"
  exit 1
fi
MOCK_LOG="$OUT_DIR/mock.log"
# 30 big archives + 35 chapters + 3 small = 68 rows, two list pages, so the
# flings scroll through rebinds and a page-2 load; MOCK_BIG_PAGES=40 is the
# archive the reader opens.
MOCK_PORT=$MOCK_PORT MOCK_BIG_PAGES=40 MOCK_BIG_COUNT=30 MOCK_CHAPTERS=1 \
  $PYTHON -u scripts/ci/mock_lrr.py > "$MOCK_LOG" 2>&1 &
MOCK_PID=$!
trap 'kill $MOCK_PID 2>/dev/null || true; adb reverse --remove "tcp:$MOCK_PORT" >/dev/null 2>&1 || true' EXIT
adb reverse "tcp:$MOCK_PORT" "tcp:$MOCK_PORT" > /dev/null
sleep 2

# ---- Fresh state: install, connect (the reader must start at page 1) ------
adb wait-for-device
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r "$APK" > /dev/null
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
adb shell am start -W -n "$LAUNCHER" > /dev/null
ui connect "$PKG" "$MOCK_URL"
sleep 3

# ---- Cold start: one warm-up launch discarded, then the median of N -------
adb shell am force-stop "$PKG"
adb shell am start -W -n "$LAUNCHER" > /dev/null
sleep 5
TIMES=()
for _ in $(seq 1 $COLD_STARTS); do
  adb shell am force-stop "$PKG"
  sleep 2
  adb logcat -c
  T=$(adb shell am start -W -n "$LAUNCHER" | tr -d '\r' | awk '/^TotalTime:/ { print $2 }')
  TIMES+=("$T")
  sleep 6
done
# The last launch's time-to-fully-drawn ("+1s234ms" or "+345ms"), reported
# when the first list page lands; absent when the list did not load.
FULLY=$(adb logcat -d | tr -d '\r' | awk -v pkg="$PKG" '
  $0 ~ "Fully drawn " pkg "/" {
    t = $NF; sub(/^\+/, "", t); s = 0
    if (t ~ /^[0-9]+s[0-9]+ms$/) { split(t, p, "s"); s = p[1]; t = p[2] }
    sub(/ms$/, "", t); last = s * 1000 + t
  }
  END { if (last != "") print last }')
SORTED=$(printf '%s\n' "${TIMES[@]}" | sort -n)
MEDIAN=$(echo "$SORTED" | awk '{ a[NR] = $1 } END { print a[int((NR + 1) / 2)] }')
metric startup.cold.total_ms "$MEDIAN"
metric startup.cold.samples "$(echo "$SORTED" | tr '\n' '/' | sed 's#/$##')"
[ -n "$FULLY" ] && metric startup.cold.fully_drawn_ms "$FULLY"

# ---- Trace list flings + reader -----------------------------------------
ui wait resource-id "$PKG:id/thumb_new" 60
adb shell perfetto --background -o "$TRACE_DEV" -t "${TRACE_SECONDS}s" -a "$PKG" gfx view sched freq > /dev/null
sleep 2
metric pss.list_kb "$(pss_kb)"

adb shell dumpsys gfxinfo "$PKG" reset > /dev/null
read -r W H < <(adb shell wm size | tr -d '\r' | sed -nE 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/p')
X=$((W / 2)); Y1=$((H * 3 / 4)); Y2=$((H / 4))
for i in $(seq 1 $FLINGS); do
  if [ $((i % 2)) -eq 1 ]; then adb shell input swipe "$X" "$Y1" "$X" "$Y2" 120
  else adb shell input swipe "$X" "$Y2" "$X" "$Y1" 120; fi
  sleep 1.5
done
GFX=$(adb shell dumpsys gfxinfo "$PKG" | tr -d '\r')
metric list.frames "$(echo "$GFX" | awk -F': ' '/^Total frames rendered/ { print $2; exit }')"
metric list.jank_pct "$(echo "$GFX" | sed -nE 's/^Janky frames: [0-9]+ \(([0-9.]+)%\).*/\1/p' | head -n1)"
metric list.p90_ms "$(echo "$GFX" | sed -nE 's/^90th percentile: ([0-9]+)ms.*/\1/p' | head -n1)"
metric list.p99_ms "$(echo "$GFX" | sed -nE 's/^99th percentile: ([0-9]+)ms.*/\1/p' | head -n1)"

# Back at the top, open the 40-page archive and turn pages from page 1.
adb shell input keyevent KEYCODE_MOVE_HOME
sleep 1
if ui has text "Mock Big Archive 1"; then ui tap text "Mock Big Archive 1"
else ui tap resource-id "$PKG:id/thumb_new"; fi
ui tap resource-id "$PKG:id/read" 30
sleep 5
TX=$((W / 10)); TY=$((H / 2))
for _ in $(seq 1 $PAGE_TURNS); do
  adb shell input tap "$TX" "$TY"
  sleep 0.8
done
sleep 2
metric pss.reader_kb "$(pss_kb)"

adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell input keyevent KEYCODE_BACK
sleep 10
metric pss.after_reader_kb "$(pss_kb)"

# Stop the trace early instead of waiting out the duration.
adb shell "kill -INT \$(pidof perfetto)" || true
until [ -z "$(adb shell pidof perfetto | tr -d '\r')" ]; do sleep 1; done
adb pull "$TRACE_DEV" "$OUT_DIR/lrr-smoke.perfetto-trace" > /dev/null
metric reader.pages_served "$(grep -c '/page' "$MOCK_LOG" || true)"

# No `-I`: CI installs the perfetto package with `pip install --user`, which
# isolated mode would hide.
$PYTHON scripts/ci/perf_extract.py --trace "$OUT_DIR/lrr-smoke.perfetto-trace" \
  --out "$OUT_DIR/perf.json" ${GITHUB_STEP_SUMMARY:+--summary "$GITHUB_STEP_SUMMARY"} "${SETS[@]}"
echo "perf: wrote $OUT_DIR/perf.json"
