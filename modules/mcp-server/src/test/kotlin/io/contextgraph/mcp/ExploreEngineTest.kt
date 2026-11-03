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

    fun addSymbol(
        id: String,
        label: String,
        lineStart: Int,
        lineEnd: Int,
        path: String,
        fqn: String = label,
        type: NodeType = NodeType.Method
    ) {
        val nodeId = NodeId(id)
        storage.upsertNode(
            GraphNode(nodeId, type, label, properties = mapOf("fqn" to JsonPrimitive(fqn)), confidence = 0.98)
        )
        storage.upsertProvenance(
            nodeId.value, "node",
            Provenance(ArtifactId("art"), path, lineStart = lineStart, lineEnd = lineEnd, extractor = "tree-sitter", extractedAt = Clock.System.now())
        )
    }

    fun engine(confidenceFloor: Double = ConfidenceDefaults.CALL_RESOLUTION_REPO_UNIQUE_NAME) =
        ExploreEngine(storage, projectRoot, noOpEmbedder, disabledLiteLlm, confidenceFloor)

    fun calls(from: String, to: String, n: Int, confidence: Double = ConfidenceDefaults.CALL_RESOLUTION_SAME_DIRECTORY) =
        storage.upsertEdge(GraphEdge(EdgeId("calls:$n"), NodeId(from), NodeId(to), EdgeType.Calls, confidence = confidence))

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

        val response = engine().explore("caller", detail = ExploreDetail.FULL)

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

        val response = engine(confidenceFloor = 0.8).explore("callee", detail = ExploreDetail.FULL)

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

        val response = engine(confidenceFloor = 0.8).explore("callee", detail = ExploreDetail.FULL)

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

        // Each symbol encodes to ~830 tokens with its full source and ~45 elided; 1,000 fits
        // one full symbol plus the other elided, not both full, exercising the packing edge.
        val response = engine().explore("big", tokenBudget = 1_000, detail = ExploreDetail.FULL)

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

    test("the encoded response stays within the token budget even when long ids and many edges dominate it") {
        // Long scope-chain ids and edge-heavy symbols are what the old per-edge guess
        // (40 chars) underestimated by an order of magnitude on real repositories.
        val dir = "very/deeply/nested/source/root/com/example/feature/payment/presentation/giftcard"
        val hub = "$dir/GiftCardBalanceViewModel.kt#GiftCardBalanceViewModel.handleGiftCardBalanceRequest"
        writeSource("$dir/GiftCardBalanceViewModel.kt", (1..30).map { "    val giftCardBalanceLine$it = $it" })
        addSymbol(hub, "handleGiftCardBalanceRequest", 1, 30, "$dir/GiftCardBalanceViewModel.kt")
        repeat(30) { i ->
            val path = "$dir/GiftCardBalanceUseCase$i.kt"
            val id = "$path#GiftCardBalanceUseCase$i.giftCardBalanceStep$i"
            writeSource(path, (1..30).map { "    val giftCardBalanceStep${i}Line$it = $it" })
            addSymbol(id, "giftCardBalanceStep$i", 1, 30, path)
            storage.upsertEdge(
                GraphEdge(
                    EdgeId("calls:$i"), NodeId(hub), NodeId(id), EdgeType.Calls,
                    properties = mapOf("rung" to JsonPrimitive("same_directory")),
                    confidence = ConfidenceDefaults.CALL_RESOLUTION_SAME_DIRECTORY
                )
            )
        }

        for (detail in ExploreDetail.values()) {
            for (budget in listOf(500, 2_000, 6_000)) {
                val response = engine().explore("gift card balance", tokenBudget = budget, detail = detail)
                val encoded = encodeExploreResponse(response)

                (encoded.length / 4.0 <= budget).shouldBeTrue()
                response.estimatedTokensUsed shouldBe (encoded.length / 4.0).toInt()
                if (detail == ExploreDetail.FULL) response.truncated.shouldBeTrue()
            }
        }
    }

    test("the encoded response is compact JSON") {
        writeSource("A.kt", listOf("fun alpha() {", "    return", "}"))
        addSymbol("A.kt#alpha", "alpha", 1, 3, "A.kt")

        val encoded = encodeExploreResponse(engine().explore("alpha"))

        encoded.contains("\n    \"").shouldBeFalse()
        encoded.startsWith("{\"question\":").shouldBeTrue()
    }

    test("ranks production symbols above test symbols that match the same terms") {
        writeSource("src/main/Balance.kt", listOf("fun balance() {}"))
        writeSource("src/test/BalanceTest.kt", listOf("fun balanceReturnsCachedBalance() {}"))
        addSymbol("src/test/BalanceTest.kt#BalanceTest.balanceReturnsCachedBalance", "balanceReturnsCachedBalance", 1, 1, "src/test/BalanceTest.kt")
        addSymbol("src/main/Balance.kt#balance", "balance", 1, 1, "src/main/Balance.kt")

        val ids = engine().explore("cached balance").symbols.map { it.id }

        ids.first() shouldBe "src/main/Balance.kt#balance"
    }

    test("keeps test symbols in normal order when the question asks about tests") {
        writeSource("src/main/Balance.kt", listOf("fun balance() {}"))
        writeSource("src/test/BalanceTest.kt", listOf("fun balanceReturnsCachedBalance() {}"))
        addSymbol("src/test/BalanceTest.kt#BalanceTest.balanceReturnsCachedBalance", "balanceReturnsCachedBalance", 1, 1, "src/test/BalanceTest.kt")
        addSymbol("src/main/Balance.kt#balance", "balance", 1, 1, "src/main/Balance.kt")

        val ids = engine().explore("which test covers cached balance").symbols.map { it.id }

        ids.first() shouldBe "src/test/BalanceTest.kt#BalanceTest.balanceReturnsCachedBalance"
    }

    test("documents the default token budgets: pointers by default, full on request") {
        addSymbol("A.kt#alpha", "alpha", 1, 1, "A.kt")
        writeSource("A.kt", listOf("fun alpha() {}"))

        val pointers = engine().explore("alpha")
        val full = engine().explore("alpha", detail = ExploreDetail.FULL)

        pointers.detail shouldBe "pointers"
        pointers.tokenBudget shouldBe DEFAULT_POINTERS_TOKEN_BUDGET
        DEFAULT_POINTERS_TOKEN_BUDGET shouldBe 4_000
        full.detail shouldBe "full"
        full.tokenBudget shouldBe DEFAULT_EXPLORE_TOKEN_BUDGET
        DEFAULT_EXPLORE_TOKEN_BUDGET shouldBe 15_000
    }

    test("pointers carry source for the top symbols only and short call references for every symbol") {
        repeat(4) { i ->
            writeSource("Ledger$i.kt", listOf("fun postLedger$i() {", "    step()", "}"))
            addSymbol("Ledger$i.kt#postLedger$i", "postLedger$i", 1, 3, "Ledger$i.kt")
        }
        repeat(3) { i -> calls("Ledger$i.kt#postLedger$i", "Ledger${i + 1}.kt#postLedger${i + 1}", i) }

        val response = engine().explore("post ledger")

        response.symbols.size shouldBe 4
        response.symbols.take(POINTER_SOURCE_SYMBOLS).forEach { it.source.shouldNotBeNull() }
        response.symbols.drop(POINTER_SOURCE_SYMBOLS).forEach {
            it.source.shouldBeNull()
            it.elided.shouldBeTrue()
        }
        response.symbols.forEach {
            it.edges.shouldBeEmpty()
            it.blastRadius.shouldBeEmpty()
            it.why.shouldNotBeNull()
        }
        val second = response.symbols.single { it.id == "Ledger1.kt#postLedger1" }
        second.calls shouldBe listOf("Ledger2.kt#postLedger2")
        second.calledBy shouldBe listOf("Ledger0.kt#postLedger0")
    }

    test("a pointer's source is cut to its first lines and says so") {
        val body = (1..100).map { "    line $it" }
        writeSource("Long.kt", listOf("fun longLedger() {") + body + listOf("}"))
        addSymbol("Long.kt#longLedger", "longLedger", 1, body.size + 2, "Long.kt")

        val symbol = engine().explore("long ledger", tokenBudget = 4_000).symbols.single()

        symbol.sourceTruncated shouldBe true
        symbol.source.shouldNotBeNull().lines().size shouldBe 60
    }

    test("ranks a method above a constant whose name repeats the question words") {
        writeSource("Constants.kt", listOf("const val POST_LEDGER_ACTION = \"post\""))
        writeSource("Ledger.kt", listOf("fun postLedgerAction() {}"))
        addSymbol("Constants.kt#POST_LEDGER_ACTION", "POST_LEDGER_ACTION", 1, 1, "Constants.kt", type = NodeType.Custom("Property"))
        addSymbol("Ledger.kt#postLedgerAction", "postLedgerAction", 1, 1, "Ledger.kt")

        val ids = engine().explore("post ledger action").symbols.map { it.id }

        ids.first() shouldBe "Ledger.kt#postLedgerAction"
    }

    test("of two equal name matches, ranks first the one linked to another match") {
        writeSource("A.kt", listOf("fun settleInvoice() {}"))
        writeSource("B.kt", listOf("fun settleInvoice() {}"))
        writeSource("C.kt", listOf("fun invoiceTotal() {}"))
        addSymbol("A.kt#settleInvoice", "settleInvoice", 1, 1, "A.kt")
        addSymbol("B.kt#settleInvoice", "settleInvoice", 1, 1, "B.kt")
        addSymbol("C.kt#invoiceTotal", "invoiceTotal", 1, 1, "C.kt")
        calls("B.kt#settleInvoice", "C.kt#invoiceTotal", 1)

        val ids = engine().explore("settle invoice total").symbols.map { it.id }

        ids.indexOf("B.kt#settleInvoice") shouldBe ids.indexOf("A.kt#settleInvoice") - 1
    }

    test("pulls in a callee whose name shares no word with the question") {
        writeSource("Checkout.kt", listOf("fun checkoutCart() {}"))
        writeSource("Gateway.kt", listOf("fun chargeGateway() {}"))
        addSymbol("Checkout.kt#checkoutCart", "checkoutCart", 1, 1, "Checkout.kt")
        addSymbol("Gateway.kt#chargeGateway", "chargeGateway", 1, 1, "Gateway.kt")
        calls("Checkout.kt#checkoutCart", "Gateway.kt#chargeGateway", 1)

        val symbol = engine().explore("checkout cart").symbols.single { it.id == "Gateway.kt#chargeGateway" }

        symbol.why.shouldNotBeNull().startsWith("graph neighbour") shouldBe true
    }

    test("pulls in the enclosing class of a matched method") {
        writeSource("Orders.kt", listOf("class Zephyr {", "    fun submitOrderNow() {}", "}"))
        addSymbol("Orders.kt#Zephyr", "Zephyr", 1, 3, "Orders.kt", type = NodeType.Class)
        addSymbol("Orders.kt#Zephyr.submitOrderNow", "submitOrderNow", 2, 2, "Orders.kt")
        storage.upsertEdge(
            GraphEdge(EdgeId("contains:1"), NodeId("Orders.kt#Zephyr"), NodeId("Orders.kt#Zephyr.submitOrderNow"), EdgeType.Contains, confidence = 0.98)
        )

        val ids = engine().explore("submit order now").symbols.map { it.id }

        ids.contains("Orders.kt#Zephyr") shouldBe true
    }

    test("splits identifiers into words and questions into adjacent word pairs") {
        identifierWords("ShoppingCartController") shouldBe listOf("shopping", "cart", "controller")
        identifierWords("MAX_CART_ITEMS") shouldBe listOf("max", "cart", "items")
        identifierWords("parseHTTPResponse2") shouldBe listOf("parse", "http", "response", "2")
        termPairs("the shopping cart apply coupon") shouldBe listOf(
            "shopping" to "cart", "cart" to "apply", "apply" to "coupon"
        )
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
