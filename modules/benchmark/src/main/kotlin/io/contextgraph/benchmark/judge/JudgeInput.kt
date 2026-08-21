package io.contextgraph.benchmark.judge

import io.contextgraph.benchmark.model.Question
import kotlinx.serialization.Serializable

/**
 * A gold fact reduced to what the judge is allowed to see: its id (so the
 * verdict can be matched back to it) and its statement. Deliberately does
 * NOT carry [io.contextgraph.benchmark.model.GoldFact.evidence] -- the
 * `file:line` citation is a gold-set production detail (AC-4/AC-6), not
 * something the judge scores against.
 */
@Serializable
data class JudgeGoldFact(val id: String, val statement: String)

/**
 * Everything sent to the judge, and nothing else (AC-11). Question text,
 * gold fact list, answer text -- full stop. No arm label, no tool-call
 * trace, no timing, no token count: this type has no field any of those
 * could travel in on, and [JudgeInputTest] proves that on the actual
 * serialized form a real call would send.
 */
@Serializable
data class JudgeInput(
    val questionText: String,
    val goldFacts: List<JudgeGoldFact>,
    val answerText: String
) {
    companion object {
        /**
         * The only supported way to build a [JudgeInput]. Takes a
         * [Question] and a plain answer string -- deliberately not an
         * `AgentRunRecord` or an `Arm` -- so there is no parameter on this
         * factory's own signature an arm label or a metric could be smuggled
         * in through.
         */
        fun from(question: Question, answerText: String): JudgeInput =
            JudgeInput(
                questionText = question.text,
                goldFacts = question.goldFacts.map { JudgeGoldFact(it.id, it.statement) },
                answerText = answerText
            )
    }
}
