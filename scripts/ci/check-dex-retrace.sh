#!/usr/bin/env bash
# Fails when release crash stacks could not be retraced with the build's
# mapping.txt (audit 2026-10-06d REL-04). R8 (AGP 8.13) keeps line info by
# default: methods carry position tables (pc-based minified lines that
# mapping.txt maps back), and every class's source file is set to
# "r8-map-id-<pg_map_id>", so each frame of a field crash report names the
# exact mapping it needs: `at a3.b(r8-map-id-797e...:12)`. A rule such as
# `-renamesourcefileattribute` or `-keepattributes SourceFile` replaces that
# stamp; a debug-info strip removes the lines.
# Needs a prior `./gradlew :app:minifyAppReleaseReleaseWithR8`.
set -euo pipefail

DEX_DIR="app/build/intermediates/dex/appReleaseRelease/minifyAppReleaseReleaseWithR8"
MAPPING="app/build/outputs/mapping/appReleaseRelease/mapping.txt"
if [ ! -f "$MAPPING" ]; then
  echo "::error::No R8 mapping at $MAPPING"
  exit 1
fi
MAP_ID=$(sed -nE 's/^# pg_map_id: ([0-9a-f]+)$/\1/p' "$MAPPING" | head -1)
if [ -z "$MAP_ID" ]; then
  echo "::error::$MAPPING has no pg_map_id header"
  exit 1
fi
shopt -s nullglob
DEXES=("$DEX_DIR"/classes*.dex)
if [ ${#DEXES[@]} -eq 0 ]; then
  echo "::error::No R8-minified classes*.dex found in $DEX_DIR"
  exit 1
fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BUILD_TOOLS=$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -n1)
DEXDUMP="${BUILD_TOOLS}dexdump"
[ -x "$DEXDUMP" ] || DEXDUMP="${DEXDUMP}.exe"
if [ ! -x "$DEXDUMP" ]; then
  echo "::error::dexdump not found under $BUILD_TOOLS"
  exit 1
fi
STAMPED=0
OTHERS=""
LINES=0
for DEX in "${DEXES[@]}"; do
  DUMP=$("$DEXDUMP" -d "$DEX")
  SOURCES=$(grep -oE 'source_file_idx[[:space:]]*: [0-9-]+ \([^)]*\)' <<< "$DUMP" | sed -E 's/.*\((.*)\)$/\1/' || true)
  STAMPED=$((STAMPED + $(grep -cxF "r8-map-id-$MAP_ID" <<< "$SOURCES" || true)))
  OTHERS+=$(grep -vxF -e "r8-map-id-$MAP_ID" -e "unknown" <<< "$SOURCES" || true)
  LINES=$((LINES + $(grep -cE '^[[:space:]]+0x[0-9a-f]+ line=[0-9]+' <<< "$DUMP" || true)))
done
if [ -n "$OTHERS" ]; then
  echo "::error::Release DEX classes name a source file other than r8-map-id-$MAP_ID:"
  echo "$OTHERS" | sort | uniq -c | sort -rn | head -10
  exit 1
fi
if [ "$STAMPED" -eq 0 ] || [ "$LINES" -eq 0 ]; then
  echo "::error::Release DEX is not retraceable: $STAMPED classes stamped r8-map-id-$MAP_ID, $LINES line entries"
  exit 1
fi
echo "OK: $STAMPED release DEX classes stamped r8-map-id-$MAP_ID, $LINES line entries."
