package io.contextgraph.graph

import io.contextgraph.core.ArtifactId
import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock

class EntityResolverTest : FunSpec({

    fun provenance(path: String) = Provenance(
        artifactId = ArtifactId(path),
        path = path,
        extractor = "test",
        extractedAt = Clock.System.now()
    )

    fun node(id: String, label: String, type: NodeType = NodeType.Class) = GraphNode(
        id = NodeId(id), type = type, label = label, confidence = 0.9,
        provenance = listOf(provenance("/src/$id.kt"))
    )

    fun edge(srcId: String, tgtId: String) = GraphEdge(
        id = EdgeId("calls:$srcId:$tgtId"),
        source = NodeId(srcId), target = NodeId(tgtId),
        type = EdgeType.Calls, confidence = 1.0
    )

    test("unique nodes pass through unchanged") {
        val resolver = EntityResolver()
        val nodes = listOf(node("A", "Alpha"), node("B", "Beta"))
        resolver.resolve(nodes) shouldHaveSize 2
    }

    test("duplicate label+type nodes are merged into one") {
        val resolver = EntityResolver()
        val n1 = node("A", "UserService")
        val n2 = node("B", "UserService") // same label, same type → same canonical key

        val resolved = resolver.resolve(listOf(n1, n2))
        resolved shouldHaveSize 1
    }

    test("merged node takes the higher confidence value") {
        val resolver = EntityResolver()
        val low  = GraphNode(NodeId("A"), NodeType.Class, "Svc", confidence = 0.5, provenance = listOf(provenance("/a")))
        val high = GraphNode(NodeId("B"), NodeType.Class, "Svc", confidence = 0.9, provenance = listOf(provenance("/b")))

        val resolved = resolver.resolve(listOf(low, high))
        resolved.single().confidence shouldBe 0.9
    }

    test("merged node accumulates provenance from both sources") {
        val resolver = EntityResolver()
        val n1 = GraphNode(NodeId("A"), NodeType.Class, "Svc", provenance = listOf(provenance("/a.kt")))
        val n2 = GraphNode(NodeId("B"), NodeType.Class, "Svc", provenance = listOf(provenance("/b.kt")))

        val resolved = resolver.resolve(listOf(n1, n2))
        resolved.single().provenance shouldHaveSize 2
    }

    test("nodes with different types are not merged") {
        val resolver = EntityResolver()
        val classNode    = GraphNode(NodeId("A"), NodeType.Class,    "Auth", provenance = listOf(provenance("/a")))
        val conceptNode  = GraphNode(NodeId("B"), NodeType.Concept,  "Auth", provenance = listOf(provenance("/b")))

        resolver.resolve(listOf(classNode, conceptNode)) shouldHaveSize 2
    }

    test("label comparison is case-insensitive") {
        val resolver = EntityResolver()
        val n1 = GraphNode(NodeId("A"), NodeType.Function, "doLogin", provenance = listOf(provenance("/a")))
        val n2 = GraphNode(NodeId("B"), NodeType.Function, "DoLogin", provenance = listOf(provenance("/b")))

        resolver.resolve(listOf(n1, n2)) shouldHaveSize 1
    }

    test("resolveEdges remaps source and target IDs after merge") {
        val resolver = EntityResolver()
        val n1 = node("A", "Svc") // will be canonical
        val n2 = node("B", "Svc") // will be remapped to A

        val resolved = resolver.resolve(listOf(n1, n2))
        val e = edge("B", "A") // edge from remapped node
        val resolvedEdges = resolver.resolveEdges(listOf(e), resolved)

        // self-loops are dropped, so this edge disappears after remap
        resolvedEdges shouldHaveSize 0
    }

    test("resolveEdges drops edges whose nodes were not resolved") {
        val resolver = EntityResolver()
        val n = node("A", "Alpha")
        resolver.resolve(listOf(n))

        val danglingEdge = edge("A", "MISSING")
        resolver.resolveEdges(listOf(danglingEdge), listOf(n)) shouldHaveSize 0
    }

    test("clear resets resolver state") {
        val resolver = EntityResolver()
        resolver.resolve(listOf(node("A", "Svc")))
        resolver.clear()
        // After clear, resolving the same nodes should produce a fresh result
        resolver.resolve(listOf(node("A", "Svc"))) shouldHaveSize 1
    }
})
