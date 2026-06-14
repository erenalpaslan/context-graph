package io.contextgraph.report

import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.NodeType
import io.contextgraph.core.StorageAdapter
import io.contextgraph.graph.GraphAlgorithms
import kotlinx.datetime.Clock

class ReportGenerator(
    private val storage: StorageAdapter,
    private val algorithms: GraphAlgorithms = GraphAlgorithms()
) {
    fun generate(): String {
        val stats = storage.getStats()
        val allNodes = storage.getAllNodes()
        val allEdges = storage.getAllEdges()
        val allArtifacts = storage.getAllArtifacts()

        val g = algorithms.buildJGraphT(allNodes, allEdges)
        val pageRanks = algorithms.pageRank(g)
        val components = algorithms.connectedComponents(allNodes, allEdges)

        val topNodes = allNodes
            .sortedByDescending { pageRanks[it.id] ?: 0.0 }
            .take(10)

        val highDegree = allNodes
            .sortedByDescending { algorithms.inDegree(g, it.id) + algorithms.outDegree(g, it.id) }
            .take(10)

        val lowConfidence = allEdges.filter { it.confidence < ConfidenceDefaults.LOW_CONFIDENCE_THRESHOLD }
        val isolated = allNodes.filter {
            algorithms.inDegree(g, it.id) == 0 && algorithms.outDegree(g, it.id) == 0
        }

        val typeCounts = allArtifacts.groupingBy { NodeType.stringify(it.type) }.eachCount()

        return buildString {
            appendLine("# GRAPH_REPORT")
            appendLine()
            appendLine("Generated: ${Clock.System.now()}")
            appendLine()

            appendLine("## Project Overview")
            appendLine()
            appendLine("| Metric | Count |")
            appendLine("|--------|-------|")
            appendLine("| Artifacts | ${stats.artifactCount} |")
            appendLine("| Nodes | ${stats.nodeCount} |")
            appendLine("| Edges | ${stats.edgeCount} |")
            appendLine("| Connected Components | ${components.size} |")
            appendLine()

            if (typeCounts.isNotEmpty()) {
                appendLine("### Artifact Breakdown")
                appendLine()
                typeCounts.entries.sortedByDescending { it.value }.forEach { (type, count) ->
                    appendLine("- **$type**: $count")
                }
                appendLine()
            }

            appendLine("## Key Entities (by PageRank)")
            appendLine()
            topNodes.forEach { node ->
                val rank = pageRanks[node.id] ?: 0.0
                appendLine("- **${node.label}** [${NodeType.stringify(node.type)}] — rank=${String.format("%.4f", rank)}, confidence=${node.confidence}")
            }
            appendLine()

            appendLine("## Major Clusters")
            appendLine()
            components.sortedByDescending { it.size }.take(5).forEachIndexed { i, comp ->
                val labels = comp.take(3).mapNotNull { storage.getNode(it)?.label }
                appendLine("### Cluster ${i + 1} (${comp.size} nodes)")
                appendLine("Sample: ${labels.joinToString(", ")}")
                appendLine()
            }

            appendLine("## High-Degree Nodes (Important Dependencies)")
            appendLine()
            highDegree.forEach { node ->
                val deg = algorithms.inDegree(g, node.id) + algorithms.outDegree(g, node.id)
                appendLine("- **${node.label}** [${NodeType.stringify(node.type)}] — degree=$deg")
            }
            appendLine()

            if (lowConfidence.isNotEmpty()) {
                appendLine("## Low-Confidence Relationships (< ${ConfidenceDefaults.LOW_CONFIDENCE_THRESHOLD})")
                appendLine()
                lowConfidence.take(20).forEach { edge ->
                    val src = storage.getNode(edge.source)?.label ?: edge.source.value
                    val tgt = storage.getNode(edge.target)?.label ?: edge.target.value
                    appendLine("- $src → $tgt [confidence=${edge.confidence}]")
                }
                appendLine()
            }

            if (isolated.isNotEmpty()) {
                appendLine("## Isolated Nodes (No Connections)")
                appendLine()
                isolated.take(20).forEach { node ->
                    appendLine("- ${node.label} [${NodeType.stringify(node.type)}]")
                }
                appendLine()
            }
        }
    }
}
