#!/usr/bin/env sh
# 02-sync.sh — flatten the per-service staging tree (OUT_DIR/service/<svc>/{graph,openapi,
# impact}.json) into the web data dir as flat <svc>.json / .openapi.json / .impact.json,
# and MERGE the nexcore projects into that dir's manifest.json (other analyzers' entries
# are preserved). No re-analysis — reads whatever 01-refresh.sh already staged.
#
# Must run AFTER the shared assembler (flowmap/sh stage 12 = spring-kotlin sync) so the
# web manifest.json already exists and nexcore's backend graphs aren't pruned as
# "departed" (nexcore is not one of that sync's sources).
#
# SYNC_DIR comes from the environment (the unified pipeline passes spring's own SYNC_DIR
# so both land in the same dir) or flowmap.config.
# Usage: SYNC_DIR=<web-data-dir> ./scripts/02-sync.sh
set -e
. "$(dirname "$0")/_common.sh"
SYNC_DIR="${SYNC_DIR:-$(cfg SYNC_DIR)}"
[ -n "$SYNC_DIR" ] || { echo "SYNC_DIR not set (env or flowmap.config)"; exit 1; }
exec "$BIN" sync --out-dir "$OUT_DIR" --sync-dir "$SYNC_DIR"
