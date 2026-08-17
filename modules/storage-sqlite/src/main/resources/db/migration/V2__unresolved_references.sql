CREATE TABLE IF NOT EXISTS unresolved_references (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    artifact_id TEXT NOT NULL,
    repo_relative_path TEXT NOT NULL,
    reference_name TEXT NOT NULL,
    referring_symbol_id TEXT NOT NULL,
    line INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_unresolved_references_artifact ON unresolved_references(artifact_id);
CREATE INDEX IF NOT EXISTS idx_unresolved_references_name ON unresolved_references(reference_name);
