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
./gradlew :modules:cli:run --args='search "authentication"'
./gradlew :modules:cli:run --args="node <nodeId>"
./gradlew :modules:cli:run --args="expand <nodeId> --depth 3"
./gradlew :modules:cli:run --args="path <fromId> <toId>"
./gradlew :modules:cli:run --args="report"
./gradlew :modules:cli:run --args="serve-mcp"
./gradlew :modules:cli:run --args="export graph.json"
./gradlew :modules:cli:run --args="config set litellm.enabled true"
```

## Architecture

The pipeline flows left-to-right:

```
FileDiscovery → ArtifactTypeDetector → ExtractorRegistry → ResourceExtractor(s)
                                                                     ↓
                                               EntityResolver → GraphBuilder
                                                                     ↓
                                                           SqliteStorageAdapter (SQLite + FTS5)
                                                                     ↓
                              QueryEngine / McpServer / CLI / ReportGenerator / GraphHtmlExporter
```

**Key design choices:**
- `IngestPipeline` runs extraction concurrently (Coroutines + `Dispatchers.IO`) but funnels all DB writes through a single `Channel` consumer to avoid SQLite lock contention.
- `StorageAdapter` is the only interface between the graph domain and persistence — swap implementations without touching anything else.
- `ResourceExtractor` is the extension point for adding new file types: implement the interface, register in `ExtractorRegistry`.
- `NodeType` uses sealed interfaces with `data object` singletons for all well-known types plus `Custom(name)` for open-ended extension.

## Module Dependency Graph

```
core  ←  ingest  ←  extractors  ←  graph
  ↑                                  ↑
storage-sqlite                     query  ←  mcp-server
                                     ↑
                              report / visualization / cli
```

`core` has no internal dependencies — it defines the domain (`GraphNode`, `GraphEdge`, `Artifact`, `StorageAdapter`, `ResourceExtractor`, `ContextGraphConfig`).

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

## MCP Server

The server exposes 10 tools over stdio: `index_project`, `search_nodes`, `get_node`, `expand_node`, `find_path`, `get_evidence`, `impact_analysis`, `related_files`, `build_context`, `generate_report`. It also exposes 6 resources (`contextgraph://project`, `…/graph/nodes`, `…/graph/edges`, `…/artifacts`, `…/reports/summary`, `…/clusters`) and 4 prompts.

## Testing

Tests use **Kotest** (JUnit5 runner). Test fixtures live in `test-fixtures/` with pre-built `.contextgraph/graph.db` databases for integration tests.
