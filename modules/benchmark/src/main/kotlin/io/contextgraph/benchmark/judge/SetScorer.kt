package io.contextgraph.benchmark.judge

import io.contextgraph.benchmark.model.AgentRunRecord
import io.contextgraph.benchmark.model.FactScore
import io.contextgraph.benchmark.model.JudgeScore
import io.contextgraph.benchmark.model.Question
import java.util.UUID

/** Thrown when a set-valued answer carries no parseable answer set at all. */
class UnparseableAnswerSetException(message: String) : Exception(message)

/**
 * Scores a set-valued question by comparing the answer's set against a mechanically derived one.
 *
 * No model runs here, which is the point. Every previous measurement had an LLM judge between the
 * answer and the number, and while its verdicts turned out stable, it also scored an empty string
 * 0.5 once -- a grader that can do that is a variance source the comparison does not need. A set
 * question has an exact answer, so it gets an exact score.
 *
 * F1 rather than recall. The key-fact scoring this replaces asked only "did the answer state each
 * of these facts", which cannot punish a wrong claim, and so systematically favours the arm that
 * returns more. That is the wrong incentive for a benchmark whose subject claims to return
 * *precise* relations: a tool that hands back 2,000 candidate lines to cover 79 real ones should
 * not outscore one that returns the 79.
 */
object SetScorer {

    fun score(question: Question, record: AgentRunRecord): JudgeScore {
        val expected = requireNotNull(question.expectedSet) {
            "SetScorer called for ${question.id}, which carries no expectedSet"
        }.map(::normalize).toSet()

        val returned = parseAnswerSet(record.finalAnswer)
            ?: throw UnparseableAnswerSetException(
                "${question.id}/${record.arm} produced no parseable answer set. An answer that " +
                    "cannot be read is not a wrong answer, it is an unscored one, and counting it " +
                    "as zero would blame the arm for a formatting failure. Answer began: " +
                    record.finalAnswer.take(200)
            )

        val truePositives = returned.count { it in expected }
        val precision = if (returned.isEmpty()) 0.0 else truePositives.toDouble() / returned.size
        val recall = if (expected.isEmpty()) 0.0 else truePositives.toDouble() / expected.size
        val f1 = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)

        return JudgeScore(
            id = UUID.randomUUID().toString(),
            runId = record.id,
            // Recorded as the scorer rather than a model name: a reader must be able to tell at a
            // glance that no model graded this, without cross-referencing the config.
            judgeModel = SCORER_NAME,
            factScores = expected.sorted().map { FactScore(it, hit = it in returned) },
            accuracyScore = f1,
            precision = precision,
            recall = recall,
            returnedCount = returned.size
        )
    }

    const val SCORER_NAME: String = "exact-set-comparison"

    /**
     * Pulls the answer set out of the final answer.
     *
     * Deliberately tolerant about *where* the list is (agents wrap it in prose, fences, or both)
     * and strict about *what counts as an element*: only `path:line`. A loose parser would turn a
     * sentence into set members and score noise.
     */
    internal fun parseAnswerSet(answer: String): Set<String>? {
        val elements = ELEMENT.findAll(answer).map { normalize(it.value) }.toSet()
        return elements.ifEmpty { null }
    }

    /**
     * Paths are compared leading-`./`-free and separator-normalised, so an answer is not marked
     * wrong for a cosmetic difference in how it wrote the same location.
     */
    private fun normalize(raw: String): String =
        raw.trim().trim('`', '"', '\'', ',').removePrefix("./").replace('\\', '/')

    private val ELEMENT = Regex("""[\w./-]+\.(?:java|kt|ts|tsx|js|jsx|go):\d+""")
}
