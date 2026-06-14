package io.contextgraph.core

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ArtifactId(val value: String)
