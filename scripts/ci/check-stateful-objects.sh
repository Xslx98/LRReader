#!/usr/bin/env bash
# Caps the number of source files whose top-level `object` holds mutable state
# (2026-11 architecture review). Process-wide singletons with state belong on a
# ServiceRegistry module, where tests can replace them; a new stateful top-level
# object is a hidden singleton. Counted: files declaring a top-level object that
# have a member (4-space indent) `var`, `lateinit var`, or a val initialised
# with a mutable collection, flow, cache or atomic. Lower MAX when the count
# drops; never raise it for new code. Only the app (com.lanraragi.reader) is
# counted: the framework cannot use ServiceRegistry (rule D1), so its few
# injection points (for example lib/image/ImageTelemetry) are framework objects.
set -euo pipefail

MAX=37
SRC=app/src/main/java/com/lanraragi/reader
STATE='^    (private |internal |protected )?(@Volatile )?(@JvmStatic )?(lateinit var |var |val [A-Za-z_]+[^=]*= *(mutable(Map|List|Set)Of|ConcurrentHashMap|HashMap|HashSet|ArrayList|LinkedHashMap|MutableStateFlow|MutableSharedFlow|LruCache|Atomic[A-Za-z]+|WeakHashMap)\b)'

COUNT=0
LIST=""
while IFS= read -r f; do
  if grep -qE "$STATE" "$f"; then
    COUNT=$((COUNT + 1))
    LIST="$LIST
  $f"
  fi
done < <(grep -rlE '^(internal |private )?object [A-Z]' "$SRC" --include=*.kt)

if [ "$COUNT" -gt "$MAX" ]; then
  echo "::error::$COUNT files hold state in a top-level object (max $MAX). Put new process-wide state on a ServiceRegistry module instead.$LIST"
  exit 1
fi
echo "OK: $COUNT files hold state in a top-level object (max $MAX)"
