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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.math.ln
import kotlin.math.round

/**
 * How much an `explore` response carries per symbol.
 *
 * [POINTERS] is the default: a ranked list of locations, each with why it matched and its most
 * useful call edges, and verbatim source for the top few only. An agent reads the files it then
 * needs. The response stays in the agent's context for every later turn, so a small one that
 * points at the right code costs less than a large one carrying source the agent never uses --
 * measured on an agent benchmark, the agent re-read most of what a full response had sent anyway.
 *
 * [FULL] carries verbatim source, up to 20 edges and the whole blast radius for every symbol that
 * fits the budget -- the original slice 17 shape, for callers that want the source in one call.
 */
enum class ExploreDetail { POINTERS, FULL }

/**
 * Default token budget for a [ExploreDetail.FULL] response (slice 17, AC-37 / interview Q4).
 * ~15k tokens -- generous enough for several symbols' full source, small enough that a single
 * call never dwarfs an agent's context window on its own.
 */
const val DEFAULT_EXPLORE_TOKEN_BUDGET = 15_000

/** Default token budget for a [ExploreDetail.POINTERS] response: room for about 15 pointers and two snippets. */
const val DEFAULT_POINTERS_TOKEN_BUDGET = 4_000

/**
 * Characters per token for the budget -- not a real tokenizer. Source code averages close to 4
 * characters per token under most BPE tokenizers. The budget is checked against the length of
 * the response as [encodeExploreResponse] writes it, so the only approximation left is this
 * ratio, never the size of the payload itself.
 */
private const val CHARS_PER_TOKEN = 4.0

/** Path segments that mark a test source root. Compared lowercased. */
private val TEST_DIRECTORY_SEGMENTS = setOf(
    "test", "tests", "__tests__", "androidtest", "testfixtures", "unittest", "integrationtest", "spec", "specs"
)

/** Question words that make test sources relevant again, so they keep their normal rank. */
private val TEST_QUESTION_TERMS = setOf("test", "tests", "testing", "spec", "specs")

/**
 * The one encoder for an `explore` response, used both for the wire and for budget accounting.
 * Compact rather than pretty-printed: indentation was a third of the payload, and an agent
 * reads compact JSON as easily. Null fields are left out rather than written as `null`.
 */
private val exploreJson = Json { encodeDefaults = true; explicitNulls = false }

internal fun encodeExploreResponse(response: ExploreResponse): String =
    exploreJson.encodeToString(ExploreResponse.serializer(), response)

private fun encodedLength(symbol: ExploreSymbol): Int =
    exploreJson.encodeToString(ExploreSymbol.serializer(), symbol).length

/**
 * Whether [path] is test code: under a test source root (`src/test`, `__tests__`, ...) or named
 * like a test file (`FooTest.kt`, `foo.test.ts`, `foo_test.go`, `test_foo.py`).
 */
internal fun isTestSourcePath(path: String): Boolean {
    val segments = path.split('/')
    if (segments.dropLast(1).any { it.lowercase() in TEST_DIRECTORY_SEGMENTS }) return true
    val fileName = segments.last()
    val stem = fileName.substringBefore('.')
    return stem.endsWith("Test") || stem.endsWith("Tests") || stem.endsWith("Spec") ||
        stem.endsWith("_test") || stem.startsWith("test_") ||
        ".test." in fileName || ".spec." in fileName
}

/**
 * Lowercased words of an identifier: `ShoppingCartController` -> shopping, cart, controller;
 * `MAX_CART_ITEMS` -> max, cart, items; `parseHTTPResponse` -> parse,
 * http, response.
 */
internal fun identifierWords(label: String): List<String> =
    Regex("[A-Z]+(?![a-z])|[A-Z]?[a-z]+|[0-9]+")
        .findAll(label)
        .map { it.value.lowercase() }
        .toList()

/** Bounds how many candidate symbols get ranked and (for the top ones) have blast radius computed, independent of the token budget -- keeps one `explore` call bounded on a repo where a query matches hundreds of symbols. */
private const val MAX_CANDIDATE_SYMBOLS = 40

