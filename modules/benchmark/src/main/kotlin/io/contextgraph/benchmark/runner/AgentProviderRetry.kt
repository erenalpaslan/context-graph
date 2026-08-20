package io.contextgraph.benchmark.runner

import io.contextgraph.benchmark.judge.JudgeRetryConfig
import io.contextgraph.benchmark.judge.isRetryableJudgeFailure
import io.contextgraph.benchmark.judge.judgeErrorMessage
import io.contextgraph.benchmark.judge.retryAfterMillisOf
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Thrown when an agent-side model-provider HTTP call ([AnthropicMessagesClient],
 * [OpenAiChatCompletionsClient]) ultimately fails to produce a usable turn -- either a permanent
 * error (401/403, or any other non-retryable status) on the first attempt, or a transient one
 * (429/5xx) that stayed unhealthy through every retry [JudgeRetryConfig] allowed, or a 2xx
 * response so empty (zero tokens, no answer text) that treating it as a successful run would be
 * the same silent-corruption bug task 19 exists to close (see [requireMeasuredOutcome]).
 *
 * Task 19's whole point is that this exception must actually escape [AgentClient.run] rather than
 * being swallowed into a fabricated zero-token "success" -- from there,
 * [io.contextgraph.benchmark.orchestrator.BenchmarkOrchestrator]'s existing per-run `catch
 * (e: Exception)` already routes it into `failedRunCount` and out of every statistic, with no
 * change needed on that side. See `AnthropicMessagesClientTest` / `OpenAiChatCompletionsClientTest`
 * for the invalid-key smoke-scenario proof this was written against.
 */
class AgentCallFailedException(message: String) : Exception(message)

/**
 * Retries one provider HTTP call ([call], invoked fresh on every attempt -- ktor request builders
 * are not reusable across attempts) against the same 429/5xx-transient, 401/403-permanent policy
 * task 18 already established for the judge client ([isRetryableJudgeFailure]). Reused here rather
 * than re-derived: both call sites are "call an LLM provider's chat endpoint over HTTP", the error
 * body shape (`{"error":{"type":...,"message":...}}`) is the same across Anthropic, OpenAI, and
 * the LiteLLM proxy fronting either, and nothing about the classification is judge-specific. This
 * function does not itself know about the judge -- reusing [isRetryableJudgeFailure] here changes
 * nothing about how the judge client behaves, since that function is pure and takes no judge state.
 *
 * Returns the response body text of the first successful (2xx, no recognizable provider error
 * shape) response. Throws [AgentCallFailedException] naming the provider's own `error.type`/
 * `message` -- task 19's requirement that the error text be diagnostic ("istek başarısız demek
 * teşhis için yetersiz"), not just a bare status code -- once a permanent failure is seen or
 * [JudgeRetryConfig.maxAttempts] is exhausted.
 */
internal suspend fun postWithProviderRetry(
    retryConfig: JudgeRetryConfig,
    wait: suspend (Long) -> Unit,
    providerName: String,
    call: suspend () -> HttpResponse
): String {
    var backoffMillis = retryConfig.initialDelayMillis
    var attempt = 0
    while (true) {
        attempt++
        val response = call()
        val bodyText = response.body<String>()
        val errorMessage = judgeErrorMessage(bodyText)
        if (response.status.isSuccess() && errorMessage == null) {
            return bodyText
        }

        val reason = providerErrorReason(bodyText, response.status)
        if (!isRetryableJudgeFailure(response.status, bodyText) || attempt >= retryConfig.maxAttempts) {
            throw AgentCallFailedException(
                "$providerName call failed permanently after $attempt attempt(s) " +
                    "(status=${response.status.value}): $reason"
            )
        }
        wait(retryAfterMillisOf(response) ?: backoffMillis)
        backoffMillis = (backoffMillis * retryConfig.backoffMultiplier).toLong().coerceAtMost(retryConfig.maxDelayMillis)
    }
}

private val agentErrorJson = Json { ignoreUnknownKeys = true }

/**
 * AC-19's "hata mesajı Anthropic'in error.type ve mesajını içeriyor" -- [judgeErrorMessage] alone
 * only ever surfaces the `error.message` half (that's all task 18's judge-side callers needed);
 * this reads the sibling `error.type` field too, since a diagnostic-quality agent failure message
 * needs both ("authentication_error: invalid x-api-key", not just "invalid x-api-key"). Falls back
 * to the raw status/body when the response carries no recognizable `error` object at all (a
 * non-JSON body, or a JSON body with no `error` key).
 */
private fun providerErrorReason(bodyText: String, status: HttpStatusCode): String {
    val errorObject = runCatching { agentErrorJson.parseToJsonElement(bodyText).jsonObject["error"]?.jsonObject }.getOrNull()
    val type = errorObject?.get("type")?.jsonPrimitive?.contentOrNull
    val message = errorObject?.get("message")?.jsonPrimitive?.contentOrNull
    val described = listOfNotNull(type, message).joinToString(": ")
    return described.ifBlank { "HTTP ${status.value}: $bodyText" }
}

/**
 * AC "200 dönen ama sıfır token / boş cevap taşıyan bir yanıt sessizce başarılı sayılmıyor": a
 * run whose every HTTP response was individually 2xx can still be worthless if the aggregated
 * outcome carries no tokens and no answer text at all -- the exact shape a swallowed error (a 401
 * body with no `usage`/`content` keys, previously parsed as if it were a normal empty turn) used
 * to produce. Checked once, against the whole run's totals, after [providerName]'s tool-use loop
 * finishes -- not per HTTP response -- because a single mid-run turn legitimately carrying zero
 * *output* tokens (a turn that only invoked a tool) is normal; a run that never accumulated
 * anything at all across every turn is not.
 *
 * A blank answer is fatal on its own, regardless of how many tokens the run burned. The original
 * check required *all three* conditions, which fit the case it was written for (a 401 parsed as an
 * empty turn: no tokens, no text) but let through a worse one found later: a run that spent its
 * entire tool budget, accumulated 444k input tokens, and still returned "". Those runs were scored
 * -- one of them got 0.5 from the judge on an empty string -- and every such score silently pulled
 * its arm's accuracy toward zero. Whatever a run cost, if it produced no answer there is nothing
 * to grade, and recording it as a failure is the only honest option.
 */
internal fun requireMeasuredOutcome(providerName: String, inputTokens: Long, outputTokens: Long, finalAnswer: String) {
    if (finalAnswer.isBlank()) {
        throw AgentCallFailedException(
            "$providerName returned only HTTP 2xx response(s) for this run, but the run produced " +
                "no final answer text at all (input=$inputTokens, output=$outputTokens tokens). " +
                "An unanswered run cannot be judged, so it is recorded as a failure rather than " +
                "scored as if the agent had answered badly."
        )
    }
}
