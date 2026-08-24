# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

Requires Java 17+.

```bash
# Build all modules
./gradlew build

# Run CLI
./gradlew :modules:cli:run --args="<command>"

# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :modules:extractors:test

# Run a single test class
./gradlew :modules:query:test --tests "io.contextgraph.query.QueryEngineTest"
```

### CLI Commands

```bash
./gradlew :modules:cli:run --args="init"                   # Init .contextgraph/ in cwd
./gradlew :modules:cli:run --args="index /path/to/project" # Index a directory
./gradlew :modules:cli:run --args="refresh"                # Re-parse changed files into the local overlay
./gradlew :modules:cli:run --args="watch"                  # Watcher daemon; needs watcher.enabled=true
./gradlew :modules:cli:run --args="ci-reindex ."           # CI only — writes the committed baseline
./gradlew :modules:cli:run --args='search "authentication"'
./gradlew :modules:cli:run --args='search "auth" --semantic'
./gradlew :modules:cli:run --args="node <nodeId>"
./gradlew :modules:cli:run --args="expand <nodeId> --depth 3"
./gradlew :modules:cli:run --args="path <fromId> <toId>"
./gradlew :modules:cli:run --args="report"
./gradlew :modules:cli:run --args="describe-modules"       # LLM module descriptions + embeddings
./gradlew :modules:cli:run --args="serve-mcp"
./gradlew :modules:cli:run --args="mcp bind"    # print this project's .mcp.json block
./gradlew :modules:cli:run --args="export graph.json"
./gradlew :modules:cli:run --args="config set litellm.enabled true"
```

`describe-modules` takes `--regenerate-stale` to regenerate descriptions a prior run
flagged stale rather than only flagging them. `ci-reindex` refuses to run with an
interactive terminal attached (`CONTEXTGRAPH_CI=true` overrides) — that refusal is what
keeps the committed baseline single-writer.

For a launcher without Gradle startup per command:
`./gradlew :modules:cli:installDist` puts a `contextgraph` binary in
`modules/cli/build/install/contextgraph/bin/`.

Released builds are a single self-contained jar instead — `./gradlew :modules:cli:shadowJar`
produces `modules/cli/build/libs/contextgraph-cli-<version>-all.jar`, carrying every
dependency and the tree-sitter natives for **both** macOS arm64 and Linux x64. That one file
is what `brew install contextgraph` and the composite action each fetch. Because
`compileTreeSitterGrammars` only ever builds host-platform natives, assembling it takes two
runners — see `.github/workflows/release.yml` and `modules/tree-sitter/prebuilt-natives/`.

## Architecture

The pipeline flows left-to-right:

```
FileDiscovery → ArtifactTypeDetector → ExtractorRegistry → ResourceExtractor(s)
                                          (TreeSitterExtractor, Markdown, PDF, SQL, Config, Semantic)
                                                                     ↓
                            ReferenceResolver → EntityResolver → GraphBuilder
                                                                     ↓
                                                           SqliteStorageAdapter (SQLite + FTS5)
                                                                     ↓
                              QueryEngine / McpServer / CLI / ReportGenerator / GraphHtmlExporter
```

**Key design choices:**
- `IngestPipeline` runs extraction concurrently (Coroutines + `Dispatchers.IO`) but funnels all DB writes through a single `Channel` consumer to avoid SQLite lock contention.
- **Writes go through the batch seam on `StorageAdapter` — `writeArtifactBatch`, `upsertNodes`, `upsertEdges` — not one call per row.** ContextGraph previously opened, prepared, executed and closed a separate SQLite connection for every row; that alone accounted for a 17.3× ingest slowdown (Keycloak: 142m 55.6s → 8m 16.4s). Do not reintroduce per-row writes. See `docs/ingest-cost.md`.
- Source files are parsed to a tree-sitter AST and walked for declarations. Symbols get **scope-correct identity** (`DeclarationSiteId`, `Fqn` in `modules:tree-sitter`), so same-named members of different types stay distinct.
- References unresolvable within a file are held and resolved afterwards by `ReferenceResolver` through `ResolutionLadder`'s increasingly permissive rungs. Every edge records which rung resolved it and at what confidence — that provenance is what `explore` and `impact_analysis` surface.
- `StorageAdapter` is the only interface between the graph domain and persistence — swap implementations without touching anything else.
- `ResourceExtractor` is the extension point for adding new file types: implement the interface, register in `ExtractorRegistry`.
- `NodeType` uses sealed interfaces with `data object` singletons for all well-known types plus `Custom(name)` for open-ended extension.
- `ReindexPrimitive` (in `modules:ingest`) is the single reindex call site shared by `index`/`refresh`, the watcher, `ci-reindex` and the MCP `index_project` tool. It takes both locks — an in-JVM `ReentrantLock` and a cross-process `<dbPath>.lock`. Reindexing outside it can corrupt the graph.

