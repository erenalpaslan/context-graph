-- ReferenceResolver's pass 2 calls StorageAdapter.findNodesByLabel once per unresolved
-- reference (io.contextgraph.ingest.ReferenceResolver). Without an index on nodes(label),
-- every call is a full table scan of the nodes table.
CREATE INDEX IF NOT EXISTS idx_nodes_label ON nodes(label);
