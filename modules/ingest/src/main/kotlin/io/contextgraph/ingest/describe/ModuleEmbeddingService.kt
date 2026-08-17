package io.contextgraph.ingest.describe

import io.contextgraph.core.GraphNode
import io.contextgraph.core.LiteLlmConfig
import io.contextgraph.core.NodeType
import io.contextgraph.core.StorageAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonPrimitive

private val logger = KotlinLogging.logger {}

/** Summary of one embedding pass, printed by the `describe-modules` CLI command. */
data class ModuleEmbeddingReport(
    val eligible: Int,
    val embedded: Int,
    val skipped: Int
)

/**
 * Computes and stores an embedding vector (AC-21) for every `CodeModule` node that has a
 * `description` property, whenever the stored vector is missing, was computed under a different
 * model or dimension than [ModuleEmbeddingModel] (AC-38), or was computed from different
 * description text (detected via a hash of the description -- the same technique
 * [ModuleDescriptionService] uses for symbol-inventory staleness).
 *
 * Runs only from the explicit `describe-modules` CLI command, immediately after descriptions are
 * authored -- never from `index`. Indexing stays deterministic and keyless; this is the same
 * separation [ModuleDescriptionService] already holds for the chat model, applied to the
 * embedding model. This is also what keeps AC-38's mismatch-triggers-re-embed behaviour a
 * one-time maintenance action: [ModuleSemanticSearch] only ever reads whatever vectors already
 * exist here, it never re-embeds the corpus itself at query time.
 *
 * A module with no `description` property is skipped before it is ever counted [eligible] --
 * "no description, no vector" is the direct mechanism behind the acceptance criterion that an
 * undescribed module is excluded from ranking without breaking it.
 */
class ModuleEmbeddingService(
    private val storage: StorageAdapter,
    private val embedder: ModuleEmbedder,
    private val litellm: LiteLlmConfig,
    private val rateLimiter: RateLimiter = RateLimiter(litellm.rateLimitPerMinute)
) {
    suspend fun run(): ModuleEmbeddingReport {
        var eligible = 0
        var embedded = 0
        var skipped = 0

        for (node in storage.getAllNodes().filter { it.type == NodeType.CodeModule }) {
            val description = (node.properties["description"] as? JsonPrimitive)?.content ?: continue
            eligible++
            if (!needsEmbedding(node, description)) continue

            if (!litellm.enabled) {
                skipped++
                continue
            }
            rateLimiter.acquire()
            val vector = try {
                embedder.embed(description, litellm)
            } catch (e: Exception) {
                logger.warn(e) { "Embedding call failed for module ${node.id.value}" }
                null
            }
            if (vector == null) {
                skipped++
                continue
            }
            persist(node, vector, description)
            embedded++
        }

        return ModuleEmbeddingReport(eligible, embedded, skipped)
    }

    private fun needsEmbedding(node: GraphNode, description: String): Boolean {
        val vector = (node.properties[ModuleEmbeddingProperties.VECTOR] as? JsonPrimitive)?.content ?: return true
        val model = (node.properties[ModuleEmbeddingProperties.MODEL] as? JsonPrimitive)?.content
        val dimension = (node.properties[ModuleEmbeddingProperties.DIMENSION] as? JsonPrimitive)?.content?.toIntOrNull()
        if (model != ModuleEmbeddingModel.NAME || dimension != ModuleEmbeddingModel.DIMENSION) return true
        val hash = (node.properties[ModuleEmbeddingProperties.SOURCE_HASH] as? JsonPrimitive)?.content
        return hash != SymbolInventory.sha256(description) || vector.isBlank()
    }

    private fun persist(node: GraphNode, vector: FloatArray, description: String) {
        storage.upsertNode(
            node.copy(
                properties = node.properties + mapOf(
                    ModuleEmbeddingProperties.VECTOR to JsonPrimitive(ModuleVectorCodec.encode(vector)),
                    ModuleEmbeddingProperties.MODEL to JsonPrimitive(ModuleEmbeddingModel.NAME),
                    ModuleEmbeddingProperties.DIMENSION to JsonPrimitive(ModuleEmbeddingModel.DIMENSION),
                    ModuleEmbeddingProperties.SOURCE_HASH to JsonPrimitive(SymbolInventory.sha256(description))
                )
            )
        )
    }
}
