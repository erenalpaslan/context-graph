package io.contextgraph.extractors

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractionResult
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.core.ResourceExtractor
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonPrimitive
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import java.nio.file.Path
import kotlin.io.path.readText

class MarkdownExtractor : ResourceExtractor {
    override val id = "markdown"
    override val supportedTypes = setOf(NodeType.MarkdownFile, NodeType.Document)

    override suspend fun extract(artifact: Artifact, context: ExtractionContext): ExtractionResult {
        val content = Path.of(artifact.path).readText()
        val parser = Parser.builder()
            .extensions(listOf(TablesExtension.create(), HeadingAnchorExtension.create()))
            .build()
        val doc = parser.parse(content)

        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val now = Clock.System.now()

        val fileNode = GraphNode(
            id = NodeId("file:${artifact.id.value}"),
            type = artifact.type,
            label = Path.of(artifact.path).fileName.toString(),
            confidence = 1.0,
            provenance = listOf(Provenance(artifact.id, artifact.path, extractor = id, extractedAt = now))
        )
        nodes.add(fileNode)

        val sectionStack = mutableListOf<GraphNode>()

        doc.accept(object : AbstractVisitor() {
            private var lineCounter = 1

            override fun visit(heading: Heading) {
                val text = extractText(heading)
                if (text.isBlank()) { visitChildren(heading); return }

                val level = heading.level
                val nodeId = NodeId("section:${artifact.id.value}:$text")
                val sectionNode = GraphNode(
                    id = nodeId,
                    type = NodeType.Concept,
                    label = text,
                    properties = mapOf("headingLevel" to JsonPrimitive(level)),
                    confidence = ConfidenceDefaults.MARKDOWN_HEADING,
                    provenance = listOf(Provenance(artifact.id, artifact.path, textSpan = text, extractor = id, extractedAt = now))
                )
                nodes.add(sectionNode)

                // Pop sections at same or deeper level
                while (sectionStack.isNotEmpty() && (sectionStack.last().properties["headingLevel"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 >= level) {
                    sectionStack.removeLast()
                }

                val parent = sectionStack.lastOrNull() ?: fileNode
                edges.add(GraphEdge(
                    id = EdgeId("contains:${parent.id.value}:${nodeId.value}"),
                    source = parent.id,
                    target = nodeId,
                    type = EdgeType.Contains,
                    confidence = ConfidenceDefaults.MARKDOWN_HEADING
                ))
                sectionStack.add(sectionNode)
                visitChildren(heading)
            }

            override fun visit(link: Link) {
                val destination = link.destination
                if (destination.isNotBlank() && !destination.startsWith("#")) {
                    val parent = sectionStack.lastOrNull() ?: fileNode
                    val targetId = NodeId("link:$destination")
                    if (nodes.none { it.id == targetId }) {
                        nodes.add(GraphNode(
                            id = targetId,
                            type = NodeType.Document,
                            label = destination,
                            confidence = ConfidenceDefaults.MARKDOWN_CONCEPT
                        ))
                    }
                    edges.add(GraphEdge(
                        id = EdgeId("references:${parent.id.value}:$destination"),
                        source = parent.id,
                        target = targetId,
                        type = EdgeType.References,
                        confidence = ConfidenceDefaults.MARKDOWN_CONCEPT
                    ))
                }
                visitChildren(link)
            }

            override fun visit(emphasis: StrongEmphasis) {
                val text = extractText(emphasis).trim()
                if (text.length > 3 && text.split(" ").size >= 2) {
                    val nodeId = NodeId("concept:${text.lowercase().replace(" ", "_")}")
                    if (nodes.none { it.id == nodeId }) {
                        nodes.add(GraphNode(
                            id = nodeId,
                            type = NodeType.Concept,
                            label = text,
                            confidence = ConfidenceDefaults.MARKDOWN_CONCEPT,
                            provenance = listOf(Provenance(artifact.id, artifact.path, textSpan = text, extractor = id, extractedAt = now))
                        ))
                    }
                    val parent = sectionStack.lastOrNull() ?: fileNode
                    edges.add(GraphEdge(
                        id = EdgeId("defines:${parent.id.value}:${nodeId.value}"),
                        source = parent.id,
                        target = nodeId,
                        type = EdgeType.Defines,
                        confidence = ConfidenceDefaults.MARKDOWN_CONCEPT
                    ))
                }
                visitChildren(emphasis)
            }
        })

        // Detect requirement/decision patterns in raw text
        detectSpecialPatterns(content, artifact, nodes, edges, now)

        return ExtractionResult(artifact, nodes, edges)
    }

    private fun detectSpecialPatterns(
        content: String,
        artifact: Artifact,
        nodes: MutableList<GraphNode>,
        edges: MutableList<GraphEdge>,
        now: kotlinx.datetime.Instant
    ) {
        val lines = content.lines()
        lines.forEachIndexed { idx, line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("## Decision:") || trimmed.startsWith("### Decision:") -> {
                    val label = trimmed.substringAfter(":").trim()
                    if (label.isNotBlank()) {
                        nodes.add(GraphNode(
                            id = NodeId("decision:${artifact.id.value}:$idx"),
                            type = NodeType.Decision,
                            label = label,
                            confidence = ConfidenceDefaults.MARKDOWN_HEADING,
                            provenance = listOf(Provenance(artifact.id, artifact.path, lineStart = idx + 1, textSpan = line, extractor = id, extractedAt = now))
                        ))
                    }
                }
                Regex("^-\\s+\\[[ x]\\]\\s+.+").matches(trimmed) -> {
                    val label = trimmed.replace(Regex("^-\\s+\\[[ x]\\]\\s+"), "")
                    nodes.add(GraphNode(
                        id = NodeId("requirement:${artifact.id.value}:$idx"),
                        type = NodeType.Requirement,
                        label = label,
                        confidence = ConfidenceDefaults.MARKDOWN_CONCEPT,
                        provenance = listOf(Provenance(artifact.id, artifact.path, lineStart = idx + 1, textSpan = line, extractor = id, extractedAt = now))
                    ))
                }
                trimmed.contains(Regex("\\b(MUST|SHALL|REQUIRED|SHOULD|RECOMMENDED)\\b")) -> {
                    nodes.add(GraphNode(
                        id = NodeId("requirement:${artifact.id.value}:$idx"),
                        type = NodeType.Requirement,
                        label = trimmed.take(120),
                        confidence = ConfidenceDefaults.MARKDOWN_CONCEPT,
                        provenance = listOf(Provenance(artifact.id, artifact.path, lineStart = idx + 1, textSpan = trimmed.take(120), extractor = id, extractedAt = now))
                    ))
                }
            }
        }
    }

    private fun extractText(node: org.commonmark.node.Node): String {
        val sb = StringBuilder()
        node.accept(object : AbstractVisitor() {
            override fun visit(text: Text) { sb.append(text.literal) }
            override fun visit(code: Code) { sb.append(code.literal) }
        })
        return sb.toString()
    }
}
