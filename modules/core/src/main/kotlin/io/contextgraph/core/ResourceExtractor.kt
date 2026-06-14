package io.contextgraph.core

interface ResourceExtractor {
    val id: String
    val supportedTypes: Set<NodeType>
    suspend fun extract(artifact: Artifact, context: ExtractionContext): ExtractionResult
}
