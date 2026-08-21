package io.contextgraph.benchmark.corpus

import io.contextgraph.benchmark.model.Question
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.GraphDb
import io.contextgraph.storage.SqliteStorageAdapter
import java.nio.file.Path

/**
 * AC-2a: after [CorpusIndexer.index] finishes for a repo and before that index is used by any
 * agent run, proves every file the repo's gold facts cite ([io.contextgraph.benchmark.model.Evidence.file])
 * is present in the graph -- queried from the graph itself ([io.contextgraph.core.StorageAdapter.getArtifact]),
 * never from the filesystem.
 *
 * The filesystem is deliberately not consulted: "the file exists on disk, the `.contextgraph/`
 * directory is present, the graph is a plausible size" is exactly what a truncated indexing run
 * cannot be told apart from by any of those signals -- this is a measured failure, not a
 * hypothetical one. A keycloak index that a stopped Gradle daemon cut off after an hour looked
 * healthy on every filesystem-level signal (228,587 nodes, 11,730 artifacts, 1.1 GB, directory
 * present) while 6 of the 22 files the gold set cited were never written to the graph at all,
 * scattered unevenly (`requiredactions/` held 32 artifacts but not `UpdatePassword.java`). Asking
 * the graph directly is the only check that would have caught it, because it measures the index
 * against the thing it will actually be used for.
 */
object IndexIntegrityGate {

    /**
     * Thrown when one or more gold-fact-cited files are missing from the indexed graph. A repo
     * whose index fails this check is incomplete: the caller must not proceed to run agents
     * against it, silently re-index, or otherwise paper over the gap -- the cause needs
     * investigating, not auto-remediating.
     */
    class IndexIncompleteException(val repoId: String, val missingFiles: List<String>) : Exception(
        buildMessage(repoId, missingFiles)
    ) {
        companion object {
            private fun buildMessage(repoId: String, missingFiles: List<String>): String {
                val sorted = missingFiles.sorted()
                return "Index for repo '$repoId' is incomplete: ${sorted.size} file(s) cited by " +
                    "gold-fact evidence are missing from the graph: ${sorted.joinToString(", ")}. " +
                    "A truncated or interrupted indexing run can look healthy by directory " +
                    "presence, size, or node count alone -- this run refuses to start against an " +
                    "index that cannot answer the questions it will be asked. Investigate why " +
                    "indexing did not cover these files and re-index; do not retry automatically."
            }
        }
    }

    /**
     * [indexedWorkingDir] is the repo's WITH working copy, already indexed by [CorpusIndexer].
     * [questions] is the full loaded question set (or any superset covering [repoId]) -- this
     * function filters to [repoId] itself, so callers do not need to pre-filter. A repo with no
     * cited files at all (empty gold set, or no questions loaded for it) passes trivially: there
     * is nothing to verify coverage against.
     *
     * Throws [IndexIncompleteException] if any cited file is absent from the graph. Returns
     * normally otherwise -- there is no partial-pass result; a caller either gets a clean bill or
     * an exception listing exactly what is missing.
     */
    fun verify(repoId: String, indexedWorkingDir: Path, questions: List<Question>) {
        val citedFiles = questions
            .asSequence()
            .filter { it.repoId == repoId }
            .flatMap { it.goldFacts.asSequence() }
            .map { it.evidence.file }
            .distinct()
            .toList()

        if (citedFiles.isEmpty()) return

        check(GraphDb.exists(indexedWorkingDir)) {
            "No ContextGraph index found at $indexedWorkingDir for repo '$repoId' -- cannot verify " +
                "index integrity against a graph that was never written. Corpus prep must index " +
                "this working copy before the integrity gate can run."
        }

        val storage = SqliteStorageAdapter(GraphDb.forRead(indexedWorkingDir))
        try {
            val missing = citedFiles.filter { file -> storage.getArtifact(ArtifactId(file)) == null }
            if (missing.isNotEmpty()) {
                throw IndexIncompleteException(repoId, missing)
            }
        } finally {
            storage.close()
        }
    }
}
