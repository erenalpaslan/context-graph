package io.contextgraph.benchmark.runner

import io.contextgraph.benchmark.judge.JudgeRetryConfig
import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.BenchmarkConfig
import io.contextgraph.benchmark.model.ModelConfig
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Files

private fun question() = Question(
    id = "q1",
    repoId = "repo1",
    text = "What does the auth module do?",
    category = QuestionCategory.GRAPH_HEAVY,
    goldFacts = emptyList()
)

private fun benchmarkConfig(ceiling: Int = 40) =
    BenchmarkConfig(toolCallCeiling = ceiling, repeatsPerArm = 4, models = ModelConfig(agentModel = "claude-sonnet-5"))

private fun MockRequestHandleScope.jsonBody(text: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
    content = ByteReadChannel(text),
    status = status,
    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
)

/**
 * Task 19: [AnthropicMessagesClient] used to parse a response's body without ever looking at the
 * HTTP status -- an error response has no `usage`/`content`/`stop_reason` keys, so it fell
 * straight through the same parsing a real success got and produced a "successful" run with zero
 * tokens and an empty answer. Measured against a real invalid key: a `smoke` run logged "6
 * succeeded, 0 failed" with every record carrying `inputTokens=0, outputTokens=0, costUsd=0.0,
 * finalAnswer=""`.
 *
 * These tests drive the real client (not a stub) against [MockEngine] -- no real network call --
 * and prove: the status is checked, 429/5xx retry, 401/403 fail fast with Anthropic's own
 * `error.type`/`message` in the exception text, a 2xx response with no measurable content is
 * still rejected, and the tool-use loop (including the 40-call ceiling and the contamination
 * guard) survives all of the above unchanged. See `BenchmarkOrchestratorTest`'s "task 19: an
 * invalid API key..." test for the same proof one layer up, through the real orchestrator.
 */
