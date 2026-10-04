#!/usr/bin/env bash
# Fails when the R8-minified release DEX still calls android.util.Log.v/d/i/w.
# proguard-rules.pro strips them with -assumenosideeffects; a call R8 cannot
# prove side-effect-free survives. Log.e is kept on purpose.
# Needs a prior `./gradlew :app:minifyAppReleaseReleaseWithR8`.
set -euo pipefail

DEX_DIR="app/build/intermediates/dex/appReleaseRelease/minifyAppReleaseReleaseWithR8"
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
VIOLATIONS=""
for DEX in "${DEXES[@]}"; do
  VIOLATIONS+=$("$DEXDUMP" -d "$DEX" | grep -E 'Landroid/util/Log;\.[wdiv]:' || true)
done
if [ -n "$VIOLATIONS" ]; then
  echo "::error::Release DEX contains un-stripped Log.v/d/i/w call sites:"
  echo "$VIOLATIONS" | head -20
  exit 1
fi
echo "OK: release DEX contains no Log.v/d/i/w call sites."
