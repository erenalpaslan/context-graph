package io.contextgraph.mcp

import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.EdgeType
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.graph.GraphAlgorithms
import io.contextgraph.ingest.ReindexPrimitive
import io.contextgraph.ingest.describe.LiteLlmModuleEmbedder
import io.contextgraph.query.CallGraph
import io.contextgraph.query.ContextBundler
import io.contextgraph.query.QueryEngine
import io.contextgraph.query.RESOLVED_CALL_CONFIDENCE
import io.contextgraph.query.Trace
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * How many neighbours `expand_node` and `impact_analysis` render before saying the rest were
 * omitted.
 *
 * Set to [ContextBundler.DEFAULT_MAX_NODES] so the two caps coincide instead of compounding: the
 * query layer keeps 50 and the rendering layer used to keep 30 of those, so a 74-caller answer
 * arrived as 30 with nothing anywhere saying that 44 were gone. One number, reported.
 */
private const val DEFAULT_NEIGHBOUR_LIMIT = ContextBundler.DEFAULT_MAX_NODES

/** Hops a `trace` walks by default, and at most. Five covers screen -> ViewModel -> use case -> use case -> API use case. */
private const val DEFAULT_TRACE_DEPTH = 5
private const val MAX_TRACE_DEPTH = 8

/** Where a callers `trace` stops by default: the names UI entry points and framework-invoked types conventionally end in. */
private const val DEFAULT_ENTRY_POINTS = "(ViewModel|Presenter|Fragment|Activity|Controller|Screen)$"

