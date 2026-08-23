-- Identifier segment vocabulary: maps a sub-word of a compound identifier (e.g. "distribution"
-- from "RungDistribution") to the whole node labels that contain it, so a caller can look one
-- up directly -- something nodes_fts's tokenizer cannot serve, because it treats a compound
-- identifier with no separator as a single token and never indexes its parts on their own (see
-- SqliteStorageAdapter.ftsLabelFor for the index-time workaround this table complements).
--
-- Keyed on (segment, name), not (segment, node_id): node ids in this codebase average hundreds
-- of characters (an id concatenates two declaration-site ids -- see the comment above
-- BULK_CHUNK_SIZE in SqliteStorageAdapter.kt), and thousands of nodes can share one label, so
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
-- PRAGMA foreign_keys (see deleteNodesForArtifact's comment in SqliteStorageAdapter.kt).
-- Callers re-verify against nodes(label) at query time; idx_nodes_label (V3) already covers
-- that join without a full scan, so no further index is added here.
CREATE TABLE IF NOT EXISTS name_segment_vocab (
    segment TEXT NOT NULL,
    name TEXT NOT NULL,
    PRIMARY KEY (segment, name)
) WITHOUT ROWID;
