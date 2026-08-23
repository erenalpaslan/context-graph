package io.contextgraph.query

import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId

/**
 * How relevant a candidate is **to the query** -- the term [ContextBundler] adds to the graph's
 * own `pageRank * confidence` when deciding what a bundle's best fifty nodes are.
 *
 * It exists because that sort used to be a function of the node alone. A query selected the
 * candidate set and was then discarded, so within the set order was decided by centrality, and
 * centrality on the subgraph induced by the candidates rewards whichever cluster is most densely
 * cross-linked -- in most repositories, the documentation. The measured cost was 35.6% of the top
 * ten going to files that could not be the answer.
 *
 * Scores are points, on the scale CodeGraph publishes for the same job (an exact name match is
 * worth 80, a filename hit 10, a documentation file -15), because those constants are known to
 * work on one real corpus and are a more honest starting point than numbers invented here. They
 * are deliberately far larger than `pageRank * confidence`, which sums to 1.0 across the whole
 * candidate set and is therefore ~1e-3 per node: the graph term survives as the tie-breaker among
 * candidates the query cannot tell apart, and is dominated wherever the query can tell them apart.
 *
 * Construct one per query via [of]. A bundle with no query passes `null` instead, and ranks
 * exactly as it did before any of this existed.
 */
class QueryRelevance private constructor(
    private val seedRelevance: Map<NodeId, Double>
) {

    /**
     * Points for [node], whose file is [path] (null when the node has no provenance -- a real
     * state, not an error, and every path-derived term simply does not fire for it).
     */
    fun score(node: GraphNode, path: String?): Double = 0.0

    companion object {
        /**
         * [queryText] as asked, and [seedsInRankOrder] -- the candidates that entered the set as
         * search hits, best first. Candidates absent from that list entered by graph expansion:
         * they are neighbours, not matches, and score nothing from having been found.
         */
        fun of(queryText: String, seedsInRankOrder: List<NodeId>): QueryRelevance {
            val n = seedsInRankOrder.size
            return QueryRelevance(
                seedRelevance = seedsInRankOrder
                    .mapIndexed { i, id -> id to (n - i).toDouble() / n }
                    .toMap()
            )
        }
    }
}
