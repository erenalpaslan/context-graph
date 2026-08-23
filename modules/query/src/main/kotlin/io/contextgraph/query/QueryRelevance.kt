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
    fun score(node: GraphNode, path: String?): Double = searchHitPoints(node)

    /**
     * What the search layer already knew and the sort used to throw away: this candidate's place
     * in the relevance ordering the query itself produced.
     *
     * Zero for a candidate that entered by graph expansion. It is a neighbour of a match, not a
     * match, and its claim on a slot is the graph's -- which the `pageRank * confidence` term
     * added alongside this one still presses on its behalf.
     */
    private fun searchHitPoints(node: GraphNode): Double =
        SEARCH_HIT_POINTS * (seedRelevance[node.id] ?: 0.0)

    companion object {

        /**
         * Points for the best search hit, decaying to `SEARCH_HIT_POINTS / n` for the last of n.
         *
         * Set to half of an exact name match (80, the top tier of the name ladder) and chosen before
         * any number was measured, for one reason that is worth being able to check later: a
         * candidate whose name *is* what was asked for should be able to outrank a candidate that
         * was merely the tenth-best full-text hit, while a search hit should still comfortably
         * outrank a candidate carrying nothing but a query word buried in its path (3).
         */
        private const val SEARCH_HIT_POINTS = 40.0

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
