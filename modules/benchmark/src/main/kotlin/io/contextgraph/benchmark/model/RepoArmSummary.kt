package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * A repo-level breakdown (task 06's "repo bazında" requirement): every
 * [AgentRunRecord] for every question belonging to [repoId], pooled per
 * [arm] and re-aggregated — not a median of the per-question medians.
 */
@Serializable
data class RepoArmSummary(
    val repoId: String,
    val arm: Arm,
    val metrics: AggregatedMetrics
)
