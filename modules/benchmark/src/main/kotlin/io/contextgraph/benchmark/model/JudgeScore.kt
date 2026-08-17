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
    val accuracyScore: Double
)
