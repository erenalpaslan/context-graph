package io.contextgraph.benchmark.stats

import io.contextgraph.benchmark.judge.SetScorer
import io.contextgraph.benchmark.model.AgentRunRecord
import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.BenchmarkConfig
import io.contextgraph.benchmark.model.BenchmarkRun
import io.contextgraph.benchmark.model.BreakEvenResult
import io.contextgraph.benchmark.model.Evidence
import io.contextgraph.benchmark.model.FactScore
import io.contextgraph.benchmark.model.GoldFact
import io.contextgraph.benchmark.model.IngestRecord
import io.contextgraph.benchmark.model.JudgeScore
import io.contextgraph.benchmark.model.ModelConfig
import io.contextgraph.benchmark.model.Profile
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.contextgraph.core.ContextGraphConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.Instant

/**
 * Known-input/known-output coverage for [BenchmarkStats.summarize]: exclusion
 * accounting, ceiling counts, repo/category breakdowns, the headline vs.
 * negative-control split (AC-20), and break-even integration (AC-21), all
 * against hand-computed expectations.
 *
 * Fixture shape:
 * - repoA / q1 (GRAPH_HEAVY): WITH_TOOLS has 4 repeats — one contaminated
 *   (excluded from the median but still counted), one hits the ceiling
 *   without being contaminated, and the contaminated one *also* hits the
 *   ceiling (proves ceiling counts survive contamination exclusion).
 *   WITHOUT_TOOLS has 4 clean repeats.
 * - repoA / q2 (NEGATIVE_CONTROL): both arms have 4 clean repeats, and
 *   WITHOUT_TOOLS is *cheaper* than WITH_TOOLS — the case where
 *   ContextGraph loses, which must still surface.
 * - repoB / q3 (NEUTRAL): WITH_TOOLS only, 2 clean repeats — exercises the
 *   headline pooling GRAPH_HEAVY and NEUTRAL across repos.
 * - repoC: an [IngestRecord] with no matching agent runs at all — exercises
 *   [BreakEvenResult.InsufficientData] end to end.
 */
private fun run(agentRuns: List<AgentRunRecord>, judgeScores: List<JudgeScore>): BenchmarkRun {
    val fact = GoldFact(id = "f1", statement = "irrelevant to this test", evidence = Evidence("src/Foo.kt", 1))
    val questions = listOf(
        Question("q1", "repoA", "graph-heavy question", QuestionCategory.GRAPH_HEAVY, listOf(fact)),
        Question("q2", "repoA", "negative control question", QuestionCategory.NEGATIVE_CONTROL, listOf(fact)),
        Question("q3", "repoB", "neutral question", QuestionCategory.NEUTRAL, listOf(fact))
    )
    val ingestRecords = listOf(
        IngestRecord("repoA", durationMillis = 1000, tokensUsed = 100, costUsd = 10.0, config = ContextGraphConfig()),
        IngestRecord("repoC", durationMillis = 1000, tokensUsed = 100, costUsd = 7.0, config = ContextGraphConfig())
    )
    return BenchmarkRun(
        runId = "test-run",
        profile = Profile.SMOKE,
        generatedAt = Instant.parse("2026-08-17T00:00:00Z"),
        config = BenchmarkConfig(models = ModelConfig(judgeModel = "claude-opus-5")),
        questions = questions,
        ingestRecords = ingestRecords,
        agentRuns = agentRuns,
        judgeScores = judgeScores
    )
}

private fun agentRun(
    id: String,
    questionId: String,
    arm: Arm,
    repeatIndex: Int,
    tokens: Long,
    toolCalls: Int,
    costUsd: Double,
    hitCeiling: Boolean = false,
    contaminated: Boolean = false
) = AgentRunRecord(
    id = id,
    questionId = questionId,
    arm = arm,
    repeatIndex = repeatIndex,
    inputTokens = tokens,
    outputTokens = tokens / 2,
    toolCallCount = toolCalls,
    fileReadCount = toolCalls / 2,
    wallClockMillis = tokens * 10,
    costUsd = costUsd,
    finalAnswer = "answer for $id",
    hitCeiling = hitCeiling,
    contaminated = contaminated,
    cliInvocationAttempts = 0
)

