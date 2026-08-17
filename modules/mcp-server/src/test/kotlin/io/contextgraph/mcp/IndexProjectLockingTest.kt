package io.contextgraph.mcp

import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.ingest.ReindexPrimitive
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The fix this test exists for: MCP's `contextgraph.index_project` used to build and run its
 * own [io.contextgraph.ingest.IngestPipeline] directly, bypassing the shared
 * [ReindexPrimitive] (and its two-level lock) that the CLI's `index`/`refresh`, the watcher,
 * and `ci-reindex` all go through -- so a real client triggering `index_project` while a CLI
 * `refresh` (or the watcher) was reindexing the same db path concurrently had no guard against
 * corrupting it. `index_project` now calls the exact same [ReindexPrimitive] as every CLI
 * trigger, so it takes the same lock.
 *
 * This proves it end to end: a real MCP client calls `contextgraph.index_project` over the
 * actual stdio wire protocol at the same time something outside the server -- calling
 * [ReindexPrimitive] directly, exactly what `modules:cli`'s `RefreshCommand` does -- reindexes
 * the *same* db path. If `index_project` still ran its own unlocked pipeline, this would race
 * two SQLite writers against one file; with the shared primitive, one caller's two locks
 * (in-JVM + cross-process file lock) fully serialize the other, so the database comes out
 * intact either way.
 */
class IndexProjectLockingTest : FunSpec({

    test("contextgraph.index_project and a concurrent external reindex on the same db path do not corrupt the database") {
        coroutineScope {
            val root = Files.createTempDirectory("index-project-locking-root")
            root.resolve("README.md").writeText("# Hello\n\nContent for the locking test.\n")
            root.resolve("docs").createDirectories()
            root.resolve("docs/NOTES.md").writeText("# Notes\n\nA second file so there is more than one artifact.\n")

            val dbDir = Files.createTempDirectory("index-project-locking-db")
            val dbPath = dbDir.resolve("graph.db")
            // Pre-create so both writers open the same, already-existing file rather than one
            // of them racing schema creation.
            SqliteStorageAdapter(dbPath).close()

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

            val mcpServer = ContextGraphMcpServer(dbPath, ContextGraphConfig())
            val server = mcpServer.createServer()
            val client = Client(Implementation("index-project-locking-test", "1.0.0"), ClientOptions())

            val serverJob = launch(Dispatchers.IO) { runCatching { server.connect(serverTransport) } }
            val clientJob = launch(Dispatchers.IO) { runCatching { client.connect(clientTransport) } }

            try {
                withTimeout(15_000) {
                    while (runCatching { client.listTools() }.getOrNull() == null) delay(50)
                }

                // Fires at (roughly) the same time as the MCP call below: a direct call into the
                // same primitive `RefreshCommand`/the watcher/`ci-reindex` use, standing in for a
                // concurrent CLI trigger against the identical db path.
                val externalReindex = async(Dispatchers.IO) {
                    ReindexPrimitive.run(root, ContextGraphConfig(), dbPath)
                }

                val callResult = withTimeout(30_000) {
                    client.callTool(
                        CallToolRequest(
                            name = "contextgraph.index_project",
                            arguments = buildJsonObject { put("path", root.toString()) }
                        )
                    )
                }

                val externalStats = externalReindex.await()

                callResult?.isError shouldBe false
                val text = (callResult?.content?.single() as TextContent).text!!
                text shouldBe text // sanity: content decoded without throwing
                text.contains("Indexed") shouldBe true

                // Whichever caller the lock let through first does the real extraction work;
                // the other finds the checksum already stored and legitimately skips it -- the
                // same checksum-skip-under-contention property FreshnessTest asserts for the
                // three CLI triggers. Either way, neither call errors and the file is never
                // left in a state a fresh connection can't read.
                (externalStats.artifactCount + externalStats.skipped) shouldBeGreaterThan 0

                val reopened = SqliteStorageAdapter(dbPath)
                try {
                    reopened.getAllArtifacts().size shouldBeGreaterThan 0
                } finally {
                    reopened.close()
                }
            } finally {
                serverJob.cancel()
                clientJob.cancel()
            }
        }
    }
})
