package io.contextgraph.ingest.describe

import io.contextgraph.core.LiteLlmConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * Authors one module description from a prompt already built entirely from
 * [SymbolInventory] text or child-module descriptions -- this interface never sees a
 * file path or a body, only the string it's handed. Returns `null`, never throws, on any
 * failure: disabled config, an unreachable endpoint, or a response this client can't parse.
 * "No description" is the normal degraded state (`undescribed`), not a build failure.
 */
interface ModuleDescriber {
    suspend fun describe(prompt: String, config: LiteLlmConfig): String?
}

/**
 * A LiteLLM chat-completions client dedicated to module descriptions.
 *
 * Deliberately **not** a reuse of `SemanticExtractor`'s client (modules/extractors): that one
 * runs only on Document/Markdown/PDF artifacts, truncates input at 8000 characters (module
 * inventories can legitimately exceed that for a large module), constructs a new `HttpClient`
 * per call, and returns structured concept/claim/relationship JSON -- none of which fits a
 * plain-text module summary. Sharing it would mean bending its response contract to a second,
 * unrelated shape. Both clients independently honour `litellm.enabled`/`baseUrl`/`model` from
 * the same [LiteLlmConfig] rather than inventing a second config surface.
 */
class LiteLlmModuleDescriber(
    private val engine: HttpClientEngine = CIO.create()
) : ModuleDescriber {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun describe(prompt: String, config: LiteLlmConfig): String? {
        if (!config.enabled) return null

        return try {
            val client = HttpClient(engine) {
                install(ContentNegotiation) { json(json) }
                install(HttpTimeout) { requestTimeoutMillis = config.timeoutSeconds * 1000L }
            }
            try {
                // A plain Map<String, Any> here (as SemanticExtractor uses) trips ktor's
                // reflection-based serializer guesser on heterogeneous nested values
                // ("Serializing collections of different element types is not yet supported"),
                // caught silently by the outer try/catch below -- confirmed against MockEngine
                // while building this client. Typed @Serializable request classes sidestep
                // guessSerializer entirely: setBody(T) picks the serializer at compile time.
                val requestBody = ChatCompletionRequest(
                    model = config.model,
                    messages = listOf(
                        ChatMessage(role = "system", content = SYSTEM_PROMPT),
                        ChatMessage(role = "user", content = prompt)
                    )
                )
                val response = client.post("${config.baseUrl}/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
                val responseText = response.body<String>()
                val responseJson = json.parseToJsonElement(responseText).jsonObject
                responseJson["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Module description LLM call failed; leaving module undescribed" }
            null
        }
    }

    companion object {
        private const val SYSTEM_PROMPT = """You write short, factual descriptions of a code module for an engineer unfamiliar with the codebase.
You are given only a symbol inventory -- file names, symbol names, kinds, fully-qualified names, and doc comments where available -- or the descriptions of the module's own child modules. You are never given implementation source.
Write 2-4 plain sentences describing the module's apparent responsibility based only on what is given. Do not invent behaviour the inventory does not support."""
    }
}

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2
)
