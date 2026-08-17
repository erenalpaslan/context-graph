package io.contextgraph.ingest.describe

import io.contextgraph.core.GraphNode
import io.contextgraph.core.LiteLlmConfig
import io.contextgraph.core.NodeId
import io.contextgraph.core.StorageAdapter
import io.contextgraph.ingest.DetectedModule
import io.contextgraph.ingest.ModuleTree
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonPrimitive

private val logger = KotlinLogging.logger {}

/** Summary of one `describe-modules` run, printed by the CLI command. */
data class DescribeModulesReport(
    val totalModules: Int,
    val generated: Int,
    val regenerated: Int,
    val staleFlagged: Int,
    val leftUndescribed: Int
)

/**
 * Orchestrates `describe-modules`: for every module in the graph, author a description from its
 * symbol inventory (leaf modules) or its children's descriptions (parent modules), store it as
 * node properties, and detect drift on every subsequent run without ever calling the LLM to fix
 * it automatically.
 *
 * This is the *only* place in the codebase that calls an LLM to describe a module -- indexing
 * (`IngestPipeline`/`ModuleDetector`) never does, by construction: it has no [ModuleDescriber]
 * to call. Deliberately a separate, explicit command (see the CLI's `describe-modules`), not a
 * step `index` runs automatically, so indexing stays deterministic and keyless.
 *
 * Staleness is a pure hash comparison, never a reason to call the LLM on its own: a changed
 * inventory only ever sets `descriptionStale = true` here. The stored `description` is touched
 * again only when [run]'s `regenerateStale` parameter is explicitly `true` -- the CLI's
 * `--regenerate-stale` flag is what makes that explicit.
 */
class ModuleDescriptionService(
    private val storage: StorageAdapter,
    private val describer: ModuleDescriber,
    private val litellm: LiteLlmConfig,
    private val rateLimiter: RateLimiter = RateLimiter(litellm.rateLimitPerMinute)
) {
    suspend fun run(regenerateStale: Boolean): DescribeModulesReport {
        val tree = ModuleTreeReader.read(storage)
        val symbolsByModule = SymbolInventory.collectByModule(storage, tree)
        val childDescriptions = mutableMapOf<NodeId, String>()

        var generated = 0
        var regenerated = 0
        var staleFlagged = 0
        var leftUndescribed = 0

        for (module in topologicalOrder(tree)) {
            val node = storage.getNode(module.id) ?: continue
            val ownText = SymbolInventory.canonicalText(symbolsByModule[module.id].orEmpty())
            val childText = tree.children(module)
                .sortedBy { it.path }
                .mapNotNull { child -> childDescriptions[child.id]?.let { "${child.name} (${child.path}): $it" } }
                .joinToString("\n")
            val hasContent = ownText.isNotBlank() || childText.isNotBlank()
            val currentHash = SymbolInventory.sha256("$ownText\n---\n$childText")

            val existingDescription = (node.properties["description"] as? JsonPrimitive)?.content
            val existingHash = (node.properties["descriptionInventoryHash"] as? JsonPrimitive)?.content

            when {
                existingDescription == null -> {
                    if (!hasContent) {
                        persistUndescribed(node)
                        leftUndescribed++
                    } else {
                        val result = generateDescription(module, ownText, childText)
                        if (result != null) {
                            persist(node, result, currentHash, stale = false)
                            childDescriptions[module.id] = result
                            generated++
                        } else {
                            persistUndescribed(node)
                            leftUndescribed++
                        }
                    }
                }
                existingHash != currentHash -> {
                    if (regenerateStale) {
                        val result = generateDescription(module, ownText, childText)
                        if (result != null) {
                            persist(node, result, currentHash, stale = false)
                            childDescriptions[module.id] = result
                            regenerated++
                        } else {
                            persistStaleOnly(node)
                            childDescriptions[module.id] = existingDescription
                            staleFlagged++
                        }
                    } else {
                        persistStaleOnly(node)
                        childDescriptions[module.id] = existingDescription
                        staleFlagged++
                    }
                }
                else -> {
                    // Up to date: make sure the stale flag reads false (e.g. after a prior flag).
                    if ((node.properties["descriptionStale"] as? JsonPrimitive)?.content != "false") {
                        storage.upsertNode(node.copy(properties = node.properties + mapOf(
                            "descriptionStale" to JsonPrimitive(false)
                        )))
                    }
                    childDescriptions[module.id] = existingDescription
                }
            }
        }

        return DescribeModulesReport(
            totalModules = tree.modules.size,
            generated = generated,
            regenerated = regenerated,
            staleFlagged = staleFlagged,
            leftUndescribed = leftUndescribed
        )
    }

    private suspend fun generateDescription(module: DetectedModule, ownText: String, childText: String): String? {
        if (!litellm.enabled) return null
        rateLimiter.acquire()
        val prompt = buildPrompt(module, ownText, childText)
        return try {
            describer.describe(prompt, litellm)
        } catch (e: Exception) {
            logger.warn(e) { "describe-modules: description generation failed for ${module.path}" }
            null
        }
    }

    private fun persist(node: GraphNode, description: String, hash: String, stale: Boolean) {
        storage.upsertNode(
            node.copy(
                properties = node.properties + mapOf(
                    "description" to JsonPrimitive(description),
                    "descriptionInventoryHash" to JsonPrimitive(hash),
                    "descriptionStale" to JsonPrimitive(stale),
                    "undescribed" to JsonPrimitive(false)
                )
            )
        )
    }

    private fun persistStaleOnly(node: GraphNode) {
        storage.upsertNode(
            node.copy(properties = node.properties + mapOf("descriptionStale" to JsonPrimitive(true)))
        )
    }

    /**
     * Stamps `undescribed = true` explicitly, rather than leaving the absence of a `description`
     * property to imply it. An undescribed module is a normal, first-class state -- not an error,
     * and not merely "nothing written yet" -- and slice 17's `explore` surfacing needs a flag it
     * can query for, not an inference over which properties are missing.
     */
    private fun persistUndescribed(node: GraphNode) {
        storage.upsertNode(
            node.copy(properties = node.properties + mapOf("undescribed" to JsonPrimitive(true)))
        )
    }

    private fun buildPrompt(module: DetectedModule, ownText: String, childText: String): String = buildString {
        appendLine("Module: ${module.name} (path: ${module.path.ifEmpty { "<repo root>" }})")
        appendLine()
        appendLine("Files and symbols directly in this module:")
        appendLine(ownText.ifBlank { "(none)" })
        appendLine()
        appendLine("Child module summaries:")
        appendLine(childText.ifBlank { "(none)" })
    }

    /** Children before parents, so a parent's prompt can always use an already-generated child description. */
    private fun topologicalOrder(tree: ModuleTree): List<DetectedModule> {
        val processed = mutableSetOf<NodeId>()
        val remaining = tree.modules.toMutableList()
        val result = mutableListOf<DetectedModule>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { m -> tree.children(m).all { it.id in processed } }
            if (ready.isEmpty()) {
                // Cycle guard: should be unreachable given ModuleTree's own construction, but
                // never hang describe-modules on a graph shape this code didn't anticipate.
                result.addAll(remaining.sortedBy { it.path })
                break
            }
            val batch = ready.sortedBy { it.path }
            result.addAll(batch)
            batch.forEach { processed.add(it.id); remaining.remove(it) }
        }
        return result
    }
}
