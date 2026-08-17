package io.contextgraph.ingest.describe

import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.StorageAdapter
import io.contextgraph.ingest.ModuleTree
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * One declaration-site symbol as it is allowed to leave the machine: a file name, a kind, a
 * name, an optional fqn, and an optional doc comment. Never a body, never a raw line of source.
 */
data class SymbolEntry(
    val file: String,
    val kind: String,
    val name: String,
    val fqn: String?,
    val docComment: String?
)

/**
 * Rolls the graph's declaration-site nodes up into a per-module [SymbolEntry] list, and turns
 * that list into the deterministic text an LLM prompt (or a staleness hash) is built from.
 *
 * This is a pure read of what indexing already persisted -- no tree-sitter, no file access, no
 * network. It is the seam that keeps the guarantee "the LLM sees only the symbol inventory,
 * never raw file bodies" mechanically true: [canonicalText] can only ever emit the four fields
 * on [SymbolEntry], because that's all it's handed.
 */
object SymbolInventory {
    /**
     * Every grammar's declaration-site extractor sets `kind` on every symbol node it emits (see
     * `JavaSymbolExtractor.declarationNode`, the seam every other grammar slice copies).
     * Import/module-reference nodes never set it. Using its presence as the "is this a symbol"
     * test means a new grammar lands correctly here with zero changes to this file, and avoids
     * an allowlist of NodeTypes that would silently go stale as new grammars land.
     */
    private const val KIND_PROPERTY = "kind"

    /** Set by [io.contextgraph.treesitter.Fqn.withFqn]; read here as a string, not a project dependency. */
    private const val FQN_PROPERTY = "fqn"

    /** Not populated by any grammar today -- a forward-compat hook, read if present, never required. */
    private const val DOC_COMMENT_PROPERTY = "docComment"

    /**
     * Groups every symbol node in [storage] under the module that owns its provenance path, per
     * [ModuleTree.ownerOf]. A node with no `kind` property, no provenance, or a provenance path
     * outside every module boundary is silently excluded -- not an error, just not a symbol this
     * inventory can place.
     */
    fun collectByModule(storage: StorageAdapter, tree: ModuleTree): Map<NodeId, List<SymbolEntry>> {
        val bucket = mutableMapOf<NodeId, MutableList<SymbolEntry>>()
        for (node in storage.getAllNodes()) {
            if (node.type == NodeType.CodeModule) continue
            val kind = (node.properties[KIND_PROPERTY] as? JsonPrimitive)?.content ?: continue
            val path = storage.getProvenance(node.id.value).firstOrNull()?.path ?: continue
            val owner = tree.ownerOf(path) ?: continue
            val fqn = (node.properties[FQN_PROPERTY] as? JsonPrimitive)?.content
            val doc = (node.properties[DOC_COMMENT_PROPERTY] as? JsonPrimitive)?.content
            bucket.getOrPut(owner.id) { mutableListOf() }.add(SymbolEntry(path, kind, node.label, fqn, doc))
        }
        return bucket
    }

    /**
     * Deterministic text for a set of symbols: same entries, any order in, same string out. This
     * is both the LLM prompt input and the staleness-hash input, so its exact shape only needs
     * to satisfy one property -- stability under reordering -- not human prettiness.
     */
    fun canonicalText(entries: List<SymbolEntry>): String =
        entries.sortedWith(compareBy({ it.file }, { it.name }, { it.kind }))
            .joinToString("\n") { e ->
                buildString {
                    append(e.file).append(" :: ").append(e.kind).append(" ").append(e.name)
                    e.fqn?.let { append(" [fqn=").append(it).append("]") }
                    e.docComment?.let { append(" -- ").append(it) }
                }
            }

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
