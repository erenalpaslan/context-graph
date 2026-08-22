package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory
import kotlinx.serialization.Serializable

/**
 * Mean precision@k/recall@k and MRR for one side, over [measuredCount] questions. [measuredCount]
 * can be smaller than the enclosing [RetrievalAggregate.questionCount] for either of the two
 * *graph* sides: a question whose repo's index failed integrity verification contributes no
 * ContextGraph [SideResult], and a question whose `codegraph explore` call timed out or errored
 * contributes no CodeGraph one (see [RetrievalRunResult]). Such a question is excluded from that
 * side's average rather than silently counted as a zero -- that would understate the tool by
 * blaming it for an infrastructure problem instead of a retrieval one. The exclusion is visible
 * rather than implied: a [measuredCount] below [RetrievalAggregate.questionCount] is exactly the
 * signal that a denominator differs, and the report prints both numbers side by side.
 *
 * `ripgrep`'s [measuredCount] always equals [RetrievalAggregate.questionCount].
 */
@Serializable
data class SideAggregate(
    val measuredCount: Int,
    val meanPrecisionAtK: Map<Int, Double>,
    val meanRecallAtK: Map<Int, Double>,
    val mrr: Double
)

/** Every side's [SideAggregate] over the same set of questions, plus how many questions that set had. */
@Serializable
data class RetrievalAggregate(
    val questionCount: Int,
    val contextGraph: SideAggregate,
    val ripgrep: SideAggregate,
    /**
     * `null` for a run that measured no CodeGraph side at all -- an archived schema-v1 result, or
     * a run where the binary or the index was absent. Distinct from a present [SideAggregate]
     * whose [SideAggregate.measuredCount] is 0, which means CodeGraph *was* in the run and
     * measured nothing.
     *
     * Additive with a default because this type is nested inside [RetrievalSummary], which is
     * nested inside [RetrievalRun.summary]: a required field here would fail to decode all four
     * archived results, not just their top level.
     */
    val codeGraph: SideAggregate? = null
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
