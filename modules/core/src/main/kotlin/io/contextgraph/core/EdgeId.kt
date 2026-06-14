package io.contextgraph.core

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class EdgeId(val value: String)
