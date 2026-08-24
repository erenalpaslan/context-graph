package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory
import kotlinx.serialization.Serializable

/**
 * Mean precision@k/recall@k and MRR for one side, over [measuredCount] questions. [measuredCount]
 * can be smaller than the enclosing [RetrievalAggregate.questionCount] for any side whose
 * per-question [SideResult] is nullable: a question whose repo's index failed integrity
 * verification contributes no ContextGraph one, a question whose `codegraph explore` call timed
 * out or errored contributes no CodeGraph one, and a question whose `grep` invocation failed
 * contributes no bash one (see [RetrievalRunResult]). Such a question is excluded from that
 * side's average rather than silently counted as a zero -- that would understate the tool by
 * blaming it for an infrastructure problem instead of a retrieval one. The exclusion is visible
 * rather than implied: a [measuredCount] below [RetrievalAggregate.questionCount] is exactly the
 * signal that a denominator differs, and the report prints both numbers side by side.
 *
 * The rule is the same one for every side, bash included -- it is deliberately *not* relaxed for
 * the two working-tree sides just because neither can be blocked by an index. `ripgrep`'s
 * [measuredCount] always equals [RetrievalAggregate.questionCount] because its per-question
 * result is non-null by type; bash's usually will too, and when it does not, the gap is a failed
 * subprocess and is printed rather than averaged away.
 */
@Serializable
data class SideAggregate(
    val measuredCount: Int,
    val meanPrecisionAtK: Map<Int, Double>,
    val meanRecallAtK: Map<Int, Double>,
    val mrr: Double
)

/**
 * Every side's [SideAggregate] over the same set of questions, plus how many questions that set
 * had. The two non-null fields are the sides that have been present since schema v1; the two
 * nullable ones each say "this side was not in this run" with a `null` rather than with a zero.
 */
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
    val codeGraph: SideAggregate? = null,
    /**
     * `null` for a run that measured no bash side anywhere in this group -- an archived result
     * from before the fourth side existed, or a run where every question's `grep` invocation
     * failed. Distinct from a present [SideAggregate] whose [SideAggregate.measuredCount] is 0.
     *
     * The same treatment [codeGraph] gets, for the same reason and not merely by symmetry: "bash
     * was not in this run" and "bash was in this run and scored 0" are different claims, and only
     * the second is evidence about what a shell alone can retrieve -- which is the one number
     * this whole comparison was commissioned to produce. Publishing the first as the second
     * would hand the project an unearned win over the honest floor it is supposed to beat.
     */
    val bash: SideAggregate? = null
)

/**
 * AC-26: [headline] pools GRAPH_HEAVY and NEUTRAL questions only -- [negativeControl] is
 * reported completely separately, on purpose, the same separation
 * [io.contextgraph.benchmark.report.BenchmarksReportGenerator] draws for the agent-A/B axis
 * (AC-20). [byCategory] and [byRepo] give the finer breakdown AC-26 also asks for.
 *
 * Any [RetrievalAggregate] here is computed only from questions whose repo actually produced a
 * ContextGraph-side measurement (see [RetrievalRun.skippedRepos]) *for the ContextGraph side of
 * that aggregate specifically* -- [SideAggregate.mrr] and friends for the two text-search
 * baselines, `ripgrep` and `bash`, are computed over every question in the group regardless,
 * since neither is ever blocked by an index integrity failure. See [RetrievalStats] for exactly
 * how the two are kept from silently averaging together a different denominator.
 *
 * Every one of the four groupings carries all four sides, so no table downstream has a column it
 * can never fill: a side added at the headline but not per repo would be worse than a side left
 * out entirely, because the missing breakdown would read as a measurement that came back empty.
 */
@Serializable
data class RetrievalSummary(
    val headline: RetrievalAggregate,
    val negativeControl: RetrievalAggregate,
    val byCategory: Map<QuestionCategory, RetrievalAggregate>,
    val byRepo: Map<String, RetrievalAggregate>
)

/**
 * One side's aggregate, reached from the side rather than through a `when` at each call site -- the
 * single place that knows which field of a [RetrievalAggregate] a [RetrievalSide] names. `null`
 * means that side was not in the run at all and is never a zeroed stand-in, which
 * [RetrievalAggregate.codeGraph] and [RetrievalAggregate.bash] explain is a different claim.
 *
 * It lives beside the type, not inside a generator, because both [RetrievalReportGenerator] and
 * [RetrievalSitePage] need exactly this mapping and neither owns it. The same shape and the same
 * reason as [sideResult], which does this for one question rather than for a group.
 */
internal fun RetrievalAggregate.sideAggregate(side: RetrievalSide): SideAggregate? = when (side) {
    RetrievalSide.CONTEXT_GRAPH -> contextGraph
    RetrievalSide.CODE_GRAPH -> codeGraph
    RetrievalSide.BASH -> bash
    RetrievalSide.RIPGREP -> ripgrep
}
