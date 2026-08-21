package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * The base aggregation unit (AC-15): one (question, arm) pair, summarized
 * across its repeats. [repoId] and [category] are carried alongside the
 * metrics — denormalized from the owning [Question] — so a reader of
 * [BenchmarkSummary.perQuestionArm] doesn't have to join back against
 * [BenchmarkRun.questions] to know which repo or category a row belongs to.
 */
@Serializable
data class QuestionArmSummary(
    val questionId: String,
    val repoId: String,
    val category: QuestionCategory,
    val arm: Arm,
    val metrics: AggregatedMetrics
)
