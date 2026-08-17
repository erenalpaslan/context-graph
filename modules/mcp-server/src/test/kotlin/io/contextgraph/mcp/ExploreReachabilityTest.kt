package io.contextgraph.mcp

import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files

/**
 * Proves `explore` is reachable the way a real MCP client reaches it -- over the actual stdio
 * wire protocol this server ships with, not a direct call into [ExploreEngine]. Four earlier
 * slices in this run shipped green but unreachable from production; this test exists so slice
 * 17 does not join them.
 *
 * A real client and a real server are wired together over two in-process pipes (one per
 * direction), exactly mirroring what [io.contextgraph.cli.ServeMcpCommand] wires over real
 * stdin/stdout -- the only difference is the transport's byte source, not the protocol code
 * either side runs.
 */
class ExploreReachabilityTest : FunSpec({

    test("a real MCP client can list tools and call contextgraph.explore over the stdio protocol") {
        coroutineScope {
            val dbDir = Files.createTempDirectory("explore-reachability-db")
            val storage = SqliteStorageAdapter(dbDir.resolve("graph.db"))
            val symbolId = NodeId("Auth/UserService.kt#UserService.authenticate")
            storage.upsertNode(GraphNode(symbolId, NodeType.Method, "authenticate"))
            storage.upsertProvenance(
                symbolId.value, "node",
                Provenance(ArtifactId("art"), "Auth/UserService.kt", lineStart = 1, lineEnd = 1, extractor = "tree-sitter", extractedAt = Clock.System.now())
            )
            storage.close()

            val serverToClientOut = PipedOutputStream()
            val serverToClientIn = PipedInputStream(serverToClientOut)
            val clientToServerOut = PipedOutputStream()
            val clientToServerIn = PipedInputStream(clientToServerOut)

            val serverTransport = StdioServerTransport(
                clientToServerIn.asSource().buffered(),
                serverToClientOut.asSink().buffered()
            )
            val clientTransport = StdioClientTransport(
                serverToClientIn.asSource().buffered(),
                clientToServerOut.asSink().buffered()
            )

            val mcpServer = ContextGraphMcpServer(dbDir.resolve("graph.db"), ContextGraphConfig())
            val server = mcpServer.createServer()
            val client = Client(Implementation("explore-reachability-test", "1.0.0"), ClientOptions())

            val serverJob = launch(Dispatchers.IO) { runCatching { server.connect(serverTransport) } }
            val clientJob = launch(Dispatchers.IO) { runCatching { client.connect(clientTransport) } }

            try {
                // Handshake completion timing is an SDK internal; poll listTools() rather than
                // assume connect() has returned or blocked by any particular point.
                val toolNames = withTimeout(15_000) {
                    var names: List<String>? = null
                    while (names == null) {
                        names = runCatching { client.listTools()?.tools?.map { it.name } }.getOrNull()
                        if (names == null) delay(50)
                    }
                    names
                }

                // AC-25: explore is registered, and the original ten tools are all still callable.
                toolNames shouldContain "contextgraph.explore"
                toolNames shouldContain "contextgraph.index_project"
                toolNames shouldContain "contextgraph.search_nodes"
                toolNames shouldContain "contextgraph.get_node"
                toolNames shouldContain "contextgraph.expand_node"
                toolNames shouldContain "contextgraph.find_path"
                toolNames shouldContain "contextgraph.get_evidence"
                toolNames shouldContain "contextgraph.impact_analysis"
                toolNames shouldContain "contextgraph.related_files"
                toolNames shouldContain "contextgraph.build_context"
                toolNames shouldContain "contextgraph.generate_report"

                val callResult = withTimeout(15_000) {
                    client.callTool(
                        CallToolRequest(
                            name = "contextgraph.explore",
                            arguments = buildJsonObject { put("question", "authenticate") }
                        )
                    )
                }

                val text = (callResult?.content?.single() as TextContent).text!!
                text shouldContain "\"authenticate\""
                text shouldContain "Auth/UserService.kt"
                text shouldContain "\"empty\": false"
            } finally {
                serverJob.cancel()
                clientJob.cancel()
            }
        }
    }
})
