package io.contextgraph.benchmark.retrieval

/**
 * precision@k, recall@k and reciprocal-rank over a *ranked* list of file paths against an
 * *expected* set of file paths (AC-24). Pure functions, no I/O, no randomness -- every input
 * pair has exactly one output, which is what AC-24's "aynı girdi aynı sayıyı verir" (same input,
 * same number) requires and what `RetrievalMetricsTest` proves against known input/output pairs.
 *
 * [ranked] is de-duplicated (first occurrence kept) before scoring in every function here: both
 * sides this module measures ([io.contextgraph.query.QueryEngine.buildContext]'s evidence list
 * and ripgrep's per-file match counts) can legitimately surface the same file more than once --
 * a node's evidence list can repeat a path, and a file can contain more than one matching line --
 * and a file should only ever count once toward precision/recall/rank, not once per occurrence.
 */
object RetrievalMetrics {

    /**
     * Of the top [k] ranked files, what fraction are in [expected]. Divides by [k] itself, not
     * by `min(k, ranked.size)`: a side that returns fewer than [k] results is not rewarded for
     * returning less -- the unfilled slots count as misses, the standard IR definition of
     * precision@k. `expected` empty is not a case AC-4 permits (every gold fact carries
     * evidence), so this is not special-cased; an empty [expected] simply yields `0.0`.
     */
    fun precisionAtK(ranked: List<String>, expected: Set<String>, k: Int): Double {
        require(k > 0) { "k must be positive, was $k" }
        val top = ranked.distinct().take(k)
        if (top.isEmpty()) return 0.0
        val hits = top.count { it in expected }
        return hits.toDouble() / k
    }

    /**
     * Of all files in [expected], what fraction appear somewhere in the top [k] ranked files.
     * `1.0` if [expected] is empty (nothing to find, so nothing is missed) -- this should not
     * occur in practice (every question has at least one gold-fact-cited file, AC-23) but is
     * defined rather than left to divide by zero.
     */
    fun recallAtK(ranked: List<String>, expected: Set<String>, k: Int): Double {
        require(k > 0) { "k must be positive, was $k" }
        if (expected.isEmpty()) return 1.0
        val top = ranked.distinct().take(k).toSet()
        val hits = expected.count { it in top }
        return hits.toDouble() / expected.size
    }

    /**
     * `1 / rank` of the first ranked file that is in [expected] (rank is 1-based), or `0.0` if
     * no ranked file is ever in [expected]. This is the per-question contribution to Mean
     * Reciprocal Rank -- averaging this across a question set is MRR itself, done by
     * [RetrievalStats], not here (this function has no notion of "a set of questions").
     */
    fun reciprocalRank(ranked: List<String>, expected: Set<String>): Double {
        val deduped = ranked.distinct()
        val index = deduped.indexOfFirst { it in expected }
        return if (index < 0) 0.0 else 1.0 / (index + 1)
    }
}
