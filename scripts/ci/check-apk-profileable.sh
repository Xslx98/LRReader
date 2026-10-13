#!/usr/bin/env bash
# The release manifest must declare <profileable android:shell="true"/> so
# Perfetto, the Studio profiler and Macrobenchmark can attach to the shipped,
# non-debuggable build (audit 2026-10-13 PERF-11). Manifest merging can drop
# or override it silently, so check the packaged APK, not the source.
# Needs a prior `./gradlew :app:assembleAppReleaseRelease`.
# Usage, from the repo root: bash scripts/ci/check-apk-profileable.sh [apk]
set -euo pipefail
cd "$(dirname "$0")/../.."

APK="${1:-app/build/outputs/apk/appRelease/release/app-appRelease-release.apk}"
if [ ! -f "$APK" ]; then
  echo "::error::check-apk-profileable: no APK at $APK (run ./gradlew :app:assembleAppReleaseRelease)"
  exit 1
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
APKANALYZER=$(ls "$SDK"/cmdline-tools/*/bin/apkanalyzer 2>/dev/null | sort -V | tail -n1 || true)
[ -n "$APKANALYZER" ] || APKANALYZER=$(ls "$SDK"/cmdline-tools/*/bin/apkanalyzer.bat 2>/dev/null | sort -V | tail -n1 || true)
if [ -z "$APKANALYZER" ]; then
  echo "::error::check-apk-profileable: apkanalyzer not found under $SDK/cmdline-tools"
  exit 1
fi

MANIFEST=$("$APKANALYZER" manifest print "$APK" | tr -d '\r')
if echo "$MANIFEST" | grep -q 'android:debuggable="true"'; then
  echo "::error::check-apk-profileable: release APK is debuggable"
  exit 1
fi
# The packaged element is <profileable android:shell="true"/>, possibly with
# the attribute printed on its own line.
if ! echo "$MANIFEST" | tr '\n' ' ' | grep -Eq '<profileable[^>]*android:shell="true"'; then
  echo "::error::check-apk-profileable: release manifest has no <profileable android:shell=\"true\"/>"
  exit 1
fi
echo "OK: release APK is profileable and not debuggable."
