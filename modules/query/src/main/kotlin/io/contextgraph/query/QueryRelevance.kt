package io.contextgraph.query

import io.contextgraph.core.GraphNode
import io.contextgraph.core.IdentifierSplitter
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType

/**
 * How relevant a candidate is **to the query** -- the term [ContextBundler] adds to the graph's
 * own `pageRank * confidence` when deciding what a bundle's best fifty nodes are.
 *
 * It exists because that sort used to be a function of the node alone. A query selected the
 * candidate set and was then discarded, so within the set order was decided by centrality, and
 * centrality on the subgraph induced by the candidates rewards whichever cluster is most densely
 * cross-linked -- in most repositories, the documentation. The measured cost was 35.6% of the top
 * ten going to files that could not be the answer.
 *
 * Scores are points, on the scale CodeGraph publishes for the same job (an exact name match is
 * worth 80, a filename hit 10, a documentation file -15), because those constants are known to
 * work on one real corpus and are a more honest starting point than numbers invented here. They
 * are deliberately far larger than `pageRank * confidence`, which sums to 1.0 across the whole
 * candidate set and is therefore ~1e-3 per node: the graph term survives as the tie-breaker among
 * candidates the query cannot tell apart, and is dominated wherever the query can tell them apart.
 *
 * Construct one per query via [of]. A bundle with no query passes `null` instead, and ranks
 * exactly as it did before any of this existed.
 */
class QueryRelevance private constructor(
    private val queryText: String,
    private val words: List<String>,
    private val seedRelevance: Map<NodeId, Double>
) {

    private val queryLower = queryText.trim().lowercase()

    private val asksAboutDocumentation = words.any { it in DOCUMENTATION_QUERY_WORDS }
    private val asksAboutTests = words.any { it in TEST_QUERY_WORDS }

    /**
     * Points for [node], whose file is [path] (null when the node has no provenance -- a real
     * state, not an error, and every path-derived term simply does not fire for it).
     */
    fun score(node: GraphNode, path: String?): Double =
        searchHitPoints(node) + kindPoints(node) + pathPoints(path) + deprioritisationPoints(node, path)

    /**
     * What the search layer already knew and the sort used to throw away: this candidate's place
     * in the relevance ordering the query itself produced.
     *
     * Zero for a candidate that entered by graph expansion. It is a neighbour of a match, not a
     * match, and its claim on a slot is the graph's -- which the `pageRank * confidence` term
     * added alongside this one still presses on its behalf.
     */
    private fun searchHitPoints(node: GraphNode): Double =
        SEARCH_HIT_POINTS * (seedRelevance[node.id] ?: 0.0)

    /**
     * What *kind* of thing usually answers a question about code: behaviour above structure,
     * structure above containers, containers above nothing.
     *
     * A file scores zero, which is the point of the signal rather than a rounding of it. A file
     * node is a container for the thing being asked about, never the thing itself, and file nodes
     * are exactly the class that centrality used to float to the top.
     */
    private fun kindPoints(node: GraphNode): Double = kindPointsOf(node.type)

    /**
     * Where the query's words fall in the candidate's path -- how a human narrows a repository
     * before reading a line of it.
     *
     * Each query **word** contributes once, at the most specific place it reaches: the filename
     * beats a directory, a directory beats an incidental appearance anywhere else in the path.
     * Once per word and not once per sub-token, which is the mistake CodeGraph shipped and had to
     * fix -- one concept spelled several ways inflated a single path fourfold.
     */
    private fun pathPoints(path: String?): Double {
        if (path == null || words.isEmpty()) return 0.0
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return 0.0

        val fileWords = IdentifierSplitter.split(segments.last()).map { it.lowercase() }.toSet()
        val directoryWords = segments.dropLast(1)
            .flatMap { IdentifierSplitter.split(it) }
            .map { it.lowercase() }
            .toSet()
        val whole = path.lowercase()

        return words.sumOf { word ->
            when {
                word in fileWords -> PATH_FILENAME_POINTS
                word in directoryWords -> PATH_DIRECTORY_POINTS
                whole.contains(word) -> PATH_ELSEWHERE_POINTS
                else -> 0.0
            }
        }
    }

    /**
     * A penalty for candidates whose file is documentation or a test, waived when the query is
     * asking about exactly that.
     *
     * Documentation is the densest cluster in most repositories, which is why PageRank on the
     * induced subgraph kept promoting it: over a third of the ten best slots went to prose that
     * could not answer a question about how code behaves. A question about *the documentation*
     * exists too, though, and for it the same files are the answer -- so this is a de-prioritisation
     * with a waiver, not a filter. Nothing is ever removed from the candidate set here.
     */
    private fun deprioritisationPoints(node: GraphNode, path: String?): Double {
        var points = 0.0
        if (!asksAboutDocumentation && isDocumentation(node, path)) points -= DEPRIORITISED_POINTS
        if (!asksAboutTests && isTest(node, path)) points -= DEPRIORITISED_POINTS
        return points
    }

    companion object {

        /**
         * Points for the best search hit, decaying to `SEARCH_HIT_POINTS / n` for the last of n.
         *
         * Set to half of an exact name match (80, the top tier of the name ladder) and chosen before
         * any number was measured, for one reason that is worth being able to check later: a
         * candidate whose name *is* what was asked for should be able to outrank a candidate that
         * was merely the tenth-best full-text hit, while a search hit should still comfortably
         * outrank a candidate carrying nothing but a query word buried in its path (3).
         */
        private const val SEARCH_HIT_POINTS = 40.0

        /**
         * [queryText] as asked, and [seedsInRankOrder] -- the candidates that entered the set as
         * search hits, best first. Candidates absent from that list entered by graph expansion:
         * they are neighbours, not matches, and score nothing from having been found.
         */
        fun of(
            queryText: String,
            seedsInRankOrder: List<NodeId>,
            exactNameMatches: List<NodeId> = emptyList()
        ): QueryRelevance {
            val n = seedsInRankOrder.size
            return QueryRelevance(
                queryText = queryText,
                words = IdentifierSplitter.split(queryText).map { it.lowercase() }.distinct(),
                // An exact-name match enters at the *bottom* of the search-hit range, not the top.
                // CodeGraph injects at the top and lets its name ladder sort the arrivals out; that
                // ladder was measured here and removed, so injecting at the top left an exact-name
                // match standing in front of hits that were ranked correctly -- MRR 0.4259 ->
                // 0.3148. The supplement's job is to make a buried name present, not to give it
                // precedence over the query's own best answers, so it enters ranked last among the
                // hits and ahead of the expansion, and argues for itself from there.
                seedRelevance = exactNameMatches.associateWith { if (n == 0) 1.0 else 1.0 / n } +
                    seedsInRankOrder
                        .mapIndexed { i, id -> id to (n - i).toDouble() / n }
                        .toMap()
            )
        }
    }
}

