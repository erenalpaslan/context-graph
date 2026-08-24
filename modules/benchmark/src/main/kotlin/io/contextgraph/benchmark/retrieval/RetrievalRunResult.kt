package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory
import kotlinx.serialization.Serializable

/**
 * One side's measured outcome for one question -- ContextGraph, CodeGraph, bash or ripgrep: the
 * ranked file list it actually produced, plus precision@k/recall@k (keyed by the `k` values the
 * run was configured with) and reciprocal-rank derived from it via [RetrievalMetrics].
 */
@Serializable
data class SideResult(
    val rankedFiles: List<String>,
    val precisionAtK: Map<Int, Double>,
    val recallAtK: Map<Int, Double>,
    val reciprocalRank: Double
)

/**
 * One question's full retrieval measurement: all four sides, each given the same raw
 * [io.contextgraph.benchmark.model.Question.text], run against the same [expectedFiles], scored
 * with the same metrics. Those three "same"s are the fairness invariants the whole comparison
 * rests on -- a number obtained by breaking one of them is worse than no number.
 *
 * Three of the four sides are nullable, for different reasons, and no null ever means zero:
 *
 * - [contextGraph] is `null` when that repo's WITH index failed
 *   [io.contextgraph.benchmark.corpus.IndexIntegrityGate] at measurement time (see
 *   [RetrievalRun.skippedRepos]).
 * - [codeGraph] is `null` when `codegraph explore` timed out or exited non-zero for this
 *   question, or when no CodeGraph index was prepared for the repo at all. A *successful*
 *   invocation that named no files is a real zero and is recorded as an empty ranked list, not
 *   as null -- keeping those two apart is what stops a broken invocation from reading as
 *   "CodeGraph retrieves nothing", the single most damaging way this instrument could mislead.
 * - [bash] is `null` for the two reasons its own KDoc gives, neither of which is an indexing one.
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
    /**
     * The shared token list **both** text-search baselines were given: [ripgrep] and [bash] each
     * search for exactly these, derived once by [RipgrepQueryDeriver] from the question's raw
     * text. Recording it once, rather than per side, is what makes "the two baselines differ in
     * the tool and in nothing else" checkable from the archived result alone.
     *
     * The name predates the second text-search baseline and is kept deliberately: renaming a
     * persisted field would stop every archived `retrieval-*.json` from decoding, and a stale
     * name is a far smaller cost than an unreadable archive. Read it as "the derived query
     * tokens", not as "tokens only ripgrep saw".
     */
    val ripgrepQueryTokens: List<String>,
    val contextGraph: SideResult?,
    val ripgrep: SideResult,
    /**
     * Additive with a default so the four archived schema-v1 `retrieval-*.json` results still
     * decode -- a required field here would break every one of them.
     */
    val codeGraph: SideResult? = null,
    /**
     * The base-system shell baseline: `grep` over the same never-indexed WITHOUT working copy
     * [ripgrep] reads, given the same [ripgrepQueryTokens], ranked by the same rule.
     *
     * **Nullable with a default, not non-null like [ripgrep] -- and that is a deliberate call,
     * not an oversight.** The bash side resembles ripgrep in the way that would argue for
     * non-null: it is never blocked by either tool's indexing state, because it reads the working
     * copy directly, so no integrity gate and no missing index can ever cost it a measurement.
     * Two facts outweigh that:
     *
     * 1. Every result JSON archived under `modules/benchmark/results/` was written before this
     *    field existed and genuinely has no bash side. A non-null field would need a default
     *    value, and *any* default is a fabricated measurement -- a zeroed [SideResult] would make
     *    every archived run claim that a shell baseline ran in it and found nothing. `null` is
     *    the only value that says the true thing, which is "not measured here".
     * 2. Within a run that does have the side, a `grep` invocation can still fail (a non-0/1 exit
     *    status is raised, never swallowed -- see the bash runner). That question is unmeasured,
     *    not zero, exactly as a timed-out `codegraph explore` is.
     *
     * A *clean* no-match run -- `grep` exited 1, having searched everything and matched nothing --
     * is a real zero and is recorded as an empty ranked list with zeroed metrics, never as null.
     * That is the honest floor this whole fourth side exists to measure.
     */
    val bash: SideResult? = null
)

/** A repo this run could not fully or partially measure, and why -- never a silent omission. */
@Serializable
data class SkippedRepo(val repoId: String, val reason: String)
