#!/usr/bin/env bash
# Fails when app code in the R8-minified release DEX builds a property animator
# from a property-name string (audit 2026-10-06e STAB-02). ObjectAnimator /
# PropertyValuesHolder / Property.of resolve "progress" to setProgress by
# reflection at run time; R8 renames or inlines app setters that no keep rule
# covers, so the animator silently does nothing in release while debug builds
# look right (the search-bar and Downloads icon morphs were dead this way).
# App code must pass a typed android.util.Property (View.ALPHA, a
# FloatProperty/IntProperty constant) instead. Library classes are reported,
# not failed: their setters are framework methods or kept by consumer rules.
# Call sites are attributed to their original class through mapping.txt.
# Needs a prior `./gradlew :app:minifyAppReleaseReleaseWithR8`.
set -euo pipefail

DEX_DIR="app/build/intermediates/dex/appReleaseRelease/minifyAppReleaseReleaseWithR8"
MAPPING="app/build/outputs/mapping/appReleaseRelease/mapping.txt"
APP_PREFIX="com.lanraragi."
if [ ! -f "$MAPPING" ]; then
  echo "::error::No R8 mapping at $MAPPING"
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

DUMP=$(mktemp)
trap 'rm -f "$DUMP"' EXIT
for DEX in "${DEXES[@]}"; do
  "$DEXDUMP" -d "$DEX" >> "$DUMP"
done

# Output: "APP <class>.<method> <last const-string>" per app call site,
# "LIB <count>" and "CLASSES <app classes seen>" at the end.
RESULT=$(awk -v prefix="$APP_PREFIX" -v quote="'" '
  NR == FNR {
    # mapping.txt class line: "original.Name -> obfuscated:"
    if ($0 !~ /^[ #]/ && $2 == "->") {
      obf = $3
      sub(/:$/, "", obf)
      gsub(/\./, "/", obf)
      orig["L" obf ";"] = $1
    }
    next
  }
  /^  Class descriptor  :/ {
    cls = $4
    gsub(quote, "", cls)
    name = (cls in orig) ? orig[cls] : cls
    if (index(name, prefix) == 1) classes++
    next
  }
  /^      name          :/ {
    method = $3
    gsub(quote, "", method)
    next
  }
  /const-string/ {
    last = $0
    sub(/.*const-string(\/jumbo)? [^,]*, /, "", last)
    sub(/ \/\/ string@.*/, "", last)
    next
  }
  /Landroid\/animation\/ObjectAnimator;\.(of[A-Za-z]*:\(Ljava\/lang\/Object;Ljava\/lang\/String;|setPropertyName:)|Landroid\/animation\/PropertyValuesHolder;\.(of[A-Za-z]*:\(Ljava\/lang\/String;|setPropertyName:)|Landroid\/util\/Property;\.of:/ {
    if (index(name, prefix) == 1) {
      print "APP " name "." method " property " last
    } else {
      lib++
    }
  }
  END {
    print "LIB " (lib + 0)
    print "CLASSES " (classes + 0)
  }
' "$MAPPING" "$DUMP")

APP_CLASSES=$(sed -n 's/^CLASSES //p' <<< "$RESULT")
LIB_SITES=$(sed -n 's/^LIB //p' <<< "$RESULT")
VIOLATIONS=$(grep '^APP ' <<< "$RESULT" || true)
if [ -z "$APP_CLASSES" ] || [ "$APP_CLASSES" -eq 0 ]; then
  echo "::error::No ${APP_PREFIX}* class found in the release DEX via $MAPPING; the check cannot attribute call sites"
  exit 1
fi
if [ -n "$VIOLATIONS" ]; then
  echo "::error::Release DEX builds property animators from name strings in app code (R8 may rename the setter); use a typed android.util.Property:"
  sed 's/^APP /  /' <<< "$VIOLATIONS"
  exit 1
fi
echo "OK: no string-named property animators in $APP_CLASSES app classes ($LIB_SITES library call sites ignored)."
