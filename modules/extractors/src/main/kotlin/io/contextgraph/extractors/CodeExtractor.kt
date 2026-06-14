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
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Regex-based code extractor supporting Kotlin, Java, Python, TypeScript, JavaScript, Go, Rust.
 * Tree-sitter JNI bindings can replace this for higher fidelity in the future.
 */
class CodeExtractor : ResourceExtractor {
    override val id = "code"
    override val supportedTypes = setOf(NodeType.CodeFile, NodeType.TestFile)

    override suspend fun extract(artifact: Artifact, context: ExtractionContext): ExtractionResult {
        val path = Path.of(artifact.path)
        val content = path.readText()
        val ext = path.extension.lowercase()
        val now = Clock.System.now()

        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()

        val fileNode = GraphNode(
            id = NodeId("file:${artifact.id.value}"),
            type = artifact.type,
            label = path.name,
            properties = mapOf("language" to JsonPrimitive(ext)),
            confidence = 1.0,
            provenance = listOf(Provenance(artifact.id, artifact.path, extractor = id, extractedAt = now))
        )
        nodes.add(fileNode)

        val lines = content.lines()
        val prov = { line: Int, span: String -> Provenance(artifact.id, artifact.path, lineStart = line + 1, textSpan = span, extractor = id, extractedAt = now) }

        when (ext) {
            "kt" -> extractKotlin(artifact, fileNode, lines, nodes, edges, prov)
            "java" -> extractJava(artifact, fileNode, lines, nodes, edges, prov)
            "py" -> extractPython(artifact, fileNode, lines, nodes, edges, prov)
            "ts", "tsx" -> extractTypeScript(artifact, fileNode, lines, nodes, edges, prov)
            "js", "jsx" -> extractJavaScript(artifact, fileNode, lines, nodes, edges, prov)
            "go" -> extractGo(artifact, fileNode, lines, nodes, edges, prov)
            "rs" -> extractRust(artifact, fileNode, lines, nodes, edges, prov)
        }

        return ExtractionResult(artifact, nodes, edges)
    }

    private fun addChild(
        parent: GraphNode,
        nodeId: NodeId,
        type: NodeType,
        label: String,
        confidence: Double,
        prov: Provenance,
        nodes: MutableList<GraphNode>,
        edges: MutableList<GraphEdge>,
        extraProps: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
    ) {
        if (nodes.any { it.id == nodeId }) return
        nodes.add(GraphNode(id = nodeId, type = type, label = label, properties = extraProps, confidence = confidence, provenance = listOf(prov)))
        edges.add(GraphEdge(
            id = EdgeId("contains:${parent.id.value}:${nodeId.value}"),
            source = parent.id, target = nodeId, type = EdgeType.Contains, confidence = confidence
        ))
    }

