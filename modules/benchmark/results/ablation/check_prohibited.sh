#!/bin/sh
# Every file the ranking run was forbidden to touch, checked against a base revision rather than
# asserted. A number obtained by weakening the baseline or the gold set is not a number, and
# "we did not touch them" is a claim a reader has no way to verify without this.
#
#   usage: check_prohibited.sh [base-revision]   (default: main)
set -eu
cd "$(git rev-parse --show-toplevel)"
base=${1:-main}

R=modules/benchmark/src/main/kotlin/io/contextgraph/benchmark/retrieval
C=modules/benchmark/src/main/kotlin/io/contextgraph/benchmark/corpus

set -- \
  "$R/RipgrepQueryDeriver.kt" \
  "$R/RipgrepBaselineRunner.kt" \
  "$R/RipgrepProcess.kt" \
  "$R/RetrievalMetrics.kt" \
  "$R/ExpectedFileSet.kt" \
  "$C/IndexIntegrityGate.kt" \
  modules/benchmark/questions \
  modules/benchmark/questions-set

for f in "$@"; do
  [ -e "$f" ] || { echo "ABSENT (cannot be unchanged if it does not exist): $f"; exit 2; }
done

echo "files checked:"
for f in "$@"; do echo "  $f"; done
echo
echo "\$ git diff --stat $base -- <those paths>"
git diff --stat "$base" -- "$@"
echo "(no output above this line means every one is byte-for-byte identical to $base)"
echo
printf '$ git diff --name-only %s -- <those paths> | wc -l\n' "$base"
git diff --name-only "$base" -- "$@" | wc -l
