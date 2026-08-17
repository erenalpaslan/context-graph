package io.contextgraph.treesitter

import io.contextgraph.core.ArtifactId
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.UnresolvedReference
import io.github.treesitter.ktreesitter.Language
import io.github.treesitter.ktreesitter.Node
import io.github.treesitter.ktreesitter.Tree
import kotlinx.datetime.Instant

/**
 * The seam a language plugs into.
 *
 * Every supported language implements this once and is listed once in
 * [LanguageRegistry]. Nothing outside a language's own file (and that one registry
 * line) should need to change to add a language.
 */
interface LanguageSupport {
    /** Stable identifier for this language, e.g. `"java"`, `"typescript"`, `"tsx"`. */
    val id: String

    /** Lowercase file extensions (without the leading dot) this language claims. */
    val extensions: Set<String>

    /** The tree-sitter [Language], backed by this language's compiled native grammar. */
    fun language(): Language

    /**
     * Walks an already-parsed [SymbolExtractionRequest.tree] and returns the
     * declaration-site nodes/edges it finds (types, functions/methods, properties,
     * imports -- whatever this language's grammar makes visible). Excludes the file
     * node itself; the caller ([io.contextgraph.extractors.TreeSitterExtractor]) adds that.
     *
     * Default is "extract nothing" so a grammar can be registered (parses, proves the
     * native binary loads) before its extraction is implemented. A language earns real
     * coverage by overriding this one method; nothing else in the seam changes. All
     * eight registered grammars override it as of slice 18.
     */
    fun extract(request: SymbolExtractionRequest): SymbolExtraction = SymbolExtraction.EMPTY
}

/** Everything a language's [LanguageSupport.extract] needs to build identity-correct nodes. */
data class SymbolExtractionRequest(
    val tree: Tree,
    val source: String,
    /**
     * [source] pre-encoded to UTF-8 bytes once per file, so every grammar's [Node.textIn]
     * call slices the byte array tree-sitter's own offsets actually index into, instead of
     * mis-slicing [source] itself. See [SourceText] for why this exists.
     */
    val sourceText: SourceText,
    /** Repo-relative path, e.g. `Auth/UserService.java` -- half of declaration-site identity. */
    val repoRelativePath: String,
    val artifactId: ArtifactId,
    /** [io.contextgraph.core.ResourceExtractor.id] of the caller, recorded on every [io.contextgraph.core.Provenance]. */
    val extractorId: String,
    val extractedAt: Instant
)

/**
 * A byte-faithful view of a file's source, computed once per file.
 *
 * Tree-sitter reports every [Node]'s position ([Node.getStartByte]/[Node.getEndByte]) as a
 * **byte** offset into the UTF-8 encoding of the source it parsed. ktreesitter's own
 * [Node.text] slices the char-indexed Kotlin `String` with those byte offsets instead --
 * correct only for pure-ASCII source, where "byte" and "char" coincide. A single non-ASCII
 * character anywhere in the file (an em-dash in a comment, an accented identifier, CJK, an
 * emoji, ...) throws every offset after it out of alignment: [Node.text] returns silently
 * corrupted text for nodes that follow it, and once the accumulated byte/char drift pushes
 * a late offset past the string's char length, `String.substring` throws
 * `StringIndexOutOfBoundsException` -- observed in production as a whole file (and its
 * symbols) silently dropped from the graph.
 *
 * [SourceText] holds the same source pre-encoded to UTF-8 bytes exactly once per file, so
 * [Node.textIn] can slice the correct array directly -- one encode per file, not an O(n)
 * rescan on each of a file's (often dozens of) `.text()` calls. No truncation or
 * replacement characters ever result: a byte range tree-sitter reports always lands on a
 * UTF-8 code-point boundary (tree-sitter parses the byte stream itself, never splits a
 * multi-byte sequence), so decoding it is always exact -- including characters outside the
 * BMP, which UTF-8-decode into the correct UTF-16 surrogate pair.
 */
class SourceText(source: String) {
    internal val bytes: ByteArray = source.toByteArray(Charsets.UTF_8)
}

/**
 * Byte-correct replacement for [Node.text]: decodes this node's span directly out of
 * [source]'s pre-encoded UTF-8 bytes via this node's tree-sitter-reported
 * [Node.getStartByte]/[Node.getEndByte], rather than (mis)indexing a char-indexed `String`
 * with byte offsets. See [SourceText]'s doc for why that distinction matters. Every one of
 * this module's six grammars calls this instead of [Node.text].
 */
fun Node.textIn(source: SourceText): String {
    val start = startByte.toInt()
    val end = endByte.toInt()
    return String(source.bytes, start, end - start, Charsets.UTF_8)
}

/** Nodes and edges one language's [LanguageSupport.extract] produced for one file. */
data class SymbolExtraction(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    /**
     * Call-site occurrences this language's walk observed -- pass 1's other output,
     * alongside [nodes]/[edges]. Feeds [io.contextgraph.core.ExtractionResult.references]
     * (via [io.contextgraph.extractors.TreeSitterExtractor]), which
     * `io.contextgraph.ingest.ReferenceResolver` (pass 2) resolves against the symbol
     * table. Defaults to empty so a grammar that implements [LanguageSupport.extract]
     * without emitting call sites yet still compiles unchanged -- see slice 20.
     */
    val references: List<UnresolvedReference> = emptyList()
) {
    companion object {
        val EMPTY = SymbolExtraction(emptyList(), emptyList())
    }
}
