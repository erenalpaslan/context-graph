package io.contextgraph.query

import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.Provenance

data class ContextBundle(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val evidence: List<Provenance>,
    val rankScores: Map<String, Double>,
    /**
     * How many nodes matched before [nodes] was capped -- equal to `nodes.size` when nothing was
     * dropped.
     *
     * Without this a caller cannot distinguish "these are all of them" from "these are the first
     * 50 of 74", because both arrive as a list of 50. Rendering the second as if it were the first
     * is how a tool answers "find every X" with a fragment that reads as complete, which is worse
     * than answering nothing: the reader stops looking. Defaulted so existing construction sites
     * that genuinely cap nothing stay correct without restating their own size.
     */
    val totalNodeCount: Int = nodes.size
)
