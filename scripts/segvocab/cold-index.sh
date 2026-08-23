#!/bin/sh
# Deliverable 2: the cold-index step (agent-team/tasks/02-measurement-rig.md).
#
# Deletes the private WITH copy's .contextgraph/ and rebuilds it from nothing, every time this
# is called -- there is no flag to skip the delete. That is what makes a warm-index run unable
# to masquerade as a cold one *by construction*: this is the only script in the rig that runs
# the CLI's `index` command, and it always deletes first. On top of that structural guarantee,
# it also verifies the built database's mtime is not older than the deletion, so "cold" is a
# checked property of the output, not just an intention in the script's control flow.
#
# IngestPipeline.extractFile skips any artifact whose checksum still matches an existing row,
# so indexing over a warm database measures the skip path (D12/AC-2) -- this is the step that
# neutralises that for every arm alike.
#
# D6's caveat: the CLI's .contextgraph home follows the CWD it was invoked from
# (Main.kt:projectRoot() = Path.of(".")), not the directory argument to `index` -- so this
# script always `cd`s into the WITH directory before invoking the installed cli binary.
#
# Usage: cold-index.sh <snapshot-dir>
#   <snapshot-dir>  a directory produced by build-arm.sh (holds cli/ and benchmark/).
#
# On success, prints one JSON object to stdout:
#   {"repoId":"excalidraw","durationMillis":N,"indexSizeBytes":N,"deletedAtEpoch":N,"dbMtimeEpoch":N}
set -eu
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
. "$SCRIPT_DIR/lib.sh"

SNAPSHOT_DIR=${1:?"usage: cold-index.sh <snapshot-dir>"}
CLI_BIN="$SNAPSHOT_DIR/cli/bin/cli"
[ -x "$CLI_BIN" ] || segvocab_die "no cli launcher at $CLI_BIN -- run build-arm.sh first"

WITH_DIR="$SEGVOCAB_CORPUS_ROOT/$SEGVOCAB_REPO_ID/with"
[ -d "$WITH_DIR" ] || segvocab_die "no private WITH copy at $WITH_DIR -- run prepare-corpus.sh first"

# Neither private working copy carries a .contextgraph/config.json, so loadConfig() falls back
# to ContextGraphConfig() defaults -- litellm.enabled=false, matching D6's requirement that
# every index in this run is LLM-free. Asserted rather than assumed.
[ -e "$WITH_DIR/.contextgraph/config.json" ] && segvocab_die "unexpected config.json in $WITH_DIR -- would change litellm defaults"

DELETED_AT=$(date +%s)
segvocab_log "deleting $WITH_DIR/.contextgraph (cold-index start, epoch=$DELETED_AT)"
rm -rf "$WITH_DIR/.contextgraph"
[ -e "$WITH_DIR/.contextgraph" ] && segvocab_die "delete did not remove $WITH_DIR/.contextgraph"

segvocab_log "indexing $WITH_DIR (cwd-driven .contextgraph home, D6)"
START_MS=$(( $(date +%s%N 2>/dev/null || echo 0) / 1000000 ))
if [ "$START_MS" = 0 ]; then START_MS=$(($(date +%s) * 1000)); fi

(
    cd "$WITH_DIR"
    JAVA_HOME=/Users/erenalpaslan/Library/Java/JavaVirtualMachines/jbr-17.0.8.1/Contents/Home \
    JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -Djava.io.tmpdir=/tmp/claude" \
        "$CLI_BIN" index . 1>&2
)

END_MS=$(( $(date +%s%N 2>/dev/null || echo 0) / 1000000 ))
if [ "$END_MS" = 0 ]; then END_MS=$(($(date +%s) * 1000)); fi
DURATION_MS=$((END_MS - START_MS))

DB_PATH="$WITH_DIR/.contextgraph/graph.local.db"
[ -f "$DB_PATH" ] || segvocab_die "index command did not produce $DB_PATH"

DB_MTIME=$(stat -f %m "$DB_PATH")
DB_SIZE=$(stat -f %z "$DB_PATH")

if [ "$DB_MTIME" -lt "$DELETED_AT" ]; then
    segvocab_die "refusing to trust this as a cold index: $DB_PATH mtime ($DB_MTIME) predates " \
        "the deletion at $DELETED_AT -- the database was not actually rebuilt by this run"
fi

segvocab_log "cold index complete: ${DURATION_MS}ms, ${DB_SIZE} bytes (mtime $DB_MTIME >= delete $DELETED_AT, proven cold)"
printf '{"repoId":"%s","durationMillis":%s,"indexSizeBytes":%s,"deletedAtEpoch":%s,"dbMtimeEpoch":%s}\n' \
    "$SEGVOCAB_REPO_ID" "$DURATION_MS" "$DB_SIZE" "$DELETED_AT" "$DB_MTIME"
