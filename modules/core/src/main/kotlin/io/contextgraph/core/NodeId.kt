package io.contextgraph.core

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class NodeId(val value: String)
