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

        return visitedNodes.values.toList() to visitedEdges.values.toList()
    }
}
