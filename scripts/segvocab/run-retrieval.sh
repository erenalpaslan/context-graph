#!/bin/sh
# Runs the retrieval axis, read-only, against the private frozen corpus -- never indexes
# anything itself (that is cold-index.sh's job, and must have already run against the same
# snapshot's WITH copy). Invokes RetrievalCliKt directly via `java -cp`, against the snapshot
# build-arm.sh produced, so this never triggers a Gradle recompile that could pick up a
# concurrent edit mid-measurement (see build-arm.sh's header).
#
# Points --questions-dir at the tracked modules/benchmark/questions/ directory, unmodified --
# nothing is filtered or copied, so there is no risk of this rig quietly becoming a second copy
# of the gold question set that could drift from the one under prohibition (AC-14).
#
# Usage: run-retrieval.sh <snapshot-dir>
# Prints the absolute path to the written retrieval-<epoch>.json as the last line of stdout.
set -eu
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
. "$SCRIPT_DIR/lib.sh"

SNAPSHOT_DIR=${1:?"usage: run-retrieval.sh <snapshot-dir>"}
BENCH_LIB="$SNAPSHOT_DIR/benchmark/lib"
[ -d "$BENCH_LIB" ] || segvocab_die "no benchmark distribution at $BENCH_LIB -- run build-arm.sh first"

mkdir -p "$SEGVOCAB_RESULTS_ROOT"

segvocab_log "scoring retrieval: corpus-root=$SEGVOCAB_CORPUS_ROOT questions=$SEGVOCAB_REPO_ROOT/modules/benchmark/questions"
# java.io.tmpdir pinned explicitly: the JBR JVM reads macOS's per-process temp dir via
# confstr(_CS_DARWIN_USER_TEMP_DIR) rather than $TMPDIR, and the sandbox denies writes there
# (capabilities.json's gradleInSandbox.denialsReproducedHere, reproduced here identically for
# a bare `java` launch -- sqlite-jdbc and JNA both try to extract native libs into it).
JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -Djava.io.tmpdir=/tmp/claude" \
"$SEGVOCAB_JAVA" \
    -Dcontextgraph.benchmark.repoRoot="$SEGVOCAB_REPO_ROOT" \
    -cp "$BENCH_LIB/*" \
    io.contextgraph.benchmark.cli.RetrievalCliKt \
    --corpus-root "$SEGVOCAB_CORPUS_ROOT" \
    --questions-dir "$SEGVOCAB_REPO_ROOT/modules/benchmark/questions" \
    --output-dir "$SEGVOCAB_RESULTS_ROOT" \
    --rg-path /opt/homebrew/bin/rg \
    --codegraph-path /opt/homebrew/bin/codegraph \
    1>&2

# RetrievalRun.writeTo names the file "$runId.json" and runId embeds Clock.System.now() --
# the newest one in the output dir is the one this invocation just wrote.
LATEST=$(ls -t "$SEGVOCAB_RESULTS_ROOT"/retrieval-*.json 2>/dev/null | head -1)
[ -n "$LATEST" ] || segvocab_die "RetrievalCli reported success but no retrieval-*.json appeared in $SEGVOCAB_RESULTS_ROOT"

echo "$LATEST"
