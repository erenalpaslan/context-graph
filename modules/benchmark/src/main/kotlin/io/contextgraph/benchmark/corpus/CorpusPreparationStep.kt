package io.contextgraph.benchmark.corpus

import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.IngestRecord
import io.contextgraph.benchmark.model.Question
import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.LiteLlmConfig
import java.nio.file.Path

/** One repo's outcome from [CorpusPreparationStep.run]: the updated catalog entry plus its ingest cost. */
data class CorpusPreparationResult(
    val repo: CorpusRepo,
    /** Null when no graph was built for this repo -- see [CorpusPreparationStep.run]'s `indexWithCopy`. */
    val ingestRecord: IngestRecord?
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
        indexWithCopy: Boolean = true
    ): List<CorpusPreparationResult> = repos.map { repo ->
        val prepared = preparer.prepare(repo, corpusRoot)
        val withoutPath = Path.of(requireNotNull(prepared.workingCopyWithoutPath))
        val withPath = Path.of(requireNotNull(prepared.workingCopyWithPath))

        // Before indexing: a prior run's WITH copy must never have been mistaken for WITHOUT.
        CleanCopyVerifier.verifyClean(withoutPath)

        val ingestRecord = if (indexWithCopy) {
            val record = CorpusIndexer.index(repo.id, withPath, ingestConfig)
            // AC-2a: the index is not usable until this passes. Throws IndexIncompleteException
            // (uncaught here, on purpose) if a gold-fact-cited file didn't make it into the graph --
            // the whole run must not start, not just this repo's slice of it.
            IndexIntegrityGate.verify(repo.id, withPath, questions)
            record
        } else {
            null
        }

        // After indexing: proves indexing wrote only to the WITH copy (AC-1a / AC-7a).
        CleanCopyVerifier.verifyClean(withoutPath)

        CorpusPreparationResult(prepared, ingestRecord)
    }
}
