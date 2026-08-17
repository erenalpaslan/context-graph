package io.contextgraph.ingest.describe

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.GraphStats
import io.contextgraph.core.LiteLlmConfig
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.core.StorageAdapter
import io.contextgraph.core.UnresolvedReference
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * Orchestration for AC-21/AC-38: which modules get embedded, which are skipped, and -- the
 * mismatch case AC-38 is about -- which get re-embedded because the stored model/dimension no
 * longer matches [ModuleEmbeddingModel]. Uses a scripted [ModuleEmbedder] fake, mirroring
 * [ModuleDescriptionServiceTest]'s use of a scripted [ModuleDescriber]; the wire format is already
 * covered by [LiteLlmModuleEmbedderTest].
 */
class ModuleEmbeddingServiceTest : FunSpec({

    fun moduleNode(id: String, properties: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) =
        GraphNode(id = NodeId(id), type = NodeType.CodeModule, label = id, properties = properties)

    test("a described module with no stored vector is embedded and persisted with model+dimension+hash") {
        val storage = EmbeddingFakeStorage()
        storage.upsertNode(moduleNode("m:app", mapOf("description" to JsonPrimitive("Handles widgets."))))
        val embedder = ScriptedEmbedder(FloatArray(ModuleEmbeddingModel.DIMENSION) { 0.5f })

        val report = runBlocking {
            ModuleEmbeddingService(storage, embedder, LiteLlmConfig(enabled = true)).run()
        }

        report.eligible shouldBe 1
        report.embedded shouldBe 1
        embedder.calls shouldBe 1
        val node = storage.getNode(NodeId("m:app"))!!
        (node.properties[ModuleEmbeddingProperties.MODEL] as JsonPrimitive).content shouldBe ModuleEmbeddingModel.NAME
        (node.properties[ModuleEmbeddingProperties.DIMENSION] as JsonPrimitive).content shouldBe
            ModuleEmbeddingModel.DIMENSION.toString()
        val decoded = ModuleVectorCodec.decode((node.properties[ModuleEmbeddingProperties.VECTOR] as JsonPrimitive).content)
        decoded.size shouldBe ModuleEmbeddingModel.DIMENSION
    }

    test("a module with no description is never embedded and is not counted eligible") {
        val storage = EmbeddingFakeStorage()
        storage.upsertNode(moduleNode("m:app"))
        val embedder = ScriptedEmbedder(FloatArray(ModuleEmbeddingModel.DIMENSION))

        val report = runBlocking {
            ModuleEmbeddingService(storage, embedder, LiteLlmConfig(enabled = true)).run()
        }

        report.eligible shouldBe 0
        report.embedded shouldBe 0
        embedder.calls shouldBe 0
    }

    test("a module already embedded with the current model, dimension, and description hash is not re-embedded") {
        val storage = EmbeddingFakeStorage()
        storage.upsertNode(moduleNode("m:app", mapOf("description" to JsonPrimitive("Handles widgets."))))
        val embedder = ScriptedEmbedder(FloatArray(ModuleEmbeddingModel.DIMENSION) { 0.1f })
        val service = ModuleEmbeddingService(storage, embedder, LiteLlmConfig(enabled = true))
        runBlocking { service.run() }

        val secondReport = runBlocking { service.run() }

        secondReport.embedded shouldBe 0
        embedder.calls shouldBe 1
    }

    test("a stored vector from a different model or dimension is re-embedded, never compared as-is") {
        val storage = EmbeddingFakeStorage()
        storage.upsertNode(
            moduleNode(
                "m:app",
                mapOf(
                    "description" to JsonPrimitive("Handles widgets."),
                    ModuleEmbeddingProperties.VECTOR to JsonPrimitive(ModuleVectorCodec.encode(FloatArray(3) { 1f })),
                    ModuleEmbeddingProperties.MODEL to JsonPrimitive("text-embedding-ada-002"),
                    ModuleEmbeddingProperties.DIMENSION to JsonPrimitive(3),
                    ModuleEmbeddingProperties.SOURCE_HASH to JsonPrimitive(SymbolInventory.sha256("Handles widgets."))
                )
            )
        )
        val embedder = ScriptedEmbedder(FloatArray(ModuleEmbeddingModel.DIMENSION) { 0.2f })

        val report = runBlocking {
            ModuleEmbeddingService(storage, embedder, LiteLlmConfig(enabled = true)).run()
        }

        report.embedded shouldBe 1
        embedder.calls shouldBe 1
        val node = storage.getNode(NodeId("m:app"))!!
        (node.properties[ModuleEmbeddingProperties.MODEL] as JsonPrimitive).content shouldBe ModuleEmbeddingModel.NAME
    }

    test("a changed description is re-embedded even when model and dimension already match") {
        val storage = EmbeddingFakeStorage()
        storage.upsertNode(moduleNode("m:app", mapOf("description" to JsonPrimitive("First description."))))
        val embedder = ScriptedEmbedder(FloatArray(ModuleEmbeddingModel.DIMENSION) { 0.3f })
        val service = ModuleEmbeddingService(storage, embedder, LiteLlmConfig(enabled = true))
        runBlocking { service.run() }

        storage.upsertNode(
            storage.getNode(NodeId("m:app"))!!.copy(
                properties = storage.getNode(NodeId("m:app"))!!.properties + mapOf(
                    "description" to JsonPrimitive("Regenerated description.")
                )
            )
        )
        val report = runBlocking { service.run() }

        report.embedded shouldBe 1
        embedder.calls shouldBe 2
    }

    test("disabled LiteLLM makes no embedder calls and reports the module skipped, not an error") {
        val storage = EmbeddingFakeStorage()
        storage.upsertNode(moduleNode("m:app", mapOf("description" to JsonPrimitive("Handles widgets."))))
        val embedder = LiteLlmModuleEmbedder(io.ktor.client.engine.mock.MockEngine { error("must not be called") })

        val report = runBlocking {
            ModuleEmbeddingService(storage, embedder, LiteLlmConfig(enabled = false)).run()
        }

        report.eligible shouldBe 1
        report.embedded shouldBe 0
        report.skipped shouldBe 1
        val node = storage.getNode(NodeId("m:app"))!!
        node.properties.containsKey(ModuleEmbeddingProperties.VECTOR) shouldBe false
    }

    test("an embedder that returns null (endpoint unreachable) leaves the module skipped, not broken") {
        val storage = EmbeddingFakeStorage()
        storage.upsertNode(moduleNode("m:app", mapOf("description" to JsonPrimitive("Handles widgets."))))
        val embedder = ScriptedEmbedder(null)

        val report = runBlocking {
            ModuleEmbeddingService(storage, embedder, LiteLlmConfig(enabled = true)).run()
        }

        report.skipped shouldBe 1
        report.embedded shouldBe 0
    }
})

private class ScriptedEmbedder(private val vector: FloatArray?) : ModuleEmbedder {
    var calls = 0
    override suspend fun embed(text: String, config: LiteLlmConfig): FloatArray? {
        calls++
        return vector
    }
}

private class EmbeddingFakeStorage : StorageAdapter {
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
    override fun insertUnresolvedReference(reference: UnresolvedReference) = Unit
    override fun deleteUnresolvedReferencesForArtifact(artifactId: ArtifactId) = Unit
    override fun getAllUnresolvedReferences(): List<UnresolvedReference> = emptyList()
    override fun deleteEdgesOfType(type: EdgeType) = Unit
}
