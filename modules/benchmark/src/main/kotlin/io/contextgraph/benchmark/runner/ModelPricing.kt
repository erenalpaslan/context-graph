package io.contextgraph.benchmark.runner

/** Standard (non-introductory) $/million-token rates, current as of this slice's writing. */
private data class Rate(val inputPerMillion: Double, val outputPerMillion: Double)

private val RATES: Map<String, Rate> = mapOf(
    "claude-sonnet-5" to Rate(inputPerMillion = 3.00, outputPerMillion = 15.00),
    "claude-opus-5" to Rate(inputPerMillion = 5.00, outputPerMillion = 25.00),
    "claude-haiku-4-5" to Rate(inputPerMillion = 1.00, outputPerMillion = 5.00)
)

/**
 * Converts a run's token counts into AC-10's `costUsd` and AC-21's break-even input.
 * Deliberately a lookup table rather than something the caller has to keep supplying --
 * [io.contextgraph.benchmark.model.BenchmarkConfig.models] names a model, this turns that name
 * into a price.
 *
 * A model not in [RATES] throws rather than silently substituting another model's rate: a wrong
 * `costUsd` is worse than a crashed run here, because a crash is visible and a wrong number
 * quietly corrupts AC-21's break-even calculation with no signal that anything was off. It was
 * previously a silent fallback to the agent-tier rate -- harmless only by coincidence, since both
 * [io.contextgraph.benchmark.model.ModelConfig] defaults happen to be in this table; that
 * coincidence stops holding the moment a caller passes a typo'd or newly-added model string.
 */
object ModelPricing {
    fun costUsd(model: String, inputTokens: Long, outputTokens: Long): Double {
        val rate = RATES[model] ?: throw IllegalArgumentException(
            "No pricing entry for model '$model' -- refusing to guess a costUsd " +
                "(would silently corrupt AC-21's break-even calculation). Add a Rate for it to " +
                "ModelPricing.RATES."
        )
        return computeCost(rate, inputTokens, outputTokens)
    }

    /**
     * Same computation as [costUsd], but for a caller (task 20's OpenAI client) that must keep
     * running with an unpriced model rather than throw: returns `null` instead of a computed
     * value when [model] has no [RATES] entry. The `null` is meant to travel all the way to
     * [io.contextgraph.benchmark.model.AgentRunRecord.costUsd] and be reported as "not recorded",
     * never coerced to `0.0` -- a real `0.0` would misreport a break-even calculation as if the
     * run were genuinely free.
     */
    fun costUsdOrNull(model: String, inputTokens: Long, outputTokens: Long): Double? =
        RATES[model]?.let { rate -> computeCost(rate, inputTokens, outputTokens) }

    /** Whether [model] has a [RATES] entry -- the same check [costUsdOrNull] makes, exposed for callers (e.g. the report generator) that need to explain a missing cost rather than just tolerate it. */
    fun hasRate(model: String): Boolean = model in RATES

    private fun computeCost(rate: Rate, inputTokens: Long, outputTokens: Long): Double =
        (inputTokens / 1_000_000.0) * rate.inputPerMillion +
            (outputTokens / 1_000_000.0) * rate.outputPerMillion
}
