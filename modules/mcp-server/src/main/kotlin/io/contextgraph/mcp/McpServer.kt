package io.contextgraph.mcp

import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.EdgeType
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractorRegistry
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
import io.contextgraph.query.QueryEngine
import io.contextgraph.report.ReportGenerator
import io.contextgraph.storage.SqliteStorageAdapter
import io.contextgraph.visualization.GraphHtmlExporter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.Role
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

class ContextGraphMcpServer(
    private val dbPath: Path,
    private val config: ContextGraphConfig = ContextGraphConfig()
) {
    private val storage = SqliteStorageAdapter(dbPath)
    private val queryEngine = QueryEngine(storage)

    fun createServer(): Server {
        val server = Server(
            Implementation("contextgraph", "1.0.0"),
            ServerOptions(
                ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    resources = ServerCapabilities.Resources(subscribe = false, listChanged = true),
                    prompts = ServerCapabilities.Prompts(listChanged = true)
                )
            )
        )

        registerTools(server)
        registerResources(server)
        registerPrompts(server)

        return server
    }

    private fun registerTools(server: Server) {
        server.addTool(
            "contextgraph.index_project",
            "Index a project directory into the knowledge graph",
            Tool.Input(
                buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Absolute path to the project directory") }
                },
                listOf("path")
            )
        ) { request ->
            val path = request.arguments["path"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'path' argument")
            try {
                val projectPath = Path.of(path)
                val registry = ExtractorRegistry(listOf(
                    MarkdownExtractor(), CodeExtractor(), PdfExtractor(),
                    SqlExtractor(), ConfigExtractor(), SemanticExtractor()
                ))
                val pipeline = IngestPipeline(
                    discovery = FileDiscovery(config),
                    registry = registry,
                    checksumTracker = ChecksumTracker(),
                    storage = storage,
                    context = ExtractionContext(projectPath, config)
                )
                val stats = runBlocking { pipeline.index(projectPath) }
                CallToolResult(
                    content = listOf(TextContent("Indexed ${stats.artifactCount} artifacts, ${stats.nodeCount} nodes, ${stats.edgeCount} edges")),
                    isError = false
                )
            } catch (e: Exception) {
                logger.error(e) { "index_project failed" }
                errorResult(e.message ?: "Indexing failed")
            }
        }

        server.addTool(
            "contextgraph.search_nodes",
            "Search the knowledge graph for nodes matching a query",
            Tool.Input(
                buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "Search query") }
                    putJsonObject("types") { put("type", "string"); put("description", "Comma-separated node types to filter (optional)") }
                    putJsonObject("minConfidence") { put("type", "number"); put("description", "Minimum confidence 0-1 (default 0.5)") }
                    putJsonObject("limit") { put("type", "number"); put("description", "Max results (default 20)") }
                },
                listOf("query")
            )
        ) { request ->
            val query = request.arguments["query"]?.jsonPrimitive?.content ?: ""
            val typesStr = request.arguments["types"]?.jsonPrimitive?.content
            val minConf = request.arguments["minConfidence"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.5
            val limit = request.arguments["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
            val types = typesStr?.split(",")?.mapNotNull { NodeType.fromStringOrNull(it.trim()) } ?: emptyList()
            val result = queryEngine.search(query, types, minConf, limit)
            val text = result.nodes.joinToString("\n") { node ->
                "[${NodeType.stringify(node.type)}] ${node.label} (id=${node.id.value}, confidence=${node.confidence})"
            }.ifEmpty { "No nodes found" }
            CallToolResult(content = listOf(TextContent(text)), isError = false)
        }

        server.addTool(
            "contextgraph.get_node",
            "Fetch a node by ID with its properties and provenance",
            Tool.Input(
                buildJsonObject { putJsonObject("nodeId") { put("type", "string"); put("description", "Node ID") } },
                listOf("nodeId")
            )
        ) { request ->
            val nodeId = request.arguments["nodeId"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'nodeId'")
            val node = storage.getNode(NodeId(nodeId))
                ?: return@addTool errorResult("Node not found: $nodeId")
            val evidence = storage.getProvenance(nodeId)
            val text = buildString {
                appendLine("Node: ${node.label} [${NodeType.stringify(node.type)}]")
                appendLine("ID: ${node.id.value}")
                appendLine("Confidence: ${node.confidence}")
                if (node.properties.isNotEmpty()) appendLine("Properties: ${node.properties}")
                if (evidence.isNotEmpty()) {
                    appendLine("Provenance:")
                    evidence.forEach { p -> appendLine("  - ${p.path}:${p.lineStart ?: "?"} (${p.extractor})") }
                }
            }
            CallToolResult(content = listOf(TextContent(text)), isError = false)
        }

        server.addTool(
            "contextgraph.expand_node",
            "BFS neighborhood expansion from a node",
            Tool.Input(
                buildJsonObject {
                    putJsonObject("nodeId") { put("type", "string"); put("description", "Node ID to expand") }
                    putJsonObject("depth") { put("type", "number"); put("description", "BFS depth (default 2)") }
                },
                listOf("nodeId")
            )
        ) { request ->
            val nodeId = request.arguments["nodeId"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'nodeId'")
            val depth = request.arguments["depth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2
            val bundle = queryEngine.expandNode(nodeId, depth)
            val text = "Found ${bundle.nodes.size} nodes, ${bundle.edges.size} edges\n" +
                bundle.nodes.take(30).joinToString("\n") { "[${NodeType.stringify(it.type)}] ${it.label}" }
            CallToolResult(content = listOf(TextContent(text)), isError = false)
        }

        server.addTool(
            "contextgraph.find_path",
            "Find shortest explanation path between two nodes",
            Tool.Input(
                buildJsonObject {
                    putJsonObject("fromId") { put("type", "string"); put("description", "Source node ID") }
                    putJsonObject("toId") { put("type", "string"); put("description", "Target node ID") }
                },
                listOf("fromId", "toId")
            )
        ) { request ->
            val fromId = request.arguments["fromId"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'fromId'")
            val toId = request.arguments["toId"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'toId'")
            val path = queryEngine.findPath(fromId, toId)
            val text = if (path.isEmpty()) "No path found"
            else path.joinToString(" → ") { "${it.label} [${NodeType.stringify(it.type)}]" }
            CallToolResult(content = listOf(TextContent(text)), isError = false)
        }

        server.addTool(
            "contextgraph.get_evidence",
            "Get full provenance chain for a node",
            Tool.Input(
                buildJsonObject { putJsonObject("nodeId") { put("type", "string"); put("description", "Node ID") } },
                listOf("nodeId")
            )
        ) { request ->
            val nodeId = request.arguments["nodeId"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'nodeId'")
            val evidence = queryEngine.getEvidence(nodeId)
            val text = if (evidence.isEmpty()) "No evidence found"
            else evidence.joinToString("\n") { p ->
                "- ${p.path}${p.lineStart?.let { ":$it" } ?: ""}${p.lineEnd?.let { "-$it" } ?: ""} [${p.extractor}]"
            }
            CallToolResult(content = listOf(TextContent(text)), isError = false)
        }

        server.addTool(
            "contextgraph.impact_analysis",
            "What depends on this node (reverse dependency analysis)",
            Tool.Input(
                buildJsonObject { putJsonObject("nodeId") { put("type", "string"); put("description", "Node ID") } },
                listOf("nodeId")
            )
        ) { request ->
            val nodeId = request.arguments["nodeId"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'nodeId'")
            val bundle = queryEngine.impactAnalysis(nodeId)
            val text = "Impact: ${bundle.nodes.size} dependent nodes\n" +
                bundle.nodes.take(20).joinToString("\n") { "[${NodeType.stringify(it.type)}] ${it.label}" }
            CallToolResult(content = listOf(TextContent(text)), isError = false)
        }

        server.addTool(
            "contextgraph.related_files",
            "Get source files associated with a node",
            Tool.Input(
                buildJsonObject { putJsonObject("nodeId") { put("type", "string"); put("description", "Node ID") } },
                listOf("nodeId")
            )
        ) { request ->
            val nodeId = request.arguments["nodeId"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'nodeId'")
            val files = queryEngine.relatedFiles(nodeId)
            val text = if (files.isEmpty()) "No files found" else files.joinToString("\n")
            CallToolResult(content = listOf(TextContent(text)), isError = false)
        }

        server.addTool(
            "contextgraph.build_context",
            "Build a ranked context bundle for a task description",
            Tool.Input(
                buildJsonObject {
                    putJsonObject("task") { put("type", "string"); put("description", "Task description or question") }
                    putJsonObject("depth") { put("type", "number"); put("description", "Graph traversal depth (default 2)") }
                },
                listOf("task")
            )
        ) { request ->
            val task = request.arguments["task"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'task'")
            val depth = request.arguments["depth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2
            val bundle = queryEngine.buildContext(task, depth)
            val text = buildString {
                appendLine("Context for: $task")
                appendLine("Nodes (${bundle.nodes.size}):")
                bundle.nodes.take(20).forEach { appendLine("  [${NodeType.stringify(it.type)}] ${it.label}") }
                if (bundle.edges.isNotEmpty()) appendLine("Edges: ${bundle.edges.size}")
            }
            CallToolResult(content = listOf(TextContent(text)), isError = false)
        }

        server.addTool(
            "contextgraph.generate_report",
            "Generate GRAPH_REPORT.md and graph.html",
            Tool.Input(
                buildJsonObject {
                    putJsonObject("outputPath") { put("type", "string"); put("description", "Output directory (optional)") }
                },
                emptyList()
            )
        ) { request ->
            try {
                val outputDir = request.arguments["outputPath"]?.jsonPrimitive?.content
                    ?.let { Path.of(it) } ?: Path.of(".")
                val generator = ReportGenerator(storage, GraphAlgorithms())
                val report = generator.generate()
                val reportFile = outputDir.resolve("GRAPH_REPORT.md").toFile()
                reportFile.writeText(report)

                val exporter = GraphHtmlExporter(storage)
                val htmlFile = outputDir.resolve("graph.html").toFile()
                htmlFile.writeText(exporter.export())

                CallToolResult(
                    content = listOf(TextContent("Report: ${reportFile.absolutePath}\nHTML: ${htmlFile.absolutePath}\n\n${report.lines().take(10).joinToString("\n")}")),
                    isError = false
                )
            } catch (e: Exception) {
                errorResult(e.message ?: "Report generation failed")
            }
        }
    }

    private fun registerResources(server: Server) {
        server.addResource("contextgraph://project", "Project", "Project metadata and index statistics", "application/json") { _ ->
            val stats = storage.getStats()
            ReadResourceResult(listOf(TextResourceContents(
                """{"artifactCount":${stats.artifactCount},"nodeCount":${stats.nodeCount},"edgeCount":${stats.edgeCount}}""",
                "contextgraph://project", "application/json"
            )))
        }

        server.addResource("contextgraph://graph/nodes", "Graph Nodes", "All nodes in the knowledge graph", "application/json") { _ ->
            val nodes = storage.getAllNodes(minConfidence = 0.5)
            val json = nodes.take(500).joinToString(",", "[", "]") { n ->
                """{"id":"${n.id.value}","type":"${NodeType.stringify(n.type)}","label":"${n.label.replace("\"", "'")}","confidence":${n.confidence}}"""
            }
            ReadResourceResult(listOf(TextResourceContents(json, "contextgraph://graph/nodes", "application/json")))
        }

        server.addResource("contextgraph://graph/edges", "Graph Edges", "All edges in the knowledge graph", "application/json") { _ ->
            val edges = storage.getAllEdges(minConfidence = 0.5)
            val json = edges.take(500).joinToString(",", "[", "]") { e ->
                """{"id":"${e.id.value}","source":"${e.source.value}","target":"${e.target.value}","type":"${EdgeType.stringify(e.type)}","confidence":${e.confidence}}"""
            }
            ReadResourceResult(listOf(TextResourceContents(json, "contextgraph://graph/edges", "application/json")))
        }

        server.addResource("contextgraph://artifacts", "Artifacts", "Indexed source artifacts", "application/json") { _ ->
            val artifacts = storage.getAllArtifacts()
            val json = artifacts.joinToString(",", "[", "]") { a ->
                """{"id":"${a.id.value}","path":"${a.path.replace("\\", "/")}","type":"${NodeType.stringify(a.type)}"}"""
            }
            ReadResourceResult(listOf(TextResourceContents(json, "contextgraph://artifacts", "application/json")))
        }

        server.addResource("contextgraph://reports/summary", "Report Summary", "Latest graph summary report", "text/markdown") { _ ->
            val generator = ReportGenerator(storage, GraphAlgorithms())
            ReadResourceResult(listOf(TextResourceContents(generator.generate(), "contextgraph://reports/summary", "text/markdown")))
        }

        server.addResource("contextgraph://clusters", "Clusters", "Connected component cluster summary", "application/json") { _ ->
            val nodes = storage.getAllNodes()
            val edges = storage.getAllEdges()
            val components = GraphAlgorithms().connectedComponents(nodes, edges)
            val json = components.take(20).mapIndexed { i, comp ->
                val labels = comp.take(5).mapNotNull { storage.getNode(it)?.label }
                """{"cluster":$i,"size":${comp.size},"sample":${labels.joinToString(",", "[", "]") { "\"$it\"" }}}"""
            }.joinToString(",", "[", "]")
            ReadResourceResult(listOf(TextResourceContents(json, "contextgraph://clusters", "application/json")))
        }
    }

    private fun registerPrompts(server: Server) {
        server.addPrompt("explain_codebase", "Explain this codebase", emptyList()) { _ ->
            GetPromptResult(
                description = "Explain this codebase",
                messages = listOf(PromptMessage(Role.user, TextContent(
                    "Use contextgraph.build_context with task='explain the overall architecture and key components' to get context, then provide a comprehensive explanation."
                )))
            )
        }

        server.addPrompt("find_context_for_task", "Find relevant context for a task", listOf(
            PromptArgument("task", "The task or question", required = true)
        )) { request ->
            val task = request.arguments?.get("task") ?: "the current task"
            GetPromptResult(
                description = "Find context for: $task",
                messages = listOf(PromptMessage(Role.user, TextContent(
                    "Use contextgraph.build_context with task='$task' to retrieve relevant knowledge graph context."
                )))
            )
        }

        server.addPrompt("analyze_change_impact", "Analyze change impact", listOf(
            PromptArgument("nodeId", "Node ID of the changed entity", required = true)
        )) { request ->
            val nodeId = request.arguments?.get("nodeId") ?: ""
            GetPromptResult(
                description = "Analyze impact of changes to: $nodeId",
                messages = listOf(PromptMessage(Role.user, TextContent(
                    "Use contextgraph.impact_analysis with nodeId='$nodeId' to find dependents, then describe the change impact."
                )))
            )
        }

        server.addPrompt("summarize_research", "Summarize this research collection", emptyList()) { _ ->
            GetPromptResult(
                description = "Summarize research collection",
                messages = listOf(PromptMessage(Role.user, TextContent(
                    "Use contextgraph.search_nodes with query='' and types='Concept,Claim,Methodology' to list key research entities, then provide a structured summary."
                )))
            )
        }
    }

    private fun errorResult(message: String) = CallToolResult(
        content = listOf(TextContent("Error: $message")),
        isError = true
    )
}

fun ContextGraphMcpServer.startStdio() {
    val server = createServer()
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered()
    )
    logger.info { "ContextGraph MCP Server starting (stdio)" }
    runBlocking { server.connect(transport) }
}

fun main() {
    val dbPath = Path.of(System.getProperty("contextgraph.db", ".contextgraph/graph.db"))
    ContextGraphMcpServer(dbPath).startStdio()
}
