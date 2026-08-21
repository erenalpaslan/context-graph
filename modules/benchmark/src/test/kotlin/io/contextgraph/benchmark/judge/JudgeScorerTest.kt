package io.contextgraph.benchmark.judge

import io.contextgraph.benchmark.model.Evidence
import io.contextgraph.benchmark.model.FactScore
import io.contextgraph.benchmark.model.GoldFact
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

/**
 * A fake [JudgeClient] is exactly the seam the task note calls for: "the
 * scorer's LLM call kept separable enough to be tested without calling the
 * real model." It also proves, at the type level, that a [JudgeScorer]
 * cannot hand the client anything but a [JudgeInput] -- see [JudgeClient]'s
 * signature.
 */
private class FakeJudgeClient(private val verdicts: Map<String, Boolean>) : JudgeClient {
    var lastInput: JudgeInput? = null
    var lastModel: String? = null

    override suspend fun scoreFacts(input: JudgeInput, judgeModel: String): List<FactScore> {
        lastInput = input
        lastModel = judgeModel
        return input.goldFacts.map { FactScore(it.id, verdicts[it.id] ?: false) }
    }
}

/**
 * A judge model omitting a fact id from its response is an ordinary
 * occurrence (e.g. a truncated or slightly malformed JSON list), not an
 * exotic one. Silently dropping the missing id -- rather than reconciling
 * against the gold-fact set -- shrinks the denominator and inflates the
 * score. The gold-fact set on [Question], not whatever the client happened
 * to return, is always the source of truth for both the fact list and the
 * accuracy denominator.
 */
private class IncompleteJudgeClient(private val hitsByFactId: Map<String, Boolean>) : JudgeClient {
    override suspend fun scoreFacts(input: JudgeInput, judgeModel: String): List<FactScore> =
        input.goldFacts
            .filter { it.id in hitsByFactId }
            .map { FactScore(it.id, hitsByFactId.getValue(it.id)) }
}

class JudgeScorerTest : FunSpec({

    val question = Question(
        id = "q1",
        repoId = "excalidraw",
        text = "What happens on save?",
        category = QuestionCategory.GRAPH_HEAVY,
        goldFacts = listOf(
            GoldFact(id = "f1", statement = "Fact one", evidence = Evidence("src/Foo.kt", 1)),
            GoldFact(id = "f2", statement = "Fact two", evidence = Evidence("src/Bar.kt", 2)),
            GoldFact(id = "f3", statement = "Fact three", evidence = Evidence("src/Baz.kt", 3))
        )
    )

    test("scores per-fact hit/miss and derives an accuracy score from the hits, not free text") {
        val client = FakeJudgeClient(mapOf("f1" to true, "f2" to true, "f3" to false))
        val scorer = JudgeScorer(client)

        val score = runBlocking {
            scorer.score(runId = "run-1", judgeModel = "claude-opus-5", question = question, answerText = "the answer")
        }

        score.factScores.shouldContainExactly(
            FactScore("f1", true),
            FactScore("f2", true),
            FactScore("f3", false)
        )
        score.accuracyScore shouldBe (2.0 / 3.0 plusOrMinus 1e-9)
    }

    test("records which judge model produced the score (AC-12)") {
        val client = FakeJudgeClient(mapOf("f1" to true, "f2" to true, "f3" to true))
        val scorer = JudgeScorer(client)

        val score = runBlocking {
            scorer.score(runId = "run-1", judgeModel = "claude-opus-5", question = question, answerText = "the answer")
        }

        score.judgeModel shouldBe "claude-opus-5"
        score.runId shouldBe "run-1"
    }

    test("takes the judge model from its caller, never a hard-coded name") {
        val client = FakeJudgeClient(mapOf("f1" to true, "f2" to true, "f3" to true))
        val scorer = JudgeScorer(client)

        val score = runBlocking {
            scorer.score(runId = "run-1", judgeModel = "some-other-model", question = question, answerText = "the answer")
        }

        score.judgeModel shouldBe "some-other-model"
    }

    test("the client only ever receives the blind JudgeInput built from the question and answer") {
        val client = FakeJudgeClient(mapOf("f1" to true, "f2" to true, "f3" to true))
        val scorer = JudgeScorer(client)

        runBlocking {
            scorer.score(runId = "run-1", judgeModel = "claude-opus-5", question = question, answerText = "SENTINEL_ANSWER")
        }

        client.lastInput shouldBe JudgeInput.from(question, "SENTINEL_ANSWER")
    }

    test("a response missing a gold fact id is reconciled as a miss, not dropped from the denominator") {
        // Only f1 and f3 are answered; f2 is silently omitted by the client.
        val client = IncompleteJudgeClient(mapOf("f1" to true, "f3" to true))
        val scorer = JudgeScorer(client)

        val score = runBlocking {
            scorer.score(runId = "run-1", judgeModel = "claude-opus-5", question = question, answerText = "the answer")
        }

        // Full gold-fact set present in the output, with the missing one scored as a miss --
        // NOT a 2-fact result with an inflated 2/2 = 1.0 score.
        score.factScores.shouldContainExactly(
            FactScore("f1", true),
            FactScore("f2", false),
            FactScore("f3", true)
        )
        score.accuracyScore shouldBe (2.0 / 3.0 plusOrMinus 1e-9)
    }

    test("a response missing every gold fact id scores zero, not an empty (and vacuously 0.0-but-hollow) list") {
        val client = IncompleteJudgeClient(emptyMap())
        val scorer = JudgeScorer(client)

        val score = runBlocking {
            scorer.score(runId = "run-1", judgeModel = "claude-opus-5", question = question, answerText = "the answer")
        }

        score.factScores.shouldContainExactly(
            FactScore("f1", false),
            FactScore("f2", false),
            FactScore("f3", false)
        )
        score.accuracyScore shouldBe (0.0 plusOrMinus 1e-9)
    }
})
