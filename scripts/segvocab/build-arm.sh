#!/bin/sh
# Builds the two runnable distributions an arm's measurement needs (the CLI, for cold
# indexing; the benchmark module, for the retrieval runner) from whatever code is currently
# checked out, and snapshots both into a private directory this run owns.
#
# Snapshotting matters because this worktree can be shared with a concurrent editor of
# modules/storage-sqlite and modules/core while this rig runs (another agent or developer
# working the same branch). Once this script returns, measure-arm.sh's cold-index and retrieval
# steps run entirely against the copied `bin/`+`lib/` trees below -- never against
# modules/*/build/, which a concurrent `compileKotlin` can rewrite out from under an in-flight
# measurement. Two calls into the same snapshot therefore measure the same bytecode even if the
# source tree changes between them.
#
# An arm here is a code state applied and reverted with git, not a runtime flag -- so the normal
# use is: checkout the arm's tree, run this script once, then run measure-arm.sh against the
# printed snapshot path as many times as wanted without rebuilding.
#
# Usage: build-arm.sh <label> [source-root]
#   <label>        a short name for this code state (e.g. "a0c-cold-baseline",
#                  "a2-segment-table"). Must be filesystem-safe; used verbatim as the
#                  snapshot directory name.
#   [source-root]  which git worktree to build from. Defaults to this rig's own repo root
#                  (the live worktree every other script in this directory reads from) --
#                  that default is what a real arm must use, since an arm is a code state
#                  applied and reverted with git *in this worktree*. The override exists only
#                  for building against a different, already-checked-out tree (e.g. a scratch
#                  `git worktree add` pinned to a specific commit) when the live worktree is
#                  mid-edit by someone else and will not compile yet -- see this rig's README
#                  for when that applies.
#
# Prints the absolute snapshot directory path as the last line of stdout on success.
set -eu
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
. "$SCRIPT_DIR/lib.sh"

LABEL=${1:?"usage: build-arm.sh <label> [source-root]"}
case "$LABEL" in
    */*|"") segvocab_die "label must be a single filesystem-safe path segment, got: $LABEL" ;;
esac
SOURCE_ROOT=${2:-$SEGVOCAB_REPO_ROOT}

SNAPSHOT_DIR="$SEGVOCAB_SNAPSHOTS_ROOT/$LABEL"
rm -rf "$SNAPSHOT_DIR"
mkdir -p "$SNAPSHOT_DIR"

segvocab_log "building :modules:cli:installDist and :modules:benchmark:installDist for arm '$LABEL' from $SOURCE_ROOT"
segvocab_gradle "$SOURCE_ROOT" :modules:cli:installDist :modules:benchmark:installDist >&2

CLI_BUILD_DIR="$SOURCE_ROOT/modules/cli/build/install/cli"
BENCH_BUILD_DIR="$SOURCE_ROOT/modules/benchmark/build/install/benchmark"
[ -d "$CLI_BUILD_DIR" ] || segvocab_die "installDist did not produce $CLI_BUILD_DIR"
[ -d "$BENCH_BUILD_DIR" ] || segvocab_die "installDist did not produce $BENCH_BUILD_DIR"

segvocab_log "snapshotting both distributions into $SNAPSHOT_DIR (immune to further rebuilds)"
cp -R "$CLI_BUILD_DIR" "$SNAPSHOT_DIR/cli"
cp -R "$BENCH_BUILD_DIR" "$SNAPSHOT_DIR/benchmark"

# Record the code state this snapshot was built from, so a row printed later can be traced
# back to it without trusting the label alone to say what was actually compiled.
GIT_HEAD=$(git -C "$SOURCE_ROOT" rev-parse HEAD 2>/dev/null || echo "unknown")
GIT_DIRTY=$(git -C "$SOURCE_ROOT" status --porcelain 2>/dev/null | wc -l | tr -d ' ')
cat > "$SNAPSHOT_DIR/BUILD_INFO.json" <<EOF
{
  "label": "$LABEL",
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "gitHead": "$GIT_HEAD",
  "gitDirtyFileCount": $GIT_DIRTY
}
EOF

segvocab_log "arm '$LABEL' built at git HEAD=$GIT_HEAD (dirty files: $GIT_DIRTY)"
echo "$SNAPSHOT_DIR"
