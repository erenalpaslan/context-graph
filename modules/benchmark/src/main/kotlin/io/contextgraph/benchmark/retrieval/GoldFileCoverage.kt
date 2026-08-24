package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.runner.GraphTool
import kotlinx.serialization.Serializable

/**
 * The four comparators, and the one place their user-facing names are written down.
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
    // tool it measures can never disagree about what to call itself. The two text-search
    // baselines have no GraphTool entry -- they are baselines, not graph tools -- so their labels
    // live here.
    CONTEXT_GRAPH(GraphTool.CONTEXTGRAPH.label),
    CODE_GRAPH(GraphTool.CODEGRAPH.label),

    /**
     * The honest floor: what a developer with nothing but a stock shell gets. Its qualifier says
     * *base-system* rather than just "baseline", because "bash" alone would not distinguish it
     * from [RIPGREP] -- which is also run from a shell, but is a third-party install the floor is
     * not allowed to assume.
     */
    BASH("bash (base-system shell only)"),
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
     * file that is on disk. True of both text-search baselines, [RetrievalSide.RIPGREP] and
     * [RetrievalSide.BASH], and the reason neither side is ever gated. A working-tree side must
     * be recorded with this basis rather than left out: an omitted row reads as
     * [NOT_DETERMINABLE], which for these two would be false.
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
    val basis: CoverageBasis,
    /**
     * Every node type in this side's index, mapped to how many nodes carry it -- or `null` when
     * the side has no index this can be asked of (both text-search baselines), or has one that
     * cannot be asked (CodeGraph exposes no node-type census).
     *
     * **A file being *in* the index and the indexer having *understood* it are different facts,
     * and [presentFileCount] alone conflates them.** A repo written in a language no grammar is
     * registered for still gets its files read, hashed and stored: the coverage figure above
     * comes out at 100% while every retrieval query returns nothing, because there is not one
     * declaration in there to match. That was the real state of `gin` in the three-way run of
     * 2026-08-22 -- 100% file coverage, zero Go symbols, a flat 0.0% down every column -- and a
     * reader who saw only the score would have read an extraction gap as a retrieval verdict.
     *
     * Recording the census is what lets the report tell those apart *from the result document
     * alone*, with no hardcoded sentence about any particular repo or language: a repo whose
     * [extractedDeclarationCount] is 0 gets the disclosure, and stops getting it the moment a
     * grammar for its language lands and the number moves. `null` is not zero here either --
     * "nobody counted" is printed as its own state.
     *
     * Additive with a default, like every other field added to this document, so archived
     * results still decode.
     */
    val extractedNodeCounts: Map<String, Int>? = null
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

    /**
     * How many nodes in this index are *declarations* -- something the source parser found in a
     * file, as opposed to the file itself or prose extracted from it. `null` when no census was
     * taken; 0 means one was taken and found none, which is the state worth disclosing.
     */
    val extractedDeclarationCount: Int?
        get() = extractedNodeCounts?.filterKeys { it in DECLARATION_NODE_TYPES }?.values?.sum()

    companion object {
        /**
         * The node types a language grammar emits for a declaration it parsed out of source.
         *
         * Deliberately narrower than "everything [io.contextgraph.core.NodeType] does not call a
         * file type". That wider set includes `Concept` (extracted from prose, present in
         * abundance in a repo whose code was never parsed) and `Module` (which
         * `ConfigExtractor` emits for every dependency in a manifest, with no grammar involved) --
         * either would show a comfortable non-zero for exactly the repo this count exists to
         * expose. These four are what the grammars in `modules:tree-sitter` actually produce for
         * a declaration site: `Interface` arrives as a `Custom` type from the Java and
         * TypeScript grammars, which is why it is matched by name rather than by singleton.
         *
         * A count is a count and not a proof: `ConfigExtractor` also emits `Function` for an npm
         * script, so a JavaScript repo whose grammar broke could still show a handful. That is
         * why the report prints the whole census beside the total rather than the total alone --
         * "12 `Function`, 4127 `CodeFile`" reads as the gap it is.
         */
        val DECLARATION_NODE_TYPES = setOf("Function", "Method", "Class", "Interface")
    }
}
