package io.contextgraph.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.GraphDb
import io.contextgraph.core.GraphSnapshot
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.graph.GraphAlgorithms
import io.contextgraph.ingest.ReindexPrimitive
import io.contextgraph.ingest.describe.LiteLlmModuleDescriber
import io.contextgraph.ingest.describe.LiteLlmModuleEmbedder
import io.contextgraph.ingest.describe.ModuleDescriptionService
import io.contextgraph.ingest.describe.ModuleEmbeddingService
import io.contextgraph.ingest.describe.ModuleSemanticSearch
import io.contextgraph.mcp.ContextGraphMcpServer
import io.contextgraph.mcp.startStdio
import io.contextgraph.query.QueryEngine
import io.contextgraph.report.ReportGenerator
import io.contextgraph.storage.SqliteStorageAdapter
import io.contextgraph.visualization.GraphHtmlExporter
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

internal val json = Json { prettyPrint = true; encodeDefaults = true }

/**
 * The `.contextgraph/` home is always resolved relative to the current working
 * directory the CLI was invoked from, independent of the directory being indexed.
 */
internal fun projectRoot(): Path = Path.of(".")

/** Path to open for read-only commands: the local overlay when it exists, the committed baseline otherwise. */
private fun resolveReadDbPath(): Path = GraphDb.forRead(projectRoot())

/**
 * Path to open for local writes (CLI index, refresh, watcher): seeds the overlay from the
 * baseline on first use. Every non-CI writer in this module goes through this -- it never
 * returns the committed baseline (see `GraphDb.forLocalWrite`), which is what keeps the
 * baseline single-writer (CI only) enforced in code rather than by convention alone.
 */
internal fun resolveWriteDbPath(): Path = GraphDb.forLocalWrite(projectRoot())

/**
 * Opens storage for a read-only command. `SqliteStorageAdapter`'s constructor
 * unconditionally creates and schema-migrates whatever path it is handed, so opening it
 * on [resolveReadDbPath]'s result without checking anything exists first would silently
 * materialize an empty `.contextgraph/graph.db` — the file only CI is allowed to write —
 * the first time anyone runs a read command against a repo with no graph at all. Degrade
 * to an actionable error instead of creating state no one asked for.
 */
private fun CliktCommand.openReadStorage(): SqliteStorageAdapter {
    val root = projectRoot()
    if (!GraphDb.exists(root)) {
        echo(
            "No graph found in ${root.toAbsolutePath().normalize()}. Run 'index' first.",
            err = true
        )
        throw ProgramResult(1)
    }
    return SqliteStorageAdapter(resolveReadDbPath())
}

internal fun loadConfig(): ContextGraphConfig {
    val configFile = Path.of(".contextgraph/config.json")
    return if (configFile.exists()) {
        try { json.decodeFromString(configFile.toFile().readText()) } catch (_: Exception) { ContextGraphConfig() }
    } else ContextGraphConfig()
}

class ContextGraphCli : CliktCommand("contextgraph") {
    override fun help(context: Context) = "ContextGraph — knowledge graph for code and docs"
    override fun run() = Unit
}

class InitCommand : CliktCommand("init") {
    override fun help(context: Context) = "Initialize .contextgraph/ in the current directory"
    override fun run() {
        Path.of(".contextgraph").createDirectories()
        val configFile = Path.of(".contextgraph/config.json")
        if (!configFile.exists()) {
            configFile.toFile().writeText(json.encodeToString(ContextGraphConfig.serializer(), ContextGraphConfig()))
        }
        echo("Initialized .contextgraph/ in ${Path.of(".").absolutePathString()}")
    }
}

class IndexCommand : CliktCommand("index") {
    override fun help(context: Context) = "Index a directory into the knowledge graph"
    private val path by argument().help("Directory to index").default(".")

    override fun run() {
        val projectPath = Path.of(path).toAbsolutePath().normalize()
        val config = loadConfig()
        echo("Indexing $projectPath...")
        val stats = ReindexPrimitive.run(projectPath, config, resolveWriteDbPath())
        echo("Done: ${stats.artifactCount} artifacts, ${stats.nodeCount} nodes, ${stats.edgeCount} edges")
        if (stats.skipped > 0) echo("  Skipped (unchanged): ${stats.skipped}")
        if (stats.failed > 0) echo("  Failed: ${stats.failed}")
        if (stats.parseWarnings > 0) {
            echo("  Parse warnings: ${stats.parseWarnings} file(s) had syntax errors (partial extraction; see logs)")
        }
    }
}

class SearchCommand : CliktCommand("search") {
    override fun help(context: Context) = "Search for nodes in the knowledge graph"
    private val query by argument().help("Search query")
    private val limit by option("--limit", help = "Max results").int().default(20)
    private val minConfidence by option("--min-confidence", help = "Minimum confidence 0-1").double().default(0.5)
    private val semantic by option(
        "--semantic",
        help = "Rank modules by semantic similarity to the query (embeds the query only; " +
            "falls back to keyword search with no embedding endpoint or no module vectors)"
    ).flag(default = false)

