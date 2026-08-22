package io.contextgraph.benchmark.corpus

import io.contextgraph.benchmark.retrieval.CodeGraphExecutionException
import io.contextgraph.benchmark.retrieval.CodeGraphProcess
import io.contextgraph.benchmark.retrieval.RetrievalSide
import io.contextgraph.benchmark.retrieval.ToolIngestCost
import io.contextgraph.benchmark.runner.GraphTool
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

/** [CodeGraphIndexer.index] exceeded its budget, or CodeGraph failed outright. Never swallowed. */
class CodeGraphIndexFailedException(val repoId: String, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Builds CodeGraph's index for one repo's `codegraph` working copy -- the counterpart of
 * [CorpusIndexer] for the third comparator, recording the same two facts (how long, how big) so
 * ingest cost stays comparable between the tools and separate from query cost.
 *
 * **`codegraph index` cannot be the entry point, and this is not a detail.** Measured against
 * v1.5.0: `codegraph index` on a directory that was never initialized refuses with
 * *"✗ CodeGraph not initialized … ℹ Run \"codegraph init\" first"*. `codegraph init` builds the
 * initial index itself, and on an already-initialized project it exits 0 without re-indexing. So
 * `init` is both the correct entry point and naturally idempotent.
 *
 * Reuse mirrors [CorpusPreparationStep]'s reasoning for ContextGraph: a working copy is checked
 * out at a pinned SHA and verified never to change, so an index that already exists for it is
 * current by construction and rebuilding it buys nothing.
 */
object CodeGraphIndexer {

    /**
     * Deliberately generous. ContextGraph's own indexing of Keycloak takes ~50 minutes, so an
     * hour-scale budget for a tool doing comparable work is realistic rather than lavish, and the
     * cost of setting it too low is worse than the cost of waiting: the whole point of a timeout
     * here is to tell a slow index apart from a hung process, not to cap how long indexing may
     * legitimately take.
     */
    val DEFAULT_TIMEOUT: Duration = 4.hours

    /** CodeGraph's on-disk index, confirmed against the installed binary: `<root>/.codegraph/`. */
    const val INDEX_DIR_NAME: String = ".codegraph"

    fun isIndexed(workingCopy: Path): Boolean = Files.isDirectory(workingCopy.resolve(INDEX_DIR_NAME))

    /**
     * Indexes [workingCopy] and returns its cost.
     *
     * Returns -- rather than throws -- a [ToolIngestCost] carrying an `absentReason` when the
     * binary does not resolve. That is a recorded absence, not a failure: an optional third-party
     * tool being uninstalled must not break corpus preparation for everyone, and the reason
     * reaching both the manifest and the console is what keeps it from being a silent skip.
     *
     * **Throws [CodeGraphIndexFailedException] when CodeGraph runs and fails, including on
     * timeout.** Loudly, with nothing recorded as a successful ingest and no partial index handed
     * on. That asymmetry with the paragraph above is deliberate. The integrity gate this suite
     * already carries exists because a Keycloak index truncated by a stopped Gradle daemon looked
     * healthy on every filesystem-level signal -- 228,587 nodes, 1.1 GB, directory present --
     * while 6 of 22 gold-cited files had never been written. A timed-out `codegraph init` must
     * never become a scored one, and it is never retried automatically: retrying a slow index is
     * how that incident happened.
     */
    fun index(
        repoId: String,
        workingCopy: Path,
        codegraphPath: String = "codegraph",
        timeout: Duration = DEFAULT_TIMEOUT,
        progress: (String) -> Unit = {}
    ): ToolIngestCost {
        if (!CodeGraphProcess.isAvailable(codegraphPath)) {
            val reason = "${RetrievalSide.CODE_GRAPH.label} binary '$codegraphPath' did not resolve, so the codegraph " +
                "working copy at $workingCopy was checked out but never indexed"
            logger.warn { "$repoId: $reason" }
            progress("$repoId: $reason")
            return absent(repoId, reason)
        }

        if (isIndexed(workingCopy)) {
            logger.info {
                "reusing existing CodeGraph index for '$repoId' at ${workingCopy.resolve(INDEX_DIR_NAME)} " +
                    "(pinned checkout, so it cannot be stale)"
            }
            return ToolIngestCost(
                repoId = repoId,
                tool = GraphTool.CODEGRAPH,
                durationMillis = 0,
                indexSizeBytes = indexSizeBytes(workingCopy)
            )
        }

        val elapsed = try {
            measureTime {
                CodeGraphProcess.run(
                    args = listOf("init", workingCopy.toAbsolutePath().toString()),
                    codegraphPath = codegraphPath,
                    timeout = timeout,
                    onProgress = { line -> progress("$repoId: $line") }
                )
            }
        } catch (e: CodeGraphExecutionException) {
            throw CodeGraphIndexFailedException(
                repoId,
                "${RetrievalSide.CODE_GRAPH.label} indexing of '$repoId' at $workingCopy " +
                    (if (e.timedOut) "exceeded its $timeout budget" else "failed") +
                    ". Not retried, and nothing partial is recorded as an ingest -- investigate " +
                    "before re-running. Cause: ${e.message}",
                e
            )
        }

        // The gate this suite already applies to ContextGraph's index has no CodeGraph equivalent,
        // so this is the one structural check available: a run that reported success and wrote no
        // index did not do what it said.
        if (!isIndexed(workingCopy)) {
            throw CodeGraphIndexFailedException(
                repoId,
                "${RetrievalSide.CODE_GRAPH.label} reported success for '$repoId' but wrote no $INDEX_DIR_NAME directory " +
                    "at $workingCopy -- refusing to record an ingest for an index that is not there."
            )
        }

        return ToolIngestCost(
            repoId = repoId,
            tool = GraphTool.CODEGRAPH,
            durationMillis = elapsed.inWholeMilliseconds,
            indexSizeBytes = indexSizeBytes(workingCopy)
        )
    }

    fun absent(repoId: String, reason: String): ToolIngestCost =
        ToolIngestCost(repoId = repoId, tool = GraphTool.CODEGRAPH, absentReason = reason)

    /** Total bytes under the index directory, or null if it cannot be walked -- never a silent 0. */
    fun indexSizeBytes(workingCopy: Path): Long? {
        val dir = workingCopy.resolve(INDEX_DIR_NAME)
        if (!Files.isDirectory(dir)) return null
        return try {
            Files.walk(dir).use { stream ->
                stream.filter { it.isRegularFile() }.mapToLong { Files.size(it) }.sum()
            }
        } catch (e: Exception) {
            logger.warn(e) { "could not measure CodeGraph index size at $dir" }
            null
        }
    }
}
