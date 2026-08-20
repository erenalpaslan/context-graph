package io.contextgraph.benchmark.orchestrator

import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.BenchmarkConfig
import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.Profile
import io.contextgraph.benchmark.model.Question

/**
 * Computes the scope of a profile -- which repos, which questions, how many repeats -- as pure
 * functions with no I/O (task 12's own rule: this slice adds no new measurement logic, only
 * wiring, and a plan is not a measurement). [smoke] and [full] share every function here; the
 * only difference between the two profiles is the arguments [plan] is called with, never a
 * separate code path (AC-16, AC-17: "iki profil de aynı bileşenleri, aynı sırayla kullanır").
 */
object RunPlanner {

    /**
     * `smoke` is pinned to gin rather than "whichever repo comes first in the catalog": gin is
     * the smallest of the four corpus repos and, because indexing runs with `litellm.enabled =
     * false` (spec Q1), is the cheapest one to prepare from scratch or re-index. See task 12's
     * corpus-reuse note: the orchestrator scopes corpus preparation down to just this one repo
     * for `smoke`, rather than calling [io.contextgraph.benchmark.corpus.CorpusPreparationStep]
     * over the full four-repo catalog (which would unconditionally re-index all four every run).
     */
    const val DEFAULT_SMOKE_REPO_ID = "gin"

    /** AC-16: "1 repo x 3 soru". */
    const val SMOKE_QUESTIONS_PER_REPO = 3

    /** Which repos a profile touches. `full` is every repo in [catalog]; `smoke` is just [smokeRepoId]. */
    fun repoScope(
        profile: Profile,
        catalog: List<CorpusRepo>,
        smokeRepoId: String = DEFAULT_SMOKE_REPO_ID
    ): List<CorpusRepo> = when (profile) {
        Profile.SMOKE -> catalog.filter { it.id == smokeRepoId }
        Profile.FULL -> catalog
    }

    /**
     * The repeat count a profile runs with. Per the spec table, `smoke` is 1 repetition per
     * (question, arm); `full` uses [BenchmarkConfig]'s configured default (4, spec Q2's medium-of-4
     * protocol) unchanged. Ceiling and model choice are never varied by profile -- only scope is.
     */
    fun configFor(profile: Profile, base: BenchmarkConfig = BenchmarkConfig()): BenchmarkConfig = when (profile) {
        Profile.SMOKE -> base.copy(repeatsPerArm = 1)
        Profile.FULL -> base
    }

    /**
     * Which questions a profile runs, restricted to [repoIds]. `smoke` additionally caps each
     * repo at [SMOKE_QUESTIONS_PER_REPO] questions (deterministically -- sorted by id, so the
     * same gold set always yields the same smoke selection); `full` runs every question loaded
     * for its repo scope (AC-17's "~8 soru" is however many the real gold set for that repo has,
     * not a number this function hard-codes).
     */
    fun selectQuestions(profile: Profile, repoIds: List<String>, allQuestions: List<Question>): List<Question> {
        val scoped = allQuestions.filter { it.repoId in repoIds }
        return when (profile) {
            Profile.SMOKE -> repoIds.flatMap { repoId ->
                scoped.filter { it.repoId == repoId }.sortedBy { it.id }.take(SMOKE_QUESTIONS_PER_REPO)
            }
            Profile.FULL -> scoped.sortedWith(compareBy({ it.repoId }, { it.id }))
        }
    }

    /**
     * The full plan for [profile]: which repos, which questions, and the (question, arm, repeat)
     * cross product to run. Every (question, arm) pair gets [BenchmarkConfig.repeatsPerArm]
     * repeats, [Arm.entries] in full every time (both arms always run; a profile never runs only
     * one arm, per AC-7's "diğer her şey aynıdır" -- the comparison needs both sides).
     */
    fun plan(
        profile: Profile,
        catalog: List<CorpusRepo>,
        allQuestions: List<Question>,
        config: BenchmarkConfig,
        smokeRepoId: String = DEFAULT_SMOKE_REPO_ID,
        /**
         * Which arms to plan. Both, always, for any A/B measurement -- AC-7's comparison needs
         * two sides. A single arm is only for calibration: measuring how hard a question is for
         * the baseline, before spending anything comparing tools on it.
         */
        arms: List<Arm> = Arm.entries
    ): RunPlan {
        val scopedRepos = repoScope(profile, catalog, smokeRepoId)
        val questions = selectQuestions(profile, scopedRepos.map { it.id }, allQuestions)

        // A repo with no questions in this plan contributes nothing to run, so preparing it is
        // pure waste -- and not cheap waste: cloning and indexing the largest repo in the catalog
        // took ~55 minutes of a `full` run whose questions had deliberately excluded it, before a
        // single agent run started. Narrowing to the repos the questions actually reference makes
        // `--questions-dir` a real scoping mechanism for `full`, which is otherwise all-or-nothing.
        val referencedRepoIds = questions.map { it.repoId }.toSet()
        val repos = scopedRepos.filter { it.id in referencedRepoIds }

        val plannedRuns = questions.flatMap { question ->
            arms.flatMap { arm ->
                (0 until config.repeatsPerArm).map { repeat -> PlannedRun(question, arm, repeat) }
            }
        }
        return RunPlan(repos, questions, plannedRuns)
    }
}
