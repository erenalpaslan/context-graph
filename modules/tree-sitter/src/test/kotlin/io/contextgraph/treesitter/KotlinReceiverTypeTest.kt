package io.contextgraph.treesitter

import io.contextgraph.core.ArtifactId
import io.contextgraph.core.UnresolvedReference
import io.contextgraph.treesitter.grammars.KotlinLanguageSupport
import io.github.treesitter.ktreesitter.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pass 1's half of receiver-type resolution for Kotlin: does the walker read what the file says
 * about each call's receiver, and record the declared types pass 2 needs to finish the job?
 *
 * Without these every Kotlin member call resolved by name alone, so a call made through a
 * dependency holder -- `useCases.loadOrder.execute()` -- had no edge at all once `execute` was
 * declared on more types than the ambiguity cap allows.
 */
class KotlinReceiverTypeTest : FunSpec({

    fun extract(source: String) = KotlinLanguageSupport.extract(
        SymbolExtractionRequest(
            tree = Parser(KotlinLanguageSupport.language()).parse(source),
            source = source,
            sourceText = SourceText(source),
            repoRelativePath = "app/Caller.kt",
            artifactId = ArtifactId("app/Caller.kt"),
            extractorId = "tree-sitter",
            extractedAt = Instant.parse("2026-11-03T00:00:00Z")
        )
    )

    fun referencesIn(source: String): List<UnresolvedReference> = extract(source).references

    fun List<UnresolvedReference>.named(name: String) = single { it.referenceName == name }

    test("parameters, typed locals and constructor properties give the receiver's declared type") {
        val refs = referencesIn(
            """
            class Caller(private val orders: OrderRepository, plain: Ignored) {
                fun run(session: UserSession?) {
                    val mapper: Mapper<Order> = make()
                    session.getId()
                    mapper.toUi()
                    orders.load()
                }
            }
            """.trimIndent()
        )
        refs.named("getId").receiverType shouldBe "UserSession"
        refs.named("toUi").receiverType shouldBe "Mapper"
        refs.named("load").receiverType shouldBe "OrderRepository"
    }

    test("body properties reach every function, bare or through this, and this is the enclosing type") {
        val refs = referencesIn(
            """
            class Caller {
                private val cache: OrderCache = OrderCache()
                fun a() { cache.read() }
                fun b() { this.cache.write() }
                fun c() { this.helper() }
            }
            """.trimIndent()
        )
        refs.named("read").receiverType shouldBe "OrderCache"
        refs.named("write").receiverType shouldBe "OrderCache"
        refs.named("helper").receiverType shouldBe "Caller"
    }

    test("a capitalised receiver is taken as an object or companion, and an inferred local gives no hint") {
        val refs = referencesIn(
            """
            fun run() {
                val inferred = load()
                Formatter.format()
                inferred.size()
            }
            """.trimIndent()
        )
        refs.named("format").receiverType shouldBe "Formatter"
        refs.named("size").receiverType shouldBe null
    }

    test("a receiver that is another call records that call's name") {
        val refs = referencesIn(
            """
            class Caller(private val api: OrderApi) {
                fun run() {
                    api.fetch(1).fold(onSuccess = {}, onError = {})
                    runCatching { 1 }.getOrNull()
                }
            }
            """.trimIndent()
        )
        refs.named("fold").receiverCall shouldBe "fetch"
        refs.named("getOrNull").receiverCall shouldBe "runCatching"
    }

    test("a receiver that is a property of a named value records the property and its owner's type") {
        val refs = referencesIn(
            """
            class Caller(private val useCases: OrderUseCases) {
                fun run() { useCases.loadOrder.execute() }
            }
            """.trimIndent()
        )
        val execute = refs.named("execute")
        execute.receiverProperty shouldBe "loadOrder"
        execute.receiverPropertyOwner shouldBe "OrderUseCases"
        execute.receiverType shouldBe null
    }

    test("calling a typed value directly is a call to its type's invoke") {
        val refs = referencesIn(
            """
            class Caller(private val loadOrder: LoadOrderUseCase) {
                fun run(validate: Validator) {
                    loadOrder(1)
                    validate()
                    notAValue()
                }
            }
            """.trimIndent()
        )
        refs.filter { it.referenceName == "invoke" }.map { it.receiverType } shouldBe listOf("LoadOrderUseCase", "Validator")
        refs.named("notAValue").receiverType shouldBe null
    }

    test("a member call reports its own line, not the line its receiver chain starts on") {
        val refs = referencesIn(
            """
            class Caller(private val api: OrderApi) {
                fun run() {
                    api.fetch(1)
                        .map { it }
                        .fold(onSuccess = {}, onError = {})
                }
            }
            """.trimIndent()
        )
        refs.named("fetch").line shouldBe 3
        refs.named("map").line shouldBe 4
        refs.named("fold").line shouldBe 5
    }

    test("declarations carry their declared type, return type and supertypes as simple names") {
        val nodes = extract(
            """
            class Caller(val orders: a.b.OrderRepository?) : Base(), Listener<Order> {
                val cache: Map<String, Order> = emptyMap()
                fun load(id: String): Result<Order> = TODO()
                fun inferred() = 1
            }
            """.trimIndent()
        ).nodes.associateBy { it.id.value.substringAfter('#') }

        nodes.getValue("Caller.orders").properties["type"] shouldBe JsonPrimitive("OrderRepository")
        nodes.getValue("Caller.cache").properties["type"] shouldBe JsonPrimitive("Map")
        nodes.getValue("Caller.load(String)").properties["returns"] shouldBe JsonPrimitive("Result")
        nodes.getValue("Caller.inferred()").properties["returns"] shouldBe null
        nodes.getValue("Caller").properties["supertypes"] shouldBe
            JsonArray(listOf(JsonPrimitive("Base"), JsonPrimitive("Listener")))
    }
})
