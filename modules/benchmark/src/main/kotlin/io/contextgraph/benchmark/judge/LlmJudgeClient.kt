package io.contextgraph.benchmark.judge

import io.contextgraph.benchmark.model.FactScore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where the real judge client sends its request. */
data class JudgeHttpConfig(
    val baseUrl: String = "http://localhost:4000",
    val timeoutSeconds: Int = 60
)

/**
 * Retry policy for transient judge-call failures (task 18): 429/5xx are retried with exponential
 * backoff, capped at [maxDelayMillis] and bounded by [maxAttempts] total tries (the first attempt
 * plus [maxAttempts] - 1 retries). A server-supplied `Retry-After` header overrides the computed
 * delay for that one wait when present (see [retryAfterMillisOf]).
 */
data class JudgeRetryConfig(
    val maxAttempts: Int = 5,
    val initialDelayMillis: Long = 500,
    val maxDelayMillis: Long = 30_000,
    val backoffMultiplier: Double = 2.0
)

/**
 * Thrown when a judge HTTP call ultimately fails -- either a permanent error (401/403, an
 * invalid model name, any non-retryable status) on the first attempt, or a transient one
 * (429/5xx) that stayed unhealthy through every retry [JudgeRetryConfig] allowed. Distinct from
 * [SerializationException] (a *successful* call whose body could not be parsed as the expected
 * shape) so callers can tell "the judge never gave us a scorable answer" apart from "the judge
 * answered but its answer was garbage".
 */
class JudgeCallFailedException(message: String) : Exception(message)

/**
 * A LiteLLM-compatible chat-completions [JudgeClient] for Opus 5 (or a
 * second, independent model for kappa validation) -- the model is always
 * the caller-supplied [scoreFacts] parameter, never hard-coded here (AC-12).
 *
 * Follows the same shape as `ModuleDescriber`'s LiteLLM client
 * (`modules/ingest`): CIO engine injectable for testing, request/response as
 * typed `@Serializable` classes so ktor picks the serializer at compile
 * time. The two steps that don't need an HTTP call at all -- building the
 * outgoing messages from a [JudgeInput], and parsing the model's JSON
 * content into [FactScore]s -- are pulled out as [buildJudgeMessages] and
 * [parseJudgeContent] so they're directly unit-testable (see
 * `LlmJudgeClientTest`) without a mock HTTP engine.
 *
 * Task 18: every call retries transient failures (429/5xx, per [JudgeRetryConfig] and
 * [isRetryableJudgeFailure]) with exponential backoff before giving up, and never retries a
 * permanent one (401/403, an invalid model name, ...). [wait] is a seam purely for
 * `LlmJudgeClientTest` to assert on computed backoff without a real test sleeping seconds at a
 * time -- production always uses the real `kotlinx.coroutines.delay`.
 */
class LlmJudgeClient(
    private val config: JudgeHttpConfig = JudgeHttpConfig(),
    private val engine: HttpClientEngine = CIO.create(),
    private val retryConfig: JudgeRetryConfig = JudgeRetryConfig(),
    private val wait: suspend (Long) -> Unit = { millis -> delay(millis) }
) : JudgeClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun scoreFacts(input: JudgeInput, judgeModel: String): List<FactScore> {
        val expectedFactIds = input.goldFacts.map { it.id }.toSet()

        // An empty answer expresses no fact, so the only correct grade is all-miss -- and it is
        // cheap enough to settle here that there is no reason to spend a judge call discovering
        // it. This is not hypothetical prudence: asked to grade an empty string, the judge
        // returned 0.5, crediting the answer with half the gold facts it could not possibly have
        // contained. AgentRunner no longer lets an unanswered run reach the judge at all, but a
        // grader that can be talked into scoring nothing above zero should not depend on an
        // upstream guard to stay honest.
        if (input.answerText.isBlank()) {
            return input.goldFacts.map { FactScore(it.id, hit = false) }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) { requestTimeoutMillis = config.timeoutSeconds * 1000L }
        }
        try {
            val requestBody = JudgeChatRequest(model = judgeModel, messages = buildJudgeMessages(input))
            var backoffMillis = retryConfig.initialDelayMillis
            var attempt = 0
            while (true) {
                attempt++
                val response = client.post("${config.baseUrl}/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
                val responseText = response.body<String>()
                val errorMessage = judgeErrorMessage(responseText)
                if (response.status.isSuccess() && errorMessage == null) {
                    val content = json.parseToJsonElement(responseText).jsonObject["choices"]
                        ?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")
                        ?.jsonPrimitive?.content
                        ?: throw SerializationException("Judge response carried no message content: $responseText")
                    return parseJudgeContent(content, expectedFactIds)
                }

                val reason = errorMessage ?: "HTTP ${response.status.value}: $responseText"
                if (!isRetryableJudgeFailure(response.status, responseText) || attempt >= retryConfig.maxAttempts) {
                    throw JudgeCallFailedException(
                        "Judge call failed permanently after $attempt attempt(s) (status=${response.status.value}): $reason"
                    )
                }
                wait(retryAfterMillisOf(response) ?: backoffMillis)
                backoffMillis = (backoffMillis * retryConfig.backoffMultiplier).toLong().coerceAtMost(retryConfig.maxDelayMillis)
            }
        } finally {
            client.close()
        }
    }
}

