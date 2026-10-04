#!/usr/bin/env bash
# Fails when a build changed or added a Room schema export under app/schemas
# that is not committed. KSP rewrites <version>.json on every build, so an
# entity change without a version bump shows up here as a modified file
# (every upgraded device would otherwise crash with "Room cannot verify the
# data integrity"). Run after a build.
set -euo pipefail

CHANGED=$(git status --porcelain -- app/schemas)
if [ -n "$CHANGED" ]; then
  echo "::error::Room schema export differs from the committed one. Bump the database version and commit the new schema:"
  echo "$CHANGED"
  git --no-pager diff --stat -- app/schemas
  exit 1
fi
echo "OK: Room schema exports match the committed files."