/**
 * The de-prioritisation, in points. CodeGraph's constant, adopted rather than invented: large
 * enough to sink a documentation file past the code around it, small enough that a documentation
 * file which is genuinely the best search hit (40) still outranks a mediocre one.
 */
private const val DEPRIORITISED_POINTS = 15.0

// There is no name-match ladder here, and its absence is a measurement rather than an oversight.
// CodeGraph's five tiers (name == query 80, one query word == name 60, prefix 10 + 30r, all query
// sub-terms in the name 15, substring 10) were implemented exactly and measured on the same nine
// questions: MRR fell 0.4259 -> 0.3000. The 60-point tier was the cause -- a thirty-word question
// shares a common word with almost any repository, and 60 outranks the 40 the best full-text hit
// carries, so a component named `Position` displaced the file that answered a question containing
// the word "position". Removing that tier returned every metric to exactly where it had been
// (0.4259 / 0.3148 / 0.3426 / 32.00%), digit for digit, because the other four are inert on prose:
// no sentence is ever equal to, a prefix of, or a substring of an identifier, and no identifier
// contains all thirty of a sentence's words.
//
// So the ladder is worth nothing here and cost something in one form. It belongs to a search box
// taking a few words, which is the surface CodeGraph built it for; if this project grows one, the
// tiers are in this file's history at 1e9bf24.

/**
 * The kind ladder, CodeGraph's rungs mapped onto this project's [NodeType]s. Exhaustive on
 * purpose -- a `when` with no `else`, so a node type added later has to be placed deliberately
 * instead of silently scoring zero.
 *
 * Two groups sit at zero, for the same reason and not by omission. **File-level types**, because a
 * file is the container of the answer and never the answer, and floating file nodes is the failure
 * this signal exists to stop. **Non-code entities** (concepts, claims, people, requirements),
 * because this ladder ranks code symbol kinds and they are not one; giving them a rung would be
 * inventing a signal rather than adopting one. Neither is pushed *below* zero -- nothing here
 * penalises, it only distinguishes.
 */
