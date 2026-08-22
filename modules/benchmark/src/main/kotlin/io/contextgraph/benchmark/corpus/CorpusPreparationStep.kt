package io.contextgraph.benchmark.corpus

import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.IngestRecord
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.retrieval.RetrievalSide
import io.contextgraph.benchmark.retrieval.ToolIngestCost
import io.contextgraph.benchmark.runner.GraphTool
import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.GraphDb
import io.github.oshai.kotlinlogging.KotlinLogging
import io.contextgraph.core.LiteLlmConfig
import java.nio.file.Path
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/** One repo's outcome from [CorpusPreparationStep.run]: the updated catalog entry plus its ingest cost. */
data class CorpusPreparationResult(
    val repo: CorpusRepo,
    /** Null when no graph was built for this repo -- see [CorpusPreparationStep.run]'s `indexWithCopy`. */
    val ingestRecord: IngestRecord?,
    /**
     * Per-tool ingest cost for this repo, as written to its [IngestManifest]. Always carries an
     * entry for each tool -- one that was not indexed carries an `absentReason` instead of a
     * duration, never a zero, so "cost nothing" and "was never built" stay distinguishable.
     */
    val toolIngestCosts: List<ToolIngestCost> = emptyList()
)

/**
 * The whole corpus step (AC-1, AC-1a, AC-2) end to end, for slice 12's orchestrator to call
 * behind `--profile smoke|full`: for each repo, clone-or-verify both working copies, confirm
 * the WITHOUT copy carries no ContextGraph artifact both before and after indexing runs, then
 * index only the WITH copy.
 *
 * Not itself a CLI command — `modules/benchmark/README.md` reserves CLI wiring for slice 12
 * (`io.contextgraph.benchmark.orchestrator`); this is the function that wiring calls.
 *
 * [questions] backs AC-2a's index integrity gate: once a repo's WITH copy is indexed, every
 * file that repo's gold facts cite must be present in the graph *before* this function returns
 * it as ready — see [IndexIntegrityGate] for why this is checked against the graph, not the
 * filesystem. Defaults to empty so existing callers that don't pass a question set (e.g. tests
 * only interested in AC-1/AC-1a/AC-2) are unaffected — an empty or non-matching question set has
 * nothing to verify coverage against, so the gate passes trivially rather than requiring every
 * caller to opt in.
 */
object CorpusPreparationStep {

