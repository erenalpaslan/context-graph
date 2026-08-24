package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.corpus.CodeGraphIndexer
import io.contextgraph.benchmark.corpus.CorpusPreparer
import io.contextgraph.benchmark.corpus.IndexIntegrityGate
import io.contextgraph.benchmark.corpus.IngestManifest
import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.Question
import io.contextgraph.core.GraphDb
import io.contextgraph.query.QueryEngine
import io.contextgraph.storage.SqliteStorageAdapter
import kotlinx.datetime.Clock
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs the whole retrieval axis over a set of already-prepared corpus repos: for every question,
 * scores **all four sides** -- ContextGraph, CodeGraph, bash and ripgrep -- against the
 * [ExpectedFileSet] its own gold facts derive, and returns a [RetrievalRun] with
 * [RetrievalStats.summarize] already folded in.
 *
 * The three fairness invariants live here, in [scoreQuestion], and they are the point of the
 * exercise: every side is handed the same raw `question.text`, scored against the same expected
 * set, with the same metrics, and no side's output is re-ranked, filtered or truncated before
 * scoring. A number obtained by breaking one of them is worse than no number. The fourth side
 * **extends** those invariants rather than diluting them: [BashBaselineRunner] takes the same raw
 * `question.text`, derives its tokens from the same [RipgrepQueryDeriver.deriveTokens] the ripgrep
 * side uses, searches the same WITHOUT working copy, and its ranked list reaches [scoreSide]
 * exactly as `grep` emitted it.
 *
 * Deliberately does not prepare, clone, or (re-)index anything -- it only *reads*
 * [corpusRoot]`/<repoId>/{with,without,codegraph}`, the layout
 * [io.contextgraph.benchmark.corpus.CorpusPreparer] already writes, plus the ingest manifest
 * corpus prep left behind. This matters for a repo whose index is still being built by a
 * concurrent process: this runner never opens those paths for writing, so it cannot corrupt or
 * race an in-progress index.
 *
 * **Three kinds of absence, and none of them is a zero.** A repo whose ContextGraph index fails
 * [IndexIntegrityGate] loses its ContextGraph side; a repo with no CodeGraph index, or a question
 * whose `codegraph explore` call fails, loses that side; a question whose `grep` invocation exits
 * with a real error -- an unreadable file or a bad path, where `rg` would have returned a partial
 * result -- loses its bash side. Every one is recorded as a [SkippedRepo] with its reason and
 * excluded from that side's average, rather than counted as 0.0 -- which would blame a tool for an
 * infrastructure failure instead of a retrieval one.
 *
 * Note what the bash side is *never* absent for: an indexing reason. Like the ripgrep side it
 * reads the never-indexed WITHOUT working tree, so no integrity gate and no missing index can cost
 * it a measurement; the only way it goes missing is a failed subprocess, and that failure names
 * itself. A `grep` that ran clean and matched nothing is a real zero and is scored as one.
 *
 * The gate is applied to the ContextGraph side only, and is left exactly as it was. That
 * asymmetry is answered by publishing each tool's gold-file coverage
 * ([GoldFileCoverageCalculator]) rather than by weakening the gate.
 */