class ContextGraphMcpServer(
    private val dbPath: Path,
    private val config: ContextGraphConfig = ContextGraphConfig()
) {
    private val storage = SqliteStorageAdapter(dbPath)
    private val queryEngine = QueryEngine(storage)
    private val callGraph = CallGraph(storage)
    private val rendering = CallGraphRendering(storage)

    /**
     * `dbPath` is always `<projectRoot>/.contextgraph/{graph.db,graph.local.db}` -- the one
     * topology [io.contextgraph.core.GraphDb] establishes and every caller (CLI, watcher, this
     * server) follows. [ExploreEngine] needs the project root, not the db path, to resolve a
     * symbol's `Provenance.path` (repo-relative) to an openable file for verbatim source.
     */
    private val projectRoot: Path = run {
        val absolute = dbPath.toAbsolutePath().normalize()
        val parent = absolute.parent
        if (parent != null && parent.fileName?.toString() == ".contextgraph") {
            parent.parent ?: parent
        } else {
            parent ?: Path.of(".")
        }
    }

    private val exploreEngine = ExploreEngine(storage, projectRoot, LiteLlmModuleEmbedder(), config.litellm)

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
        // Slice 17 (AC-24/25/37): the primary way to use this server. Registered first --
        // description leads with "primary tool" -- and every tool below is de-emphasised in
        // its own description now that this one exists, per the slice's ergonomics decision:
        // agents choose badly among many tools, so answer most questions with one fat call
        // instead of asking an agent to chain several thin ones together.
        server.addTool(
            "contextgraph.explore",
            "PRIMARY TOOL -- answer a natural-language question about this codebase in one call. " +
                "Returns the matched modules and the relevant symbols ranked by how well their names " +
                "match the question and how they connect to each other in the call graph. Each symbol " +
                "has its file and line range, why it ranked, its main callers and callees (with " +
                "confidence and resolution rung) and what depends on it (blast radius). By default " +
                "('detail': 'pointers', about ${DEFAULT_POINTERS_TOKEN_BUDGET} tokens) only the top " +
                "$POINTER_SOURCE_SYMBOLS symbols carry verbatim source: read the files you need from " +
                "the locations given. 'detail': 'full' carries source for every symbol that fits the " +
                "budget (default ${DEFAULT_EXPLORE_TOKEN_BUDGET} tokens) at a much larger response. " +
                "Symbols past the budget carry location only and are marked 'elided': true. A question " +
                "matching nothing returns 'empty': true rather than an error.",
            Tool.Input(
                buildJsonObject {
                    putJsonObject("question") { put("type", "string"); put("description", "Natural-language question about the codebase") }
                    putJsonObject("tokenBudget") {
                        put("type", "number")
                        put(
                            "description",
                            "Max response size in tokens, at four characters per token. Default " +
                                "$DEFAULT_POINTERS_TOKEN_BUDGET for pointers, $DEFAULT_EXPLORE_TOKEN_BUDGET for full."
                        )
                    }
                    putJsonObject("detail") {
                        put("type", "string")
                        putJsonArray("enum") { add("pointers"); add("full") }
                        put("description", "'pointers' (default): ranked locations, source for the top few. 'full': source for every symbol that fits.")
                    }
                },
                listOf("question")
            )
        ) { request ->
            val question = request.arguments["question"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'question' argument")
            val tokenBudget = request.arguments["tokenBudget"]?.jsonPrimitive?.content?.toIntOrNull()
            val detail = when (request.arguments["detail"]?.jsonPrimitive?.content?.lowercase()) {
                null, "pointers" -> ExploreDetail.POINTERS
                "full" -> ExploreDetail.FULL
                else -> return@addTool errorResult("'detail' must be 'pointers' or 'full'")
            }
            try {
                val response = runBlocking { exploreEngine.explore(question, tokenBudget, detail) }
                CallToolResult(
                    content = listOf(TextContent(encodeExploreResponse(response))),
                    isError = false
                )
            } catch (e: Exception) {
                logger.error(e) { "explore failed" }
                errorResult(e.message ?: "Explore failed")
            }
        }

        // index_project now goes through the same shared reindex primitive
        // (`io.contextgraph.ingest.ReindexPrimitive`) that the CLI's `index`/`refresh`, the
        // watcher, and `ci-reindex` all use -- previously it built and ran its own IngestPipeline
        // directly, a fourth reindex call site that took neither of the primitive's two locks
        // (in-JVM ReentrantLock + cross-process `<dbPath>.lock`), so it could corrupt the graph
        // database if run concurrently with the watcher or a CLI refresh. `ReindexPrimitive`
        // moved out of `modules:cli` into `modules:ingest` (both `cli` and `mcp-server` already
        // depend on it, and it depends back on neither) precisely so this tool could reach it
        // without a circular Gradle dependency on `modules:cli`.
        server.addTool(
            "contextgraph.index_project",
            "Index a project directory into the knowledge graph. Secondary tool -- prefer 'contextgraph.explore' to answer questions once a project is indexed.",
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
                val projectPath = Path.of(path).toAbsolutePath().normalize()
                val stats = ReindexPrimitive.run(projectPath, config, dbPath)
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
            "Search the knowledge graph for nodes matching a query. Secondary tool -- returns pointers, not source; prefer 'contextgraph.explore' for most questions.",
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
            // Search already named the id; the location is what turns a hit into somewhere to
            // look, and it costs one indexed lookup per row.
            val text = result.nodes.joinToString("\n") { node ->
                "${renderNode(node)}\n  confidence=${node.confidence}"
            }.ifEmpty { "No nodes found" }
            CallToolResult(content = listOf(TextContent(text)), isError = false)
        }

        server.addTool(
            "contextgraph.get_node",
            "Fetch a node by ID with its properties and provenance. Secondary tool -- prefer 'contextgraph.explore' for most questions.",
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
            "BFS neighborhood expansion from a node. Each result carries its node id and its " +
                "file:line location. Secondary tool -- prefer 'contextgraph.explore' for most " +
                "questions, and 'contextgraph.impact_analysis' to list callers of one symbol.",
            Tool.Input(
                buildJsonObject {
                    putJsonObject("nodeId") { put("type", "string"); put("description", "Node ID to expand") }
                    putJsonObject("depth") { put("type", "number"); put("description", "BFS depth (default 2)") }
                    putJsonObject("limit") {
                        put("type", "number")
                        put("description", "Max nodes to render (default $DEFAULT_NEIGHBOUR_LIMIT)")
                    }
                },
                listOf("nodeId")
            )
        ) { request ->
            val nodeId = request.arguments["nodeId"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'nodeId'")
            val depth = request.arguments["depth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2
            val limit = request.arguments["limit"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: DEFAULT_NEIGHBOUR_LIMIT
            val bundle = queryEngine.expandNode(nodeId, depth, maxNodes = limit)
            val text = "Found ${bundle.nodes.size} nodes, ${bundle.edges.size} edges\n" +
                renderNodes(bundle.nodes, limit)
            CallToolResult(content = listOf(TextContent(text)), isError = false)
        }

        server.addTool(
            "contextgraph.find_path",
            "Find shortest explanation path between two nodes. Secondary tool -- prefer 'contextgraph.explore' for most questions.",
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
            // A path of bare labels reads fine and cannot be followed: two steps named `getId`
            // give the reader no way to tell which type each belongs to, let alone open either.
            val text = if (path.isEmpty()) "No path found"
            else path.joinToString("\n  ↓\n") { renderNode(it) }
            CallToolResult(content = listOf(TextContent(text)), isError = false)
        }

        server.addTool(
            "contextgraph.get_evidence",
            "Get full provenance chain for a node. Secondary tool -- prefer 'contextgraph.explore' for most questions.",
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
            "Who calls this method or function -- 'what calls X' / 'what breaks if I change X'. Lists every resolved " +
                "call from production code with the call site's file:line, including calls that reach it through an " +
                "interface method it implements, and says how complete the list is. Calls are resolved by the receiver's " +
                "declared type, so same-named methods on other types are excluded, which a text search cannot do. One " +
                "hop: for chains across several hops ('which screens end up calling X') use 'contextgraph.trace'. Get " +
                "the nodeId from 'contextgraph.search_nodes' or 'contextgraph.explore'. 'direction': 'callees' lists " +
                "what it calls instead.",
            Tool.Input(
                buildJsonObject {
                    putJsonObject("nodeId") { put("type", "string"); put("description", "Node ID of a method, function or type") }
                    putJsonObject("direction") {
                        put("type", "string")
                        putJsonArray("enum") { add("callers"); add("callees") }
                        put("description", "'callers' (default): who calls it. 'callees': what it calls.")
                    }
                    putJsonObject("minConfidence") {
                        put("type", "number")
                        put("description", "Lowest call confidence listed (default $RESOLVED_CALL_CONFIDENCE). 0.4 also lists name-only guesses.")
                    }
                    putJsonObject("includeTests") {
                        put("type", "boolean")
                        put("description", "List calls from test code too (default false; they are counted either way)")
                    }
                    putJsonObject("limit") {
                        put("type", "number")
                        put("description", "Max calls to render (default $DEFAULT_NEIGHBOUR_LIMIT)")
                    }
                },
                listOf("nodeId")
            )
        ) { request ->
            val nodeId = request.arguments["nodeId"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'nodeId'")
            val limit = request.arguments["limit"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: DEFAULT_NEIGHBOUR_LIMIT
            val minConfidence = request.arguments["minConfidence"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: RESOLVED_CALL_CONFIDENCE
            val includeTests = request.arguments["includeTests"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val text = when (request.arguments["direction"]?.jsonPrimitive?.content?.lowercase()) {
                null, "callers" -> callGraph.callers(NodeId(nodeId), minConfidence, includeTests)?.let { rendering.callers(it, limit) }
                "callees" -> callGraph.callees(NodeId(nodeId), minConfidence, includeTests)?.let { rendering.callees(it, limit) }
                else -> return@addTool errorResult("'direction' must be 'callers' or 'callees'")
            } ?: return@addTool errorResult("No node with id '$nodeId' -- get one from contextgraph.search_nodes")
            CallToolResult(content = listOf(TextContent(text)), isError = false)
        }

        server.addTool(
            "contextgraph.trace",
            "Follow calls several hops in one call: 'which ViewModels/screens end up calling X' (direction 'callers', " +
                "the default) or 'which endpoints can X end up calling' (direction 'callees'). Returns the call tree " +
                "with each call site's file:line, the entry points reached, the branches that end without a caller, " +
                "and what it could not follow. Follows resolved calls, including calls through use-case holders, " +
                "invoke operators and interface methods. Callers stop at entry points matching 'entryPoints' " +
                "(default /$DEFAULT_ENTRY_POINTS/ on the method or its type). For callees, set 'entryPoints' to " +
                "what you are looking for ('Api$' for endpoints) and only the paths that reach it are shown. " +
                "Works from a type too: a ViewModel's callees are everything its methods call. Get the nodeId " +
                "from 'contextgraph.search_nodes' or 'contextgraph.explore'.",
            Tool.Input(
                buildJsonObject {
                    putJsonObject("nodeId") { put("type", "string"); put("description", "Node ID of the method or function to start from") }
                    putJsonObject("direction") {
                        put("type", "string")
                        putJsonArray("enum") { add("callers"); add("callees") }
                        put("description", "'callers' (default): walk up to whoever ends up calling it. 'callees': walk down to what it ends up calling.")
                    }
                    putJsonObject("depth") {
                        put("type", "number")
                        put("description", "Max hops (default $DEFAULT_TRACE_DEPTH, at most $MAX_TRACE_DEPTH)")
                    }
                    putJsonObject("entryPoints") {
                        put("type", "string")
                        put(
                            "description",
                            "Regex on a method or its type's name where the walk stops. Default for callers: " +
                                "$DEFAULT_ENTRY_POINTS; empty string for no stop. For callees, give the target -- 'Api$' " +
                                "for Retrofit-style API interfaces, 'Repository$' -- and only the paths that reach it are shown."
                        )
                    }
                    putJsonObject("minConfidence") {
                        put("type", "number")
                        put("description", "Lowest call confidence followed (default $RESOLVED_CALL_CONFIDENCE)")
                    }
                    putJsonObject("includeTests") {
                        put("type", "boolean")
                        put("description", "Follow calls from test code too (default false)")
                    }
                },
                listOf("nodeId")
            )
        ) { request ->
            val nodeId = request.arguments["nodeId"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'nodeId'")
            val direction = when (request.arguments["direction"]?.jsonPrimitive?.content?.lowercase()) {
                null, "callers" -> Trace.Direction.CALLERS
                "callees" -> Trace.Direction.CALLEES
                else -> return@addTool errorResult("'direction' must be 'callers' or 'callees'")
            }
            val depth = (request.arguments["depth"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_TRACE_DEPTH)
                .coerceIn(1, MAX_TRACE_DEPTH)
            val entryPattern = request.arguments["entryPoints"]?.jsonPrimitive?.content
                ?: if (direction == Trace.Direction.CALLERS) DEFAULT_ENTRY_POINTS else ""
            val entryPoints = try {
                entryPattern.takeIf { it.isNotBlank() }?.toRegex()
            } catch (e: IllegalArgumentException) {
                return@addTool errorResult("'entryPoints' is not a valid regex: ${e.message}")
            }
            val minConfidence = request.arguments["minConfidence"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: RESOLVED_CALL_CONFIDENCE
            val includeTests = request.arguments["includeTests"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val trace = callGraph.trace(NodeId(nodeId), direction, depth, entryPoints, minConfidence, includeTests)
                ?: return@addTool errorResult("No node with id '$nodeId' -- get one from contextgraph.search_nodes")
            CallToolResult(content = listOf(TextContent(rendering.trace(trace, entryPoints))), isError = false)
        }

        server.addTool(
            "contextgraph.related_files",
            "Get source files associated with a node. Secondary tool -- prefer 'contextgraph.explore' for most questions.",
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
            "Build a ranked context bundle for a task description. Secondary tool -- prefer 'contextgraph.explore' for most questions.",
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
                appendLine("Nodes (${bundle.totalNodeCount}):")
                appendLine(renderNodes(bundle.nodes, DEFAULT_NEIGHBOUR_LIMIT, bundle.totalNodeCount))
                if (bundle.edges.isNotEmpty()) appendLine("Edges: ${bundle.edges.size}")
            }
            CallToolResult(content = listOf(TextContent(text)), isError = false)
        }

        server.addTool(
            "contextgraph.generate_report",
            "Generate GRAPH_REPORT.md and graph.html. Secondary tool -- prefer 'contextgraph.explore' to answer a specific question; use this for a whole-repo report file.",
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

    /**
     * One node, rendered so the agent can act on it without guessing: the id it would pass back
     * into any other tool, and the file and line span it was extracted from.
     *
     * The label alone used to be the whole output of the neighbourhood tools, and a benchmark on
     * Keycloak showed exactly what that costs. Asked for every caller of one overloaded builder
     * method, `expand_node` replied with fifteen consecutive lines reading `[Method]
     * getConfigMetadata` -- indistinguishable from one another, attached to no file, useless as
     * an answer and useless as a next step. The graph held the path and the line span the whole
     * time; only this rendering dropped them. The agent did the sensible thing and went back to
     * grep, and the measured result was a graph that "did not help".
     *
     * Location comes from [io.contextgraph.core.Provenance], not from parsing [NodeId]. A code
     * symbol's id does begin with its path, but a `Concept`, a `Requirement` or a file node each
     * spell theirs differently, so id-parsing would work on exactly the nodes that need it least
     * and silently mislead on the rest. Provenance also carries the line span, which no id does.
     */
    private fun renderNode(node: io.contextgraph.core.GraphNode): String {
        val where = storage.getProvenance(node.id.value).firstOrNull()?.let { p ->
            val lines = listOfNotNull(p.lineStart, p.lineEnd).distinct().joinToString("-")
            if (lines.isEmpty()) p.path else "${p.path}:$lines"
        }
        return buildString {
            append("[${NodeType.stringify(node.type)}] ${node.label}")
            append("\n  id=${node.id.value}")
            if (where != null) append("\n  at $where")
        }
    }

    /**
     * Renders at most [limit] nodes and *says so* when it renders fewer than it was given.
     *
     * The tools used to `take(30)` under a header that reported the full count, so an answer cut
     * to 30 of 74 read exactly like a complete one. For a question of the form "find every X"
     * that is worse than returning nothing: the agent has no way to know it is holding a
     * fragment, so it stops looking.
     */
    private fun renderNodes(
        nodes: List<io.contextgraph.core.GraphNode>,
        limit: Int,
        /**
         * How many matched in total. Differs from `nodes.size` when the query layer already capped
         * before this saw the list -- which is the case that most needs saying out loud, since by
         * then the evidence of truncation is gone from the list itself.
         */
        totalCount: Int = nodes.size
    ): String {
        val shown = nodes.take(limit)
        val omitted = totalCount - shown.size
        return buildString {
            shown.forEach { appendLine(renderNode(it)) }
            if (omitted > 0) {
                append(
                    "... $omitted more not shown (showing ${shown.size} of $totalCount) -- " +
                        "raise 'limit' to see the rest. This list is INCOMPLETE."
                )
            }
        }.trimEnd()
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

    // Server.connect() only performs the handshake -- it returns as soon as the transport is
    // started, not when the session ends. Left alone, that makes runBlocking { connect(...) }
    // return immediately after startup and the JVM exits with stdin still open, so no client
    // ever gets an `initialize` response. The SDK's own lifecycle signal for "the session is
    // over" is the onClose callback: Protocol.connect() wires transport.onClose { doClose() },
    // and doClose() calls the (overridable) onClose() that Server uses to invoke whatever
    // callback was registered via server.onClose { ... }. So we register that callback first,
    // connect, then suspend on it -- the process now stays alive for exactly the transport's
    // lifetime (client disconnect / stdin EOF) and shuts down the moment it closes, with no
    // polling or thread-join hack.
    runBlocking {
        val closed = CompletableDeferred<Unit>()
        server.onClose { closed.complete(Unit) }
        server.connect(transport)
        closed.await()
    }
    logger.info { "ContextGraph MCP Server stopped (stdio closed)" }
}

fun main() {
    val dbPath = Path.of(System.getProperty("contextgraph.db", ".contextgraph/graph.db"))
    ContextGraphMcpServer(dbPath).startStdio()
}
