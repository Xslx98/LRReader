#!/usr/bin/env bash
# Runs locally what CI's Build & Test job gates on, in the same order, so a
# push cannot go red on a check that was skipped here (local `detekt` is not
# CI's `detektMain`, and the DEX / schema / baseline checks are not Gradle
# tasks). Usage, from the repo root: bash scripts/ci-check.sh [base-ref]
# The base for the baseline check defaults to origin/main.
set -euo pipefail
cd "$(dirname "$0")/.."

BASE="${1:-origin/main}"
./gradlew app:assembleAppReleaseDebug app:testAppReleaseDebugUnitTest app:lintAppReleaseDebug \
  detektMain :app:minifyAppReleaseReleaseWithR8
bash scripts/ci/check-schemas.sh
bash scripts/ci/check-jitpack-aars.sh
bash scripts/ci/check-baselines.sh "$BASE"
bash scripts/ci/check-dex-logs.sh
echo "ci-check: all gates passed"
