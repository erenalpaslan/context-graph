package io.contextgraph.benchmark.model

import io.contextgraph.core.ContextGraphConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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

    test("failedRunCount defaults to null (older result JSON without the field still round-trips) and round-trips when set") {
        val withoutField = sampleRun()
        withoutField.failedRunCount shouldBe null
        BenchmarkRun.fromJson(withoutField.toJson()).failedRunCount shouldBe null

        val withField = sampleRun().copy(failedRunCount = 2)
        BenchmarkRun.fromJson(withField.toJson()).failedRunCount shouldBe 2
    }

    test("agentClientKind defaults to null (older result JSON without the field still round-trips, distinct from REAL) and round-trips when set") {
        val withoutField = sampleRun()
        withoutField.agentClientKind shouldBe null
        BenchmarkRun.fromJson(withoutField.toJson()).agentClientKind shouldBe null

        val real = sampleRun().copy(agentClientKind = AgentClientKind.REAL)
        BenchmarkRun.fromJson(real.toJson()).agentClientKind shouldBe AgentClientKind.REAL

        val synthetic = sampleRun().copy(agentClientKind = AgentClientKind.SYNTHETIC)
        BenchmarkRun.fromJson(synthetic.toJson()).agentClientKind shouldBe AgentClientKind.SYNTHETIC
    }

    test("a legacy result JSON with no agentClientKind key at all still decodes, as null (not as REAL)") {
        // Simulate a result written before this field existed: remove the key entirely (not
        // just set it to null) via structured JSON editing, since the regression this guards
        // against is a missing key, not a present-but-null one, and text-level line surgery
        // would risk leaving a dangling trailing comma the parser then rejects.
        val original = Json.parseToJsonElement(sampleRun().toJson()).jsonObject
        val legacyJson = Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(original.filterKeys { it != "agentClientKind" })
        )
        legacyJson.contains("agentClientKind") shouldBe false
        BenchmarkRun.fromJson(legacyJson).agentClientKind shouldBe null
    }

    test("default BenchmarkConfig matches the spec's Q1/Q2 answers") {
        val config = BenchmarkConfig()
        config.toolCallCeiling shouldBe 40
        config.repeatsPerArm shouldBe 4
        // The agent model has moved several times for reasons outside the benchmark's control
        // (Opus 5 -> Sonnet 5 -> nano -> gpt-4.1 -> mini); ModelConfig's doc carries the full
        // chain. The last move is the load-bearing one here: this account allows only 30k TPM on
        // gpt-4.1, which a single agentic request already exceeds, versus 200k on mini.
        config.models.agentModel shouldBe "gpt-4.1-mini"
    }

    test("the judge is a different, stronger model than the agent, as AC-12 originally intended") {
        // AC-12 pinned the judge to claude-opus-5 precisely so it would differ from the agent.
        // Two provider accidents collapsed that (an Opus 5 rate_limit_error, then an invalid
        // Anthropic key), leaving agent and judge sharing one model and the "it graded its own
        // answer" objection resting entirely on AC-14's kappa run.
        //
        // The gpt-4.1 TPM ceiling restored the separation as a side effect: the agent had to move
        // to mini for headroom, while the judge -- whose calls are single and small -- had no
        // reason to follow. Asserted separately from the Q1/Q2 defaults because the judge model
        // is a *revision* of one of those answers, and the two must be free to differ.
        val config = BenchmarkConfig()
        config.models.judgeModel shouldBe "gpt-4.1"
        (config.models.judgeModel == config.models.agentModel) shouldBe false
    }
})
