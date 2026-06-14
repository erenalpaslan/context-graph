package io.contextgraph.extractors

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

class ConfigExtractor : ResourceExtractor {
    override val id = "config"
    override val supportedTypes = setOf(NodeType.ConfigFile, NodeType.PackageFile)

    private val jackson = ObjectMapper()
    private val yaml = Yaml()

    override suspend fun extract(artifact: Artifact, context: ExtractionContext): ExtractionResult {
        val path = Path.of(artifact.path)
        val name = path.name.lowercase()
        return when {
            name == "package.json" -> extractPackageJson(artifact, path)
            name.endsWith(".gradle.kts") || name.endsWith(".gradle") -> extractGradle(artifact, path)
            name in setOf("docker-compose.yml", "docker-compose.yaml") -> extractDockerCompose(artifact, path)
            name == "dockerfile" -> extractDockerfile(artifact, path)
            name.endsWith(".yml") || name.endsWith(".yaml") -> extractGenericYaml(artifact, path)
            else -> ExtractionResult(artifact, emptyList(), emptyList())
        }
    }

    private fun extractPackageJson(artifact: Artifact, path: Path): ExtractionResult {
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val now = Clock.System.now()
        val prov = { span: String -> Provenance(artifact.id, artifact.path, textSpan = span, extractor = id, extractedAt = now) }

        try {
            val json: JsonNode = jackson.readTree(path.readText())
            val pkgName = json["name"]?.asText() ?: path.name

            val pkgNode = GraphNode(
                id = NodeId("package:${artifact.id.value}"),
                type = NodeType.PackageFile,
                label = pkgName,
                confidence = ConfidenceDefaults.CONFIG_DEPENDENCY,
                provenance = listOf(prov(pkgName))
            )
            nodes.add(pkgNode)

            listOf("dependencies", "devDependencies", "peerDependencies").forEach { depField ->
                json[depField]?.fields()?.forEach { (dep, version) ->
                    val depId = NodeId("dep:$dep")
                    if (nodes.none { it.id == depId }) {
                        nodes.add(GraphNode(id = depId, type = NodeType.Module, label = dep, confidence = ConfidenceDefaults.CONFIG_DEPENDENCY, provenance = listOf(prov(dep))))
                    }
                    edges.add(GraphEdge(id = EdgeId("depends:${pkgNode.id.value}:$dep"), source = pkgNode.id, target = depId, type = EdgeType.DependsOn, confidence = ConfidenceDefaults.CONFIG_DEPENDENCY))
                }
            }

            json["scripts"]?.fields()?.forEach { (script, _) ->
                val scriptId = NodeId("script:${artifact.id.value}:$script")
                nodes.add(GraphNode(id = scriptId, type = NodeType.Function, label = script, confidence = ConfidenceDefaults.CONFIG_DEPENDENCY, provenance = listOf(prov(script))))
                edges.add(GraphEdge(id = EdgeId("contains:${pkgNode.id.value}:$script"), source = pkgNode.id, target = scriptId, type = EdgeType.Contains, confidence = ConfidenceDefaults.CONFIG_DEPENDENCY))
            }
        } catch (_: Exception) {}

        return ExtractionResult(artifact, nodes, edges)
    }

