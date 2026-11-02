#!/usr/bin/env bash
# Moves a package, or one file's top-level declarations, and rewrites every
# reference in the same pass: sources, tests, XML, proguard rules, the baseline
# profile, the lint/detekt baselines, the coverage floor and the architecture
# allowlist. Details and modes: scripts/move-package.py.
# Usage, from the repo root: bash scripts/move-package.sh <old> <new>
# Env: PYTHON (default python3; locally e.g. PYTHON="uv run --no-project python").
set -euo pipefail
cd "$(dirname "$0")/.."
PYTHON="${PYTHON:-python3}"
$PYTHON scripts/move-package.py "$@"
