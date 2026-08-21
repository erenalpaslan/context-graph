package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * Configuration values that profiles can vary — never hard-code the tool
 * call ceiling, repeat count, or model choice elsewhere in the suite.
 * Defaults match the spec's answers to Q1/Q2: ceiling 40, 4 repeats per arm.
 * See [ModelConfig] for the model defaults and how/why they moved twice.
 */
@Serializable
data class BenchmarkConfig(
    val toolCallCeiling: Int = 40,
    val repeatsPerArm: Int = 4,
    val models: ModelConfig = ModelConfig(),
    /**
     * Per-run spend limit for agent backends that run their own loop and therefore cannot be held
     * to [toolCallCeiling] -- the Claude Code CLI's `--max-budget-usd`. Null means unlimited.
     *
     * The unit differs from [toolCallCeiling] on purpose, because the two backends can enforce
     * different things: an in-process client counts its own tool calls, while an external agent
     * only understands a budget its own CLI implements. What AC-7 requires is that *both arms
     * get the same budget*, which holds either way; what changes is that a dollar budget is not
     * comparable to a 40-call budget, so results produced under the two are not interchangeable.
     */
    val maxBudgetUsdPerRun: Double? = null
)