/** Bounds edges shown per symbol in a full response so one hub symbol's edge list cannot dominate it. */
private const val MAX_EDGES_PER_SYMBOL = 20

/** Callers and callees listed per pointer, each. */
private const val MAX_POINTER_LINKS = 3

/** How many of the top pointers carry verbatim source. */
internal const val POINTER_SOURCE_SYMBOLS = 2

/** A pointer's source is cut to this many lines; the agent reads the file for the rest. */
private const val POINTER_SOURCE_MAX_LINES = 60

/** Rows asked of the full-text index for the whole question. bm25 puts rows matching several terms first. */
private const val POOL_QUESTION_LIMIT = 120

/** Rows taken into the pool per term, so a term the whole-question search drowned out still contributes. */
private const val POOL_TERM_LIMIT = 15

/**
 * Rows counted per term to weigh it by rarity. A term reaching this many symbols ("value",
 * "user", "new") is common in the codebase and says little about which code the question means;
 * a term reaching three ("retryingoutboxpublisher") says nearly everything.
 */
private const val RARITY_SAMPLE = 1_000

/** Rows taken into the pool per adjacent word pair. */
private const val POOL_PAIR_LIMIT = 30

/** Bonus per adjacent question word pair found in order among a symbol's identifier words. */
private const val PHRASE_BONUS = 4.0

/** How many of the best lexical matches have their graph neighbours pulled in as candidates. */
private const val EXPANSION_SEEDS = 10

/** Neighbours pulled in per seed. */
private const val EXPANSION_PER_SEED = 5

/** Edges below this confidence are guesses and count for neither structure nor links. */
private const val MIN_RANKING_EDGE_CONFIDENCE = 0.5

/** Weight of a symbol's own resolved-edge count, log-scaled: a flow's classes and methods call things, a constant does not. */
private const val STRUCTURE_WEIGHT = 0.5

/** Resolved edges counted at most, so a hub every file calls (a result type, a logger) does not rank on structure alone. */
private const val MAX_COUNTED_STRUCTURAL_EDGES = 30

/**
 * Weight of the links to other candidates, each counted by that candidate's own lexical
 * relevance relative to the best match: symbols that connect to other strong matches are the
 * flow the question is about, while links to weak matches count for little.
 */
private const val LINK_WEIGHT = 4.0

/** Links counted at most, strongest first, so a hub that touches everything does not outrank on links alone. */
private const val MAX_COUNTED_LINKS = 5

/** Bonus when a question word is the symbol's whole compound name (`retryingoutboxpublisher`): the question named it. */
private const val EXACT_NAME_BONUS = 12.0

/** Bonus for a symbol inside a module the question matched. */
private const val MODULE_BONUS = 2.0

/** Edge types that carry how code is connected. Containment, imports and sibling links say where code sits, not what it does. */
private val RANKING_EDGE_TYPES: Set<EdgeType> = setOf(EdgeType.Calls, EdgeType.Implements, EdgeType.References, EdgeType.DependsOn, EdgeType.Uses)

/** Edge types that link two candidates into one flow: the ranking edges plus containment (a class and its method). */
private val LINK_EDGE_TYPES: Set<EdgeType> = RANKING_EDGE_TYPES + EdgeType.Contains + EdgeType.Defines

/**
 * How much a node type's lexical match counts. Declarations that do things (classes, functions,
 * methods) count fully; values and fields count for little, because their names are often the
 * question's words joined with underscores (`ACTION_APPLY_COUPON`) while holding none of
 * the behaviour the question asks about.
 */
private fun typeWeight(type: NodeType): Double = when (NodeType.stringify(type)) {
    "Class", "Interface", "Object", "Enum", "Function", "Method", "API", "Route", "Component" -> 1.0
    "CompanionObject", "TypeAlias" -> 0.5
    "Property", "Field", "Column", "Variable", "Constant" -> 0.5
    else -> 0.6
}

