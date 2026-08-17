package io.contextgraph.ingest.describe

import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.GraphNode
import io.contextgraph.core.LiteLlmConfig
import io.contextgraph.core.NodeType
import io.contextgraph.core.StorageAdapter
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.sqrt

/** How a [ModuleSearchResult] was ranked. */
enum class ModuleSearchMethod { SEMANTIC, KEYWORD }

/** One ranked module result from [ModuleSemanticSearch.search]. [score] is a cosine similarity for [ModuleSearchMethod.SEMANTIC], the node's stored confidence for [ModuleSearchMethod.KEYWORD]. */
data class ModuleSearchResult(
    val node: GraphNode,
    val score: Double,
    val method: ModuleSearchMethod
)

/**
 * The natural-language way into the graph (AC-21/22/38): ranks `CodeModule` nodes against a
 * query by cosine similarity between the query's embedding and each module's stored embedding.
 * "Where do we handle refunds" can match a module whose description mentions refunds even when no
 * symbol is named `refund` anything -- the gap keyword search over symbol names cannot close.
 *
 * No vector database, no index: at the module-count this spec scopes to (a few dozen, not
 * per-symbol -- module-granularity summaries are what makes this affordable, see the spec's
 * non-goals), a linear scan over vectors already sitting in [StorageAdapter.getAllNodes] is
 * sub-millisecond. An index here would be pure overhead.
 *
 * Only the query string is ever embedded by [search] -- once per call, via [embedder]. Every
 * module's vector was computed ahead of time by [ModuleEmbeddingService] as part of
 * `describe-modules`; this class only ever reads what's already stored, filtered to vectors whose
 * recorded model and dimension match [ModuleEmbeddingModel] (AC-38 says a mismatch is re-embedded,
 * never compared as-is -- so a mismatched vector is simply not a candidate here, the same as no
 * vector at all). It never calls [embedder] on a module description, so the corpus is never
 * re-embedded per query.
 *
 * Two situations degrade to the existing FTS5 keyword search over `CodeModule` nodes rather than
 * failing: no query vector at all (LiteLLM disabled or unreachable), and a query vector with no
 * candidate clearing [ConfidenceDefaults.EMBEDDING_SIMILARITY_MIN] (including "no candidates
 * exist yet").
 */
class ModuleSemanticSearch(
    private val storage: StorageAdapter,
    private val embedder: ModuleEmbedder,
    private val litellm: LiteLlmConfig
) {
    suspend fun search(query: String, limit: Int = 10): List<ModuleSearchResult> {
        val queryVector = embedder.embed(query, litellm) ?: return keywordFallback(query, limit)

        val ranked = usableCandidates()
            .map { (node, vector) -> node to cosineSimilarity(queryVector, vector) }
            .filter { (_, score) -> score >= ConfidenceDefaults.EMBEDDING_SIMILARITY_MIN }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (node, score) -> ModuleSearchResult(node, score, ModuleSearchMethod.SEMANTIC) }

        return ranked.ifEmpty { keywordFallback(query, limit) }
    }

    private fun usableCandidates(): List<Pair<GraphNode, FloatArray>> =
        storage.getAllNodes()
            .filter { it.type == NodeType.CodeModule }
            .mapNotNull { node -> vectorOf(node)?.let { node to it } }

    private fun vectorOf(node: GraphNode): FloatArray? {
        val model = (node.properties[ModuleEmbeddingProperties.MODEL] as? JsonPrimitive)?.content
        val dimension = (node.properties[ModuleEmbeddingProperties.DIMENSION] as? JsonPrimitive)?.content?.toIntOrNull()
        if (model != ModuleEmbeddingModel.NAME || dimension != ModuleEmbeddingModel.DIMENSION) return null
        val encoded = (node.properties[ModuleEmbeddingProperties.VECTOR] as? JsonPrimitive)?.content ?: return null
        return try {
            ModuleVectorCodec.decode(encoded).takeIf { it.size == ModuleEmbeddingModel.DIMENSION }
        } catch (_: Exception) {
            null
        }
    }

    private fun keywordFallback(query: String, limit: Int): List<ModuleSearchResult> =
        storage.searchNodes(query, types = listOf(NodeType.CodeModule), limit = limit)
            .map { ModuleSearchResult(it, score = it.confidence, method = ModuleSearchMethod.KEYWORD) }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