## Module Dependency Graph

Thirteen modules. `a → b` means "a depends on b".

```
core                    (no internal dependencies)
  ↑
  ├── tree-sitter ← extractors ← ingest
  ├── storage-sqlite ────────────↑
  ├── graph
  ├── query          → core, graph, storage-sqlite
  ├── report         → core, graph, storage-sqlite
  └── visualization  → core, storage-sqlite

mcp-server → core, extractors, graph, ingest, query, report, storage-sqlite, visualization
cli        → all of the above, plus mcp-server
eval       → core, ingest, mcp-server, storage-sqlite
benchmark  → core, extractors, graph, ingest, mcp-server, query, storage-sqlite, cli
```

`core` has no internal dependencies — it defines the domain (`GraphNode`, `GraphEdge`, `Artifact`, `StorageAdapter`, `ResourceExtractor`, `ContextGraphConfig`).

Note the direction between `ingest` and `extractors`: **`ingest` depends on `extractors`**, which depends on `tree-sitter`. `eval` grades `explore` answers against curated questions; `benchmark` runs the four-way retrieval and ingest-cost comparison against CodeGraph, a base-system `grep` baseline and ripgrep, and is the only module that depends on `cli`.

## Project Configuration

Each indexed project needs `.contextgraph/config.json` (auto-created by `init`). Key options:

| Key | Default | Purpose |
|-----|---------|---------|
| `litellm.enabled` | `false` | Enable LLM-powered semantic extraction |
| `litellm.baseUrl` | `http://localhost:4000` | LiteLLM proxy endpoint |
| `litellm.model` | `gpt-4o` | Model for semantic extraction |
| `litellm.rateLimitPerMinute` | `10` | Rate limit for LLM calls |
| `includePatterns` | `["**/*"]` | Glob patterns to include |
| `excludePatterns` | build dirs, `.git`, etc. | Glob patterns to exclude |
| `maxFileSizeBytes` | `10 MB` | Files larger than this are skipped |
| `ignoreSecrets` | `true` | Skip files that look like secrets |
| `moduleRoots` | `[]` | Explicit module roots where layout is not inferable |
| `watcher.enabled` | `false` | Opt-in; `watch` refuses to start without it |
| `watcher.debounceMillis` | `500` | Coalesce bursts of filesystem events |
| `watcher.fallbackIntervalMillis` | `30000` | Full rescan interval, covers dropped watch registrations |

## MCP Server

The server exposes **11 tools** over stdio. `contextgraph.explore` is the primary one: it answers a natural-language question in a single call, returning matched modules, relevant symbols with **verbatim source**, their resolved edges (with confidence and resolution rung), and blast radius, capped at a token budget with lower-ranked symbols marked `elided`. It exists because agents choose badly among many thin tools; prefer it.

The other ten are secondary and mostly return pointers: `index_project`, `search_nodes`, `get_node`, `expand_node`, `find_path`, `get_evidence`, `impact_analysis`, `related_files`, `build_context`, `generate_report`.

It also exposes 6 resources (`contextgraph://project`, `…/graph/nodes`, `…/graph/edges`, `…/artifacts`, `…/reports/summary`, `…/clusters`) and 4 prompts (`explain_codebase`, `find_context_for_task`, `analyze_change_impact`, `summarize_research`).

## Testing

Tests use **Kotest** (JUnit5 runner). Test fixtures live in `test-fixtures/` as source-only projects (e.g. `test-fixtures/kotlin-project/`) — no `.contextgraph/graph.db` is committed under `test-fixtures/`, since `.gitignore`'s `**/.contextgraph/*` rule excludes every nested `.contextgraph/` directory (only the repo root's own `.contextgraph/graph.db` is the deliberate exception — see below). Tests that need a graph for a fixture build it from that source at test time by running the real pipeline (`FileDiscovery` → extractors → `IngestPipeline` → `SqliteStorageAdapter`) against a temp DB path; this is fast, deterministic, and requires no state beyond a fresh `git clone`. See `modules/cli/src/test/kotlin/io/contextgraph/cli/FixtureGenerationTest.kt` for the pattern.

### Committed graph baseline

The repo root's own `.contextgraph/graph.db` is a committed baseline, written only by
CI, so agents can query a fresh clone before any local indexing has happened.
`.contextgraph/graph.local.db` is the gitignored developer/watcher overlay, seeded by
copying the baseline on first local write. See `GraphDb.kt` for the read/write seam
every caller goes through, and `GraphDbGitIntegrationTest.kt` for the real-git proof
of fresh-clone reads, overlay seeding, and baseline non-modification.
