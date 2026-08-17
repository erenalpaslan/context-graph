package io.contextgraph.benchmark.model

import io.contextgraph.core.ContextGraphConfig
import kotlinx.serialization.Serializable

/**
 * Indexing cost for one corpus repo (AC-2), kept in its own collection so it
 * never gets mixed into query-time [AgentRunRecord] metrics. [config] is the
 * `ContextGraphConfig` the indexing step actually used (`litellm.enabled`
 * expected to be `false` per Q1) — reusing core's config type rather than
 * duplicating it.
 */
@Serializable
data class IngestRecord(
    val repoId: String,
    val durationMillis: Long,
    val tokensUsed: Long,
    val costUsd: Double,
    val config: ContextGraphConfig
)
