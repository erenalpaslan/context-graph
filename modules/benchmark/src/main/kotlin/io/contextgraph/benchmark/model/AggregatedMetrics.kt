package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * One group's aggregated metrics — the unit every breakdown in
 * [BenchmarkSummary] is built from, whether the group is a single
 * (question, arm) pair ([QuestionArmSummary]), a whole repo
 * ([RepoArmSummary]), a whole category ([CategoryArmSummary]), or the run's
 * headline/negative-control split ([ArmSummary]). Each [MetricSummary] field
 * is null only when the group has zero included data points for that metric
 * — e.g. [accuracyScore] before any [JudgeScore] has been recorded.
 *
 * [totalRuns] counts every [AgentRunRecord] matching the group, contaminated
 * or not. [excludedContaminatedRuns] is that count restricted to
 * `contaminated == true` — AC-9 requires this count stay visible in the
 * summary rather than being silently dropped. [includedRuns] is
 * `totalRuns - excludedContaminatedRuns`: the sample size the
 * [MetricSummary] fields are computed from (except [accuracyScore], whose
 * sample size can be smaller still if an included run has no matching
 * primary-judge [JudgeScore] yet).
 *
 * [ceilingHitRuns] counts `hitCeiling == true` runs across *all* [totalRuns],
 * including contaminated ones (AC-10a). A ceiling hit is an operational fact
 * about the run environment, independent of the contamination exclusion;
 * computing it only over the already-filtered [includedRuns] population
 * would let a run that is both contaminated and ceiling-hit silently
 * disappear from the ceiling count.
 */
@Serializable
data class AggregatedMetrics(
    val totalRuns: Int,
    val includedRuns: Int,
    val excludedContaminatedRuns: Int,
    val ceilingHitRuns: Int,
    val inputTokens: MetricSummary?,
    val outputTokens: MetricSummary?,
    val totalTokens: MetricSummary?,
    val toolCallCount: MetricSummary?,
    val fileReadCount: MetricSummary?,
    val wallClockMillis: MetricSummary?,
    val costUsd: MetricSummary?,
    val accuracyScore: MetricSummary?
)
