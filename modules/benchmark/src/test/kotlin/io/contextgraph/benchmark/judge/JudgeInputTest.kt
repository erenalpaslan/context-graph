package io.contextgraph.benchmark.judge

import io.contextgraph.benchmark.model.Evidence
import io.contextgraph.benchmark.model.GoldFact
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

/**
 * AC-11: the judge's input must not carry the arm's identity, tool-call
 * trace, or any metric -- only question text, gold fact list, and answer
 * text. This is proven against the actual JSON that would be serialized
 * into a real judge call, built from a [Question] whose gold facts carry
 * sentinel markers that must survive, and an answer string carrying a
 * distinct sentinel that must also survive -- while every WITH/WITHOUT-arm
 * and metric marker must not appear anywhere in the output.
 */
class JudgeInputTest : FunSpec({

    val json = Json { encodeDefaults = true }

    val question = Question(
        id = "q1",
        repoId = "excalidraw",
        text = "SENTINEL_QUESTION_TEXT",
        category = QuestionCategory.GRAPH_HEAVY,
        goldFacts = listOf(
            GoldFact(id = "f1", statement = "SENTINEL_FACT_ONE", evidence = Evidence("src/Foo.kt", 10)),
            GoldFact(id = "f2", statement = "SENTINEL_FACT_TWO", evidence = Evidence("src/Bar.kt", 20))
        )
    )
    val answerText = "SENTINEL_ANSWER_TEXT"

    test("carries only question text, gold fact statements, and answer text") {
        val input = JudgeInput.from(question, answerText)

        input.questionText shouldBe "SENTINEL_QUESTION_TEXT"
        input.goldFacts shouldBe listOf(
            JudgeGoldFact("f1", "SENTINEL_FACT_ONE"),
            JudgeGoldFact("f2", "SENTINEL_FACT_TWO")
        )
        input.answerText shouldBe "SENTINEL_ANSWER_TEXT"
    }

    test("the serialized form the judge would actually receive contains no arm label or metric") {
        val input = JudgeInput.from(question, answerText)

        val serialized = json.encodeToString(JudgeInput.serializer(), input)

        // The content that must survive.
        serialized shouldContain "SENTINEL_QUESTION_TEXT"
        serialized shouldContain "SENTINEL_FACT_ONE"
        serialized shouldContain "SENTINEL_ANSWER_TEXT"

        // Arm identity must never appear.
        serialized shouldNotContain "WITH_TOOLS"
        serialized shouldNotContain "WITHOUT_TOOLS"
        serialized shouldNotContain "arm"

        // No tool-call trace, timing, token count, cost, or contamination/ceiling flags.
        serialized shouldNotContain "toolCallCount"
        serialized shouldNotContain "fileReadCount"
        serialized shouldNotContain "wallClockMillis"
        serialized shouldNotContain "inputTokens"
        serialized shouldNotContain "outputTokens"
        serialized shouldNotContain "costUsd"
        serialized shouldNotContain "hitCeiling"
        serialized shouldNotContain "contaminated"
        serialized shouldNotContain "cliInvocationAttempts"
        serialized shouldNotContain "repeatIndex"

        // Evidence (file:line) is a gold-fact production detail, not part of
        // the judge's blind input either.
        serialized shouldNotContain "src/Foo.kt"
    }
})
