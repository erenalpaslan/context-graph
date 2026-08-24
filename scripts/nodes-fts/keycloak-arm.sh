#!/bin/sh
# One arm's Keycloak measurement, start to finish, as a single detachable unit.
#
# Keycloak is not part of `scripts/segvocab/measure-arm.sh`'s per-arm default, and rightly so:
# ContextGraph's retrieval denominator is excalidraw's nine questions, because `IndexIntegrityGate`
# gates Keycloak's ContextGraph side out over one gold-cited `META-INF/services/` resource, and it
# has scored nothing in every run since the three-way. But three things this run must publish are
# only obtainable from a real Keycloak index: its ingest duration, its index size, and its
# gold-file coverage -- coverage on the ContextGraph side is an index query, not a filesystem
# check, so it cannot be quoted from an earlier run's results.
#
# Written as one script so the whole thing can be run detached (`nohup ... &` + `disown`). A
# prior run had a 99-minute Keycloak ingest reaped by the harness as a background task; run C has
# since cut that to roughly eight minutes, but detaching is still the right shape for the one
# step here that is measured in minutes rather than seconds.
#
# Leaves the index in place for the caller to score and then delete -- a Keycloak index is
# ~1.55 GB and this volume runs at 96% capacity, so never hold two.
#
# Usage: keycloak-arm.sh <snapshot-dir> <out-json>
set -eu
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
SEGVOCAB_REPO_ID=keycloak
export SEGVOCAB_REPO_ID
. "$SCRIPT_DIR/../segvocab/lib.sh"

SNAPSHOT_DIR=${1:?"usage: keycloak-arm.sh <snapshot-dir> <out-json>"}
OUT=${2:?"usage: keycloak-arm.sh <snapshot-dir> <out-json>"}

segvocab_log "=== keycloak arm: $SNAPSHOT_DIR ==="

"$SCRIPT_DIR/../segvocab/prepare-corpus.sh" >&2

INGEST_JSON=$("$SCRIPT_DIR/../segvocab/cold-index.sh" "$SNAPSHOT_DIR")
segvocab_log "keycloak cold ingest: $INGEST_JSON"

DB_PATH="$SEGVOCAB_CORPUS_ROOT/keycloak/with/.contextgraph/graph.local.db"
PROBE_JSON=$("$SCRIPT_DIR/probe-index.sh" "$DB_PATH")
segvocab_log "keycloak probe: $PROBE_JSON"

printf '{"repoId":"keycloak","ingest":%s,"probe":%s}\n' "$INGEST_JSON" "$PROBE_JSON" > "$OUT"
segvocab_log "wrote $OUT"
cat "$OUT"