    private fun extractKotlin(
        artifact: Artifact, fileNode: GraphNode, lines: List<String>,
        nodes: MutableList<GraphNode>, edges: MutableList<GraphEdge>,
        prov: (Int, String) -> Provenance
    ) {
        val importRegex = Regex("""^import\s+([\w.]+)""")
        val classRegex = Regex("""^(?:public\s+|private\s+|internal\s+|open\s+|abstract\s+|sealed\s+|data\s+)*(?:class|interface|object|enum\s+class)\s+(\w+)""")
        val funRegex = Regex("""^\s*(?:public\s+|private\s+|internal\s+|protected\s+|override\s+|suspend\s+|inline\s+|operator\s+)*fun\s+(\w+)\s*\(""")
        val packageRegex = Regex("""^package\s+([\w.]+)""")

        lines.forEachIndexed { i, line ->
            val trimmed = line.trim()
            packageRegex.find(trimmed)?.let { m ->
                val pkg = m.groupValues[1]
                val pkgId = NodeId("package:$pkg")
                if (nodes.none { it.id == pkgId }) {
                    nodes.add(GraphNode(id = pkgId, type = NodeType.Package, label = pkg, confidence = ConfidenceDefaults.AST_SYMBOL, provenance = listOf(prov(i, line))))
                }
                edges.add(GraphEdge(id = EdgeId("contains:${pkgId.value}:${fileNode.id.value}"), source = pkgId, target = fileNode.id, type = EdgeType.Contains, confidence = ConfidenceDefaults.AST_SYMBOL))
            }
            importRegex.find(trimmed)?.let { m ->
                val import = m.groupValues[1]
                val importId = NodeId("import:${artifact.id.value}:$import")
                nodes.add(GraphNode(id = importId, type = NodeType.Module, label = import, confidence = ConfidenceDefaults.IMPORT_RELATION, provenance = listOf(prov(i, line))))
                edges.add(GraphEdge(id = EdgeId("imports:${fileNode.id.value}:$import"), source = fileNode.id, target = importId, type = EdgeType.Imports, confidence = ConfidenceDefaults.IMPORT_RELATION))
            }
            classRegex.find(trimmed)?.let { m ->
                val name = m.groupValues[1]
                addChild(fileNode, NodeId("class:${artifact.id.value}:$name"), NodeType.Class, name, ConfidenceDefaults.AST_SYMBOL, prov(i, line), nodes, edges)
            }
            funRegex.find(line)?.let { m ->
                val name = m.groupValues[1]
                if (name.isNotBlank()) {
                    addChild(fileNode, NodeId("fun:${artifact.id.value}:$name"), NodeType.Function, name, ConfidenceDefaults.AST_SYMBOL, prov(i, line), nodes, edges)
                }
            }
        }
    }

    private fun extractJava(
        artifact: Artifact, fileNode: GraphNode, lines: List<String>,
        nodes: MutableList<GraphNode>, edges: MutableList<GraphEdge>,
        prov: (Int, String) -> Provenance
    ) {
        val importRegex = Regex("""^import\s+([\w.]+);""")
        val classRegex = Regex("""(?:public\s+|private\s+|protected\s+|abstract\s+|final\s+)*(?:class|interface|enum)\s+(\w+)""")
        val methodRegex = Regex("""^\s+(?:public\s+|private\s+|protected\s+|static\s+|final\s+|synchronized\s+|native\s+)*\w[\w<>[\], ]*\s+(\w+)\s*\(""")

        lines.forEachIndexed { i, line ->
            val trimmed = line.trim()
            importRegex.find(trimmed)?.let { m ->
                val import = m.groupValues[1]
                val importId = NodeId("import:${artifact.id.value}:$import")
                nodes.add(GraphNode(id = importId, type = NodeType.Module, label = import, confidence = ConfidenceDefaults.IMPORT_RELATION, provenance = listOf(prov(i, line))))
                edges.add(GraphEdge(id = EdgeId("imports:${fileNode.id.value}:$import"), source = fileNode.id, target = importId, type = EdgeType.Imports, confidence = ConfidenceDefaults.IMPORT_RELATION))
            }
            classRegex.find(trimmed)?.let { m ->
                addChild(fileNode, NodeId("class:${artifact.id.value}:${m.groupValues[1]}"), NodeType.Class, m.groupValues[1], ConfidenceDefaults.AST_SYMBOL, prov(i, line), nodes, edges)
            }
            methodRegex.find(line)?.let { m ->
                val name = m.groupValues[1]
                if (name.isNotBlank() && name !in setOf("if", "for", "while", "switch", "catch")) {
                    addChild(fileNode, NodeId("method:${artifact.id.value}:$name:$i"), NodeType.Method, name, ConfidenceDefaults.AST_SYMBOL, prov(i, line), nodes, edges)
                }
            }
        }
    }