class RetrievalBenchmarkRunner(
    private val corpusRoot: Path,
    private val questions: List<Question>,
    private val catalog: List<CorpusRepo>,
    private val kValues: List<Int> = DEFAULT_K_VALUES,
    private val rgPath: String = "rg",
    private val codegraphPath: String = "codegraph",
    /**
     * Overridable the way [rgPath] and [codegraphPath] are, but with a different *kind* of default:
     * [BashProcess.BASE_SYSTEM_GREP] is an absolute path, not a name resolved through `PATH`. A
     * developer's `PATH` may put a Homebrew or `nix` GNU grep first, and measuring that would
     * silently be measuring an installed third-party tool again -- exactly the flaw this side
     * exists to remove from the ripgrep baseline.
     */
    private val grepPath: String = BashProcess.BASE_SYSTEM_GREP,
    private val progress: (String) -> Unit = {}
) {

    fun run(): RetrievalRun {
        val results = mutableListOf<RetrievalRunResult>()
        val skipped = mutableListOf<SkippedRepo>()
        val coverage = mutableListOf<GoldFileCoverage>()
        val baseline = RipgrepBaselineRunner(rgPath)
        val bashBaseline = BashBaselineRunner(grepPath)

        for (repo in catalog) {
            val repoQuestions = questions.filter { it.repoId == repo.id }
            if (repoQuestions.isEmpty()) continue

            val withoutDir = corpusRoot.resolve(repo.id).resolve("without")
            if (!Files.isDirectory(withoutDir)) {
                skipped += SkippedRepo(
                    repo.id,
                    "WITHOUT working copy not found at $withoutDir -- corpus not prepared for this repo; skipping all four sides"
                )
                continue
            }

            val withDir = corpusRoot.resolve(repo.id).resolve("with")
            val queryEngine = openContextGraphSide(repo.id, withDir, repoQuestions, skipped)
            val codeGraph = openCodeGraphSide(repo.id, skipped)

            coverage += GoldFileCoverageCalculator.forRepo(repo.id, repoQuestions, withDir, codeGraph)

            try {
                for (question in repoQuestions) {
                    progress("${question.id}: scoring ContextGraph, CodeGraph, bash and ripgrep")
                    results += scoreQuestion(
                        question, withoutDir, queryEngine, codeGraph, baseline, bashBaseline, skipped
                    )
                }
            } finally {
                queryEngine?.close()
            }
        }

        val summary = RetrievalStats.summarize(results, kValues)
        return RetrievalRun(
            runId = "retrieval-${Clock.System.now().toEpochMilliseconds()}",
            generatedAt = Clock.System.now(),
            kValues = kValues,
            results = results,
            skippedRepos = skipped,
            summary = summary,
            goldFileCoverage = coverage,
            ingestCosts = IngestManifest.readAll(corpusRoot, catalog.map { it.id })
        )
    }

    /**
     * The CodeGraph side's per-repo setup, deliberately shaped like [openContextGraphSide]: a repo
     * with no prepared or indexed `codegraph` copy yields `null` and one skip naming why, rather
     * than a runner that would fail identically on every question and bury the reason 25 times.
     */
    private fun openCodeGraphSide(repoId: String, skipped: MutableList<SkippedRepo>): CodeGraphRetrievalRunner? {
        val dir = corpusRoot.resolve(repoId).resolve(CorpusPreparer.CODEGRAPH_ROLE)
        if (!Files.isDirectory(dir)) {
            skipped += SkippedRepo(
                repoId,
                "${RetrievalSide.CODE_GRAPH.label} side skipped (other sides still measured): no codegraph working copy at $dir " +
                    "-- corpus prep for this repo predates the third comparator, or was never run"
            )
            return null
        }
        if (!CodeGraphIndexer.isIndexed(dir)) {
            skipped += SkippedRepo(
                repoId,
                "${RetrievalSide.CODE_GRAPH.label} side skipped (other sides still measured): $dir carries no " +
                    "${CodeGraphIndexer.INDEX_DIR_NAME} index -- prepareCorpus could not index it " +
                    "(see that repo's ingest.json for the recorded reason)"
            )
            return null
        }
        return CodeGraphRetrievalRunner(dir, codegraphPath)
    }

    private fun openContextGraphSide(
        repoId: String,
        withDir: Path,
        repoQuestions: List<Question>,
        skipped: MutableList<SkippedRepo>
    ): ClosableQueryEngine? {
        return try {
            IndexIntegrityGate.verify(repoId, withDir, repoQuestions)
            val storage = SqliteStorageAdapter(GraphDb.forRead(withDir))
            ClosableQueryEngine(storage)
        } catch (e: Exception) {
            skipped += SkippedRepo(
                repoId,
                "${RetrievalSide.CONTEXT_GRAPH.label} side skipped (other sides still measured): ${e.message}"
            )
            null
        }
    }

    private fun scoreQuestion(
        question: Question,
        withoutDir: Path,
        queryEngine: ClosableQueryEngine?,
        codeGraph: CodeGraphRetrievalRunner?,
        baseline: RipgrepBaselineRunner,
        bashBaseline: BashBaselineRunner,
        skipped: MutableList<SkippedRepo>
    ): RetrievalRunResult {
        val expected = ExpectedFileSet.of(question)

        // All four sides get `question.text`, unmodified. Nothing is pre-filtered, and nothing is
        // lifted from the gold facts -- the invariant the whole comparison rests on.
        val ripgrepOutcome = baseline.rankedFiles(question.text, withoutDir)
        val ripgrepSide = scoreSide(ripgrepOutcome.rankedFiles, expected)

        // The same raw question, the same WITHOUT working copy, and -- because both runners call
        // RipgrepQueryDeriver.deriveTokens and there is no second tokenizer -- the same tokens the
        // ripgrep side just searched for. The two baselines differ in the tool and in nothing else.
        val bashSide = try {
            scoreSide(bashBaseline.rankedFiles(question.text, withoutDir).rankedFiles, expected)
        } catch (e: BashCommandExecutionException) {
            // Not a zero, for the same reason a failed `codegraph explore` is not one -- and this
            // side needs the rule more, not less: `grep` exits 2 on an unreadable file or a bad
            // path even under `-s`, where `rg` would hand back a partial result, so on a real
            // 50k-file checkout the bash side can fail where the ripgrep side does not. A thrown
            // invocation published as 0.0 would read as "a shell alone retrieves nothing", which
            // is precisely the unearned win this fourth side exists to rule out.
            skipped += SkippedRepo(
                question.repoId,
                "${RetrievalSide.BASH.label} side unmeasured for question ${question.id} (its other sides still " +
                    "scored): ${e.message}"
            )
            null
        }

        val contextGraphSide = queryEngine?.let {
            val ranked = ContextGraphRetrievalRunner(it.queryEngine).rankedFiles(question.text)
            scoreSide(ranked, expected)
        }

        val codeGraphSide = codeGraph?.let {
            try {
                scoreSide(it.rankedFiles(question.text), expected)
            } catch (e: CodeGraphExecutionException) {
                // Not a zero. One question CodeGraph could not be asked is an infrastructure fact;
                // scoring it 0.0 would blame the tool for a subprocess failure and understate it.
                // Recorded per question, never silently dropped -- and one bad question must not
                // sink a multi-hour measurement, so the run continues.
                skipped += SkippedRepo(
                    question.repoId,
                    "${RetrievalSide.CODE_GRAPH.label} side unmeasured for question ${question.id} (its other sides still " +
                        "scored): ${if (e.timedOut) "timed out" else "failed"} -- ${e.message}"
                )
                null
            }
        }

        return RetrievalRunResult(
            questionId = question.id,
            repoId = question.repoId,
            category = question.category,
            expectedFiles = expected.sorted(),
            // Recorded once for both text-search baselines, which is what makes "they differ in
            // the tool and in nothing else" checkable from the archived result alone.
            ripgrepQueryTokens = ripgrepOutcome.tokens,
            contextGraph = contextGraphSide,
            ripgrep = ripgrepSide,
            codeGraph = codeGraphSide,
            bash = bashSide
        )
    }

    private fun scoreSide(rankedFiles: List<String>, expected: Set<String>): SideResult = SideResult(
        rankedFiles = rankedFiles,
        precisionAtK = kValues.associateWith { k -> RetrievalMetrics.precisionAtK(rankedFiles, expected, k) },
        recallAtK = kValues.associateWith { k -> RetrievalMetrics.recallAtK(rankedFiles, expected, k) },
        reciprocalRank = RetrievalMetrics.reciprocalRank(rankedFiles, expected)
    )

    /** Bundles [QueryEngine] with the [SqliteStorageAdapter] underneath it so both close together. */
    private class ClosableQueryEngine(private val storage: SqliteStorageAdapter) {
        val queryEngine = QueryEngine(storage)
        fun close() = storage.close()
    }

    companion object {
        /**
         * k=5 and k=10 (documented in `BENCHMARKS.md`'s retrieval section, not just here): the
         * real gold-set data has a median of 3 and a maximum of 5 distinct cited files per
         * question across all 33 questions (computed once, by hand, from the real question
         * files -- not a guess), so k=5 is the smallest k at which *every* question's recall@k
         * can theoretically reach 1.0, and k=10 is a softer, twice-as-generous ceiling that
         * tests whether the right files are still findable within roughly "the first page" of
         * either side's output once some noise is allowed in.
         */
        val DEFAULT_K_VALUES = listOf(5, 10)
    }
}
