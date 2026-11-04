package io.contextgraph.query

import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files

/**
 * [CallGraph] answers "who calls this", "what does this call" and the same several hops out, in
 * a form an agent can stop at: only resolved calls, with what was left out counted, and calls
 * that dispatch through an interface followed rather than lost.
 */
class CallGraphTest : FunSpec({

    lateinit var storage: SqliteStorageAdapter
    lateinit var graph: CallGraph

    beforeEach {
        storage = SqliteStorageAdapter(Files.createTempDirectory("call-graph-test").resolve("graph.db"))
        graph = CallGraph(storage)
    }

    afterEach { storage.close() }

    fun method(id: String) = storage.upsertNode(GraphNode(NodeId(id), NodeType.Method, id.substringAfterLast('.').substringBefore('(')))

    fun type(id: String) = storage.upsertNode(GraphNode(NodeId(id), NodeType.Class, id.substringAfterLast('#')))

    fun contains(owner: String, member: String) =
        storage.upsertEdge(GraphEdge(EdgeId("contains:$owner:$member"), NodeId(owner), NodeId(member), EdgeType.Contains, confidence = 0.98))

    fun implements(sub: String, sup: String) =
        storage.upsertEdge(GraphEdge(EdgeId("implements:$sub:$sup"), NodeId(sub), NodeId(sup), EdgeType.Implements, confidence = 0.98))

    fun calls(from: String, to: String, confidence: Double = 0.93, line: Int = 10) = storage.upsertEdge(
        GraphEdge(
            EdgeId("calls:$from:$to"), NodeId(from), NodeId(to), EdgeType.Calls, confidence = confidence,
            properties = mapOf("rung" to JsonPrimitive("file_imports"), "lines" to JsonArray(listOf(JsonPrimitive(line))))
        )
    )

    test("callers lists resolved production calls and counts guesses and test calls instead of mixing them in") {
        val target = "net/Outcome.kt#Outcome.fold(Function1,Function1)"
        method(target)
        listOf("app/a/A.kt#A.run()", "app/b/B.kt#B.run()", "app/c/C.kt#C.sum()", "app/src/test/ATest.kt#ATest.runs()").forEach(::method)
        calls("app/a/A.kt#A.run()", target, line = 43)
        calls("app/b/B.kt#B.run()", target)
        calls("app/c/C.kt#C.sum()", target, confidence = 0.45)
        calls("app/src/test/ATest.kt#ATest.runs()", target)

        val list = graph.callers(NodeId(target)).shouldNotBeNull()

        list.resolved.map { it.caller.id.value } shouldContainExactly listOf("app/a/A.kt#A.run()", "app/b/B.kt#B.run()")
        list.resolved.first().lines shouldBe listOf(43)
        list.guesses.map { it.caller.id.value } shouldContainExactly listOf("app/c/C.kt#C.sum()")
        list.testCalls.map { it.caller.id.value } shouldContainExactly listOf("app/src/test/ATest.kt#ATest.runs()")
        graph.callers(NodeId(target), includeTests = true).shouldNotBeNull().resolved.size shouldBe 3
    }

    test("callers of an implementation include calls made through the interface method it implements") {
        type("repo/OrderRepository.kt#OrderRepository")
        method("repo/OrderRepository.kt#OrderRepository.load(String)")
        contains("repo/OrderRepository.kt#OrderRepository", "repo/OrderRepository.kt#OrderRepository.load(String)")
        type("repo/OrderRepositoryImpl.kt#OrderRepositoryImpl")
        method("repo/OrderRepositoryImpl.kt#OrderRepositoryImpl.load(String)")
        contains("repo/OrderRepositoryImpl.kt#OrderRepositoryImpl", "repo/OrderRepositoryImpl.kt#OrderRepositoryImpl.load(String)")
        implements("repo/OrderRepositoryImpl.kt#OrderRepositoryImpl", "repo/OrderRepository.kt#OrderRepository")
        method("app/LoadOrder.kt#LoadOrder.invoke()")
        calls("app/LoadOrder.kt#LoadOrder.invoke()", "repo/OrderRepository.kt#OrderRepository.load(String)")

        val list = graph.callers(NodeId("repo/OrderRepositoryImpl.kt#OrderRepositoryImpl.load(String)")).shouldNotBeNull()

        val site = list.resolved.single()
        site.caller.id.value shouldBe "app/LoadOrder.kt#LoadOrder.invoke()"
        site.via.shouldNotBeNull().id.value shouldBe "repo/OrderRepository.kt#OrderRepository.load(String)"
    }

    test("callees of a call to an interface method include its implementations") {
        type("repo/OrderRepository.kt#OrderRepository")
        method("repo/OrderRepository.kt#OrderRepository.load(String)")
        contains("repo/OrderRepository.kt#OrderRepository", "repo/OrderRepository.kt#OrderRepository.load(String)")
        type("repo/OrderRepositoryImpl.kt#OrderRepositoryImpl")
        method("repo/OrderRepositoryImpl.kt#OrderRepositoryImpl.load(String)")
        contains("repo/OrderRepositoryImpl.kt#OrderRepositoryImpl", "repo/OrderRepositoryImpl.kt#OrderRepositoryImpl.load(String)")
        implements("repo/OrderRepositoryImpl.kt#OrderRepositoryImpl", "repo/OrderRepository.kt#OrderRepository")
        method("app/LoadOrder.kt#LoadOrder.invoke()")
        calls("app/LoadOrder.kt#LoadOrder.invoke()", "repo/OrderRepository.kt#OrderRepository.load(String)")

        val callees = graph.callees(NodeId("app/LoadOrder.kt#LoadOrder.invoke()")).shouldNotBeNull().resolved

        callees.map { it.callee.id.value } shouldContainExactlyInAnyOrder listOf(
            "repo/OrderRepository.kt#OrderRepository.load(String)",
            "repo/OrderRepositoryImpl.kt#OrderRepositoryImpl.load(String)"
        )
    }

    test("a type's callers and callees are its members', without calls between its own members") {
        type("ui/OrderViewModel.kt#OrderViewModel")
        listOf("ui/OrderViewModel.kt#OrderViewModel.load()", "ui/OrderViewModel.kt#OrderViewModel.render()").forEach {
            method(it)
            contains("ui/OrderViewModel.kt#OrderViewModel", it)
        }
        calls("ui/OrderViewModel.kt#OrderViewModel.load()", "ui/OrderViewModel.kt#OrderViewModel.render()")
        method("app/LoadOrder.kt#LoadOrder.invoke()")
        calls("ui/OrderViewModel.kt#OrderViewModel.load()", "app/LoadOrder.kt#LoadOrder.invoke()")
        method("ui/OrderFragment.kt#OrderFragment.onStart()")
        calls("ui/OrderFragment.kt#OrderFragment.onStart()", "ui/OrderViewModel.kt#OrderViewModel.load()")

        graph.callees(NodeId("ui/OrderViewModel.kt#OrderViewModel")).shouldNotBeNull()
            .resolved.map { it.callee.id.value } shouldContainExactly listOf("app/LoadOrder.kt#LoadOrder.invoke()")
        graph.callers(NodeId("ui/OrderViewModel.kt#OrderViewModel")).shouldNotBeNull()
            .resolved.map { it.caller.id.value } shouldContainExactly listOf("ui/OrderFragment.kt#OrderFragment.onStart()")
    }

    test("a callers trace walks several hops and stops at entry points") {
        val api = "api/GetOrderApiUseCase.kt#GetOrderApiUseCase.execute()"
        val useCase = "app/LoadOrder.kt#LoadOrder.invoke()"
        val viewModel = "ui/OrderViewModel.kt#OrderViewModel.load()"
        val fragment = "ui/OrderFragment.kt#OrderFragment.onStart()"
        listOf(api, useCase, viewModel, fragment).forEach(::method)
        calls(useCase, api)
        calls(viewModel, useCase)
        calls(fragment, viewModel)

        val trace = graph.trace(NodeId(api), Trace.Direction.CALLERS, depth = 5, entryPoint = Regex("ViewModel$")).shouldNotBeNull()

        val hop1 = trace.root.children.single()
        hop1.node.id.value shouldBe useCase
        val hop2 = hop1.children.single()
        hop2.node.id.value shouldBe viewModel
        hop2.stop shouldBe TraceStop.ENTRY_POINT
        hop2.children shouldBe emptyList()
    }

    test("a trace shows a node reached twice once in full and marks the second") {
        val target = "api/Api.kt#Api.get()"
        listOf(target, "app/A.kt#A.a()", "app/B.kt#B.b()", "app/Top.kt#Top.top()").forEach(::method)
        calls("app/A.kt#A.a()", target)
        calls("app/B.kt#B.b()", target)
        calls("app/Top.kt#Top.top()", "app/A.kt#A.a()")
        calls("app/Top.kt#Top.top()", "app/B.kt#B.b()")

        val trace = graph.trace(NodeId(target), Trace.Direction.CALLERS, depth = 5, entryPoint = null).shouldNotBeNull()

        val stops = trace.root.children.flatMap { it.children }.map { it.stop }
        stops shouldContainExactlyInAnyOrder listOf(TraceStop.LEAF, TraceStop.SEEN)
    }

    test("a callees trace towards an entry point keeps only the branches that reach one") {
        val viewModel = "ui/OrderViewModel.kt#OrderViewModel.load()"
        val useCase = "app/LoadOrder.kt#LoadOrder.invoke()"
        val api = "api/OrderApi.kt#OrderApi.getOrder()"
        val formatter = "ui/Format.kt#Format.price()"
        listOf(viewModel, useCase, api, formatter).forEach(::method)
        calls(viewModel, useCase)
        calls(useCase, api)
        calls(viewModel, formatter)

        val trace = graph.trace(NodeId(viewModel), Trace.Direction.CALLEES, depth = 5, entryPoint = Regex("Api$")).shouldNotBeNull()

        val only = trace.root.children.single()
        only.node.id.value shouldBe useCase
        only.children.single().stop shouldBe TraceStop.ENTRY_POINT
    }
})
