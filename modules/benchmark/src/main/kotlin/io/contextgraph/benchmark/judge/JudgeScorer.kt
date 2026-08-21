package io.contextgraph.benchmark.judge

import io.contextgraph.benchmark.model.FactScore
import io.contextgraph.benchmark.model.JudgeScore
import io.contextgraph.benchmark.model.Question

/**
 * Scores one agent answer against its question's gold facts via a
 * [JudgeClient]. The judge model only ever needs to return hit/miss per
 * fact (AC-13); the accuracy score is derived here, not trusted from the
 * model's own arithmetic.
 *
 * The client's response is reconciled against [Question.goldFacts] rather
 * than trusted as complete: a model omitting a fact id from its JSON is an
 * ordinary occurrence, not an exotic one, and silently dropping it would
 * shrink the denominator and inflate the score. Any gold fact id the client
 * didn't return is scored as a miss, so [JudgeScore.factScores] always
 * covers the full gold-fact set and [JudgeScore.accuracyScore] is always
 * computed over it.
 */
class JudgeScorer(private val client: JudgeClient) {
    suspend fun score(runId: String, judgeModel: String, question: Question, answerText: String): JudgeScore {
        val input = JudgeInput.from(question, answerText)
        val returnedByFactId = client.scoreFacts(input, judgeModel).associateBy { it.factId }
        val factScores = question.goldFacts.map { fact ->
            returnedByFactId[fact.id] ?: FactScore(fact.id, hit = false)
        }
        val accuracyScore = if (factScores.isEmpty()) {
            0.0
        } else {
            factScores.count { it.hit }.toDouble() / factScores.size
        }

        return JudgeScore(
            id = "$runId:$judgeModel",
            runId = runId,
            judgeModel = judgeModel,
            factScores = factScores,
            accuracyScore = accuracyScore
        )
    }
}
