package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/** Hit/miss verdict for one gold fact, as part of a [JudgeScore]. */
@Serializable
data class FactScore(
    val factId: String,
    val hit: Boolean
)
