package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/** Fixed model choices (AC-12): the agent model is deliberately different from the judge model. */
@Serializable
data class ModelConfig(
    val agentModel: String = "claude-sonnet-5",
    val judgeModel: String = "claude-opus-5"
)
