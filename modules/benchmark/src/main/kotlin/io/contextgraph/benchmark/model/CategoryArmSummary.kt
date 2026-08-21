package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * A category-level breakdown (task 06's "kategori bazında" requirement):
 * every [AgentRunRecord] for every question of [category] (across all
 * repos), pooled per [arm] and re-aggregated.
 */
@Serializable
data class CategoryArmSummary(
    val category: QuestionCategory,
    val arm: Arm,
    val metrics: AggregatedMetrics
)
