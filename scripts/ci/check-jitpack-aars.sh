#!/usr/bin/env bash
# Integrity check for the com.github.seven332 libraries JitPack serves.
# They are pinned by tag, which their (long inactive) author could move, and
# JitPack would then serve a different build under the same version. Every
# one has a committed copy in libs/seven332-backup/; the AAR Gradle resolved
# must be byte-identical to it. Run after a build has resolved dependencies.
set -euo pipefail

CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/com.github.seven332"
compared=0
fail=0
for backup in libs/seven332-backup/*.aar; do
  file=$(basename "$backup")
  base=${file%.aar}
  name=${base%-*}
  version=${base##*-}
  resolved=$(find "$CACHE/$name/$version" -name "$file" 2>/dev/null | head -1 || true)
  # Backups of libraries no longer on the classpath have nothing to compare.
  [ -n "$resolved" ] || continue
  compared=$((compared + 1))
  if [ "$(sha256sum < "$backup")" != "$(sha256sum < "$resolved")" ]; then
    echo "::error::$file from JitPack differs from libs/seven332-backup/$file"
    fail=1
  fi
done
if [ "$compared" -eq 0 ]; then
  echo "::error::No resolved com.github.seven332 AAR found under $CACHE; run a build first"
  exit 1
fi
[ "$fail" -eq 0 ] && echo "OK: $compared JitPack AARs match libs/seven332-backup/"
exit "$fail"
