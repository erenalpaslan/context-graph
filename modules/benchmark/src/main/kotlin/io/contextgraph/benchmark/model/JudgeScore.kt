package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * A judge's structured verdict on one [AgentRunRecord]'s
 * [AgentRunRecord.finalAnswer], scored against that question's gold facts.
 * [judgeModel] distinguishes the primary judge from the second, independent
 * model used in slice 05's kappa validation mode, so a single run can carry
 * more than one `JudgeScore` (one per judge model that scored it).
 */
@Serializable
data class JudgeScore(
    val id: String,
    val runId: String,
    val judgeModel: String,
    val factScores: List<FactScore>,
    val accuracyScore: Double,
    /**
     * Set-valued questions only: how much of what the answer returned was correct.
     *
     * Recall alone (which [accuracyScore] reduces to on key-fact questions) rewards saying more,
     * and a benchmark scored only on recall quietly favours whichever arm reads more files and
     * writes longer answers -- the opposite of what a graph offers. Null where the question is not
     * set-valued, so absence never reads as zero.
     */
    val precision: Double? = null,
    /** Set-valued questions only: how much of the true set the answer found. */
    val recall: Double? = null,
    /** Set-valued questions only: how many elements the answer returned, correct or not. */
    val returnedCount: Int? = null
)
