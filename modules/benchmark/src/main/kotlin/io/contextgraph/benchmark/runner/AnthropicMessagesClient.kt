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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val ANTHROPIC_VERSION = "2023-06-01"
private const val DEFAULT_MAX_TOKENS_PER_TURN = 4096

/**
 * The production [AgentClient]: a manual tool-use loop against the Anthropic Messages API,
 * following this repo's established ktor pattern (see
 * `modules/ingest/.../LiteLlmModuleDescriber.kt`) rather than adding an SDK dependency this
 * module doesn't declare. Content blocks are handled as loosely-typed JSON (not a full
 * `@Serializable` hierarchy) because the response mixes `text`/`tool_use` variants and the only
 * thing this loop needs from them is a handful of fields -- a typed sealed hierarchy would be
 * more ceremony than the two call sites that read it justify.
 *
 * Every Bash tool call is routed through [AgentRunContext.bashExecutor] and nothing else -- this
 * class has no other way to run a shell command, which is what makes the contamination guard
 * (see [GuardedBashExecutor]) apply to every command this client ever issues.
 *
 * Task 19: the HTTP status is checked on every call ([postWithProviderRetry]), not just the
 * response body -- a 401/403/429/5xx used to fall straight through into the same JSON parsing a
 * real success got, silently producing a "successful" run with zero tokens and an empty answer
 * (measured: a real smoke run against an invalid key reported "6 succeeded, 0 failed"). 429/5xx
 * are retried with backoff, 401/403 fail fast (shared with the judge client's task 18
 * classification, see [postWithProviderRetry]'s doc), and a run whose aggregated outcome is still
 * empty after every response was individually 2xx also fails loudly ([requireMeasuredOutcome])
 * rather than being reported as a genuine zero-cost, zero-token success.
 */
class AnthropicMessagesClient(
    private val engine: HttpClientEngine = CIO.create(),
    private val apiKey: String? = Secrets.resolve(Secrets.ANTHROPIC_API_KEY),
    private val baseUrl: String = "https://api.anthropic.com",
    private val retryConfig: JudgeRetryConfig = JudgeRetryConfig(),
    private val wait: suspend (Long) -> Unit = { millis -> delay(millis) }
) : AgentClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun run(context: AgentRunContext): AgentClientOutcome = runBlocking {
        val key = requireNotNull(apiKey) {
            Secrets.missingMessage(Secrets.ANTHROPIC_API_KEY) +
                " AnthropicMessagesClient cannot run a real agent turn without it."
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
            buildJsonObject { put("role", "user"); put("content", context.question.text) }
        )

        var toolCallCount = 0
        var fileReadCount = 0
        var totalInputTokens = 0L
        var totalOutputTokens = 0L
        var finalAnswer = ""

        // One request/response turn, folding usage into the running totals. Extracted so the
        // forced final turn below reuses this call instead of growing a divergent copy.
        suspend fun postTurn(toolChoiceNone: Boolean): JsonObject {
            val requestBody = buildJsonObject {
                put("model", context.model)
                put("max_tokens", DEFAULT_MAX_TOKENS_PER_TURN)
                put("system", context.systemPrompt)
                put("tools", toolsJson)
                if (toolChoiceNone) put("tool_choice", buildJsonObject { put("type", "none") })
                put("messages", JsonArray(messages.toList()))
            }

            val bodyText = postWithProviderRetry(retryConfig, wait, "Anthropic Messages API") {
                client.post("$baseUrl/v1/messages") {
                    header("x-api-key", apiKey)
                    header("anthropic-version", ANTHROPIC_VERSION)
                    contentType(ContentType.Application.Json)
                    setBody(requestBody.toString())
                }
            }
            val responseJson = json.parseToJsonElement(bodyText).jsonObject

            val usage = responseJson["usage"]?.jsonObject
            totalInputTokens += usage?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0L
            totalOutputTokens += usage?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: 0L
            return responseJson
        }

        fun textOf(responseJson: JsonObject): String =
            responseJson["content"]?.jsonArray.orEmpty()
                .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                .joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }

        var ceilingReached = false

        while (true) {
            val responseJson = postTurn(toolChoiceNone = false)
            val contentBlocks = responseJson["content"]?.jsonArray.orEmpty()
            finalAnswer = textOf(responseJson).ifBlank { finalAnswer }

            messages.add(buildJsonObject {
                put("role", "assistant")
                put("content", responseJson["content"] ?: JsonArray(emptyList()))
            })

            val stopReason = responseJson["stop_reason"]?.jsonPrimitive?.content
            if (stopReason != "tool_use") break

            val toolUseBlocks = contentBlocks.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" }
            val toolResults = mutableListOf<JsonElement>()
            // Every tool_use block gets a matching tool_result, including the ones the ceiling
            // cuts off: the forced final turn below is a further request, and the API rejects one
            // whose assistant turn left a tool_use unanswered.
            for (block in toolUseBlocks) {
                val obj = block.jsonObject
                val toolUseId = obj["id"]?.jsonPrimitive?.content.orEmpty()
                val resultText = if (toolCallCount >= context.toolCallCeiling) {
                    ceilingReached = true
                    TOOL_BUDGET_EXHAUSTED
                } else {
                    toolCallCount++
                    val toolName = obj["name"]?.jsonPrimitive?.content.orEmpty()
                    val input = obj["input"]?.jsonObject ?: JsonObject(emptyMap())
                    executeTool(context, toolName, input) { fileReadCount++ }
                }
                toolResults.add(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", toolUseId)
                    put("content", resultText)
                })
            }

            messages.add(buildJsonObject { put("role", "user"); put("content", JsonArray(toolResults)) })

            if (ceilingReached || toolCallCount >= context.toolCallCeiling) {
                ceilingReached = true
                break
            }
        }

        // See OpenAiChatCompletionsClient for the incident this mirrors: a run that spent its tool
        // budget used to end inside a tool-calling turn, which carries no assistant text, and was
        // recorded with an empty answer -- a non-measurement wearing a score, biased against
        // whichever arm hits the ceiling more often. The ceiling ends the tool phase, not the run.
        if (ceilingReached) {
            messages.add(buildJsonObject { put("role", "user"); put("content", FINAL_ANSWER_PROMPT) })
            finalAnswer = textOf(postTurn(toolChoiceNone = true)).ifBlank { finalAnswer }
        }

        requireMeasuredOutcome("Anthropic Messages API", totalInputTokens, totalOutputTokens, finalAnswer)

        return AgentClientOutcome(
            inputTokens = totalInputTokens,
            outputTokens = totalOutputTokens,
            toolCallCount = toolCallCount,
            fileReadCount = fileReadCount,
            costUsd = ModelPricing.costUsd(context.model, totalInputTokens, totalOutputTokens),
            finalAnswer = finalAnswer
        )
    }

    private fun buildToolsArray(extraTools: List<ToolDefinition>) = buildJsonArray {
        addJsonObject {
            put("type", "bash_20250124")
            put("name", "bash")
        }
        for (tool in extraTools) {
            addJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", json.parseToJsonElement(tool.inputSchemaJson))
            }
        }
    }
}

private inline fun JsonArrayBuilder.addJsonObject(builderAction: JsonObjectBuilder.() -> Unit) {
    add(buildJsonObject(builderAction))
}
