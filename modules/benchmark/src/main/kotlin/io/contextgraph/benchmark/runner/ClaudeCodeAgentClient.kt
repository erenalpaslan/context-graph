package io.contextgraph.benchmark.runner

import io.contextgraph.benchmark.model.Arm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isExecutable
import kotlin.io.path.writeText

/**
 * Runs each arm as a real Claude Code session (`claude --print`) instead of a hand-rolled
 * tool-use loop.
 *
 * The reason is validity, not convenience. ContextGraph ships as an MCP server, so the thing a
 * user actually does is attach it to a coding agent -- and until now the benchmark measured a
 * close approximation of that written by the benchmark itself. Here the arms *are* the product's
 * real integration path: identical Claude Code invocations, differing only in whether
 * `--mcp-config` carries the ContextGraph server.
 *
 * It also removes a flattering asymmetry. The previous control arm had one tool, a bash executor
 * this repo wrote; this one has Claude Code's own Read/Grep/Glob/Bash. That is a much harder
 * baseline to beat, which is exactly what makes beating it worth reporting.
 *
 * Three things are deliberately held identical across arms, since each is a way the comparison
 * could silently stop being one:
 *  - `--strict-mcp-config` in **both** arms. Without it the control arm would inherit whatever MCP
 *    servers the developer running the benchmark happens to have configured, and "no ContextGraph"
 *    would quietly mean "no ContextGraph, plus whatever else was lying around".
 *  - `WebSearch`/`WebFetch` disallowed in **both** arms. A hosted agent can look up a public repo's
 *    documentation online and answer without reading the code at all -- a contamination route the
 *    previous bash-only harness did not have, and one that would make the corpus irrelevant.
 *  - [AgentRunContext.env], carrying the sanitized PATH and [CliSentinel]'s shim. The spawned agent
 *    runs its own shell commands out of [GuardedBashExecutor]'s reach, so the environment is the
 *    only thing that carries AC-8/AC-9's guarantee across the process boundary.
 */
