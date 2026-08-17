package io.contextgraph.benchmark.model

import io.contextgraph.core.ContextGraphConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.io.path.createTempDirectory

private fun sampleRun(): BenchmarkRun {
    val fact1 = GoldFact(id = "f1", statement = "Foo calls Bar", evidence = Evidence("src/Foo.kt", 12))
    val fact2 = GoldFact(id = "f2", statement = "Bar implements Baz", evidence = Evidence("src/Bar.kt", 34))
    val question = Question(
        id = "q1",
        repoId = "excalidraw",
        text = "What breaks if Foo's signature changes?",
        category = QuestionCategory.GRAPH_HEAVY,
        goldFacts = listOf(fact1, fact2)
    )
    val agentRun = AgentRunRecord(
        id = "run-1",
        questionId = "q1",
        arm = Arm.WITH_TOOLS,
        repeatIndex = 0,
        inputTokens = 1000,
        outputTokens = 200,
        toolCallCount = 5,
        fileReadCount = 3,
        wallClockMillis = 42_000,
        costUsd = 0.12,
        finalAnswer = "Foo's callers in Bar and Baz would break.",
        hitCeiling = false,
        contaminated = false,
        cliInvocationAttempts = 0
    )
    val judgeScore = JudgeScore(
        id = "score-1",
        runId = "run-1",
        judgeModel = "claude-opus-5",
        factScores = listOf(FactScore("f1", true), FactScore("f2", false)),
        accuracyScore = 0.5
    )
    val ingest = IngestRecord(
        repoId = "excalidraw",
        durationMillis = 60_000,
        tokensUsed = 5000,
        costUsd = 0.05,
        config = ContextGraphConfig()
    )
    val corpusRepo = CorpusRepo(
        id = "excalidraw",
        name = "excalidraw",
        url = "https://github.com/excalidraw/excalidraw",
        pinnedTag = "v0.17.0",
        pinnedSha = "abc123"
    )
    return BenchmarkRun(
        runId = "run-full-sample",
        profile = Profile.FULL,
        generatedAt = Instant.parse("2026-08-17T12:00:00Z"),
        config = BenchmarkConfig(),
        corpusRepos = listOf(corpusRepo),
        questions = listOf(question),
        ingestRecords = listOf(ingest),
        agentRuns = listOf(agentRun),
        judgeScores = listOf(judgeScore)
    )
}

class BenchmarkRunSerializationTest : FunSpec({

    test("a fully populated run round-trips through JSON unchanged") {
        val run = sampleRun()
        val json = run.toJson()
        val decoded = BenchmarkRun.fromJson(json)
        decoded shouldBe run
    }

    test("schemaVersion is present in the serialized JSON") {
        val json = sampleRun().toJson()
        json.contains("\"schemaVersion\": 1") shouldBe true
    }

    test("an empty (zero-question) run round-trips end to end via the filesystem") {
        val run = BenchmarkRun(
            runId = "run-empty",
            profile = Profile.SMOKE,
            generatedAt = Instant.parse("2026-08-17T12:00:00Z"),
            config = BenchmarkConfig()
        )
        val dir = createTempDirectory("benchmark-result-test")
        val written = run.writeTo(dir)

        val readBack = BenchmarkRun.readFrom(written)

        readBack shouldBe run
        readBack.questions.isEmpty() shouldBe true
        readBack.agentRuns.isEmpty() shouldBe true
        readBack.schemaVersion shouldBe BenchmarkRun.SCHEMA_VERSION
    }

    test("default BenchmarkConfig matches the spec's Q1/Q2 answers") {
        val config = BenchmarkConfig()
        config.toolCallCeiling shouldBe 40
        config.repeatsPerArm shouldBe 4
        config.models.agentModel shouldBe "claude-sonnet-5"
        config.models.judgeModel shouldBe "claude-opus-5"
    }
})
