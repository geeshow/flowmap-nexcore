#!/usr/bin/env sh
# 01-refresh.sh — analyze every NEXCORE module under REPO + combine + openapi + impact +
# manifest into OUT_DIR (per-module <m>.json / .openapi.json / .impact.json plus the
# _combined.json / _openapi.json / _manifest.json aggregates). Impact is skipped
# automatically when REPO has no git history.
#
# Extra flags pass through, e.g.  ./scripts/01-refresh.sh --no-impact --impact-max 20
# Usage: ./scripts/01-refresh.sh [extra refresh flags...]
set -e
. "$(dirname "$0")/_common.sh"
mkdir -p "$OUT_DIR"

exec "$BIN" refresh --repo "$REPO" --out-dir "$OUT_DIR" "$@"
