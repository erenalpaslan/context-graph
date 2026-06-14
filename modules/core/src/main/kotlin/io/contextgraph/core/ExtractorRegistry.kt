package io.contextgraph.core

class ExtractorRegistry(private val extractors: List<ResourceExtractor>) {
    fun findExtractors(type: NodeType): List<ResourceExtractor> =
        extractors.filter { type in it.supportedTypes }

    fun all(): List<ResourceExtractor> = extractors
}
