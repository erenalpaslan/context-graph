package io.contextgraph.ingest.describe

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ConfidenceDefaults
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * AC-21/22/38: ranking modules by cosine similarity to a query, with the two required
 * degradations -- no vector for a module (never described, or described but not yet embedded)
 * excluded from ranking; no query embedding available (LiteLLM disabled/unreachable) falls back
 * to keyword search rather than failing. The corpus-never-re-embedded-per-query property is
 * checked directly: the embedder is called exactly once per [ModuleSemanticSearch.search] call.
 */
class ModuleSemanticSearchTest : FunSpec({

    fun withVector(id: String, description: String, vector: FloatArray) = GraphNode(
        id = NodeId(id),
        type = NodeType.CodeModule,
        label = id,
        properties = mapOf(
            "description" to JsonPrimitive(description),
            ModuleEmbeddingProperties.VECTOR to JsonPrimitive(ModuleVectorCodec.encode(vector)),
            ModuleEmbeddingProperties.MODEL to JsonPrimitive(ModuleEmbeddingModel.NAME),
            ModuleEmbeddingProperties.DIMENSION to JsonPrimitive(ModuleEmbeddingModel.DIMENSION),
            ModuleEmbeddingProperties.SOURCE_HASH to JsonPrimitive(SymbolInventory.sha256(description))
        )
    )

    fun axisVector(index: Int, dim: Int = ModuleEmbeddingModel.DIMENSION): FloatArray =
        FloatArray(dim).also { it[index] = 1f }

    test("ranks modules by cosine similarity to the query, closest first") {
        val storage = SearchFakeStorage()
        // "billing" module's vector points the same direction as the query; "auth" is orthogonal.
        storage.upsertNode(withVector("m:billing", "Handles refunds and charge reversals.", axisVector(0)))
        storage.upsertNode(withVector("m:auth", "Handles login sessions.", axisVector(1)))
        val embedder = ScriptedSearchEmbedder(axisVector(0))

        val results = runBlocking {
            ModuleSemanticSearch(storage, embedder, LiteLlmConfig(enabled = true)).search("where do we handle refunds")
        }

        results.first().node.id shouldBe NodeId("m:billing")
        results.first().method shouldBe ModuleSearchMethod.SEMANTIC
        embedder.calls shouldBe 1 // the query only -- the corpus's own vectors were never (re-)embedded
    }

    test("a module below the relevance floor is excluded from semantic ranking") {
        val storage = SearchFakeStorage()
        storage.upsertNode(withVector("m:unrelated", "Totally unrelated.", axisVector(1)))
        // Query orthogonal to the only module's vector: cosine similarity is 0, below EMBEDDING_SIMILARITY_MIN.
        val embedder = ScriptedSearchEmbedder(axisVector(0))

        val results = runBlocking {
            ModuleSemanticSearch(storage, embedder, LiteLlmConfig(enabled = true)).search("query")
        }

        // Falls back to keyword search once nothing clears the floor -- see the fallback test below
        // for the isolated case. Here we just confirm the orthogonal module never surfaces as a
        // semantic hit.
        results.none { it.method == ModuleSearchMethod.SEMANTIC && it.node.id == NodeId("m:unrelated") } shouldBe true
    }

    test("a module with no vector is excluded from ranking without throwing") {
        val storage = SearchFakeStorage()
        storage.upsertNode(GraphNode(id = NodeId("m:undescribed"), type = NodeType.CodeModule, label = "m:undescribed"))
        storage.upsertNode(withVector("m:billing", "Handles refunds.", axisVector(0)))
        val embedder = ScriptedSearchEmbedder(axisVector(0))

        val results = runBlocking {
            ModuleSemanticSearch(storage, embedder, LiteLlmConfig(enabled = true)).search("refunds")
        }

        results.none { it.node.id == NodeId("m:undescribed") } shouldBe true
    }

    test("no embedding endpoint available degrades to keyword search instead of failing") {
        val storage = SearchFakeStorage()
        storage.upsertNode(withVector("m:billing", "Handles refunds.", axisVector(0)))
        storage.keywordResults = listOf(storage.getNode(NodeId("m:billing"))!!)
        val embedder = ScriptedSearchEmbedder(null) // LiteLLM disabled/unreachable

        val results = runBlocking {
            ModuleSemanticSearch(storage, embedder, LiteLlmConfig(enabled = false)).search("refunds")
        }

        results.size shouldBe 1
        results.first().method shouldBe ModuleSearchMethod.KEYWORD
    }

    test("no modules have any vector at all degrades to keyword search instead of returning nothing") {
        val storage = SearchFakeStorage()
        val node = GraphNode(id = NodeId("m:app"), type = NodeType.CodeModule, label = "m:app")
        storage.upsertNode(node)
        storage.keywordResults = listOf(node)
        val embedder = ScriptedSearchEmbedder(axisVector(0))

        val results = runBlocking {
            ModuleSemanticSearch(storage, embedder, LiteLlmConfig(enabled = true)).search("anything")
        }

        results.map { it.method } shouldBe listOf(ModuleSearchMethod.KEYWORD)
    }
})

private class ScriptedSearchEmbedder(private val vector: FloatArray?) : ModuleEmbedder {
    var calls = 0
    override suspend fun embed(text: String, config: LiteLlmConfig): FloatArray? {
        calls++
        return vector
    }
}

private class SearchFakeStorage : StorageAdapter {
    private val nodes = mutableMapOf<String, GraphNode>()
    var keywordResults: List<GraphNode> = emptyList()

    override fun upsertArtifact(artifact: Artifact) = Unit
    override fun getArtifact(id: ArtifactId): Artifact? = null
    override fun deleteNodesForArtifact(artifactId: ArtifactId) = Unit
    override fun upsertNode(node: GraphNode) { nodes[node.id.value] = node }
    override fun upsertEdge(edge: GraphEdge) = Unit
    override fun upsertProvenance(entityId: String, entityKind: String, provenance: Provenance) = Unit
    override fun searchNodes(query: String, types: List<NodeType>, minConfidence: Double, limit: Int): List<GraphNode> =
        keywordResults
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
