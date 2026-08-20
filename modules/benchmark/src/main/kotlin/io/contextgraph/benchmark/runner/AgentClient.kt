package io.contextgraph.benchmark.runner

import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.Question
import java.nio.file.Path

/** One tool an [AgentClient] may offer the model, beyond the built-in Bash/Read/Grep set. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchemaJson: String
)

/**
 * Everything that varies -- or, per AC-7, is deliberately held constant -- between the two arms
 * of one (question, arm) run. [AgentRunner] builds this; every field an [AgentClient]
 * implementation reads comes from here, never assembled independently, so the parity AC-7
 * requires ("aynı model, aynı sistem promptu, aynı soru metni, aynı tool call tavanı") is a
 * structural property of how [AgentRunner] constructs two of these, not a convention a client
 * has to remember to honour.
 *
 * [bashExecutor] is the *only* way a client implementation may run a shell command -- see
 * [GuardedBashExecutor]'s doc comment for why that closes off the CLI-invocation-bypassing-PATH
 * gap PATH sanitization alone leaves open. [extraTools] carries the ContextGraph MCP tool(s) for
 * the WITH_TOOLS arm and is empty for WITHOUT_TOOLS -- the model is never even offered a tool it
 * shouldn't be able to call, rather than being offered it and refused.
 */
data class AgentRunContext(
    val question: Question,
    val arm: Arm,
    val repeatIndex: Int,
    val systemPrompt: String,
    val model: String,
    val toolCallCeiling: Int,
    val workingDir: Path,
    val bashExecutor: GuardedBashExecutor,
    val extraTools: List<ToolDefinition>,
    val invokeExtraTool: (name: String, inputJson: String) -> String,
    /**
     * The sanitized environment [AgentRunner] built for this run: the real `contextgraph` binary
     * removed from PATH and [CliSentinel]'s shim directory prepended, so any invocation resolving
     * the bare name is intercepted and counted rather than executed.
     *
     * In-process clients never need this -- they route every command through [bashExecutor], which
     * already carries it. A client that spawns an external agent process does, because that
     * process runs its *own* shell commands out of reach of [bashExecutor], and this environment
     * is the only thing that makes AC-8/AC-9's guarantee follow it there.
     */
    val env: Map<String, String> = emptyMap(),
    /**
     * The per-run spend limit for clients whose budget is denominated in money rather than tool
     * calls, or null for no limit. [toolCallCeiling] is the budget an in-process client enforces
     * itself by counting; an external agent runs its own loop, so the only budget it can be held
     * to is one its own CLI understands. Both arms always receive the identical value -- which is
     * what AC-7's parity requirement actually turns on, not the unit the budget is counted in.
     */
    val maxBudgetUsd: Double? = null
)

/**
 * Stands in for the result of a tool call the ceiling cut off, so the model learns *why* the tool
 * stopped answering rather than seeing a malformed conversation.
 */
internal const val TOOL_BUDGET_EXHAUSTED: String =
    "Tool-call budget exhausted. No further tool calls are available for this question."

/**
 * The turn that converts a spent tool budget into an answer.
 *
 * Without it, a run that hits the ceiling ends inside a tool-calling turn -- and those carry no
 * assistant text, so the run is recorded with an empty answer. An empty answer is not a weak
 * measurement, it is the absence of one, and it silently drags whichever arm hits the ceiling more
 * often toward zero. Asking for the best answer the evidence so far supports is what the tool-call
 * ceiling was always meant to model: a budget, not a disqualification.
 */
internal const val FINAL_ANSWER_PROMPT: String =
    "You have used your entire tool-call budget, so no further tool calls are possible. " +
        "Answer the original question now, as completely as the evidence you have already " +
        "gathered allows. If that evidence is incomplete, give your best supported answer and " +
        "state plainly what you could not confirm."

/**
 * What one agent run produced, before [AgentRunner] folds in the guardrail bookkeeping
 * (contamination, ceiling, CLI attempt count) that isn't the client's job to know about.
 *
 * [costUsd] is `null` when the run's model has no entry in
 * [io.contextgraph.benchmark.runner.ModelPricing] (task 20) -- e.g. an OpenAI model, which the
 * pricing table does not cover. This is deliberately distinct from `0.0`: a null means "cost was
 * never computed for this run", not "this run cost nothing". See [ModelPricing.costUsdOrNull].
 */
data class AgentClientOutcome(
    val inputTokens: Long,
    val outputTokens: Long,
    val toolCallCount: Int,
    val fileReadCount: Int,
    val costUsd: Double?,
    val finalAnswer: String,
    /** Per-tool-name call counts, empty for backends that do not report names. See
     * [io.contextgraph.benchmark.model.AgentRunRecord.toolNameCounts] for why this is recorded. */
    val toolNameCounts: Map<String, Int> = emptyMap()
)

/**
 * The coding-agent execution backend: given one [AgentRunContext], run the agent to completion
 * (or the tool-call ceiling) and report what happened. Kept behind this narrow interface so
 * [AgentRunner]'s guardrail and metric-extraction logic -- the part this slice's acceptance
 * criteria are actually about -- is testable with a fake, without a real model call. The
 * production implementation is [AnthropicMessagesClient].
 */
fun interface AgentClient {
    fun run(context: AgentRunContext): AgentClientOutcome
}
