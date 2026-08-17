package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * `--profile smoke|full` (AC-16, AC-17). Scope only — both profiles run the
 * exact same code path (wired up in slice 12); there is no separate "smoke"
 * implementation.
 */
@Serializable
enum class Profile {
    SMOKE,
    FULL
}
