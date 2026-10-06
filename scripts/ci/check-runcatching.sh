#!/usr/bin/env bash
# Caps plain runCatching in app sources (audit 2026-10-04 C33). Around a
# suspend call it swallows CancellationException, so a cancelled coroutine
# carries on; such code must use util/suspendRunCatching instead. detekt's
# SuspendFunSwallowedCancellation cannot see suspend calls in this setup (its
# type resolution misses the coroutine metadata), hence this count.
# A new plain runCatching around non-suspend code is fine: raise MAX with it.
set -euo pipefail

MAX=35
COUNT=$(grep -rhoE "(^|[^A-Za-z.])runCatching[ ({<]" app/src/main/java --include=*.kt | wc -l | tr -d ' ')
if [ "$COUNT" -gt "$MAX" ]; then
  echo "::error::$COUNT plain runCatching uses in app/src/main (max $MAX). Use suspendRunCatching around suspend calls, or raise MAX in $0 for non-suspend code."
  exit 1
fi
echo "OK: $COUNT plain runCatching uses (max $MAX)"
