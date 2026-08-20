package io.contextgraph.benchmark.runner

import io.contextgraph.core.GraphDb
import io.contextgraph.core.LiteLlmConfig
import io.contextgraph.ingest.describe.LiteLlmModuleEmbedder
import io.contextgraph.mcp.ExploreEngine
import io.contextgraph.mcp.ExploreResponse
import io.contextgraph.storage.SqliteStorageAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

/** The WITH_TOOLS arm's tool surface: what tools exist, and how to run one by name. */
interface McpToolBridge : AutoCloseable {
    fun tools(): List<ToolDefinition>
    fun invoke(name: String, inputJson: String): String
}

/**
 * Wires the WITH_TOOLS arm's single tool onto [ExploreEngine] against the already-indexed
 * working copy's graph -- the same engine `ContextGraphMcpServer` exposes over stdio as
 * `contextgraph.explore`, called here **in-process** instead of through a spawned CLI or MCP
 * subprocess. That distinction matters for this slice specifically: the whole point of the
 * contamination guard is to keep the *agent* from reaching the `contextgraph` binary directly,
 * so it is the harness -- not the agent, and not a process the agent could inspect or interfere
 * with -- that talks to the graph.
 *
 * Opens the read seam ([GraphDb.forRead]), not a hand-built `.contextgraph/graph.db` path:
 * [io.contextgraph.benchmark.corpus.CorpusIndexer] writes through [GraphDb.forLocalWrite], which
 * lands in the `graph.local.db` overlay, not the CI-only baseline -- a caller that hardcodes the
 * baseline name opens a file that was never written and gets an empty graph silently, since
 * [SqliteStorageAdapter] creates and schema-migrates whatever path it is handed. [GraphDb.exists]
 * is checked first for exactly the reason its own doc comment calls out: opening storage on
 * [GraphDb.forRead]'s result when neither file exists would itself materialize an empty baseline
 * at the path only CI is allowed to write. A WITH_TOOLS run against a working copy with no graph
 * at all is a broken run, not a legitimately zero-gain one, so this fails loudly rather than
 * silently measuring "ContextGraph provides nothing" against a repo it was never actually run on.
 */
class ContextGraphMcpToolBridge(indexedWorkingDir: Path) : McpToolBridge {
    private val storage = run {
        check(GraphDb.exists(indexedWorkingDir)) {
            "No ContextGraph index found at $indexedWorkingDir (neither " +
                "${GraphDb.OVERLAY_FILE_NAME} nor ${GraphDb.BASELINE_FILE_NAME} exists under " +
                ".contextgraph/) -- refusing to open storage on a nonexistent path, which would " +
                "silently create and query an empty graph instead of the WITH_TOOLS arm's real " +
                "index. This working copy was never indexed; corpus prep must run for it first."
        }
        SqliteStorageAdapter(GraphDb.forRead(indexedWorkingDir))
    }
    private val engine = ExploreEngine(storage, indexedWorkingDir, LiteLlmModuleEmbedder(), LiteLlmConfig())
    private val json = Json { ignoreUnknownKeys = true }

    override fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = TOOL_NAME,
            description = "Answer a natural-language question about this codebase using " +
                "ContextGraph's knowledge graph: matched modules, relevant symbols, verbatim " +
                "source, and their resolved edges. Prefer this over reading files directly.",
            inputSchemaJson = """{"type":"object","properties":{"question":{"type":"string","description":"Natural-language question about the codebase"}},"required":["question"]}"""
        )
    )

    override fun invoke(name: String, inputJson: String): String {
        require(name == TOOL_NAME) { "ContextGraphMcpToolBridge has no tool named '$name'" }
        val question = json.parseToJsonElement(inputJson).jsonObject["question"]
            ?.jsonPrimitive?.content
            ?: return """{"error":"missing 'question' argument"}"""
        val response = runBlocking { engine.explore(question) }
        return json.encodeToString(ExploreResponse.serializer(), response)
    }

    override fun close() {
        storage.close()
    }

    companion object {
        const val TOOL_NAME = "contextgraph_explore"
    }
}
