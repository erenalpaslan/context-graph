# CLAUDE.md

## Build & Run

Java 17 (`jvmToolchain(17)`); Gradle 8.11.1, via `./gradlew`.

```bash
./gradlew build                        # all modules
./gradlew test                         # all tests
./gradlew :modules:<name>:test         # one module
./gradlew :modules:query:test --tests "io.contextgraph.query.QueryEngineTest"
```

`report` and `visualization` have no test sources — `NO-SOURCE` there is a pass.

### CLI

Build the launcher once: `./gradlew :modules:cli:installDist` puts `contextgraph`
in `modules/cli/build/install/contextgraph/bin/`. **Use it, not
`:modules:cli:run`, which runs with `modules/cli` as its working directory** — so
`init`, `index .`, `refresh`, `report` and `export` act there rather than on the
repo, and `serve-mcp` gets no stdin under Gradle and exits at once. Put that
`bin/` on `PATH`; the examples assume it.

```bash
contextgraph init                          # create .contextgraph/ in cwd
contextgraph index /path/to/project
contextgraph refresh                       # changed files → the local overlay
contextgraph watch                         # daemon; needs watcher.enabled=true
contextgraph search "authentication"
contextgraph search "auth" --semantic      # ranks modules by embedding
contextgraph node <id>
contextgraph expand <id> --depth 3
contextgraph path <fromId> <toId>
contextgraph report                        # writes GRAPH_REPORT.md and graph.html
contextgraph export graph.json
contextgraph describe-modules              # LLM descriptions; --regenerate-stale
contextgraph serve-mcp
contextgraph mcp bind                      # prints this project's .mcp.json block
contextgraph config set litellm.enabled true
```

`ci-reindex` rewrites the committed baseline and belongs to CI alone. Its guard is
`System.console() != null`, which stops an interactive human but **not an agent** —
do not run it (`CONTEXTGRAPH_CI=true` overrides the guard).

Released builds are one self-contained jar: `./gradlew :modules:cli:shadowJar` →
`modules/cli/build/libs/contextgraph-cli-<version>-all.jar`, with every dependency
and natives for macOS arm64 **and** Linux x64. It takes two runners, since
`compileTreeSitterGrammars` only builds host natives — see
`.github/workflows/release.yml` and `modules/tree-sitter/prebuilt-natives/`.

## Architecture

```
FileDiscovery → ArtifactTypeDetector → ExtractorRegistry → ResourceExtractor(s)
                                          (TreeSitter, Markdown, PDF, SQL, Config, Semantic)
                                                                     ↓
                            ReferenceResolver → EntityResolver → GraphBuilder
                                                                     ↓
                                                           SqliteStorageAdapter (SQLite + FTS5)
                                                                     ↓
                              QueryEngine / McpServer / CLI / ReportGenerator / GraphHtmlExporter
```

- `IngestPipeline` extracts concurrently (Coroutines + `Dispatchers.IO`) but funnels every DB write through a single `Channel` consumer, avoiding SQLite lock contention.
- **Writes go through the batch seam on `StorageAdapter` — `writeArtifactBatch`, `upsertNodes`, `upsertEdges` — never one call per row.** A separate connection per row cost a 17.3× ingest slowdown. Do not reintroduce it; see `docs/ingest-cost.md`.
- `ReindexPrimitive` (`modules:ingest`) is the single reindex call site, shared by `index`/`refresh`, the watcher, `ci-reindex` and the MCP `index_project` tool. It takes an in-JVM `ReentrantLock` **and** a cross-process `<dbPath>.lock`. Reindexing outside it can corrupt the graph.
- Sources are parsed to a tree-sitter AST and walked for declarations. Symbols get scope-correct identity (`DeclarationSiteId`, `Fqn`), so same-named members of different types stay distinct.
- References unresolvable within a file are held and resolved afterwards by `ReferenceResolver`, up `ResolutionLadder`'s increasingly permissive rungs. Every edge records which rung resolved it and at what confidence — that provenance is what `explore` and `impact_analysis` surface.
- `StorageAdapter` is the only seam between the graph domain and persistence. `ResourceExtractor` is the extension point for new file types: implement it, register it in `ExtractorRegistry`. `NodeType` is sealed, with `data object` singletons plus `Custom(name)`.

## Modules

Thirteen, each at `modules/<name>`. Every module depends on `core`, which has no
internal dependencies; the third column lists the edges beyond that.

