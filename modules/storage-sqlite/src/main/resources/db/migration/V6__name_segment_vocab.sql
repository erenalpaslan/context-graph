-- Identifier segment vocabulary: maps a sub-word of a compound identifier (e.g. "distribution"
-- from "RungDistribution") to the whole node labels that contain it, so a caller can look one
-- up directly -- something nodes_fts's tokenizer cannot serve, because it treats a compound
-- identifier with no separator as a single token and never indexes its parts on their own (see
-- SqliteStorageAdapter.ftsLabelFor for the index-time workaround this table complements).
--
-- Keyed on (segment, name), not (segment, node_id): thousands of nodes can share one label, so
-- keying on the id would make the table roughly an order of magnitude larger for a statistic
-- that only needs each name mentioned once. WITHOUT ROWID because (segment, name) is already a
-- natural, covering key -- no separate rowid index is needed to look a row up by it.
--
-- INSERT OR IGNORE against this real primary key is what nodes_fts's own "INSERT OR REPLACE"
-- cannot be: nodes_fts is FTS5 with `id UNINDEXED`, so it has no unique index for a conflict
-- clause to target and grows without bound on every reindex. This table cannot -- the same
-- (segment, name) pair written twice collapses to one row.
--
-- Every row is a proposal, not truth: file nodes are deliberately excluded (a file's basename
-- duplicates the symbols declared inside it and would skew segment rarity -- how many distinct
-- names a segment reaches), and a node removed by a later index leaves its rows behind as
-- orphans on purpose -- there is no cascade, because this database never enables
-- PRAGMA foreign_keys: edges.source_id and edges.target_id both declare
-- "REFERENCES nodes(id) ON DELETE CASCADE" (V1__init.sql:20-21), yet
-- SqliteStorageAdapter.deleteNodesForArtifact deletes edges by hand rather than relying on that
-- cascade to fire -- a real foreign-key pragma would make that hand-deletion redundant, not
-- merely defensive. Callers of this table re-verify against nodes(label) at query time instead;
-- idx_nodes_label (V3) already covers that join without a full scan, so no further index is
-- added here. (Note: the rarity denominator this enables -- COUNT(DISTINCT name), read by
-- SqliteStorageAdapter.totalVocabNames -- counts orphaned names too, since it does not join
-- back to nodes; see that function's own comment for why that is left as is.)
CREATE TABLE IF NOT EXISTS name_segment_vocab (
    segment TEXT NOT NULL,
    name TEXT NOT NULL,
    PRIMARY KEY (segment, name)
) WITHOUT ROWID;
