package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory

/**
 * One question's measurement, projected into the form a per-question *presentation* needs -- the
 * shared derivation behind every surface that shows this run question by question.
 *
 * It exists because there is more than one such surface. [RetrievalReportGenerator] prints a
 * markdown table; the documentation site presents the same rows interactively. Two surfaces each
 * deriving "which side found the gold file first" from the raw ranked lists is two chances to
 * derive it differently, and a site and a report disagreeing about the same question's score would
 * discredit both. So the derivation lives here, once, and both render it.
 *
 * Five things are derived rather than read straight out of [RetrievalRunResult], and each is a
 * decision the surfaces must not make for themselves:
 *
 * - **[QuestionSideScore.firstGoldHitRank]** -- the position of the first gold-cited file in a
 *   side's own answer, which is what tells "found it at rank 1" from "found it at rank 9" from
 *   "never found it at all". A mean erases that distinction entirely.
 * - **absence versus zero** -- a side with no measurement for a question is a `null`
 *   [QuestionSideScore], never a zeroed one. Exactly the distinction
 *   [RetrievalStats.summarize] keeps for the aggregates, kept here for the rows beneath them.
 * - **[missedByEverySide]** -- a question no side found anything for says something about the
 *   question rather than about the tools, and a surface has to be able to mark it as such.
 * - **[leaders]** -- who actually won the question, ties included, so no surface has to invent
 *   its own tie-breaking rule (and so none of them silently breaks a tie in a favoured
 *   direction).
 * - **[leaderVerdict]** -- [leaders], named, so a surface renders "who won" as text without
 *   re-deriving the "one leader vs. a tie" wording for itself.
 *
 * Nothing here filters, re-orders or re-scores anything: [project] returns every question the run
 * scored, ordered by repo and then by question id, so a row where any side loses is exactly as
 * easy to find as one where it wins.
 */
data class PerQuestionBreakdown(
    val questionId: String,
    val repoId: String,
    val category: QuestionCategory,
    /** The gold-fact-derived file set every side was scored against, in the order it was recorded. */
    val expectedFiles: List<String>,
    /** The shared tokens both text-search sides were given -- see [RetrievalRunResult.ripgrepQueryTokens]. */
    val queryTokens: List<String>,
    /**
     * Every side, in [RetrievalSide] order, mapped to its score or to `null` where that side has
     * no measurement for this question. Every side is present as a key: a surface iterating this
     * map renders an absent side as absent rather than omitting the column and hiding the absence.
     */
    val scores: Map<RetrievalSide, QuestionSideScore?>
) {
    /** How many files this question was scored against -- the denominator of every recall figure for it. */
    val expectedFileCount: Int get() = expectedFiles.size

    /** The sides that actually measured this question, in [RetrievalSide] order. */
    val measured: List<QuestionSideScore> get() = scores.values.filterNotNull()

    /**
     * True when every side that measured this question returned no gold-cited file *anywhere* in
     * its ranked list -- not merely below `k`, but nowhere at all.
     *
     * The strong form is deliberate: it is the only version of the claim that is true at every `k`
     * and on every metric at once, so a surface can mark the row without qualifying which figure
     * it means. A question where every side found something at rank 200 scores ~0 on everything
     * this run prints, but the tools did reach it, and saying otherwise would be an overstatement
     * in the one column a reader trusts to be literal.
     *
     * False when no side measured the question at all: nothing was asked, so nothing was missed.
     * (Unreachable through [RetrievalRunResult] as it stands -- [RetrievalRunResult.ripgrep] is
     * non-null by type -- and guarded anyway, because the alternative is a vacuous `true` that
     * would mark a row nobody measured as a failure of every tool.)
     */
    val missedByEverySide: Boolean get() = measured.isNotEmpty() && measured.none { it.foundGoldFile }

    /**
     * The side(s) that put a gold-cited file highest, tied sides included, empty when every
     * measured side missed. Ranked on the rank itself rather than on a rounded score, so two
     * sides that genuinely differ are never reported as tied.
     */
    val leaders: List<QuestionSideScore>
        get() {
            val best = measured.mapNotNull { it.firstGoldHitRank }.minOrNull() ?: return emptyList()
            return measured.filter { it.firstGoldHitRank == best }
        }

    /**
     * [leaders], named: `"X leads"` for one, `"X, Y tie at rank N"` for a tie, `null` when
     * [leaders] is empty.
     *
     * Both [RetrievalReportGenerator] and [RetrievalSitePage] render a verdict for every question,
     * and until this was hoisted each held its own copy of exactly this naming -- one difference
     * away from the report and the site disagreeing about who won the same question. `null` rather
     * than a third piece of wording for the empty case: that case, and the separate "nothing was
     * even measured" case, are each surface's own -- the report bolds one and adds a pointer to its
     * own "Skipped" section, the site's is plain JSON text meant for a page to style itself -- so
     * only the naming that is genuinely identical between them lives here.
     */
    fun leaderVerdict(): String? {
        val won = leaders
        if (won.isEmpty()) return null
        return if (won.size == 1) {
            "${won.single().side.label} leads"
        } else {
            "${won.joinToString(", ") { it.side.label }} tie at rank ${won.first().firstGoldHitRank}"
        }
    }

    fun scoreFor(side: RetrievalSide): QuestionSideScore? = scores[side]

    companion object {
        /** Every question the run scored, ordered by repo and then by question id. */
        fun project(run: RetrievalRun): List<PerQuestionBreakdown> =
            run.results
                .sortedWith(compareBy({ it.repoId }, { it.questionId }))
                .map { result ->
                    val expected = result.expectedFiles.toSet()
                    PerQuestionBreakdown(
                        questionId = result.questionId,
                        repoId = result.repoId,
                        category = result.category,
                        expectedFiles = result.expectedFiles,
                        queryTokens = result.ripgrepQueryTokens,
                        scores = RetrievalSide.entries.associateWith { side ->
                            result.sideResult(side)?.let { QuestionSideScore.of(side, it, expected) }
                        }
                    )
                }

        /**
         * [project]'s output grouped by repo, repos in ascending order and each repo's questions in
         * the order [project] gives them.
         */
        fun byRepo(run: RetrievalRun): Map<String, List<PerQuestionBreakdown>> =
            project(run).groupBy { it.repoId }
    }
}

