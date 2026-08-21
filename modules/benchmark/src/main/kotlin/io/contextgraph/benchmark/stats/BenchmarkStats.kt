package io.contextgraph.benchmark.stats

import io.contextgraph.benchmark.judge.SetScorer
import io.contextgraph.benchmark.model.AgentRunRecord
import io.contextgraph.benchmark.model.AggregatedMetrics
import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.ArmSummary
import io.contextgraph.benchmark.model.BenchmarkRun
import io.contextgraph.benchmark.model.BenchmarkSummary
import io.contextgraph.benchmark.model.CategoryArmSummary
import io.contextgraph.benchmark.model.JudgeScore
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionArmSummary
import io.contextgraph.benchmark.model.QuestionCategory
import io.contextgraph.benchmark.model.RepoArmSummary
import io.contextgraph.benchmark.model.RepoBreakEven

/**
 * Builds a [BenchmarkSummary] from a [BenchmarkRun]'s raw records. Every
 * function here is pure — no I/O, no LLM call, no agent run — so this file
 * is fast and deterministic to unit test (task 06's notes require exactly
 * this).
 */
object BenchmarkStats {

    /** Categories folded into [BenchmarkSummary.headline]; AC-20 keeps [QuestionCategory.NEGATIVE_CONTROL] out. */
    private val HEADLINE_CATEGORIES = setOf(QuestionCategory.GRAPH_HEAVY, QuestionCategory.NEUTRAL)

    fun summarize(run: BenchmarkRun): BenchmarkSummary {
        val questionsById = run.questions.associateBy { it.id }
        val accuracyByRunId = accuracyLookup(run.judgeScores, run.config.models.judgeModel)

        val perQuestionArm = run.agentRuns
            .groupBy { it.questionId to it.arm }
            .mapNotNull { (key, runs) ->
                val (questionId, arm) = key
                val question = questionsById[questionId] ?: return@mapNotNull null
                QuestionArmSummary(
                    questionId = questionId,
                    repoId = question.repoId,
                    category = question.category,
                    arm = arm,
                    metrics = aggregate(runs, accuracyByRunId)
                )
            }
            .sortedWith(compareBy({ it.questionId }, { it.arm }))

        val perRepoArm = groupAndAggregate(run.agentRuns, questionsById, accuracyByRunId) { it.repoId }
            .map { (repoId, arm, metrics) -> RepoArmSummary(repoId, arm, metrics) }
            .sortedWith(compareBy({ it.repoId }, { it.arm }))

        val perCategoryArm = groupAndAggregate(run.agentRuns, questionsById, accuracyByRunId) { it.category }
            .map { (category, arm, metrics) -> CategoryArmSummary(category, arm, metrics) }
            .sortedWith(compareBy({ it.category }, { it.arm }))

        val headline = armSummaries(run.agentRuns, questionsById, accuracyByRunId) { it.category in HEADLINE_CATEGORIES }
        val negativeControl =
            armSummaries(run.agentRuns, questionsById, accuracyByRunId) { it.category == QuestionCategory.NEGATIVE_CONTROL }

        val breakEvenByRepo = run.ingestRecords.map { ingest ->
            val withCost = perRepoArm.firstOrNull { it.repoId == ingest.repoId && it.arm == Arm.WITH_TOOLS }?.metrics?.costUsd
            val withoutCost = perRepoArm.firstOrNull { it.repoId == ingest.repoId && it.arm == Arm.WITHOUT_TOOLS }?.metrics?.costUsd
            RepoBreakEven(
                repoId = ingest.repoId,
                result = computeBreakEven(ingest.costUsd, withCost, withoutCost)
            )
        }

        return BenchmarkSummary(
            perQuestionArm = perQuestionArm,
            perRepoArm = perRepoArm,
            perCategoryArm = perCategoryArm,
            headline = headline,
            negativeControl = negativeControl,
            breakEvenByRepo = breakEvenByRepo
        )
    }

