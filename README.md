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

<!-- benchmarks:start -->
## Benchmarks

Four sides answered the same 33 questions over the same four repositories, scored against
the same expected files — the ones each question's own gold facts cite — by mean reciprocal
rank and recall. No LLM is in the loop anywhere: the same corpus and the same questions
always produce the same numbers.

- **ContextGraph (this project)** — this repository's own graph, queried through
  `QueryEngine.buildContext`.
- **CodeGraph (third-party)** — `@colbymchenry/codegraph` 1.5.0, driven as `codegraph
  explore`.
- **bash (base-system shell only)** — stock `grep` over a never-indexed checkout. **No
  third-party tool is installed, invoked or assumed:** this is the floor a developer with
  nothing but a shell already has, and the honest thing an index has to earn its cost
  against.
- **ripgrep (third-party)** — `rg` over the same never-indexed checkout, given the same
  derived tokens. A separate install, kept as the comparator the earlier runs used.

**These rows all come from one measurement run.** Every side was handed the same raw
question text, against the same working copies at the same pinned commits, and scored
against the same gold-derived expected files with the same metrics; no side's output was
re-ranked, filtered or truncated before scoring. The section this replaces had to warn that
its comparator rows came from an earlier run. That caveat is gone because the run it
described has been superseded, not because it stopped mattering.

**Retrieval, per repository** — each repo's own questions, its negative control included:

| Repo | Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) |
|---|---|---|---|---|---|
| `calcom` (8) | MRR | **0.369** | 0.125 | 0.152 | 0.152 |
| `calcom` (8) | recall@10 | **33.8%** | 3.1% | 7.5% | 7.5% |
| `excalidraw` (9) | MRR | **0.556** | 0.244 | 0.140 | 0.140 |
| `excalidraw` (9) | recall@10 | **42.6%** | 22.2% | 14.8% | 14.8% |
| `gin` (8) | MRR | 0.656 | **0.813** | 0.352 | 0.352 |
| `gin` (8) | recall@10 | **92.7%** | 83.3% | 82.3% | 82.3% |
| `keycloak` (8) | MRR | **0.264** | 0.056 | 0.223 | 0.223 |
| `keycloak` (8) | recall@10 | 17.3% | 8.8% | **39.6%** | **39.6%** |

The number after each repo is how many questions it contributes. **Bold is the leading
column in that row**, whoever it is.

**ContextGraph (this project) does not lead every row, and the rows it loses are in the
table above rather than only in the full report:** on `gin` CodeGraph (third-party) leads on
MRR, 0.813 against 0.656; on `keycloak` bash (base-system shell only) and ripgrep
(third-party) lead on recall@10, 39.6% against 17.3%. Plain `grep` pays nothing for an index
and still retrieves more than this project's index does on `keycloak`.

**Pooled over the 29 headline questions**, which excludes the four negative-control
questions the full report scores separately — so the pooled row and the per-repo rows above
are different pools and do not average into each other:

| Metric | ContextGraph (this project) | CodeGraph (third-party) | bash (base-system shell only) | ripgrep (third-party) |
|---|---|---|---|---|
| precision@5 | **20.0%** | 12.4% | 11.0% | 11.0% |
| precision@10 | **12.4%** | 6.2% | 8.3% | 8.3% |
| recall@5 | **38.7%** | 28.0% | 27.0% | 27.0% |
| recall@10 | **46.0%** | 28.0% | 35.1% | 35.1% |
| MRR | **0.485** | 0.326 | 0.218 | 0.218 |

This project's own progression on `excalidraw`'s nine questions is like-for-like across
three published runs: **0.133** MRR before ranking became a function of the query, **0.482**
after, **0.556** here, once identifier segments were materialised at index time.

**Keycloak is measured this time.** The previous run published its retrieval side as
unmeasured: `IndexIntegrityGate` refused it over a single gold-cited file, a
`META-INF/services/` provider-configuration entry that was missing from the index. The gate
was not relaxed and no gold fact was dropped — the indexing gap behind it was fixed. File
discovery had been treating `org.keycloak.credential.hash.PasswordHashProviderFactory` as a
secret, because the sensitive-filename heuristic matched words in it; a ServiceLoader
registration file is named after the interface it registers and never after its own
contents, so those entries are now exempt from that heuristic. Keycloak's index now holds 26
of 26 gold-cited files and the gate passes on its own terms.

