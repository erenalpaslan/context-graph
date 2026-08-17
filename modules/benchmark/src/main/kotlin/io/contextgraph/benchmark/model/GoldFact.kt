package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * A single gold key-fact a correct answer must contain. [evidence] is
 * mandatory (AC-4) — there is no way to construct a `GoldFact` without it.
 */
@Serializable
data class GoldFact(
    val id: String,
    val statement: String,
    val evidence: Evidence
)