/**
 * One side's measurement of one question: what it scored, and how deep in its own answer the first
 * gold-cited file sat.
 *
 * The two are the same fact twice, on purpose -- [reciprocalRank] is `1 / firstGoldHitRank` -- and
 * both are carried because they answer different questions. The score is what the MRR rows of the
 * aggregate tables are the mean of, so it is what connects a row to the table above it; the rank
 * is what a reader can act on.
 */
data class QuestionSideScore(
    val side: RetrievalSide,
    val reciprocalRank: Double,
    /**
     * 1-based position of the first gold-cited file in this side's ranked list, or `null` when it
     * returned none anywhere. Counted over the *de-duplicated* list, exactly as [RetrievalMetrics]
     * scores it -- a side can legitimately name the same file twice, and a rank counted over raw
     * positions would disagree with the reciprocal rank printed beside it.
     */
    val firstGoldHitRank: Int?,
    val precisionAtK: Map<Int, Double>,
    val recallAtK: Map<Int, Double>,
    /**
     * This side's answer as it was scored: its own ranked files, de-duplicated, in its own emission
     * order. Carried rather than left to be fetched back out of the run, so a surface showing the
     * answer beneath the score shows the same list the score was computed from.
     */
    val rankedFiles: List<String>
) {
    /** Whether this side reached a gold-cited file at all, at any depth. */
    val foundGoldFile: Boolean get() = firstGoldHitRank != null

    companion object {
        fun of(side: RetrievalSide, result: SideResult, expected: Set<String>): QuestionSideScore {
            val deduped = result.rankedFiles.distinct()
            val index = deduped.indexOfFirst { it in expected }
            return QuestionSideScore(
                side = side,
                reciprocalRank = result.reciprocalRank,
                firstGoldHitRank = if (index < 0) null else index + 1,
                precisionAtK = result.precisionAtK,
                recallAtK = result.recallAtK,
                rankedFiles = deduped
            )
        }
    }
}

/**
 * One side's per-question measurement, reached from the side rather than through a `when` at each
 * call site -- the single place that knows which field of a [RetrievalRunResult] a [RetrievalSide]
 * names. `null` means that side has no measurement for this question and never means zero.
 */
fun RetrievalRunResult.sideResult(side: RetrievalSide): SideResult? = when (side) {
    RetrievalSide.CONTEXT_GRAPH -> contextGraph
    RetrievalSide.CODE_GRAPH -> codeGraph
    RetrievalSide.BASH -> bash
    RetrievalSide.RIPGREP -> ripgrep
}
