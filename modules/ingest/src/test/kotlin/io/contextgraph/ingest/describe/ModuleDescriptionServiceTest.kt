package io.contextgraph.ingest.describe

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.GraphStats
import io.contextgraph.core.LiteLlmConfig
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.core.StorageAdapter
import io.contextgraph.ingest.ModuleDetector
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * Orchestration: which modules get an LLM call, which get skipped, which get flagged stale --
 * and never regenerated without being told to. Uses a scripted [ModuleDescriber] fake instead of
 * a real HTTP client; [LiteLlmModuleDescriberTest] already covers the wire format and the
 * no-raw-source property.
 */
class ModuleDescriptionServiceTest : FunSpec({

    fun repoWith(vararg modules: String): java.nio.file.Path {
        val root = Files.createTempDirectory("module-description-service-test")
        root.resolve("settings.gradle.kts").writeText(
            "include(" + modules.joinToString(", ") { "\":$it\"" } + ")"
        )
        return root
    }

    test("generates a description for an undescribed module and stores hash + description") {
        val root = repoWith("app")
        val storage = ServiceFakeStorage()
        ModuleDetector().detect(root, ContextGraphConfig()).toGraphNodes().forEach { storage.upsertNode(it) }
        storage.addSymbol("app/Foo.kt#Foo", "app/Foo.kt", "class", "Foo", "Foo")
        val describer = ScriptedDescriber("A description.")

        val report = runBlocking { ModuleDescriptionService(storage, describer, LiteLlmConfig(enabled = true)).run(false) }

        report.generated shouldBe 1
        report.leftUndescribed shouldBe 0
        val appNode = storage.getAllNodes().first { it.type == NodeType.CodeModule }
        (appNode.properties["description"] as JsonPrimitive).content shouldBe "A description."
        (appNode.properties["descriptionInventoryHash"] as JsonPrimitive).content.isBlank() shouldBe false
        describer.calls shouldBe 1
    }

    test("a module with no owned symbols and no children is left undescribed without an LLM call") {
        val root = repoWith("app")
        val storage = ServiceFakeStorage()
        ModuleDetector().detect(root, ContextGraphConfig()).toGraphNodes().forEach { storage.upsertNode(it) }
        val describer = ScriptedDescriber(null)

        val report = runBlocking { ModuleDescriptionService(storage, describer, LiteLlmConfig(enabled = true)).run(false) }

        report.leftUndescribed shouldBe 1
        report.generated shouldBe 0
        describer.calls shouldBe 0
        val appNode = storage.getAllNodes().first { it.type == NodeType.CodeModule }
        appNode.properties.containsKey("description") shouldBe false
        (appNode.properties["undescribed"] as JsonPrimitive).content shouldBe "true"
    }

    test("disabled LiteLLM leaves modules undescribed and exits via a normal report, no exception") {
        val root = repoWith("app")
        val storage = ServiceFakeStorage()
        ModuleDetector().detect(root, ContextGraphConfig()).toGraphNodes().forEach { storage.upsertNode(it) }
        storage.addSymbol("app/Foo.kt#Foo", "app/Foo.kt", "class", "Foo", "Foo")
        val describer = LiteLlmModuleDescriber(io.ktor.client.engine.mock.MockEngine { error("must not be called") })

        val report = runBlocking { ModuleDescriptionService(storage, describer, LiteLlmConfig(enabled = false)).run(false) }

        report.leftUndescribed shouldBe 1
        report.generated shouldBe 0
        val appNode = storage.getAllNodes().first { it.type == NodeType.CodeModule }
        (appNode.properties["undescribed"] as JsonPrimitive).content shouldBe "true"
    }

    test("an unchanged inventory on a second run makes no further LLM calls and stays non-stale") {
        val root = repoWith("app")
        val storage = ServiceFakeStorage()
        ModuleDetector().detect(root, ContextGraphConfig()).toGraphNodes().forEach { storage.upsertNode(it) }
        storage.addSymbol("app/Foo.kt#Foo", "app/Foo.kt", "class", "Foo", "Foo")
        val describer = ScriptedDescriber("A description.")
        val service = ModuleDescriptionService(storage, describer, LiteLlmConfig(enabled = true))

        runBlocking { service.run(false) }
        val secondReport = runBlocking { service.run(false) }

        describer.calls shouldBe 1
        secondReport.generated shouldBe 0
        secondReport.staleFlagged shouldBe 0
        val appNode = storage.getAllNodes().first { it.type == NodeType.CodeModule }
        (appNode.properties["descriptionStale"] as JsonPrimitive).content shouldBe "false"
    }

    test("a changed inventory is flagged stale, not silently regenerated") {
        val root = repoWith("app")
        val storage = ServiceFakeStorage()
        ModuleDetector().detect(root, ContextGraphConfig()).toGraphNodes().forEach { storage.upsertNode(it) }
        storage.addSymbol("app/Foo.kt#Foo", "app/Foo.kt", "class", "Foo", "Foo")
        val describer = ScriptedDescriber("A description.")
        val service = ModuleDescriptionService(storage, describer, LiteLlmConfig(enabled = true))
        runBlocking { service.run(false) }

        // The module's symbol inventory changes: a new symbol appears.
        storage.addSymbol("app/Bar.kt#Bar", "app/Bar.kt", "class", "Bar", "Bar")
        val report = runBlocking { service.run(false) }

        report.staleFlagged shouldBe 1
        report.generated shouldBe 0
        report.regenerated shouldBe 0
        describer.calls shouldBe 1 // still just the original call -- no regeneration happened
        val appNode = storage.getAllNodes().first { it.type == NodeType.CodeModule }
        (appNode.properties["descriptionStale"] as JsonPrimitive).content shouldBe "true"
        (appNode.properties["description"] as JsonPrimitive).content shouldBe "A description." // untouched
    }

    test("--regenerate-stale explicitly regenerates a stale description") {
        val root = repoWith("app")
        val storage = ServiceFakeStorage()
        ModuleDetector().detect(root, ContextGraphConfig()).toGraphNodes().forEach { storage.upsertNode(it) }
        storage.addSymbol("app/Foo.kt#Foo", "app/Foo.kt", "class", "Foo", "Foo")
        val describer = ScriptedDescriber("First description.")
        val service = ModuleDescriptionService(storage, describer, LiteLlmConfig(enabled = true))
        runBlocking { service.run(false) }

        storage.addSymbol("app/Bar.kt#Bar", "app/Bar.kt", "class", "Bar", "Bar")
        describer.default = "Regenerated description."
        val report = runBlocking { service.run(true) }

        report.regenerated shouldBe 1
        report.staleFlagged shouldBe 0
        describer.calls shouldBe 2
        val appNode = storage.getAllNodes().first { it.type == NodeType.CodeModule }
        (appNode.properties["description"] as JsonPrimitive).content shouldBe "Regenerated description."
        (appNode.properties["descriptionStale"] as JsonPrimitive).content shouldBe "false"
    }

    test("a parent module's description is generated from its children's descriptions, not their symbols") {
        val root = repoWith("app", "app:feature-a")
        val storage = ServiceFakeStorage()
        ModuleDetector().detect(root, ContextGraphConfig()).toGraphNodes().forEach { storage.upsertNode(it) }
        storage.addSymbol("app/feature-a/Widget.kt#Widget", "app/feature-a/Widget.kt", "class", "Widget", "Widget")

        val prompts = mutableListOf<String>()
        val describer = object : ModuleDescriber {
            override suspend fun describe(prompt: String, config: LiteLlmConfig): String? {
                prompts.add(prompt)
                // Match on the prompt's own module header, not a bare substring: the parent's
                // rollup prompt legitimately *contains* "feature-a" too, since it embeds the
                // child's name and path alongside its description. Matching on "Module: feature-a"
                // instead of a loose "contains" is what makes this test distinguish "the child was
                // asked" from "the parent's prompt happens to mention the child's name."
                return if (prompt.startsWith("Module: feature-a")) "Feature A handles widgets." else "Parent rollup."
            }
        }
        val service = ModuleDescriptionService(storage, describer, LiteLlmConfig(enabled = true))

        val report = runBlocking { service.run(false) }

        report.generated shouldBe 2
        val appNode = storage.getAllNodes().first { it.properties["path"]?.let { (it as JsonPrimitive).content } == "app" }
        val parentPrompt = prompts.last()
        (parentPrompt.startsWith("Module: app")) shouldBe true
        (appNode.properties["description"] as JsonPrimitive).content shouldBe "Parent rollup."
        // The parent's prompt must be built from the child's *description*, never Widget's own symbol line.
        (parentPrompt.contains("Widget")) shouldBe false
        (parentPrompt.contains("Feature A handles widgets.")) shouldBe true
    }
})

private class ScriptedDescriber(var default: String?) : ModuleDescriber {
    var calls = 0
    override suspend fun describe(prompt: String, config: LiteLlmConfig): String? {
        calls++
        return default
    }
}

private class ServiceFakeStorage : StorageAdapter {
    private val nodes = mutableMapOf<String, GraphNode>()
    private val provenance = mutableMapOf<String, MutableList<Provenance>>()

    fun addSymbol(id: String, path: String, kind: String, name: String, fqn: String?) {
        upsertNode(
            GraphNode(
                id = NodeId(id),
                type = NodeType.Class,
                label = name,
                properties = buildMap {
                    put("kind", JsonPrimitive(kind))
                    fqn?.let { put("fqn", JsonPrimitive(it)) }
                }
            )
        )
        provenance.getOrPut(id) { mutableListOf() }.add(
            Provenance(ArtifactId("art:$path"), path, extractor = "test", extractedAt = Clock.System.now())
        )
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
