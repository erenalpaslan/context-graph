package io.contextgraph.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class GraphEdge(
    val id: EdgeId,
    val source: NodeId,
    val target: NodeId,
    val type: EdgeType,
    val properties: Map<String, JsonElement> = emptyMap(),
    val provenance: List<Provenance> = emptyList(),
    val confidence: Double = 1.0
)
