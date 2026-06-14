package io.contextgraph.extractors

import io.contextgraph.core.Artifact
import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractionResult
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.core.ResourceExtractor
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.readText

private val logger = KotlinLogging.logger {}

class SemanticExtractor : ResourceExtractor {
    override val id = "semantic"
    override val supportedTypes = setOf(
        NodeType.Document,
        NodeType.MarkdownFile,
        NodeType.PDF,
        NodeType.ResearchPaper
    )

    private val semaphore = Semaphore(3)
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun extract(artifact: Artifact, context: ExtractionContext): ExtractionResult {
        if (!context.config.litellm.enabled) {
            return ExtractionResult(artifact, emptyList(), emptyList())
        }

        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val now = Clock.System.now()

        semaphore.withPermit {
            try {
                val content = Path.of(artifact.path).readText().take(8000)
                val client = HttpClient(CIO) {
                    install(ContentNegotiation) { json(lenientJson) }
                }

                val requestBody = mapOf(
                    "model" to context.config.litellm.model,
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to SYSTEM_PROMPT),
                        mapOf("role" to "user", "content" to "Extract knowledge graph entities from this text:\n\n$content")
                    ),
                    "temperature" to 0.2,
                    "response_format" to mapOf("type" to "json_object")
                )

                val response = client.post("${context.config.litellm.baseUrl}/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
                client.close()

                val responseText = response.body<String>()
                val responseJson = lenientJson.parseToJsonElement(responseText).jsonObject
                val content2 = responseJson["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                    ?: return@withPermit

                val extracted = lenientJson.parseToJsonElement(content2).jsonObject

                extracted["concepts"]?.jsonArray?.forEach { concept ->
                    val obj = concept.jsonObject
                    val label = obj["label"]?.jsonPrimitive?.content ?: return@forEach
                    val confidence = obj["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull()
                        ?.coerceIn(ConfidenceDefaults.LLM_CONCEPT_MIN, ConfidenceDefaults.LLM_CONCEPT_MAX)
                        ?: ConfidenceDefaults.LLM_CONCEPT_MIN
                    val evidence = obj["evidence"]?.jsonPrimitive?.content
                    nodes.add(GraphNode(
                        id = NodeId("concept:llm:${label.lowercase().replace(" ", "_")}"),
                        type = NodeType.Concept,
                        label = label,
                        confidence = confidence,
                        provenance = listOf(Provenance(artifact.id, artifact.path, textSpan = evidence, extractor = id, extractedAt = now))
                    ))
                }

                extracted["claims"]?.jsonArray?.forEach { claim ->
                    val obj = claim.jsonObject
                    val label = obj["label"]?.jsonPrimitive?.content ?: return@forEach
                    val confidence = obj["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull()
                        ?.coerceIn(ConfidenceDefaults.LLM_CONCEPT_MIN, ConfidenceDefaults.LLM_CONCEPT_MAX)
                        ?: ConfidenceDefaults.LLM_CONCEPT_MIN
                    val evidence = obj["evidence"]?.jsonPrimitive?.content
                    nodes.add(GraphNode(
                        id = NodeId("claim:llm:${label.take(40).lowercase().replace(" ", "_")}"),
                        type = NodeType.Claim,
                        label = label,
                        confidence = confidence,
                        provenance = listOf(Provenance(artifact.id, artifact.path, textSpan = evidence, extractor = id, extractedAt = now))
                    ))
                }

                extracted["relationships"]?.jsonArray?.forEach { rel ->
                    val obj = rel.jsonObject
                    val source = obj["source"]?.jsonPrimitive?.content ?: return@forEach
                    val target = obj["target"]?.jsonPrimitive?.content ?: return@forEach
                    val type = obj["type"]?.jsonPrimitive?.content ?: "references"
                    val confidence = obj["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull()
                        ?.coerceIn(ConfidenceDefaults.LLM_RELATION_MIN, ConfidenceDefaults.LLM_RELATION_MAX)
                        ?: ConfidenceDefaults.LLM_RELATION_MIN

                    val srcId = NodeId("concept:llm:${source.lowercase().replace(" ", "_")}")
                    val tgtId = NodeId("concept:llm:${target.lowercase().replace(" ", "_")}")
                    edges.add(GraphEdge(
                        id = EdgeId("llm:${srcId.value}:$type:${tgtId.value}"),
                        source = srcId,
                        target = tgtId,
                        type = EdgeType.fromString(type),
                        confidence = confidence,
                        provenance = listOf(Provenance(artifact.id, artifact.path, extractor = id, extractedAt = now))
                    ))
                }
            } catch (e: Exception) {
                logger.warn(e) { "Semantic extraction failed for ${artifact.path}" }
            }
        }

        return ExtractionResult(artifact, nodes, edges)
    }

    companion object {
        private const val SYSTEM_PROMPT = """You are a knowledge graph extraction assistant.
Extract concepts, claims, and relationships from the provided text.
Return a JSON object with this exact structure:
{
  "concepts": [{"label": "...", "description": "...", "confidence": 0.0, "evidence": "..."}],
  "claims": [{"label": "...", "confidence": 0.0, "evidence": "..."}],
  "relationships": [{"source": "...", "target": "...", "type": "supports|contradicts|references|explains|uses|cites", "confidence": 0.0, "evidence": "..."}]
}
Rules:
- Only extract entities clearly present in the text
- Use conservative confidence scores (0.65-0.85 for concepts, 0.55-0.80 for relationships)
- Evidence should be a short quote from the text supporting the extraction
- Limit to the 10 most important concepts and 5 most important claims"""
    }
}
