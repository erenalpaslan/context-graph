package io.contextgraph.mcp

import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.LiteLlmConfig
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.core.StorageAdapter
import io.contextgraph.ingest.describe.ModuleEmbedder
import io.contextgraph.ingest.describe.ModuleSearchMethod
import io.contextgraph.ingest.describe.ModuleSearchResult
import io.contextgraph.ingest.describe.ModuleSemanticSearch
import io.contextgraph.query.BlastRadius
import io.contextgraph.query.VerbatimSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

/**
 * Default token budget for one [ExploreEngine.explore] response (slice 17, AC-37 / interview
 * Q4). ~15k tokens -- generous enough for several symbols' full source, small enough that a
 * single call never dwarfs an agent's context window on its own. Configurable per call via the
 * `explore` MCP tool's `tokenBudget` argument; this constant is only the default when the
 * caller omits it.
 */
const val DEFAULT_EXPLORE_TOKEN_BUDGET = 15_000

/**
 * Rough characters-per-token used only to decide packing order and where to draw the elision
 * line -- not a real tokenizer. Cheap and self-contained (no tokenizer dependency for an
 * estimate that only needs to be in the right ballpark); English source code averages close to
 * 4 characters per token under most BPE tokenizers, which is the same rule of thumb used
 * elsewhere for this kind of budgeting.
 */
private const val CHARS_PER_TOKEN = 4.0

/** Bounds how many candidate symbols get ranked and (for the top ones) have blast radius computed, independent of the token budget -- keeps one `explore` call bounded on a repo where a query matches hundreds of symbols. */
private const val MAX_CANDIDATE_SYMBOLS = 40

/** Bounds edges shown per symbol so one hub symbol's edge list cannot dominate the response. */
private const val MAX_EDGES_PER_SYMBOL = 20

private val STOPWORDS = setOf(
    "the", "a", "an", "is", "are", "was", "were", "do", "does", "did", "how", "what", "where",
    "when", "why", "who", "which", "to", "of", "in", "on", "for", "and", "or", "with", "this",
    "that", "it", "its", "be", "as", "at", "by", "from"
)

/** Lowercased, de-duplicated, stopword- and short-token-filtered words from [text] -- the terms [ExploreEngine] searches on and scores matches against. */
internal fun tokenize(text: String): List<String> =
    Regex("[A-Za-z0-9_]+").findAll(text)
        .map { it.value.lowercase() }
        .filter { it.length >= 3 && it !in STOPWORDS }
        .distinct()
        .toList()

@Serializable
data class ExploreModule(
    val id: String,
    val name: String,
    val path: String,
    val description: String?,
    val undescribed: Boolean,
    val descriptionStale: Boolean,
    val matchMethod: String,
    val score: Double
)

@Serializable
data class ExploreEdgeView(
    val type: String,
    val direction: String,
    val nodeId: String,
    val label: String,
    val confidence: Double,
    val rung: String? = null
)

@Serializable
data class ExploreBlastRadiusHit(
    val nodeId: String,
    val label: String,
    val nodeType: String,
    val hops: Int,
    val viaEdgeType: String,
    val confidence: Double
)

@Serializable
data class ExploreSymbol(
    val id: String,
    val label: String,
    val nodeType: String,
    val fqn: String?,
    val path: String?,
    val lineStart: Int?,
    val lineEnd: Int?,
    val confidence: Double,
    val elided: Boolean,
    val source: String?,
    val edges: List<ExploreEdgeView>,
    val blastRadius: List<ExploreBlastRadiusHit>
)

@Serializable
data class ExploreResponse(
    val question: String,
    val tokenBudget: Int,
    val estimatedTokensUsed: Int,
    val blastRadiusConfidenceFloor: Double,
    val modules: List<ExploreModule>,
    val symbols: List<ExploreSymbol>,
    val truncated: Boolean,
    val empty: Boolean
)

/**
 * The engine behind the `explore` MCP tool (slice 17): one natural-language question in,
 * matched modules + relevant symbols + **verbatim source** + resolved edges (with confidence
 * and rung) + blast radius out, packed into a token budget. See `McpServer.kt` for how this is
 * wired up as an MCP tool, and the slice task file for why this is deliberately one fat call
 * instead of several thin ones.
 *
 * Consumes almost everything earlier slices built: the resolution ladder's graded `Calls`
 * edges (10), module descriptions/embeddings (15/16), and declaration-site provenance line
 * ranges for verbatim source (02 onward). Does not modify any of that -- read-only over
 * [storage] and the source tree at [projectRoot].
 */
