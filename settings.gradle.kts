rootProject.name = "contextgraph"

include(
    ":modules:core",
    ":modules:ingest",
    ":modules:extractors",
    ":modules:tree-sitter",
    ":modules:graph",
    ":modules:storage-sqlite",
    ":modules:query",
    ":modules:mcp-server",
    ":modules:cli",
    ":modules:report",
    ":modules:visualization",
    ":modules:eval"
)
