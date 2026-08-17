package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * A pinned corpus repo (AC-1). [pinnedTag] and [pinnedSha] are chosen and
 * filled in by slice 02 at corpus-prep time (deferred by spec Q4) — recorded
 * in the result JSON rather than hard-coded in a spec or in source.
 */
@Serializable
data class CorpusRepo(
    val id: String,
    val name: String,
    val url: String,
    val pinnedTag: String,
    val pinnedSha: String
)
