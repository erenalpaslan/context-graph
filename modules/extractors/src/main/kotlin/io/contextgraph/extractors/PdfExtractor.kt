package io.contextgraph.extractors

import io.contextgraph.core.Artifact
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
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File

class PdfExtractor : ResourceExtractor {
    override val id = "pdf"
    override val supportedTypes = setOf(NodeType.PDF, NodeType.ResearchPaper)

    override suspend fun extract(artifact: Artifact, context: ExtractionContext): ExtractionResult {
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val now = Clock.System.now()

        val prov = { page: Int, span: String ->
            Provenance(artifact.id, artifact.path, page = page, textSpan = span.take(200), extractor = id, extractedAt = now)
        }

        Loader.loadPDF(File(artifact.path)).use { doc ->
            val stripper = PDFTextStripper()
            val pageCount = doc.numberOfPages

            val fileNode = GraphNode(
                id = NodeId("file:${artifact.id.value}"),
                type = artifact.type,
                label = File(artifact.path).name,
                properties = mapOf("pageCount" to JsonPrimitive(pageCount)),
                confidence = 1.0,
                provenance = listOf(Provenance(artifact.id, artifact.path, extractor = id, extractedAt = now))
            )
            nodes.add(fileNode)

            var prevSectionNode: GraphNode? = null
            val citationRegex = Regex("""\[(\d+)\]|\[([A-Z][a-z]+(?:\s+et\s+al\.?)?,\s*\d{4})\]""")

            for (pageNum in 1..pageCount) {
                stripper.startPage = pageNum
                stripper.endPage = pageNum
                val pageText = stripper.getText(doc).trim()
                if (pageText.isBlank()) continue

                val pageNode = GraphNode(
                    id = NodeId("page:${artifact.id.value}:$pageNum"),
                    type = NodeType.Concept,
                    label = "Page $pageNum",
                    properties = mapOf("page" to JsonPrimitive(pageNum)),
                    confidence = 1.0,
                    provenance = listOf(prov(pageNum, pageText.take(100)))
                )
                nodes.add(pageNode)
                edges.add(GraphEdge(
                    id = EdgeId("contains:${fileNode.id.value}:${pageNode.id.value}"),
                    source = fileNode.id, target = pageNode.id, type = EdgeType.Contains, confidence = 1.0
                ))

                // Detect section headings: short lines that are mostly uppercase or title-case
                val lines = pageText.lines()
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.length in 5..100 && looksLikeHeading(trimmed)) {
                        val sectionId = NodeId("section:${artifact.id.value}:${trimmed.lowercase().replace(" ", "_")}")
                        if (nodes.none { it.id == sectionId }) {
                            val sectionNode = GraphNode(
                                id = sectionId,
                                type = NodeType.Concept,
                                label = trimmed,
                                confidence = ConfidenceDefaults.PDF_SECTION,
                                provenance = listOf(prov(pageNum, trimmed))
                            )
                            nodes.add(sectionNode)
                            edges.add(GraphEdge(
                                id = EdgeId("contains:${fileNode.id.value}:${sectionId.value}"),
                                source = fileNode.id, target = sectionId, type = EdgeType.Contains, confidence = ConfidenceDefaults.PDF_SECTION
                            ))
                            prevSectionNode = sectionNode
                        }
                    }
                }

                // Extract citations
                citationRegex.findAll(pageText).forEach { m ->
                    val citation = m.value
                    val citId = NodeId("citation:${artifact.id.value}:${citation.replace(Regex("[^\\w]"), "_")}")
                    if (nodes.none { it.id == citId }) {
                        nodes.add(GraphNode(
                            id = citId,
                            type = NodeType.Claim,
                            label = citation,
                            confidence = ConfidenceDefaults.PDF_SECTION,
                            provenance = listOf(prov(pageNum, citation))
                        ))
                    }
                    val parent = prevSectionNode ?: fileNode
                    edges.add(GraphEdge(
                        id = EdgeId("cites:${parent.id.value}:${citId.value}:$pageNum"),
                        source = parent.id, target = citId, type = EdgeType.Cites, confidence = ConfidenceDefaults.PDF_SECTION
                    ))
                }
            }
        }

        return ExtractionResult(artifact, nodes, edges)
    }

    private fun looksLikeHeading(line: String): Boolean {
        if (line.endsWith(".") || line.endsWith(",")) return false
        val words = line.split(" ")
        if (words.size > 10) return false
        val capitalWords = words.count { it.isNotEmpty() && it[0].isUpperCase() }
        return capitalWords.toDouble() / words.size > 0.6
    }
}
