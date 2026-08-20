package io.contextgraph.benchmark.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import io.contextgraph.benchmark.corpus.LocalGitFixture
import io.contextgraph.benchmark.judge.JudgeClient
import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.BenchmarkRun
import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.FactScore
import io.contextgraph.benchmark.model.Profile
import io.contextgraph.benchmark.runner.AgentClient
import io.contextgraph.benchmark.runner.AgentClientOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Exercises [BenchmarkCli]'s real wiring (slice 12): argument parsing/rejection still needs no
 * agent or judge call at all (unchanged from slice 01's thin-CLI tests), but a valid `--profile`
 * now runs the full orchestrated sequence -- so the positive-path tests inject a fixture corpus
 * catalog and deterministic stub [AgentClient]/[JudgeClient] at the same boundary
 * [io.contextgraph.benchmark.orchestrator.BenchmarkOrchestrator] defines, exactly as a real
 * invocation would inject the live [io.contextgraph.benchmark.runner.AnthropicMessagesClient] /
 * [io.contextgraph.benchmark.judge.LlmJudgeClient] instead.
 */
class BenchmarkCliTest : FunSpec({

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

    val stubJudgeClient = object : JudgeClient {
        override suspend fun scoreFacts(input: io.contextgraph.benchmark.judge.JudgeInput, judgeModel: String): List<FactScore> =
            input.goldFacts.map { FactScore(it.id, hit = true) }
    }

    fun writeFixtureQuestions(dir: Path, repoId: String) {
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
              - id: $repoId-q3
                repoId: $repoId
                text: "What does q3 do?"
                category: NEGATIVE_CONTROL
                goldFacts:
                  - id: $repoId-q3-f1
                    statement: "fact one"
                    evidence: "src/Foo.kt:1"
                  - id: $repoId-q3-f2
                    statement: "fact two"
                    evidence: "src/Foo.kt:1"
                  - id: $repoId-q3-f3
                    statement: "fact three"
                    evidence: "src/Foo.kt:1"
            """.trimIndent()
        )
    }

    test("--profile smoke runs the real orchestrated sequence end to end and writes a schema-versioned result JSON plus BENCHMARKS.md") {
        val root = createTempDirectory("benchmark-cli-smoke")
        val remote = LocalGitFixture.create(root.resolve("remote"))
        val repo = CorpusRepo(id = "gin", name = "gin", url = remote.path.toString(), pinnedTag = remote.tag, pinnedSha = remote.sha)
        val questionsDir = root.resolve("questions")
        writeFixtureQuestions(questionsDir, "gin")
        val outputDir = root.resolve("results")

        val cli = BenchmarkCli(
            agentClient = stubAgentClient(),
            judgeClient = stubJudgeClient,
            catalog = listOf(repo),
            startDir = root
        )
        cli.parse(
            arrayOf(
                "--profile", "smoke",
                "--output-dir", outputDir.toString(),
                "--corpus-root", root.resolve("corpus").toString(),
                "--questions-dir", questionsDir.toString()
            )
        )

        val written = outputDir.listDirectoryEntries("*.json")
        written.size shouldBe 1

        val run = BenchmarkRun.readFrom(written.single())
        run.profile shouldBe Profile.SMOKE
        run.schemaVersion shouldBe BenchmarkRun.SCHEMA_VERSION
        run.questions.size shouldBe 3 // all 3 fixture questions fit under smoke's 3-per-repo cap
        run.agentRuns.size shouldBe 6 // 3 questions x 2 arms x 1 repeat
        run.corpusRepos.map { it.id } shouldBe listOf("gin")

        val reportPath = outputDir.resolve("BENCHMARKS.md")
        reportPath.exists() shouldBe true
        reportPath.readText() shouldContain "# BENCHMARKS"
    }

    test("--profile full runs the identical sequence, just over full scope instead of smoke's cap") {
        val root = createTempDirectory("benchmark-cli-full")
        val remote = LocalGitFixture.create(root.resolve("remote"))
        val repo = CorpusRepo(id = "gin", name = "gin", url = remote.path.toString(), pinnedTag = remote.tag, pinnedSha = remote.sha)
        val questionsDir = root.resolve("questions")
        writeFixtureQuestions(questionsDir, "gin")
        val outputDir = root.resolve("results")

        val cli = BenchmarkCli(
            agentClient = stubAgentClient(),
            judgeClient = stubJudgeClient,
            catalog = listOf(repo),
            startDir = root
        )
        cli.parse(
            arrayOf(
                "--profile", "full",
                "--output-dir", outputDir.toString(),
                "--corpus-root", root.resolve("corpus").toString(),
                "--questions-dir", questionsDir.toString()
            )
        )

        val run = BenchmarkRun.readFrom(outputDir.listDirectoryEntries("*.json").single())
        run.profile shouldBe Profile.FULL
        run.questions.size shouldBe 3 // all fixture questions, uncapped
        run.agentRuns.size shouldBe 24 // 3 questions x 2 arms x 4 repeats (default repeatsPerArm)
    }

    test("an unrecognized profile is rejected with a non-zero-exit-coded, clear error, before any orchestration runs") {
        val dir = createTempDirectory("benchmark-cli-invalid")
        shouldThrow<CliktError> {
            BenchmarkCli().parse(arrayOf("--profile", "bogus", "--output-dir", dir.toString()))
        }
        dir.listDirectoryEntries("*.json").isEmpty() shouldBe true
    }

    test("a missing --profile is rejected before any orchestration runs") {
        val dir = createTempDirectory("benchmark-cli-missing")
        shouldThrow<CliktError> {
            BenchmarkCli().parse(arrayOf("--output-dir", dir.toString()))
        }
    }

    test("task 18: a judge outage that fails every answer still leaves the agent-run checkpoint readable on disk") {
        val root = createTempDirectory("benchmark-cli-judge-outage")
        val remote = LocalGitFixture.create(root.resolve("remote"))
        val repo = CorpusRepo(id = "gin", name = "gin", url = remote.path.toString(), pinnedTag = remote.tag, pinnedSha = remote.sha)
        val questionsDir = root.resolve("questions")
        writeFixtureQuestions(questionsDir, "gin")
        val outputDir = root.resolve("results")

        val alwaysFailingJudgeClient = object : JudgeClient {
            override suspend fun scoreFacts(input: io.contextgraph.benchmark.judge.JudgeInput, judgeModel: String): List<FactScore> =
                throw RuntimeException("simulated judge outage (e.g. a sustained 429)")
        }

        val cli = BenchmarkCli(
            agentClient = stubAgentClient(),
            judgeClient = alwaysFailingJudgeClient,
            catalog = listOf(repo),
            startDir = root
        )

        shouldThrow<io.contextgraph.benchmark.orchestrator.AllJudgeCallsFailedException> {
            cli.parse(
                arrayOf(
                    "--profile", "smoke",
                    "--output-dir", outputDir.toString(),
                    "--corpus-root", root.resolve("corpus").toString(),
                    "--questions-dir", questionsDir.toString()
                )
            )
        }

        // The whole run() call above threw -- the final `result.run.writeTo` at the bottom of
        // BenchmarkCli.run() never executed. What is on disk is only the checkpoint written
        // before judging started, and it must still be there, readable, and honest about being
        // incomplete.
        val written = outputDir.listDirectoryEntries("*.json")
        written.size shouldBe 1
        val checkpoint = BenchmarkRun.readFrom(written.single())
        checkpoint.agentRuns.size shouldBe 6 // 3 questions x 2 arms x 1 repeat -- every agent run survived
        checkpoint.judgeScores.size shouldBe 0
        checkpoint.judgingComplete shouldBe false
        checkpoint.summary shouldBe null
    }
})
