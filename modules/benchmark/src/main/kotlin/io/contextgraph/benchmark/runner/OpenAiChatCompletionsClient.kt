package io.contextgraph.benchmark.runner

import io.contextgraph.benchmark.config.Secrets
import io.contextgraph.benchmark.judge.JudgeRetryConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val BASH_TOOL_DESCRIPTION =
    "Execute a shell command in the working directory and return its combined stdout/stderr."

/**
 * Task 20's OpenAI [AgentClient]: a manual tool-use loop against OpenAI's `/v1/chat/completions`
 * endpoint, talking OpenAI's own wire format directly rather than through
 * [AnthropicMessagesClient]'s Anthropic Messages shape. Anthropic's account key came back invalid
 * (401, confirmed directly); the user switched to a verified OpenAI key (`/v1/models` returns 200,
 * 126 models visible) and moved both the agent and the judge to `gpt-4.1-nano`.
 *
 * **Why a dedicated client instead of routing through the LiteLLM proxy already running for the
 * judge (task 17):** the proxy's only *empirically verified* surface, per
 * [io.contextgraph.benchmark.proxy.LiteLlmPin]'s own doc, is `/v1/chat/completions` -- exactly
 * this endpoint, not litellm's separate (and, on this pinned `1.83.9` release, unverified in this
 * codebase) Anthropic-messages-passthrough surface that [AnthropicMessagesClient]'s wire format
 * would need. Reusing that shape would also carry Anthropic's `bash_20250124` *server* tool type,
 * which is Anthropic-specific and has no defined meaning once litellm forwards it to an OpenAI
 * backend. Going straight to OpenAI's native function-calling format for both the bash tool and
 * the ContextGraph MCP tool sidesteps both risks at the cost of a second small client -- judged
 * worth it because task 20's second red line is that the tool-use loop (MCP bridge, 40-call
 * ceiling, ceiling flag, contamination guard) must survive the provider switch intact, and a
 * translation layer this module has never run against OpenAI is exactly where that could quietly
 * break. This class talks to `https://api.openai.com` directly, bypassing the LiteLLM proxy
 * entirely for the agent side -- same as [AnthropicMessagesClient] always has (the proxy has only
 * ever fronted the judge).
 *
 * The tool-execution and retry/failure semantics are intentionally identical to
 * [AnthropicMessagesClient]'s, factored into [executeTool] and [postWithProviderRetry] /
 * [requireMeasuredOutcome] precisely so this class doesn't re-derive task 19's guarantees: a
 * failed HTTP call never becomes a fabricated successful run here either.
 */