| Module | Responsibility | Also depends on |
|---|---|---|
| `core` | Domain types, the `StorageAdapter`/`ResourceExtractor` SPIs, `GraphDb` path seam | — |
| `tree-sitter` | Grammars, JNI native loader, scope-correct symbol identity (`Fqn`) | — |
| `extractors` | The six `ResourceExtractor`s: tree-sitter, Markdown, PDF, SQL, config, semantic | `tree-sitter` |
| `ingest` | Discovery, the concurrent pipeline, reference resolution, `ReindexPrimitive` | `extractors`, `storage-sqlite` |
| `graph` | Extraction output → nodes/edges; entity resolution; PageRank, components | — |
| `storage-sqlite` | SQLite + FTS5 adapter, Flyway migrations, batched writes | — |
| `query` | Search, expand, path-find, evidence, blast radius, context bundles | `graph`, `storage-sqlite` |
| `report` | One Markdown health report from storage stats and graph algorithms | `graph`, `storage-sqlite` |
| `visualization` | The whole graph as one standalone interactive HTML page | `storage-sqlite` |
| `mcp-server` | MCP stdio server: `ExploreEngine`, 11 tools, 6 resources, 4 prompts | `extractors`, `graph`, `ingest`, `query`, `report`, `storage-sqlite`, `visualization` |
| `cli` | Clikt entry point, 15 subcommands | `mcp-server` + its deps; **not** `tree-sitter` |
| `eval` | Grades `explore` answers against a curated question set | `ingest`, `mcp-server`, `storage-sqlite` |
| `benchmark` | Corpus prep, LLM judge, retrieval/ingest-cost comparison vs CodeGraph, `grep`, ripgrep | `cli` (the only module that does), `extractors`, `graph`, `ingest`, `mcp-server`, `query`, `storage-sqlite` |

## Repository layout

Beyond `modules/`, `docs/`, `gradle/` and `.github/`: `action/` a composite GitHub
Action installing the CLI · `packaging/` the Homebrew formula template ·
`scripts/` `ci-reindex.sh` plus measurement scripts · `test-fixtures/`
source-only fixture projects · `.contextgraph/` the committed graph baseline.

## Project Configuration

Each indexed project needs `.contextgraph/config.json` (auto-created by `init`):

| Key | Default | Purpose |
|-----|---------|---------|
| `litellm.enabled` | `false` | Enable LLM-powered semantic extraction |
| `litellm.baseUrl` | `http://localhost:4000` | LiteLLM proxy endpoint |
| `litellm.model` | `gpt-4o` | Model for semantic extraction |
| `litellm.rateLimitPerMinute` | `10` | Rate limit for LLM calls |
| `includePatterns` | `["**/*"]` | Globs to include |
| `excludePatterns` | build dirs, `.git`, etc. | Globs to exclude |
| `maxFileSizeBytes` | `10 MB` | Larger files are skipped |
| `ignoreSecrets` | `true` | Skip files that look like secrets |
| `moduleRoots` | `[]` | Explicit module roots where layout is not inferable |
| `watcher.enabled` | `false` | Opt-in; `watch` refuses to start without it |
| `watcher.debounceMillis` | `500` | Coalesce bursts of filesystem events |
| `watcher.fallbackIntervalMillis` | `30000` | Full rescan; covers dropped registrations |

## MCP Server

11 tools over stdio. **`contextgraph.explore` is the primary one**: it answers a
natural-language question in a single call, returning matched modules, relevant
symbols with **verbatim source**, their resolved edges (with confidence and
resolution rung), and blast radius, capped at a token budget with lower-ranked
symbols marked `elided`. Agents choose badly among many thin tools — prefer it.
The other ten mostly return pointers: `index_project`, `search_nodes`, `get_node`,
`expand_node`, `find_path`, `get_evidence`, `impact_analysis`, `related_files`,
`build_context`, `generate_report`. Also 6 resources under `contextgraph://` and
4 prompts, both enumerable over the protocol.

## Testing

Kotest, on the JUnit5 runner. `test-fixtures/` holds source-only projects with no
committed graph; tests needing one build it at test time through the real pipeline
against a temp DB — see `modules/cli/src/test/kotlin/io/contextgraph/cli/FixtureGenerationTest.kt`.

### Which database you may write to

- `.contextgraph/graph.db` — the **committed baseline**, so a fresh clone is
  queryable before any local indexing. Regenerated only through `ci-reindex`
  (`scripts/ci-reindex.sh`). **Never write it.**
- `.contextgraph/graph.local.db` — the gitignored developer/watcher **overlay**,
  seeded by copying the baseline on first local write. This is the one local runs
  and the watcher write.

`GraphDb` is the seam every caller goes through: `forRead` prefers the overlay and
falls back to the baseline, `forLocalWrite` never returns the baseline.
`.gitignore`'s `**/.contextgraph/*` excludes every nested `.contextgraph/`
directory; `!.contextgraph/graph.db` is the single root-anchored exception. See
`GraphDbGitIntegrationTest.kt` (in `modules/storage-sqlite`) for the real-git proof.

**Until the overlay exists, reads write the baseline.** `forRead` falls back to
`graph.db` and SQLite opens it read-write, so on a fresh clone even `search`
leaves it dirty. Restore with `git checkout -- .contextgraph/graph.db`; once the
overlay exists, reads leave it alone.
