package io.contextgraph.ingest

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractorRegistry
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.GraphStats
import io.contextgraph.core.ModuleRootConfig
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.core.StorageAdapter
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Proves ModuleDetector is actually reachable from the production indexing path — not only from
 * its own unit test. IngestPipeline.index() must detect module boundaries and persist CodeModule
 * nodes through the same StorageAdapter every other node goes through, without introducing a
 * second DB-writing path (all writes still funnel through the single-consumer channel loop).
 */
class IngestPipelineModuleTest : FunSpec({

    test("index() persists detected CodeModule nodes through StorageAdapter") {
        val root = Files.createTempDirectory("ingest-pipeline-module-test")
        root.resolve("settings.gradle.kts").writeText("""include(":app", ":core")""")
        root.resolve("app").createDirectories()
        root.resolve("core").createDirectories()

        val storage = InMemoryStorageAdapter()
        val pipeline = IngestPipeline(
            discovery = FileDiscovery(ContextGraphConfig()),
            registry = ExtractorRegistry(emptyList()),
            checksumTracker = ChecksumTracker(),
            storage = storage,
            context = ExtractionContext(root, ContextGraphConfig())
        )

        val stats = runBlocking { pipeline.index(root) }

        val moduleNodes = storage.nodes.values.filter { it.type == NodeType.CodeModule }
        moduleNodes.map { it.label } shouldContainExactlyInAnyOrder listOf("app", "core")
        stats.nodeCount shouldBe moduleNodes.size
    }

    test("index() honours config-declared module roots when persisting CodeModule nodes") {
        val root = Files.createTempDirectory("ingest-pipeline-module-test")
        root.resolve("ios/MyApp").createDirectories()

        val config = ContextGraphConfig(moduleRoots = listOf(ModuleRootConfig(path = "ios/MyApp")))
        val storage = InMemoryStorageAdapter()
        val pipeline = IngestPipeline(
            discovery = FileDiscovery(config),
            registry = ExtractorRegistry(emptyList()),
            checksumTracker = ChecksumTracker(),
            storage = storage,
            context = ExtractionContext(root, config)
        )

        runBlocking { pipeline.index(root) }

        val moduleNodes = storage.nodes.values.filter { it.type == NodeType.CodeModule }
        moduleNodes.map { it.label } shouldContainExactlyInAnyOrder listOf("MyApp")
    }

    test("index() persists CodeModule nodes into a real SQLite graph.db, retrievable after reopening it") {
        val root = Files.createTempDirectory("ingest-pipeline-sqlite-module-test")
        root.resolve("settings.gradle.kts").writeText("""include(":app", ":app:feature-a", ":core")""")

        val dbDir = Files.createTempDirectory("ingest-pipeline-sqlite-module-db")
        val dbPath = dbDir.resolve("graph.db")

        val writeStorage = SqliteStorageAdapter(dbPath)
        try {
            val pipeline = IngestPipeline(
                discovery = FileDiscovery(ContextGraphConfig()),
                registry = ExtractorRegistry(emptyList()),
                checksumTracker = ChecksumTracker(),
                storage = writeStorage,
                context = ExtractionContext(root, ContextGraphConfig())
            )
            runBlocking { pipeline.index(root) }
        } finally {
            writeStorage.close()
        }

        // Reopen as a fresh connection, independent of the writer, to prove the rows are
        // genuinely durable on disk rather than only visible through the writer's own cache.
        val readStorage = SqliteStorageAdapter(dbPath)
        try {
            val moduleNodes = readStorage.getAllNodes().filter { it.type == NodeType.CodeModule }
            moduleNodes.map { it.label } shouldContainExactlyInAnyOrder listOf("app", "feature-a", "core")

            val featureA = moduleNodes.first { it.label == "feature-a" }
            val app = moduleNodes.first { it.label == "app" }
            (featureA.properties["parentId"] as kotlinx.serialization.json.JsonPrimitive).content shouldBe app.id.value
        } finally {
            readStorage.close()
        }
    }
})

private class InMemoryStorageAdapter : StorageAdapter {
    val nodes = mutableMapOf<String, GraphNode>()
    private val edges = mutableListOf<GraphEdge>()
    private val artifacts = mutableMapOf<String, Artifact>()
    private val provenance = mutableMapOf<String, MutableList<Provenance>>()

    override fun upsertArtifact(artifact: Artifact) {
        artifacts[artifact.id.value] = artifact
    }

    override fun getArtifact(id: ArtifactId): Artifact? = artifacts[id.value]

    override fun deleteNodesForArtifact(artifactId: ArtifactId) {
        // No artifact-scoped node bookkeeping needed for this fake.
    }

    override fun upsertNode(node: GraphNode) {
        nodes[node.id.value] = node
    }

    override fun upsertEdge(edge: GraphEdge) {
        edges.add(edge)
    }

    override fun upsertProvenance(entityId: String, entityKind: String, provenance: Provenance) {
        this.provenance.getOrPut(entityId) { mutableListOf() }.add(provenance)
    }

    override fun searchNodes(query: String, types: List<NodeType>, minConfidence: Double, limit: Int): List<GraphNode> =
        emptyList()

    override fun getNode(id: NodeId): GraphNode? = nodes[id.value]

    override fun getEdgesFrom(source: NodeId): List<GraphEdge> = edges.filter { it.source == source }

    override fun getEdgesTo(target: NodeId): List<GraphEdge> = edges.filter { it.target == target }

    override fun getProvenance(entityId: String): List<Provenance> = provenance[entityId] ?: emptyList()

    override fun getAllNodes(minConfidence: Double): List<GraphNode> = nodes.values.toList()

    override fun getAllEdges(minConfidence: Double): List<GraphEdge> = edges.toList()

    override fun getAllArtifacts(): List<Artifact> = artifacts.values.toList()

    override fun getStats(): GraphStats = GraphStats(artifacts.size, nodes.size, edges.size)

    override fun close() {}

    override fun findNodesByLabel(label: String): List<GraphNode> = nodes.values.filter { it.label == label }

    override fun insertUnresolvedReference(reference: io.contextgraph.core.UnresolvedReference) {
        // No unresolved-reference bookkeeping needed for this fake.
    }

    override fun deleteUnresolvedReferencesForArtifact(artifactId: ArtifactId) {
        // No unresolved-reference bookkeeping needed for this fake.
    }

    override fun getAllUnresolvedReferences(): List<io.contextgraph.core.UnresolvedReference> = emptyList()

    override fun deleteEdgesOfType(type: io.contextgraph.core.EdgeType) {
        edges.removeAll { it.type == type }
    }
}