    private fun extractPython(
        artifact: Artifact, fileNode: GraphNode, lines: List<String>,
        nodes: MutableList<GraphNode>, edges: MutableList<GraphEdge>,
        prov: (Int, String) -> Provenance
    ) {
        val importRegex = Regex("""^(?:import\s+([\w.]+)|from\s+([\w.]+)\s+import)""")
        val classRegex = Regex("""^class\s+(\w+)""")
        val defRegex = Regex("""^def\s+(\w+)\s*\(""")

        lines.forEachIndexed { i, line ->
            val trimmed = line.trim()
            importRegex.find(trimmed)?.let { m ->
                val import = m.groupValues[1].ifBlank { m.groupValues[2] }
                if (import.isNotBlank()) {
                    val importId = NodeId("import:${artifact.id.value}:$import")
                    nodes.add(GraphNode(id = importId, type = NodeType.Module, label = import, confidence = ConfidenceDefaults.IMPORT_RELATION, provenance = listOf(prov(i, line))))
                    edges.add(GraphEdge(id = EdgeId("imports:${fileNode.id.value}:$import"), source = fileNode.id, target = importId, type = EdgeType.Imports, confidence = ConfidenceDefaults.IMPORT_RELATION))
                }
            }
            classRegex.find(trimmed)?.let { m ->
                addChild(fileNode, NodeId("class:${artifact.id.value}:${m.groupValues[1]}"), NodeType.Class, m.groupValues[1], ConfidenceDefaults.AST_SYMBOL, prov(i, line), nodes, edges)
            }
            defRegex.find(trimmed)?.let { m ->
                addChild(fileNode, NodeId("fun:${artifact.id.value}:${m.groupValues[1]}"), NodeType.Function, m.groupValues[1], ConfidenceDefaults.AST_SYMBOL, prov(i, line), nodes, edges)
            }
        }
    }

    private fun extractTypeScript(
        artifact: Artifact, fileNode: GraphNode, lines: List<String>,
        nodes: MutableList<GraphNode>, edges: MutableList<GraphEdge>,
        prov: (Int, String) -> Provenance
    ) {
        val importRegex = Regex("""^import\s+.*?from\s+['"]([^'"]+)['"]""")
        val classRegex = Regex("""(?:export\s+)?(?:abstract\s+)?class\s+(\w+)""")
        val funcRegex = Regex("""(?:export\s+)?(?:async\s+)?function\s+(\w+)\s*[(<]""")
        val arrowRegex = Regex("""(?:export\s+)?const\s+(\w+)\s*=\s*(?:async\s+)?\(""")

        lines.forEachIndexed { i, line ->
            val trimmed = line.trim()
            importRegex.find(trimmed)?.let { m ->
                val import = m.groupValues[1]
                val importId = NodeId("import:${artifact.id.value}:$import")
                nodes.add(GraphNode(id = importId, type = NodeType.Module, label = import, confidence = ConfidenceDefaults.IMPORT_RELATION, provenance = listOf(prov(i, line))))
                edges.add(GraphEdge(id = EdgeId("imports:${fileNode.id.value}:$import"), source = fileNode.id, target = importId, type = EdgeType.Imports, confidence = ConfidenceDefaults.IMPORT_RELATION))
            }
            classRegex.find(trimmed)?.let { m ->
                addChild(fileNode, NodeId("class:${artifact.id.value}:${m.groupValues[1]}"), NodeType.Class, m.groupValues[1], ConfidenceDefaults.AST_SYMBOL, prov(i, line), nodes, edges)
            }
            funcRegex.find(trimmed)?.let { m ->
                addChild(fileNode, NodeId("fun:${artifact.id.value}:${m.groupValues[1]}"), NodeType.Function, m.groupValues[1], ConfidenceDefaults.AST_SYMBOL, prov(i, line), nodes, edges)
            }
            arrowRegex.find(trimmed)?.let { m ->
                addChild(fileNode, NodeId("fun:${artifact.id.value}:${m.groupValues[1]}"), NodeType.Function, m.groupValues[1], ConfidenceDefaults.AST_SYMBOL, prov(i, line), nodes, edges)
            }
        }
    }

