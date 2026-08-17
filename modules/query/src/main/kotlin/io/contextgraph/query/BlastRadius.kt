package io.contextgraph.query

import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.StorageAdapter

/** One node the blast radius reached: the node itself, the edge that reached it, and how many hops from the seed. */
data class BlastRadiusHit(
    val node: GraphNode,
    val viaEdge: GraphEdge,
    val hops: Int
)

/**
 * Result of [BlastRadius.compute]: everything reachable from [seedId] by walking
 * [EdgeType.Calls] and [EdgeType.Contains] edges backwards, plus the [confidenceFloor] that
 * gated which edges counted -- callers need to know which floor was applied to trust the set,
 * not just the set itself (slice 17's task notes: "say which confidence floor was used").
 */
data class BlastRadiusResult(
    val seedId: NodeId,
    val confidenceFloor: Double,
    val hits: List<BlastRadiusHit>
)

/**
 * "What breaks if this changes" for the `explore` MCP tool (slice 17): reverse traversal over
 * [EdgeType.Calls] (who calls this symbol) and [EdgeType.Contains] (what structurally contains
 * this symbol, e.g. its declaring class) edges -- exactly the two edge types the slice's task
 * notes name. Only crosses an edge whose confidence is at least [confidenceFloor]: an ambiguous,
 * low-confidence `Calls` guess (see the slice 10 resolution ladder, `ResolutionLadder`) must not
 * manufacture a false blast-radius entry. `Contains` edges are always high-confidence
 * (`ConfidenceDefaults.AST_SYMBOL`), so the floor's practical effect is almost entirely on
 * `Calls` edges.
 *
 * BFS, not DFS, so [BlastRadiusHit.hops] is always the shortest distance from the seed. Stops at
 * [maxDepth] hops or [maxNodes] hits, whichever comes first -- a hub symbol (a widely-used
 * utility method) can otherwise pull in a large fraction of the graph.
 */
class BlastRadius(private val storage: StorageAdapter) {
    private val relevantTypes = setOf(EdgeType.Calls, EdgeType.Contains)

    fun compute(
        seedId: NodeId,
        confidenceFloor: Double,
        maxDepth: Int = 3,
        maxNodes: Int = 50
    ): BlastRadiusResult {
        val visited = mutableSetOf(seedId)
        val hits = mutableListOf<BlastRadiusHit>()
        var frontier = listOf(seedId)
        var hops = 0

        while (frontier.isNotEmpty() && hops < maxDepth && hits.size < maxNodes) {
            hops++
            val nextFrontier = mutableListOf<NodeId>()
            outer@ for (nodeId in frontier) {
                val incoming = storage.getEdgesTo(nodeId)
                    .filter { it.type in relevantTypes && it.confidence >= confidenceFloor }
                for (edge in incoming) {
                    if (!visited.add(edge.source)) continue
                    val node = storage.getNode(edge.source) ?: continue
                    hits.add(BlastRadiusHit(node, edge, hops))
                    nextFrontier.add(edge.source)
                    if (hits.size >= maxNodes) break@outer
                }
            }
            frontier = nextFrontier
        }

        return BlastRadiusResult(seedId, confidenceFloor, hits)
    }
}
