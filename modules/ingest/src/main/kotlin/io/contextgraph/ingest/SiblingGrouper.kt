package io.contextgraph.ingest

import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.StorageAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * The `fqn` property key every declaration node carries. Defined authoritatively as
 * `FQN_PROPERTY` in `io.contextgraph.treesitter.Fqn` (modules:tree-sitter) -- duplicated
 * here as a literal rather than pulled in via a new `modules:ingest -> modules:tree-sitter`
 * dependency. This is a stable, guarded wire-format contract (every grammar's own snapshot
 * test calls `requireFqnOnDeclarations` to enforce it), not an implementation detail that
 * could drift silently underneath this class, and `modules:ingest` has never depended on
 * `modules:tree-sitter` -- only on `modules:core`, which [GraphNode.properties] already
 * belongs to. See the slice 11 report for why a new module edge wasn't judged worth it for
 * one string constant.
 */
private const val FQN_PROPERTY = "fqn"

/**
 * Pass 3 of indexing, run after pass 1's extraction and pass 2's [ReferenceResolver]:
 * groups every declaration node that shares an `fqn` -- a type extended across several
 * files, a Kotlin extension function repeated on the same receiver, an Objective-C
 * header/implementation split, TypeScript declaration merging, or plain method overloads
 * -- and links every non-primary member of the group to one primary declaration via an
 * [EdgeType.SiblingOf] edge.
 *
 * Naive and full, deliberately, mirroring [ReferenceResolver]: every call to [groupAll]
 * wipes the entire `SiblingOf` edge set and rebuilds it from [StorageAdapter.getAllNodes],
 * so a renamed or removed declaration can never leave a stale sibling edge behind, and a
 * newly indexed file's declarations join an existing group with no scoping logic of their
 * own. The largest target repo is under 5k files (see the run's interview decisions), so a
 * full scan of every node on every run is affordable -- and unlike [ReferenceResolver],
 * which issues one [StorageAdapter.findNodesByLabel] query *per unresolved reference*
 * (backed by `idx_nodes_label` since `V3__nodes_label_index.sql` -- a full table scan per
 * call before that migration existed), this pass makes exactly one call to
 * [StorageAdapter.getAllNodes] and groups every node in memory via `groupBy`. There is no
 * per-item database probe here at all, indexed or not, so an indexed `fqn` column would buy
 * nothing: the cost this class pays is one linear scan plus one in-memory group-by,
 * independent of how many fqn groups exist or how ReferenceResolver's own lookup is
 * optimized. That is why this slice adds no schema migration of its own, even though one (a
 * V3 generated column over the existing, already-persisted `fqn` property) was considered
 * and would have been a no-reparse change had it been needed.
 *
 * Grouping works off `fqn` ALONE -- no [GraphNode.type], no per-language `kind` property,
 * no language id is ever inspected here. That is what makes one implementation correct for
 * every language the task notes call out (Kotlin extension functions, Swift extension
 * blocks, Objective-C header/impl and categories, TypeScript declaration merging) without
 * a single per-language branch: whatever a grammar's `fqn` says two declarations share,
 * this class groups.
 *
 * **Choosing the primary.** "The one that actually declares the type, as opposed to
 * extending it" has no single generic signal across languages -- Swift's primary is the
 * declaration whose ID happens to equal its fqn exactly, but Objective-C's `@interface`
 * and `@implementation` are equally "primary," with nothing distinguishing them at all.
 * Rather than special-case per language, this class picks the lexicographically smallest
 * [GraphNode.id] in the group as primary: deterministic (node IDs are derived only from
 * path and scope chain, so this is stable across reindexes of unchanged source), requires
 * no language-specific knowledge, and in every concrete case this project targets happens
 * to land on the declaration a reader would call primary anyway -- a type's own file sorts
 * before a same-named `+ext@N` extension-block ID in the same directory, and a `.h` sorts
 * before its `.m`. Where no such distinguished member exists at all (a Kotlin extension on
 * a stdlib type with no in-repo declaration), the same rule simply elects one extension
 * site as the group's hub -- still satisfying "grouped with each other," never "dropped."
 */
class SiblingGrouper(private val storage: StorageAdapter) {

    fun groupAll(): GroupingStats {
        storage.deleteEdgesOfType(EdgeType.SiblingOf)

        val groups = storage.getAllNodes()
            .mapNotNull { node -> fqnOf(node)?.let { it to node } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }

        var edgesCreated = 0
        for (members in groups.values) {
            val sorted = members.sortedBy { it.id.value }
            val primary = sorted.first()
            for (sibling in sorted.drop(1)) {
                storage.upsertEdge(
                    GraphEdge(
                        id = EdgeId("sibling_of:${sibling.id.value}:${primary.id.value}"),
                        source = sibling.id,
                        target = primary.id,
                        type = EdgeType.SiblingOf,
                        confidence = ConfidenceDefaults.SIBLING_GROUPING
                    )
                )
                edgesCreated++
            }
        }

        logger.debug { "Pass 3: linked ${groups.size} fqn group(s) into $edgesCreated SiblingOf edge(s)" }
        return GroupingStats(groups = groups.size, edges = edgesCreated)
    }

    private fun fqnOf(node: GraphNode): String? =
        (node.properties[FQN_PROPERTY] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}

data class GroupingStats(val groups: Int, val edges: Int)
