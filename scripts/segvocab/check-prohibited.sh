#!/bin/sh
# Deliverable 4: the prohibition checker (agent-team/tasks/02-measurement-rig.md, AC-14).
#
# Reports an explicit PASS or FAIL, runnable at any point during this run (not only at the
# end), on three independent checks:
#
#   1. `git diff --name-only <base> --` over the six named harness files and the question YAML
#      files is empty (run A's check, reused).
#   2. Every one of those same files' current blob hash (git hash-object, working tree)
#      matches the hash recorded in capabilities.json's prohibitedFilesBaseline.blobs --
#      i.e. before-any-code-was-written, not re-derived from `git diff` at review time.
#      Catches the case git diff would miss: a staged-then-unstaged edit, or a checkout of a
#      *different* commit that happens to carry the same paths unchanged relative to that
#      commit but not relative to the true starting point.
#   3. `git diff --stat <base> -- modules/benchmark/src` is empty -- run A's stronger,
#      self-imposed line: nothing under modules/benchmark/src at all, not just the eight
#      specifically-named files.
#
# Usage: check-prohibited.sh [base-revision]   (default: main)
set -eu
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
. "$SCRIPT_DIR/lib.sh"

BASE=${1:-main}
CAPABILITIES_JSON="/Users/erenalpaslan/Projects/context-graph/.harness/runs/2026-08-23-145625-materialise-identifier-segments-at-index/capabilities.json"

cd "$SEGVOCAB_REPO_ROOT"

R=modules/benchmark/src/main/kotlin/io/contextgraph/benchmark/retrieval
C=modules/benchmark/src/main/kotlin/io/contextgraph/benchmark/corpus

# The six named harness files + the question YAMLs (D-list from capabilities.json's
# prohibitedFilesBaseline.blobs, gin/calcom included even though never prepared -- their
# YAMLs are still a named prohibited path).
NAMED_FILES="
$R/RetrievalMetrics.kt
$R/ExpectedFileSet.kt
$C/IndexIntegrityGate.kt
$R/RipgrepQueryDeriver.kt
$R/RipgrepBaselineRunner.kt
$R/RipgrepProcess.kt
modules/benchmark/questions/calcom.yaml
modules/benchmark/questions/excalidraw.yaml
modules/benchmark/questions/gin.yaml
modules/benchmark/questions/keycloak.yaml
"

PASS=1

echo "=== check 1: git diff --name-only $BASE -- <named files> ==="
DIFF_NAMES=$(git diff --name-only "$BASE" -- $NAMED_FILES || true)
if [ -n "$DIFF_NAMES" ]; then
    echo "FAIL: the following named file(s) differ from $BASE:"
    echo "$DIFF_NAMES" | sed 's/^/  /'
    PASS=0
else
    echo "PASS: all named files byte-for-byte identical to $BASE"
fi
echo

echo "=== check 2: working-tree blob hash vs capabilities.json's prohibitedFilesBaseline.blobs ==="
if [ ! -f "$CAPABILITIES_JSON" ]; then
    echo "FAIL: capabilities.json not found at $CAPABILITIES_JSON -- cannot verify the pre-edit baseline"
    PASS=0
else
    BASELINE=$(python3 -c "
import json, sys
with open('$CAPABILITIES_JSON') as f:
    d = json.load(f)
blobs = d.get('prohibitedFilesBaseline', {}).get('blobs', {})
for path, sha in sorted(blobs.items()):
    print(f'{path}\t{sha}')
")
    if [ -z "$BASELINE" ]; then
        echo "FAIL: capabilities.json has no prohibitedFilesBaseline.blobs entries"
        PASS=0
    else
        echo "$BASELINE" | while IFS="$(printf '\t')" read -r path expected_sha; do
            [ -e "$path" ] || { echo "FAIL: $path -- absent from working tree (baseline expects blob $expected_sha)"; exit 1; }
            actual_sha=$(git hash-object "$path")
            if [ "$actual_sha" = "$expected_sha" ]; then
                echo "  ok   $path"
            else
                echo "FAIL: $path -- blob $actual_sha, expected $expected_sha (recorded before any edit)"
                exit 1
            fi
        done || PASS=0
    fi
fi
echo

echo "=== check 3: git diff --stat $BASE -- modules/benchmark/src (run A's stronger line) ==="
BENCH_SRC_DIFF=$(git diff --stat "$BASE" -- modules/benchmark/src || true)
if [ -n "$BENCH_SRC_DIFF" ]; then
    echo "FAIL: modules/benchmark/src differs from $BASE:"
    echo "$BENCH_SRC_DIFF" | sed 's/^/  /'
    PASS=0
else
    echo "PASS: modules/benchmark/src is byte-for-byte identical to $BASE (no file created, modified or removed)"
fi
echo

if [ "$PASS" = 1 ]; then
    echo "=== VERDICT: PASS -- all prohibitions hold against $BASE ==="
    exit 0
else
    echo "=== VERDICT: FAIL -- see above ==="
    exit 1
fi
