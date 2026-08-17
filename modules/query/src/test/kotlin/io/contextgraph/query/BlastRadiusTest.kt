package io.contextgraph.query

import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Node ids follow the real declaration-site format (`repoRelativePath#scope.chain`) so a test
 * failure reads the same way a real graph's would -- see ReferenceResolverTest for the same
 * convention on the resolution side of the graph.
 */
class BlastRadiusTest : FunSpec({

    lateinit var storage: SqliteStorageAdapter

    beforeEach {
        val tmpDir = Files.createTempDirectory("blast-radius-test")
        storage = SqliteStorageAdapter(tmpDir.resolve("graph.db"))
    }

    afterEach {
        storage.close()
    }

    fun node(id: String, label: String = id.substringAfterLast('.')) =
        GraphNode(NodeId(id), NodeType.Method, label)

    fun edge(from: String, to: String, type: EdgeType, confidence: Double) =
        GraphEdge(EdgeId("$from->$to"), NodeId(from), NodeId(to), type, confidence = confidence)

    test("finds a direct caller through a Calls edge") {
        storage.upsertNode(node("A.kt#A.callee"))
        storage.upsertNode(node("B.kt#B.caller"))
        storage.upsertEdge(edge("B.kt#B.caller", "A.kt#A.callee", EdgeType.Calls, 0.97))

        val result = BlastRadius(storage).compute(NodeId("A.kt#A.callee"), confidenceFloor = 0.8)

        result.hits shouldContainExactlyInAnyOrder listOf(
            BlastRadiusHit(node("B.kt#B.caller"), edge("B.kt#B.caller", "A.kt#A.callee", EdgeType.Calls, 0.97), 1)
        )
    }

    test("follows transitive callers up to maxDepth") {
        storage.upsertNode(node("A.kt#A.callee"))
        storage.upsertNode(node("B.kt#B.mid"))
        storage.upsertNode(node("C.kt#C.top"))
        storage.upsertEdge(edge("B.kt#B.mid", "A.kt#A.callee", EdgeType.Calls, 0.97))
        storage.upsertEdge(edge("C.kt#C.top", "B.kt#B.mid", EdgeType.Calls, 0.97))

        val result = BlastRadius(storage).compute(NodeId("A.kt#A.callee"), confidenceFloor = 0.8, maxDepth = 3)

        result.hits.map { it.node.id.value to it.hops } shouldContainExactlyInAnyOrder listOf(
            "B.kt#B.mid" to 1,
            "C.kt#C.top" to 2
        )
    }

    test("excludes a Calls edge below the confidence floor") {
        storage.upsertNode(node("A.kt#A.callee"))
        storage.upsertNode(node("B.kt#B.caller"))
        storage.upsertEdge(edge("B.kt#B.caller", "A.kt#A.callee", EdgeType.Calls, 0.45))

        val result = BlastRadius(storage).compute(NodeId("A.kt#A.callee"), confidenceFloor = 0.8)

        result.hits.shouldBeEmpty()
    }

    test("reaches the containing class through a reversed Contains edge") {
        storage.upsertNode(node("A.kt#A"))
        storage.upsertNode(node("A.kt#A.method"))
        storage.upsertEdge(edge("A.kt#A", "A.kt#A.method", EdgeType.Contains, 0.98))

        val result = BlastRadius(storage).compute(NodeId("A.kt#A.method"), confidenceFloor = 0.8)

        result.hits.map { it.node.id.value } shouldContainExactlyInAnyOrder listOf("A.kt#A")
    }

    test("ignores edge types outside Calls and Contains") {
        storage.upsertNode(node("A.kt#A"))
        storage.upsertNode(node("A.kt#A.sibling"))
        storage.upsertEdge(edge("A.kt#A.sibling", "A.kt#A", EdgeType.SiblingOf, 0.98))

        val result = BlastRadius(storage).compute(NodeId("A.kt#A"), confidenceFloor = 0.8)

        result.hits.shouldBeEmpty()
    }

    test("does not revisit a node already counted, even with a cycle") {
        storage.upsertNode(node("A.kt#A.a"))
        storage.upsertNode(node("B.kt#B.b"))
        storage.upsertEdge(edge("B.kt#B.b", "A.kt#A.a", EdgeType.Calls, 0.97))
        storage.upsertEdge(edge("A.kt#A.a", "B.kt#B.b", EdgeType.Calls, 0.97))

        val result = BlastRadius(storage).compute(NodeId("A.kt#A.a"), confidenceFloor = 0.8, maxDepth = 5)

        result.hits.map { it.node.id.value } shouldBe listOf("B.kt#B.b")
    }

    test("records which confidence floor was applied") {
        storage.upsertNode(node("A.kt#A.a"))

        val result = BlastRadius(storage).compute(NodeId("A.kt#A.a"), confidenceFloor = 0.8)

        result.confidenceFloor shouldBe 0.8
        result.seedId shouldBe NodeId("A.kt#A.a")
    }
})
