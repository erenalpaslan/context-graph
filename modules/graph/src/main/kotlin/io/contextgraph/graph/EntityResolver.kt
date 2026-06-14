package io.contextgraph.graph

import io.contextgraph.core.EdgeId
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType

class EntityResolver {
    private val nodeMap = mutableMapOf<String, GraphNode>()
    private val idRemapping = mutableMapOf<NodeId, NodeId>()

    fun resolve(nodes: List<GraphNode>): List<GraphNode> {
        for (node in nodes) {
            val key = canonicalKey(node)
            val existing = nodeMap[key]
            if (existing != null) {
                val mergedProvenance = (existing.provenance + node.provenance)
                    .distinctBy { "${it.artifactId.value}:${it.lineStart}:${it.textSpan}" }
                val merged = existing.copy(
                    provenance = mergedProvenance,
                    confidence = maxOf(existing.confidence, node.confidence)
                )
                nodeMap[key] = merged
                if (node.id != existing.id) {
                    idRemapping[node.id] = existing.id
                }
            } else {
                nodeMap[key] = node
            }
        }
        return nodeMap.values.toList()
    }

    fun resolveEdges(edges: List<GraphEdge>, resolvedNodes: List<GraphNode>): List<GraphEdge> {
        val validIds = resolvedNodes.map { it.id }.toSet()
        return edges.mapNotNull { edge ->
            val srcId = idRemapping[edge.source] ?: edge.source
            val tgtId = idRemapping[edge.target] ?: edge.target
            if (srcId in validIds && tgtId in validIds && srcId != tgtId) {
                edge.copy(
                    id = EdgeId("${EdgeId(edge.type::class.simpleName?.lowercase() ?: "edge")}:${srcId.value}:${tgtId.value}"),
                    source = srcId,
                    target = tgtId
                )
            } else null
        }.distinctBy { it.id }
    }

    fun clear() {
        nodeMap.clear()
        idRemapping.clear()
    }

    private fun canonicalKey(node: GraphNode): String {
        val typeStr = NodeType.stringify(node.type)
        return "$typeStr:${node.label.trim().lowercase()}"
    }
}
