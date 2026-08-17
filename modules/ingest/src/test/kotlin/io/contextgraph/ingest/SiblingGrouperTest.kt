package io.contextgraph.ingest

import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files

/**
 * Unit-level tests for pass 3 in isolation: given declaration nodes already sitting in
 * storage (as pass 1 would leave them, `fqn` included in their properties), does
 * [SiblingGrouper.groupAll] produce exactly the SiblingOf edges it should? Exercises
 * SqliteStorageAdapter directly, same approach as ReferenceResolverTest -- getAllNodes and
 * deleteEdgesOfType are real SQL, and that's the seam this class's correctness depends on.
 */
class SiblingGrouperTest : FunSpec({

    lateinit var storage: SqliteStorageAdapter

    beforeEach {
        val tmpDir = Files.createTempDirectory("sibling-grouper-test")
        storage = SqliteStorageAdapter(tmpDir.resolve("graph.db"))
    }

    afterEach {
        storage.close()
    }

    fun declNode(id: String, fqn: String, type: NodeType = NodeType.Class, label: String = id) =
        GraphNode(
            id = NodeId(id),
            type = type,
            label = label,
            properties = mapOf("fqn" to JsonPrimitive(fqn))
        )

    test("a type extended across two files: the extension links to the primary declaration") {
        storage.upsertNode(declNode("Auth/UserService.swift#UserService", "UserService"))
        storage.upsertNode(declNode("Auth/UserServiceGreetable.swift#UserService+ext@1", "UserService"))

        val stats = SiblingGrouper(storage).groupAll()

        stats.groups shouldBe 1
        stats.edges shouldBe 1
        val edges = storage.getEdgesFrom(NodeId("Auth/UserServiceGreetable.swift#UserService+ext@1"))
        edges shouldHaveSize 1
        edges.single().type shouldBe EdgeType.SiblingOf
        edges.single().target shouldBe NodeId("Auth/UserService.swift#UserService")
        edges.single().confidence shouldBe ConfidenceDefaults.SIBLING_GROUPING
        // Primary carries no outgoing SiblingOf edge of its own.
        storage.getEdgesFrom(NodeId("Auth/UserService.swift#UserService")).shouldBeEmpty()
    }

    test("a query for the type reaches every declaration site through sibling edges") {
        // Mirrors AC-9's four-file example: one primary plus three extension sites.
        val ids = listOf(
            "Auth/UserService.swift#UserService",
            "Auth/A.swift#UserService+ext@1",
            "Auth/B.swift#UserService+ext@1",
            "Auth/C.swift#UserService+ext@1"
        )
        ids.forEach { storage.upsertNode(declNode(it, "UserService")) }

        SiblingGrouper(storage).groupAll()

        // Two-hop BFS from any single member (as GraphExpander's depth=2 default does)
        // must reach all four nodes: sibling -> primary (hop 1), primary -> other
        // siblings (hop 2).
        fun neighbors(id: NodeId) = (storage.getEdgesFrom(id) + storage.getEdgesTo(id))
            .map { if (it.source == id) it.target else it.source }

        val start = NodeId("Auth/B.swift#UserService+ext@1")
        val hop1 = neighbors(start).toSet()
        val hop2 = hop1.flatMap { neighbors(it) }.toSet()
        val reached = (setOf(start) + hop1 + hop2)

        reached shouldBe ids.map { NodeId(it) }.toSet()
    }

    test("Kotlin extension functions on the same receiver, repeated across files, are grouped with each other") {
        // No class declaration for String exists in the repo (it's a stdlib type) -- only
        // the extension function declarations themselves share fqn.
        storage.upsertNode(
            declNode(
                "Ext/StringExtA.kt#String.shout()",
                "com.example.ext.String.shout",
                type = NodeType.Function
            )
        )
        storage.upsertNode(
            declNode(
                "Ext/StringExtB.kt#String.shout()",
                "com.example.ext.String.shout",
                type = NodeType.Function
            )
        )

        val stats = SiblingGrouper(storage).groupAll()

        stats.groups shouldBe 1
        stats.edges shouldBe 1
        val allEdges = storage.getAllEdges()
        allEdges shouldHaveSize 1
        allEdges.single().type shouldBe EdgeType.SiblingOf
    }

    test("where no primary declaration exists in the repo, extension sites are still grouped rather than dropped") {
        val ids = listOf(
            "Ext/A.kt#Int.identity()",
            "Ext/B.kt#Int.identity()",
            "Ext/C.kt#Int.identity()"
        )
        ids.forEach { storage.upsertNode(declNode(it, "com.example.ext.Int.identity", type = NodeType.Function)) }

        val stats = SiblingGrouper(storage).groupAll()

        stats.groups shouldBe 1
        stats.edges shouldBe 2
        // Every node in the group is reachable within one hop of the elected hub.
        val hub = ids.map { NodeId(it) }.minBy { it.value }
        val spokes = ids.map { NodeId(it) }.filter { it != hub }
        spokes.forEach { spoke ->
            storage.getEdgesFrom(spoke).single().target shouldBe hub
        }
    }

    test("distinct fqns never produce a sibling edge") {
        storage.upsertNode(declNode("A.kt#Foo", "pkg.Foo"))
        storage.upsertNode(declNode("B.kt#Bar", "pkg.Bar"))

        val stats = SiblingGrouper(storage).groupAll()

        stats.groups shouldBe 0
        stats.edges shouldBe 0
        storage.getAllEdges().shouldBeEmpty()
    }

    test("nodes without an fqn property (file/module nodes) are never grouped") {
        storage.upsertNode(GraphNode(NodeId("A.kt"), NodeType.CodeFile, "A.kt"))
        storage.upsertNode(GraphNode(NodeId("B.kt"), NodeType.CodeFile, "A.kt")) // same label, no fqn

        val stats = SiblingGrouper(storage).groupAll()

        stats.groups shouldBe 0
        storage.getAllEdges().shouldBeEmpty()
    }

    test("grouping never merges nodes -- every original node survives with its own file, line and properties") {
        val before1 = declNode("Auth/UserService.swift#UserService", "UserService", label = "UserService")
        val before2 = declNode("Auth/UserServiceGreetable.swift#UserService+ext@1", "UserService", label = "UserService")
        storage.upsertNode(before1)
        storage.upsertNode(before2)

        SiblingGrouper(storage).groupAll()

        storage.getNode(before1.id) shouldBe before1
        storage.getNode(before2.id) shouldBe before2
        storage.getAllNodes() shouldHaveSize 2
    }

    test("groupAll wipes and rebuilds the whole SiblingOf set rather than accumulating stale edges") {
        // A stale SiblingOf edge from a prior run, pointing at a node/fqn pairing that no
        // longer holds (e.g. one side was renamed away on a later reindex).
        storage.upsertEdge(
            GraphEdge(
                id = EdgeId("sibling_of:A#old:B#stale"),
                source = NodeId("A#old"),
                target = NodeId("B#stale"),
                type = EdgeType.SiblingOf
            )
        )
        storage.upsertNode(declNode("A.kt#Solo", "pkg.Solo"))
        // No second member shares "pkg.Solo" anymore.

        SiblingGrouper(storage).groupAll()

        storage.getAllEdges().shouldBeEmpty()
    }

    test("a non-SiblingOf edge is untouched by groupAll's rebuild") {
        storage.upsertNode(GraphNode(NodeId("A.kt"), NodeType.CodeFile, "A.kt"))
        storage.upsertNode(declNode("A.kt#Member", "pkg.Member"))
        storage.upsertEdge(
            GraphEdge(
                id = EdgeId("contains:A.kt:A.kt#Member"),
                source = NodeId("A.kt"),
                target = NodeId("A.kt#Member"),
                type = EdgeType.Contains
            )
        )

        SiblingGrouper(storage).groupAll()

        storage.getEdgesFrom(NodeId("A.kt")).map { it.type } shouldBe listOf(EdgeType.Contains)
    }
})
