package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.runner.GraphTool
import kotlinx.serialization.Serializable

/**
 * What it cost to build one tool's index for one repo: how long it took, and how much disk the
 * resulting index occupies.
 *
 * Recorded per tool so ingest cost stays comparable across ContextGraph and CodeGraph, and stays
 * separate from query cost -- the two answer different questions, and a tool that indexes slowly
 * but answers instantly should not be able to hide either fact behind the other.
 *
 * [absentReason] is the third state, and it is the reason the two numbers are nullable rather
 * than defaulting to 0. A missing index is not a free one. Corpus preparation records why it did
 * not build one -- the binary did not resolve, the run was not asked to -- and the report prints
 * that reason instead of a zero that would read as "indexing this repo costs nothing".
 */
@Serializable
data class ToolIngestCost(
    val repoId: String,
    val tool: GraphTool,
    /** Wall-clock duration of the indexing run, or null when no index was built. */
    val durationMillis: Long? = null,
    /** Size on disk of the resulting index, or null when no index was built or it could not be measured. */
    val indexSizeBytes: Long? = null,
    /** Why no index was built, or null when one was. Exactly one of this and [durationMillis] is non-null. */
    val absentReason: String? = null
) {
    init {
        require((absentReason == null) != (durationMillis == null)) {
            "exactly one of durationMillis and absentReason must be set, so a missing index is " +
                "never reported as a zero-cost one (repo '$repoId', tool ${tool.id}, " +
                "durationMillis $durationMillis, absentReason $absentReason)"
        }
    }
}