class OpenAiChatCompletionsClient(
    private val engine: HttpClientEngine = CIO.create(),
    private val apiKey: String? = Secrets.resolve(Secrets.OPENAI_API_KEY),
    private val baseUrl: String = "https://api.openai.com",
    private val retryConfig: JudgeRetryConfig = JudgeRetryConfig(),
    private val wait: suspend (Long) -> Unit = { millis -> delay(millis) }
) : AgentClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun run(context: AgentRunContext): AgentClientOutcome = runBlocking {
        val key = requireNotNull(apiKey) {
            Secrets.missingMessage(Secrets.OPENAI_API_KEY) +
                " OpenAiChatCompletionsClient cannot run a real agent turn without it."
        }

        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) { requestTimeoutMillis = 300_000 }
        }
        try {
            runLoop(client, key, context)
        } finally {
            client.close()
        }
    }

    private suspend fun runLoop(client: HttpClient, apiKey: String, context: AgentRunContext): AgentClientOutcome {
        val toolsJson = buildToolsArray(context.extraTools)
        val messages = mutableListOf<JsonElement>(
            buildJsonObject { put("role", "system"); put("content", context.systemPrompt) },
            buildJsonObject { put("role", "user"); put("content", context.question.text) }
        )

        var toolCallCount = 0
        var fileReadCount = 0
        var totalInputTokens = 0L
        var totalOutputTokens = 0L
        var finalAnswer = ""

        // One request/response turn: posts, folds usage into the running totals, and hands back
        // the choice. Extracted so the forced final turn below reuses this exact call rather than
        // growing a second, subtly divergent copy of it.
        suspend fun postTurn(toolChoice: String?): JsonObject {
            val requestBody = buildJsonObject {
                put("model", context.model)
                put("messages", JsonArray(messages.toList()))
                put("tools", toolsJson)
                if (toolChoice != null) put("tool_choice", toolChoice)
            }

            val bodyText = postWithProviderRetry(retryConfig, wait, "OpenAI chat completions") {
                client.post("$baseUrl/v1/chat/completions") {
                    header("Authorization", "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody.toString())
                }
            }
            val responseJson = json.parseToJsonElement(bodyText).jsonObject

            val usage = responseJson["usage"]?.jsonObject
            totalInputTokens += usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0L
            totalOutputTokens += usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0L

            return responseJson["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw AgentCallFailedException(
                    "OpenAI chat completions response carried no choices -- not a normal " +
                        "successful turn: $bodyText"
                )
        }

        var ceilingReached = false

        while (true) {
            val choice = postTurn(toolChoice = null)
            val assistantMessage = choice["message"]?.jsonObject
                ?: throw AgentCallFailedException(
                    "OpenAI chat completions response carried no message -- not a normal " +
                        "successful turn: $choice"
                )

            finalAnswer = assistantMessage["content"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { finalAnswer }
            messages.add(assistantMessage)

            val toolCalls = assistantMessage["tool_calls"]?.jsonArray.orEmpty()
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            if (finishReason != "tool_calls" || toolCalls.isEmpty()) break

            // Every tool_call in this turn gets a matching "tool" message, including any the
            // ceiling cuts off. Previously those were dropped, which was only safe because no
            // further request was ever sent; the forced final turn below *is* such a request, and
            // OpenAI rejects one whose assistant turn has an unanswered tool_call.
            for (toolCall in toolCalls) {
                val obj = toolCall.jsonObject
                val callId = obj["id"]?.jsonPrimitive?.content.orEmpty()
                val resultText = if (toolCallCount >= context.toolCallCeiling) {
                    ceilingReached = true
                    TOOL_BUDGET_EXHAUSTED
                } else {
                    toolCallCount++
                    val function = obj["function"]?.jsonObject
                    val toolName = function?.get("name")?.jsonPrimitive?.content.orEmpty()
                    val argumentsText = function?.get("arguments")?.jsonPrimitive?.content.orEmpty()
                    val input = runCatching { json.parseToJsonElement(argumentsText).jsonObject }
                        .getOrDefault(JsonObject(emptyMap()))
                    executeTool(context, toolName, input) { fileReadCount++ }
                }
                messages.add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", callId)
                    put("content", resultText)
                })
            }

            if (ceilingReached || toolCallCount >= context.toolCallCeiling) {
                ceilingReached = true
                break
            }
        }

        // A run that spent its whole tool budget used to end right here with finalAnswer still
        // empty: the turn it was cut off in was a tool-calling turn, and those carry no assistant
        // text. That recorded a *non-measurement* wearing a score. The first gpt-4.1-mini smoke
        // showed what it costs -- all three WITHOUT_TOOLS runs burned 40 calls and returned "",
        // and the judge still handed one of them 0.5 -- so the arm the whole benchmark exists to
        // compare against was never actually answering, and its accuracy was an artifact of the
        // ceiling rather than a measurement of the arm.
        //
        // The ceiling now ends the tool phase, not the run: one final turn with tool_choice=none,
        // which is what a budget was always supposed to mean. An agent that ran out of budget
        // answers from what it managed to gather; it does not fall silent.
        if (ceilingReached) {
            messages.add(buildJsonObject {
                put("role", "user")
                put("content", FINAL_ANSWER_PROMPT)
            })
            val choice = postTurn(toolChoice = "none")
            val content = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            finalAnswer = content.orEmpty().ifBlank { finalAnswer }
        }

        requireMeasuredOutcome("OpenAI chat completions", totalInputTokens, totalOutputTokens, finalAnswer)

        return AgentClientOutcome(
            inputTokens = totalInputTokens,
            outputTokens = totalOutputTokens,
            toolCallCount = toolCallCount,
            fileReadCount = fileReadCount,
            // Task 20: gpt-4.1-nano has no ModelPricing entry, so this is null ("not recorded"),
            // never a computed 0.0 -- see AgentClientOutcome's doc on why that distinction matters.
            costUsd = ModelPricing.costUsdOrNull(context.model, totalInputTokens, totalOutputTokens),
            finalAnswer = finalAnswer
        )
    }

    private fun buildToolsArray(extraTools: List<ToolDefinition>) = buildJsonArray {
        addJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "bash")
                put("description", BASH_TOOL_DESCRIPTION)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("command", buildJsonObject { put("type", "string") })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("command")) })
                })
            })
        }
        for (tool in extraTools) {
            addJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", json.parseToJsonElement(tool.inputSchemaJson))
                })
            }
        }
    }
}

private inline fun JsonArrayBuilder.addJsonObject(builderAction: JsonObjectBuilder.() -> Unit) {
    add(buildJsonObject(builderAction))
}
