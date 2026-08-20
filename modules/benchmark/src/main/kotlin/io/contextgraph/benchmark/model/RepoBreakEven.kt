package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/** One repo's [BreakEvenResult], keyed by [repoId] so [BenchmarkSummary.breakEvenByRepo] stays a flat list. */
@Serializable
data class RepoBreakEven(
    val repoId: String,
    val result: BreakEvenResult
)
