package io.contextgraph.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractorRegistry
import io.contextgraph.core.GraphSnapshot
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.extractors.CodeExtractor
import io.contextgraph.extractors.ConfigExtractor
import io.contextgraph.extractors.MarkdownExtractor
import io.contextgraph.extractors.PdfExtractor
import io.contextgraph.extractors.SemanticExtractor
import io.contextgraph.extractors.SqlExtractor
import io.contextgraph.graph.GraphAlgorithms
import io.contextgraph.ingest.ChecksumTracker
import io.contextgraph.ingest.FileDiscovery
import io.contextgraph.ingest.IngestPipeline
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

private val json = Json { prettyPrint = true; encodeDefaults = true }

private fun resolveDbPath(): Path = Path.of(".contextgraph/graph.db")

private fun loadConfig(): ContextGraphConfig {
    val configFile = Path.of(".contextgraph/config.json")
    return if (configFile.exists()) {
        try { json.decodeFromString(configFile.toFile().readText()) } catch (_: Exception) { ContextGraphConfig() }
    } else ContextGraphConfig()
}

private fun buildPipeline(config: ContextGraphConfig, storage: SqliteStorageAdapter, projectPath: Path): IngestPipeline {
    val registry = ExtractorRegistry(listOf(
        MarkdownExtractor(), CodeExtractor(), PdfExtractor(),
        SqlExtractor(), ConfigExtractor(), SemanticExtractor()
    ))
    return IngestPipeline(
        discovery = FileDiscovery(config),
        registry = registry,
        checksumTracker = ChecksumTracker(),
        storage = storage,
        context = ExtractionContext(projectPath, config)
    )
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
        val storage = SqliteStorageAdapter(resolveDbPath())
        val pipeline = buildPipeline(config, storage, projectPath)
        echo("Indexing $projectPath...")
        val stats = runBlocking { pipeline.index(projectPath) }
        echo("Done: ${stats.artifactCount} artifacts, ${stats.nodeCount} nodes, ${stats.edgeCount} edges")
        if (stats.skipped > 0) echo("  Skipped (unchanged): ${stats.skipped}")
        if (stats.failed > 0) echo("  Failed: ${stats.failed}")
    }
}

class SearchCommand : CliktCommand("search") {
    override fun help(context: Context) = "Search for nodes in the knowledge graph"
    private val query by argument().help("Search query")
    private val limit by option("--limit", help = "Max results").int().default(20)
    private val minConfidence by option("--min-confidence", help = "Minimum confidence 0-1").double().default(0.5)

    override fun run() {
        val storage = SqliteStorageAdapter(resolveDbPath())
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
}

class NodeCommand : CliktCommand("node") {
    override fun help(context: Context) = "Show a node and its provenance"
    private val nodeId by argument().help("Node ID")

    override fun run() {
        val storage = SqliteStorageAdapter(resolveDbPath())
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
        val storage = SqliteStorageAdapter(resolveDbPath())
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
        val storage = SqliteStorageAdapter(resolveDbPath())
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
        val storage = SqliteStorageAdapter(resolveDbPath())
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

class ServeMcpCommand : CliktCommand("serve-mcp") {
    override fun help(context: Context) = "Start MCP server (stdio)"
    override fun run() {
        ContextGraphMcpServer(resolveDbPath(), loadConfig()).startStdio()
    }
}

class ExportCommand : CliktCommand("export") {
    override fun help(context: Context) = "Export graph to JSON"
    private val output by argument().help("Output file path").default("graph.json")

    override fun run() {
        val storage = SqliteStorageAdapter(resolveDbPath())
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
            else -> {
                echo("Unknown key: $key. Valid: litellm.base-url, litellm.model, litellm.enabled, litellm.rate-limit, max-file-size-mb", err = true)
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
            SearchCommand(),
            NodeCommand(),
            ExpandCommand(),
            PathCommand(),
            ReportCommand(),
            ServeMcpCommand(),
            ExportCommand(),
            ConfigCommand().subcommands(ConfigSetCommand())
        )
        .main(args)
}
