package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * A whole-run, single-category-group breakdown per [arm]. Used for
 * [BenchmarkSummary.headline] (pools [QuestionCategory.GRAPH_HEAVY] and
 * [QuestionCategory.NEUTRAL] questions, across all repos) and
 * [BenchmarkSummary.negativeControl] (pools [QuestionCategory.NEGATIVE_CONTROL]
 * questions only) — AC-20 requires these never mix.
 */
@Serializable
data class ArmSummary(
    val arm: Arm,
    val metrics: AggregatedMetrics
)
