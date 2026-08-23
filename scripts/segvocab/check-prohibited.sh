#!/bin/sh
# The prohibition checker: verifies, rather than asserts, that this branch never touched the
# benchmark harness it is measured against.
#
# Reports an explicit PASS or FAIL, runnable at any point during this run (not only at the
# end), on three independent checks:
#
#   1. `git diff --name-only <base> --` over the six named harness files and the question YAML
#      files is empty.
#   2. Every one of those same files' current blob hash (git hash-object, working tree)
#      matches the hash recorded before-any-code-was-written, not re-derived from `git diff`
#      at review time. Catches the case git diff would miss: a staged-then-unstaged edit, or
#      a checkout of a *different* commit that happens to carry the same paths unchanged
#      relative to that commit but not relative to the true starting point.
#   3. `git diff --stat <base> -- modules/benchmark/src` is empty, and
#      `git ls-files --others --exclude-standard -- modules/benchmark/src` finds no untracked
#      file either -- the stronger, self-imposed line: nothing new or changed under
#      modules/benchmark/src at all, not just the eight specifically-named files. The `git diff`
#      half alone is blind to a file that was created but never `git add`ed; the `ls-files` half
#      is what actually backs a claim of "no file created".
#
# All three checks fail loudly rather than silently reading a git error as a clean diff: an
# earlier version of this script ended every `git diff` call in `|| true`, so `git` failing to
# resolve `<base>` at all (a typo, a deleted branch) produced an empty diff and therefore a PASS.
# Reproduced: `check-prohibited.sh no-such-branch-xyz` printed `VERDICT: PASS`. This version
# verifies `<base>` resolves to a real commit before running any check, and no longer swallows a
# `git diff` failure into an empty string.
#
# Check 2's baseline lives in this directory's own prohibited-files-baseline.json, tracked by
# git -- not in .harness/runs/<id>/capabilities.json, which the run that wrote it also
# recorded the same ten hashes into (as prohibitedFilesBaseline.blobs) but which is gitignored
# (.harness/runs/**) and so does not survive that run directory being cleaned up. A checker
# whose one non-trivial check silently starts hard-FAILing the day someone tidies up an old
# run directory is not a checker anyone but the run that wrote it can trust; committing the
# same ten (path, blob-sha) pairs here is what makes this script work for a reader who is not
# us, on a fresh clone, indefinitely.
#
# Usage: check-prohibited.sh [base-revision]   (default: main)
set -eu
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
. "$SCRIPT_DIR/lib.sh"

BASE=${1:-main}
BASELINE_JSON="$SCRIPT_DIR/prohibited-files-baseline.json"

cd "$SEGVOCAB_REPO_ROOT"

# A git error (most commonly: $BASE does not resolve to anything) must never be read as "no
# differences". Every `git diff` call below used to end in `|| true`, which collapses both
# outcomes -- a real empty diff, and git itself failing to run -- into the same empty string,
# so a typo'd or deleted base revision silently produced VERDICT: PASS instead of a loud error.
# Reproduced: `check-prohibited.sh no-such-branch-xyz` printed PASS on every check. Failing here,
# before any of the three checks run, is what makes that impossible: a base that does not
# resolve is a usage error, not a clean bill of health.
git rev-parse --verify --quiet "$BASE^{commit}" >/dev/null || segvocab_die "base revision '$BASE' does not resolve to a commit -- refusing to treat a git error as a clean diff"

R=modules/benchmark/src/main/kotlin/io/contextgraph/benchmark/retrieval
C=modules/benchmark/src/main/kotlin/io/contextgraph/benchmark/corpus

# The six named harness files + the question YAMLs (same ten paths as this directory's own
# prohibited-files-baseline.json, gin/calcom included even though never prepared -- their
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
DIFF_NAMES=$(git diff --name-only "$BASE" -- $NAMED_FILES) || segvocab_die "git diff failed for check 1 against '$BASE' -- see stderr above, not treating this as an empty diff"
if [ -n "$DIFF_NAMES" ]; then
    echo "FAIL: the following named file(s) differ from $BASE:"
    echo "$DIFF_NAMES" | sed 's/^/  /'
    PASS=0
else
    echo "PASS: all named files byte-for-byte identical to $BASE"
fi
echo

echo "=== check 2: working-tree blob hash vs $BASELINE_JSON's blobs ==="
if [ ! -f "$BASELINE_JSON" ]; then
    # Absent, not wrong: a checked-out tree missing this tracked file is a checkout problem,
    # not evidence of a prohibited-file edit -- checks 1 and 3 above still ran and still hold
    # the line on their own. SKIP rather than FAIL so a missing baseline (this file deleted,
    # or run against some other tree that never had it) is reported as exactly what it is,
    # not conflated with a genuine violation.
    echo "SKIP: $BASELINE_JSON not found -- cannot verify the pre-edit baseline (checks 1 and 3 above are unaffected)"
else
    BASELINE=$(python3 -c "
import json, sys
with open('$BASELINE_JSON') as f:
    d = json.load(f)
blobs = d.get('blobs', {})
for path, sha in sorted(blobs.items()):
    print(f'{path}\t{sha}')
")
    if [ -z "$BASELINE" ]; then
        echo "FAIL: $BASELINE_JSON has no blobs entries"
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
BENCH_SRC_DIFF=$(git diff --stat "$BASE" -- modules/benchmark/src) || segvocab_die "git diff failed for check 3 against '$BASE' -- see stderr above, not treating this as an empty diff"
# `git diff` only ever sees tracked content -- a brand-new file sitting in modules/benchmark/src
# that was never `git add`ed is invisible to it, so "identical to $BASE" on the diff alone would
# be true even with an untracked file created there. `git ls-files --others` is what actually
# answers "created", which is half of what this check's message used to assert without checking.
BENCH_SRC_UNTRACKED=$(git ls-files --others --exclude-standard -- modules/benchmark/src)
if [ -n "$BENCH_SRC_DIFF" ] || [ -n "$BENCH_SRC_UNTRACKED" ]; then
    echo "FAIL: modules/benchmark/src differs from $BASE:"
    [ -n "$BENCH_SRC_DIFF" ] && echo "$BENCH_SRC_DIFF" | sed 's/^/  /'
    if [ -n "$BENCH_SRC_UNTRACKED" ]; then
        echo "  untracked (created, never committed):"
        echo "$BENCH_SRC_UNTRACKED" | sed 's/^/    /'
    fi
    PASS=0
else
    echo "PASS: modules/benchmark/src is byte-for-byte identical to $BASE, and no untracked file sits there either (no file created, modified or removed)"
fi
echo

if [ "$PASS" = 1 ]; then
    echo "=== VERDICT: PASS -- all prohibitions hold against $BASE ==="
    exit 0
else
    echo "=== VERDICT: FAIL -- see above ==="
    exit 1
fi
