package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * Configuration values that profiles can vary — never hard-code the tool
 * call ceiling, repeat count, or model choice elsewhere in the suite.
 * Defaults match the spec's answers to Q1/Q2: ceiling 40, 4 repeats per arm,
 * agent Sonnet 5 / judge Opus 5.
 */
@Serializable
data class BenchmarkConfig(
    val toolCallCeiling: Int = 40,
    val repeatsPerArm: Int = 4,
    val models: ModelConfig = ModelConfig()
)