    private fun extractJavaScript(
        artifact: Artifact, fileNode: GraphNode, lines: List<String>,
        nodes: MutableList<GraphNode>, edges: MutableList<GraphEdge>,
        prov: (Int, String) -> Provenance
    ) = extractTypeScript(artifact, fileNode, lines, nodes, edges, prov)

    private fun extractGo(
        artifact: Artifact, fileNode: GraphNode, lines: List<String>,
        nodes: MutableList<GraphNode>, edges: MutableList<GraphEdge>,
        prov: (Int, String) -> Provenance
    ) {
        val importRegex = Regex("""^\s+"([\w./]+)"""")
        val funcRegex = Regex("""^func\s+(?:\(\w+\s+\*?\w+\)\s+)?(\w+)\s*\(""")
        val structRegex = Regex("""^type\s+(\w+)\s+struct""")

        lines.forEachIndexed { i, line ->
            importRegex.find(line)?.let { m ->
                val import = m.groupValues[1]
                val importId = NodeId("import:${artifact.id.value}:$import")
                nodes.add(GraphNode(id = importId, type = NodeType.Module, label = import, confidence = ConfidenceDefaults.IMPORT_RELATION, provenance = listOf(prov(i, line))))
                edges.add(GraphEdge(id = EdgeId("imports:${fileNode.id.value}:$import"), source = fileNode.id, target = importId, type = EdgeType.Imports, confidence = ConfidenceDefaults.IMPORT_RELATION))
            }
            funcRegex.find(line.trim())?.let { m ->
                addChild(fileNode, NodeId("fun:${artifact.id.value}:${m.groupValues[1]}"), NodeType.Function, m.groupValues[1], ConfidenceDefaults.AST_SYMBOL, prov(i, line), nodes, edges)
            }
            structRegex.find(line.trim())?.let { m ->
                addChild(fileNode, NodeId("class:${artifact.id.value}:${m.groupValues[1]}"), NodeType.Class, m.groupValues[1], ConfidenceDefaults.AST_SYMBOL, prov(i, line), nodes, edges)
            }
        }
    }

    private fun extractRust(
        artifact: Artifact, fileNode: GraphNode, lines: List<String>,
        nodes: MutableList<GraphNode>, edges: MutableList<GraphEdge>,
        prov: (Int, String) -> Provenance
    ) {
        val useRegex = Regex("""^use\s+([\w:]+)""")
        val fnRegex = Regex("""^(?:pub\s+)?(?:async\s+)?fn\s+(\w+)\s*[(<]""")
        val structRegex = Regex("""^(?:pub\s+)?struct\s+(\w+)""")
        val implRegex = Regex("""^impl\s+(?:\w+\s+for\s+)?(\w+)""")

        lines.forEachIndexed { i, line ->
            val trimmed = line.trim()
            useRegex.find(trimmed)?.let { m ->
                val import = m.groupValues[1]
                val importId = NodeId("import:${artifact.id.value}:$import")
                nodes.add(GraphNode(id = importId, type = NodeType.Module, label = import, confidence = ConfidenceDefaults.IMPORT_RELATION, provenance = listOf(prov(i, line))))
                edges.add(GraphEdge(id = EdgeId("imports:${fileNode.id.value}:$import"), source = fileNode.id, target = importId, type = EdgeType.Imports, confidence = ConfidenceDefaults.IMPORT_RELATION))
            }
            fnRegex.find(trimmed)?.let { m ->
                addChild(fileNode, NodeId("fun:${artifact.id.value}:${m.groupValues[1]}"), NodeType.Function, m.groupValues[1], ConfidenceDefaults.AST_SYMBOL, prov(i, line), nodes, edges)
            }
            structRegex.find(trimmed)?.let { m ->
                addChild(fileNode, NodeId("class:${artifact.id.value}:${m.groupValues[1]}"), NodeType.Class, m.groupValues[1], ConfidenceDefaults.AST_SYMBOL, prov(i, line), nodes, edges)
            }
        }
    }
}
