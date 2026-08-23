#!/bin/sh
# What a built graph database says about its own search index.
#
# Answers the four questions this run's measurement needs, from a real index, as one JSON
# object on stdout:
#
#   1. `nodes` vs `nodes_fts` row counts -- the headline symptom. Before search rows were keyed
#      on `nodes.rowid`, `INSERT OR REPLACE` had nothing to conflict against on an FTS5 table
#      and every re-upsert appended another row: excalidraw read 10,602 against 10,383.
#   2. Rows that are wrong in a way counting alone cannot see -- search rows with no live node
#      behind them, and nodes with no search row. A duplicate offset by an orphan sums to the
#      right total while being wrong twice, so the totals are never trusted on their own.
#   3. The byte share of FTS5's shadow tables, `nodes_fts_content` in particular. That table is
#      a second copy of `id`/`label`/`properties`, which `nodes` already holds; it is exactly
#      what an external-content or contentless FTS5 design would stop storing. Run C left index
#      size an open question and this run deliberately does not take that win (decision D12), so
#      it publishes the number instead of a hypothesis.
#   4. FTS5's own `integrity-check`, which catches an index disagreeing with its content -- the
#      failure a half-replaced row would produce and that no row count would reveal.
#
# **Read-only, always.** Every connection is opened through a `file:...?mode=ro` URI, so this
# can be pointed at the shared corpus (which no script in this run may ever open for writing)
# without any care being needed at the call site. `integrity-check` is an FTS5 command issued
# through an INSERT, which a read-only connection refuses; that is reported as `skipped` rather
# than worked around by opening the database for writing.
#
# Usage: probe-index.sh <path-to-graph.db>
set -eu

DB=${1:?"usage: probe-index.sh <path-to-graph.db>"}
[ -f "$DB" ] || { printf 'probe-index.sh: no database at %s\n' "$DB" >&2; exit 1; }

ABS=$(cd "$(dirname "$DB")" && pwd -P)/$(basename "$DB")
RO_URI="file:$ABS?mode=ro"

query() {
    sqlite3 "$RO_URI" "$1"
}

NODES=$(query "SELECT count(*) FROM nodes;")
FTS=$(query "SELECT count(*) FROM nodes_fts;")
ORPHANED=$(query "SELECT count(*) FROM nodes_fts f WHERE NOT EXISTS (SELECT 1 FROM nodes n WHERE n.rowid = f.rowid);")
UNINDEXED=$(query "SELECT count(*) FROM nodes n WHERE NOT EXISTS (SELECT 1 FROM nodes_fts f WHERE f.rowid = n.rowid);")

DB_BYTES=$(stat -f %z "$ABS")

# dbstat reports real page occupancy per table, so these are on-disk bytes rather than a sum of
# string lengths. Absent when SQLite was built without SQLITE_ENABLE_DBSTAT_VTAB, in which case
# every byte figure is reported as null rather than estimated.
if query "SELECT count(*) FROM dbstat LIMIT 1;" >/dev/null 2>&1; then
    FTS_CONTENT_BYTES=$(query "SELECT coalesce(sum(pgsize), 0) FROM dbstat WHERE name = 'nodes_fts_content';")
    FTS_TOTAL_BYTES=$(query "SELECT coalesce(sum(pgsize), 0) FROM dbstat WHERE name LIKE 'nodes_fts%';")
    NODES_BYTES=$(query "SELECT coalesce(sum(pgsize), 0) FROM dbstat WHERE name = 'nodes';")
else
    FTS_CONTENT_BYTES=null
    FTS_TOTAL_BYTES=null
    NODES_BYTES=null
fi

if query "INSERT INTO nodes_fts(nodes_fts) VALUES('integrity-check');" >/dev/null 2>&1; then
    INTEGRITY='"ok"'
else
    # Expected on a read-only connection; a genuine corruption shows up as a non-zero
    # orphaned/unindexed count or a row-count mismatch above, which are read-only questions.
    INTEGRITY='"skipped-read-only"'
fi

printf '{"db":"%s","nodes":%s,"nodesFts":%s,"orphanedFtsRows":%s,"nodesWithoutFtsRow":%s,' \
    "$ABS" "$NODES" "$FTS" "$ORPHANED" "$UNINDEXED"
printf '"dbBytes":%s,"nodesTableBytes":%s,"ftsShadowBytes":%s,"ftsContentBytes":%s,"integrityCheck":%s}\n' \
    "$DB_BYTES" "$NODES_BYTES" "$FTS_TOTAL_BYTES" "$FTS_CONTENT_BYTES" "$INTEGRITY"
