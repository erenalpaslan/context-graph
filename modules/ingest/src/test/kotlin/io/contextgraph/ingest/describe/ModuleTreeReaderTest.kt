package io.contextgraph.ingest.describe

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.GraphStats
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.core.StorageAdapter
import io.contextgraph.ingest.ModuleDetector
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * [ModuleTreeReader] must reconstruct exactly the tree [io.contextgraph.ingest.ModuleDetector]
 * detected -- describe-modules runs long after indexing, against whatever CodeModule nodes are
 * sitting in the graph, never against a live ModuleDetector.detect() call. Round-tripping real
 * ModuleDetector output through toGraphNodes() and back is the only way to prove that.
 */
class ModuleTreeReaderTest : FunSpec({

    test("round-trips a real ModuleDetector tree through persisted nodes") {
        val root = Files.createTempDirectory("module-tree-reader-test")
        root.resolve("settings.gradle.kts").writeText("""include(":app", ":app:feature-a", ":core")""")

        val originalTree = ModuleDetector().detect(root, io.contextgraph.core.ContextGraphConfig())
        val storage = FakeNodeStorage()
        originalTree.toGraphNodes().forEach { storage.upsertNode(it) }

        val reconstructed = ModuleTreeReader.read(storage)

        reconstructed.modules.map { it.path } shouldContainExactlyInAnyOrder
            originalTree.modules.map { it.path }

        val originalFeatureA = originalTree.modules.first { it.path == "app/feature-a" }
        val rebuiltFeatureA = reconstructed.modules.first { it.path == "app/feature-a" }
        rebuiltFeatureA.parentId shouldBe originalFeatureA.parentId
        rebuiltFeatureA.buildSystem shouldBe originalFeatureA.buildSystem

        val originalApp = originalTree.modules.first { it.path == "app" }
        val rebuiltApp = reconstructed.modules.first { it.path == "app" }
        reconstructed.children(rebuiltApp).map { it.path } shouldBe
            originalTree.children(originalApp).map { it.path }
    }

    test("ignores non-CodeModule nodes") {
        val storage = FakeNodeStorage()
        storage.upsertNode(GraphNode(id = NodeId("app/Foo.kt#Foo"), type = NodeType.Class, label = "Foo"))

        ModuleTreeReader.read(storage).modules shouldBe emptyList()
    }
})

private class FakeNodeStorage : StorageAdapter {
    private val nodes = mutableMapOf<String, GraphNode>()
    override fun upsertArtifact(artifact: Artifact) = Unit
    override fun getArtifact(id: ArtifactId): Artifact? = null
    override fun deleteNodesForArtifact(artifactId: ArtifactId) = Unit
    override fun upsertNode(node: GraphNode) { nodes[node.id.value] = node }
    override fun upsertEdge(edge: GraphEdge) = Unit
    override fun upsertProvenance(entityId: String, entityKind: String, provenance: Provenance) = Unit
    override fun searchNodes(query: String, types: List<NodeType>, minConfidence: Double, limit: Int): List<GraphNode> = emptyList()
    override fun getNode(id: NodeId): GraphNode? = nodes[id.value]
    override fun getEdgesFrom(source: NodeId): List<GraphEdge> = emptyList()
    override fun getEdgesTo(target: NodeId): List<GraphEdge> = emptyList()
    override fun getProvenance(entityId: String): List<Provenance> = emptyList()
    override fun getAllNodes(minConfidence: Double): List<GraphNode> = nodes.values.toList()
    override fun getAllEdges(minConfidence: Double): List<GraphEdge> = emptyList()
    override fun getAllArtifacts(): List<Artifact> = emptyList()
    override fun getStats(): GraphStats = GraphStats(0, nodes.size, 0)
    override fun close() = Unit
    override fun findNodesByLabel(label: String): List<GraphNode> = nodes.values.filter { it.label == label }
    override fun insertUnresolvedReference(reference: io.contextgraph.core.UnresolvedReference) = Unit
    override fun deleteUnresolvedReferencesForArtifact(artifactId: ArtifactId) = Unit
    override fun getAllUnresolvedReferences(): List<io.contextgraph.core.UnresolvedReference> = emptyList()
    override fun deleteEdgesOfType(type: io.contextgraph.core.EdgeType) = Unit
}
