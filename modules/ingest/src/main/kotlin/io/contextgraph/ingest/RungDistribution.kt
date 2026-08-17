package io.contextgraph.ingest

import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.NodeId
import io.contextgraph.core.StorageAdapter
import kotlinx.serialization.json.JsonPrimitive

/** [RungDistribution.compute]'s language key when a `Calls` edge's source file has no
 *  recorded `language` property -- e.g. a language-less test double. */
const val UNKNOWN_LANGUAGE = "unknown"

/**
 * Reports how [ResolutionLadder] actually resolved, grouped by language -- the signal the
 * task notes call "the most useful signal anyone will have about where resolution is weak"
 * and slice 19's eval harness consumes. Deliberately not logged-only: it is a pure read
 * over what [ReferenceResolver] already persisted (the `rung` property on every `Calls`
 * edge, plus the `language` property [io.contextgraph.extractors.TreeSitterExtractor]
 * already stamps on every file node), through nothing but the existing [StorageAdapter]
 * read API -- so any consumer (a report command, the eval harness, a test) gets the same
 * queryable distribution without a new storage method or schema.
 */
object RungDistribution {

    /**
     * Language -> rung label (see [ResolutionRung.label]) -> number of `Calls` edges
     * resolved at that rung for that language.
     */
    fun compute(storage: StorageAdapter): Map<String, Map<String, Int>> =
        storage.getAllEdges()
            .filter { it.type == EdgeType.Calls }
            .groupBy { languageOf(storage, it.source) }
            .mapValues { (_, edges) ->
                edges.groupingBy { rungOf(it) }.eachCount()
            }

    private fun languageOf(storage: StorageAdapter, sourceId: NodeId): String {
        val filePath = sourceId.value.substringBefore('#')
        val fileNode = storage.getNode(NodeId(filePath)) ?: return UNKNOWN_LANGUAGE
        val language = fileNode.properties["language"] as? JsonPrimitive
        return language?.content ?: UNKNOWN_LANGUAGE
    }

    private fun rungOf(edge: GraphEdge): String {
        val rung = edge.properties["rung"] as? JsonPrimitive
        return rung?.content ?: "unrecorded"
    }
}
