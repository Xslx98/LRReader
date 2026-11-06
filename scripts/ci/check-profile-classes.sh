#!/usr/bin/env bash
# Every app class named in app/src/main/baseline-prof.txt must still exist.
# The profile names classes by descriptor (Lcom/lanraragi/...;), so a moved,
# renamed or deleted class silently drops its rules and the startup path it
# covered runs interpreted again; check-baseline-profile.sh only counts rules
# and cannot see that. Named classes (and ** / * wildcards) must match a
# compiled class. Numbered anonymous classes (Foo$1, $onCreate$14$2) drift with
# every edit, so they only count toward the overall match ratio, which must
# stay >= MIN_PERCENT. D8/R8 synthetics and R classes are never in the
# compiler output and are skipped.
# Needs a prior `./gradlew app:assembleAppReleaseDebug` (or any task that
# compiles the appReleaseDebug sources). Usage, from the repo root:
#   bash scripts/ci/check-profile-classes.sh
# Refresh the profile with scripts/perf/update-baseline-profile.sh; a pure
# package move rewrites the descriptors in the same commit instead.
set -euo pipefail

PROFILE=app/src/main/baseline-prof.txt
MIN_PERCENT=95
KOTLIN_DIR=app/build/tmp/kotlin-classes/appReleaseDebug
JAVAC_DIR=app/build/intermediates/javac/appReleaseDebug/compileAppReleaseDebugJavaWithJavac/classes
FRAMEWORK_DIR=framework/build/tmp/kotlin-classes/debug
MODULE=app_appReleaseRelease
MAPPING=app/build/outputs/mapping/appReleaseRelease/mapping.txt

# Internal members carry the Kotlin module name, so a rule is only live if it
# names the shipped module (app/build.gradle pins the :benchmark build types to
# it). Any other suffix matches nothing in the release build: R8 drops the rule.
WRONG_MODULE=$(grep -E '\$app_[A-Za-z0-9]+' "$PROFILE" | grep -vE "\\\$${MODULE}([^A-Za-z0-9]|\$)" || true)
if [ -n "$WRONG_MODULE" ]; then
  echo "::error::baseline-prof.txt has $(wc -l <<< "$WRONG_MODULE") rules whose internal members are not named for module $MODULE, e.g.: $(head -n1 <<< "$WRONG_MODULE")"
  exit 1
fi
# The pin above is a literal: catch the shipped module being renamed from under
# it. Only a release build has a mapping, so the debug-only CI job skips this.
if [ -f "$MAPPING" ] && ! grep -qF "\$$MODULE" "$MAPPING"; then
  echo "::error::$MAPPING names no \$$MODULE member; the shipped module was renamed, update app/build.gradle and baseline-prof.txt"
  exit 1
fi

if [ ! -d "$KOTLIN_DIR/com/lanraragi" ]; then
  echo "::error::$KOTLIN_DIR is missing; run ./gradlew app:assembleAppReleaseDebug first"
  exit 1
fi

CLASSES=$(mktemp)
trap 'rm -f "$CLASSES"' EXIT
for dir in "$KOTLIN_DIR" "$JAVAC_DIR" "$FRAMEWORK_DIR"; do
  [ -d "$dir" ] || continue
  (cd "$dir" && find com -name '*.class') | sed 's/\.class$//'
done | sort -u > "$CLASSES"

awk -v min="$MIN_PERCENT" '
  FNR == NR { have[$0] = 1; n_have++; list[n_have] = $0; next }
  {
    if (!match($0, /L(com\/lanraragi\/[^;]+);/)) next
    cls = substr($0, RSTART + 1, RLENGTH - 2)
    if (cls ~ /\$\$ExternalSynthetic|\$r8\$/) next
    if (cls ~ /\/R$/ || cls ~ /\/R\$/) next
    rules++
    if (cls in seen) { if (seen[cls]) ok++; next }
    found = 0
    if (index(cls, "*") > 0) {
      re = cls
      gsub(/\$/, "\\$", re)
      gsub(/\*\*/, "\001", re)
      gsub(/\*/, "[^/]*", re)
      gsub(/\001/, ".*", re)
      re = "^" re "$"
      for (i = 1; i <= n_have; i++) if (list[i] ~ re) { found = 1; break }
    } else if (cls in have) {
      found = 1
    }
    seen[cls] = found
    if (found) { ok++; next }
    if (cls ~ /\$[0-9]/) anon_missing[cls] = 1
    else named_missing[cls] = 1
  }
  END {
    n_named = 0
    for (c in named_missing) { print "::error::baseline-prof.txt names a missing class: " c; n_named++ }
    n_anon = 0
    for (c in anon_missing) n_anon++
    pct = rules ? (100.0 * ok / rules) : 0
    printf "baseline-prof.txt: %d app rules, %.1f%% match a compiled class; %d missing named, %d missing anonymous classes\n", rules, pct, n_named, n_anon
    if (n_named > 0) {
      print "::error::Move or rename? Rewrite the descriptors in baseline-prof.txt in the same commit (scripts/move-package.sh), or refresh the profile."
      exit 1
    }
    if (pct < min) {
      printf "::error::Only %.1f%% of the profile rules match (min %d%%): refresh it with scripts/perf/update-baseline-profile.sh\n", pct, min
      exit 1
    }
  }
' "$CLASSES" "$PROFILE"
