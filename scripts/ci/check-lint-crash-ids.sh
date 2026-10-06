#!/usr/bin/env bash
# Fails when app/lint-baseline.xml holds a crash-class lint issue (audit
# 2026-10-06d STAB-11). Lint matches baseline entries by id, message and
# file, so a baselined NewApi hid an API 30 call that crashed Android 9/10
# (STAB-01) while CI stayed green. These ids flag code that throws on some
# devices; fix the code (SDK gate, right constant, permission check) or
# suppress one call site with a reasoned @SuppressLint, never baseline them.
set -euo pipefail

BASELINE=app/lint-baseline.xml
IDS=(
  NewApi
  InlinedApi
  WrongConstant
  MissingPermission
  UnsafeOptInUsageError
  Range
  ResourceType
  WrongThread
  UnspecifiedRegisterReceiverFlag
)

fail=0
for id in "${IDS[@]}"; do
  count=$(grep -c "id=\"$id\"" "$BASELINE" || true)
  if [ "$count" -gt 0 ]; then
    echo "::error::$BASELINE holds $count '$id' entr(y/ies): crash-class lint issues must be fixed, not baselined"
    fail=1
  fi
done
if [ "$fail" -eq 0 ]; then
  echo "OK: no crash-class ids in $BASELINE (${IDS[*]})"
fi
exit "$fail"
