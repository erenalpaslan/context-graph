package io.contextgraph.graph

import io.contextgraph.core.ExtractionResult

class GraphBuilder(private val resolver: EntityResolver = EntityResolver()) {
    fun build(result: ExtractionResult): ExtractionResult {
        val resolvedNodes = resolver.resolve(result.nodes)
        val resolvedEdges = resolver.resolveEdges(result.edges, resolvedNodes)
        return result.copy(nodes = resolvedNodes, edges = resolvedEdges)
    }

    fun buildAll(results: List<ExtractionResult>): ExtractionResult {
        resolver.clear()
        val allNodes = results.flatMap { it.nodes }
        val allEdges = results.flatMap { it.edges }
        val resolvedNodes = resolver.resolve(allNodes)
        val resolvedEdges = resolver.resolveEdges(allEdges, resolvedNodes)
        // Use first artifact as placeholder (multi-artifact result)
        val firstArtifact = results.firstOrNull()?.artifact
            ?: return ExtractionResult(results.first().artifact, resolvedNodes, resolvedEdges)
        return ExtractionResult(firstArtifact, resolvedNodes, resolvedEdges)
    }
}
