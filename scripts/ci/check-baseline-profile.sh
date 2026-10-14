#!/usr/bin/env bash
# The release APK must carry the app's baseline profile (audit 2026-10-13
# PERF-03): app/src/main/baseline-prof.txt has rules for the app's own
# classes (an empty or library-only profile would still package fine), and
# the packaged APK contains the compiled assets/dexopt/baseline.prof(m).
# Needs a prior `./gradlew :app:assembleAppReleaseRelease`.
# Usage, from the repo root: bash scripts/ci/check-baseline-profile.sh [apk]
# `--source-only` checks just the source file (no APK needed).
set -euo pipefail
cd "$(dirname "$0")/../.."

SOURCE_ONLY=0
if [ "${1:-}" = "--source-only" ]; then
  SOURCE_ONLY=1
  shift
fi
APK="${1:-app/build/outputs/apk/appRelease/release/app-appRelease-release.apk}"
PROFILE=app/src/main/baseline-prof.txt
MIN_APP_RULES=200

if [ ! -f "$PROFILE" ]; then
  echo "::error::check-baseline-profile: $PROFILE is missing (scripts/perf/update-baseline-profile.sh)"
  exit 1
fi
APP_RULES=$(grep -c 'Lcom/lanraragi/reader/' "$PROFILE" || true)
if [ "$APP_RULES" -lt "$MIN_APP_RULES" ]; then
  echo "::error::check-baseline-profile: only $APP_RULES rules reference com/lanraragi/reader (< $MIN_APP_RULES); regenerate the profile"
  exit 1
fi

if [ "$SOURCE_ONLY" -eq 1 ]; then
  echo "OK: $PROFILE has $APP_RULES app rules."
  exit 0
fi
if [ ! -f "$APK" ]; then
  echo "::error::check-baseline-profile: no APK at $APK (run ./gradlew :app:assembleAppReleaseRelease)"
  exit 1
fi
# Listed once, matched from a here-string: `… | grep -q` under pipefail fails
# with SIGPIPE when grep exits early (false negative seen on CI).
ENTRIES=$(unzip -l "$APK" | awk '{ print $NF }')
for entry in assets/dexopt/baseline.prof assets/dexopt/baseline.profm; do
  if ! grep -qx "$entry" <<< "$ENTRIES"; then
    echo "::error::check-baseline-profile: $APK has no $entry"
    exit 1
  fi
done
echo "OK: baseline profile packaged ($APP_RULES app rules in $PROFILE)."
