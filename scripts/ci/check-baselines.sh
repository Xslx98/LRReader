#!/usr/bin/env bash
# Fails when the lint or detekt baselines or the architecture allowlist gained
# entries relative to BASE:
# new findings must be fixed, not baselined. Usage: check-baselines.sh <base-ref>
# CI passes the push's "before" commit or the PR base; locally origin/main.
# The allowlist may seed entries only for a rule that is new since BASE. The
# architecture rules themselves may only be added: removing or editing a rule
# line needs a commit in BASE..HEAD with an "Arch-Rule-Change: <reason>" trailer.
set -euo pipefail

BASE="${1:?usage: check-baselines.sh <base-ref>}"
if ! git cat-file -e "$BASE^{commit}" 2>/dev/null; then
  echo "::warning::Base $BASE not available; skipping the baseline check."
  exit 0
fi

fail=0
check() {
  local file="$1" pattern="$2"
  if ! git cat-file -e "$BASE:$file" 2>/dev/null; then
    echo "OK: $file is new since $BASE (initial seed)"
    return
  fi
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
check framework/lint-baseline.xml '<issue'
check config/detekt/baseline-framework.xml '<ID>'
check config/detekt/baseline-framework-debug.xml '<ID>'
check config/detekt/baseline-framework-release.xml '<ID>'

RULES=config/architecture/rules.txt
if git cat-file -e "$BASE:$RULES" 2>/dev/null; then
  base_ids=$(git show "$BASE:$RULES" | grep -oE '^D[0-9]+[a-z]?' | paste -sd'|' -)
  check config/architecture/allowlist.txt "($base_ids) "
  # Against the merge-base: on a PR, BASE is main's tip, and a rule main gained
  # after the branch point must not read as one this branch removed.
  fork=$(git merge-base "$BASE" HEAD)
  removed_rules=$(git diff "$fork" HEAD -- "$RULES" | grep -cE '^-D[0-9]' || true)
  if [ "$removed_rules" -gt 0 ]; then
    if git log --no-merges --format=%B "$fork..HEAD" | grep -qE '^Arch-Rule-Change: .+'; then
      echo "OK: $RULES changed $removed_rules rule line(s); reason given in an Arch-Rule-Change trailer"
    else
      echo "::error::$RULES removed or edited $removed_rules rule line(s) vs $BASE. Rules may only be added;" \
        "a deliberate change needs an 'Arch-Rule-Change: <reason>' trailer in one of the commits"
      fail=1
    fi
  else
    echo "OK: $RULES lost no rule lines vs $BASE"
  fi
else
  check config/architecture/allowlist.txt 'D[0-9]'
fi
exit "$fail"