private val STOPWORDS = setOf(
    "the", "a", "an", "is", "are", "was", "were", "do", "does", "did", "how", "what", "where",
    "when", "why", "who", "which", "to", "of", "in", "on", "for", "and", "or", "with", "this",
    "that", "it", "its", "be", "as", "at", "by", "from"
)

/** Lowercased, de-duplicated, stopword- and short-token-filtered words from [text] -- the terms [ExploreEngine] searches on and scores matches against. */
internal fun tokenize(text: String): List<String> = termSequence(text).distinct().toList()

private fun termSequence(text: String): Sequence<String> =
    Regex("[A-Za-z0-9_]+").findAll(text)
        .map { it.value.lowercase() }
        .filter { it.length >= 3 && it !in STOPWORDS }

/**
 * Adjacent word pairs of [text], in order and de-duplicated: "shopping cart apply coupon" ->
 * shopping cart, cart apply, apply coupon. Code names a concept with the same words joined
 * (`ShoppingCart`, `ApplyCoupon`), so a pair that appears in order inside an identifier is far
 * stronger evidence than either word alone.
 */
internal fun termPairs(text: String): List<Pair<String, String>> =
    termSequence(text).zipWithNext().distinct().toList()

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
    val blastRadius: List<ExploreBlastRadiusHit>,
    /** Ranking score; higher is more relevant. Comparable within one response only. */
    val score: Double? = null,
    /** Why this symbol ranked: the question words it matched and how it connects to the other results. */
    val why: String? = null,
    /** Pointers only: how many symbols depend on this one (its blast radius size). */
    val dependents: Int? = null,
    /** True when [source] was cut to its first lines. */
    val sourceTruncated: Boolean? = null,
    /** Pointers only: what this symbol calls or uses, strongest first, as `File.kt#Scope.member()`. */
    val calls: List<String>? = null,
    /** Pointers only: what calls or uses this symbol, strongest first, as `File.kt#Scope.member()`. */
    val calledBy: List<String>? = null
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
    val empty: Boolean,
    val detail: String = "full"
)

/** A ranked candidate and the evidence for its rank, kept so the response can say why. */
private class RankedSymbol(
    val node: GraphNode,
    val edges: List<GraphEdge>,
    val matchedTerms: List<String>,
    val links: Int,
    val structuralEdges: Int,
    val score: Double
)

