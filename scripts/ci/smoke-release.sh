#!/usr/bin/env bash
# Runtime smoke of the minified release APK on a running emulator/device:
# install, cold start, rotate, background and foreground again, then — against
# the mock LANraragi in scripts/ci/mock_lrr.py — connect, open the list, a
# detail page and the reader, turn 20 pages and come back; fail on any crash
# of the app process. Unit tests run the debug build only, and R8 keep-rule
# mistakes (a stripped serializer, a reflectively created class) crash only
# the release build: v1.15.1 shipped a launch crash with green CI, and the
# first-screen-only smoke never reached the reader (audit 2026-10-13).
# Usage: smoke-release.sh <apk>
# Env: PYTHON (default python3) runs the mock server and the UI driver.
set -euo pipefail
cd "$(dirname "$0")/../.."

APK="${1:?usage: smoke-release.sh <apk>}"
PKG="com.lanraragi.reader"
LAUNCHER="$PKG/$PKG.ui.splash.SplashActivity"
PYTHON="${PYTHON:-python3}"
# The mock is reached from the device through `adb reverse`: works on any
# emulator or device, independent of the emulator NAT (10.0.2.2).
MOCK_PORT=3939
MOCK_URL="http://127.0.0.1:$MOCK_PORT"
PAGE_TURNS=20

ui() { $PYTHON scripts/ci/smoke_ui.py "$@"; }

adb wait-for-device
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r "$APK"
# The first start asks for POST_NOTIFICATIONS; the system dialog would sit
# over the connect form and skew the cold-start timing.
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
adb logcat -c

crashed() {
  adb logcat -d -b crash,main | grep -E "FATAL EXCEPTION|Process: $PKG|ANR in $PKG" || true
}
check() {
  local step="$1"
  sleep "${2:-8}"
  local found
  found=$(crashed)
  if [ -n "$found" ]; then
    echo "::error::release smoke: crash after '$step'"
    adb logcat -d -b crash,main | grep -A40 -E "FATAL EXCEPTION|ANR in $PKG" | head -80
    exit 1
  fi
  if [ -z "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then
    echo "::error::release smoke: $PKG is not running after '$step'"
    adb logcat -d | tail -80
    exit 1
  fi
  echo "OK: $step"
}

adb shell am start -W -n "$LAUNCHER"
check "cold start" 15
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
check "rotate to landscape"
adb shell settings put system user_rotation 0
check "rotate back"
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell am start -W -n "$LAUNCHER"
check "return from background"

# ---- Server flow: connect → list → detail → reader → back ----------------
# A leftover mock on the port would serve the app while this run's log stays
# empty (Python's HTTPServer sets SO_REUSEADDR, so the bind still succeeds).
if (exec 3<>"/dev/tcp/127.0.0.1/$MOCK_PORT") 2>/dev/null; then
  echo "::error::release smoke: port $MOCK_PORT is already in use on the host"
  exit 1
fi
MOCK_LOG=$(mktemp)
MOCK_PORT=$MOCK_PORT MOCK_BIG_PAGES=40 MOCK_CHAPTERS=1 \
  $PYTHON -u scripts/ci/mock_lrr.py > "$MOCK_LOG" 2>&1 &
MOCK_PID=$!
trap 'kill $MOCK_PID 2>/dev/null || true; adb reverse --remove "tcp:$MOCK_PORT" >/dev/null 2>&1 || true' EXIT
adb reverse "tcp:$MOCK_PORT" "tcp:$MOCK_PORT"
sleep 2
if ! kill -0 "$MOCK_PID" 2>/dev/null; then
  echo "::error::release smoke: mock LANraragi did not start"
  cat "$MOCK_LOG"
  exit 1
fi

# A fresh install lands on the server-config scene. `input text` goes through
# the active keyboard, and a composing one (Gboard on a zh-CN emulator turns
# "://" into full-width punctuation) mangles the URL: disable the keyboards
# while typing so the key events reach the field directly.
IMES=$(adb shell ime list -s | tr -d '\r')
ui tap resource-id "$PKG:id/server_url" 30
for ime in $IMES; do adb shell ime disable "$ime" >/dev/null; done
adb shell input text "$MOCK_URL"
for ime in $IMES; do adb shell ime enable "$ime" >/dev/null; done
sleep 1
if ! ui has text "$MOCK_URL"; then
  echo "::error::release smoke: server URL field does not read $MOCK_URL"
  ui texts
  exit 1
fi
# The cleartext row only appears for an http:// URL.
ui tap resource-id "$PKG:id/checkbox_allow_cleartext"
ui tap resource-id "$PKG:id/connect"
ui wait resource-id "$PKG:id/thumb_new" 60
check "connect and list" 3

# The 40-page archive when the list shows titles, else the first card.
if ui has text "Mock Big Archive 1"; then
  ui tap text "Mock Big Archive 1"
else
  ui tap resource-id "$PKG:id/thumb_new"
fi
ui wait resource-id "$PKG:id/read" 30
check "detail page" 3

ui tap resource-id "$PKG:id/read"
check "reader open" 8

# The reader is a GL surface (nothing for uiautomator): turn pages by tapping
# the left edge, the "next" side of the default right-to-left direction.
read -r W H < <(adb shell wm size | tr -d '\r' | sed -nE 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/p')
X=$((W / 10)); Y=$((H / 2))
for _ in $(seq 1 $PAGE_TURNS); do
  adb shell input tap "$X" "$Y"
  sleep 0.7
done
check "reader $PAGE_TURNS page turns" 5

adb shell input keyevent KEYCODE_BACK
check "back from reader" 5

PAGES_SERVED=$(grep -c "/page" "$MOCK_LOG" || true)
echo "mock served $PAGES_SERVED page requests"
if [ "$PAGES_SERVED" -lt 5 ]; then
  echo "::error::release smoke: the reader fetched only $PAGES_SERVED pages from the mock"
  tail -40 "$MOCK_LOG"
  exit 1
fi
echo "release smoke passed"
