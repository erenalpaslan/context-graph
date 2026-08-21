package io.contextgraph.query

import io.contextgraph.core.GraphNode
import io.contextgraph.core.StorageAdapter
import io.contextgraph.graph.GraphAlgorithms

class ContextBundler(
    private val storage: StorageAdapter,
    private val algorithms: GraphAlgorithms = GraphAlgorithms()
) {
    fun bundle(nodes: List<GraphNode>, maxNodes: Int = DEFAULT_MAX_NODES): ContextBundle {
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

        // nodes.size, not rankedNodes.size: the point of the field is to record what the take()
        // above dropped, so a renderer can say "50 of 74" instead of implying 74 never existed.
        return ContextBundle(rankedNodes, edges, evidence, rankScores, totalNodeCount = nodes.size)
    }

    companion object {
        /**
         * Nodes kept per bundle before the PageRank ranking starts discarding. Named rather than
         * repeated as a literal because two layers now have to agree on it: the query layer caps
         * here, and the MCP layer defaults its own `limit` to the same number so an agent that
         * passes nothing gets a cap it can see reported rather than one applied twice.
         */
        const val DEFAULT_MAX_NODES: Int = 50
    }
}
