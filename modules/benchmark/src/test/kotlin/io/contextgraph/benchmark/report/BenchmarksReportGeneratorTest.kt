package io.contextgraph.benchmark.report

import io.contextgraph.benchmark.judge.KappaValidationResult
import io.contextgraph.benchmark.model.AgentClientKind
import io.contextgraph.benchmark.model.AgentRunRecord
import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.BenchmarkConfig
import io.contextgraph.benchmark.model.BenchmarkRun
import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.Evidence
import io.contextgraph.benchmark.model.FactScore
import io.contextgraph.benchmark.model.GoldFact
import io.contextgraph.benchmark.model.IngestRecord
import io.contextgraph.benchmark.model.JudgeScore
import io.contextgraph.benchmark.model.ModelConfig
import io.contextgraph.benchmark.model.Profile
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.contextgraph.benchmark.stats.BenchmarkStats
import io.contextgraph.core.ContextGraphConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.Instant

/**
 * Coverage for [BenchmarksReportGenerator]: determinism (same input twice ->
 * identical document), every required section present, negative controls
 * kept out of the headline (AC-20) including a case where ContextGraph
 * loses, break-even's three [io.contextgraph.benchmark.model.BreakEvenResult]
 * cases rendered distinctly, and missing data (no summary yet, an unrun
 * repo, no kappa validation) stated explicitly rather than crashing or
 * going silent.
 */
