package io.contextgraph.query

import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeType
import io.contextgraph.core.StorageAdapter

class NodeSearcher(private val storage: StorageAdapter) {
    fun search(
        query: String,
        types: List<NodeType> = emptyList(),
        minConfidence: Double = 0.0,
        limit: Int = 20
    ): List<GraphNode> = storage.searchNodes(query, types, minConfidence, limit)

    fun getById(id: String): GraphNode? = storage.getNode(io.contextgraph.core.NodeId(id))
}
