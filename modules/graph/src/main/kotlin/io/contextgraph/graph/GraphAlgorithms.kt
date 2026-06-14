package io.contextgraph.graph

import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import org.jgrapht.alg.scoring.PageRank
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.AsUndirectedGraph
import org.jgrapht.graph.DefaultEdge
import org.jgrapht.graph.DefaultWeightedEdge
import org.jgrapht.graph.DirectedWeightedPseudograph
import org.jgrapht.alg.connectivity.ConnectivityInspector

class GraphAlgorithms {
    fun buildJGraphT(nodes: List<GraphNode>, edges: List<GraphEdge>): DirectedWeightedPseudograph<NodeId, DefaultWeightedEdge> {
        val g = DirectedWeightedPseudograph<NodeId, DefaultWeightedEdge>(DefaultWeightedEdge::class.java)
        nodes.forEach { g.addVertex(it.id) }
        edges.forEach { edge ->
            if (g.containsVertex(edge.source) && g.containsVertex(edge.target)) {
                try {
                    val e = g.addEdge(edge.source, edge.target)
                    if (e != null) g.setEdgeWeight(e, edge.confidence)
                } catch (_: Exception) {}
            }
        }
        return g
    }

    fun shortestPath(
        g: DirectedWeightedPseudograph<NodeId, DefaultWeightedEdge>,
        from: NodeId,
        to: NodeId
    ): List<NodeId> {
        if (!g.containsVertex(from) || !g.containsVertex(to)) return emptyList()
        return try {
            val algo = DijkstraShortestPath(g)
            algo.getPath(from, to)?.vertexList ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun pageRank(g: DirectedWeightedPseudograph<NodeId, DefaultWeightedEdge>): Map<NodeId, Double> {
        return try {
            val pr = PageRank(g)
            g.vertexSet().associateWith { pr.getVertexScore(it) ?: 0.0 }
        } catch (_: Exception) { emptyMap() }
    }

    fun connectedComponents(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>
    ): List<Set<NodeId>> {
        val g = buildJGraphT(nodes, edges)
        val undirected = AsUndirectedGraph(g)
        return try {
            val inspector = ConnectivityInspector(undirected)
            inspector.connectedSets()
        } catch (_: Exception) { listOf(nodes.map { it.id }.toSet()) }
    }

    fun inDegree(
        g: DirectedWeightedPseudograph<NodeId, DefaultWeightedEdge>,
        nodeId: NodeId
    ): Int = if (g.containsVertex(nodeId)) g.inDegreeOf(nodeId) else 0

    fun outDegree(
        g: DirectedWeightedPseudograph<NodeId, DefaultWeightedEdge>,
        nodeId: NodeId
    ): Int = if (g.containsVertex(nodeId)) g.outDegreeOf(nodeId) else 0
}
