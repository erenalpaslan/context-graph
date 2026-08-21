package io.contextgraph.benchmark.runner

import io.contextgraph.benchmark.judge.JudgeRetryConfig
import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.BenchmarkConfig
import io.contextgraph.benchmark.model.ModelConfig
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
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
    BenchmarkConfig(toolCallCeiling = ceiling, repeatsPerArm = 4, models = ModelConfig(agentModel = "gpt-4.1-nano"))

private fun MockRequestHandleScope.jsonBody(text: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
    content = ByteReadChannel(text),
    status = status,
    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
)

/**
 * Task 20's OpenAI [AgentClient], proven against [MockEngine] (never a real network call) rather
 * than by reading the wire format and hoping. Two things this suite exists to nail down beyond
 * what [AnthropicMessagesClientTest] already covers for the shared retry/anomaly logic
 * ([postWithProviderRetry], [requireMeasuredOutcome]): OpenAI's own function-calling tool-call
 * shape round-trips correctly, and `costUsd` comes back `null` -- not `0.0` -- for `gpt-4.1-nano`,
 * which has no [ModelPricing] entry (task 20's "kaydedilmedi, sıfır değil" red line).
 */
class OpenAiChatCompletionsClientTest : FunSpec({

    test("a normal successful single-turn run returns real tokens and answer text, and costUsd is null (no ModelPricing entry for gpt-4.1-nano)") {
        val engine = MockEngine {
            jsonBody(
                """{"choices":[{"message":{"role":"assistant","content":"the auth module validates tokens"},""" +
                    """"finish_reason":"stop"}],"usage":{"prompt_tokens":120,"completion_tokens":40}}"""
            )
        }
        val client = OpenAiChatCompletionsClient(engine, apiKey = "fake-key", baseUrl = "http://fake")
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("openai-client-success")

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())

        record.inputTokens shouldBe 120L
        record.outputTokens shouldBe 40L
        record.finalAnswer shouldBe "the auth module validates tokens"
        record.toolCallCount shouldBe 0
        record.costUsd.shouldBeNull() // not 0.0 -- gpt-4.1-nano has no pricing entry, cost is "not recorded"
    }

    test("a 401 fails the run outright, is never retried, and the error text names the provider's error.type and message") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            jsonBody(
                """{"error":{"type":"invalid_request_error","code":"invalid_api_key","message":"Incorrect API key provided"}}""",
                status = HttpStatusCode.Unauthorized
            )
        }
        val client = OpenAiChatCompletionsClient(
            engine, apiKey = "invalid-key", baseUrl = "http://fake",
            retryConfig = JudgeRetryConfig(maxAttempts = 5, initialDelayMillis = 1), wait = {}
        )
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("openai-client-401")

        val exception = shouldThrow<AgentCallFailedException> {
            runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())
        }

        callCount shouldBe 1
        exception.message shouldContain "invalid_request_error"
        exception.message shouldContain "Incorrect API key provided"
    }

    test("429 is retried with exponential backoff and then succeeds") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount < 3) {
                jsonBody("""{"error":{"type":"rate_limit_error","message":"slow down"}}""", HttpStatusCode.TooManyRequests)
            } else {
                jsonBody("""{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""")
            }
        }
        val delays = mutableListOf<Long>()
        val client = OpenAiChatCompletionsClient(
            engine, apiKey = "key", baseUrl = "http://fake",
            retryConfig = JudgeRetryConfig(maxAttempts = 5, initialDelayMillis = 100, backoffMultiplier = 2.0),
            wait = { delays += it }
        )
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("openai-client-429")

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())

        callCount shouldBe 3
        delays shouldBe listOf(100L, 200L)
        record.finalAnswer shouldBe "ok"
    }

    test("retries are exhausted after maxAttempts and the run fails loudly") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            jsonBody("""{"error":{"type":"rate_limit_error","message":"slow down"}}""", HttpStatusCode.TooManyRequests)
        }
        val client = OpenAiChatCompletionsClient(
            engine, apiKey = "key", baseUrl = "http://fake",
            retryConfig = JudgeRetryConfig(maxAttempts = 3, initialDelayMillis = 1), wait = {}
        )
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("openai-client-exhausted")

        shouldThrow<AgentCallFailedException> {
            runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())
        }
        callCount shouldBe 3
    }

    test("a 2xx response with no measurable content is not silently accepted as a zero-token success") {
        val engine = MockEngine { jsonBody("""{"choices":[{"message":{"role":"assistant","content":null},"finish_reason":"stop"}]}""") }
        val client = OpenAiChatCompletionsClient(engine, apiKey = "key", baseUrl = "http://fake")
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("openai-client-empty-200")

        val exception = shouldThrow<AgentCallFailedException> {
            runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())
        }
        exception.message shouldContain "no final answer text at all"
    }

    test("a run that burns tokens but still produces no answer is a failure, not a zero-scoring answer") {
        // The original guard demanded zero tokens *and* no answer, which fit the 401-parsed-as-an-
        // empty-turn case it was written for. A real run then found the gap: 444k input tokens
        // spent, empty answer returned, counted as a success and handed to the judge -- which
        // scored the empty string 0.5. Cost is not evidence that anything was measured.
        val engine = MockEngine {
            jsonBody(
                """{"choices":[{"message":{"role":"assistant","content":null},"finish_reason":"stop"}],""" +
                    """"usage":{"prompt_tokens":444000,"completion_tokens":1300}}"""
            )
        }
        val client = OpenAiChatCompletionsClient(engine, apiKey = "key", baseUrl = "http://fake")
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("openai-client-tokens-no-answer")

        val exception = shouldThrow<AgentCallFailedException> {
            runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())
        }
        exception.message shouldContain "no final answer text at all"
        exception.message shouldContain "444000"
    }

    // ------------------------------------------------------- tool-use loop preserved

    test("the bash tool round-trips through OpenAI's function-calling shape and the real contamination-guarded executor") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) {
                jsonBody(
                    """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[""" +
                        """{"id":"call1","type":"function","function":{"name":"bash","arguments":"{\"command\":\"echo hello-from-bash\"}"}}""" +
                        """]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":50,"completion_tokens":10}}"""
                )
            } else {
                jsonBody(
                    """{"choices":[{"message":{"role":"assistant","content":"the command printed hello-from-bash"},""" +
                        """"finish_reason":"stop"}],"usage":{"prompt_tokens":30,"completion_tokens":15}}"""
                )
            }
        }
        val client = OpenAiChatCompletionsClient(engine, apiKey = "key", baseUrl = "http://fake")
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("openai-client-bash-loop")

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())

        callCount shouldBe 2
        record.toolCallCount shouldBe 1
        record.finalAnswer shouldBe "the command printed hello-from-bash"
        record.inputTokens shouldBe 80L
        record.outputTokens shouldBe 25L
        record.contaminated shouldBe false
    }

    test("AC-10a: the tool-call ceiling still cuts a run off after the configured number of calls, and the run answers from what it gathered rather than falling silent") {
        // A model that would call tools forever. The ceiling has to stop it -- but stopping the
        // *tools* is not the same as stopping the *run*: the run has to come back with an answer,
        // or the arm that hits the ceiling most often scores zero for reasons that have nothing
        // to do with how well it explored. The final turn is identified by tool_choice=none,
        // which the client only sends once the budget is spent.
        var forcedFinalTurns = 0
        val engine = MockEngine { request ->
            val body = String(request.body.toByteArray())
            if (body.contains("\"tool_choice\":\"none\"")) {
                forcedFinalTurns++
                jsonBody(
                    """{"choices":[{"message":{"role":"assistant","content":"partial answer from what I gathered"},""" +
                        """"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
                )
            } else {
                jsonBody(
                    """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[""" +
                        """{"id":"callN","type":"function","function":{"name":"bash","arguments":"{\"command\":\"echo again\"}"}}""" +
                        """]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
                )
            }
        }
        val client = OpenAiChatCompletionsClient(engine, apiKey = "key", baseUrl = "http://fake")
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("openai-client-ceiling")

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig(ceiling = 2))

        record.toolCallCount shouldBe 2
        record.hitCeiling shouldBe true
        // The budget is spent exactly once, so exactly one final turn is forced -- not a loop of
        // them, which is the way this fix could silently become unbounded.
        forcedFinalTurns shouldBe 1
        record.finalAnswer shouldContain "partial answer"
    }

    test("AC-8/AC-9: an attempted contextgraph CLI invocation is blocked, counted, and never marks the run contaminated") {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) {
                jsonBody(
                    """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[""" +
                        """{"id":"call1","type":"function","function":{"name":"bash","arguments":"{\"command\":\"contextgraph search foo\"}"}}""" +
                        """]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
                )
            } else {
                jsonBody(
                    """{"choices":[{"message":{"role":"assistant","content":"blocked, giving up"},""" +
                        """"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
                )
            }
        }
        val client = OpenAiChatCompletionsClient(engine, apiKey = "key", baseUrl = "http://fake")
        val runner = AgentRunner(client)
        val workingDir = Files.createTempDirectory("openai-client-contamination")

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, workingDir, repeatIndex = 0, config = benchmarkConfig())

        record.cliInvocationAttempts shouldBe 1
        record.contaminated shouldBe false
    }
})
