package io.contextgraph.query

import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.Provenance

data class ContextBundle(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val evidence: List<Provenance>,
    val rankScores: Map<String, Double>
)