class BenchmarkStatsTest : FunSpec({

    // repoA / q1 / WITH_TOOLS: 3 clean repeats + 1 contaminated repeat that also hits the ceiling.
    val q1With = listOf(
        agentRun("q1-with-0", "q1", Arm.WITH_TOOLS, 0, tokens = 100, toolCalls = 5, costUsd = 1.0),
        agentRun("q1-with-1", "q1", Arm.WITH_TOOLS, 1, tokens = 200, toolCalls = 6, costUsd = 2.0),
        agentRun("q1-with-2", "q1", Arm.WITH_TOOLS, 2, tokens = 300, toolCalls = 7, costUsd = 3.0, hitCeiling = true),
        agentRun(
            "q1-with-3", "q1", Arm.WITH_TOOLS, 3,
            tokens = 9999, toolCalls = 40, costUsd = 100.0, hitCeiling = true, contaminated = true
        )
    )
    // repoA / q1 / WITHOUT_TOOLS: 4 clean repeats, even sample size.
    val q1Without = listOf(
        agentRun("q1-without-0", "q1", Arm.WITHOUT_TOOLS, 0, tokens = 1000, toolCalls = 30, costUsd = 10.0),
        agentRun("q1-without-1", "q1", Arm.WITHOUT_TOOLS, 1, tokens = 2000, toolCalls = 32, costUsd = 20.0),
        agentRun("q1-without-2", "q1", Arm.WITHOUT_TOOLS, 2, tokens = 3000, toolCalls = 34, costUsd = 30.0),
        agentRun("q1-without-3", "q1", Arm.WITHOUT_TOOLS, 3, tokens = 4000, toolCalls = 36, costUsd = 40.0)
    )
    // repoA / q2 (negative control) / WITH_TOOLS: 4 identical clean repeats.
    val q2With = (0..3).map {
        agentRun("q2-with-$it", "q2", Arm.WITH_TOOLS, it, tokens = 50, toolCalls = 2, costUsd = 0.5)
    }
    // repoA / q2 (negative control) / WITHOUT_TOOLS: cheaper than WITH_TOOLS — ContextGraph loses here.
    val q2Without = (0..3).map {
        agentRun("q2-without-$it", "q2", Arm.WITHOUT_TOOLS, it, tokens = 20, toolCalls = 1, costUsd = 0.2)
    }
    // repoB / q3 (neutral) / WITH_TOOLS only, 2 clean repeats.
    val q3With = listOf(
        agentRun("q3-with-0", "q3", Arm.WITH_TOOLS, 0, tokens = 10, toolCalls = 1, costUsd = 0.1),
        agentRun("q3-with-1", "q3", Arm.WITH_TOOLS, 1, tokens = 10, toolCalls = 1, costUsd = 0.1)
    )

    val allRuns = q1With + q1Without + q2With + q2Without + q3With

    // Judge scores from the primary judge for every *included* run, plus one from a
    // second (kappa-validation) judge model that must not leak into aggregation.
    val judgeScores = listOf(
        JudgeScore("js-1", "q1-with-0", "claude-opus-5", listOf(FactScore("f1", true)), accuracyScore = 1.0),
        JudgeScore("js-2", "q1-with-1", "claude-opus-5", listOf(FactScore("f1", true)), accuracyScore = 1.0),
        JudgeScore("js-3", "q1-with-2", "claude-opus-5", listOf(FactScore("f1", false)), accuracyScore = 0.5),
        JudgeScore("js-secondary", "q1-with-0", "claude-sonnet-5-validator", listOf(FactScore("f1", false)), accuracyScore = 0.0),
        // repoB / q3 is scored by the deterministic set scorer, not by a model. Its judgeModel is
        // never the configured one, so it exercises the case where filtering "everything that
        // isn't the primary judge" silently discards a real score.
        JudgeScore("js-set-0", "q3-with-0", SetScorer.SCORER_NAME, emptyList(), accuracyScore = 0.8),
        JudgeScore("js-set-1", "q3-with-1", SetScorer.SCORER_NAME, emptyList(), accuracyScore = 0.6)
    )

    val summary = BenchmarkStats.summarize(run(allRuns, judgeScores))

    test("per-(question, arm) median/variance matches hand-computed values, excluding the contaminated repeat") {
        val q1WithSummary = summary.perQuestionArm.first { it.questionId == "q1" && it.arm == Arm.WITH_TOOLS }
        q1WithSummary.repoId shouldBe "repoA"
        q1WithSummary.category shouldBe QuestionCategory.GRAPH_HEAVY
        q1WithSummary.metrics.totalRuns shouldBe 4
        q1WithSummary.metrics.includedRuns shouldBe 3
        q1WithSummary.metrics.excludedContaminatedRuns shouldBe 1
        q1WithSummary.metrics.costUsd!!.median shouldBe 2.0 // sorted [1,2,3], odd -> middle
        q1WithSummary.metrics.costUsd!!.min shouldBe 1.0
        q1WithSummary.metrics.costUsd!!.max shouldBe 3.0
        q1WithSummary.metrics.toolCallCount!!.median shouldBe 6.0
    }

    test("ceiling-hit count includes the contaminated run that also hit the ceiling") {
        val q1WithSummary = summary.perQuestionArm.first { it.questionId == "q1" && it.arm == Arm.WITH_TOOLS }
        q1WithSummary.metrics.ceilingHitRuns shouldBe 2 // repeat 2 (clean) and repeat 3 (contaminated)
    }

    test("even sample size (4 clean WITHOUT_TOOLS repeats) uses the average-of-two-middle-values median") {
        val q1WithoutSummary = summary.perQuestionArm.first { it.questionId == "q1" && it.arm == Arm.WITHOUT_TOOLS }
        q1WithoutSummary.metrics.costUsd!!.median shouldBe 25.0 // (20 + 30) / 2
        q1WithoutSummary.metrics.excludedContaminatedRuns shouldBe 0
        q1WithoutSummary.metrics.ceilingHitRuns shouldBe 0
    }

    test("accuracy score is aggregated only from the configured primary judge model") {
        val q1WithSummary = summary.perQuestionArm.first { it.questionId == "q1" && it.arm == Arm.WITH_TOOLS }
        // 3 included runs, all scored by the primary judge -> sample size 3, not 4 (secondary judge excluded).
        q1WithSummary.metrics.accuracyScore!!.sampleSize shouldBe 3
        q1WithSummary.metrics.accuracyScore!!.median shouldBe 1.0 // sorted [0.5, 1.0, 1.0]
    }

    test("a set question's deterministic scorer counts as primary, not as a second opinion to discard") {
        val q3WithSummary = summary.perQuestionArm.first { it.questionId == "q3" && it.arm == Arm.WITH_TOOLS }
        // Both runs were scored by SetScorer under a judgeModel that is not the configured one.
        // Before the fix this was null -- "Accuracy: no data" on a run whose scores were on disk.
        q3WithSummary.metrics.accuracyScore!!.sampleSize shouldBe 2
        q3WithSummary.metrics.accuracyScore!!.median shouldBe (0.7 plusOrMinus 1e-9) // (0.6 + 0.8) / 2
    }

    test("repo-level breakdown pools every question in that repo, not a median of medians") {
        val repoAWith = summary.perRepoArm.first { it.repoId == "repoA" && it.arm == Arm.WITH_TOOLS }
        // included costUsd values pooled: q1 [1,2,3] + q2 [0.5,0.5,0.5,0.5] -> sorted [0.5,0.5,0.5,0.5,1,2,3], n=7
        repoAWith.metrics.includedRuns shouldBe 7
        repoAWith.metrics.totalRuns shouldBe 8
        repoAWith.metrics.excludedContaminatedRuns shouldBe 1
        repoAWith.metrics.costUsd!!.median shouldBe 0.5

        val repoAWithout = summary.perRepoArm.first { it.repoId == "repoA" && it.arm == Arm.WITHOUT_TOOLS }
        // pooled: q1 [10,20,30,40] + q2 [0.2,0.2,0.2,0.2] -> sorted 8 values, median of index3/4 = (0.2+10)/2
        repoAWithout.metrics.costUsd!!.median shouldBe (5.1 plusOrMinus 1e-9)
    }

    test("category-level breakdown pools every question of that category across repos") {
        val negativeControlWith = summary.perCategoryArm.first {
            it.category == QuestionCategory.NEGATIVE_CONTROL && it.arm == Arm.WITH_TOOLS
        }
        negativeControlWith.metrics.costUsd!!.median shouldBe 0.5
        negativeControlWith.metrics.includedRuns shouldBe 4
    }

    test("headline pools GRAPH_HEAVY and NEUTRAL across repos and excludes NEGATIVE_CONTROL") {
        val headlineWith = summary.headline.first { it.arm == Arm.WITH_TOOLS }
        // q1 included [1,2,3] + q3 [0.1,0.1] -> sorted [0.1,0.1,1,2,3], n=5, median = middle (index 2) = 1.0
        headlineWith.metrics.includedRuns shouldBe 5
        headlineWith.metrics.costUsd!!.median shouldBe 1.0

        val headlineWithout = summary.headline.first { it.arm == Arm.WITHOUT_TOOLS }
        // only q1 has WITHOUT_TOOLS data among headline categories (q3 has none)
        headlineWithout.metrics.includedRuns shouldBe 4
        headlineWithout.metrics.costUsd!!.median shouldBe 25.0
    }

    test("negative control results are reported separately and are not folded into the headline, including where ContextGraph loses") {
        val negativeWith = summary.negativeControl.first { it.arm == Arm.WITH_TOOLS }
        val negativeWithout = summary.negativeControl.first { it.arm == Arm.WITHOUT_TOOLS }
        negativeWith.metrics.costUsd!!.median shouldBe 0.5
        negativeWithout.metrics.costUsd!!.median shouldBe 0.2
        // WITHOUT_TOOLS is cheaper here -- ContextGraph loses this category, and it must still be visible.
        (negativeWithout.metrics.costUsd!!.median < negativeWith.metrics.costUsd!!.median) shouldBe true

        summary.headline.shouldHaveSize(2)
        summary.negativeControl.shouldHaveSize(2)
    }

    test("break-even for repoA amortizes using pooled repo-level median costs") {
        val repoABreakEven = summary.breakEvenByRepo.first { it.repoId == "repoA" }.result
        val amortizes = repoABreakEven.shouldBeInstanceOf<BreakEvenResult.Amortizes>()
        amortizes.ingestCostUsd shouldBe 10.0
        amortizes.savingPerQuestionUsd shouldBe (4.6 plusOrMinus 1e-9) // 5.1 - 0.5
        amortizes.questionsToBreakEven shouldBe ((10.0 / 4.6) plusOrMinus 1e-9)
    }

    test("break-even for a repo with an ingest record but no agent runs is InsufficientData, not a crash") {
        val repoCBreakEven = summary.breakEvenByRepo.first { it.repoId == "repoC" }.result
        repoCBreakEven.shouldBeInstanceOf<BreakEvenResult.InsufficientData>()
    }

    test("breakEvenByRepo is driven by ingestRecords, not by which repos have agent runs") {
        // repoB has agent runs but no IngestRecord in this fixture, so it must not appear.
        summary.breakEvenByRepo.map { it.repoId } shouldBe listOf("repoA", "repoC")
    }

    // ------------------------------------------------------------- task 20: costUsd = null

    test("a null costUsd (task 20: no ModelPricing entry for the run's model) is excluded from cost aggregation, not treated as a real zero") {
        val runsWithUnpricedCost = listOf(
            agentRun("u1", "q1", Arm.WITH_TOOLS, 0, tokens = 100, toolCalls = 1, costUsd = 2.0).copy(costUsd = null),
            agentRun("u2", "q1", Arm.WITH_TOOLS, 1, tokens = 100, toolCalls = 1, costUsd = 2.0).copy(costUsd = null),
            agentRun("u3", "q1", Arm.WITH_TOOLS, 2, tokens = 100, toolCalls = 1, costUsd = 2.0) // one real cost, in case any leaked in
        )
        val unpricedRun = run(runsWithUnpricedCost, judgeScores = emptyList())
        val unpricedSummary = BenchmarkStats.summarize(unpricedRun)

        val q1With = unpricedSummary.perQuestionArm.first { it.questionId == "q1" && it.arm == Arm.WITH_TOOLS }
        // Only u3's real 2.0 feeds the metric -- the two nulls are dropped entirely, not folded
        // in as 0.0 (which would have pulled the median down to 0.0 for a 3-sample group).
        q1With.metrics.costUsd?.sampleSize shouldBe 1
        q1With.metrics.costUsd?.median shouldBe 2.0
        // includedRuns/totalRuns still count every run -- costUsd being unrecorded doesn't hide the run itself.
        q1With.metrics.totalRuns shouldBe 3
        q1With.metrics.includedRuns shouldBe 3
    }

    test("costUsd summary is null (not a zero-median summary) when every run in a group has no recorded cost") {
        val allUnpriced = listOf(
            agentRun("u1", "q1", Arm.WITH_TOOLS, 0, tokens = 100, toolCalls = 1, costUsd = 2.0).copy(costUsd = null),
            agentRun("u2", "q1", Arm.WITH_TOOLS, 1, tokens = 100, toolCalls = 1, costUsd = 2.0).copy(costUsd = null)
        )
        val unpricedRun = run(allUnpriced, judgeScores = emptyList())
        val unpricedSummary = BenchmarkStats.summarize(unpricedRun)

        val q1With = unpricedSummary.perQuestionArm.first { it.questionId == "q1" && it.arm == Arm.WITH_TOOLS }
        q1With.metrics.costUsd shouldBe null
    }
})