class ClaudeCodeAgentClient(
    private val claudeCliPath: String = resolveClaudeCli(),
    private val contextGraphCliPath: Path? = null,
    /** Which graph server the WITH_TOOLS arm gets; see [GraphTool] for why this is a choice. */
    val graphTool: GraphTool = GraphTool.CONTEXTGRAPH,
    /** Executable for a third-party graph tool's MCP server (e.g. the repo-local `codegraph`). */
    private val externalToolCliPath: Path? = null,
    private val timeoutMinutes: Long = 30,
    private val processRunner: (List<String>, Path, Map<String, String>, Long) -> ProcessOutput = ::runProcess
) : AgentClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun run(context: AgentRunContext): AgentClientOutcome {
        val mcpConfig = writeMcpConfig(context)
        try {
            val command = buildCommand(context, mcpConfig)
            val output = processRunner(command, context.workingDir, context.env, timeoutMinutes)
            return parse(output, context)
        } finally {
            mcpConfig.deleteIfExists()
        }
    }

    /**
     * Even the WITHOUT_TOOLS arm gets a config file -- an empty one. `--strict-mcp-config` only
     * takes effect alongside `--mcp-config`, and "no servers, stated explicitly" is a different
     * guarantee from "no servers mentioned, inherit the machine's".
     */
    private fun writeMcpConfig(context: AgentRunContext): Path {
        val file = Files.createTempFile("contextgraph-mcp-${context.arm.name.lowercase()}-", ".json")
        val body = if (context.arm == Arm.WITH_TOOLS) {
            val (cli, args) = when (graphTool) {
                GraphTool.CONTEXTGRAPH -> (
                    contextGraphCliPath?.absolutePathString()
                        ?: throw AgentCallFailedException(
                            "The WITH_TOOLS arm needs the ContextGraph CLI to launch its MCP server, " +
                                "but no path was configured. Build it with " +
                                "`./gradlew :modules:cli:installDist` and pass the resulting bin/cli path."
                        )
                    ) to listOf("serve-mcp")
                // Launched through an absolute `node` rather than its own `#!/usr/bin/env node`
                // shim: the benchmark runs with a sanitized PATH that need not contain node, and
                // an MCP server that cannot start is indistinguishable, from inside a run, from a
                // tool the agent declined to use -- the first CodeGraph attempt lost three runs to
                // exactly that ambiguity.
                GraphTool.CODEGRAPH -> {
                    val shim = externalToolCliPath?.absolutePathString()
                        ?: throw AgentCallFailedException(
                            "The WITH_TOOLS arm needs the codegraph executable to launch its MCP " +
                                "server, but no path was configured. Install it repo-locally with " +
                                "`npm i --prefix .benchmark-tools @colbymchenry/codegraph`."
                        )
                    resolveNode() to listOf(shim, "serve", "--mcp")
                }
            }
            val argsJson = args.joinToString(",") { quote(it) }
            """{"mcpServers":{${quote(graphTool.serverName)}:{"command":${quote(cli)},"args":[$argsJson]}}}"""
        } else {
            """{"mcpServers":{}}"""
        }
        file.writeText(body)
        return file
    }

    private fun buildCommand(context: AgentRunContext, mcpConfig: Path): List<String> = buildList {
        add(claudeCliPath)
        add("--print")
        add(context.question.text)
        add("--output-format"); add("stream-json")
        add("--verbose")
        add("--model"); add(context.model)
        // Appended, not replaced: replacing Claude Code's system prompt would strip the tool
        // guidance that makes it a competent agent, and would hobble both arms rather than
        // measuring them.
        add("--append-system-prompt"); add(context.systemPrompt)
        add("--permission-mode"); add("bypassPermissions")
        add("--mcp-config"); add(mcpConfig.absolutePathString())
        add("--strict-mcp-config")
        // A denylist, not `--tools`: `--tools` restricts to the *built-in* set and drops every
        // MCP tool with it, which would silently empty the WITH_TOOLS arm of the only thing being
        // measured. Verified directly against the CLI before relying on it.
        add("--disallowedTools"); DISALLOWED_TOOLS.forEach { add(it) }
        // Without this the spawned session inherits the developer's own user-level settings,
        // skills and tools -- an early run's control arm reached for ScheduleWakeup and Agent,
        // neither of which belongs in a clean baseline and neither of which another machine would
        // have reproduced.
        add("--setting-sources"); add("")
        context.maxBudgetUsd?.let { add("--max-budget-usd"); add(it.toString()) }
    }

    private fun parse(output: ProcessOutput, context: AgentRunContext): AgentClientOutcome {
        var toolCallCount = 0
        var fileReadCount = 0
        val toolNameCounts = mutableMapOf<String, Int>()
        var result: JsonObject? = null

        for (line in output.stdout.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val event = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: continue
            when (event["type"]?.jsonPrimitive?.contentOrNull) {
                "assistant" -> {
                    val blocks = event["message"]?.jsonObject?.get("content")?.jsonArray.orEmpty()
                    for (block in blocks) {
                        val obj = block.jsonObject
                        if (obj["type"]?.jsonPrimitive?.contentOrNull != "tool_use") continue
                        toolCallCount++
                        val toolName = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        toolNameCounts.merge(toolName, 1, Int::plus)
                        if (toolName in FILE_READ_TOOLS) fileReadCount++
                    }
                }
                "result" -> result = event
            }
        }

        val resultEvent = result ?: throw AgentCallFailedException(
            "Claude Code produced no result event for ${context.question.id}/${context.arm} " +
                "(exit=${output.exitCode}). stderr: ${output.stderr.take(600)}"
        )

        // `is_error` is the CLI's own verdict on the session. Trusting the exit code alone is the
        // same mistake as trusting an HTTP 2xx: task 19's bug in a different costume.
        if (resultEvent["is_error"]?.jsonPrimitive?.booleanOrNull == true) {
            throw AgentCallFailedException(
                "Claude Code reported is_error for ${context.question.id}/${context.arm}: " +
                    "subtype=${resultEvent["subtype"]?.jsonPrimitive?.contentOrNull}, " +
                    "api_error_status=${resultEvent["api_error_status"]?.jsonPrimitive?.contentOrNull}"
            )
        }

        val usage = resultEvent["usage"]?.jsonObject
        val finalAnswer = resultEvent["result"]?.jsonPrimitive?.contentOrNull.orEmpty()

        requireMeasuredOutcome(
            providerName = "Claude Code CLI",
            inputTokens = usage.longField("input_tokens"),
            outputTokens = usage.longField("output_tokens"),
            finalAnswer = finalAnswer
        )

        return AgentClientOutcome(
            // Cache reads and creations are real input tokens the run was billed for; counting
            // only `input_tokens` would report a fraction of what the run actually consumed and
            // make a cached run look dramatically cheaper than an identical uncached one.
            inputTokens = usage.longField("input_tokens") +
                usage.longField("cache_read_input_tokens") +
                usage.longField("cache_creation_input_tokens"),
            outputTokens = usage.longField("output_tokens"),
            toolCallCount = toolCallCount,
            fileReadCount = fileReadCount,
            // Unlike the OpenAI path, this is a real figure the CLI reports rather than a lookup
            // in a pricing table that may not cover the model -- so it is never null here.
            costUsd = resultEvent["total_cost_usd"]?.jsonPrimitive?.doubleOrNull,
            finalAnswer = finalAnswer,
            toolNameCounts = toolNameCounts
        )
    }

    private companion object {
        /**
         * What counts as reading a file. Grep and Glob are searches -- they answer "where is it"
         * without returning file contents -- and folding them in here would inflate the control
         * arm's read count with work that is not reading.
         */
        val FILE_READ_TOOLS = setOf("Read", "NotebookRead")

        /**
         * Pinned identically for both arms. Three groups, for three different reasons: editing
         * tools would let an agent modify the working copy and trip AC-9's contamination
         * fingerprint; web tools let a hosted agent answer from a public repo's online docs
         * without reading the corpus at all; and the session-management tools are this harness's
         * own furniture, not part of any baseline a second machine would reproduce.
         *
         * `ToolSearch` is deliberately absent -- it is how MCP tools get discovered at all in this
         * CLI version, so denying it would guarantee the result it is meant to measure.
         */
        val DISALLOWED_TOOLS = listOf(
            "Edit", "Write", "NotebookEdit",
            "WebSearch", "WebFetch",
            "Agent", "TodoWrite", "Skill", "Workflow", "ScheduleWakeup", "ReportFindings",
            "CronCreate", "CronDelete", "CronList", "Monitor", "PushNotification",
            "SendMessage", "TaskCreate", "TaskGet", "TaskList", "TaskOutput", "TaskStop", "TaskUpdate"
        )

        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        fun JsonObject?.longField(name: String): Long =
            this?.get(name)?.jsonPrimitive?.longOrNull ?: 0L

    }
}

