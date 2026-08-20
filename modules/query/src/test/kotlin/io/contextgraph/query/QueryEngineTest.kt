package io.contextgraph.query

import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.GraphNode
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import java.nio.file.Files

/**
 * Exercises `QueryEngine.buildContext(task)` -- the exact entry point the MCP `build_context`
 * tool calls, and the one that used to hand a raw natural-language task string straight to
 * SQLite FTS5's MATCH, whose default semantics are implicit AND across every word. A real
 * question sentence almost never has every one of its words on the same indexed row, so this
 * path returned an empty bundle for essentially any natural-language input.
 *
 * Fixtures here are invented for this test file -- not derived from, or calibrated against,
 * the benchmark module's question fixtures -- so this proves the fix's own logic rather than
 * its fit to a particular gold set's wording.
 */
class QueryEngineTest : FunSpec({

    lateinit var storage: SqliteStorageAdapter
    lateinit var engine: QueryEngine

    beforeEach {
        val tmpDir = Files.createTempDirectory("query-engine-test")
        storage = SqliteStorageAdapter(tmpDir.resolve("graph.db"))
        engine = QueryEngine(storage)
    }

    afterEach {
        storage.close()
    }

    fun node(id: String, label: String, type: NodeType = NodeType.Class) =
        GraphNode(NodeId(id), type, label, confidence = 0.9)

    test("buildContext finds relevant nodes from a full natural-language task sentence") {
        storage.upsertNode(node("A", "ShoppingCartService"))
        storage.upsertNode(node("B", "UnrelatedWidget"))

        // No exact phrase in this sentence appears verbatim anywhere in the index; the old
        // implicit-AND MATCH behavior returned zero nodes here regardless of relevance.
        val bundle = engine.buildContext("How does the shopping cart service handle checkout failures and refunds?")

        bundle.nodes.map { it.id }.shouldNotBeEmpty()
        bundle.nodes.map { it.id } shouldContain NodeId("A")
    }

    test("buildContext still finds a node from a short single-term task description") {
        storage.upsertNode(node("A", "LoginHandler"))
        storage.upsertNode(node("B", "UnrelatedWidget"))

        val bundle = engine.buildContext("LoginHandler")

        bundle.nodes.map { it.id } shouldContain NodeId("A")
    }

    test("buildContext does not throw on a task sentence containing FTS5 special characters") {
        storage.upsertNode(node("A", "ErrorRetryHandler"))

        val bundle = engine.buildContext("What's the error-handling (retry) logic here?")

        bundle.nodes.map { it.id } shouldContain NodeId("A")
    }
})