    /** Groups [agentRuns] by (key(question), arm) and aggregates each group — the shared shape behind repo/category breakdowns. */
    private fun <K> groupAndAggregate(
        agentRuns: List<AgentRunRecord>,
        questionsById: Map<String, Question>,
        accuracyByRunId: Map<String, Double>,
        key: (Question) -> K
    ): List<Triple<K, Arm, AggregatedMetrics>> =
        agentRuns
            .mapNotNull { r -> questionsById[r.questionId]?.let { key(it) to r } }
            .groupBy({ (k, _) -> k }, { (_, r) -> r })
            .flatMap { (k, runsForKey) ->
                runsForKey.groupBy { it.arm }.map { (arm, runs) ->
                    Triple(k, arm, aggregate(runs, accuracyByRunId))
                }
            }

    private fun armSummaries(
        agentRuns: List<AgentRunRecord>,
        questionsById: Map<String, Question>,
        accuracyByRunId: Map<String, Double>,
        categoryFilter: (Question) -> Boolean
    ): List<ArmSummary> =
        agentRuns
            .filter { r -> questionsById[r.questionId]?.let(categoryFilter) == true }
            .groupBy { it.arm }
            .map { (arm, runs) -> ArmSummary(arm = arm, metrics = aggregate(runs, accuracyByRunId)) }
            .sortedBy { it.arm }

    /**
     * Only the run's configured primary judge model feeds aggregation; a kappa-validation second
     * judge stays out of the headline numbers.
     *
     * [SetScorer.SCORER_NAME] counts as primary too. A set question is not scored by a model at
     * all -- [SetScorer] compares the returned set against mechanically derived ground truth and
     * stamps its own name into `judgeModel` -- so a filter that admits only the configured judge
     * model dropped every set-question score on the floor. The whole run then reported "Accuracy:
     * no data" while the scores sat in its own JSON, which reads as "the instrument found nothing"
     * rather than "the instrument's output was filtered out". The exclusion this filter exists for
     * is a *second opinion on the same answer*, and a deterministic scorer is never that.
     */
    private fun accuracyLookup(judgeScores: List<JudgeScore>, primaryJudgeModel: String): Map<String, Double> =
        judgeScores.filter { it.judgeModel == primaryJudgeModel || it.judgeModel == SetScorer.SCORER_NAME }
            .associate { it.runId to it.accuracyScore }

    private fun aggregate(runs: List<AgentRunRecord>, accuracyByRunId: Map<String, Double>): AggregatedMetrics {
        val totalRuns = runs.size
        val ceilingHitRuns = runs.count { it.hitCeiling }
        val included = runs.filterNot { it.contaminated }

        return AggregatedMetrics(
            totalRuns = totalRuns,
            includedRuns = included.size,
            excludedContaminatedRuns = totalRuns - included.size,
            ceilingHitRuns = ceilingHitRuns,
            inputTokens = summarizeMetric(included.map { it.inputTokens.toDouble() }),
            outputTokens = summarizeMetric(included.map { it.outputTokens.toDouble() }),
            totalTokens = summarizeMetric(included.map { (it.inputTokens + it.outputTokens).toDouble() }),
            toolCallCount = summarizeMetric(included.map { it.toolCallCount.toDouble() }),
            fileReadCount = summarizeMetric(included.map { it.fileReadCount.toDouble() }),
            wallClockMillis = summarizeMetric(included.map { it.wallClockMillis.toDouble() }),
            // Task 20: costUsd is null ("not recorded"), not 0.0, for a run whose model has no
            // ModelPricing entry -- mapNotNull excludes those runs from this metric entirely
            // rather than counting them as free, matching summarizeMetric's own "empty group ->
            // null summary" contract used everywhere else a metric can be legitimately absent.
            costUsd = summarizeMetric(included.mapNotNull { it.costUsd }),
            accuracyScore = summarizeMetric(included.mapNotNull { accuracyByRunId[it.id] })
        )
    }
}
