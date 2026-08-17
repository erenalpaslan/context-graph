package io.contextgraph.mcp

import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.LiteLlmConfig
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.ingest.describe.ModuleEmbedder
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** Never resolves anything -- these tests exercise the token/keyword-fallback path deliberately, the same as a repo with `litellm.enabled = false` (this environment's default). */
private val noOpEmbedder = object : ModuleEmbedder {
    override suspend fun embed(text: String, config: LiteLlmConfig): FloatArray? = null
}

private val disabledLiteLlm = LiteLlmConfig(enabled = false)

class ExploreEngineTest : FunSpec({

    lateinit var storage: SqliteStorageAdapter
    lateinit var projectRoot: Path

    beforeEach {
        val dbDir = Files.createTempDirectory("explore-engine-db")
        storage = SqliteStorageAdapter(dbDir.resolve("graph.db"))
        projectRoot = Files.createTempDirectory("explore-engine-project")
    }

    afterEach { storage.close() }

    fun writeSource(relativePath: String, lines: List<String>) {
        val file = projectRoot.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.write(file, lines)
    }

    fun addSymbol(id: String, label: String, lineStart: Int, lineEnd: Int, path: String, fqn: String = label) {
        val nodeId = NodeId(id)
        storage.upsertNode(
            GraphNode(nodeId, NodeType.Method, label, properties = mapOf("fqn" to JsonPrimitive(fqn)), confidence = 0.98)
        )
        storage.upsertProvenance(
            nodeId.value, "node",
            Provenance(ArtifactId("art"), path, lineStart = lineStart, lineEnd = lineEnd, extractor = "tree-sitter", extractedAt = Clock.System.now())
        )
    }

    fun engine(confidenceFloor: Double = ConfidenceDefaults.CALL_RESOLUTION_REPO_UNIQUE_NAME) =
        ExploreEngine(storage, projectRoot, noOpEmbedder, disabledLiteLlm, confidenceFloor)

    test("returns verbatim source for a matched symbol, exactly the recorded line range") {
        writeSource(
            "Auth/UserService.kt",
            listOf(
                "package auth",
                "",
                "class UserService {",
                "    fun authenticate(user: String): Boolean {",
                "        return true",
                "    }",
                "}"
            )
        )
        addSymbol("Auth/UserService.kt#UserService.authenticate", "authenticate", lineStart = 4, lineEnd = 6, path = "Auth/UserService.kt")

        val response = engine().explore("authenticate")

        response.empty.shouldBeFalse()
        val symbol = response.symbols.single { it.id == "Auth/UserService.kt#UserService.authenticate" }
        symbol.elided.shouldBeFalse()
        symbol.source shouldBe "    fun authenticate(user: String): Boolean {\n        return true\n    }"
        symbol.path shouldBe "Auth/UserService.kt"
        symbol.lineStart shouldBe 4
        symbol.lineEnd shouldBe 6
    }

    test("surfaces both confidence and resolution rung on a Calls edge") {
        writeSource("Caller.kt", listOf("fun caller() {}"))
        writeSource("Callee.kt", listOf("fun callee() {}"))
        addSymbol("Caller.kt#caller", "caller", 1, 1, "Caller.kt")
        addSymbol("Callee.kt#callee", "callee", 1, 1, "Callee.kt")
        storage.upsertEdge(
            GraphEdge(
                EdgeId("calls:1"), NodeId("Caller.kt#caller"), NodeId("Callee.kt#callee"), EdgeType.Calls,
                properties = mapOf("rung" to JsonPrimitive("same_directory")),
                confidence = ConfidenceDefaults.CALL_RESOLUTION_SAME_DIRECTORY
            )
        )

        val response = engine().explore("caller")

        val symbol = response.symbols.single { it.id == "Caller.kt#caller" }
        val callsEdge = symbol.edges.single { it.type == "calls" && it.direction == "outgoing" }
        callsEdge.confidence shouldBe ConfidenceDefaults.CALL_RESOLUTION_SAME_DIRECTORY
        callsEdge.rung shouldBe "same_directory"
        callsEdge.nodeId shouldBe "Callee.kt#callee"
    }

    test("blast radius reverse-traverses Calls edges and reports the confidence floor used") {
        writeSource("Callee.kt", listOf("fun callee() {}"))
        writeSource("Caller.kt", listOf("fun caller() {}"))
        addSymbol("Callee.kt#callee", "callee", 1, 1, "Callee.kt")
        addSymbol("Caller.kt#caller", "caller", 1, 1, "Caller.kt")
        storage.upsertEdge(
            GraphEdge(
                EdgeId("calls:1"), NodeId("Caller.kt#caller"), NodeId("Callee.kt#callee"), EdgeType.Calls,
                properties = mapOf("rung" to JsonPrimitive("repo_unique_name")),
                confidence = ConfidenceDefaults.CALL_RESOLUTION_REPO_UNIQUE_NAME
            )
        )

        val response = engine(confidenceFloor = 0.8).explore("callee")

        response.blastRadiusConfidenceFloor shouldBe 0.8
        val symbol = response.symbols.single { it.id == "Callee.kt#callee" }
        symbol.blastRadius.shouldNotBeEmpty()
        symbol.blastRadius.single().nodeId shouldBe "Caller.kt#caller"
    }

    test("blast radius excludes a caller resolved below the confidence floor") {
        writeSource("Callee.kt", listOf("fun callee() {}"))
        writeSource("Caller.kt", listOf("fun caller() {}"))
        addSymbol("Callee.kt#callee", "callee", 1, 1, "Callee.kt")
        addSymbol("Caller.kt#caller", "caller", 1, 1, "Caller.kt")
        storage.upsertEdge(
            GraphEdge(
                EdgeId("calls:1"), NodeId("Caller.kt#caller"), NodeId("Callee.kt#callee"), EdgeType.Calls,
                properties = mapOf("rung" to JsonPrimitive("repo_unique_name")),
                confidence = ConfidenceDefaults.CALL_RESOLUTION_REPO_UNIQUE_NAME_AMBIGUOUS
            )
        )

        val response = engine(confidenceFloor = 0.8).explore("callee")

        val symbol = response.symbols.single { it.id == "Callee.kt#callee" }
        symbol.blastRadius.shouldBeEmpty()
    }

    test("flags an undescribed module") {
        storage.upsertNode(
            GraphNode(
                NodeId("mod:billing"), NodeType.CodeModule, "billing",
                properties = mapOf("path" to JsonPrimitive("billing"), "undescribed" to JsonPrimitive(true))
            )
        )

        val response = engine().explore("billing")

        val module = response.modules.single { it.id == "mod:billing" }
        module.undescribed.shouldBeTrue()
        module.description.shouldBeNull()
    }

    test("flags a stale module description") {
        storage.upsertNode(
            GraphNode(
                NodeId("mod:billing"), NodeType.CodeModule, "billing",
                properties = mapOf(
                    "path" to JsonPrimitive("billing"),
                    "description" to JsonPrimitive("Handles billing"),
                    "undescribed" to JsonPrimitive(false),
                    "descriptionStale" to JsonPrimitive(true)
                )
            )
        )

        val response = engine().explore("billing")

        val module = response.modules.single { it.id == "mod:billing" }
        module.descriptionStale.shouldBeTrue()
        module.description shouldBe "Handles billing"
    }

    test("packs highest-ranked symbols with full source and elides the remainder once the token budget is exceeded") {
        val bigBody = (1..80).map { "    line $it of a long function body" }
        writeSource("Big1.kt", listOf("fun bigOne() {") + bigBody + listOf("}"))
        writeSource("Big2.kt", listOf("fun bigTwo() {") + bigBody + listOf("}"))
        addSymbol("Big1.kt#bigOne", "bigOne", 1, bigBody.size + 2, "Big1.kt")
        addSymbol("Big2.kt#bigTwo", "bigTwo", 1, bigBody.size + 2, "Big2.kt")

        // Each symbol's full (source + overhead) cost is ~738 estimated tokens (see
        // ExploreEngine.fullCost); 900 fits exactly one, not both, exercising the packing edge.
        val response = engine().explore("big", tokenBudget = 900)

        response.truncated.shouldBeTrue()
        response.symbols.shouldNotBeEmpty()
        val elided = response.symbols.filter { it.elided }
        val full = response.symbols.filterNot { it.elided }
        elided.shouldNotBeEmpty()
        full.shouldNotBeEmpty()
        elided.forEach { it.source.shouldBeNull() }
        full.forEach { it.source.shouldNotBeNull() }
        response.estimatedTokensUsed shouldBe response.estimatedTokensUsed // sanity: computed, not left at 0
        (response.estimatedTokensUsed > 0).shouldBeTrue()
    }

    test("documents the default token budget") {
        addSymbol("A.kt#a", "a", 1, 1, "A.kt")
        writeSource("A.kt", listOf("fun a() {}"))

        val response = engine().explore("a")

        response.tokenBudget shouldBe DEFAULT_EXPLORE_TOKEN_BUDGET
        DEFAULT_EXPLORE_TOKEN_BUDGET shouldBe 15_000
    }

    test("a question matching nothing returns an explicit empty result, not an error") {
        val response = engine().explore("zzzznonexistentqueryxyz")

        response.empty.shouldBeTrue()
        response.modules.shouldBeEmpty()
        response.symbols.shouldBeEmpty()
    }

    test("a blank question returns an explicit empty result, not an error") {
        val response = engine().explore("   ")

        response.empty.shouldBeTrue()
    }
})
