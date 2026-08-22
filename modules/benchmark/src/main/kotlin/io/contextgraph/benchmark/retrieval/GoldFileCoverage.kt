package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.runner.GraphTool
import kotlinx.serialization.Serializable

/**
 * The three comparators, and the one place their user-facing names are written down.
 *
 * [label] carries the parenthetical deliberately. "ContextGraph" and "CodeGraph" differ by two
 * letters, and a reader skimming a table headed with the bare words will misread which one won --
 * which matters more here than it normally would, because the research run this instrument serves
 * exists precisely to find out whether the answer is CodeGraph. The parenthetical is the cheapest
 * disambiguation that survives the table being pasted, on its own, into some other document.
 *
 * Every report string comes from here rather than being retyped at each call site, so the two
 * names cannot drift apart or lose their qualifier in one table out of six.
 */
@Serializable
enum class RetrievalSide(val label: String) {
    // The two graph tools take their names from GraphTool, which owns them, so a side and the
    // tool it measures can never disagree about what to call itself. ripgrep has no GraphTool
    // entry -- it is the baseline, not a graph tool -- so its label lives here.
    CONTEXT_GRAPH(GraphTool.CONTEXTGRAPH.label),
    CODE_GRAPH(GraphTool.CODEGRAPH.label),
    RIPGREP("ripgrep (baseline)");

    companion object {
        /** The side that measures [tool]. Lets a caller holding a [GraphTool] reach the side without a `when`. */
        fun of(tool: GraphTool): RetrievalSide = when (tool) {
            GraphTool.CONTEXTGRAPH -> CONTEXT_GRAPH
            GraphTool.CODEGRAPH -> CODE_GRAPH
        }
    }
}

/** How a [GoldFileCoverage] figure was arrived at -- the three are not interchangeable. */
@Serializable
enum class CoverageBasis {
    /** Asked the tool's own index, file by file, whether it holds each cited file. A real fraction. */
    INDEX_QUERY,

    /**
     * 100% by construction: the tool searches the working tree directly and so can reach every
     * file that is on disk. True of ripgrep, and the reason its side is never gated.
     */
    READS_WORKING_TREE,

    /**
     * The tool exposes no per-file index read surface, so the question cannot be answered without
     * guessing at one. Reported as an explicit unknown rather than as 0%, or as 100%, or by
     * quietly omitting the row -- all three would be inventions.
     */
    NOT_DETERMINABLE
}

/**
 * How much of one repo's gold-fact-cited file set one tool's index can actually answer for.
 *
 * This exists to make an asymmetry visible instead of leaving it as an invisible thumb on the
 * scale. [io.contextgraph.benchmark.corpus.IndexIntegrityGate] gates the ContextGraph side only:
 * a repo whose ContextGraph index is incomplete gets *dropped* from that side, while CodeGraph's
 * equally-incomplete index would get *scored*. Left alone, that biases the comparison in
 * ContextGraph's favour.
 *
 * The two available remedies were to gate both sides identically, or to publish each tool's
 * coverage as a number. The first needs a reliable way to ask CodeGraph's index "is file X in
 * you?", and no such surface is documented -- guessing at one is exactly the silent-misparse
 * failure this instrument is most at risk of. So the second was chosen, and the report names it
 * as the chosen remedy and names the residual: a repo failing the gate is still dropped from the
 * ContextGraph side while CodeGraph's is scored. Coverage makes that fact printable; it does not
 * make it go away.
 *
 * The gate itself is deliberately left untouched. Loosening it to obtain a number would
 * manufacture the very result the research run is trying to test.
 */
@Serializable
data class GoldFileCoverage(
    val repoId: String,
    val side: RetrievalSide,
    /** How many distinct files this repo's gold facts cite. Zero means there was nothing to cover. */
    val citedFileCount: Int,
    /** How many of those the tool's index holds. `null` exactly when [basis] is [CoverageBasis.NOT_DETERMINABLE]. */
    val presentFileCount: Int?,
    val basis: CoverageBasis
) {
    init {
        require((presentFileCount == null) == (basis == CoverageBasis.NOT_DETERMINABLE)) {
            "presentFileCount must be null exactly when basis is NOT_DETERMINABLE, so an unknown " +
                "can never be read as a zero (repo '$repoId', side $side, basis $basis, " +
                "presentFileCount $presentFileCount)"
        }
    }

    /**
     * Coverage as a fraction, or `null` when it is not determinable -- never 0.0 standing in for
     * "we could not tell". A repo citing no files at all also yields `null`: 0/0 is not full
     * coverage and printing it as 100% would overstate the index.
     */
    val fraction: Double?
        get() = presentFileCount?.takeIf { citedFileCount > 0 }?.let { it.toDouble() / citedFileCount }
}
