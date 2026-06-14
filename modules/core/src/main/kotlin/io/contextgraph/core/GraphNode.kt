package io.contextgraph.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class GraphNode(
    val id: NodeId,
    val type: NodeType,
    val label: String,
    val properties: Map<String, JsonElement> = emptyMap(),
    val provenance: List<Provenance> = emptyList(),
    val confidence: Double = 1.0
)
