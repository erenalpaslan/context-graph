package io.contextgraph.query

import io.contextgraph.core.GraphNode
import io.contextgraph.core.StorageAdapter
import io.contextgraph.graph.GraphAlgorithms

class ContextBundler(
    private val storage: StorageAdapter,
    private val algorithms: GraphAlgorithms = GraphAlgorithms()
) {
    fun bundle(nodes: List<GraphNode>, maxNodes: Int = 50): ContextBundle {
        if (nodes.isEmpty()) return ContextBundle(emptyList(), emptyList(), emptyList(), emptyMap())

        val nodeIds = nodes.map { it.id }.toSet()

        // Collect all edges between the result nodes
        val edges = nodes.flatMap { node ->
            storage.getEdgesFrom(node.id).filter { it.target in nodeIds } +
            storage.getEdgesTo(node.id).filter { it.source in nodeIds }
        }.distinctBy { it.id }

        // PageRank on the subgraph to rank nodes
        val g = algorithms.buildJGraphT(nodes, edges)
        val ranks = algorithms.pageRank(g)

        val rankedNodes = nodes
            .sortedByDescending { (ranks[it.id] ?: 0.0) * it.confidence }
            .take(maxNodes)

        val evidence = rankedNodes.flatMap { storage.getProvenance(it.id.value) }
        val rankScores = rankedNodes.associate { it.id.value to (ranks[it.id] ?: 0.0) }

        return ContextBundle(rankedNodes, edges, evidence, rankScores)
    }
}
