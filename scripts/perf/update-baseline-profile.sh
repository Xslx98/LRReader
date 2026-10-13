#!/usr/bin/env bash
# Regenerates the baseline profile and copies it into the app source so the
# release build packages it (audit 2026-10-13 PERF-03). Run before every
# release (the release skill does): rules name obfuscated classes through the
# R8 mapping, so a stale profile silently covers less.
#
# The generator (benchmark/.../BaselineProfileGenerator) runs on the AOSP
# managed device from benchmark/build.gradle and needs the mock LANraragi on
# the host (reached from that emulator at 10.0.2.2):
#   MOCK_BIG_PAGES=40 MOCK_BIG_COUNT=30 MOCK_CHAPTERS=1 python3 -u scripts/ci/mock_lrr.py &
# Close other emulators first: two of them plus Gradle starve the host.
# Usage, from the repo root: bash scripts/perf/update-baseline-profile.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

OUT_ROOT=benchmark/build/outputs/managed_device_android_test_additional_output/nonminifiedrelease
TARGET=app/src/main/baseline-prof.txt

./gradlew :benchmark:collectAppReleaseNonMinifiedReleaseBaselineProfile \
  -Pandroid.testInstrumentationRunnerArguments.lrr.server=http://10.0.2.2:3939 \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile

# With includeInStartupProfile the rule writes one file,
# BaselineProfileGenerator_generate-startup-prof.txt (full profile, startup
# classes flagged); AGP's profgen compiles it from src/main/baseline-prof.txt.
GENERATED=$(find "$OUT_ROOT" -name 'BaselineProfileGenerator_generate-startup-prof.txt' | head -n1)
if [ -z "$GENERATED" ]; then
  echo "update-baseline-profile: no generated profile under $OUT_ROOT" >&2
  exit 1
fi
cp "$GENERATED" "$TARGET"
echo "update-baseline-profile: $(grep -c 'Lcom/lanraragi/reader/' "$TARGET") app rules -> $TARGET"
bash scripts/ci/check-baseline-profile.sh --source-only