/** stdout, stderr and exit status of one external process run. */
data class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String)

private fun runProcess(
    command: List<String>,
    workingDir: Path,
    env: Map<String, String>,
    timeoutMinutes: Long
): ProcessOutput {
    val builder = ProcessBuilder(command).directory(workingDir.toFile())
    if (env.isNotEmpty()) {
        builder.environment().clear()
        builder.environment().putAll(env)
    }
    val process = builder.start()
    process.outputStream.close()
    // Drained on separate threads: a session that writes more than the pipe buffer to one stream
    // while this thread reads the other would deadlock, and an agentic run writes a lot.
    val outText = StringBuilder()
    val errText = StringBuilder()
    val outThread = Thread { process.inputStream.bufferedReader().forEachLine { outText.appendLine(it) } }
    val errThread = Thread { process.errorStream.bufferedReader().forEachLine { errText.appendLine(it) } }
    outThread.start(); errThread.start()

    val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
    if (!finished) {
        process.destroyForcibly()
        outThread.join(5_000); errThread.join(5_000)
        throw AgentCallFailedException(
            "Claude Code did not finish within $timeoutMinutes minute(s) and was killed. " +
                "Partial stderr: ${errText.take(600)}"
        )
    }
    outThread.join(10_000); errThread.join(10_000)
    return ProcessOutput(process.exitValue(), outText.toString(), errText.toString())
}

/**
 * Where the `claude` binary is. Resolution order is explicit override, then PATH, then the two
 * places the installers actually put it -- because the benchmark runs with a *sanitized* PATH, and
 * a resolution that works in an interactive shell is not evidence it works inside a run.
 */
/**
 * Absolute path to a `node` runtime, for MCP servers shipped as `#!/usr/bin/env node` scripts.
 * Resolution never relies on the run's own PATH, which is deliberately sanitized.
 */
internal fun resolveNode(): String {
    System.getenv("BENCHMARK_NODE_PATH")?.takeIf { it.isNotBlank() }?.let { return it }
    return listOf("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node")
        .firstOrNull { Path.of(it).isExecutable() }
        ?: "node"
}

internal fun resolveClaudeCli(): String {
    System.getenv("CLAUDE_CODE_PATH")?.takeIf { it.isNotBlank() }?.let { return it }
    val candidates = listOf(
        "/opt/homebrew/bin/claude",
        "/usr/local/bin/claude",
        System.getProperty("user.home") + "/.claude/local/claude"
    )
    return candidates.firstOrNull { Path.of(it).isExecutable() } ?: "claude"
}