class BenchmarksReportGeneratorTest : FunSpec({

    fun fact(id: String) = GoldFact(id = id, statement = "irrelevant to this test", evidence = Evidence("src/Foo.kt", 1))

    fun agentRun(
        id: String,
        questionId: String,
        arm: Arm,
        inputTokens: Long,
        outputTokens: Long,
        toolCalls: Int,
        fileReads: Int,
        wallClockMillis: Long,
        costUsd: Double,
        hitCeiling: Boolean = false,
        contaminated: Boolean = false,
        cliInvocationAttempts: Int = 0
    ) = AgentRunRecord(
        id = id,
        questionId = questionId,
        arm = arm,
        repeatIndex = 0,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        toolCallCount = toolCalls,
        fileReadCount = fileReads,
        wallClockMillis = wallClockMillis,
        costUsd = costUsd,
        finalAnswer = "answer for $id",
        hitCeiling = hitCeiling,
        contaminated = contaminated,
        cliInvocationAttempts = cliInvocationAttempts
    )

    /**
     * repoA/q1 (GRAPH_HEAVY) and repoA/q2 (NEGATIVE_CONTROL), one repeat per
     * (question, arm) so every [io.contextgraph.benchmark.model.MetricSummary]
     * has median == min == max, keeping expected values simple to hand-check.
     * q2's WITHOUT_TOOLS arm scores higher accuracy than WITH_TOOLS -- the
     * "ContextGraph loses" case the negative-control section must surface.
     * repoB has an [IngestRecord] and a [CorpusRepo] entry but zero agent
     * runs, exercising the "not run yet" path for a whole repo.
     */
    fun sampleRun(): BenchmarkRun {
        val questions = listOf(
            Question("q1", "repoA", "graph-heavy question", QuestionCategory.GRAPH_HEAVY, listOf(fact("f1"))),
            Question("q2", "repoA", "negative control question", QuestionCategory.NEGATIVE_CONTROL, listOf(fact("f2")))
        )
        val agentRuns = listOf(
            agentRun("q1-with", "q1", Arm.WITH_TOOLS, inputTokens = 1000, outputTokens = 500, toolCalls = 10, fileReads = 5, wallClockMillis = 30_000, costUsd = 0.5),
            agentRun(
                "q1-without", "q1", Arm.WITHOUT_TOOLS, inputTokens = 5000, outputTokens = 2000, toolCalls = 25,
                fileReads = 20, wallClockMillis = 90_000, costUsd = 2.0, hitCeiling = true, cliInvocationAttempts = 1
            ),
            agentRun("q2-with", "q2", Arm.WITH_TOOLS, inputTokens = 200, outputTokens = 100, toolCalls = 3, fileReads = 1, wallClockMillis = 5_000, costUsd = 0.1),
            agentRun("q2-without", "q2", Arm.WITHOUT_TOOLS, inputTokens = 100, outputTokens = 50, toolCalls = 1, fileReads = 1, wallClockMillis = 1_000, costUsd = 0.02)
        )
        val judgeScores = listOf(
            JudgeScore("js1", "q1-with", "claude-opus-5", listOf(FactScore("f1", true)), accuracyScore = 1.0),
            JudgeScore("js2", "q1-without", "claude-opus-5", listOf(FactScore("f1", false)), accuracyScore = 0.5),
            JudgeScore("js3", "q2-with", "claude-opus-5", listOf(FactScore("f2", false)), accuracyScore = 0.6),
            JudgeScore("js4", "q2-without", "claude-opus-5", listOf(FactScore("f2", true)), accuracyScore = 1.0)
        )
        val ingestRecords = listOf(
            IngestRecord("repoA", durationMillis = 600_000, tokensUsed = 50_000, costUsd = 1.0, config = ContextGraphConfig()),
            IngestRecord("repoB", durationMillis = 100_000, tokensUsed = 10_000, costUsd = 0.5, config = ContextGraphConfig())
        )
        val corpusRepos = listOf(
            CorpusRepo(
                id = "repoA", name = "Excalidraw", url = "https://github.com/excalidraw/excalidraw",
                pinnedTag = "v1.2.3", pinnedSha = "abcdef1234567890"
            ),
            CorpusRepo(
                id = "repoB", name = "gin", url = "https://github.com/gin-gonic/gin",
                pinnedTag = "v1.9.0", pinnedSha = "0123456789abcdef"
            )
        )
        val run = BenchmarkRun(
            runId = "golden-run",
            profile = Profile.SMOKE,
            generatedAt = Instant.parse("2026-08-17T12:00:00Z"),
            config = BenchmarkConfig(
                toolCallCeiling = 40,
                repeatsPerArm = 1,
                models = ModelConfig(agentModel = "claude-sonnet-5", judgeModel = "claude-opus-5")
            ),
            corpusRepos = corpusRepos,
            questions = questions,
            ingestRecords = ingestRecords,
            agentRuns = agentRuns,
            judgeScores = judgeScores
        )
        return run.copy(summary = BenchmarkStats.summarize(run))
    }

    test("determinism: the same BenchmarkRun JSON generates byte-identical documents both times") {
        val original = sampleRun()
        val json = original.toJson()
        val first = BenchmarksReportGenerator.generate(BenchmarkRun.fromJson(json))
        val second = BenchmarksReportGenerator.generate(BenchmarkRun.fromJson(json))
        first shouldBe second
    }

    test("determinism holds with failedRunCount set, same as with it absent") {
        val withFailures = sampleRun().copy(failedRunCount = 3)
        val json = withFailures.toJson()
        val first = BenchmarksReportGenerator.generate(BenchmarkRun.fromJson(json))
        val second = BenchmarksReportGenerator.generate(BenchmarkRun.fromJson(json))
        first shouldBe second
    }

    test("determinism holds with agentClientKind set (SYNTHETIC), same as with it absent") {
        val withKind = sampleRun().copy(agentClientKind = AgentClientKind.SYNTHETIC)
        val json = withKind.toJson()
        val first = BenchmarksReportGenerator.generate(BenchmarkRun.fromJson(json))
        val second = BenchmarksReportGenerator.generate(BenchmarkRun.fromJson(json))
        first shouldBe second
    }

    test("every required section is present") {
        val doc = BenchmarksReportGenerator.generate(sampleRun())
        doc shouldContain "# BENCHMARKS"
        doc shouldContain "## Methodology"
        doc shouldContain "## Headline Results"
        doc shouldContain "## Per-Repo Results"
        doc shouldContain "## Negative Controls"
        doc shouldContain "## Contamination Report"
        doc shouldContain "## Tool-Call Ceiling Report"
        doc shouldContain "## Judge Validation"
        doc shouldContain "## Ingest Cost & Break-Even"
        doc shouldContain "## Reproduction"
    }

    test("methodology states the control arm is a separate clean checkout, not tools switched off") {
        val doc = BenchmarksReportGenerator.generate(sampleRun())
        doc shouldContain "separate, independently checked-out working copy"
        doc shouldContain "never touched"
        doc shouldContain "root/.contextgraph/graph.db"
        doc shouldContain "is **not** the same agent with tools switched off"
    }

    test("methodology records agent/judge model, ceiling and repeat count from config") {
        val doc = BenchmarksReportGenerator.generate(sampleRun())
        doc shouldContain "`claude-sonnet-5`"
        doc shouldContain "`claude-opus-5`"
        doc shouldContain "| Tool-call ceiling | 40 (same in both arms) |"
        doc shouldContain "| Repeats per (question, arm) | 1 |"
    }

    test("headline pools only GRAPH_HEAVY/NEUTRAL and shows median with min/max") {
        val doc = BenchmarksReportGenerator.generate(sampleRun())
        val headlineSection = doc.substringAfter("## Headline Results").substringBefore("## Per-Repo Results")
        // q1 (GRAPH_HEAVY) is the only headline-category question, single repeat -> median == min == max, n=1.
        headlineSection shouldContain "$0.5000 (min $0.5000, max $0.5000, n=1)"
        headlineSection shouldContain "100.0% (min 100.0%, max 100.0%, n=1)"
    }

    test("per-repo table shows pinned tag and SHA for a measured repo, and states repoB has not run yet") {
        val doc = BenchmarksReportGenerator.generate(sampleRun())
        doc shouldContain "### Excalidraw (`repoA`)"
        doc shouldContain "Measured at tag `v1.2.3`, commit `abcdef1234567890`."
        doc shouldContain "### gin (`repoB`)"
        doc shouldContain "Measured at tag `v1.9.0`, commit `0123456789abcdef`."
        val repoBSection = doc.substringAfter("### gin (`repoB`)").substringBefore("## Negative Controls")
        repoBSection shouldContain "_Not run yet -- no agent runs recorded for this repo._"
    }

    test("negative controls are excluded from the headline and show the loss to WITHOUT_TOOLS explicitly") {
        val doc = BenchmarksReportGenerator.generate(sampleRun())
        val headlineSection = doc.substringAfter("## Headline Results").substringBefore("## Per-Repo Results")
        headlineSection shouldNotContain "q2"
        val negativeSection = doc.substringAfter("## Negative Controls").substringBefore("## Contamination Report")
        negativeSection shouldContain "| q2 | repoA |"
        negativeSection shouldContain "ContextGraph loses on accuracy"
    }

    test("contamination report shows blocked CLI attempts and excluded runs per arm") {
        val doc = BenchmarksReportGenerator.generate(sampleRun())
        val section = doc.substringAfter("## Contamination Report").substringBefore("## Tool-Call Ceiling Report")
        // WITHOUT_TOOLS run recorded one blocked CLI invocation attempt; nothing was contaminated in this fixture.
        section shouldContain "WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) | 2 | 0 | 1 |"
        section shouldContain "WITH_TOOLS (ContextGraph MCP) | 2 | 0 | 0 |"
    }

    test("contamination report states the failed-run count was not recorded when the field is absent (older/default result)") {
        val doc = BenchmarksReportGenerator.generate(sampleRun()) // failedRunCount defaults to null
        val section = doc.substringAfter("## Contamination Report").substringBefore("## Tool-Call Ceiling Report")
        section shouldContain "Failed runs (crashed before producing a record at all"
        section shouldContain "not recorded for this result"
    }

    test("contamination report states zero failed runs explicitly, distinct from 'not recorded'") {
        val doc = BenchmarksReportGenerator.generate(sampleRun().copy(failedRunCount = 0))
        val section = doc.substringAfter("## Contamination Report").substringBefore("## Tool-Call Ceiling Report")
        section shouldContain "Failed runs (crashed before producing a record at all"
        section shouldNotContain "not recorded for this result"
        section.trim().lines().last { it.startsWith("Failed runs") } shouldContain ": 0"
    }

    test("contamination report shows a nonzero failed-run count from AC-9's resilience requirement") {
        val doc = BenchmarksReportGenerator.generate(sampleRun().copy(failedRunCount = 2))
        val section = doc.substringAfter("## Contamination Report").substringBefore("## Tool-Call Ceiling Report")
        section.trim().lines().last { it.startsWith("Failed runs") } shouldContain ": 2"
    }

    test("a run with no agent runs at all still shows its failed-run count rather than skipping the line") {
        val allFailed = BenchmarkRun(
            runId = "all-failed-run",
            profile = Profile.SMOKE,
            generatedAt = Instant.parse("2026-08-17T12:00:00Z"),
            config = BenchmarkConfig(),
            failedRunCount = 6
        )
        val doc = BenchmarksReportGenerator.generate(allFailed)
        val section = doc.substringAfter("## Contamination Report").substringBefore("## Tool-Call Ceiling Report")
        section.trim().lines().last { it.startsWith("Failed runs") } shouldContain ": 6"
        section shouldContain "_No agent runs recorded for this result._"
    }

    test("ceiling report shows the WITHOUT_TOOLS run that hit the ceiling") {
        val doc = BenchmarksReportGenerator.generate(sampleRun())
        val section = doc.substringAfter("## Tool-Call Ceiling Report").substringBefore("## Judge Validation")
        section shouldContain "WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent) | 2 | 1 |"
        section shouldContain "WITH_TOOLS (ContextGraph MCP) | 2 | 0 |"
    }

    test("judge validation states plainly when kappa validation was not run") {
        val doc = BenchmarksReportGenerator.generate(sampleRun(), kappaValidation = null)
        doc shouldContain "**Kappa validation was not run for this result.**"
        doc shouldNotContain "Cohen's kappa |"
    }

    test("judge validation renders agreement rate and Cohen's kappa when a validation result is supplied") {
        val kappa = KappaValidationResult(
            primaryJudgeModel = "claude-opus-5",
            secondaryJudgeModel = "claude-opus-4",
            totalRuns = 4,
            sampledRunIds = listOf("q1-with", "q1-without"),
            agreementRate = 0.9,
            kappa = 0.81,
            secondaryScores = emptyList()
        )
        val doc = BenchmarksReportGenerator.generate(sampleRun(), kappaValidation = kappa)
        doc shouldContain "| Primary judge | `claude-opus-5` |"
        doc shouldContain "| Secondary judge | `claude-opus-4` |"
        doc shouldContain "| Agreement rate | 90.0% |"
        doc shouldContain "| Cohen's kappa | 0.810 |"
    }

    test("break-even renders Amortizes with a questions-to-break-even figure, never a bare number without context") {
        val doc = BenchmarksReportGenerator.generate(sampleRun())
        val section = doc.substringAfter("## Ingest Cost & Break-Even").substringBefore("## Reproduction")
        // repoA: WITH_TOOLS pooled median cost 0.3, WITHOUT_TOOLS pooled median cost 1.01 -> saves 0.71/question.
        section shouldContain "Amortizes -- saves \$0.7100/question, breaks even at"
        section shouldContain "questions"
    }

    test("break-even renders NotAmortizing as a plain statement, not a division") {
        val notAmortizingRun = sampleRun().let { run ->
            // Flip repoA's costs so WITH_TOOLS is now the more expensive arm -> saving <= 0.
            val flipped = run.agentRuns.map {
                if (it.questionId == "q1" && it.arm == Arm.WITH_TOOLS) it.copy(costUsd = 999.0) else it
            }
            val withFlippedRuns = run.copy(agentRuns = flipped)
            withFlippedRuns.copy(summary = BenchmarkStats.summarize(withFlippedRuns))
        }
        val doc = BenchmarksReportGenerator.generate(notAmortizingRun)
        val section = doc.substringAfter("## Ingest Cost & Break-Even").substringBefore("## Reproduction")
        section shouldContain "Does not amortize on cost alone"
        section shouldNotContain "breaks even at"
    }

    test("break-even renders InsufficientData with its reason when a repo has an ingest record but no agent runs") {
        val doc = BenchmarksReportGenerator.generate(sampleRun())
        val section = doc.substringAfter("## Ingest Cost & Break-Even").substringBefore("## Reproduction")
        section shouldContain "Insufficient data --"
    }

    // ------------------------------------------------------------- task 20: cost not recorded

    /** [sampleRun] with every agent run's costUsd nulled out and the agent model switched to one ModelPricing has no rate for -- the shape a real gpt-4.1-nano run produces (task 20). */
    fun sampleRunWithUnpricedModel(): BenchmarkRun {
        val base = sampleRun()
        val nullCostRuns = base.agentRuns.map { it.copy(costUsd = null) }
        val unpriced = base.copy(
            config = base.config.copy(models = base.config.models.copy(agentModel = "gpt-4.1-nano")),
            agentRuns = nullCostRuns
        )
        return unpriced.copy(summary = BenchmarkStats.summarize(unpriced))
    }

    test("methodology states cost was not recorded for an unpriced agent model, not silently") {
        val doc = BenchmarksReportGenerator.generate(sampleRunWithUnpricedModel())
        doc shouldContain "Cost not recorded for this run."
        doc shouldContain "gpt-4.1-nano"
    }

    test("methodology carries no cost-not-recorded note for a priced agent model (regression: sampleRun's claude-sonnet-5)") {
        val doc = BenchmarksReportGenerator.generate(sampleRun())
        doc shouldNotContain "Cost not recorded for this run."
    }

    test("cost row reads 'no data' rather than \$0.0000 when cost was never recorded") {
        val doc = BenchmarksReportGenerator.generate(sampleRunWithUnpricedModel())
        val headline = doc.substringAfter("## Headline Results").substringBefore("## Per-Repo Results")
        val costRow = headline.lines().first { it.startsWith("| Cost |") }
        costRow shouldContain "no data"
        costRow shouldNotContain "$0.0000"
    }

    test("break-even section explicitly attributes InsufficientData to an unrecorded cost, not a missing run, for an unpriced model") {
        val doc = BenchmarksReportGenerator.generate(sampleRunWithUnpricedModel())
        val section = doc.substringAfter("## Ingest Cost & Break-Even").substringBefore("## Reproduction")
        section shouldContain "cost was not recorded"
        section shouldContain "cost not recorded for agent model `gpt-4.1-nano`"
        section shouldNotContain "Amortizes"
    }

    test("a run with no summary yet renders explicit not-run-yet statements instead of crashing") {
        val unsummarized = sampleRun().copy(summary = null)
        val doc = BenchmarksReportGenerator.generate(unsummarized)
        doc shouldContain "_Not run yet -- no headline-category agent runs recorded for this result._"
        doc shouldContain "_Not run yet -- no negative-control agent runs recorded for this result._"
    }

    test("a run with no agent runs, ingest records or corpus repos at all renders without crashing") {
        val empty = BenchmarkRun(
            runId = "empty-run",
            profile = Profile.SMOKE,
            generatedAt = Instant.parse("2026-08-17T12:00:00Z"),
            config = BenchmarkConfig()
        )
        val doc = BenchmarksReportGenerator.generate(empty)
        doc shouldContain "_No corpus repos recorded for this result._"
        doc shouldContain "_No agent runs recorded for this result._"
        doc shouldContain "_No indexing recorded for this result._"
    }

    test("AC-18a: a SYNTHETIC result carries the top-of-document warning, right after the title") {
        val doc = BenchmarksReportGenerator.generate(sampleRun().copy(agentClientKind = AgentClientKind.SYNTHETIC))
        doc shouldContain "SYNTHETIC RESULT -- not a measurement"
        doc shouldContain "Do not cite these numbers as a benchmark result"
        // Right after the title: the first non-blank line after "# BENCHMARKS" is the warning,
        // not the "_Generated from..._" line or any other section.
        val lines = doc.lines()
        val titleIndex = lines.indexOfFirst { it == "# BENCHMARKS" }
        val firstContentAfterTitle = lines.drop(titleIndex + 1).first { it.isNotBlank() }
        firstContentAfterTitle shouldContain "SYNTHETIC RESULT"
    }

    test("AC-18a: a result with agentClientKind unrecorded (null, e.g. an older result JSON) also carries the warning") {
        val doc = BenchmarksReportGenerator.generate(sampleRun().copy(agentClientKind = null))
        doc shouldContain "SYNTHETIC RESULT -- not a measurement"
        doc shouldContain "not recorded -- predates this field"
    }

    test("AC-18a: a REAL result carries no synthetic-run warning") {
        val doc = BenchmarksReportGenerator.generate(sampleRun().copy(agentClientKind = AgentClientKind.REAL))
        doc shouldNotContain "SYNTHETIC RESULT"
        doc shouldNotContain "not a measurement"
    }

    test("task 18: a checkpoint result (judgingComplete = false) carries the top-of-document unscored warning, right after the title") {
        // agentClientKind = REAL so the (unrelated) AC-18a synthetic-run banner stays out of this
        // document -- isolates the assertion to the unscored warning this test is about.
        val doc = BenchmarksReportGenerator.generate(sampleRun().copy(agentClientKind = AgentClientKind.REAL, judgingComplete = false))
        doc shouldContain "UNSCORED RESULT -- judging did not complete"
        val lines = doc.lines()
        val titleIndex = lines.indexOfFirst { it == "# BENCHMARKS" }
        val firstContentAfterTitle = lines.drop(titleIndex + 1).first { it.isNotBlank() }
        firstContentAfterTitle shouldContain "UNSCORED RESULT"
    }

    test("task 18: judgingComplete = false alongside a non-REAL agentClientKind shows both top-of-document warnings, synthetic first") {
        val doc = BenchmarksReportGenerator.generate(sampleRun().copy(agentClientKind = AgentClientKind.SYNTHETIC, judgingComplete = false))
        doc shouldContain "SYNTHETIC RESULT -- not a measurement"
        doc shouldContain "UNSCORED RESULT -- judging did not complete"
        doc.indexOf("SYNTHETIC RESULT") shouldBeLessThan doc.indexOf("UNSCORED RESULT")
    }

    test("task 18: a fully-judged result (judgingComplete = true, the default) carries no unscored warning") {
        val doc = BenchmarksReportGenerator.generate(sampleRun())
        doc shouldNotContain "UNSCORED RESULT"
    }

    test("task 18: judge validation states the failed-judge count was not recorded when the field is absent") {
        val doc = BenchmarksReportGenerator.generate(sampleRun()) // failedJudgeCount defaults to null
        val section = doc.substringAfter("## Judge Validation").substringBefore("## Ingest Cost & Break-Even")
        section shouldContain "Judge scoring failures"
        section shouldContain "not recorded for this result"
    }

    test("task 18: judge validation states zero judge failures explicitly, distinct from 'not recorded'") {
        val doc = BenchmarksReportGenerator.generate(sampleRun().copy(failedJudgeCount = 0))
        val section = doc.substringAfter("## Judge Validation").substringBefore("## Ingest Cost & Break-Even")
        section shouldContain "Judge scoring failures"
        section shouldNotContain "not recorded for this result"
        section.trim().lines().last { it.startsWith("Judge scoring failures") } shouldContain ": 0"
    }

    test("task 18: judge validation shows a nonzero failed-judge count") {
        val doc = BenchmarksReportGenerator.generate(sampleRun().copy(failedJudgeCount = 2))
        val section = doc.substringAfter("## Judge Validation").substringBefore("## Ingest Cost & Break-Even")
        section.trim().lines().last { it.startsWith("Judge scoring failures") } shouldContain ": 2"
    }

    test("determinism holds with judgingComplete=false and failedJudgeCount set, same as with them absent") {
        val partial = sampleRun().copy(judgingComplete = false, failedJudgeCount = 3, judgeScores = emptyList(), summary = null)
        val json = partial.toJson()
        val first = BenchmarksReportGenerator.generate(BenchmarkRun.fromJson(json))
        val second = BenchmarksReportGenerator.generate(BenchmarkRun.fromJson(json))
        first shouldBe second
    }

    test("reproduction commands match the real CLI surface") {
        val doc = BenchmarksReportGenerator.generate(sampleRun())
        // This run's own profile is smoke, so the top reproduction command matches it exactly.
        doc shouldContain "./gradlew :modules:benchmark:run --args=\"--profile smoke\""
        doc shouldContain "https://github.com/excalidraw/excalidraw @ `v1.2.3` (`abcdef1234567890`)"
    }
})
