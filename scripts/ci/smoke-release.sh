#!/usr/bin/env bash
# Runtime smoke of the minified release APK on a running emulator/device:
# install, cold start, rotate, background and foreground again, and fail on
# any crash of the app process. Unit tests run the debug build only, and R8
# keep-rule mistakes (a stripped serializer, a reflectively created class)
# crash only the release build: v1.15.1 shipped a launch crash with green CI.
# Usage: smoke-release.sh <apk>
set -euo pipefail

APK="${1:?usage: smoke-release.sh <apk>}"
PKG="com.lanraragi.reader"
LAUNCHER="$PKG/$PKG.ui.splash.SplashActivity"

adb wait-for-device
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r "$APK"
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
echo "release smoke passed"
