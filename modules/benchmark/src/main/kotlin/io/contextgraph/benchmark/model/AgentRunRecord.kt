package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * One measured agent run: one question, one arm, one repeat. Produced by
 * slice 04's runner. [id] is the join key [JudgeScore.runId] points back to;
 * slice 06 groups these by (questionId, arm) across repeats to compute
 * medians and variance.
 */
@Serializable
data class AgentRunRecord(
    val id: String,
    val questionId: String,
    val arm: Arm,
    val repeatIndex: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val toolCallCount: Int,
    val fileReadCount: Int,
    val wallClockMillis: Long,
    val costUsd: Double,
    val finalAnswer: String,
    val hitCeiling: Boolean,
    val contaminated: Boolean,
    val cliInvocationAttempts: Int
)