private fun kindPointsOf(type: NodeType): Double = when (type) {
    NodeType.Function, NodeType.Method -> 10.0
    NodeType.API, NodeType.Route -> 9.0
    NodeType.Class, NodeType.Component -> 8.0
    NodeType.Module, NodeType.Package, NodeType.CodeModule -> 4.0
    NodeType.DatabaseTable -> 3.0
    NodeType.Column -> 2.0

    NodeType.CodeFile, NodeType.TestFile, NodeType.MarkdownFile, NodeType.Document,
    NodeType.PDF, NodeType.Image, NodeType.Diagram, NodeType.DatabaseSchema,
    NodeType.ConfigFile, NodeType.ResearchPaper, NodeType.PackageFile -> 0.0

    NodeType.Concept, NodeType.Claim, NodeType.Methodology, NodeType.Dataset,
    NodeType.Experiment, NodeType.Requirement, NodeType.Decision,
    NodeType.Person, NodeType.Organization -> 0.0

    is NodeType.Custom -> 0.0
}

// Path relevance, CodeGraph's constants: the most specific position a query word reaches, once.
private const val PATH_FILENAME_POINTS = 10.0
private const val PATH_DIRECTORY_POINTS = 5.0
private const val PATH_ELSEWHERE_POINTS = 3.0

private val DOCUMENTATION_QUERY_WORDS = setOf("doc", "docs", "documentation", "readme", "changelog")
private val TEST_QUERY_WORDS = setOf("test", "tests", "testing", "spec", "specs", "fixture", "fixtures")

/** Prose formats. A file in one of these is written for a reader, not for a compiler. */
private val PROSE_EXTENSIONS = setOf("md", "mdx", "markdown", "rst", "adoc", "asciidoc", "txt")

/**
 * Words that make a directory a documentation directory. Matched against the *words* of a path
 * segment rather than the segment itself, which is what makes `dev-docs`, `developer-docs` and
 * `api_documentation` fall out of one general rule instead of a list of the directory names some
 * particular repository happens to use.
 */
private val DOCUMENTATION_DIRECTORY_WORDS = setOf("doc", "docs", "documentation")

/** Directory words that make a directory a test directory, across several ecosystems' conventions. */
private val TEST_DIRECTORY_WORDS = setOf("test", "tests", "spec", "specs", "testing", "fixtures", "mocks")

/**
 * Words that make a *filename* a test's, checked as whole words of the identifier -- `FooTest`,
 * `foo.test.ts`, `test_foo.py`, `FooSpec` -- so that `latest.ts`, whose last four letters spell
 * one of them, is not mistaken for one.
 */
private val TEST_FILENAME_WORDS = setOf("test", "tests", "spec", "specs")

/** Artefact types the indexer has already classified as prose. */
private val DOCUMENTATION_NODE_TYPES =
    setOf(NodeType.MarkdownFile, NodeType.Document, NodeType.PDF, NodeType.ResearchPaper)

private fun isDocumentation(node: GraphNode, path: String?): Boolean {
    if (node.type in DOCUMENTATION_NODE_TYPES) return true
    if (path == null) return false
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return false
    if (segments.last().substringAfterLast('.', "").lowercase() in PROSE_EXTENSIONS) return true
    return segments.dropLast(1).any { segment ->
        IdentifierSplitter.split(segment).any { it.lowercase() in DOCUMENTATION_DIRECTORY_WORDS }
    }
}

private fun isTest(node: GraphNode, path: String?): Boolean {
    if (node.type == NodeType.TestFile) return true
    if (path == null) return false
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return false
    val inTestDirectory = segments.dropLast(1).any { segment ->
        IdentifierSplitter.split(segment).any { it.lowercase() in TEST_DIRECTORY_WORDS }
    }
    if (inTestDirectory) return true
    // First or last word only: `TestHarness` and `CartTest` are tests, `ContestEntry` is not, and
    // a word buried mid-identifier (`RequestTestimonial`) is not evidence either.
    val words = IdentifierSplitter.split(segments.last().substringBeforeLast('.')).map { it.lowercase() }
    return words.isNotEmpty() && (words.first() in TEST_FILENAME_WORDS || words.last() in TEST_FILENAME_WORDS)
}
