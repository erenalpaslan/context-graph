#!/bin/sh
# THE single entry point (spec AC-2, task 02's first acceptance criterion): one command
# measures one arm end to end -- cold index, retrieval run, row printed -- and running it
# twice on the same code state prints the same row.
#
# Usage: measure-arm.sh <arm-label> [--snapshot-dir DIR] [--source-root DIR]
#   <arm-label>     name for this measurement (used as the build snapshot's name unless
#                   --snapshot-dir is given, and printed on the row).
#   --snapshot-dir  reuse an already-built snapshot from build-arm.sh instead of building one
#                   now. This is what makes "run it twice, get the same row" a check of the
#                   rig's own determinism rather than of whether the source tree held still
#                   between the two calls -- see build-arm.sh's header.
#   --source-root   passed through to build-arm.sh when it does build (ignored with
#                   --snapshot-dir). Defaults to this rig's own repo root.
#
# There is no flag to skip the cold-index step: the only way through this script to a row is
# through cold-index.sh, which always deletes .contextgraph before it indexes. A warm-index
# run cannot reach retrieval scoring through this rig at all.
set -eu
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
. "$SCRIPT_DIR/lib.sh"

LABEL=${1:?"usage: measure-arm.sh <arm-label> [--snapshot-dir DIR] [--source-root DIR]"}
shift
SNAPSHOT_DIR=""
SOURCE_ROOT="$SEGVOCAB_REPO_ROOT"
while [ $# -gt 0 ]; do
    case "$1" in
        --snapshot-dir) SNAPSHOT_DIR=$2; shift 2 ;;
        --source-root) SOURCE_ROOT=$2; shift 2 ;;
        *) segvocab_die "unknown argument: $1" ;;
    esac
done

segvocab_log "=== measuring arm '$LABEL' ==="

"$SCRIPT_DIR/prepare-corpus.sh" >&2

if [ -z "$SNAPSHOT_DIR" ]; then
    SNAPSHOT_DIR=$("$SCRIPT_DIR/build-arm.sh" "$LABEL" "$SOURCE_ROOT")
else
    segvocab_log "reusing snapshot $SNAPSHOT_DIR (no build)"
    [ -d "$SNAPSHOT_DIR/cli" ] && [ -d "$SNAPSHOT_DIR/benchmark" ] || \
        segvocab_die "$SNAPSHOT_DIR does not look like a build-arm.sh snapshot (missing cli/ or benchmark/)"
fi

INGEST_JSON=$("$SCRIPT_DIR/cold-index.sh" "$SNAPSHOT_DIR")
segvocab_log "ingest: $INGEST_JSON"

RESULT_JSON=$("$SCRIPT_DIR/run-retrieval.sh" "$SNAPSHOT_DIR")
segvocab_log "retrieval result: $RESULT_JSON"

python3 "$SCRIPT_DIR/extract-row.py" "$RESULT_JSON" --label "$LABEL" --ingest-json "$INGEST_JSON" \
    --snapshot-dir "$SNAPSHOT_DIR"
