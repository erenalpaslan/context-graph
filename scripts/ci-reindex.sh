#!/usr/bin/env bash
# CI entry point for the freshness triggers (slice 13). This is the ONLY sanctioned way to
# regenerate the committed baseline (.contextgraph/graph.db) -- the CLI's `index`/`refresh`
# commands and the watcher only ever write the gitignored local overlay
# (.contextgraph/graph.local.db). See GraphDb.kt and Freshness.kt (CiReindexCommand) for the
# rest of that invariant.
#
# Runs headless: no TTY is expected (CiReindexCommand itself refuses to run against an
# interactive terminal, as a second, code-level enforcement of the same invariant) and no
# LLM credentials are required -- CiReindexCommand force-disables litellm regardless of what
# .contextgraph/config.json says. Emits one line of machine-readable JSON on stdout; all
# progress/log output goes to stderr. Exits 0 on success, non-zero otherwise.
#
# Usage: scripts/ci-reindex.sh [path-to-index]   (defaults to the repo root)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TARGET="${1:-$REPO_ROOT}"

echo "==> Building the contextgraph CLI (./gradlew :modules:cli:installDist)" >&2
./gradlew :modules:cli:installDist -q

BIN="$REPO_ROOT/modules/cli/build/install/cli/bin/cli"
if [ ! -x "$BIN" ]; then
    echo '{"status":"error","message":"contextgraph CLI binary not found after installDist"}'
    exit 1
fi

echo "==> Reindexing $TARGET and writing the committed baseline" >&2
"$BIN" ci-reindex "$TARGET"