/**
 * True for HTTP 429/5xx, and also for a 2xx response whose JSON body carries the LiteLLM proxy's
 * throttling shape (task 18's note: `{"error":{"type":"throttling_error","code":"429"}}` arrives
 * in the body, not necessarily as the HTTP status code, so status alone is not a reliable
 * signal). Anything else -- 401, 403, 400/404 (an invalid model name), or a body with no
 * recognizable retryable signal -- is permanent: retrying it would only burn time and money on a
 * failure that will never resolve itself.
 */
internal fun isRetryableJudgeFailure(status: HttpStatusCode, bodyText: String): Boolean {
    if (status.value == 429 || status.value in 500..599) return true
    val error = judgeErrorObject(bodyText) ?: return false
    val code = error["code"]?.jsonPrimitive?.contentOrNull
    val type = error["type"]?.jsonPrimitive?.contentOrNull
    return code == "429" || type in RETRYABLE_ERROR_TYPES
}

private val RETRYABLE_ERROR_TYPES = setOf("throttling_error", "rate_limit_error")

/** The response body's `error` object, parsed defensively -- `null` for a non-JSON body or one with no `error` key (an ordinary success payload). */
private fun judgeErrorObject(bodyText: String): JsonObject? =
    runCatching { judgeContentJson.parseToJsonElement(bodyText).jsonObject["error"]?.jsonObject }.getOrNull()

/** A human-readable message for the body's `error` object, or `null` when there isn't one -- the signal [scoreFacts] uses to tell a judge-side failure apart from a normal chat-completions success payload, independent of the HTTP status Ktor observed. */
internal fun judgeErrorMessage(bodyText: String): String? {
    val error = judgeErrorObject(bodyText) ?: return null
    return error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
}

/** Honors the server's own `Retry-After` header (seconds) when present, over the locally computed backoff -- `null` when absent or unparsable, so the caller falls back to its own schedule. */
internal fun retryAfterMillisOf(response: HttpResponse): Long? =
    response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.let { it * 1000L }

/**
 * Builds the chat messages for one judge call from a [JudgeInput] alone --
 * this function's signature has no parameter an arm label or a metric could
 * travel in on (AC-11), and [LlmJudgeClientTest] proves the built text
 * itself carries none either.
 */
internal fun buildJudgeMessages(input: JudgeInput): List<ChatMessage> = listOf(
    ChatMessage(role = "system", content = SYSTEM_PROMPT_TEXT),
    ChatMessage(role = "user", content = buildUserContent(input))
)

private const val SYSTEM_PROMPT_TEXT = """You are a strict grader. You are given a question, a numbered list of gold key-facts, and a candidate answer to that question. For each gold fact, decide whether the answer expresses that fact -- the wording need not match, but the fact's substance must be present. Do not judge whether the gold facts themselves are correct; take them as given. Respond with ONLY a JSON object of the exact shape {"scores":[{"factId":"<id>","hit":true|false}, ...]}, one entry per gold fact id given, and nothing else -- no prose, no markdown fences."""

private fun buildUserContent(input: JudgeInput): String = buildString {
    appendLine("Question:")
    appendLine(input.questionText)
    appendLine()
    appendLine("Gold facts:")
    input.goldFacts.forEach { fact -> appendLine("- factId=${fact.id}: ${fact.statement}") }
    appendLine()
    appendLine("Answer:")
    appendLine(input.answerText)
}

/**
 * Parses a judge model's response content -- expected to be a JSON object
 * the model was instructed to emit, per [buildJudgeMessages]'s system
 * prompt -- into structured [FactScore]s (AC-13: structured output, not
 * free-text parsing). Any fact id in the response that wasn't one of the
 * ids asked about is dropped rather than trusted. This may return fewer
 * entries than [expectedFactIds] if the model omits one; that's expected
 * and is not this function's problem to solve -- [JudgeScorer] reconciles
 * the result against the full gold-fact set (a fact id missing here is
 * scored as a miss there), never trusting this list's size as complete.
 */
internal fun parseJudgeContent(content: String, expectedFactIds: Set<String>): List<FactScore> {
    val payload = judgeContentJson.decodeFromString(JudgeVerdictPayload.serializer(), content)
    return payload.scores
        .filter { it.factId in expectedFactIds }
        .map { FactScore(it.factId, it.hit) }
}

private val judgeContentJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class ChatMessage(val role: String, val content: String)

@Serializable
private data class JudgeChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.0
)

@Serializable
private data class FactVerdict(val factId: String, val hit: Boolean)

@Serializable
private data class JudgeVerdictPayload(val scores: List<FactVerdict>)
