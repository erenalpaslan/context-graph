package io.contextgraph.core

data class ExtractionResult(
    val artifact: Artifact,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val diagnostics: List<ExtractionDiagnostic> = emptyList()
)
