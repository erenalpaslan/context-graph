#!/bin/sh
# Records the byte size and modification time of both .contextgraph databases under the SHARED
# corpus at $SEGVOCAB_SHARED_CORPUS (excalidraw's and keycloak's) into a stable file this run
# keeps for the rest of its life. Run once, early -- before any arm's cold-index.sh has touched
# anything -- so every later measurement and check-shared-corpus-unmodified.sh have a fixed
# point to compare back against.
#
# This script only reads the shared corpus (stat, never open-for-write) -- it is the read half
# of the proof that this rig never re-indexes the shared corpus, which every arm this rig
# measures is bound not to touch.
#
# Usage: record-shared-corpus-baseline.sh
# Writes $SEGVOCAB_SHARED_CORPUS_BASELINE (see lib.sh) and prints its path.
set -eu
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
. "$SCRIPT_DIR/lib.sh"

stat_db() {
    repo=$1
    db="$SEGVOCAB_SHARED_CORPUS/$repo/with/.contextgraph/graph.local.db"
    if [ ! -f "$db" ]; then
        printf '{"repoId":"%s","path":"%s","present":false}' "$repo" "$db"
        return
    fi
    size=$(stat -f %z "$db")
    mtime=$(stat -f %m "$db")
    printf '{"repoId":"%s","path":"%s","present":true,"sizeBytes":%s,"mtimeEpoch":%s}' \
        "$repo" "$db" "$size" "$mtime"
}

mkdir -p "$SEGVOCAB_PRIVATE_ROOT"
{
    echo "{"
    echo "  \"recordedAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
    echo "  \"note\": \"Baseline proving the shared corpus is never re-indexed by this rig. Re-check with check-shared-corpus-unmodified.sh -- both entries must keep the exact sizeBytes and mtimeEpoch recorded here for the whole run.\","
    echo "  \"databases\": ["
    echo "    $(stat_db excalidraw),"
    echo "    $(stat_db keycloak)"
    echo "  ]"
    echo "}"
} > "$SEGVOCAB_SHARED_CORPUS_BASELINE"

segvocab_log "recorded shared-corpus baseline:"
cat "$SEGVOCAB_SHARED_CORPUS_BASELINE" >&2
echo "$SEGVOCAB_SHARED_CORPUS_BASELINE"
