package io.contextgraph.core

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Artifact(
    val id: ArtifactId,
    val type: NodeType,
    val path: String,
    val checksum: String,
    val size: Long,
    val lastModified: Instant,
    val indexedAt: Instant
)
