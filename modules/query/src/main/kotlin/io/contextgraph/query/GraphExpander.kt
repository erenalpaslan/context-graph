package io.contextgraph.query

import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.StorageAdapter

class GraphExpander(private val storage: StorageAdapter) {
    fun expand(
        seedIds: List<NodeId>,
        depth: Int = 2,
        edgeTypes: List<EdgeType> = emptyList()
    ): Pair<List<GraphNode>, List<GraphEdge>> {
        val visitedNodes = mutableMapOf<NodeId, GraphNode>()
        val visitedEdges = mutableMapOf<String, GraphEdge>()
        val frontier = ArrayDeque(seedIds)
        var currentDepth = 0

        while (frontier.isNotEmpty() && currentDepth < depth) {
            val nextFrontier = mutableListOf<NodeId>()
            while (frontier.isNotEmpty()) {
                val nodeId = frontier.removeFirst()
                if (nodeId in visitedNodes) continue

                val node = storage.getNode(nodeId) ?: continue
                visitedNodes[nodeId] = node

                val outEdges = storage.getEdgesFrom(nodeId)
                val inEdges = storage.getEdgesTo(nodeId)
                val allEdges = (outEdges + inEdges).filter { edge ->
                    edgeTypes.isEmpty() || edge.type in edgeTypes
                }

                allEdges.forEach { edge ->
                    visitedEdges[edge.id.value] = edge
                    val neighbor = if (edge.source == nodeId) edge.target else edge.source
                    if (neighbor !in visitedNodes) nextFrontier.add(neighbor)
                }
            }
            frontier.addAll(nextFrontier)
            currentDepth++
        }

        // Fetch remaining frontier nodes at final depth
        frontier.forEach { nodeId ->
            if (nodeId !in visitedNodes) {
                storage.getNode(nodeId)?.let { visitedNodes[nodeId] = it }
            }
        }

        // Sorted, because the traversal that built these maps is not a stable source of order.
        // `getEdgesFrom`/`getEdgesTo` issue no ORDER BY, so the frontier is walked in SQLite row
        // order, which differs between index builds because ingest extracts concurrently -- and
        // both of these lists escape into places where that matters. The node list is re-ranked
        // downstream, but the edge list is published as-is, and both are handed to
        // `GraphAlgorithms.buildJGraphT` and from there to PageRank, whose floating-point
        // summation is not associative: the same graph fed in two orders can differ in the last
        // few ulps, and a ranking that breaks exact ties would then break them differently.
        //
        // Sorted here, once per expansion, rather than by adding ORDER BY to the two edge lookups,
        // which the BFS calls once per visited node on a hot path.
        return visitedNodes.values.sortedBy { it.id.value } to visitedEdges.values.sortedBy { it.id.value }
    }
}