    override fun run() {
        val storage = openReadStorage()
        if (semantic) {
            runSemantic(storage)
            return
        }
        val engine = QueryEngine(storage)
        val result = engine.search(query, minConfidence = minConfidence, limit = limit)
        if (result.nodes.isEmpty()) {
            echo("No results for '$query'")
        } else {
            echo("${result.nodes.size} results for '$query':")
            result.nodes.forEach { node ->
                echo("  [${NodeType.stringify(node.type)}] ${node.label}  id=${node.id.value}  confidence=${node.confidence}")
            }
        }
    }

    private fun runSemantic(storage: SqliteStorageAdapter) {
        val config = loadConfig()
        val search = ModuleSemanticSearch(storage, LiteLlmModuleEmbedder(), config.litellm)
        val results = runBlocking { search.search(query, limit) }
        if (results.isEmpty()) {
            echo("No results for '$query'")
        } else {
            echo("${results.size} module results for '$query':")
            results.forEach { r ->
                echo("  [${r.method}] ${r.node.label}  id=${r.node.id.value}  score=${"%.3f".format(r.score)}")
            }
        }
    }
}

class NodeCommand : CliktCommand("node") {
    override fun help(context: Context) = "Show a node and its provenance"
    private val nodeId by argument().help("Node ID")

    override fun run() {
        val storage = openReadStorage()
        val node = storage.getNode(NodeId(nodeId))
        if (node == null) {
            echo("Node not found: $nodeId", err = true)
            return
        }
        echo("${node.label} [${NodeType.stringify(node.type)}]")
        echo("  ID: ${node.id.value}")
        echo("  Confidence: ${node.confidence}")
        if (node.properties.isNotEmpty()) echo("  Properties: ${node.properties}")
        val evidence = storage.getProvenance(nodeId)
        if (evidence.isNotEmpty()) {
            echo("  Provenance:")
            evidence.forEach { p -> echo("    - ${p.path}:${p.lineStart ?: "?"} (${p.extractor})") }
        }
    }
}

class ExpandCommand : CliktCommand("expand") {
    override fun help(context: Context) = "BFS neighborhood expansion from a node"
    private val nodeId by argument().help("Node ID to expand")
    private val depth by option("--depth", help = "BFS depth").int().default(2)

    override fun run() {
        val storage = openReadStorage()
        val engine = QueryEngine(storage)
        val bundle = engine.expandNode(nodeId, depth)
        echo("Expanded $nodeId to ${bundle.nodes.size} nodes, ${bundle.edges.size} edges (depth=$depth)")
        bundle.nodes.take(30).forEach { node ->
            echo("  [${NodeType.stringify(node.type)}] ${node.label}")
        }
    }
}

class PathCommand : CliktCommand("path") {
    override fun help(context: Context) = "Find shortest path between two nodes"
    private val fromId by argument().help("Source node ID")
    private val toId by argument().help("Target node ID")

    override fun run() {
        val storage = openReadStorage()
        val engine = QueryEngine(storage)
        val path = engine.findPath(fromId, toId)
        if (path.isEmpty()) {
            echo("No path found between $fromId and $toId")
        } else {
            echo("Path (${path.size} nodes):")
            echo(path.joinToString(" → ") { "${it.label} [${NodeType.stringify(it.type)}]" })
        }
    }
}

class ReportCommand : CliktCommand("report") {
    override fun help(context: Context) = "Generate GRAPH_REPORT.md and graph.html"
    private val output by option("--output", help = "Output directory").default(".")

    override fun run() {
        val storage = openReadStorage()
        val outputDir = Path.of(output)
        val generator = ReportGenerator(storage, GraphAlgorithms())
        val report = generator.generate()
        val reportFile = outputDir.resolve("GRAPH_REPORT.md").toFile()
        reportFile.writeText(report)
        echo("Report: ${reportFile.absolutePath}")
        val htmlFile = outputDir.resolve("graph.html").toFile()
        htmlFile.writeText(GraphHtmlExporter(storage).export())
        echo("HTML:   ${htmlFile.absolutePath}")
        val stats = storage.getStats()
        echo("\nGraph: ${stats.nodeCount} nodes, ${stats.edgeCount} edges across ${stats.artifactCount} artifacts")
    }
}

class DescribeModulesCommand : CliktCommand("describe-modules") {
    override fun help(context: Context) =
        "Author LLM descriptions for modules lacking one, and flag descriptions made stale by a changed symbol inventory"
    private val regenerateStale by option(
        "--regenerate-stale",
        help = "Explicitly regenerate descriptions flagged stale by a prior run, instead of only flagging them"
    ).flag(default = false)

