package io.contextgraph.benchmark.judge

import io.contextgraph.benchmark.model.FactScore
import io.contextgraph.benchmark.runner.ProcessOutput
import io.contextgraph.benchmark.runner.resolveClaudeCli
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Grades an answer with `claude --print --json-schema`, replacing the LiteLLM-fronted HTTP judge.
 *
 * What this removes is not just a dependency but a category of failure. The previous judge asked a
 * chat model to "respond with ONLY a JSON object" and then parsed whatever came back, with retries
 * for the times it didn't -- and it needed a Python virtualenv, a pinned LiteLLM release, a
 * generated proxy config and a process lifecycle to get there. `--json-schema` makes the shape a
 * constraint on generation rather than a request in a prompt, so there is nothing to retry and
 * nothing to install.
 *
 * **The judge must not share the agent's model.** Both roles now run through the same CLI, which
 * makes it easy to let them collapse onto one model and reopen the objection AC-12 exists to
 * close: an answer graded by the model that produced it. Independence here is a *model* choice,
 * not a transport one -- pass a different `judgeModel` than the agent's, and
 * [io.contextgraph.benchmark.model.ModelConfig] keeps the two fields separate precisely so that
 * remains visible in every recorded result.
 *
 * The judge is given no tools and no MCP servers. It sees the question, the gold statements and
 * the answer -- AC-11's list, which [JudgeInput] enforces structurally -- and nothing that would
 * let it go and check the repository for itself, which would make it a second agent rather than a
 * grader.
 */
class ClaudeCodeJudgeClient(
    private val claudeCliPath: String = resolveClaudeCli(),
    private val timeoutMinutes: Long = 10,
    private val processRunner: (List<String>, Long) -> ProcessOutput = ::runJudgeProcess
) : JudgeClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun scoreFacts(input: JudgeInput, judgeModel: String): List<FactScore> {
        // Same guard as LlmJudgeClient's: an empty answer expresses no fact, and spending a judge
        // call to discover that invites a grader to score nothing above zero -- which one did.
        if (input.answerText.isBlank()) return input.goldFacts.map { FactScore(it.id, hit = false) }

        val expectedIds = input.goldFacts.map { it.id }
        val output = processRunner(buildCommand(input, judgeModel), timeoutMinutes)
        val resultEvent = runCatching { json.parseToJsonElement(output.stdout.trim()).jsonObject }.getOrNull()
            ?: throw JudgeCallFailedException(
                "Claude Code judge returned output that is not JSON (exit=${output.exitCode}): " +
                    "${output.stdout.take(400)} / stderr: ${output.stderr.take(400)}"
            )

        if (resultEvent["is_error"]?.jsonPrimitive?.booleanOrNull == true) {
            throw JudgeCallFailedException(
                "Claude Code judge reported is_error: " +
                    "subtype=${resultEvent["subtype"]?.jsonPrimitive?.contentOrNull}, " +
                    "api_error_status=${resultEvent["api_error_status"]?.jsonPrimitive?.contentOrNull}"
            )
        }

        val payload = resultEvent["result"]?.jsonPrimitive?.contentOrNull
            ?: throw JudgeCallFailedException("Claude Code judge returned no result field")
        val scored = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: throw JudgeCallFailedException(
                "Claude Code judge's result was not the schema-constrained object: ${payload.take(400)}"
            )

        val byId = scored["scores"]?.jsonArray.orEmpty().associate { element ->
            val obj = element.jsonObject
            obj["factId"]?.jsonPrimitive?.contentOrNull.orEmpty() to
                (obj["hit"]?.jsonPrimitive?.booleanOrNull ?: false)
        }

        // Reconciled against the ids that were *sent*, never trusted as returned. A judge that
        // invents, drops or renames a fact id must not be able to change the denominator the
        // accuracy score is computed over.
        val missing = expectedIds.filter { it !in byId }
        if (missing.isNotEmpty()) {
            throw JudgeCallFailedException(
                "Claude Code judge did not score every gold fact: missing ${missing.joinToString()}"
            )
        }
        return expectedIds.map { FactScore(it, hit = byId.getValue(it)) }
    }

    private fun buildCommand(input: JudgeInput, judgeModel: String): List<String> = listOf(
        claudeCliPath,
        "--print", buildPrompt(input),
        "--output-format", "json",
        "--model", judgeModel,
        // No tools and no inherited MCP servers: a grader that can read the repository is not
        // grading the answer any more, it is producing its own.
        "--tools", "",
        "--mcp-config", """{"mcpServers":{}}""",
        "--strict-mcp-config",
        "--json-schema", SCORES_SCHEMA
    )

    private fun buildPrompt(input: JudgeInput): String = buildString {
        appendLine(
            "You are a strict grader. For each gold key-fact below, decide whether the candidate " +
                "answer expresses that fact. The wording need not match, but the fact's substance " +
                "must be present. Do not judge whether the gold facts themselves are correct; take " +
                "them as given. Score every fact id exactly once."
        )
        appendLine()
        appendLine("QUESTION:")
        appendLine(input.questionText)
        appendLine()
        appendLine("GOLD KEY-FACTS:")
        input.goldFacts.forEach { appendLine("- ${it.id}: ${it.statement}") }
        appendLine()
        appendLine("CANDIDATE ANSWER:")
        appendLine(input.answerText)
    }

    private companion object {
        const val SCORES_SCHEMA: String =
            """{"type":"object","properties":{"scores":{"type":"array","items":{"type":"object",""" +
                """"properties":{"factId":{"type":"string"},"hit":{"type":"boolean"}},""" +
                """"required":["factId","hit"]}}},"required":["scores"]}"""
    }
}

private fun runJudgeProcess(command: List<String>, timeoutMinutes: Long): ProcessOutput {
    val process = ProcessBuilder(command).start()
    process.outputStream.close()
    val out = StringBuilder()
    val err = StringBuilder()
    val outThread = Thread { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
    val errThread = Thread { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
    outThread.start(); errThread.start()
    if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        throw JudgeCallFailedException("Claude Code judge did not finish within $timeoutMinutes minute(s)")
    }
    outThread.join(10_000); errThread.join(10_000)
    return ProcessOutput(process.exitValue(), out.toString(), err.toString())
}
