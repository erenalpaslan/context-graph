package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory
import kotlinx.serialization.Serializable

/**
 * One side's measured outcome for one question -- ContextGraph, CodeGraph or ripgrep: the ranked
 * file list it actually produced, plus precision@k/recall@k (keyed by the `k` values the run was
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
 * One question's full retrieval measurement: all three sides, each given the same raw
 * [io.contextgraph.benchmark.model.Question.text], run against the same [expectedFiles], scored
 * with the same metrics. Those three "same"s are the fairness invariants the whole comparison
 * rests on -- a number obtained by breaking one of them is worse than no number.
 *
 * Two of the three sides are nullable, for different reasons, and neither null ever means zero:
 *
 * - [contextGraph] is `null` when that repo's WITH index failed
 *   [io.contextgraph.benchmark.corpus.IndexIntegrityGate] at measurement time (see
 *   [RetrievalRun.skippedRepos]).
 * - [codeGraph] is `null` when `codegraph explore` timed out or exited non-zero for this
 *   question, or when no CodeGraph index was prepared for the repo at all. A *successful*
 *   invocation that named no files is a real zero and is recorded as an empty ranked list, not
 *   as null -- keeping those two apart is what stops a broken invocation from reading as
 *   "CodeGraph retrieves nothing", the single most damaging way this instrument could mislead.
 *
 * [ripgrep] is never null: the WITHOUT working copy it runs against is independent of either
 * tool's indexing state, so a repo whose index is incomplete or still being built still yields a
 * real, usable ripgrep measurement.
 */
@Serializable
data class RetrievalRunResult(
    val questionId: String,
    val repoId: String,
    val category: QuestionCategory,
    val expectedFiles: List<String>,
    val ripgrepQueryTokens: List<String>,
    val contextGraph: SideResult?,
    val ripgrep: SideResult,
    /**
     * Additive with a default so the four archived schema-v1 `retrieval-*.json` results still
     * decode -- a required field here would break every one of them.
     */
    val codeGraph: SideResult? = null
)

/** A repo this run could not fully or partially measure, and why -- never a silent omission. */
@Serializable
data class SkippedRepo(val repoId: String, val reason: String)
