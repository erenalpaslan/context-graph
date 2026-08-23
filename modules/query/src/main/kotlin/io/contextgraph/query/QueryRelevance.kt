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
        searchHitPoints(node) + nameMatchPoints(node) + deprioritisationPoints(node, path)

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
     * How much of the query *is* this candidate's name -- the first thing a human checks, and the
     * heaviest signal in the stack.
     *
     * A ladder, best tier wins and the tiers do not stack. The length ratio on the prefix tier is
     * the subtle part: a two-letter query prefixing a forty-character name is weak evidence and
     * has to score like it, while a query that is nearly the whole name is nearly an exact match.
     */
    private fun nameMatchPoints(node: GraphNode): Double {
        val label = node.label.lowercase()
        if (label.isEmpty() || queryLower.isEmpty()) return 0.0
        // Split the label as written, not lowercased: lowercasing first erases the camelCase
        // boundaries the splitter exists to find.
        val labelWords = IdentifierSplitter.split(node.label).map { it.lowercase() }.toSet()

        if (label == queryLower) return NAME_EXACT_POINTS
        // A question is many words; a name that is exactly one of them was named by the asker.
        if (words.size > 1 && label in words) return NAME_QUERY_WORD_POINTS
        if (label.startsWith(queryLower)) {
            return NAME_PREFIX_BASE_POINTS +
                NAME_PREFIX_RATIO_POINTS * (queryLower.length.toDouble() / label.length)
        }
        if (words.isNotEmpty() && labelWords.containsAll(words)) return NAME_ALL_SUBTERMS_POINTS
        if (label.contains(queryLower)) return NAME_SUBSTRING_POINTS
        return 0.0
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
        fun of(queryText: String, seedsInRankOrder: List<NodeId>): QueryRelevance {
            val n = seedsInRankOrder.size
            return QueryRelevance(
                queryText = queryText,
                words = IdentifierSplitter.split(queryText).map { it.lowercase() }.distinct(),
                seedRelevance = seedsInRankOrder
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

// The name ladder, CodeGraph's constants. Read top to bottom: the name is the query; the name is
// one word the query used; the name begins with the query, scaled by how much of the name that is;
// every word of the query is somewhere in the name; the name merely contains the query.
private const val NAME_EXACT_POINTS = 80.0
private const val NAME_QUERY_WORD_POINTS = 60.0
private const val NAME_PREFIX_BASE_POINTS = 10.0
private const val NAME_PREFIX_RATIO_POINTS = 30.0
private const val NAME_ALL_SUBTERMS_POINTS = 15.0
private const val NAME_SUBSTRING_POINTS = 10.0

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