    private fun extractGradle(artifact: Artifact, path: Path): ExtractionResult {
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val now = Clock.System.now()
        val content = path.readText()

        val fileNode = GraphNode(
            id = NodeId("file:${artifact.id.value}"),
            type = NodeType.PackageFile,
            label = path.name,
            confidence = 1.0,
            provenance = listOf(Provenance(artifact.id, artifact.path, extractor = id, extractedAt = now))
        )
        nodes.add(fileNode)

        // Extract dependencies from implementation/api/testImplementation calls
        val depRegex = Regex("""(?:implementation|api|testImplementation|runtimeOnly|compileOnly)\s*\(?["']([^"']+)["']""")
        depRegex.findAll(content).forEach { m ->
            val dep = m.groupValues[1]
            val depId = NodeId("dep:${dep.replace(":", "_")}")
            if (nodes.none { it.id == depId }) {
                nodes.add(GraphNode(id = depId, type = NodeType.Module, label = dep, confidence = ConfidenceDefaults.CONFIG_DEPENDENCY,
                    provenance = listOf(Provenance(artifact.id, artifact.path, textSpan = dep, extractor = id, extractedAt = now))))
            }
            edges.add(GraphEdge(id = EdgeId("depends:${fileNode.id.value}:${depId.value}"), source = fileNode.id, target = depId, type = EdgeType.DependsOn, confidence = ConfidenceDefaults.CONFIG_DEPENDENCY))
        }

        // Extract project dependencies
        val projectDepRegex = Regex("""project\(["']:([^"']+)["']\)""")
        projectDepRegex.findAll(content).forEach { m ->
            val dep = m.groupValues[1]
            val depId = NodeId("module:$dep")
            if (nodes.none { it.id == depId }) {
                nodes.add(GraphNode(id = depId, type = NodeType.Module, label = dep, confidence = ConfidenceDefaults.CONFIG_DEPENDENCY,
                    provenance = listOf(Provenance(artifact.id, artifact.path, textSpan = dep, extractor = id, extractedAt = now))))
            }
            edges.add(GraphEdge(id = EdgeId("depends:${fileNode.id.value}:${depId.value}"), source = fileNode.id, target = depId, type = EdgeType.DependsOn, confidence = ConfidenceDefaults.CONFIG_DEPENDENCY))
        }

        return ExtractionResult(artifact, nodes, edges)
    }

    private fun extractDockerCompose(artifact: Artifact, path: Path): ExtractionResult {
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val now = Clock.System.now()

        try {
            @Suppress("UNCHECKED_CAST")
            val compose = yaml.load<Map<String, Any>>(path.readText())
            val services = (compose["services"] as? Map<*, *>) ?: return ExtractionResult(artifact, emptyList(), emptyList())

            val fileNode = GraphNode(
                id = NodeId("file:${artifact.id.value}"),
                type = NodeType.ConfigFile,
                label = path.name,
                confidence = 1.0,
                provenance = listOf(Provenance(artifact.id, artifact.path, extractor = id, extractedAt = now))
            )
            nodes.add(fileNode)

            services.keys.forEach { serviceName ->
                val svcId = NodeId("service:$serviceName")
                nodes.add(GraphNode(id = svcId, type = NodeType.Component, label = serviceName.toString(), confidence = ConfidenceDefaults.CONFIG_DEPENDENCY,
                    provenance = listOf(Provenance(artifact.id, artifact.path, textSpan = serviceName.toString(), extractor = id, extractedAt = now))))
                edges.add(GraphEdge(id = EdgeId("contains:${fileNode.id.value}:${svcId.value}"), source = fileNode.id, target = svcId, type = EdgeType.Contains, confidence = ConfidenceDefaults.CONFIG_DEPENDENCY))
            }
        } catch (_: Exception) {}

        return ExtractionResult(artifact, nodes, edges)
    }

    private fun extractDockerfile(artifact: Artifact, path: Path): ExtractionResult {
        val nodes = mutableListOf<GraphNode>()
        val now = Clock.System.now()
        val content = path.readText()

        val fromRegex = Regex("""^FROM\s+(\S+)""", RegexOption.MULTILINE)
        fromRegex.findAll(content).forEach { m ->
            val image = m.groupValues[1]
            nodes.add(GraphNode(
                id = NodeId("image:$image"),
                type = NodeType.Component,
                label = image,
                confidence = ConfidenceDefaults.CONFIG_DEPENDENCY,
                provenance = listOf(Provenance(artifact.id, artifact.path, textSpan = image, extractor = id, extractedAt = now))
            ))
        }

        return ExtractionResult(artifact, nodes, nodes.map { node ->
            GraphEdge(id = EdgeId("uses:file:${artifact.id.value}:${node.id.value}"),
                source = NodeId("file:${artifact.id.value}"), target = node.id, type = EdgeType.Uses, confidence = ConfidenceDefaults.CONFIG_DEPENDENCY)
        })
    }

    private fun extractGenericYaml(artifact: Artifact, path: Path): ExtractionResult {
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val now = Clock.System.now()

        val fileNode = GraphNode(
            id = NodeId("file:${artifact.id.value}"),
            type = NodeType.ConfigFile,
            label = path.name,
            confidence = 1.0,
            provenance = listOf(Provenance(artifact.id, artifact.path, extractor = id, extractedAt = now))
        )
        nodes.add(fileNode)

        return ExtractionResult(artifact, nodes, edges)
    }
}
