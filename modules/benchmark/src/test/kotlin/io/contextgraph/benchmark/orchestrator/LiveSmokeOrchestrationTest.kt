package io.contextgraph.benchmark.orchestrator

import io.contextgraph.benchmark.cli.RepoRoot
import io.contextgraph.benchmark.judge.JudgeClient
import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.FactScore
import io.contextgraph.benchmark.model.Profile
import io.contextgraph.benchmark.report.BenchmarksReportGenerator
import io.contextgraph.benchmark.runner.AgentClient
import io.contextgraph.benchmark.runner.AgentClientOutcome
import io.contextgraph.benchmark.runner.ContextGraphMcpToolBridge
import io.contextgraph.mcp.ExploreResponse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.opentest4j.TestAbortedException
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The one place this module's real, gitignored `.benchmark-corpus/` directory and the real
 * `modules/benchmark/questions/` gold sets are exercised together for real (task 12): real
 * [io.contextgraph.benchmark.corpus.CorpusPreparationStep] (reusing gin's already-prepared
 * working copies rather than touching the other three repos or re-cloning anything), real
 * [io.contextgraph.benchmark.questions.QuestionSetLoader], real [AgentRunner][io.contextgraph.benchmark.runner.AgentRunner]
 * orchestration (contamination guard, ceiling, fingerprinting), real
 * [io.contextgraph.benchmark.judge.JudgeScorer] reconciliation, real
 * [io.contextgraph.benchmark.stats.BenchmarkStats], and real JSON/`BENCHMARKS.md` output. Only
 * [AgentClient] and [JudgeClient] are stubbed, at the same narrow boundary
 * [BenchmarkOrchestrator]'s constructor defines for production -- see task 12's explicit
 * instruction that the stub swap must be at the client boundary only, nowhere else in the
 * pipeline.
 *
 * Skipped by default (a no-op, not merely "expected to be green") so `./gradlew build`/`check`
 * never executes the benchmark (AC-22): the real corpus directory this test depends on does not
 * exist outside this machine's checkout and would not be reproducible in CI regardless. Run it
 * explicitly:
 *
 * ```bash
 * BENCHMARK_SMOKE_LIVE_CORPUS=true ./gradlew :modules:benchmark:test \
 *   --tests "io.contextgraph.benchmark.orchestrator.LiveSmokeOrchestrationTest"
 * ```
 */
class LiveSmokeOrchestrationTest : FunSpec({

    test("smoke profile runs end to end against the real corpus and the real gold sets, with stub agent/judge clients") {
        if (System.getenv("BENCHMARK_SMOKE_LIVE_CORPUS") != "true") {
            // Task 14, point 3: a skipped test is not a passed test. `return@test` used to exit
            // the test body cleanly, which Kotest/JUnit Platform reports as this test having
            // run and succeeded -- indistinguishable in the test report from the real, gated
            // assertions below actually having executed. Throwing TestAbortedException instead
            // is the mechanism Kotest's own engine special-cases (see
            // io.kotest.engine.interceptors.MarkAbortedExceptionsAsSkippedTestInterceptor) to
            // report a test as skipped/aborted rather than successful -- visible as such in
            // Gradle's test report and JUnit XML, not silently folded into "tests passed."
            throw TestAbortedException(
                "Skipped: BENCHMARK_SMOKE_LIVE_CORPUS != \"true\" -- see this class's KDoc to run it explicitly."
            )
        }

        val repoRoot = RepoRoot.find(Path.of(System.getProperty("user.dir")))
        val corpusRoot = repoRoot.resolve(".benchmark-corpus")
        val questionsDir = repoRoot.resolve("modules/benchmark/questions")
        val outputDir = repoRoot.resolve("modules/benchmark/results")
        outputDir.createDirectories()

        // Evidence for the Lead's demand: not "smoke is green" but actual node/symbol counts
        // the WITH_TOOLS arm's real ContextGraphMcpToolBridge returned for each question, so a
        // regression to the graph.db/graph.local.db bug would show up here as empty=true /
        // symbols=[] rather than being silently invisible behind a stub that never calls the tool.
        val exploreResponseJson = Json { ignoreUnknownKeys = true }
        val withToolsEvidence = mutableListOf<Pair<String, ExploreResponse>>()

        val agentClient = AgentClient { context ->
            if (context.arm == Arm.WITH_TOOLS) {
                // A real agent asked to use ContextGraph's tool would call it with the question
                // text -- this stub does the same real call (through the real bridge AgentRunner
                // wired up), it just skips an actual model turn deciding to do so.
                val toolName = context.extraTools.single().name
                val responseJson = context.invokeExtraTool(toolName, """{"question":"${context.question.text}"}""")
                val response = exploreResponseJson.decodeFromString(ExploreResponse.serializer(), responseJson)
                withToolsEvidence += context.question.id to response
            }
            AgentClientOutcome(
                inputTokens = 500,
                outputTokens = 120,
                toolCallCount = if (context.arm == Arm.WITH_TOOLS) 1 else 6,
                fileReadCount = 1,
                costUsd = 0.01,
                finalAnswer = "Deterministic stub answer for ${context.question.id} (${context.arm})."
            )
        }
        val judgeClient = object : JudgeClient {
            override suspend fun scoreFacts(input: io.contextgraph.benchmark.judge.JudgeInput, judgeModel: String): List<FactScore> =
                input.goldFacts.map { FactScore(it.id, hit = true) }
        }

        val messages = mutableListOf<String>()
        val orchestrator = BenchmarkOrchestrator(
            agentClient = agentClient,
            judgeClient = judgeClient,
            corpusRoot = corpusRoot,
            questionsDir = questionsDir,
            progress = { messages += it; println("[live-smoke] $it") }
        )

        val result = orchestrator.run(Profile.SMOKE)

        result.plannedRunCount shouldBe 6
        result.run.agentRuns shouldHaveSize 6
        result.failedRunCount shouldBe 0
        result.run.corpusRepos.map { it.id } shouldBe listOf("gin")

        // The actual defect-fix evidence: every WITH_TOOLS question's real bridge call against
        // gin's real index returned real, non-empty content -- not the empty graph the bug used
        // to silently produce.
        withToolsEvidence shouldHaveSize 3
        withToolsEvidence.forEach { (questionId, response) ->
            println(
                "[live-smoke] WITH_TOOLS evidence for $questionId: empty=${response.empty}, " +
                    "modules=${response.modules.size}, symbols=${response.symbols.size}"
            )
            response.empty shouldBe false
            response.symbols.size shouldBeGreaterThan 0
        }

        val jsonPath = result.run.writeTo(outputDir)
        val reportPath = outputDir.resolve("BENCHMARKS.md")
        reportPath.writeText(BenchmarksReportGenerator.generate(result.run))

        println("Live smoke result JSON: ${jsonPath.absolutePathString()}")
        println("Live smoke BENCHMARKS.md: ${reportPath.absolutePathString()}")
    }
})
