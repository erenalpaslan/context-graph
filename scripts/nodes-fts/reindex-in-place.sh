#!/bin/sh
# A second index over an already-indexed working copy, with nothing deleted first.
#
# This is the one thing `scripts/segvocab/cold-index.sh` deliberately cannot do. That script
# always deletes `.contextgraph/` before it indexes, and has no flag to skip it, precisely so a
# warm index can never masquerade as a cold one on its way to a retrieval row. That guarantee is
# worth keeping, so this run does not weaken it -- it adds a separate step that indexes *warm*
# on purpose and **never feeds retrieval scoring**. Its only output is row counts.
#
# Why a warm index is the measurement that matters here: "index twice, and the search index must
# not grow" is the property `nodes_fts` lacked. Before search rows were keyed on `nodes.rowid`,
# a second index of excalidraw took it from 10,602 rows to 10,608 -- small only because
# extraction skips any artifact whose checksum still matches, so a second index re-writes
# almost nothing. The growth is real regardless of its size, and it is unbounded over a
# repository's lifetime.
#
# Usage: reindex-in-place.sh <snapshot-dir> [repo-id]
#   <snapshot-dir>  a directory produced by scripts/segvocab/build-arm.sh (holds cli/).
#   [repo-id]       defaults to $SEGVOCAB_REPO_ID, itself defaulting to excalidraw.
#
# Prints one JSON object: {"repoId","pass":"warm","durationMillis","indexSizeBytes"}
set -eu
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
. "$SCRIPT_DIR/../segvocab/lib.sh"

SNAPSHOT_DIR=${1:?"usage: reindex-in-place.sh <snapshot-dir> [repo-id]"}
REPO_ID=${2:-$SEGVOCAB_REPO_ID}

CLI_BIN="$SNAPSHOT_DIR/cli/bin/cli"
[ -x "$CLI_BIN" ] || segvocab_die "no cli launcher at $CLI_BIN -- run build-arm.sh first"

WITH_DIR="$SEGVOCAB_CORPUS_ROOT/$REPO_ID/with"
DB_PATH="$WITH_DIR/.contextgraph/graph.local.db"
[ -f "$DB_PATH" ] || segvocab_die "no existing index at $DB_PATH -- cold-index.sh must have run first"

BEFORE_MTIME=$(stat -f %m "$DB_PATH")

segvocab_log "re-indexing $WITH_DIR in place (warm, nothing deleted)"
START_MS=$(( $(date +%s%N 2>/dev/null || echo 0) / 1000000 ))
if [ "$START_MS" = 0 ]; then START_MS=$(($(date +%s) * 1000)); fi

(
    cd "$WITH_DIR"
    JAVA_HOME="$SEGVOCAB_JAVA_HOME" \
    JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -Djava.io.tmpdir=$SEGVOCAB_TMPDIR" \
        "$CLI_BIN" index . 1>&2
)

END_MS=$(( $(date +%s%N 2>/dev/null || echo 0) / 1000000 ))
if [ "$END_MS" = 0 ]; then END_MS=$(($(date +%s) * 1000)); fi

AFTER_MTIME=$(stat -f %m "$DB_PATH")
[ "$AFTER_MTIME" -ge "$BEFORE_MTIME" ] || segvocab_die "database mtime went backwards -- $DB_PATH was not the file just written"

printf '{"repoId":"%s","pass":"warm","durationMillis":%s,"indexSizeBytes":%s}\n' \
    "$REPO_ID" "$((END_MS - START_MS))" "$(stat -f %z "$DB_PATH")"