class AnthropicMessagesClientTest : FunSpec({

    test("a normal successful single-turn run returns the real tokens and answer text") {
        val engine = MockEngine {
            jsonBody(
                """{"content":[{"type":"text","text":"the auth module validates tokens"}],""" +
                    """"stop_reason":"end_turn","usage":{"input_tokens":120,"output_tokens":40}}"""
            )
        }
        val client = AnthropicMessagesClient(engine, apiKey = "fake-key", baseUrl = "http://fake")
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("anthropic-client-success")

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())

        record.inputTokens shouldBe 120L
        record.outputTokens shouldBe 40L
        record.finalAnswer shouldBe "the auth module validates tokens"
        record.toolCallCount shouldBe 0
        record.costUsd shouldBe ModelPricing.costUsd("claude-sonnet-5", 120, 40)
    }

    test("a 401 fails the run outright, is never retried, and the error text names Anthropic's error.type and message") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            jsonBody(
                """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
                status = HttpStatusCode.Unauthorized
            )
        }
        val client = AnthropicMessagesClient(
            engine, apiKey = "invalid-key", baseUrl = "http://fake",
            retryConfig = JudgeRetryConfig(maxAttempts = 5, initialDelayMillis = 1), wait = {}
        )
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("anthropic-client-401")

        val exception = shouldThrow<AgentCallFailedException> {
            runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())
        }

        callCount shouldBe 1 // no retry at all for a permanent failure
        exception.message shouldContain "authentication_error"
        exception.message shouldContain "invalid x-api-key"
    }

    test("a 403 is permanent and is never retried") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            jsonBody(
                """{"type":"error","error":{"type":"permission_error","message":"forbidden"}}""",
                status = HttpStatusCode.Forbidden
            )
        }
        val client = AnthropicMessagesClient(
            engine, apiKey = "key", baseUrl = "http://fake",
            retryConfig = JudgeRetryConfig(maxAttempts = 5, initialDelayMillis = 1), wait = {}
        )
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("anthropic-client-403")

        shouldThrow<AgentCallFailedException> {
            runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())
        }
        callCount shouldBe 1
    }

    test("429 is retried with exponential backoff and then succeeds") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount < 3) {
                jsonBody("""{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""", HttpStatusCode.TooManyRequests)
            } else {
                jsonBody("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}""")
            }
        }
        val delays = mutableListOf<Long>()
        val client = AnthropicMessagesClient(
            engine, apiKey = "key", baseUrl = "http://fake",
            retryConfig = JudgeRetryConfig(maxAttempts = 5, initialDelayMillis = 100, backoffMultiplier = 2.0),
            wait = { delays += it }
        )
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("anthropic-client-429")

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())

        callCount shouldBe 3
        delays shouldBe listOf(100L, 200L)
        record.finalAnswer shouldBe "ok"
    }

    test("a 500 (and other 5xx) is retried the same as 429") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) {
                jsonBody("""{"type":"error","error":{"type":"api_error","message":"internal error"}}""", HttpStatusCode.InternalServerError)
            } else {
                jsonBody("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}""")
            }
        }
        val client = AnthropicMessagesClient(
            engine, apiKey = "key", baseUrl = "http://fake",
            retryConfig = JudgeRetryConfig(initialDelayMillis = 1), wait = {}
        )
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("anthropic-client-5xx")

        runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())
        callCount shouldBe 2
    }

    test("retries are exhausted after maxAttempts and the run fails loudly") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            jsonBody("""{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""", HttpStatusCode.TooManyRequests)
        }
        val client = AnthropicMessagesClient(
            engine, apiKey = "key", baseUrl = "http://fake",
            retryConfig = JudgeRetryConfig(maxAttempts = 3, initialDelayMillis = 1), wait = {}
        )
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("anthropic-client-exhausted")

        shouldThrow<AgentCallFailedException> {
            runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())
        }
        callCount shouldBe 3
    }

    test("a 2xx response with no usage/content is not silently accepted as a zero-token success (the original bug shape)") {
        val engine = MockEngine { jsonBody("""{}""") }
        val client = AnthropicMessagesClient(engine, apiKey = "key", baseUrl = "http://fake")
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("anthropic-client-empty-200")

        val exception = shouldThrow<AgentCallFailedException> {
            runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())
        }
        exception.message shouldContain "no final answer text at all"
    }

    // ------------------------------------------------------- tool-use loop preserved

    test("the bash tool round-trips through the real contamination-guarded executor across two turns") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) {
                jsonBody(
                    """{"content":[{"type":"tool_use","id":"call1","name":"bash","input":{"command":"echo hello-from-bash"}}],""" +
                        """"stop_reason":"tool_use","usage":{"input_tokens":50,"output_tokens":10}}"""
                )
            } else {
                jsonBody(
                    """{"content":[{"type":"text","text":"the command printed hello-from-bash"}],""" +
                        """"stop_reason":"end_turn","usage":{"input_tokens":30,"output_tokens":15}}"""
                )
            }
        }
        val client = AnthropicMessagesClient(engine, apiKey = "key", baseUrl = "http://fake")
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("anthropic-client-bash-loop")

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())

        callCount shouldBe 2
        record.toolCallCount shouldBe 1
        record.finalAnswer shouldBe "the command printed hello-from-bash"
        record.inputTokens shouldBe 80L
        record.outputTokens shouldBe 25L
        record.contaminated shouldBe false
    }

    test("AC-10a: the tool-call ceiling still cuts a run off after the configured number of calls, and the run answers from what it gathered rather than falling silent") {
        // Mirrors the OpenAI client's test: the ceiling must stop the tools without silencing the
        // run, or the arm that exhausts its budget scores zero for reasons unrelated to how well
        // it explored. The forced final turn is the one carrying tool_choice type "none".
        var forcedFinalTurns = 0
        val engine = MockEngine { request ->
            val body = String(request.body.toByteArray())
            if (body.contains("\"tool_choice\"")) {
                forcedFinalTurns++
                jsonBody(
                    """{"content":[{"type":"text","text":"partial answer from what I gathered"}],""" +
                        """"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}"""
                )
            } else {
                jsonBody(
                    """{"content":[{"type":"tool_use","id":"call1","name":"bash","input":{"command":"echo again"}}],""" +
                        """"stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":5}}"""
                )
            }
        }
        val client = AnthropicMessagesClient(engine, apiKey = "key", baseUrl = "http://fake")
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("anthropic-client-ceiling")

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig(ceiling = 2))

        record.toolCallCount shouldBe 2
        record.hitCeiling shouldBe true
        forcedFinalTurns shouldBe 1
        record.finalAnswer shouldContain "partial answer"
    }

    test("AC-8/AC-9: an attempted contextgraph CLI invocation is blocked, counted, and never marks the run contaminated") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) {
                jsonBody(
                    """{"content":[{"type":"tool_use","id":"call1","name":"bash","input":{"command":"contextgraph search foo"}}],""" +
                        """"stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":5}}"""
                )
            } else {
                jsonBody(
                    """{"content":[{"type":"text","text":"blocked, giving up"}],""" +
                        """"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}"""
                )
            }
        }
        val client = AnthropicMessagesClient(engine, apiKey = "key", baseUrl = "http://fake")
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("anthropic-client-contamination")

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())

        record.cliInvocationAttempts shouldBe 1
        record.contaminated shouldBe false
    }
})
