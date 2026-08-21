package io.contextgraph.benchmark.orchestrator

import io.contextgraph.benchmark.corpus.LocalGitFixture
import io.contextgraph.benchmark.judge.JudgeClient
import io.contextgraph.benchmark.model.AgentClientKind
import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.FactScore
import io.contextgraph.benchmark.model.Profile
import io.contextgraph.benchmark.runner.AgentCallFailedException
import io.contextgraph.benchmark.runner.AgentClient
import io.contextgraph.benchmark.runner.AgentClientOutcome
import io.contextgraph.benchmark.runner.AnthropicMessagesClient
import io.contextgraph.benchmark.runner.ContaminatedWorkingCopyException
import io.contextgraph.benchmark.runner.OpenAiChatCompletionsClient
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.file.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain as stringShouldContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Wires slices 02-07 offline against a small local git fixture (see `CorpusPreparerTest`'s use
 * of the same fixture) rather than the real multi-gigabyte corpus, so this suite is fast and
 * runs on every `./gradlew build` -- proving the wiring, resilience, and planning behaviour task
 * 12 requires without ever touching `.benchmark-corpus/` or a real model. See
 * `LiveSmokeOrchestrationTest` for the one place this module's real corpus is exercised for
 * real, gated off by default.
 */
class BenchmarkOrchestratorTest : FunSpec({

    fun gold(id: String) =
        """
        |    - id: $id-f1
        |      statement: "Foo does something"
        |      evidence: "src/Foo.kt:1"
        |    - id: $id-f2
        |      statement: "Foo does another thing"
        |      evidence: "src/Foo.kt:1"
        |    - id: $id-f3
        |      statement: "Foo does a third thing"
        |      evidence: "src/Foo.kt:1"
        """.trimMargin()

    fun writeQuestions(dir: Path, repoId: String, ids: List<String>) {
        Files.createDirectories(dir)
        val body = buildString {
            appendLine("questions:")
            ids.forEach { id ->
                appendLine("  - id: $id")
                appendLine("    repoId: $repoId")
                appendLine("    text: \"What does $id do?\"")
                appendLine("    category: GRAPH_HEAVY")
                appendLine("    goldFacts:")
                appendLine(gold(id))
            }
        }
        dir.resolve("$repoId.yaml").writeText(body)
    }

    fun stubAgentClient(behavior: (String, Arm) -> AgentClientOutcome = { id, arm ->
        AgentClientOutcome(
            inputTokens = 100,
            outputTokens = 20,
            toolCallCount = if (arm == Arm.WITH_TOOLS) 2 else 5,
            fileReadCount = 1,
            costUsd = 0.001,
            finalAnswer = "stub answer for $id"
        )
    }) = AgentClient { context -> behavior(context.question.id, context.arm) }

    val stubJudgeClient = object : JudgeClient {
        override suspend fun scoreFacts(input: io.contextgraph.benchmark.judge.JudgeInput, judgeModel: String): List<FactScore> =
            input.goldFacts.map { FactScore(it.id, hit = true) }
    }

    test("smoke profile runs the whole sequence end to end: 1 repo x 3 questions x 2 arms x 1 repeat = 6 runs") {
        val root = Files.createTempDirectory("orch-smoke-")
        try {
            val remote = LocalGitFixture.create(root.resolve("remote"))
            val repo = CorpusRepo(id = "gin", name = "gin (fixture)", url = remote.path.toString(), pinnedTag = remote.tag, pinnedSha = remote.sha)
            val questionsDir = root.resolve("questions")
            writeQuestions(questionsDir, "gin", listOf("gin-q1", "gin-q2", "gin-q3", "gin-q4"))

            val orchestrator = BenchmarkOrchestrator(
                agentClient = stubAgentClient(),
                judgeClient = stubJudgeClient,
                corpusRoot = root.resolve("corpus"),
                questionsDir = questionsDir,
                catalog = listOf(repo)
            )

            val result = orchestrator.run(Profile.SMOKE)

            result.plannedRunCount shouldBe 6
            result.failedRunCount shouldBe 0
            result.run.failedRunCount shouldBe 0 // zero failures is recorded explicitly, not left null
            result.run.agentRuns shouldHaveSize 6
            result.run.judgeScores shouldHaveSize 6
            result.run.questions shouldHaveSize 3 // capped at SMOKE_QUESTIONS_PER_REPO even though 4 exist
            result.run.ingestRecords shouldHaveSize 1
            result.run.corpusRepos shouldHaveSize 1
            (result.run.summary != null) shouldBe true
            result.run.summary?.perQuestionArm?.isEmpty() shouldBe false
            // AC-18a: a test-only stub AgentClient is never mistaken for a real agent.
            result.run.agentClientKind shouldBe AgentClientKind.SYNTHETIC

            // Every judge score reconciles to a full accuracy (stub judge hits every fact).
            result.run.judgeScores.all { it.accuracyScore == 1.0 } shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("a plan with zero runs fails loudly instead of writing an empty result that looks like a success") {
        // The failure this guards was hit for real: --questions-dir held only excalidraw
        // questions while the smoke profile was pinned to gin, so the two never intersected. The
        // run planned 0, prepared a corpus, reported "0 succeeded, 0 failed", wrote an empty
        // result plus an empty BENCHMARKS.md, and exited 0 -- indistinguishable from a clean run
        // unless you happened to read the plan line.
        val root = Files.createTempDirectory("orch-empty-plan-")
        try {
            val remote = LocalGitFixture.create(root.resolve("remote"))
            val repo = CorpusRepo(id = "gin", name = "gin (fixture)", url = remote.path.toString(), pinnedTag = remote.tag, pinnedSha = remote.sha)
            val questionsDir = root.resolve("questions")
            writeQuestions(questionsDir, "excalidraw", listOf("excalidraw-q1"))

            val orchestrator = BenchmarkOrchestrator(
                agentClient = stubAgentClient(),
                judgeClient = stubJudgeClient,
                corpusRoot = root.resolve("corpus"),
                questionsDir = questionsDir,
                catalog = listOf(repo)
            )

            val thrown = shouldThrow<IllegalArgumentException> { orchestrator.run(Profile.SMOKE) }
            val message = thrown.message ?: ""
            message stringShouldContain "planned 0 agent runs"
            // The message has to name what to fix, not just that something is wrong.
            message stringShouldContain "--smoke-repo"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("smoke profile only prepares the repo it needs -- other catalog entries are never touched") {
        val root = Files.createTempDirectory("orch-scope-")
        try {
            val ginRemote = LocalGitFixture.create(root.resolve("gin-remote"))
            val otherRemote = LocalGitFixture.create(root.resolve("other-remote"))
            val ginRepo = CorpusRepo(id = "gin", name = "gin", url = ginRemote.path.toString(), pinnedTag = ginRemote.tag, pinnedSha = ginRemote.sha)
            val otherRepo = CorpusRepo(id = "untouched", name = "untouched", url = otherRemote.path.toString(), pinnedTag = otherRemote.tag, pinnedSha = otherRemote.sha)
            val questionsDir = root.resolve("questions")
            writeQuestions(questionsDir, "gin", listOf("gin-q1", "gin-q2", "gin-q3"))
            writeQuestions(questionsDir, "untouched", listOf("untouched-q1", "untouched-q2", "untouched-q3"))

            val corpusRoot = root.resolve("corpus")
            val orchestrator = BenchmarkOrchestrator(
                agentClient = stubAgentClient(),
                judgeClient = stubJudgeClient,
                corpusRoot = corpusRoot,
                questionsDir = questionsDir,
                catalog = listOf(ginRepo, otherRepo)
            )

            val result = orchestrator.run(Profile.SMOKE)

            result.run.corpusRepos.map { it.id } shouldBe listOf("gin")
            // The "untouched" repo's working copies were never cloned -- proves corpus prep was
            // scoped down rather than run unconditionally over the whole catalog.
            corpusRoot.resolve("untouched").toFile().shouldNotExist()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("full's plan uses the identical components and sequence as smoke -- only scope differs") {
        val root = Files.createTempDirectory("orch-full-")
        try {
            val remote = LocalGitFixture.create(root.resolve("remote"))
            val repo = CorpusRepo(id = "gin", name = "gin", url = remote.path.toString(), pinnedTag = remote.tag, pinnedSha = remote.sha)
            val questionsDir = root.resolve("questions")
            writeQuestions(questionsDir, "gin", listOf("gin-q1", "gin-q2", "gin-q3"))

            val orchestrator = BenchmarkOrchestrator(
                agentClient = stubAgentClient(),
                judgeClient = stubJudgeClient,
                corpusRoot = root.resolve("corpus"),
                questionsDir = questionsDir,
                catalog = listOf(repo)
            )

            // FULL over this single-repo, three-question fixture catalog: 1 repo x 3 questions x
            // 2 arms x 4 repeats (BenchmarkConfig's default repeatsPerArm) = 24.
            val result = orchestrator.run(Profile.FULL)

            result.plannedRunCount shouldBe 24
            result.run.agentRuns shouldHaveSize 24
            result.run.questions shouldHaveSize 3 // FULL takes every matching question, not capped at 3-per-repo by coincidence
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("resilience: one agent run throwing marks it failed and the suite continues to completion") {
        val root = Files.createTempDirectory("orch-resilience-")
        try {
            val remote = LocalGitFixture.create(root.resolve("remote"))
            val repo = CorpusRepo(id = "gin", name = "gin", url = remote.path.toString(), pinnedTag = remote.tag, pinnedSha = remote.sha)
            val questionsDir = root.resolve("questions")
            writeQuestions(questionsDir, "gin", listOf("gin-q1", "gin-q2", "gin-q3"))

            var invocationCount = 0
            val flakyAgentClient = AgentClient { context ->
                invocationCount++
                if (context.question.id == "gin-q2" && context.arm == Arm.WITH_TOOLS) {
                    throw RuntimeException("simulated agent failure")
                }
                AgentClientOutcome(100, 20, 1, 0, 0.001, "answer for ${context.question.id}")
            }

            val orchestrator = BenchmarkOrchestrator(
                agentClient = flakyAgentClient,
                judgeClient = stubJudgeClient,
                corpusRoot = root.resolve("corpus"),
                questionsDir = questionsDir,
                catalog = listOf(repo)
            )

            val result = orchestrator.run(Profile.SMOKE)

            result.plannedRunCount shouldBe 6
            result.failedRunCount shouldBe 1
            result.run.failedRunCount shouldBe 1 // the count reaches the result JSON/report, not just OrchestrationResult
            result.run.agentRuns shouldHaveSize 5
            // Every planned run was still attempted -- the failure did not stop the loop early.
            invocationCount shouldBe 6
            // The failed run never reached judging (no record for it to judge).
            result.run.judgeScores shouldHaveSize 5
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("AC-7a/task 14: a control-arm working copy carrying a ContextGraph artefact aborts the whole suite -- it is never diluted into failedRunCount") {
        val root = Files.createTempDirectory("orch-integrity-")
        try {
            val remote = LocalGitFixture.create(root.resolve("remote"))
            val repo = CorpusRepo(id = "gin", name = "gin", url = remote.path.toString(), pinnedTag = remote.tag, pinnedSha = remote.sha)
            val questionsDir = root.resolve("questions")
            writeQuestions(questionsDir, "gin", listOf("gin-q1", "gin-q2", "gin-q3"))

            // Simulates the contamination block mechanism failing during gin-q1's WITHOUT_TOOLS
            // run: something wrote a ContextGraph artefact into the copy that must stay
            // untouched. All WITHOUT_TOOLS runs for this repo share the same working copy (one
            // "without" checkout per repo, per CorpusPreparer), so the *next* WITHOUT_TOOLS run
            // (gin-q2's) is the one whose AC-7a pre-run gate (AgentRunner/WorkingCopyVerifier)
            // catches it.
            var invocationCount = 0
            val contaminatingClient = AgentClient { context ->
                invocationCount++
                if (context.arm == Arm.WITHOUT_TOOLS && context.question.id == "gin-q1") {
                    context.workingDir.resolve("graph.db").writeText("simulated contamination")
                }
                AgentClientOutcome(100, 20, 1, 0, 0.001, "answer for ${context.question.id}")
            }

            val orchestrator = BenchmarkOrchestrator(
                agentClient = contaminatingClient,
                judgeClient = stubJudgeClient,
                corpusRoot = root.resolve("corpus"),
                questionsDir = questionsDir,
                catalog = listOf(repo)
            )

            val thrown = shouldThrow<ContaminatedWorkingCopyException> { orchestrator.run(Profile.SMOKE) }
            thrown.artefacts shouldContain "graph.db"

            // Planned order is (question, arm) pairs, both arms per question, in question order:
            // gin-q1/WITH, gin-q1/WITHOUT (contaminates), gin-q2/WITH, gin-q2/WITHOUT (aborts
            // here) -- gin-q3 and beyond never run.
            invocationCount shouldBe 3
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("progress is observable: the plan is announced before any run executes, and a final summary line is emitted") {
        val root = Files.createTempDirectory("orch-progress-")
        try {
            val remote = LocalGitFixture.create(root.resolve("remote"))
            val repo = CorpusRepo(id = "gin", name = "gin", url = remote.path.toString(), pinnedTag = remote.tag, pinnedSha = remote.sha)
            val questionsDir = root.resolve("questions")
            writeQuestions(questionsDir, "gin", listOf("gin-q1", "gin-q2", "gin-q3"))

            val messages = mutableListOf<String>()
            val orchestrator = BenchmarkOrchestrator(
                agentClient = stubAgentClient(),
                judgeClient = stubJudgeClient,
                corpusRoot = root.resolve("corpus"),
                questionsDir = questionsDir,
                catalog = listOf(repo),
                progress = { messages += it }
            )

            orchestrator.run(Profile.SMOKE)

            val planIndex = messages.indexOfFirst { it.contains("plans 6 agent run(s)") }
            val firstRunIndex = messages.indexOfFirst { it.startsWith("[1/6]") }
            (planIndex >= 0) shouldBe true
            (firstRunIndex >= 0) shouldBe true
            (planIndex < firstRunIndex) shouldBe true
            messages.shouldContain("Agent runs complete: 6 succeeded, 0 failed of 6 planned.")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------- task 19: invalid key smoke proof

    test("task 19: an invalid API key against the real AnthropicMessagesClient no longer produces a synthetic '6 succeeded, 0 failed' -- every run fails and none is counted") {
        // Reproduces the exact bug this slice was written against, end to end: before task 19,
        // a real smoke run against an invalid key logged "Agent runs complete: 6 succeeded, 0
        // failed of 6 planned" with every record carrying inputTokens=0, outputTokens=0,
        // costUsd=0.0, finalAnswer="". This test wires the real production AnthropicMessagesClient
        // (not a stub) behind a MockEngine that always answers the way Anthropic's API answers an
        // invalid key -- 401 with an authentication_error body -- through the real
        // BenchmarkOrchestrator, and asserts the inverse of the old bug report.
        val root = Files.createTempDirectory("orch-invalid-key-")
        try {
            val remote = LocalGitFixture.create(root.resolve("remote"))
            val repo = CorpusRepo(id = "gin", name = "gin", url = remote.path.toString(), pinnedTag = remote.tag, pinnedSha = remote.sha)
            val questionsDir = root.resolve("questions")
            writeQuestions(questionsDir, "gin", listOf("gin-q1", "gin-q2", "gin-q3"))

            var callCount = 0
            val engine = MockEngine {
                callCount++
                respond(
                    content = ByteReadChannel(
                        """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
                    ),
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(io.ktor.http.HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
            val invalidKeyClient = AnthropicMessagesClient(
                engine = engine,
                apiKey = "sk-ant-invalid",
                baseUrl = "http://fake-anthropic",
                retryConfig = io.contextgraph.benchmark.judge.JudgeRetryConfig(maxAttempts = 1)
            )

            val messages = mutableListOf<String>()
            val orchestrator = BenchmarkOrchestrator(
                agentClient = invalidKeyClient,
                judgeClient = stubJudgeClient,
                corpusRoot = root.resolve("corpus"),
                questionsDir = questionsDir,
                catalog = listOf(repo),
                progress = { messages += it }
            )

            val result = orchestrator.run(Profile.SMOKE)

            // The old bug: this used to be plannedRunCount succeeded, 0 failed. Now every one of
            // the 6 planned runs fails, none is silently counted as a success.
            result.plannedRunCount shouldBe 6
            result.failedRunCount shouldBe 6
            result.run.failedRunCount shouldBe 6
            result.run.agentRuns shouldHaveSize 0
            callCount shouldBe 6 // one HTTP attempt per planned run -- 401 is never retried
            messages.shouldContain("Agent runs complete: 0 succeeded, 6 failed of 6 planned.")
            messages.none { it.contains("6 succeeded, 0 failed") } shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("task 19: AnthropicMessagesClient's failure carries Anthropic's own error.type/message, not a bare 'request failed'") {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(
                    """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
                ),
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(io.ktor.http.HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = AnthropicMessagesClient(engine = engine, apiKey = "sk-ant-invalid", baseUrl = "http://fake-anthropic")
        val runner = io.contextgraph.benchmark.runner.AgentRunner(client)
        val workingDir = Files.createTempDirectory("anthropic-401-message-")

        val exception = shouldThrow<AgentCallFailedException> {
            runner.run(
                io.contextgraph.benchmark.model.Question(
                    id = "q1", repoId = "repo1", text = "question text",
                    category = io.contextgraph.benchmark.model.QuestionCategory.GRAPH_HEAVY, goldFacts = emptyList()
                ),
                Arm.WITHOUT_TOOLS,
                workingDir,
                repeatIndex = 0,
                config = io.contextgraph.benchmark.model.BenchmarkConfig()
            )
        }

        exception.message stringShouldContain "authentication_error"
        exception.message stringShouldContain "invalid x-api-key"
    }

    test("agentClientKindOf: AnthropicMessagesClient and OpenAiChatCompletionsClient (the two production AgentClients) are REAL, anything else is SYNTHETIC") {
        agentClientKindOf(AnthropicMessagesClient()) shouldBe AgentClientKind.REAL
        agentClientKindOf(OpenAiChatCompletionsClient()) shouldBe AgentClientKind.REAL
        agentClientKindOf(stubAgentClient()) shouldBe AgentClientKind.SYNTHETIC
        agentClientKindOf(AgentClient { AgentClientOutcome(0, 0, 0, 0, 0.0, "") }) shouldBe AgentClientKind.SYNTHETIC
    }

    // ------------------------------------------------------------- task 18: judge resilience

    test("task 18: a checkpoint carrying every agent run is handed to onAgentRunsComplete before any judge call happens") {
        val root = Files.createTempDirectory("orch-checkpoint-")
        try {
            val remote = LocalGitFixture.create(root.resolve("remote"))
            val repo = CorpusRepo(id = "gin", name = "gin", url = remote.path.toString(), pinnedTag = remote.tag, pinnedSha = remote.sha)
            val questionsDir = root.resolve("questions")
            writeQuestions(questionsDir, "gin", listOf("gin-q1", "gin-q2", "gin-q3"))

            var judgeCallCount = 0
            val countingJudgeClient = object : JudgeClient {
                override suspend fun scoreFacts(input: io.contextgraph.benchmark.judge.JudgeInput, judgeModel: String): List<FactScore> {
                    judgeCallCount++
                    return input.goldFacts.map { FactScore(it.id, hit = true) }
                }
            }
            var checkpoint: io.contextgraph.benchmark.model.BenchmarkRun? = null
            val orchestrator = BenchmarkOrchestrator(
                agentClient = stubAgentClient(),
                judgeClient = countingJudgeClient,
                corpusRoot = root.resolve("corpus"),
                questionsDir = questionsDir,
                catalog = listOf(repo),
                onAgentRunsComplete = { run ->
                    // The checkpoint always arrives with zero judge calls made yet -- this is the
                    // whole point of the seam.
                    judgeCallCount shouldBe 0
                    checkpoint = run
                }
            )

            val result = orchestrator.run(Profile.SMOKE)

            checkpoint shouldNotBe null
            checkpoint!!.agentRuns shouldHaveSize 6
            checkpoint!!.judgeScores shouldHaveSize 0
            checkpoint!!.judgingComplete shouldBe false
            checkpoint!!.summary shouldBe null
            checkpoint!!.runId shouldBe result.run.runId // same run, same file path once written
            // And the run completes normally afterwards -- the checkpoint is a snapshot, not a halt.
            result.run.judgingComplete shouldBe true
            result.run.judgeScores shouldHaveSize 6
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("task 18: one judge call throwing marks that answer unscored and counted, without sinking the run") {
        val root = Files.createTempDirectory("orch-judge-resilience-")
        try {
            val remote = LocalGitFixture.create(root.resolve("remote"))
            val repo = CorpusRepo(id = "gin", name = "gin", url = remote.path.toString(), pinnedTag = remote.tag, pinnedSha = remote.sha)
            val questionsDir = root.resolve("questions")
            writeQuestions(questionsDir, "gin", listOf("gin-q1", "gin-q2", "gin-q3"))

            val flakyJudgeClient = object : JudgeClient {
                override suspend fun scoreFacts(input: io.contextgraph.benchmark.judge.JudgeInput, judgeModel: String): List<FactScore> {
                    if (input.questionText.contains("gin-q2")) {
                        throw RuntimeException("simulated transient judge failure")
                    }
                    return input.goldFacts.map { FactScore(it.id, hit = true) }
                }
            }

            val orchestrator = BenchmarkOrchestrator(
                agentClient = stubAgentClient(),
                judgeClient = flakyJudgeClient,
                corpusRoot = root.resolve("corpus"),
                questionsDir = questionsDir,
                catalog = listOf(repo)
            )

            val result = orchestrator.run(Profile.SMOKE)

            // gin-q2 runs in both arms -- both its judge calls fail (2 of 6), the rest score fine.
            result.failedJudgeCount shouldBe 2
            result.run.failedJudgeCount shouldBe 2
            result.run.agentRuns shouldHaveSize 6 // every agent run still recorded
            result.run.judgeScores shouldHaveSize 4 // only the unaffected answers got a score
            result.run.judgingComplete shouldBe true // judging still ran to completion overall
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("task 18: every judge call failing aborts the run loudly, after the checkpoint already reached the caller") {
        val root = Files.createTempDirectory("orch-judge-total-failure-")
        try {
            val remote = LocalGitFixture.create(root.resolve("remote"))
            val repo = CorpusRepo(id = "gin", name = "gin", url = remote.path.toString(), pinnedTag = remote.tag, pinnedSha = remote.sha)
            val questionsDir = root.resolve("questions")
            writeQuestions(questionsDir, "gin", listOf("gin-q1", "gin-q2", "gin-q3"))

            val alwaysFailingJudgeClient = object : JudgeClient {
                override suspend fun scoreFacts(input: io.contextgraph.benchmark.judge.JudgeInput, judgeModel: String): List<FactScore> =
                    throw RuntimeException("simulated total judge outage")
            }

            var checkpoint: io.contextgraph.benchmark.model.BenchmarkRun? = null
            val orchestrator = BenchmarkOrchestrator(
                agentClient = stubAgentClient(),
                judgeClient = alwaysFailingJudgeClient,
                corpusRoot = root.resolve("corpus"),
                questionsDir = questionsDir,
                catalog = listOf(repo),
                onAgentRunsComplete = { checkpoint = it }
            )

            val thrown = shouldThrow<AllJudgeCallsFailedException> { orchestrator.run(Profile.SMOKE) }

            thrown.failedJudgeCount shouldBe 6
            thrown.totalAgentRuns shouldBe 6
            // The expensive agent-run batch survived the throw: the checkpoint already reached
            // the caller, explicitly marked incomplete, before this exception was ever raised.
            checkpoint shouldNotBe null
            checkpoint!!.agentRuns shouldHaveSize 6
            checkpoint!!.judgingComplete shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
