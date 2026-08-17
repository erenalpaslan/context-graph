package io.contextgraph.ingest

import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.StorageAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * Pass 2 of indexing: resolves every [io.contextgraph.core.UnresolvedReference] pass 1 has
 * ever persisted -- not just the ones touched by this run's pass 1 -- against the current
 * declaration symbol table, and materialises the result as `Calls` edges.
 *
 * Naive and full, deliberately (see slice 09's notes: the largest target repo is under 5k
 * files, so re-resolving everything on every run is affordable and obviously correct;
 * scoped invalidation is an explicit non-goal here). Every call to [resolveAll] wipes the
 * *entire* `Calls` edge set and rebuilds it from scratch by matching
 * [io.contextgraph.core.UnresolvedReference.referenceName] against [io.contextgraph.core.GraphNode.label]
 * across every declaration currently in [storage].
 *
 * This full-rebuild-from-persisted-state design is what makes the two hard incremental
 * requirements fall out for free, with no scoping logic of their own:
 *
 *  - **A reference in an unchanged file resolves against a just-edited file.** Pass 1 only
 *    deletes and re-emits a file's own unresolved references when that file is actually
 *    reprocessed (checksum changed). A reference belonging to a skipped file is therefore
 *    still sitting in [StorageAdapter.getAllUnresolvedReferences] this call, and any
 *    declaration a different, just-reindexed file added is already visible through
 *    [StorageAdapter.findNodesByLabel] by the time this runs -- so resolution picks up the
 *    new match without the unchanged file being touched at all.
 *  - **No edge is left pointing at a node that no longer exists.** Because the entire
 *    `Calls` set is deleted and rebuilt from the *current* symbol table every time, a
 *    reference whose target was renamed or removed simply fails to resolve again instead of
 *    leaving a stale edge behind. (Structural edges another artifact holds into a file being
 *    reindexed are a separate concern, handled by `SqliteStorageAdapter.deleteNodesForArtifact`
 *    excluding `Calls` from its own per-node cleanup -- this class owns rebuilding the set
 *    that decision depends on existing.)
 *
 * Resolution is graded, not just unambiguous-or-nothing: [ResolutionLadder] (slice 10) picks
 * the most precise rung -- local scope, file imports, same directory, repo-wide unique name
 * -- that yields any same-name candidates, and this class emits an edge to each candidate at
 * that rung, at its confidence, provided there are no more than
 * [ConfidenceDefaults.CALL_RESOLUTION_CANDIDATE_CAP] of them; more than that and the
 * reference stays unresolved rather than guessing among a hairball of same-named
 * declarations.
 *
 * Must run strictly after pass 1's write phase has fully drained -- never concurrently with
 * it -- so the single-writer property [IngestPipeline] depends on for SQLite lock avoidance
 * is preserved. [IngestPipeline.index] enforces this by calling [resolveAll] only after its
 * producer/consumer `coroutineScope` has returned.
 */
class ReferenceResolver(private val storage: StorageAdapter) {

    fun resolveAll(): ResolutionStats {
        storage.deleteEdgesOfType(EdgeType.Calls)

        val references = storage.getAllUnresolvedReferences()
        var resolved = 0
        var unresolved = 0

        for (reference in references) {
            val candidates = storage.findNodesByLabel(reference.referenceName)
            val match = ResolutionLadder.resolve(reference, candidates, storage)

            if (match == null || match.second.size > ConfidenceDefaults.CALL_RESOLUTION_CANDIDATE_CAP) {
                unresolved++
                continue
            }

            val (rung, rungCandidates) = match
            val confidence = rung.confidenceFor(ambiguous = rungCandidates.size > 1)

            for (target in rungCandidates) {
                storage.upsertEdge(
                    GraphEdge(
                        id = EdgeId("calls:${reference.referringSymbolId.value}:${target.id.value}"),
                        source = reference.referringSymbolId,
                        target = target.id,
                        type = EdgeType.Calls,
                        confidence = confidence,
                        properties = mapOf("rung" to JsonPrimitive(rung.label))
                    )
                )
            }
            resolved++
        }

        logger.debug {
            "Pass 2: resolved $resolved/${references.size} unresolved references into Calls " +
                "edges ($unresolved left unresolved: no candidates anywhere, or more than " +
                "${ConfidenceDefaults.CALL_RESOLUTION_CANDIDATE_CAP} at the rung that matched)"
        }
        return ResolutionStats(resolved = resolved, unresolved = unresolved)
    }
}

data class ResolutionStats(val resolved: Int, val unresolved: Int)