class ExploreEngine(
    private val storage: StorageAdapter,
    private val projectRoot: Path,
    moduleEmbedder: ModuleEmbedder,
    litellm: LiteLlmConfig,
    /**
     * The confidence floor blast radius traversal respects. Defaults to
     * [ConfidenceDefaults.CALL_RESOLUTION_REPO_UNIQUE_NAME] (0.80) -- the same value the slice
     * 10 interview decision calls out as the line that "cleanly separates unambiguously-resolved
     * call edges from ambiguous ones" (AC-15). Blast radius should not be built out of guesses.
     */
    private val blastRadiusConfidenceFloor: Double = ConfidenceDefaults.CALL_RESOLUTION_REPO_UNIQUE_NAME
) {
    private val moduleSearch = ModuleSemanticSearch(storage, moduleEmbedder, litellm)
    private val blastRadius = BlastRadius(storage)

    suspend fun explore(question: String, tokenBudget: Int = DEFAULT_EXPLORE_TOKEN_BUDGET): ExploreResponse {
        if (question.isBlank()) return emptyResponse(question, tokenBudget)

        val tokens = tokenize(question)
        val modules = matchModules(question, tokens)
        val symbolCandidates = matchSymbols(tokens, modules)

        if (modules.isEmpty() && symbolCandidates.isEmpty()) {
            return emptyResponse(question, tokenBudget)
        }

        var used = 0
        var truncated = false
        val symbolViews = symbolCandidates.map { node ->
            val provenance = storage.getProvenance(node.id.value).firstOrNull()
            if (truncated) {
                used += elidedCost(node, provenance)
                return@map elidedSymbol(node, provenance)
            }

            val source = provenance?.let { VerbatimSource.read(projectRoot, it) }
            val edges = edgeViewsFor(node.id)
            val radius = blastRadius.compute(node.id, blastRadiusConfidenceFloor)
            val radiusViews = radius.hits.map { it.toView() }
            val cost = fullCost(node, source, edges, radiusViews)

            if (used + cost <= tokenBudget) {
                used += cost
                fullSymbol(node, provenance, source, edges, radiusViews)
            } else {
                truncated = true
                used += elidedCost(node, provenance)
                elidedSymbol(node, provenance)
            }
        }

        return ExploreResponse(
            question = question,
            tokenBudget = tokenBudget,
            estimatedTokensUsed = used,
            blastRadiusConfidenceFloor = blastRadiusConfidenceFloor,
            modules = modules,
            symbols = symbolViews,
            truncated = truncated,
            empty = false
        )
    }

    private fun emptyResponse(question: String, tokenBudget: Int) = ExploreResponse(
        question = question,
        tokenBudget = tokenBudget,
        estimatedTokensUsed = 0,
        blastRadiusConfidenceFloor = blastRadiusConfidenceFloor,
        modules = emptyList(),
        symbols = emptyList(),
        truncated = false,
        empty = true
    )

    // --- Module matching ---

    private suspend fun matchModules(question: String, tokens: List<String>): List<ExploreModule> {
        val primary = try {
            moduleSearch.search(question, limit = MAX_MODULES)
        } catch (_: Exception) {
            emptyList()
        }
        val seen = primary.mapTo(mutableSetOf()) { it.node.id }

        // ModuleSemanticSearch's own keyword fallback matches the *whole* question string
        // against a module's FTS/LIKE index, which rarely lines up with a natural-language
        // question. Supplementing with a per-token substring score over name/path/description
        // is what lets `explore` work with LiteLLM disabled (the default in this environment) --
        // the primary embedding path still wins whenever it has something to say.
        val fallback = if (tokens.isNotEmpty()) {
            storage.getAllNodes(minConfidence = 0.0)
                .asSequence()
                .filter { it.type == NodeType.CodeModule && it.id !in seen }
                .map { node -> node to moduleTokenScore(node, tokens) }
                .filter { it.second > 0.0 }
                .sortedByDescending { it.second }
                .take(MAX_MODULES)
                .map { (node, score) -> ModuleSearchResult(node, score, ModuleSearchMethod.KEYWORD) }
                .toList()
        } else {
            emptyList()
        }

        return (primary + fallback).take(MAX_MODULES).map { it.toExploreModule() }
    }

    private fun moduleTokenScore(node: GraphNode, tokens: List<String>): Double {
        val name = node.label.lowercase()
        val path = node.stringProperty("path")?.lowercase().orEmpty()
        val description = node.stringProperty("description")?.lowercase().orEmpty()
        var score = 0.0
        for (t in tokens) {
            if (t in name) score += 3.0
            if (t in path) score += 2.0
            if (t in description) score += 1.0
        }
        return score
    }

    private fun ModuleSearchResult.toExploreModule(): ExploreModule {
        val description = node.stringProperty("description")
        val undescribed = node.booleanProperty("undescribed") ?: (description == null)
        val stale = node.booleanProperty("descriptionStale") ?: false
        return ExploreModule(
            id = node.id.value,
            name = node.label,
            path = node.stringProperty("path").orEmpty(),
            description = description,
            undescribed = undescribed,
            descriptionStale = stale,
            matchMethod = method.name.lowercase(),
            score = score
        )
    }

    // --- Symbol matching ---

    private fun matchSymbols(tokens: List<String>, modules: List<ExploreModule>): List<GraphNode> {
        if (tokens.isEmpty()) return emptyList()

        val candidates = LinkedHashMap<NodeId, GraphNode>()
        for (term in tokens) {
            val found = try {
                storage.searchNodes(term, minConfidence = 0.0, limit = 25)
            } catch (_: Exception) {
                emptyList()
            }
            for (node in found) if (node.isSymbol()) candidates.putIfAbsent(node.id, node)
        }

        val modulePaths = modules.map { it.path }
        return candidates.values
            .sortedWith(
                compareByDescending<GraphNode> { symbolScore(it, tokens, modulePaths) }
                    .thenBy { it.id.value }
            )
            .take(MAX_CANDIDATE_SYMBOLS)
    }

    /** A declaration-site node -- has a scope-chain id (`path#scope.chain`) -- excluding import nodes, which reuse the same id shape but are not symbols an agent would want source for. */
    private fun GraphNode.isSymbol(): Boolean = '#' in id.value && type != NodeType.Module

    private fun symbolScore(node: GraphNode, tokens: List<String>, modulePaths: List<String>): Double {
        val label = node.label.lowercase()
        var score = node.confidence
        for (t in tokens) if (t in label) score += 5.0
        val filePath = node.id.value.substringBefore('#')
        if (modulePaths.any { it.isNotEmpty() && (filePath == it || filePath.startsWith("$it/")) }) score += 2.0
        return score
    }

    // --- Edges / blast radius ---

    private fun edgeViewsFor(nodeId: NodeId): List<ExploreEdgeView> {
        val outgoing = storage.getEdgesFrom(nodeId).map { it.toView(direction = "outgoing", other = it.target) }
        val incoming = storage.getEdgesTo(nodeId).map { it.toView(direction = "incoming", other = it.source) }
        return (outgoing + incoming).take(MAX_EDGES_PER_SYMBOL)
    }

    private fun GraphEdge.toView(direction: String, other: NodeId): ExploreEdgeView {
        val otherNode = storage.getNode(other)
        return ExploreEdgeView(
            type = EdgeType.stringify(type),
            direction = direction,
            nodeId = other.value,
            label = otherNode?.label ?: other.value,
            confidence = confidence,
            rung = (properties["rung"] as? JsonPrimitive)?.content
        )
    }

    private fun io.contextgraph.query.BlastRadiusHit.toView(): ExploreBlastRadiusHit = ExploreBlastRadiusHit(
        nodeId = node.id.value,
        label = node.label,
        nodeType = NodeType.stringify(node.type),
        hops = hops,
        viaEdgeType = EdgeType.stringify(viaEdge.type),
        confidence = viaEdge.confidence
    )

    // --- Token-budget packing ---

    private fun fullSymbol(
        node: GraphNode,
        provenance: Provenance?,
        source: String?,
        edges: List<ExploreEdgeView>,
        radius: List<ExploreBlastRadiusHit>
    ) = ExploreSymbol(
        id = node.id.value,
        label = node.label,
        nodeType = NodeType.stringify(node.type),
        fqn = node.stringProperty("fqn"),
        path = provenance?.path,
        lineStart = provenance?.lineStart,
        lineEnd = provenance?.lineEnd,
        confidence = node.confidence,
        elided = false,
        source = source,
        edges = edges,
        blastRadius = radius
    )

    private fun elidedSymbol(node: GraphNode, provenance: Provenance?) = ExploreSymbol(
        id = node.id.value,
        label = node.label,
        nodeType = NodeType.stringify(node.type),
        fqn = node.stringProperty("fqn"),
        path = provenance?.path,
        lineStart = provenance?.lineStart,
        lineEnd = provenance?.lineEnd,
        confidence = node.confidence,
        elided = true,
        source = null,
        edges = emptyList(),
        blastRadius = emptyList()
    )

    private fun fullCost(node: GraphNode, source: String?, edges: List<ExploreEdgeView>, radius: List<ExploreBlastRadiusHit>): Int {
        val sourceChars = source?.length ?: 0
        val overheadChars = node.label.length + 60 + edges.size * 40 + radius.size * 40
        return charsToTokens(sourceChars + overheadChars)
    }

    private fun elidedCost(node: GraphNode, provenance: Provenance?): Int {
        val chars = node.label.length + (provenance?.path?.length ?: 0) + 30
        return charsToTokens(chars)
    }

    private fun charsToTokens(chars: Int): Int = (chars / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)

    private fun GraphNode.stringProperty(key: String): String? = (properties[key] as? JsonPrimitive)?.content

    private fun GraphNode.booleanProperty(key: String): Boolean? = stringProperty(key)?.toBooleanStrictOrNull()

    companion object {
        private const val MAX_MODULES = 5
    }
}
