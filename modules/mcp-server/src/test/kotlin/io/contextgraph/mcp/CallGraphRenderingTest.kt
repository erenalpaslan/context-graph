package io.contextgraph.mcp

import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.query.CallGraph
import io.contextgraph.query.Trace
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files

/**
 * The text an agent decides from. It has to say how complete it is and give the location to
 * open, or the agent re-derives the answer with a text search -- which is what it did when the
 * tool answered "25 dependent nodes" with tests, containment and guesses mixed in.
 */
class CallGraphRenderingTest : FunSpec({

    lateinit var storage: SqliteStorageAdapter

    beforeEach { storage = SqliteStorageAdapter(Files.createTempDirectory("call-graph-rendering").resolve("graph.db")) }
    afterEach { storage.close() }

    fun method(id: String) = storage.upsertNode(GraphNode(NodeId(id), NodeType.Method, id.substringAfterLast('.').substringBefore('(')))
    fun calls(from: String, to: String, confidence: Double, line: Int) = storage.upsertEdge(
        GraphEdge(
            EdgeId("calls:$from:$to"), NodeId(from), NodeId(to), EdgeType.Calls, confidence = confidence,
            properties = mapOf("rung" to JsonPrimitive("file_imports"), "lines" to JsonArray(listOf(JsonPrimitive(line))))
        )
    )

    test("callers name each call site, say how complete they are, and count what they leave out") {
        val target = "net/Outcome.kt#Outcome.fold(Function1,Function1)"
        method(target)
        method("app/A.kt#A.run()")
        method("app/C.kt#C.sum()")
        calls("app/A.kt#A.run()", target, 0.93, 43)
        calls("app/C.kt#C.sum()", target, 0.45, 7)

        val text = CallGraphRendering(storage).callers(CallGraph(storage).callers(NodeId(target)).shouldNotBeNull(), limit = 50)

        text shouldContain "1 resolved call at confidence >= 0.8"
        text shouldContain "- A.run  app/A.kt:43  [file_imports 0.93]"
        text shouldContain "Not listed: 1 below 0.8, name-only guesses"
        text shouldNotContain "C.sum"
    }

    test("a trace names the entry points it reached") {
        method("api/Api.kt#Api.get()")
        method("ui/OrderViewModel.kt#OrderViewModel.load()")
        calls("ui/OrderViewModel.kt#OrderViewModel.load()", "api/Api.kt#Api.get()", 0.93, 12)
        val entry = Regex("ViewModel$")

        val trace = CallGraph(storage).trace(NodeId("api/Api.kt#Api.get()"), Trace.Direction.CALLERS, 5, entry).shouldNotBeNull()
        val text = CallGraphRendering(storage).trace(trace, entry)

        text shouldContain "<- OrderViewModel.load  ui/OrderViewModel.kt:12  [entry point]"
        text shouldContain "Entry points reached (1): OrderViewModel.load"
    }
})