    fun run(
        corpusRoot: Path,
        repos: List<CorpusRepo> = CorpusCatalog.DEFAULT,
        preparer: CorpusPreparer = CorpusPreparer(),
        ingestConfig: ContextGraphConfig = ContextGraphConfig(litellm = LiteLlmConfig(enabled = false)),
        questions: List<Question> = emptyList(),
        /**
         * When false, working copies are still checked out but no graph is built and the index
         * integrity gate does not run.
         *
         * A control-arm-only screening run never opens the WITH copy, so indexing it costs many
         * minutes and buys nothing -- and worse, its gate would abort a screening pass over a repo
         * whose index is known to be incomplete, blocking exactly the questions that most need
         * calibrating.
         */
        indexWithCopy: Boolean = true,
        /** Where the CodeGraph binary lives. Default resolves it on `PATH`, mirroring `--rg-path`'s shape. */
        codegraphPath: String = "codegraph",
        /** Budget for one `codegraph init`; exceeding it fails loudly rather than yielding a partial index. */
        codeGraphTimeout: Duration = CodeGraphIndexer.DEFAULT_TIMEOUT,
        /** Surfaces indexing progress (including CodeGraph's own output) so a slow index is not mistaken for a hung one. */
        progress: (String) -> Unit = {}
    ): List<CorpusPreparationResult> = repos.map { repo ->
        val prepared = preparer.prepare(repo, corpusRoot)
        val withoutPath = Path.of(requireNotNull(prepared.workingCopyWithoutPath))
        val withPath = Path.of(requireNotNull(prepared.workingCopyWithPath))
        val codeGraphPath = Path.of(requireNotNull(GraphTool.CODEGRAPH.withToolsDir(prepared)))

        // Before indexing: a prior run's WITH copy must never have been mistaken for WITHOUT.
        // Now covers CodeGraph's artefacts as well, so the guarantee is "no code-graph tool has
        // touched this", not merely "ContextGraph has not".
        CleanCopyVerifier.verifyClean(withoutPath)

        // A working copy is checked out at a pinned SHA and verified never to change, so an index
        // that already exists for it is current by construction -- re-indexing it produces the
        // same graph and, for the largest repo in the corpus, costs ~50 minutes before the first
        // agent run. That cost was paid twice in one evening: once for a run that was interrupted,
        // then again on the restart, for a graph that was already sitting on disk.
        val existingIndex = GraphDb.forLocalWrite(withPath)
        val alreadyIndexed = indexWithCopy && existingIndex.toFile().length() > 0
        if (alreadyIndexed) {
            logger.info { "reusing existing index for '${repo.id}' at $existingIndex (pinned checkout, so it cannot be stale)" }
        }

        val ingestRecord = if (indexWithCopy && !alreadyIndexed) {
            val record = CorpusIndexer.index(repo.id, withPath, ingestConfig)
            // AC-2a: the index is not usable until this passes. Throws IndexIncompleteException
            // (uncaught here, on purpose) if a gold-fact-cited file didn't make it into the graph --
            // the whole run must not start, not just this repo's slice of it.
            IndexIntegrityGate.verify(repo.id, withPath, questions)
            record
        } else {
            // The gate still runs against a reused index: reuse skips the cost of rebuilding, not
            // the check that the graph actually contains what the questions cite.
            if (alreadyIndexed) IndexIntegrityGate.verify(repo.id, withPath, questions)
            null
        }

        // The third comparator's index. Deliberately after ContextGraph's, and before the
        // after-check below, so one clean-copy verification covers both writers.
        val codeGraphCost = if (indexWithCopy) {
            CodeGraphIndexer.index(repo.id, codeGraphPath, codegraphPath, codeGraphTimeout, progress)
        } else {
            CodeGraphIndexer.absent(
                repo.id,
                "indexing was not requested for this run (indexWithCopy=false), so the codegraph " +
                    "working copy at $codeGraphPath was checked out but never indexed"
            )
        }

        // After indexing: proves indexing wrote only where it was supposed to (AC-1a / AC-7a) --
        // for BOTH tools now, which is the property that would silently have been lost had the
        // verifier kept knowing only about ContextGraph.
        CleanCopyVerifier.verifyClean(withoutPath)

        val costs = listOf(
            contextGraphCost(repo.id, withPath, indexWithCopy, ingestRecord, alreadyIndexed),
            codeGraphCost
        )
        IngestManifest(repoId = repo.id, costs = costs)
            .writeTo(corpusRoot.resolve(repo.id))

        CorpusPreparationResult(prepared, ingestRecord, costs)
    }

    /**
     * ContextGraph's side of the manifest. Duration comes from the [IngestRecord] the indexer
     * already produced; a reused index reports `0` because rebuilding it was genuinely skipped,
     * which is different from never having been built -- that case carries an `absentReason`.
     */
    private fun contextGraphCost(
        repoId: String,
        withPath: Path,
        indexWithCopy: Boolean,
        ingestRecord: IngestRecord?,
        alreadyIndexed: Boolean
    ): ToolIngestCost {
        val dbPath = GraphDb.forLocalWrite(withPath)
        val sizeBytes = dbPath.toFile().takeIf { it.exists() }?.length()
        return when {
            ingestRecord != null -> ToolIngestCost(
                repoId = repoId,
                tool = GraphTool.CONTEXTGRAPH,
                durationMillis = ingestRecord.durationMillis,
                indexSizeBytes = sizeBytes
            )
            alreadyIndexed -> ToolIngestCost(
                repoId = repoId,
                tool = GraphTool.CONTEXTGRAPH,
                durationMillis = 0,
                indexSizeBytes = sizeBytes
            )
            else -> ToolIngestCost(
                repoId = repoId,
                tool = GraphTool.CONTEXTGRAPH,
                absentReason = if (indexWithCopy) {
                    "no ${RetrievalSide.CONTEXT_GRAPH.label} index was produced for the with copy at $withPath"
                } else {
                    "indexing was not requested for this run (indexWithCopy=false)"
                }
            )
        }
    }
}
