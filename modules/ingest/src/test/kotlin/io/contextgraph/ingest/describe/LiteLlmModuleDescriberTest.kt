package io.contextgraph.ingest.describe

import io.contextgraph.core.LiteLlmConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking

/**
 * This is the test the slice's non-negotiable rests on: "the LLM sees only the symbol
 * inventory, never raw file bodies, and that must be verifiable from the request payload."
 * [MockEngine] captures the actual outgoing HTTP request built by ktor's real serialization
 * pipeline -- not a hand-rolled string this test controls -- so a bug in how the request is
 * assembled would show up here, not just in [SymbolInventoryTest].
 */
class LiteLlmModuleDescriberTest : FunSpec({

    val rawSourceMarker = "SECRET_RAW_FUNCTION_BODY_SHOULD_NEVER_LEAVE_THE_MACHINE_9f3a"

    test("disabled config makes zero HTTP calls") {
        var calls = 0
        val engine = MockEngine { calls++; respond("{}", HttpStatusCode.OK) }
        val describer = LiteLlmModuleDescriber(engine)

        val result = runBlocking { describer.describe("some prompt", LiteLlmConfig(enabled = false)) }

        result shouldBe null
        calls shouldBe 0
    }

    test("enabled config sends a request whose body contains only the prompt text, never raw source") {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = (request.body as io.ktor.http.content.TextContent).text
            respond(
                content = ByteReadChannel(
                    """{"choices":[{"message":{"content":"This module handles widgets."}}]}"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val describer = LiteLlmModuleDescriber(engine)
        val inventoryPrompt = "app/Widget.kt :: class Widget [fqn=Widget]"

        val result = runBlocking {
            describer.describe(inventoryPrompt, LiteLlmConfig(enabled = true, baseUrl = "http://fake"))
        }

        result shouldBe "This module handles widgets."
        capturedBody shouldContain "app/Widget.kt :: class Widget [fqn=Widget]"
        capturedBody shouldNotContain rawSourceMarker
    }

    test("an unreachable endpoint returns null instead of throwing") {
        val engine = MockEngine { throw java.io.IOException("connection refused") }
        val describer = LiteLlmModuleDescriber(engine)

        val result = runBlocking {
            describer.describe("prompt", LiteLlmConfig(enabled = true, baseUrl = "http://fake"))
        }

        result shouldBe null
    }

    test("a malformed response returns null instead of throwing") {
        val engine = MockEngine { respond("not json at all", HttpStatusCode.OK) }
        val describer = LiteLlmModuleDescriber(engine)

        val result = runBlocking {
            describer.describe("prompt", LiteLlmConfig(enabled = true, baseUrl = "http://fake"))
        }

        result shouldBe null
    }
})
