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
 * The fixed embedding model choice for module summaries and search queries (interview Q5,
 * `enterprise-code-understanding` spec): `text-embedding-3-small`, 1536 dimensions. Unlike the
 * chat model used for description authoring ([LiteLlmConfig.model], a per-repo choice), the
 * embedding model is not configurable: every vector in the graph must share one model and one
 * dimension for cosine similarity to mean anything, so this is a fixed constant rather than a
 * second config surface that could drift from what's actually stored.
 */
object ModuleEmbeddingModel {
    const val NAME = "text-embedding-3-small"
    const val DIMENSION = 1536
}

/**
 * Embeds one string of text -- a module description when [ModuleEmbeddingService] runs, or a
 * search query when [ModuleSemanticSearch] runs -- into a [ModuleEmbeddingModel.DIMENSION]-length
 * vector. Returns `null`, never throws, on any failure: disabled config, an unreachable endpoint,
 * a response this client can't parse, or a vector of the wrong length. "No vector" is a normal
 * degraded state callers fall back from, not a build failure -- the same contract [ModuleDescriber]
 * holds for description authoring.
 */
interface ModuleEmbedder {
    suspend fun embed(text: String, config: LiteLlmConfig): FloatArray?
}

/**
 * A LiteLLM `/v1/embeddings` client. Deliberately separate from [LiteLlmModuleDescriber]'s
 * `/v1/chat/completions` client: different endpoint, different request/response shape, and a
 * fixed model ([ModuleEmbeddingModel]) rather than [LiteLlmConfig.model] -- sharing a client would
 * mean bending one of the two contracts to fit the other.
 */
class LiteLlmModuleEmbedder(
    private val engine: HttpClientEngine = CIO.create()
) : ModuleEmbedder {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun embed(text: String, config: LiteLlmConfig): FloatArray? {
        if (!config.enabled) return null

        return try {
            val client = HttpClient(engine) {
                install(ContentNegotiation) { json(json) }
                install(HttpTimeout) { requestTimeoutMillis = config.timeoutSeconds * 1000L }
            }
            try {
                val requestBody = EmbeddingRequest(model = ModuleEmbeddingModel.NAME, input = text)
                val response = client.post("${config.baseUrl}/v1/embeddings") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
                val responseText = response.body<String>()
                val responseJson = json.parseToJsonElement(responseText).jsonObject
                val vector = responseJson["data"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("embedding")?.jsonArray
                    ?.map { it.jsonPrimitive.content.toFloat() }
                    ?.toFloatArray()
                when {
                    vector == null -> null
                    vector.size != ModuleEmbeddingModel.DIMENSION -> {
                        logger.warn {
                            "Embedding response had ${vector.size} dimensions, expected " +
                                "${ModuleEmbeddingModel.DIMENSION}; discarding"
                        }
                        null
                    }
                    else -> vector
                }
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Embedding LLM call failed; leaving text unembedded" }
            null
        }
    }
}

@Serializable
private data class EmbeddingRequest(val model: String, val input: String)
