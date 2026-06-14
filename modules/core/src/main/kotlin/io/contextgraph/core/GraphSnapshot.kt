package io.contextgraph.core

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GraphSnapshot(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val artifacts: List<Artifact>,
    val generatedAt: Instant,
    val version: String = "1.0"
) {
    fun toJson(): String = graphJson.encodeToString(serializer(), this)

    companion object {
        private val graphJson = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        fun fromJson(json: String): GraphSnapshot = graphJson.decodeFromString(serializer(), json)
    }
}
