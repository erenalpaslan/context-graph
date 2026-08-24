package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory

/**
 * Pure aggregation over already-scored [RetrievalRunResult]s -- no I/O, no new metric math (that
 * belongs to [RetrievalMetrics]), just means and grouping. Mirrors
 * [io.contextgraph.benchmark.stats.BenchmarkStats]'s role for the agent-A/B axis: the numbers
 * this produces are exactly what [RetrievalReportGenerator] prints, nothing recomputed there.
 */
object RetrievalStats {

    fun summarize(results: List<RetrievalRunResult>, kValues: List<Int>): RetrievalSummary {
        val headlineResults = results.filter { it.category != QuestionCategory.NEGATIVE_CONTROL }
        val negativeControlResults = results.filter { it.category == QuestionCategory.NEGATIVE_CONTROL }

        return RetrievalSummary(
            headline = aggregate(headlineResults, kValues),
            negativeControl = aggregate(negativeControlResults, kValues),
            byCategory = QuestionCategory.entries.associateWith { category ->
                aggregate(results.filter { it.category == category }, kValues)
            },
            byRepo = results.map { it.repoId }.distinct().sorted().associateWith { repoId ->
                aggregate(results.filter { it.repoId == repoId }, kValues)
            }
        )
    }

    private fun aggregate(results: List<RetrievalRunResult>, kValues: List<Int>): RetrievalAggregate =
        RetrievalAggregate(
            questionCount = results.size,
            contextGraph = sideAggregate(results.mapNotNull { it.contextGraph }, kValues),
            ripgrep = sideAggregate(results.map { it.ripgrep }, kValues),
            codeGraph = aggregateIfMeasured(results.mapNotNull { it.codeGraph }, kValues),
            // The bash side takes the same rule, and takes it as the rule rather than as a
            // special case: it reads the working tree and so is never blocked by an index, but a
            // run predating the fourth side -- or one whose every `grep` invocation failed --
            // still measured no bash side.
            bash = aggregateIfMeasured(results.mapNotNull { it.bash }, kValues)
        )

    /**
     * `null`, not a zeroed [SideAggregate], when a side was measured nowhere in the group.
     *
     * "This side was not in this run" and "this side was in this run and scored 0" are different
     * claims, and only the second is evidence about the tool. The zeroed aggregate [sideAggregate]
     * returns for an empty list is the right answer only for the two sides whose per-question
     * result is non-null by type; for every nullable side it would publish an unearned win over a
     * comparator that never ran. Same distinction `SideAggregate`'s KDoc draws between an excluded
     * question and a zero-scoring one, one level up.
     */
    private fun aggregateIfMeasured(sides: List<SideResult>, kValues: List<Int>): SideAggregate? =
        sides.takeIf { it.isNotEmpty() }?.let { sideAggregate(it, kValues) }

    private fun sideAggregate(sides: List<SideResult>, kValues: List<Int>): SideAggregate {
        if (sides.isEmpty()) {
            return SideAggregate(
                measuredCount = 0,
                meanPrecisionAtK = kValues.associateWith { 0.0 },
                meanRecallAtK = kValues.associateWith { 0.0 },
                mrr = 0.0
            )
        }
        return SideAggregate(
            measuredCount = sides.size,
            meanPrecisionAtK = kValues.associateWith { k -> sides.map { it.precisionAtK.getValue(k) }.average() },
            meanRecallAtK = kValues.associateWith { k -> sides.map { it.recallAtK.getValue(k) }.average() },
            mrr = sides.map { it.reciprocalRank }.average()
        )
    }
}
