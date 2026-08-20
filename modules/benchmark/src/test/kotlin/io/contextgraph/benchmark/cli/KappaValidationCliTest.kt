package io.contextgraph.benchmark.cli

import com.github.ajalt.clikt.core.parse
import io.contextgraph.benchmark.corpus.LocalGitFixture
import io.contextgraph.benchmark.judge.JudgeClient
import io.contextgraph.benchmark.judge.JudgeInput
import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.BenchmarkRun
import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.FactScore
import io.contextgraph.benchmark.model.Profile
import io.contextgraph.benchmark.orchestrator.BenchmarkOrchestrator
import io.contextgraph.benchmark.runner.AgentClient
import io.contextgraph.benchmark.runner.AgentClientOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Proves task 14's fix for AC-14's missing production caller: before this slice, nothing in
 * production code ever called [io.contextgraph.benchmark.judge.KappaValidator], so
 * `BENCHMARKS.md` could only ever say kappa validation was not run. This exercises
 * [KappaValidationCommand]'s real wiring end to end -- read an existing result JSON (the same
 * shape [BenchmarkCli] writes), re-score a subsample with a second stub judge, and confirm the
 * regenerated `BENCHMARKS.md` carries the agreement rate and Cohen's kappa.
 */
class KappaValidationCliTest : FunSpec({

    fun stubAgentClient() = AgentClient { context ->
        AgentClientOutcome(
            inputTokens = 100,
            outputTokens = 20,
            toolCallCount = if (context.arm == Arm.WITH_TOOLS) 2 else 5,
            fileReadCount = 1,
            costUsd = 0.001,
            finalAnswer = "stub answer for ${context.question.id}"
        )
    }

    val primaryJudgeClient = object : JudgeClient {
        override suspend fun scoreFacts(input: JudgeInput, judgeModel: String): List<FactScore> =
            input.goldFacts.map { FactScore(it.id, hit = true) }
    }

    fun writeQuestions(dir: Path, repoId: String) {
        Files.createDirectories(dir)
        dir.resolve("$repoId.yaml").writeText(
            """
            questions:
              - id: $repoId-q1
                repoId: $repoId
                text: "What does q1 do?"
                category: GRAPH_HEAVY
                goldFacts:
                  - id: $repoId-q1-f1
                    statement: "fact one"
                    evidence: "src/Foo.kt:1"
                  - id: $repoId-q1-f2
                    statement: "fact two"
                    evidence: "src/Foo.kt:1"
                  - id: $repoId-q1-f3
                    statement: "fact three"
                    evidence: "src/Foo.kt:1"
              - id: $repoId-q2
                repoId: $repoId
                text: "What does q2 do?"
                category: NEUTRAL
                goldFacts:
                  - id: $repoId-q2-f1
                    statement: "fact one"
                    evidence: "src/Foo.kt:1"
                  - id: $repoId-q2-f2
                    statement: "fact two"
                    evidence: "src/Foo.kt:1"
                  - id: $repoId-q2-f3
                    statement: "fact three"
                    evidence: "src/Foo.kt:1"
            """.trimIndent()
        )
    }

    /** Produces (and writes to disk) a real result JSON, the same shape BenchmarkCli writes. */
    fun writeSampleResult(root: Path): Path {
        val remote = LocalGitFixture.create(root.resolve("remote"))
        val repo = CorpusRepo(id = "gin", name = "gin", url = remote.path.toString(), pinnedTag = remote.tag, pinnedSha = remote.sha)
        val questionsDir = root.resolve("questions")
        writeQuestions(questionsDir, "gin")

        val orchestrator = BenchmarkOrchestrator(
            agentClient = stubAgentClient(),
            judgeClient = primaryJudgeClient,
            corpusRoot = root.resolve("corpus"),
            questionsDir = questionsDir,
            catalog = listOf(repo)
        )
        val result = orchestrator.run(Profile.SMOKE)
        val outputDir = root.resolve("results")
        return result.run.writeTo(outputDir)
    }

    test("validate-kappa re-scores a subsample with the secondary judge and folds agreement/kappa into BENCHMARKS.md") {
        val root = createTempDirectory("kappa-cli-")
        val resultJson = writeSampleResult(root)

        // Disagrees on exactly one fact per question, deterministically, so the numbers this
        // test asserts on are hand-computable rather than merely "some non-null value appeared."
        val secondaryJudgeClient = object : JudgeClient {
            override suspend fun scoreFacts(input: JudgeInput, judgeModel: String): List<FactScore> =
                input.goldFacts.mapIndexed { index, fact -> FactScore(fact.id, hit = index != 0) }
        }

        val command = KappaValidationCommand(judgeClient = secondaryJudgeClient)
        command.parse(
            arrayOf(
                "--result", resultJson.toString(),
                "--secondary-judge-model", "second-model",
                "--fraction", "1.0"
            )
        )

        val reportPath = resultJson.parent.resolve("BENCHMARKS.md")
        val report = reportPath.readText()

        report shouldNotContain "**Kappa validation was not run for this result.**"
        report shouldContain "| Secondary judge | `second-model` |"
        report shouldContain "Cohen's kappa |"

        val run = BenchmarkRun.readFrom(resultJson)
        val scoredRuns = toScoredRuns(run)
        scoredRuns.size shouldBe run.agentRuns.size // every agent run has a matching primary judge score
        // 2 questions x 2 arms x 1 repeat (smoke, this fixture's 2-question gold set): 4 runs.
        scoredRuns.size shouldBe 4

        // Same math KappaValidatorTest already proves at the unit level -- re-derived here only
        // to confirm the CLI wiring produced the identical result, not a different computation.
        // Primary judge hits every fact; secondary flips exactly the first fact of every
        // question (index 0) to a miss -- one disagreement per scored run, regardless of how
        // many gold facts that question has.
        val totalFacts = scoredRuns.sumOf { it.question.goldFacts.size }
        val disagreements = scoredRuns.size
        val expectedAgreement = (totalFacts - disagreements).toDouble() / totalFacts
        report shouldContain "| Agreement rate | ${String.format(Locale.ROOT, "%.1f", expectedAgreement * 100.0)}%"
    }

    test("toScoredRuns: an agent run whose questionId has no matching Question is skipped, not crashed on") {
        val root = createTempDirectory("kappa-cli-mismatch-")
        val resultJson = writeSampleResult(root)
        val run = BenchmarkRun.readFrom(resultJson)

        val withMissingQuestion = run.copy(questions = run.questions.drop(1))
        val scoredRuns = toScoredRuns(withMissingQuestion)

        // Runs against the dropped question's id are skipped; every other run is still scored.
        scoredRuns.size shouldBe run.agentRuns.count { it.questionId in withMissingQuestion.questions.map { q -> q.id } }
    }
})
