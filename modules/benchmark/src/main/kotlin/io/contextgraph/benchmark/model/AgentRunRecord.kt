package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * One measured agent run: one question, one arm, one repeat. Produced by
 * slice 04's runner. [id] is the join key [JudgeScore.runId] points back to;
 * slice 06 groups these by (questionId, arm) across repeats to compute
 * medians and variance.
 *
 * [costUsd] is `null`, never `0.0`, when the run's model has no
 * [io.contextgraph.benchmark.runner.ModelPricing] entry (task 20: `gpt-4.1-nano` has none) --
 * `0.0` would read as a real measurement and silently corrupt AC-21's break-even calculation.
 * `io.contextgraph.benchmark.stats.BenchmarkStats` drops nulls before aggregating rather than
 * treating them as zero-cost runs.
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
    val costUsd: Double?,
    val finalAnswer: String,
    val hitCeiling: Boolean,
    val contaminated: Boolean,
    val cliInvocationAttempts: Int,
    /**
     * How many times each named tool was called, or empty for backends that do not report names.
     *
     * This exists because of a question a run could not answer about itself. The first Claude Code
     * run scored both arms 1.0 -- an apparently clean "ContextGraph makes no difference" -- and
     * nothing in the record could distinguish that from "the MCP server never attached, so the
     * WITH_TOOLS arm was silently a second control arm". A headline of no-difference is only worth
     * anything if the tools were demonstrably offered *and used*; with only a total call count,
     * the most important negative result the suite can produce is unfalsifiable.
     *
     * Claude Code names MCP tools `mcp__<server>__<tool>`, so ContextGraph's own usage is
     * countable from these keys -- see [contextGraphToolCalls].
     */
    val toolNameCounts: Map<String, Int> = emptyMap()
) {
    /**
     * Calls that went to ContextGraph's MCP server. Zero in a WITH_TOOLS run means the arm did not
     * actually exercise the product, whatever its score says.
     */
    val contextGraphToolCalls: Int
        get() = toolNameCounts.entries.filter { it.key.startsWith(CONTEXTGRAPH_TOOL_PREFIX) }.sumOf { it.value }

    companion object {
        const val CONTEXTGRAPH_TOOL_PREFIX: String = "mcp__contextgraph__"
    }
}
