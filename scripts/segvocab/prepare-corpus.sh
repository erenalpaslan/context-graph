#!/bin/sh
# The frozen private corpus.
#
# Copies excalidraw's pristine, never-indexed working tree out of the shared corpus at
# /tmp/claude/benchmark-corpus into two independent private copies this run owns outright:
# $SEGVOCAB_CORPUS_ROOT/excalidraw/{with,without}. "with" is the one every arm cold-indexes;
# "without" is read-only forever after this script runs (ripgrep's baseline and the retrieval
# runner's gold-file-existence checks read it directly, per RetrievalBenchmarkRunner's own
# doc comment on corpusRoot/<repoId>/{with,without,codegraph}).
#
# Idempotent: does nothing if both private copies already exist, unless --force is given (which
# deletes and re-copies both). The shared corpus is only ever *read* here -- `cp -R`, nothing
# that opens it for writing -- so its own excalidraw/{with,without,codegraph} and their
# .contextgraph databases are untouched no matter how many times this runs.
#
# Deliberately copies the shared corpus's "without" tree into BOTH private "with" and
# "without" -- not the shared "with" tree -- so the private "with" copy starts genuinely
# unindexed (no borrowed .contextgraph/ to accidentally reuse) and every arm's first cold-index
# is a real first index, not a rebuild over someone else's leftover database.
set -eu
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
. "$SCRIPT_DIR/lib.sh"

FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

SRC="$SEGVOCAB_SHARED_CORPUS/$SEGVOCAB_REPO_ID/without"
WITH_DIR="$SEGVOCAB_CORPUS_ROOT/$SEGVOCAB_REPO_ID/with"
WITHOUT_DIR="$SEGVOCAB_CORPUS_ROOT/$SEGVOCAB_REPO_ID/without"

[ -d "$SRC" ] || segvocab_die "shared corpus has no pristine working tree at $SRC -- has prepareCorpus been run upstream?"

if [ "$FORCE" = 1 ]; then
    segvocab_log "removing existing private copies (--force)"
    rm -rf "$WITH_DIR" "$WITHOUT_DIR"
fi

mkdir -p "$SEGVOCAB_CORPUS_ROOT/$SEGVOCAB_REPO_ID"

if [ -d "$WITH_DIR" ] && [ -d "$WITHOUT_DIR" ]; then
    segvocab_log "private corpus already present at $SEGVOCAB_CORPUS_ROOT/$SEGVOCAB_REPO_ID (use --force to recopy)"
else
    [ -d "$WITH_DIR" ] || { segvocab_log "copying $SRC -> $WITH_DIR"; cp -R "$SRC" "$WITH_DIR"; }
    [ -d "$WITHOUT_DIR" ] || { segvocab_log "copying $SRC -> $WITHOUT_DIR"; cp -R "$SRC" "$WITHOUT_DIR"; }
fi

# A leftover .contextgraph/ here from a prior arm's measurement is expected, not a fault --
# this script only guarantees the source tree exists. Coldness is cold-index.sh's job, proven
# fresh on *every* call by deleting first and checking the rebuilt database's mtime against
# that deletion, never by this script refusing to hand back a copy that was indexed before.
segvocab_log "private corpus ready: $WITH_DIR (indexable) / $WITHOUT_DIR (read-only baseline)"
echo "$SEGVOCAB_CORPUS_ROOT"
