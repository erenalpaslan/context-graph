package io.contextgraph.core

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Provenance(
    val artifactId: ArtifactId,
    val path: String,
    val lineStart: Int? = null,
    val lineEnd: Int? = null,
    val page: Int? = null,
    val textSpan: String? = null,
    val extractor: String,
    val extractedAt: Instant
)
