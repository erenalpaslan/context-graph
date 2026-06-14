package io.contextgraph.query

import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.StorageAdapter
import io.contextgraph.graph.GraphAlgorithms

class PathFinder(private val storage: StorageAdapter) {
    private val algorithms = GraphAlgorithms()

    fun findPath(fromId: NodeId, toId: NodeId): List<GraphNode> {
        val allNodes = storage.getAllNodes()
        val allEdges = storage.getAllEdges()
        val g = algorithms.buildJGraphT(allNodes, allEdges)
        val path = algorithms.shortestPath(g, fromId, toId)
        return path.mapNotNull { storage.getNode(it) }
    }
}
