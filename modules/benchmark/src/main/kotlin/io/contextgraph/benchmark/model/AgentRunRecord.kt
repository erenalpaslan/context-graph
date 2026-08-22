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
     * Calls that went to [tool]'s MCP server. Zero in a WITH_TOOLS run means the arm did not
     * actually exercise that tool, whatever its score says.
     *
     * Takes the tool rather than assuming ContextGraph, because assuming it is what published a
     * CodeGraph run as though nothing had happened: `results/codegraph-forced/BENCHMARKS.md`
     * counted `mcp__contextgraph__` calls in a run that made 23 `mcp__codegraph__codegraph_explore`
     * ones, found none, and asserted "No ContextGraph tool was ever called in this run. The two
     * arms were therefore behaviourally identical" -- of the one run that could have validated
     * this whole harness.
     */
    fun graphToolCalls(tool: io.contextgraph.benchmark.runner.GraphTool): Int =
        toolNameCounts.entries.filter { it.key.startsWith(tool.toolPrefix) }.sumOf { it.value }

    /** Every `mcp__<server>__` prefix this run actually called, which is what a missing `graphTool` is inferred from. */
    val observedMcpPrefixes: Set<String>
        get() = toolNameCounts.keys
            .filter { it.startsWith(MCP_PREFIX) }
            .mapNotNull { key ->
                val end = key.indexOf("__", MCP_PREFIX.length)
                if (end < 0) null else key.substring(0, end + 2)
            }
            .toSet()

    companion object {
        const val MCP_PREFIX: String = "mcp__"
    }
}
