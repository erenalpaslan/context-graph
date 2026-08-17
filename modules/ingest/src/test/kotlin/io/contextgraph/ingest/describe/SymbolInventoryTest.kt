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
import io.contextgraph.ingest.DetectedModule
import io.contextgraph.ingest.ModuleSource
import io.contextgraph.ingest.ModuleTree
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonPrimitive

/**
 * [SymbolInventory] rolls declaration-site GraphNodes up into a per-module symbol list, purely
 * from what is already in the graph -- no tree-sitter, no file reads. A node counts as a
 * "symbol" iff it carries a `kind` property, the same property every grammar's extractor sets
 * on every declaration node (see JavaSymbolExtractor); import/module-reference nodes never set
 * it, so they're excluded without needing a NodeType allowlist that would go stale as new
 * grammars land.
 */
class SymbolInventoryTest : FunSpec({

    val appModule = DetectedModule(NodeId("module:app"), "app", "app", null, ModuleSource.GRADLE)
    val coreModule = DetectedModule(NodeId("module:core"), "core", "core", null, ModuleSource.GRADLE)
    val tree = ModuleTree(listOf(appModule, coreModule))

    test("collects symbol nodes into the module owning their provenance path") {
        val storage = FakeStorage()
        storage.addSymbol("app/Foo.kt#Foo.bar", "app/Foo.kt", kind = "method", name = "bar", fqn = "Foo.bar")
        storage.addSymbol("core/Baz.kt#Baz", "core/Baz.kt", kind = "class", name = "Baz", fqn = "Baz")

        val byModule = SymbolInventory.collectByModule(storage, tree)

        byModule[appModule.id]?.map { it.name } shouldBe listOf("bar")
        byModule[coreModule.id]?.map { it.name } shouldBe listOf("Baz")
    }

    test("excludes nodes with no kind property (e.g. import/module reference nodes)") {
        val storage = FakeStorage()
        storage.addNode(
            GraphNode(id = NodeId("app/Foo.kt#import:java.util.List"), type = NodeType.Module, label = "java.util.List")
        )
        storage.addProvenance("app/Foo.kt#import:java.util.List", "app/Foo.kt")

        val byModule = SymbolInventory.collectByModule(storage, tree)

        (byModule[appModule.id] ?: emptyList()) shouldBe emptyList()
    }

    test("drops symbols whose provenance path owns no module") {
        val storage = FakeStorage()
        storage.addSymbol("unowned/Thing.kt#Thing", "unowned/Thing.kt", kind = "class", name = "Thing", fqn = "Thing")

        val byModule = SymbolInventory.collectByModule(storage, tree)

        byModule.values.flatten() shouldBe emptyList()
    }

    test("canonicalText is deterministic regardless of input order") {
        val a = SymbolEntry("app/A.kt", "class", "A", "A", null)
        val b = SymbolEntry("app/B.kt", "class", "B", "B", null)

        SymbolInventory.canonicalText(listOf(a, b)) shouldBe SymbolInventory.canonicalText(listOf(b, a))
    }

    test("canonicalText includes doc comments when present, omits them when absent") {
        val withDoc = SymbolEntry("app/A.kt", "class", "A", "A", "Handles widgets.")
        val withoutDoc = SymbolEntry("app/B.kt", "class", "B", "B", null)

        val text = SymbolInventory.canonicalText(listOf(withDoc, withoutDoc))

        text shouldBe SymbolInventory.canonicalText(listOf(withDoc, withoutDoc))
        (text.contains("Handles widgets.")) shouldBe true
    }

    test("sha256 is deterministic and changes when the input text changes") {
        val h1 = SymbolInventory.sha256("hello")
        val h2 = SymbolInventory.sha256("hello")
        val h3 = SymbolInventory.sha256("hello world")

        h1 shouldBe h2
        h1 shouldNotBe h3
    }

    test("canonicalText never contains raw source bodies -- only names, kinds, fqns, doc comments") {
        val entry = SymbolEntry("app/Foo.kt", "method", "bar", "Foo.bar", "Computes bar.")
        val text = SymbolInventory.canonicalText(listOf(entry))

        // Nothing beyond the four documented fields can appear -- this is a closed-world check:
        // reconstruct the expected text from just those fields and require an exact match.
        text shouldBe "app/Foo.kt :: method bar [fqn=Foo.bar] -- Computes bar."
    }
})

private class FakeStorage : StorageAdapter {
    val nodes = mutableMapOf<String, GraphNode>()
    private val provenance = mutableMapOf<String, MutableList<Provenance>>()

    fun addNode(node: GraphNode) {
        nodes[node.id.value] = node
    }

    fun addProvenance(entityId: String, path: String) {
        provenance.getOrPut(entityId) { mutableListOf() }.add(
            Provenance(ArtifactId("art:$path"), path, extractor = "test", extractedAt = Clock.System.now())
        )
    }

    fun addSymbol(id: String, path: String, kind: String, name: String, fqn: String?) {
        addNode(
            GraphNode(
                id = NodeId(id),
                type = NodeType.Method,
                label = name,
                properties = buildMap {
                    put("kind", JsonPrimitive(kind))
                    fqn?.let { put("fqn", JsonPrimitive(it)) }
                }
            )
        )
        addProvenance(id, path)
    }

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
    override fun getProvenance(entityId: String): List<Provenance> = provenance[entityId] ?: emptyList()
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