    override fun run() {
        val root = projectRoot()
        if (!GraphDb.exists(root)) {
            echo("No graph found in ${root.toAbsolutePath().normalize()}. Run 'index' first.", err = true)
            throw ProgramResult(1)
        }
        val config = loadConfig()
        // Writes description properties onto existing CodeModule nodes, so this needs the same
        // write path (seeded local overlay) as `index` — never the CI-owned committed baseline.
        val storage = SqliteStorageAdapter(resolveWriteDbPath())
        try {
            val service = ModuleDescriptionService(storage, LiteLlmModuleDescriber(), config.litellm)
            val report = runBlocking { service.run(regenerateStale) }
            echo("Modules: ${report.totalModules}")
            echo("  Generated:    ${report.generated}")
            if (regenerateStale) echo("  Regenerated:  ${report.regenerated}")
            echo("  Stale:        ${report.staleFlagged}")
            echo("  Undescribed:  ${report.leftUndescribed}")
            if (!config.litellm.enabled) {
                echo("Note: litellm.enabled is false — no LLM calls were made; modules stay undescribed.")
            }

            // Embeds every described module's summary (AC-21), re-embedding anything whose stored
            // model/dimension no longer matches ModuleEmbeddingModel (AC-38). Same explicit,
            // deliberate step as description authoring above — never part of `index`.
            val embeddingService = ModuleEmbeddingService(storage, LiteLlmModuleEmbedder(), config.litellm)
            val embeddingReport = runBlocking { embeddingService.run() }
            echo("Embeddings: ${embeddingReport.eligible} eligible")
            echo("  Embedded:     ${embeddingReport.embedded}")
            echo("  Skipped:      ${embeddingReport.skipped}")
        } finally {
            storage.close()
        }
    }
}

class ServeMcpCommand : CliktCommand("serve-mcp") {
    override fun help(context: Context) = "Start MCP server (stdio)"
    override fun run() {
        // The MCP server exposes an index_project tool, so it needs the write path
        // (seeded overlay), not just the read path: agents that trigger a reindex
        // through it must never land on the CI-owned baseline.
        ContextGraphMcpServer(resolveWriteDbPath(), loadConfig()).startStdio()
    }
}

class ExportCommand : CliktCommand("export") {
    override fun help(context: Context) = "Export graph to JSON"
    private val output by argument().help("Output file path").default("graph.json")

    override fun run() {
        val storage = openReadStorage()
        val snapshot = GraphSnapshot(
            nodes = storage.getAllNodes(),
            edges = storage.getAllEdges(),
            artifacts = storage.getAllArtifacts(),
            generatedAt = Clock.System.now()
        )
        Path.of(output).toFile().writeText(snapshot.toJson())
        echo("Exported ${snapshot.nodes.size} nodes, ${snapshot.edges.size} edges to $output")
    }
}

class ConfigCommand : CliktCommand("config") {
    override fun help(context: Context) = "Configure ContextGraph settings"
    override fun run() = Unit
}

class ConfigSetCommand : CliktCommand("set") {
    override fun help(context: Context) = "Set a configuration value"
    private val key by argument().help("Config key (e.g. litellm.base-url)")
    private val value by argument().help("Config value")

    override fun run() {
        val configFile = Path.of(".contextgraph/config.json")
        Path.of(".contextgraph").createDirectories()
        val current = if (configFile.exists()) {
            try { json.decodeFromString<ContextGraphConfig>(configFile.toFile().readText()) } catch (_: Exception) { ContextGraphConfig() }
        } else ContextGraphConfig()

        val updated = when (key) {
            "litellm.base-url" -> current.copy(litellm = current.litellm.copy(baseUrl = value))
            "litellm.model" -> current.copy(litellm = current.litellm.copy(model = value))
            "litellm.enabled" -> current.copy(litellm = current.litellm.copy(enabled = value.toBooleanStrict()))
            "litellm.rate-limit" -> current.copy(litellm = current.litellm.copy(rateLimitPerMinute = value.toInt()))
            "max-file-size-mb" -> current.copy(maxFileSizeBytes = value.toLong() * 1024 * 1024)
            "watcher.enabled" -> current.copy(watcher = current.watcher.copy(enabled = value.toBooleanStrict()))
            "watcher.debounce-ms" -> current.copy(watcher = current.watcher.copy(debounceMillis = value.toLong()))
            "watcher.fallback-interval-ms" -> current.copy(watcher = current.watcher.copy(fallbackIntervalMillis = value.toLong()))
            else -> {
                echo(
                    "Unknown key: $key. Valid: litellm.base-url, litellm.model, litellm.enabled, litellm.rate-limit, " +
                        "max-file-size-mb, watcher.enabled, watcher.debounce-ms, watcher.fallback-interval-ms",
                    err = true
                )
                return
            }
        }
        configFile.toFile().writeText(json.encodeToString(ContextGraphConfig.serializer(), updated))
        echo("Set $key = $value")
    }
}

fun main(args: Array<String>) {
    ContextGraphCli()
        .subcommands(
            InitCommand(),
            IndexCommand(),
            RefreshCommand(),
            WatchCommand(),
            CiReindexCommand(),
            SearchCommand(),
            NodeCommand(),
            ExpandCommand(),
            PathCommand(),
            ReportCommand(),
            DescribeModulesCommand(),
            ServeMcpCommand(),
            ExportCommand(),
            ConfigCommand().subcommands(ConfigSetCommand())
        )
        .main(args)
}