**An index cannot retrieve a file it never indexed**, so coverage of the gold-cited files is
published beside the scores rather than after them. ContextGraph (this project) holds 19/19
on `calcom`, 21/21 on `excalidraw`, 10/10 on `gin`, 26/26 on `keycloak`; CodeGraph
(third-party) holds 9/19 on `calcom`, 17/21 on `excalidraw`, 10/10 on `gin`, 22/26 on
`keycloak`. Both text-search sides read the working tree directly and so reach every file by
construction.

**Ingest — what each index cost to build:**

| Repo | ContextGraph (this project) | CodeGraph (third-party) |
|---|---|---|
| `calcom` | 1m 38s, 268.6 MB | 1m 25s, 306.7 MB |
| `excalidraw` | 22.9s, 32.7 MB | 17.8s, 36.5 MB |
| `gin` | 2.7s, 8.2 MB | reused existing index, 7.9 MB |
| `keycloak` | 4m 14s, 1.58 GB | 1m 53s, 811.6 MB |

**Building the index costs this project more than it costs CodeGraph (third-party).** On
every repo where both indexes were built in this run it is slower, by 1.15× on `calcom`,
1.29× on `excalidraw`, 2.24× on `keycloak`. The claim this replaces — that ingest was
*roughly an order of magnitude* slower than the third-party tool's — rested on an earlier
run's figures and no longer holds; the widest gap measured here is `keycloak`, at 4m 14s
against 1m 53s. Not every row is a comparison: `gin`'s CodeGraph (third-party) index was
already built when this run found it and was not rebuilt, so its cost belongs to the run
that paid it and is deliberately not carried forward. On `keycloak`, the largest index here,
it also occupies 1.95× the disk (1.58 GB against 811.6 MB). Both text-search sides have no
ingest step at all and pay nothing before the first query — which is the number every row
above is being compared against. Earlier work on ingest cost
([`docs/ingest-cost.md`](docs/ingest-cost.md)) took Keycloak from 142m 55.6s to 8m 16.4s,
17.3×, by batching SQLite writes; the figure in the table is this run's own measurement on
its own corpus and machine, not a further speedup claimed on top of it.

**What these numbers do not say.** 33 questions over four repositories is a much larger
claim than the single repository this section used to report, and it is still not a proof:
four repositories are not "code in general"; the questions and the gold facts they are
scored against were written by this project, which is the single largest thing a reader
should discount for; and each figure is one run rather than a distribution. The two
text-search columns come out identical at every `k` measured here, so the margin over
ripgrep (third-party) on this corpus is a margin over `grep` — ripgrep's own engineering
bought nothing a stock shell did not already reach, and the baseline should be read as a
plain one rather than a strong one. Nothing here measures answer quality: the metric is
which files a tool puts in front of you, not what an agent then does with them, and the
agent-level axis is measured separately and never mixed with this one. Ingest was timed once
per repo per tool on one machine, with no repeats and no variance. And the rows this project
loses — `gin`'s MRR, `keycloak`'s recall@10 — are part of the result, not exceptions to it.

**The same run, question by question, as a page you can interrogate:**
[`docs/benchmarks/index.html`](docs/benchmarks/index.html) lists every one of the 33
questions with all four sides' scores, filters by repository and category, sorts by any
metric, and opens a question to show its expected files against each side's own ranked
answer. It is generated from the same result document as everything above and needs no
server: open it from a clone with the network off and it works.

Full methodology, the per-question breakdown, the flags both text-search sides were given,
every skip and every place this project loses:
[`modules/benchmark/results/four-way/BENCHMARKS.md`](modules/benchmark/results/four-way/BENCHMARKS.md).
Per-signal ablation, the cost of each mechanism, and the reasoning behind every signal that
did *not* ship: [`docs/retrieval-ranking-ablation.md`](docs/retrieval-ranking-ablation.md),
[`docs/identifier-segment-vocabulary.md`](docs/identifier-segment-vocabulary.md) and
[`docs/ingest-cost.md`](docs/ingest-cost.md).

<!-- benchmarks:end -->

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
| `benchmark` | Four-way retrieval and ingest-cost benchmark: this project, CodeGraph, ripgrep and base-system `grep` |

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
