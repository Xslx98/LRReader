#!/usr/bin/env bash
# Fails when the lint or detekt baselines gained entries relative to BASE:
# new findings must be fixed, not baselined. Usage: check-baselines.sh <base-ref>
# CI passes the push's "before" commit or the PR base; locally origin/main.
set -euo pipefail

BASE="${1:?usage: check-baselines.sh <base-ref>}"
if ! git cat-file -e "$BASE^{commit}" 2>/dev/null; then
  echo "::warning::Base $BASE not available; skipping the baseline check."
  exit 0
fi

fail=0
check() {
  local file="$1" pattern="$2"
  local added removed
  added=$(git diff "$BASE" HEAD -- "$file" | grep -cE "^\+[[:space:]]*$pattern" || true)
  removed=$(git diff "$BASE" HEAD -- "$file" | grep -cE "^-[[:space:]]*$pattern" || true)
  if [ "$added" -gt "$removed" ]; then
    echo "::error::$file grew (+$added -$removed entries vs $BASE): fix the findings, do not baseline them"
    fail=1
  else
    echo "OK: $file +$added -$removed entries vs $BASE"
  fi
}
check app/lint-baseline.xml '<issue'
check config/detekt/baseline.xml '<ID>'
check config/detekt/baseline-appReleaseDebug.xml '<ID>'
check config/detekt/baseline-appReleaseRelease.xml '<ID>'
exit "$fail"
