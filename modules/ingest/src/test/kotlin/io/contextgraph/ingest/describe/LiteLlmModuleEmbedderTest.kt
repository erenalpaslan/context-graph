package io.contextgraph.ingest.describe

import io.contextgraph.core.LiteLlmConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking

/**
 * Mirrors [LiteLlmModuleDescriberTest]'s shape for the `/v1/embeddings` client: disabled config
 * makes no call, a well-formed response parses into a vector, a wrong-length vector is discarded
 * rather than trusted, and network/parse failures degrade to `null` instead of throwing.
 */
class LiteLlmModuleEmbedderTest : FunSpec({

    fun vectorJson(dims: Int) = (1..dims).joinToString(",") { "0.0$it" }

    test("disabled config makes zero HTTP calls") {
        var calls = 0
        val engine = MockEngine { calls++; respond("{}", HttpStatusCode.OK) }
        val embedder = LiteLlmModuleEmbedder(engine)

        val result = runBlocking { embedder.embed("some text", LiteLlmConfig(enabled = false)) }

        result shouldBe null
        calls shouldBe 0
    }

    test("enabled config sends the fixed embedding model and text, and parses the returned vector") {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = (request.body as io.ktor.http.content.TextContent).text
            respond(
                content = ByteReadChannel("""{"data":[{"embedding":[${vectorJson(ModuleEmbeddingModel.DIMENSION)}]}]}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val embedder = LiteLlmModuleEmbedder(engine)

        val result = runBlocking {
            embedder.embed("This module handles widgets.", LiteLlmConfig(enabled = true, baseUrl = "http://fake"))
        }

        result?.size shouldBe ModuleEmbeddingModel.DIMENSION
        capturedBody shouldContain "\"model\":\"${ModuleEmbeddingModel.NAME}\""
        capturedBody shouldContain "This module handles widgets."
    }

    test("a vector of the wrong dimension is discarded, not trusted") {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel("""{"data":[{"embedding":[0.1,0.2,0.3]}]}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val embedder = LiteLlmModuleEmbedder(engine)

        val result = runBlocking {
            embedder.embed("text", LiteLlmConfig(enabled = true, baseUrl = "http://fake"))
        }

        result shouldBe null
    }

    test("an unreachable endpoint returns null instead of throwing") {
        val engine = MockEngine { throw java.io.IOException("connection refused") }
        val embedder = LiteLlmModuleEmbedder(engine)

        val result = runBlocking {
            embedder.embed("text", LiteLlmConfig(enabled = true, baseUrl = "http://fake"))
        }

        result shouldBe null
    }

    test("a malformed response returns null instead of throwing") {
        val engine = MockEngine { respond("not json at all", HttpStatusCode.OK) }
        val embedder = LiteLlmModuleEmbedder(engine)

        val result = runBlocking {
            embedder.embed("text", LiteLlmConfig(enabled = true, baseUrl = "http://fake"))
        }

        result shouldBe null
    }
})
