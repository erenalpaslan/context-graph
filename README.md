# ContextGraph

> Code understanding engine for AI agents. Builds a scope-correct graph of your codebase
> from tree-sitter ASTs, and answers a question in one call — matched modules, relevant
> symbols, **verbatim source**, resolved call edges, and blast radius.

Most tools hand an agent pointers and let it go read files. ContextGraph parses your
repository into a graph of symbols and the resolved edges between them, stores it in local
SQLite, and serves it over the [Model Context Protocol](https://modelcontextprotocol.io).
Ask "how does session revocation work?" and one `explore` call comes back with the modules
that matter, the source of the symbols involved, what calls them, and what breaks if you
change them.

**Languages.** Tree-sitter grammars for Kotlin, Java, TypeScript, TSX, JavaScript, Python,
Swift, Objective-C and Go. Symbols carry scope-correct identity — a `getName` in one class is
not confused with a `getName` in another.

**Also ingested.** Markdown, PDF, SQL schemas and config files land in the same graph, so
a design doc and the code implementing it are connected rather than merely co-located.

**Local.** SQLite on your disk. No service, no upload. LLM calls happen only if you turn
them on.

## Quick Start

Requires Java 17+.

```bash
# Build the launcher once
./gradlew :modules:cli:installDist
export PATH="$PWD/modules/cli/build/install/contextgraph/bin:$PATH"

# Set up and index a project
cd /path/to/your/project
contextgraph init
contextgraph index .

# Ask it things
contextgraph search "authentication"
contextgraph report          # GRAPH_REPORT.md + interactive graph.html

# Serve it to an agent
contextgraph serve-mcp
```

Working on the ContextGraph sources themselves? `./gradlew :modules:cli:run --args="index ."`
runs the same CLI without installing, at the cost of a Gradle startup per command.

## MCP Integration

Point your MCP client at the installed launcher:

```json
{
  "mcpServers": {
    "contextgraph": {
      "command": "/absolute/path/to/modules/cli/build/install/contextgraph/bin/contextgraph",
      "args": ["serve-mcp"]
    }
  }
}
```

### The primary tool

| Tool | Description |
|------|-------------|
| `contextgraph.explore` | **Answer a natural-language question in one call.** Returns matched modules with descriptions, relevant symbols with verbatim source, their resolved edges (each with confidence and resolution rung), and blast radius. Caps its response at a token budget — top-ranked symbols carry full source, the rest carry signature and location and are marked `elided`. |

`explore` exists because agents choose badly among many similar tools and chain thin calls
expensively. Prefer it for almost every question.

### Secondary tools

The other ten remain for when you need one specific thing, and mostly return pointers you
then have to follow:

| Tool | Description |
|------|-------------|
| `contextgraph.index_project` | Index a directory |
| `contextgraph.search_nodes` | Full-text + type-filtered search |
| `contextgraph.get_node` | Fetch node with provenance |
| `contextgraph.expand_node` | BFS neighborhood expansion |
| `contextgraph.find_path` | Shortest path between two nodes |
| `contextgraph.get_evidence` | Full provenance chain for a node |
| `contextgraph.impact_analysis` | Everything pointing at a node — callers, dependents |
| `contextgraph.related_files` | Source files associated with a node |
| `contextgraph.build_context` | Ranked context bundle for a task description |
| `contextgraph.generate_report` | Generate report and visualization |

The server also exposes 6 resources (`contextgraph://project`, `…/graph/nodes`,
`…/graph/edges`, `…/artifacts`, `…/reports/summary`, `…/clusters`) and 4 prompts.

## Benchmarks

Measured against [CodeGraph](https://github.com/colbymchenry/codegraph) 1.5.0 and ripgrep on
the same questions and the same corpus, scored by mean reciprocal rank and recall of the
files a human answer cites.

**Retrieval — excalidraw (TypeScript), 9 questions:**

| | MRR | R@5 | R@10 |
|---|---|---|---|
| **ContextGraph** | **0.556** | **0.398** | **0.426** |
| CodeGraph | 0.244 | 0.222 | 0.222 |
| ripgrep | 0.140 | 0.148 | 0.148 |

**The three rows are not from one measurement run.** ContextGraph's is the current shipped
configuration, measured over five cold cycles against a frozen copy of the corpus. CodeGraph's
and ripgrep's are from the earlier three-way run, where all three sides answered these same
nine questions under identical conditions and ContextGraph scored 0.482 / 0.343 / 0.370; the
comparators were not re-measured when the identifier-segment vocabulary landed. Read the gap
as indicative, not as a controlled result.

ContextGraph's own progression on those nine questions is a like-for-like comparison:
**0.133** MRR before ranking became a function of the query, **0.482** after, **0.556** once
identifier segments were materialised at index time.

**Ingest — Keycloak (Java, 234k nodes / 623k edges):** 142m 55.6s → **8m 16.4s**, a 17.3×
speedup producing a byte-identical graph. The cause of the original cost was one SQLite
connection per row written. The segment vocabulary has since added roughly 27% back on that
corpus, bought deliberately for the retrieval gain above.

**What these numbers do not say.** The retrieval result is nine questions, one repository,
one language — it is a signal, not a proof. Keycloak's retrieval side is unmeasured: the
benchmark's integrity gate refused it over a single gold-cited file that is a
`META-INF/services/` resource rather than source. And ingest, after the 17.3× win, remains
roughly an order of magnitude slower than CodeGraph's 39.7s on that corpus; ContextGraph
indexes more of the right files (25/26 gold files on Keycloak, 21/21 on excalidraw, against
CodeGraph's 22/26 and 17/21) and pays for it in time.

Full methodology, per-signal ablation, the cost of each mechanism, and the reasoning behind
every signal that did *not* ship:
[`docs/retrieval-ranking-ablation.md`](docs/retrieval-ranking-ablation.md),
[`docs/identifier-segment-vocabulary.md`](docs/identifier-segment-vocabulary.md) and
[`docs/ingest-cost.md`](docs/ingest-cost.md).

## CLI Reference

| Command | Description |
|---------|-------------|
| `init` | Initialize `.contextgraph/` in the current directory |
| `index <path>` | Index a directory into the knowledge graph |
| `refresh` | Re-parse changed files and bring the local graph in sync with the working tree |
| `watch` | Keep the local graph current as files change. Opt-in: requires `watcher.enabled=true` |
| `ci-reindex [path]` | CI-only: reindex from scratch and write the committed baseline. Refuses to run with a TTY attached |
| `search <query>` | Search the graph. `--limit`, `--min-confidence`, `--semantic` |
| `node <id>` | Show a node with its properties and provenance |
| `expand <id> [--depth N]` | BFS neighborhood expansion from a node |
| `path <fromId> <toId>` | Shortest path between two nodes |
| `report [--output dir]` | Generate `GRAPH_REPORT.md` and `graph.html` |
| `describe-modules` | Author LLM descriptions for modules lacking one, embed them, and flag descriptions made stale by a changed symbol inventory. `--regenerate-stale` |
| `export [file.json]` | Export full graph snapshot to JSON |
| `serve-mcp` | Start the MCP server over stdio |
| `config set <key> <value>` | Update configuration |

## How It Works

```
FileDiscovery → ArtifactTypeDetector → ExtractorRegistry → ResourceExtractor(s)
                                                (TreeSitter, Markdown, PDF, SQL, Config)
                                                                   ↓
                                     ReferenceResolver → EntityResolver → GraphBuilder
                                                                   ↓
                                                   SqliteStorageAdapter (SQLite + FTS5)
                                                                   ↓
                                   CLI / MCP Server / Report / Visualization / Benchmark
```

Files are discovered and routed to extractors. Source files are parsed to a tree-sitter AST
and walked for declarations, which get scope-correct identities rather than bare names.
References that cannot be resolved within a file are held and resolved afterwards through a
ladder of increasingly permissive strategies, each edge recording which rung resolved it and
with what confidence. Nodes and edges are written to local SQLite in batches — through
`writeArtifactBatch` / `upsertNodes` / `upsertEdges` on `StorageAdapter` — with FTS5 for
search. Re-indexing skips unchanged files by checksum.

## Modules

| Module | Description |
|--------|-------------|
| `core` | Domain models, graph schema, `StorageAdapter` and `ResourceExtractor` contracts |
| `ingest` | File discovery, checksum tracking, pipeline, reference resolution ladder |
| `extractors` | Tree-sitter, Markdown, PDF, SQL, config, and LLM-semantic extractors |
| `tree-sitter` | Native grammar loading and scope-correct symbol identity for 8 languages |
| `graph` | Graph builder, entity resolution, JGraphT algorithms |
| `storage-sqlite` | SQLite + FTS5 persistence with Flyway migrations and batch writes |
| `query` | Traversal, path finding, evidence retrieval, query-aware relevance ranking |
| `mcp-server` | MCP server exposing 11 tools, 6 resources and 4 prompts |
| `cli` | Command-line interface (Clikt), watcher, CI reindex |
| `report` | `GRAPH_REPORT.md` generation |
| `visualization` | Interactive `graph.html` export (D3.js) |
| `eval` | Grades `explore` answers against curated questions |
| `benchmark` | Three-way retrieval and ingest-cost benchmark vs CodeGraph and ripgrep |

## Configuration

`.contextgraph/config.json`, created by `init`:

| Key | Default | Purpose |
|-----|---------|---------|
| `includePatterns` | `["**/*"]` | Glob patterns to include |
| `excludePatterns` | build dirs, `.git`, … | Glob patterns to exclude |
| `maxFileSizeBytes` | 10 MB | Files larger than this are skipped |
| `ignoreSecrets` | `true` | Skip files that look like secrets |
| `moduleRoots` | `[]` | Explicit module roots, when layout is not inferable |
| `watcher.enabled` | `false` | Opt-in file watcher for `watch` |
| `watcher.debounceMillis` | `500` | Coalesce bursts of file events |
| `watcher.fallbackIntervalMillis` | `30000` | Full rescan interval, covers dropped watches |
| `litellm.enabled` | `false` | Enable LLM-powered semantic extraction |
| `litellm.baseUrl` | `http://localhost:4000` | LiteLLM proxy endpoint |
| `litellm.model` | `gpt-4o` | Model for semantic extraction |
| `litellm.rateLimitPerMinute` | `10` | Rate limit for LLM calls |

## Semantic Extraction

Optional. Enables LLM-authored module descriptions, module embeddings for
`search --semantic`, and richer concept extraction, all via a LiteLLM proxy:

```bash
litellm --model claude-opus-4-7
```

```bash
contextgraph config set litellm.enabled true
contextgraph config set litellm.base-url http://localhost:4000
contextgraph config set litellm.model claude-opus-4-7
contextgraph describe-modules
```

Everything else works with LLM calls disabled — that is the default, and CI enforces it.

## Building

```bash
./gradlew build
./gradlew test
```

Contributor and agent guidance lives in [`CLAUDE.md`](CLAUDE.md).
