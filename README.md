# ContextGraph

> Build trusted knowledge graphs from code, documents, research, and diagrams so humans and AI agents can understand complex projects faster.

ContextGraph is a resource-agnostic knowledge graph engine that turns code, documents, research papers, diagrams, schemas, and other project resources into a connected, queryable knowledge graph.

## Quick Start

```bash
# Index your project
./gradlew :modules:cli:run --args="index /path/to/your/project"

# Search the graph
./gradlew :modules:cli:run --args='search "authentication"'

# Generate report + visualization
./gradlew :modules:cli:run --args="report"

# Start MCP server (for use with Claude Desktop / AI agents)
./gradlew :modules:cli:run --args="serve-mcp"
```

## Building

Requires Java 17+.

```bash
./gradlew build
```

## Architecture

```
Input resources
  ↓ Resource detector
  ↓ Extractor registry
  ↓ Extraction results
  ↓ Entity resolver
  ↓ Graph builder
  ↓ Graph store (SQLite + FTS5)
  ↓ Query engine
  ↓ CLI / MCP / Reports / Visualization
```

## Modules

| Module | Description |
|--------|-------------|
| `core` | Domain models, graph schema, shared types |
| `ingest` | File discovery, resource detection, checksum tracking |
| `extractors` | Code, Markdown, PDF, SQL, config, semantic extractors |
| `graph` | Graph builder, entity resolution, JGraphT algorithms |
| `storage-sqlite` | SQLite + FTS5 persistence with Flyway migrations |
| `query` | Graph traversal, path finding, evidence retrieval |
| `mcp-server` | MCP server exposing 10 tools to AI agents |
| `cli` | Command-line interface (Clikt) |
| `report` | GRAPH_REPORT.md generation |
| `visualization` | Interactive graph.html export (D3.js) |

## Semantic Extraction (LiteLLM)

ContextGraph supports LLM-powered semantic extraction via LiteLLM. Configure in `.contextgraph/config.json`:

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

Run your preferred LiteLLM backend:
```bash
litellm --model claude-opus-4-7
```

## MCP Integration

Add to your Claude Desktop config:
```json
{
  "mcpServers": {
    "contextgraph": {
      "command": "/path/to/contextgraph",
      "args": ["serve-mcp"]
    }
  }
}
```
