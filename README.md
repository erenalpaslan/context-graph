# ContextGraph

> Knowledge graph engine that indexes code, docs, PDFs, and schemas into a queryable graph — query via CLI or expose to AI agents through an MCP server.

ContextGraph turns your project's files — source code, Markdown docs, research papers, SQL schemas, config files — into a connected, queryable knowledge graph stored in SQLite. Use it to understand large codebases, surface relationships between concepts, or give AI agents structured context about your project.

## Quick Start

Requires Java 17+.

```bash
# Initialize in your project
./gradlew :modules:cli:run --args="init"

# Index your project
./gradlew :modules:cli:run --args="index /path/to/your/project"

# Search the graph
./gradlew :modules:cli:run --args='search "authentication"'

# Generate a Markdown report + interactive HTML visualization
./gradlew :modules:cli:run --args="report"

# Start MCP server for AI agents
./gradlew :modules:cli:run --args="serve-mcp"
```

## CLI Reference

| Command | Description |
|---------|-------------|
| `init` | Initialize `.contextgraph/` in the current directory |
| `index <path>` | Index a directory into the knowledge graph |
| `search <query>` | Full-text search across graph nodes |
| `node <id>` | Show a node with its properties and provenance |
| `expand <id> [--depth N]` | BFS neighborhood expansion from a node |
| `path <fromId> <toId>` | Shortest path between two nodes |
| `report [--output dir]` | Generate `GRAPH_REPORT.md` and `graph.html` |
| `export [file.json]` | Export full graph snapshot to JSON |
| `serve-mcp` | Start the MCP server over stdio |
| `config set <key> <value>` | Update configuration |

## Building

```bash
./gradlew build
./gradlew test
```

## How It Works

```
FileDiscovery → ArtifactTypeDetector → ExtractorRegistry → ResourceExtractor(s)
                                                                   ↓
                                             EntityResolver → GraphBuilder
                                                                   ↓
                                                     SqliteStorageAdapter (SQLite + FTS5)
                                                                   ↓
                                   CLI / MCP Server / Report / Visualization
```

Files are discovered, routed to the appropriate extractor(s), and the resulting nodes and edges are written to a local SQLite database with full-text search. Incremental re-indexing skips unchanged files via checksum tracking.

## Modules

| Module | Description |
|--------|-------------|
| `core` | Domain models, graph schema, shared types |
| `ingest` | File discovery, resource detection, checksum tracking |
| `extractors` | Code, Markdown, PDF, SQL, config, and semantic extractors |
| `graph` | Graph builder, entity resolution, JGraphT algorithms |
| `storage-sqlite` | SQLite + FTS5 persistence with Flyway migrations |
| `query` | Graph traversal, path finding, evidence retrieval, context bundling |
| `mcp-server` | MCP server exposing 10 tools and 4 prompts to AI agents |
| `cli` | Command-line interface (Clikt) |
| `report` | `GRAPH_REPORT.md` generation |
| `visualization` | Interactive `graph.html` export (D3.js) |

## MCP Integration

ContextGraph exposes 10 tools to AI agents via the [Model Context Protocol](https://modelcontextprotocol.io):

| Tool | Description |
|------|-------------|
| `contextgraph.index_project` | Index a directory |
| `contextgraph.search_nodes` | Full-text + type-filtered search |
| `contextgraph.get_node` | Fetch node with provenance |
| `contextgraph.expand_node` | BFS neighborhood expansion |
| `contextgraph.find_path` | Shortest path between two nodes |
| `contextgraph.get_evidence` | Full provenance chain for a node |
| `contextgraph.impact_analysis` | Reverse dependency analysis |
| `contextgraph.related_files` | Source files associated with a node |
| `contextgraph.build_context` | Ranked context bundle for a task |
| `contextgraph.generate_report` | Generate report and visualization |

Add to your Claude Desktop config:

```json
{
  "mcpServers": {
    "contextgraph": {
      "command": "/path/to/gradlew",
      "args": [":modules:cli:run", "--args=serve-mcp"]
    }
  }
}
```

## Semantic Extraction

Enable LLM-powered extraction for richer concept and relationship detection via LiteLLM:

```bash
litellm --model claude-opus-4-7
```

```bash
./gradlew :modules:cli:run --args="config set litellm.enabled true"
./gradlew :modules:cli:run --args="config set litellm.base-url http://localhost:4000"
./gradlew :modules:cli:run --args="config set litellm.model claude-opus-4-7"
```

Or edit `.contextgraph/config.json` directly:

```json
{
  "litellm": {
    "baseUrl": "http://localhost:4000",
    "model": "claude-opus-4-7",
    "rateLimitPerMinute": 10,
    "enabled": true
  }
}
```
