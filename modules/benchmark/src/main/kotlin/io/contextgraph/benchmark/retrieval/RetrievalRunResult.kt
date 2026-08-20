package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory
import kotlinx.serialization.Serializable

/**
 * One side's (ContextGraph or ripgrep) measured outcome for one question: the ranked file list
 * it actually produced, plus precision@k/recall@k (keyed by the `k` values the run was
 * configured with) and reciprocal-rank derived from it via [RetrievalMetrics].
 */
@Serializable
data class SideResult(
    val rankedFiles: List<String>,
    val precisionAtK: Map<Int, Double>,
    val recallAtK: Map<Int, Double>,
    val reciprocalRank: Double
)

/**
 * One question's full retrieval measurement: both sides, run against the same
 * [expectedFiles] (AC-23), scored with the same metrics (AC-24). [contextGraph] is `null` only
 * when that repo's WITH index failed [io.contextgraph.benchmark.corpus.IndexIntegrityGate] at
 * measurement time (see [RetrievalRun.skippedRepos]) -- [ripgrep] is never null: the WITHOUT
 * working copy it runs against is independent of the WITH copy's indexing state, so a repo whose
 * index is incomplete or still being built still yields a real, usable ripgrep measurement.
 */
@Serializable
data class RetrievalRunResult(
    val questionId: String,
    val repoId: String,
    val category: QuestionCategory,
    val expectedFiles: List<String>,
    val ripgrepQueryTokens: List<String>,
    val contextGraph: SideResult?,
    val ripgrep: SideResult
)

/** A repo this run could not fully or partially measure, and why -- never a silent omission. */
@Serializable
data class SkippedRepo(val repoId: String, val reason: String)
