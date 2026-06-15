package io.contextgraph.graph

import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class GraphAlgorithmsTest : FunSpec({

    val algo = GraphAlgorithms()

    fun node(id: String) = GraphNode(NodeId(id), NodeType.Class, id, confidence = 1.0)

    fun edge(from: String, to: String, confidence: Double = 1.0) = GraphEdge(
        id = EdgeId("e:$from:$to"),
        source = NodeId(from), target = NodeId(to),
        type = EdgeType.Calls, confidence = confidence
    )

    context("buildJGraphT") {
        test("adds all nodes as vertices") {
            val nodes = listOf(node("A"), node("B"), node("C"))
            val g = algo.buildJGraphT(nodes, emptyList())
            g.vertexSet() shouldBe nodes.map { it.id }.toSet()
        }

        test("adds edges between existing vertices") {
            val nodes = listOf(node("A"), node("B"))
            val edges = listOf(edge("A", "B"))
            val g = algo.buildJGraphT(nodes, edges)
            g.edgeSet() shouldHaveSize 1
        }

        test("ignores edges referencing unknown vertices") {
            val nodes = listOf(node("A"))
            val edges = listOf(edge("A", "MISSING"))
            val g = algo.buildJGraphT(nodes, edges)
            g.edgeSet().shouldBeEmpty()
        }
    }

    context("shortestPath") {
        test("finds direct path between adjacent nodes") {
            val nodes = listOf(node("A"), node("B"))
            val g = algo.buildJGraphT(nodes, listOf(edge("A", "B")))
            val path = algo.shortestPath(g, NodeId("A"), NodeId("B"))
            path shouldBe listOf(NodeId("A"), NodeId("B"))
        }

        test("finds multi-hop path") {
            val nodes = listOf(node("A"), node("B"), node("C"))
            val edges = listOf(edge("A", "B"), edge("B", "C"))
            val g = algo.buildJGraphT(nodes, edges)
            val path = algo.shortestPath(g, NodeId("A"), NodeId("C"))
            path shouldBe listOf(NodeId("A"), NodeId("B"), NodeId("C"))
        }

        test("returns empty list when no path exists") {
            val nodes = listOf(node("A"), node("B"))
            val g = algo.buildJGraphT(nodes, emptyList())
            algo.shortestPath(g, NodeId("A"), NodeId("B")).shouldBeEmpty()
        }

        test("returns empty list when source node is missing") {
            val nodes = listOf(node("A"))
            val g = algo.buildJGraphT(nodes, emptyList())
            algo.shortestPath(g, NodeId("MISSING"), NodeId("A")).shouldBeEmpty()
        }

        test("returns single node when source equals target") {
            val nodes = listOf(node("A"))
            val g = algo.buildJGraphT(nodes, emptyList())
            val path = algo.shortestPath(g, NodeId("A"), NodeId("A"))
            path shouldBe listOf(NodeId("A"))
        }
    }

    context("connectedComponents") {
        test("single disconnected node forms its own component") {
            val nodes = listOf(node("A"), node("B"))
            val components = algo.connectedComponents(nodes, emptyList())
            components shouldHaveSize 2
        }

        test("connected nodes are in the same component") {
            val nodes = listOf(node("A"), node("B"), node("C"))
            val edges = listOf(edge("A", "B"), edge("B", "C"))
            val components = algo.connectedComponents(nodes, edges)
            components shouldHaveSize 1
            components.single() shouldContain NodeId("A")
            components.single() shouldContain NodeId("C")
        }

        test("two separate clusters form two components") {
            val nodes = listOf(node("A"), node("B"), node("C"), node("D"))
            val edges = listOf(edge("A", "B"), edge("C", "D"))
            val components = algo.connectedComponents(nodes, edges)
            components shouldHaveSize 2
        }
    }

    context("degree calculations") {
        test("inDegree counts incoming edges") {
            val nodes = listOf(node("A"), node("B"), node("C"))
            val edges = listOf(edge("A", "C"), edge("B", "C"))
            val g = algo.buildJGraphT(nodes, edges)
            algo.inDegree(g, NodeId("C")) shouldBe 2
        }

        test("outDegree counts outgoing edges") {
            val nodes = listOf(node("A"), node("B"), node("C"))
            val edges = listOf(edge("A", "B"), edge("A", "C"))
            val g = algo.buildJGraphT(nodes, edges)
            algo.outDegree(g, NodeId("A")) shouldBe 2
        }

        test("inDegree returns 0 for missing node") {
            val g = algo.buildJGraphT(emptyList(), emptyList())
            algo.inDegree(g, NodeId("MISSING")) shouldBe 0
        }
    }

    context("pageRank") {
        test("returns a score for every node") {
            val nodes = listOf(node("A"), node("B"), node("C"))
            val edges = listOf(edge("A", "B"), edge("B", "C"), edge("C", "A"))
            val g = algo.buildJGraphT(nodes, edges)
            val scores = algo.pageRank(g)
            scores.keys shouldBe nodes.map { it.id }.toSet()
        }

        test("well-connected node scores higher than isolated node") {
            val nodes = listOf(node("Hub"), node("X"), node("Y"), node("Z"), node("Leaf"))
            val edges = listOf(edge("X", "Hub"), edge("Y", "Hub"), edge("Z", "Hub"))
            val g = algo.buildJGraphT(nodes, edges)
            val scores = algo.pageRank(g)
            scores[NodeId("Hub")]!! shouldBeGreaterThan scores[NodeId("Leaf")]!!
        }
    }
})
