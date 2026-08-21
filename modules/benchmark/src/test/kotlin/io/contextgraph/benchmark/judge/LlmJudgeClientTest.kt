package io.contextgraph.benchmark.judge

import io.contextgraph.benchmark.model.FactScore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking

/**
 * [LlmJudgeClient] wraps two pure functions around the actual HTTP call:
 * building the chat messages from a [JudgeInput], and parsing the model's
 * JSON response into structured [FactScore]s. The pure functions are tested
 * directly first (no network call needed); the HTTP wiring itself -- what
 * actually gets POSTed, with which model, and how a real chat-completions
 * response gets turned into `FactScore`s -- is proven via [MockEngine],
 * which captures ktor's own real request-serialization pipeline rather than
 * a hand-rolled string this test controls.
 */
class LlmJudgeClientTest : FunSpec({

    val input = JudgeInput(
        questionText = "SENTINEL_QUESTION",
        goldFacts = listOf(
            JudgeGoldFact("f1", "SENTINEL_FACT_ONE"),
            JudgeGoldFact("f2", "SENTINEL_FACT_TWO")
        ),
        answerText = "SENTINEL_ANSWER"
    )

    test("the built prompt carries the question, gold facts and answer, and nothing else") {
        val messages = buildJudgeMessages(input)

        val userContent = messages.last().content
        userContent shouldContain "SENTINEL_QUESTION"
        userContent shouldContain "f1"
        userContent shouldContain "SENTINEL_FACT_ONE"
        userContent shouldContain "f2"
        userContent shouldContain "SENTINEL_FACT_TWO"
        userContent shouldContain "SENTINEL_ANSWER"
    }

    test("the prompt requests structured output, not free-text judgement") {
        val messages = buildJudgeMessages(input)

        val allContent = messages.joinToString("\n") { it.content }
        allContent shouldContain "JSON"
    }

    test("parses a structured JSON verdict into FactScores, filtered to the expected fact ids") {
        val content = """{"scores":[{"factId":"f1","hit":true},{"factId":"f2","hit":false}]}"""

        val scores = parseJudgeContent(content, expectedFactIds = setOf("f1", "f2"))

        scores.shouldContainExactly(FactScore("f1", true), FactScore("f2", false))
    }

    test("drops any fact id in the response that wasn't asked about") {
        val content = """{"scores":[{"factId":"f1","hit":true},{"factId":"unexpected","hit":true}]}"""

        val scores = parseJudgeContent(content, expectedFactIds = setOf("f1"))

        scores shouldBe listOf(FactScore("f1", true))
    }

    test("malformed content is not silently coerced into a verdict") {
        io.kotest.assertions.throwables.shouldThrow<Exception> {
            parseJudgeContent("not json at all", expectedFactIds = setOf("f1"))
        }
    }

    test("the built prompt never carries an arm label or a metric field name") {
        val messages = buildJudgeMessages(input)
        val allContent = messages.joinToString("\n") { it.content }

        allContent shouldNotContain "WITH_TOOLS"
        allContent shouldNotContain "WITHOUT_TOOLS"
        allContent shouldNotContain "toolCallCount"
        allContent shouldNotContain "wallClockMillis"
        allContent shouldNotContain "costUsd"
    }

    test("sends the caller-supplied judge model, never a hard-coded one, and a body carrying only the JudgeInput") {
        var capturedBody = ""
        var capturedPath = ""
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedBody = (request.body as TextContent).text
            respond(
                content = ByteReadChannel(
                    """{"choices":[{"message":{"content":"{\"scores\":[{\"factId\":\"f1\",\"hit\":true},{\"factId\":\"f2\",\"hit\":false}]}"}}]}"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = LlmJudgeClient(JudgeHttpConfig(baseUrl = "http://fake"), engine)

        val scores = runBlocking { client.scoreFacts(input, judgeModel = "SENTINEL_MODEL") }

        capturedPath shouldContain "/v1/chat/completions"
        capturedBody shouldContain "SENTINEL_MODEL"
        capturedBody shouldContain "SENTINEL_QUESTION"
        capturedBody shouldContain "SENTINEL_ANSWER"
        capturedBody shouldNotContain "WITH_TOOLS"
        capturedBody shouldNotContain "toolCallCount"
        scores.shouldContainExactlyInAnyOrder(FactScore("f1", true), FactScore("f2", false))
    }

    test("an unreachable endpoint propagates the failure rather than returning a hidden default score") {
        val engine = MockEngine { throw java.io.IOException("connection refused") }
        val client = LlmJudgeClient(JudgeHttpConfig(baseUrl = "http://fake"), engine)

        io.kotest.assertions.throwables.shouldThrow<Exception> {
            runBlocking { client.scoreFacts(input, judgeModel = "SENTINEL_MODEL") }
        }
    }

    test("a response the judge model truncated to one fact is reconciled by the caller, not silently accepted as complete") {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(
                    """{"choices":[{"message":{"content":"{\"scores\":[{\"factId\":\"f1\",\"hit\":true}]}"}}]}"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = LlmJudgeClient(JudgeHttpConfig(baseUrl = "http://fake"), engine)

        // f2 was asked about (see `input`) but the model's JSON omitted it;
        // the client itself returns only what the model actually gave --
        // JudgeScorer is what reconciles this against the full gold-fact set.
        val scores = runBlocking { client.scoreFacts(input, judgeModel = "SENTINEL_MODEL") }

        scores shouldBe listOf(FactScore("f1", true))
    }

    // ------------------------------------------------------------- task 18: retry

    fun io.ktor.client.engine.mock.MockRequestHandleScope.successResponse() = respond(
        content = ByteReadChannel(
            """{"choices":[{"message":{"content":"{\"scores\":[{\"factId\":\"f1\",\"hit\":true},{\"factId\":\"f2\",\"hit\":false}]}"}}]}"""
        ),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    )

    fun io.ktor.client.engine.mock.MockRequestHandleScope.throttlingBodyResponse(status: HttpStatusCode = HttpStatusCode.OK) = respond(
        content = ByteReadChannel("""{"error":{"type":"throttling_error","code":"429","message":"rate limited"}}"""),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    )

    test("a real HTTP 429 status is retried with exponential backoff, then succeeds") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount < 3) {
                respond(
                    content = ByteReadChannel("""{"error":{"message":"rate limited"}}"""),
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                successResponse()
            }
        }
        val delays = mutableListOf<Long>()
        val client = LlmJudgeClient(
            JudgeHttpConfig(baseUrl = "http://fake"),
            engine,
            retryConfig = JudgeRetryConfig(maxAttempts = 5, initialDelayMillis = 100, backoffMultiplier = 2.0),
            wait = { delays += it }
        )

        val scores = runBlocking { client.scoreFacts(input, judgeModel = "SENTINEL_MODEL") }

        callCount shouldBe 3
        delays shouldBe listOf(100L, 200L) // exponential backoff, waited before attempt 2 and attempt 3
        scores.shouldContainExactlyInAnyOrder(FactScore("f1", true), FactScore("f2", false))
    }

    test("429 arriving as a JSON error body with an HTTP 200 status (the LiteLLM proxy shape) is still retried") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) throttlingBodyResponse(status = HttpStatusCode.OK) else successResponse()
        }
        val client = LlmJudgeClient(
            JudgeHttpConfig(baseUrl = "http://fake"),
            engine,
            retryConfig = JudgeRetryConfig(initialDelayMillis = 1),
            wait = { }
        )

        val scores = runBlocking { client.scoreFacts(input, judgeModel = "SENTINEL_MODEL") }

        callCount shouldBe 2
        scores.shouldContainExactlyInAnyOrder(FactScore("f1", true), FactScore("f2", false))
    }

    test("a 500 (and other 5xx) status is retried the same as 429") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) {
                respond(
                    content = ByteReadChannel("""{"error":{"message":"internal error"}}"""),
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                successResponse()
            }
        }
        val client = LlmJudgeClient(
            JudgeHttpConfig(baseUrl = "http://fake"),
            engine,
            retryConfig = JudgeRetryConfig(initialDelayMillis = 1),
            wait = { }
        )

        val scores = runBlocking { client.scoreFacts(input, judgeModel = "SENTINEL_MODEL") }

        callCount shouldBe 2
        scores.shouldContainExactlyInAnyOrder(FactScore("f1", true), FactScore("f2", false))
    }

    test("a Retry-After header overrides the computed backoff for that wait") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) {
                respond(
                    content = ByteReadChannel("""{"error":{"message":"rate limited"}}"""),
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.RetryAfter, "7")
                )
            } else {
                successResponse()
            }
        }
        val delays = mutableListOf<Long>()
        val client = LlmJudgeClient(
            JudgeHttpConfig(baseUrl = "http://fake"),
            engine,
            retryConfig = JudgeRetryConfig(initialDelayMillis = 100),
            wait = { delays += it }
        )

        runBlocking { client.scoreFacts(input, judgeModel = "SENTINEL_MODEL") }

        delays shouldBe listOf(7000L) // the header's 7 seconds, not the computed 100ms backoff
    }

    test("retries are exhausted after maxAttempts and the call fails loudly") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            throttlingBodyResponse()
        }
        val client = LlmJudgeClient(
            JudgeHttpConfig(baseUrl = "http://fake"),
            engine,
            retryConfig = JudgeRetryConfig(maxAttempts = 3, initialDelayMillis = 1),
            wait = { }
        )

        io.kotest.assertions.throwables.shouldThrow<JudgeCallFailedException> {
            runBlocking { client.scoreFacts(input, judgeModel = "SENTINEL_MODEL") }
        }
        callCount shouldBe 3 // exactly maxAttempts tries, not one more
    }

    test("a 401 is a permanent failure and is never retried") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            respond(
                content = ByteReadChannel("""{"error":{"type":"authentication_error","message":"invalid API key"}}"""),
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = LlmJudgeClient(
            JudgeHttpConfig(baseUrl = "http://fake"),
            engine,
            retryConfig = JudgeRetryConfig(maxAttempts = 5, initialDelayMillis = 1),
            wait = { }
        )

        io.kotest.assertions.throwables.shouldThrow<JudgeCallFailedException> {
            runBlocking { client.scoreFacts(input, judgeModel = "SENTINEL_MODEL") }
        }
        callCount shouldBe 1 // no retry at all for a permanent error
    }

    test("a 403 is a permanent failure and is never retried") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            respond(
                content = ByteReadChannel("""{"error":{"type":"permission_error","message":"forbidden"}}"""),
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = LlmJudgeClient(
            JudgeHttpConfig(baseUrl = "http://fake"),
            engine,
            retryConfig = JudgeRetryConfig(maxAttempts = 5, initialDelayMillis = 1),
            wait = { }
        )

        io.kotest.assertions.throwables.shouldThrow<JudgeCallFailedException> {
            runBlocking { client.scoreFacts(input, judgeModel = "SENTINEL_MODEL") }
        }
        callCount shouldBe 1
    }

    test("an invalid model name (400, no retryable signal) is a permanent failure and is never retried") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            respond(
                content = ByteReadChannel("""{"error":{"type":"invalid_request_error","message":"model not found"}}"""),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = LlmJudgeClient(
            JudgeHttpConfig(baseUrl = "http://fake"),
            engine,
            retryConfig = JudgeRetryConfig(maxAttempts = 5, initialDelayMillis = 1),
            wait = { }
        )

        io.kotest.assertions.throwables.shouldThrow<JudgeCallFailedException> {
            runBlocking { client.scoreFacts(input, judgeModel = "SENTINEL_MODEL") }
        }
        callCount shouldBe 1
    }

    test("isRetryableJudgeFailure: 429 and 5xx status are retryable regardless of body") {
        isRetryableJudgeFailure(HttpStatusCode.TooManyRequests, "") shouldBe true
        isRetryableJudgeFailure(HttpStatusCode.InternalServerError, "") shouldBe true
        isRetryableJudgeFailure(HttpStatusCode.ServiceUnavailable, "not even json") shouldBe true
    }

    test("isRetryableJudgeFailure: a 200 status with a throttling-shaped body is retryable") {
        isRetryableJudgeFailure(
            HttpStatusCode.OK,
            """{"error":{"type":"throttling_error","code":"429"}}"""
        ) shouldBe true
    }

    test("isRetryableJudgeFailure: 401/403 are never retryable, even with an error body") {
        isRetryableJudgeFailure(HttpStatusCode.Unauthorized, """{"error":{"type":"authentication_error"}}""") shouldBe false
        isRetryableJudgeFailure(HttpStatusCode.Forbidden, """{"error":{"type":"permission_error"}}""") shouldBe false
    }
})
