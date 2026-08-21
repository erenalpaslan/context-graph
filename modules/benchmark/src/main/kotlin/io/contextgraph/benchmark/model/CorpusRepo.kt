package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * A pinned corpus repo (AC-1). [pinnedTag] and [pinnedSha] are chosen and
 * filled in by slice 02 at corpus-prep time (deferred by spec Q4) — recorded
 * in the result JSON rather than hard-coded in a spec or in source.
 *
 * [workingCopyWithPath] and [workingCopyWithoutPath] are filled in by slice
 * 02's corpus preparation step (AC-1a): two independent working-copy
 * directories at the same [pinnedSha], one ContextGraph-indexed (WITH arm),
 * one never touched (WITHOUT arm — see the spec's AC-7a for why the control
 * arm must be a physically separate checkout rather than the same directory
 * with tools disabled). Absolute filesystem paths on the machine that ran
 * corpus prep; `null` before prep has run for this repo.
 */
@Serializable
data class CorpusRepo(
    val id: String,
    val name: String,
    val url: String,
    val pinnedTag: String,
    val pinnedSha: String,
    val workingCopyWithPath: String? = null,
    val workingCopyWithoutPath: String? = null
)
