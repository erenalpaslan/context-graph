package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.corpus.IndexIntegrityGate
import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.Question
import io.contextgraph.core.GraphDb
import io.contextgraph.query.QueryEngine
import io.contextgraph.storage.SqliteStorageAdapter
import kotlinx.datetime.Clock
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs the whole retrieval axis (AC-23..AC-26) over a set of already-prepared corpus repos:
 * for every question, scores both sides against the [ExpectedFileSet] its own gold facts derive,
 * and returns a [RetrievalRun] with [RetrievalStats.summarize] already folded in.
 *
 * Deliberately does not prepare, clone, or (re-)index anything -- it only *reads*
 * [corpusRoot]`/<repoId>/with` and `/without`, the layout
 * [io.contextgraph.benchmark.corpus.CorpusPreparer] already writes. This matters for a corpus
 * repo whose WITH copy is still being indexed by a concurrent process: this runner never opens
 * that path for writing, so it cannot corrupt or race an in-progress index. It can only ever
 * observe one of two honest outcomes -- [IndexIntegrityGate] passes (the index is complete
 * enough to answer every gold-fact-cited file) or it throws (still incomplete, or not indexed at
 * all yet) -- and the latter is recorded as a per-repo skip of the ContextGraph side only (see
 * [RetrievalRunResult.contextGraph]), never as a crash of the whole run and never by silently
 * retrying or waiting.
 */
class RetrievalBenchmarkRunner(
    private val corpusRoot: Path,
    private val questions: List<Question>,
    private val catalog: List<CorpusRepo>,
    private val kValues: List<Int> = DEFAULT_K_VALUES,
    private val rgPath: String = "rg",
    private val progress: (String) -> Unit = {}
) {

    fun run(): RetrievalRun {
        val results = mutableListOf<RetrievalRunResult>()
        val skipped = mutableListOf<SkippedRepo>()
        val baseline = RipgrepBaselineRunner(rgPath)

        for (repo in catalog) {
            val repoQuestions = questions.filter { it.repoId == repo.id }
            if (repoQuestions.isEmpty()) continue

            val withoutDir = corpusRoot.resolve(repo.id).resolve("without")
            if (!Files.isDirectory(withoutDir)) {
                skipped += SkippedRepo(
                    repo.id,
                    "WITHOUT working copy not found at $withoutDir -- corpus not prepared for this repo; skipping both sides"
                )
                continue
            }

            val withDir = corpusRoot.resolve(repo.id).resolve("with")
            val queryEngine = openContextGraphSide(repo.id, withDir, repoQuestions, skipped)

            try {
                for (question in repoQuestions) {
                    progress("${question.id}: scoring ContextGraph and ripgrep")
                    results += scoreQuestion(question, withoutDir, queryEngine, baseline)
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
            summary = summary
        )
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
                "ContextGraph side skipped (ripgrep side still measured): ${e.message}"
            )
            null
        }
    }

    private fun scoreQuestion(
        question: Question,
        withoutDir: Path,
        queryEngine: ClosableQueryEngine?,
        baseline: RipgrepBaselineRunner
    ): RetrievalRunResult {
        val expected = ExpectedFileSet.of(question)

        val ripgrepOutcome = baseline.rankedFiles(question.text, withoutDir)
        val ripgrepSide = scoreSide(ripgrepOutcome.rankedFiles, expected)

        val contextGraphSide = queryEngine?.let {
            val ranked = ContextGraphRetrievalRunner(it.queryEngine).rankedFiles(question.text)
            scoreSide(ranked, expected)
        }

        return RetrievalRunResult(
            questionId = question.id,
            repoId = question.repoId,
            category = question.category,
            expectedFiles = expected.sorted(),
            ripgrepQueryTokens = ripgrepOutcome.tokens,
            contextGraph = contextGraphSide,
            ripgrep = ripgrepSide
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
