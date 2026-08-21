package io.contextgraph.benchmark.runner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Bash commands whose whole purpose is reading a file's contents, for AC-10's `fileReadCount`. */
private val FILE_READ_COMMAND_PREFIXES = setOf("cat", "head", "tail", "less", "more", "sed", "awk", "bat")

/**
 * Runs one tool call from an [AgentRunContext] -- shared between [AnthropicMessagesClient] and
 * [OpenAiChatCompletionsClient] (task 20) rather than duplicated, since neither the "bash" tool's
 * routing through [AgentRunContext.bashExecutor] nor the MCP tool's routing through
 * [AgentRunContext.invokeExtraTool] has anything provider-specific about it -- only the wire
 * format of the *request that produced* `toolName`/`input` differs between providers, and that
 * part stays in each client. Keeping this in one place also keeps the contamination guarantee
 * (every Bash attempt structurally forced through [GuardedBashExecutor]) a property of one
 * function, not a convention every new provider client has to remember to repeat.
 */
internal fun executeTool(
    context: AgentRunContext,
    toolName: String,
    input: JsonObject,
    onFileRead: () -> Unit
): String = when (toolName) {
    "bash" -> {
        val command = input["command"]?.jsonPrimitive?.content
        if (command == null) {
            "restart acknowledged"
        } else {
            if (looksLikeFileRead(command)) onFileRead()
            val result = context.bashExecutor.execute(command)
            (result.stdout + result.stderr).ifBlank { "(no output, exit code ${result.exitCode})" }
        }
    }
    else -> context.invokeExtraTool(toolName, input.toString())
}

private fun looksLikeFileRead(command: String): Boolean {
    val firstToken = command.trim().substringBefore(' ')
    return firstToken in FILE_READ_COMMAND_PREFIXES
}
