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
            // Null, not a zeroed aggregate, when this run measured no CodeGraph side anywhere in
            // the group: "CodeGraph was not in this run" and "CodeGraph was in this run and scored
            // 0" are different claims, and only the second is evidence about CodeGraph. The same
            // distinction `SideAggregate`'s KDoc already draws between an excluded question and a
            // zero-scoring one, one level up.
            codeGraph = results.mapNotNull { it.codeGraph }
                .takeIf { it.isNotEmpty() }
                ?.let { sideAggregate(it, kValues) }
        )

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
