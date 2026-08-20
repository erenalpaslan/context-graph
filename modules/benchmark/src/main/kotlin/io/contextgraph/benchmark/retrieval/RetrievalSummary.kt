package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory
import kotlinx.serialization.Serializable

/**
 * Mean precision@k/recall@k and MRR for one side, over [measuredCount] questions. [measuredCount]
 * can be smaller than the enclosing [RetrievalAggregate.questionCount] for the `contextGraph`
 * side specifically: a question whose repo's index failed integrity verification contributes no
 * [SideResult] at all (see [RetrievalRunResult.contextGraph]) and is excluded from this average
 * rather than silently counted as a zero -- that would understate ContextGraph's real score by
 * blaming it for an indexing problem, not a retrieval one. `ripgrep`'s [measuredCount] always
 * equals [RetrievalAggregate.questionCount].
 */
@Serializable
data class SideAggregate(
    val measuredCount: Int,
    val meanPrecisionAtK: Map<Int, Double>,
    val meanRecallAtK: Map<Int, Double>,
    val mrr: Double
)

/** Both sides' [SideAggregate] over the same set of questions, plus how many questions that set had. */
@Serializable
data class RetrievalAggregate(
    val questionCount: Int,
    val contextGraph: SideAggregate,
    val ripgrep: SideAggregate
)

/**
 * AC-26: [headline] pools GRAPH_HEAVY and NEUTRAL questions only -- [negativeControl] is
 * reported completely separately, on purpose, the same separation
 * [io.contextgraph.benchmark.report.BenchmarksReportGenerator] draws for the agent-A/B axis
 * (AC-20). [byCategory] and [byRepo] give the finer breakdown AC-26 also asks for.
 *
 * Any [RetrievalAggregate] here is computed only from questions whose repo actually produced a
 * ContextGraph-side measurement (see [RetrievalRun.skippedRepos]) *for the ContextGraph side of
 * that aggregate specifically* -- [SideAggregate.mrr] and friends for `ripgrep` are computed
 * over every question in the group regardless, since the ripgrep side is never blocked by an
 * index integrity failure. See [RetrievalStats] for exactly how the two are kept from silently
 * averaging together a different denominator.
 */
@Serializable
data class RetrievalSummary(
    val headline: RetrievalAggregate,
    val negativeControl: RetrievalAggregate,
    val byCategory: Map<QuestionCategory, RetrievalAggregate>,
    val byRepo: Map<String, RetrievalAggregate>
)
