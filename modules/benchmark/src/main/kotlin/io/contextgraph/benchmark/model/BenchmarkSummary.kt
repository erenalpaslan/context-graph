package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * Slice 06's output: the structured summary slice 07 formats into
 * `BENCHMARKS.md`. Produced by
 * `io.contextgraph.benchmark.stats.BenchmarkStats.summarize` from a
 * [BenchmarkRun]'s raw [AgentRunRecord]/[JudgeScore] lists — this type only
 * carries shape, the same convention as [Question]/[GoldFact]/[Evidence].
 *
 * [headline] and [negativeControl] are deliberately split (AC-20): the
 * former pools [QuestionCategory.GRAPH_HEAVY] and [QuestionCategory.NEUTRAL]
 * questions only, one [ArmSummary] per [Arm]; the latter pools
 * [QuestionCategory.NEGATIVE_CONTROL] questions the same way. Negative
 * control results never fold into [headline] — including the cases where
 * ContextGraph loses, which the report is required to show, not hide.
 */
@Serializable
data class BenchmarkSummary(
    val perQuestionArm: List<QuestionArmSummary> = emptyList(),
    val perRepoArm: List<RepoArmSummary> = emptyList(),
    val perCategoryArm: List<CategoryArmSummary> = emptyList(),
    val headline: List<ArmSummary> = emptyList(),
    val negativeControl: List<ArmSummary> = emptyList(),
    val breakEvenByRepo: List<RepoBreakEven> = emptyList()
)
