# _common.sh — sourced by every nexcore step script. Resolves paths/config + the CLI.
# Mirrors flowmap-spring/scripts/_common.sh so the unified flowmap/sh pipeline
# can delegate to it the same way.
#
# Config (REPO / OUT_DIR / SYNC_DIR) is read from flowmap.config when present, then
# overridden by any matching environment variable, then defaults. Steps (NN-*.sh):
#   01-refresh.sh  (analyze + combine + openapi + impact + manifest, one shot)
#   02-sync.sh     (flatten staging service tree → web data + merge manifest)

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# value of KEY=VALUE in flowmap.config, or empty. Trims an inline ` # comment` and
# trailing whitespace so the documented `KEY=VALUE   # 설명` style works (unlike the
# spring-kotlin sibling, whose config keeps comments on their own lines).
cfg() {
  grep -E "^$1=" flowmap.config 2>/dev/null | head -1 | cut -d= -f2- \
    | sed -e 's/[[:space:]]\{1,\}#.*$//' -e 's/[[:space:]]*$//'
}

REPO="${REPO:-$(cfg REPO)}";          REPO="${REPO:-../nexcore}"
OUT_DIR="${OUT_DIR:-$(cfg OUT_DIR)}";  OUT_DIR="${OUT_DIR:-json}"

BIN="build/install/flowmap-nexcore/bin/flowmap-nexcore"
# (re)build the CLI. ALWAYS run installDist — Gradle is incremental, so it recompiles
# only when sources changed and is a near-instant no-op otherwise. Guarding on
# `[ -x "$BIN" ]` would skip the rebuild whenever an old install exists, so source
# fixes never take effect and the pipeline keeps using stale compiled classes.
./gradlew -q installDist
