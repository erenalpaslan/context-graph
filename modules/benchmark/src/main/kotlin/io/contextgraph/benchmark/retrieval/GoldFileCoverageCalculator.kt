package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.Question
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.GraphDb
import io.contextgraph.storage.SqliteStorageAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Computes, per repo, how much of the gold-fact-cited file set each tool's index can answer for.
 *
 * This is the published remedy for an asymmetry that would otherwise sit invisibly in
 * ContextGraph's favour: [io.contextgraph.benchmark.corpus.IndexIntegrityGate] gates the
 * ContextGraph side only, so an incomplete ContextGraph index is *dropped* from the scores while
 * an equally incomplete CodeGraph index would be *scored*. The gate is deliberately left
 * untouched -- loosening it to obtain a number is exactly the manufactured result this instrument
 * exists to avoid -- so the honest alternative is to print each tool's coverage next to its
 * scores and let a reader see the difference in denominators.
 *
 * See [GoldFileCoverage] for the three states and why an unknown is never rendered as a zero.
 */
object GoldFileCoverageCalculator {

    fun forRepo(
        repoId: String,
        repoQuestions: List<Question>,
        contextGraphWorkingCopy: Path,
        codeGraph: CodeGraphRetrievalRunner?
    ): List<GoldFileCoverage> {
        val cited = repoQuestions
            .flatMap { it.goldFacts }
            .map { it.evidence.file }
            .distinct()

        return listOf(
            contextGraphCoverage(repoId, cited, contextGraphWorkingCopy),
            codeGraphCoverage(repoId, cited, codeGraph),
            // ripgrep searches the working tree directly, so every file that exists on disk is
            // reachable to it. That is why its side is never gated, and saying so explicitly is
            // what stops "100%" reading as a suspiciously perfect measurement.
            GoldFileCoverage(repoId, RetrievalSide.RIPGREP, cited.size, cited.size, CoverageBasis.READS_WORKING_TREE)
        )
    }

    /** The same question the integrity gate asks, asked of the graph rather than of the filesystem. */
    private fun contextGraphCoverage(repoId: String, cited: List<String>, workingCopy: Path): GoldFileCoverage {
        if (cited.isEmpty()) {
            return GoldFileCoverage(repoId, RetrievalSide.CONTEXT_GRAPH, 0, 0, CoverageBasis.INDEX_QUERY)
        }
        if (!GraphDb.exists(workingCopy)) {
            return GoldFileCoverage(repoId, RetrievalSide.CONTEXT_GRAPH, cited.size, null, CoverageBasis.NOT_DETERMINABLE)
        }
        return try {
            val storage = SqliteStorageAdapter(GraphDb.forRead(workingCopy))
            try {
                val present = cited.count { storage.getArtifact(ArtifactId(it)) != null }
                GoldFileCoverage(repoId, RetrievalSide.CONTEXT_GRAPH, cited.size, present, CoverageBasis.INDEX_QUERY)
            } finally {
                storage.close()
            }
        } catch (e: Exception) {
            // Same discipline as the CodeGraph side: an unreadable index yields an explicit
            // unknown, never a zero, and the reason is logged rather than discarded.
            logger.warn(e) { "could not query ContextGraph's index at $workingCopy for repo '$repoId'; reporting coverage as not determinable" }
            GoldFileCoverage(repoId, RetrievalSide.CONTEXT_GRAPH, cited.size, null, CoverageBasis.NOT_DETERMINABLE)
        }
    }

    /**
     * Asked through `codegraph files --json`, which turned out to exist -- the per-file index read
     * surface whose absence was the reason coverage was published instead of the CodeGraph side
     * being gated symmetrically. Where it cannot be read, this degrades to an explicit unknown
     * rather than guessing.
     */
    private fun codeGraphCoverage(
        repoId: String,
        cited: List<String>,
        codeGraph: CodeGraphRetrievalRunner?
    ): GoldFileCoverage {
        val indexed = codeGraph?.indexedFiles()
            ?: return GoldFileCoverage(repoId, RetrievalSide.CODE_GRAPH, cited.size, null, CoverageBasis.NOT_DETERMINABLE)
        if (cited.isEmpty()) {
            return GoldFileCoverage(repoId, RetrievalSide.CODE_GRAPH, 0, 0, CoverageBasis.INDEX_QUERY)
        }
        return GoldFileCoverage(
            repoId,
            RetrievalSide.CODE_GRAPH,
            cited.size,
            cited.count { it in indexed },
            CoverageBasis.INDEX_QUERY
        )
    }
}
