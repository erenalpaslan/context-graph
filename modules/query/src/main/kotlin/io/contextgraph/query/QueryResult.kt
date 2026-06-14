package io.contextgraph.query

import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.Provenance

data class QueryResult(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val evidence: List<Provenance>,
    val summary: String,
    val confidence: Double
)
