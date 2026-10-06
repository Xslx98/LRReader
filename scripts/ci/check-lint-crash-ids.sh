#!/usr/bin/env bash
# Fails when a crash-class lint issue is hidden instead of fixed (audit
# 2026-10-06d STAB-11, 2026-10-06e STAB-02/STAB-03):
#  1. app/lint-baseline.xml holds one of the ids below. Lint matches baseline
#     entries by id, message and file, so a baselined NewApi hid an API 30
#     call that crashed Android 9/10 (STAB-01) while CI stayed green, and a
#     baselined AnimatorKeep hid icon animations that R8 broke in release.
#  2. App sources suppress one of the ids (@Suppress / @SuppressLint /
#     //noinspection / tools:ignore) without an explanatory comment on the
#     same line (XML: an <!-- --> comment in or just above the element).
#     Function-wide @Suppress("WrongConstant") with no reason let any new
#     wrong constant in GalleryActivity.onCreate pass (STAB-03).
# These ids flag code that throws on some devices or silently stops working
# in release builds; fix the code (SDK gate, right constant, permission
# check, typed animator property) or suppress one call site with a reason.
set -euo pipefail

BASELINE=app/lint-baseline.xml
SOURCES=app/src
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
  UnspecifiedImmutableFlag
  AnimatorKeep
  BlockedPrivateApi
  SoonBlockedPrivateApi
  Instantiatable
  MissingClass
)

if [ ! -f "$BASELINE" ] || [ ! -d "$SOURCES/main" ]; then
  echo "::error::Run from the repo root: $BASELINE or $SOURCES/main not found"
  exit 1
fi

fail=0
for id in "${IDS[@]}"; do
  count=$(grep -c "id=\"$id\"" "$BASELINE" || true)
  if [ "$count" -gt 0 ]; then
    echo "::error::$BASELINE holds $count '$id' entr(y/ies): crash-class lint issues must be fixed, not baselined"
    fail=1
  fi
done

# Suppressions in shipped sources (unit/instrumented tests are exempt).
ID_ALT=$(IFS='|'; echo "${IDS[*]}")
WORD="(^|[^A-Za-z0-9_])($ID_ALT)([^A-Za-z0-9_]|$)"
MARKER='@Suppress(Lint)?\(|@file:Suppress\(|noinspection|tools:ignore='
set +e
HITS=$(grep -rnE --include='*.kt' --include='*.java' --include='*.xml' \
  --exclude-dir=test --exclude-dir=androidTest "$MARKER" "$SOURCES" | grep -E "$WORD")
status=$?
set -e
if [ "$status" -gt 1 ]; then
  echo "::error::Scanning $SOURCES for lint suppressions failed (grep exit $status)"
  exit 1
fi
UNREASONED=""
while IFS= read -r hit; do
  [ -z "$hit" ] && continue
  file=${hit%%:*}
  rest=${hit#*:}
  line=${rest%%:*}
  text=${rest#*:}
  text=${text%$'\r'}
  case "$file" in
    *.xml)
      # A comment anywhere from the line above the element's opening tag
      # down to the tools:ignore line counts.
      reasoned=$(awk -v n="$line" '
        NR <= n { a[NR] = $0 }
        END {
          i = n
          while (i > 1 && a[i] !~ /<[A-Za-z]/) i--
          for (j = (i > 1 ? i - 1 : 1); j <= n; j++) if (a[j] ~ /<!--/) { print "y"; exit }
        }' "$file")
      if [ -n "$reasoned" ]; then
        continue
      fi
      ;;
    *)
      # A //noinspection line is itself a comment: it needs a second one.
      if grep -q 'noinspection' <<< "$text"; then
        if grep -qE 'noinspection.*(//|/\*)' <<< "$text"; then
          continue
        fi
      elif grep -qE '//|/\*' <<< "$text"; then
        continue
      fi
      ;;
  esac
  UNREASONED+="  $file:$line:${text}"$'\n'
done <<< "$HITS"
if [ -n "$UNREASONED" ]; then
  echo "::error::Crash-class lint ids suppressed without a reason on the same line (fix the code, or narrow the suppression and add // why):"
  printf '%s' "$UNREASONED"
  fail=1
fi

if [ "$fail" -eq 0 ]; then
  echo "OK: no crash-class ids baselined or suppressed without a reason (${IDS[*]})"
fi
exit "$fail"