/**
 * The engine behind the `explore` MCP tool (slice 17): one natural-language question in,
 * matched modules + ranked symbols + resolved edges (with confidence and rung) + blast radius
 * out, packed into a token budget, with verbatim source for the top symbols ([ExploreDetail]).
 * See `McpServer.kt` for how this is wired up as an MCP tool.
 *
 * **Ranking.** Candidates come from the full-text index (the whole question, then each term),
 * plus the call-graph neighbours of the best lexical matches, so a flow's next step is found even
 * when its name shares no word with the question. Each candidate is scored on three things: how
 * well its name matches the question words (whole identifier words count more than substrings,
 * weighted by node type), how many resolved edges it has, and how many of the other candidates
 * it is directly linked to. The last is what uses the graph: the symbols a question is about tend
 * to call each other, while a constant that happens to repeat the question's words links to
 * nothing.
 *
 * Read-only over [storage] and the source tree at [projectRoot].
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

    suspend fun explore(
        question: String,
        tokenBudget: Int? = null,
        detail: ExploreDetail = ExploreDetail.POINTERS
    ): ExploreResponse {
        val budget = tokenBudget ?: when (detail) {
            ExploreDetail.POINTERS -> DEFAULT_POINTERS_TOKEN_BUDGET
            ExploreDetail.FULL -> DEFAULT_EXPLORE_TOKEN_BUDGET
        }
        val detailName = detail.name.lowercase()
        if (question.isBlank()) return emptyResponse(question, budget, detailName)

        val tokens = tokenize(question)
        val modules = matchModules(question, tokens)
        val ranked = rankSymbols(tokens, termPairs(question), modules)

        if (modules.isEmpty() && ranked.isEmpty()) {
            return emptyResponse(question, budget, detailName)
        }

        val shell = ExploreResponse(
            question = question,
            tokenBudget = budget,
            estimatedTokensUsed = 0,
            blastRadiusConfidenceFloor = blastRadiusConfidenceFloor,
            modules = modules,
            symbols = emptyList(),
            truncated = false,
            empty = false,
            detail = detailName
        )
        // Budget in characters of the encoded response. The shell is everything but the
        // symbols; the slack covers `estimatedTokensUsed` growing from "0" and `truncated`
        // flipping to "true". Each symbol then costs its own encoded length plus a comma.
        val budgetChars = (budget * CHARS_PER_TOKEN).toInt()
        var usedChars = encodeExploreResponse(shell).length + ENCODING_SLACK_CHARS
        var truncated = false
        val symbolViews = mutableListOf<ExploreSymbol>()

        for ((index, candidate) in ranked.withIndex()) {
            val provenance = storage.getProvenance(candidate.node.id.value).firstOrNull()
            if (!truncated) {
                val view = when (detail) {
                    ExploreDetail.FULL -> fullSymbol(candidate, provenance)
                    ExploreDetail.POINTERS -> pointerSymbol(candidate, provenance, withSource = index < POINTER_SOURCE_SYMBOLS)
                }
                val cost = encodedLength(view) + 1
                if (usedChars + cost <= budgetChars) {
                    usedChars += cost
                    symbolViews += view
                    continue
                }
                truncated = true
            }
            // Past the elision line: signature and location only, while even that fits.
            val elided = elidedSymbol(candidate, provenance)
            val cost = encodedLength(elided) + 1
            if (usedChars + cost > budgetChars) break
            usedChars += cost
            symbolViews += elided
        }
        truncated = truncated || symbolViews.size < ranked.size

        val response = shell.copy(symbols = symbolViews, truncated = truncated)
        return response.copy(estimatedTokensUsed = encodedTokens(response))
    }

    /** Tokens of [response] as encoded, including the digits of its own `estimatedTokensUsed`. */
    private fun encodedTokens(response: ExploreResponse): Int {
        var estimate = 0
        repeat(3) {
            estimate = (encodeExploreResponse(response.copy(estimatedTokensUsed = estimate)).length / CHARS_PER_TOKEN).toInt()
        }
        return estimate
    }

    private fun emptyResponse(question: String, tokenBudget: Int, detail: String) = ExploreResponse(
        question = question,
        tokenBudget = tokenBudget,
        estimatedTokensUsed = 0,
        blastRadiusConfidenceFloor = blastRadiusConfidenceFloor,
        modules = emptyList(),
        symbols = emptyList(),
        truncated = false,
        empty = true,
        detail = detail
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

    // --- Symbol ranking ---

    private fun rankSymbols(tokens: List<String>, pairs: List<Pair<String, String>>, modules: List<ExploreModule>): List<RankedSymbol> {
        if (tokens.isEmpty()) return emptyList()

        // 1. Lexical pool: the whole question (bm25 ranks rows matching several terms first),
        //    then each term on its own so no single term is drowned out.
        val pool = LinkedHashMap<NodeId, GraphNode>()
        fun addFound(query: String, limit: Int) {
            val found = try {
                storage.searchNodes(query, minConfidence = 0.0, limit = limit)
            } catch (_: Exception) {
                emptyList()
            }
            for (node in found) if (node.isSymbol()) pool.putIfAbsent(node.id, node)
        }
        addFound(tokens.joinToString(" "), POOL_QUESTION_LIMIT)
        val rarity = HashMap<String, Double>()
        for (term in tokens) {
            val found = try {
                storage.searchNodes(term, minConfidence = 0.0, limit = RARITY_SAMPLE)
            } catch (_: Exception) {
                emptyList()
            }
            rarity[term] = ln(1.0 + RARITY_SAMPLE.toDouble() / (1 + found.size))
            found.asSequence().filter { it.isSymbol() }.take(POOL_TERM_LIMIT).forEach { pool.putIfAbsent(it.id, it) }
        }
        for ((first, second) in pairs) addFound("$first $second", POOL_PAIR_LIMIT)

        val lexical = pool.values.associate { it.id to lexicalMatch(it, tokens, pairs, rarity) }
        val seeds = pool.values
            .sortedWith(compareByDescending<GraphNode> { lexical.getValue(it.id).first * typeWeight(it.type) }.thenBy { it.id.value })
            .take(MAX_CANDIDATE_SYMBOLS)

        // 2. Score the lexical seeds on their name, their own connectedness and their links to
        //    each other -- the same score as the final ranking, just over the seeds alone.
        val edgesById = HashMap<NodeId, List<GraphEdge>>()
        fun edgesOf(id: NodeId): List<GraphEdge> = edgesById.getOrPut(id) { storage.getEdgesFrom(id) + storage.getEdgesTo(id) }
        val modulePaths = modules.map { it.path }.filter { it.isNotEmpty() }

        fun score(candidates: Collection<GraphNode>): List<RankedSymbol> {
            val candidateIds = candidates.mapTo(HashSet()) { it.id }
            val weighted = candidates.associate { node ->
                val (lexicalScore, _) = lexical[node.id] ?: lexicalMatch(node, tokens, pairs, rarity)
                node.id to lexicalScore * typeWeight(node.type)
            }
            val bestLexical = weighted.values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
            return candidates.map { node ->
                val matched = (lexical[node.id] ?: lexicalMatch(node, tokens, pairs, rarity)).second
                val edges = edgesOf(node.id)
                val structural = edges.count { it.type in RANKING_EDGE_TYPES && it.confidence >= MIN_RANKING_EDGE_CONFIDENCE }
                val linked = edges.asSequence()
                    .filter { it.type in LINK_EDGE_TYPES && it.confidence >= MIN_RANKING_EDGE_CONFIDENCE }
                    .map { if (it.source == node.id) it.target else it.source }
                    .filter { it != node.id && it in candidateIds }
                    .distinct()
                    .toList()
                val linkStrength = linked
                    .map { weighted.getValue(it) / bestLexical }
                    .sortedDescending()
                    .take(MAX_COUNTED_LINKS)
                    .sum()
                val filePath = node.id.value.substringBefore('#')
                val inModule = modulePaths.any { filePath == it || filePath.startsWith("$it/") }
                val total = weighted.getValue(node.id) +
                    STRUCTURE_WEIGHT * ln(1.0 + minOf(structural, MAX_COUNTED_STRUCTURAL_EDGES)) +
                    LINK_WEIGHT * linkStrength +
                    (if (inModule) MODULE_BONUS else 0.0)
                RankedSymbol(node, edges, matched, linked.size, structural, total)
            }
        }

        // 3. Graph expansion from the best-scoring seeds: their call-graph neighbours, and a
        //    matched method's enclosing class. A flow's next hop (the service a controller calls,
        //    the endpoint a client hits) often shares no word with the question, so the index
        //    alone never finds it; and a question about `applyCoupon` is usually a question
        //    about the class that declares it.
        val candidates = LinkedHashMap<NodeId, GraphNode>()
        for (seed in seeds) candidates[seed.id] = seed
        val expandFrom = score(seeds).sortedByDescending { it.score }.take(EXPANSION_SEEDS)
        for (seed in expandFrom) {
            val seedId = seed.node.id
            val enclosing = seed.edges.filter { it.type == EdgeType.Contains && it.target == seedId }
            val connections = seed.edges
                .filter { it.type in RANKING_EDGE_TYPES && it.confidence >= MIN_RANKING_EDGE_CONFIDENCE }
                .sortedByDescending { it.confidence }
            (enclosing + connections).asSequence()
                .map { if (it.source == seedId) it.target else it.source }
                .filter { it !in candidates }
                .distinct()
                .mapNotNull { storage.getNode(it) }
                .filter { it.isSymbol() }
                .take(EXPANSION_PER_SEED)
                .forEach { candidates[it.id] = it }
        }

        // 4. Final score over seeds and expansion together.
        val ranked = score(candidates.values)

        // Test sources go after production sources unless the question is about tests: their
        // names repeat the production terms (`returnsCachedBalance`), so on score alone they
        // crowd out the code the question is actually about.
        val demoteTests = tokens.none { it in TEST_QUESTION_TERMS }
        return ranked
            .sortedWith(
                compareBy<RankedSymbol> { demoteTests && isTestSourcePath(it.node.id.value.substringBefore('#')) }
                    .thenByDescending { it.score }
                    .thenBy { it.node.id.value }
            )
            .take(MAX_CANDIDATE_SYMBOLS)
    }

    /**
     * Lexical score of [node]'s label against [tokens] and the terms it matched. A term equal to
     * one of the label's identifier words counts most, a term sharing a stem with one (balance /
     * balances) next, a bare substring least; a match only in the fully qualified name (its
     * package or enclosing class) counts a little. Each term counts by its [rarity], and a term
     * that is the whole name adds [EXACT_NAME_BONUS], and each adjacent question word pair found
     * in order among the identifier's words adds [PHRASE_BONUS].
     */
    private fun lexicalMatch(
        node: GraphNode,
        tokens: List<String>,
        pairs: List<Pair<String, String>>,
        rarity: Map<String, Double>
    ): Pair<Double, List<String>> {
        val words = identifierWords(node.label)
        val label = node.label.lowercase()
        val qualified = (node.stringProperty("fqn") ?: node.id.value).lowercase()
        var score = 0.0
        val matched = mutableListOf<String>()
        for (t in tokens) {
            val termScore = when {
                t in words -> 3.0
                words.any { it.length >= 4 && t.length >= 4 && (it.startsWith(t) || t.startsWith(it)) } -> 2.0
                t in label -> 1.0
                t in qualified -> 0.5
                else -> 0.0
            }
            if (termScore > 0.0) {
                score += termScore * (rarity[t] ?: 1.0)
                if (termScore >= 1.0) matched += t
            }
        }
        // Only a compound name counts as the question naming it: `retryingoutboxpublisher` is
        // one symbol, while `get` or `close` as a question word names nothing in particular.
        if (words.size >= 2 && label in tokens) score += EXACT_NAME_BONUS
        val adjacent = words.zipWithNext().toSet()
        score += PHRASE_BONUS * pairs.count { it in adjacent }
        return score to matched
    }

    /** A declaration-site node -- has a scope-chain id (`path#scope.chain`) -- excluding import nodes, which reuse the same id shape but are not symbols an agent would want source for. */
    private fun GraphNode.isSymbol(): Boolean = '#' in id.value && type != NodeType.Module

    // --- Edges / blast radius ---

    /**
     * [edges] as views, most useful first: how-it-connects edges before containment, imports and
     * sibling links; within those, the stronger resolution first.
     */
    private fun edgeViews(nodeId: NodeId, edges: List<GraphEdge>, limit: Int): List<ExploreEdgeView> =
        edges.asSequence()
            .sortedWith(compareBy<GraphEdge> { it.type !in RANKING_EDGE_TYPES }.thenByDescending { it.confidence })
            .take(limit)
            .map { edge ->
                if (edge.source == nodeId) edge.toView("outgoing", edge.target) else edge.toView("incoming", edge.source)
            }
            .toList()

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

    private fun fullSymbol(candidate: RankedSymbol, provenance: Provenance?): ExploreSymbol {
        val node = candidate.node
        return ExploreSymbol(
            id = node.id.value,
            label = node.label,
            nodeType = NodeType.stringify(node.type),
            fqn = node.stringProperty("fqn"),
            path = provenance?.path,
            lineStart = provenance?.lineStart,
            lineEnd = provenance?.lineEnd,
            confidence = node.confidence,
            elided = false,
            source = provenance?.let { VerbatimSource.read(projectRoot, it) },
            edges = edgeViews(node.id, candidate.edges, MAX_EDGES_PER_SYMBOL),
            blastRadius = blastRadius.compute(node.id, blastRadiusConfidenceFloor).hits.map { it.toView() },
            score = roundScore(candidate.score),
            why = why(candidate)
        )
    }

    /**
     * A pointer: location, why it ranked, its strongest callers and callees as short references,
     * and how many symbols depend on it. Only the top [POINTER_SOURCE_SYMBOLS] carry source, cut
     * to [POINTER_SOURCE_MAX_LINES] lines.
     */
    private fun pointerSymbol(candidate: RankedSymbol, provenance: Provenance?, withSource: Boolean): ExploreSymbol {
        val node = candidate.node
        val fullSource = if (withSource) provenance?.let { VerbatimSource.read(projectRoot, it) } else null
        val sourceLines = fullSource?.lines()
        val cut = sourceLines != null && sourceLines.size > POINTER_SOURCE_MAX_LINES
        val connections = candidate.edges
            .filter { it.type in RANKING_EDGE_TYPES && it.confidence >= MIN_RANKING_EDGE_CONFIDENCE }
            .sortedByDescending { it.confidence }
        return ExploreSymbol(
            id = node.id.value,
            label = node.label,
            nodeType = NodeType.stringify(node.type),
            fqn = null,
            path = provenance?.path,
            lineStart = provenance?.lineStart,
            lineEnd = provenance?.lineEnd,
            confidence = node.confidence,
            elided = fullSource == null,
            source = if (cut) sourceLines!!.take(POINTER_SOURCE_MAX_LINES).joinToString("\n") else fullSource,
            edges = emptyList(),
            blastRadius = emptyList(),
            score = roundScore(candidate.score),
            why = why(candidate),
            dependents = blastRadius.compute(node.id, blastRadiusConfidenceFloor).hits.size.takeIf { it > 0 },
            sourceTruncated = if (cut) true else null,
            calls = connections.filter { it.source == node.id }.map { shortReference(it.target) }.distinct().take(MAX_POINTER_LINKS).ifEmpty { null },
            calledBy = connections.filter { it.target == node.id }.map { shortReference(it.source) }.distinct().take(MAX_POINTER_LINKS).ifEmpty { null }
        )
    }

    /** `File.kt#Scope.member()` for a declaration-site id: enough to find it, a third the length of the full id. */
    private fun shortReference(id: NodeId): String {
        val file = id.value.substringBefore('#').substringAfterLast('/')
        val scope = id.value.substringAfter('#', missingDelimiterValue = "")
        return if (scope.isEmpty()) file else "$file#$scope"
    }

    private fun elidedSymbol(candidate: RankedSymbol, provenance: Provenance?): ExploreSymbol {
        val node = candidate.node
        return ExploreSymbol(
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
            blastRadius = emptyList(),
            score = roundScore(candidate.score)
        )
    }

    private fun why(candidate: RankedSymbol): String = buildList {
        if (candidate.matchedTerms.isNotEmpty()) add("matches ${candidate.matchedTerms.joinToString(", ")}")
        else add("graph neighbour of a match")
        if (candidate.links > 0) add("linked to ${candidate.links} other result${if (candidate.links == 1) "" else "s"}")
        if (candidate.structuralEdges > 0) add("${candidate.structuralEdges} resolved edge${if (candidate.structuralEdges == 1) "" else "s"}")
    }.joinToString("; ")

    private fun roundScore(score: Double): Double = round(score * 100) / 100

    private fun GraphNode.stringProperty(key: String): String? = (properties[key] as? JsonPrimitive)?.content

    private fun GraphNode.booleanProperty(key: String): Boolean? = stringProperty(key)?.toBooleanStrictOrNull()

    companion object {
        private const val MAX_MODULES = 5

        /** Room for `estimatedTokensUsed` to grow from one digit to seven and `truncated` to flip. */
        private const val ENCODING_SLACK_CHARS = 8
    }
}
