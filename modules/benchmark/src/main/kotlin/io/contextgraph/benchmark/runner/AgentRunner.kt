package io.contextgraph.benchmark.runner

import io.contextgraph.benchmark.model.AgentRunRecord
import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.BenchmarkConfig
import io.contextgraph.benchmark.model.Question
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Path
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Neutral on purpose: it says nothing about ContextGraph, MCP tools, or the fact that a
 * comparison is happening, because both arms receive the exact same string (AC-7). Whatever a
 * WITH_TOOLS agent knows about its extra tool comes only from that tool's own description.
 */
const val DEFAULT_SYSTEM_PROMPT: String =
    "You are a careful senior software engineer investigating a codebase to answer a " +
        "question about it. Use the tools available to you to find the answer; do not " +
        "guess. When you have the answer, give your final response as plain text with no " +
        "further tool calls."

/**
 * The two questions a tool-augmented benchmark can ask, which are not the same question and were
 * being conflated.
 *
 * The first Claude Code run scored both arms 1.0 and looked like a clean "ContextGraph makes no
 * difference" -- until per-tool counts showed the WITH_TOOLS arm had called ContextGraph's tools
 * *zero* times. The server had attached correctly (11 tools, verified directly); the agent simply
 * never reached for them, and never ran a tool search that would have surfaced them. So the run
 * measured adoption and reported it as efficacy.
 *
 * Splitting them keeps both results honest, and both are worth having: a graph nobody's agent
 * discovers is a real product problem even if the graph is excellent, and a graph that doesn't
 * help once used is a different problem entirely. Neither is evidence about the other.
 */
/**
 * The tool prefix each prompt is written against. Naming one tool in a prompt used to measure a
 * *different* tool is not a cosmetic slip: the first CodeGraph run told the agent to look for
 * `mcp__contextgraph__*`, it correctly found none, fell back to files, and every WITH_TOOLS run
 * failed the usage check. The placeholder makes that mistake unrepresentable.
 */
private const val TOOL_PREFIX_PLACEHOLDER = "{{TOOL_PREFIX}}"

enum class Measurement(private val promptTemplate: String) {
    /**
     * Nothing is said about ContextGraph. Answers "does an agent reach for these tools on its
     * own?" -- the situation of every real user who installs the MCP server and changes nothing
     * else.
     */
    ADOPTION(DEFAULT_SYSTEM_PROMPT),

    /**
     * Both arms are told a code-graph server *may* be present and to prefer it when it is.
     * Answers "does the graph produce better answers when actually used?"
     *
     * The wording is conditional and identical in both arms, which is what keeps AC-7's parity
     * intact: the control arm reads the same sentence and finds no such tools, exactly as a user
     * without ContextGraph installed would. Naming ContextGraph only to the WITH_TOOLS arm would
     * have made the prompt itself a second difference between the arms, and any measured gap
     * would then be partly the prompt's doing.
     */
    EFFICACY(
        DEFAULT_SYSTEM_PROMPT +
            " A code-graph MCP server exposing tools named `$TOOL_PREFIX_PLACEHOLDER*` may be " +
            "available in this session; those tools are not listed up front, so search the " +
            "available tools for them before you begin. If they are present, prefer them for " +
            "questions about how code is connected, and fall back to reading and grepping files " +
            "only where they fall short. If they are not present, answer using the file tools " +
            "alone."
    ),

    /**
     * Both arms are *required* to use the graph tools when present, and a WITH_TOOLS run that
     * calls none is recorded as a failure rather than scored.
     *
     * [EFFICACY] left tool selection to the agent, and the agent spent it badly: across nine
     * questions it called ContextGraph on five, all of which the control arm was already
     * answering perfectly, and on none of the three where the control arm left real headroom
     * (0.50, 0.67, 0.80). Usage and opportunity were disjoint, so the measurement could not
     * speak to the graph either way.
     *
     * Removing the choice removes that confound. What remains is the comparison worth having:
     * an answer built from the graph against an answer built from reading files.
     *
     * The instruction is identical in both arms, for a reason measured rather than assumed --
     * [EFFICACY]'s far milder "search for the tools" line lifted the *control* arm from 0.83 to
     * 1.00 on q3. A prompt given to only one arm becomes a second difference between them, and
     * any gap it produces is then partly the prompt's. Here the control arm reads the same
     * sentence and finds no such server, exactly as a user without ContextGraph installed would.
     */
    FORCED(
        DEFAULT_SYSTEM_PROMPT +
            " A code-graph MCP server exposing tools named `$TOOL_PREFIX_PLACEHOLDER*` may be " +
            "available in this session; those tools are not listed up front, so search the " +
            "available tools for them before you begin. If they are present you MUST use them as " +
            "your primary source of evidence: start there, follow the relationships they report, " +
            "and read files only to confirm what those tools have already led you to. If they are " +
            "not present, answer using the file tools alone."
    );

    /** The prompt as the agent sees it, with the placeholder resolved to [graphTool]'s prefix. */
    fun systemPromptFor(graphTool: GraphTool): String =
        promptTemplate.replace(TOOL_PREFIX_PLACEHOLDER, graphTool.toolPrefix)
}

/**
 * Thrown when a run that was required to exercise the graph did not touch it.
 *
 * Scoring such a run would put a number in the ContextGraph column that ContextGraph had no part
 * in producing -- the precise way an earlier run reported "no difference" about a tool it had
 * never called. Counted as a failure so it is visible, rather than silently averaged in.
 */
class ExtraToolsUnusedException(message: String) : Exception(message)

/**
 * Runs and measures exactly one (question, arm, repeat) agent invocation -- slice 04's whole
 * job, per its own task file: "Bu dilim tek bir koşuyu koşturur ve ölçer." No looping over
 * questions, arms, or repeats; that is slices 06 and 12's orchestration, layered on top of this.
 *
 * [mcpToolBridgeFactory] is the integration point for the WITH_TOOLS arm's tool surface --
 * defaults to [ContextGraphMcpToolBridge] against the given (indexed) working directory, but is
 * a constructor parameter precisely so tests can substitute a fake bridge and so slice 12's
 * orchestrator can substitute a differently-configured one without touching this class.
 */
class AgentRunner(
    private val agentClient: AgentClient,
    private val mcpToolBridgeFactory: (Path) -> McpToolBridge = { ContextGraphMcpToolBridge(it) },
    private val rawPathEnv: () -> String = { System.getenv("PATH").orEmpty() }
) {

    /**
     * @param workingDir For [Arm.WITH_TOOLS] this is the already-indexed working copy; for
     *   [Arm.WITHOUT_TOOLS] this must be the separate, never-indexed clean copy (slice 02's
     *   two-working-copy output -- see class doc). Verified clean before the run starts.
     */
    fun run(
        question: Question,
        arm: Arm,
        workingDir: Path,
        repeatIndex: Int,
        config: BenchmarkConfig,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        requireExtraToolUse: Boolean = false,
        graphTool: GraphTool = GraphTool.CONTEXTGRAPH
    ): AgentRunRecord {
        // AC-7a: refuse to start rather than silently clean up. Must happen before anything
        // else touches workingDir, and only for the arm whose entire identity depends on being
        // untouched.
        if (arm == Arm.WITHOUT_TOOLS) {
            WorkingCopyVerifier.verifyClean(workingDir)
        }

        val guard = ContaminationGuard()
        val sentinel = CliSentinel.createTemp()
        val sanitizedEnv = buildSanitizedEnv(sentinel.binDir)
        val bashExecutor = GuardedBashExecutor(guard, workingDir, sanitizedEnv)

        // WITH_TOOLS' bridge opens a real connection against workingDir's own graph.db (schema
        // migration check, journal housekeeping) as a one-time cost of *this harness* querying
        // the graph -- not of anything the agent did. That has to happen before the AC-9
        // baseline below, or every legitimate WITH_TOOLS run would be flagged contaminated for a
        // side effect of the harness's own tooling rather than the agent's. WITHOUT_TOOLS never
        // constructs a bridge, so its baseline is unaffected either way.
        // Only ContextGraph has an in-process bridge. A third-party tool's working copy carries
        // no `.contextgraph/` at all, so constructing one there would fail on a graph that was
        // never built -- and the Claude Code client reaches every graph tool over stdio anyway.
        val bridge = if (arm == Arm.WITH_TOOLS && graphTool == GraphTool.CONTEXTGRAPH) {
            mcpToolBridgeFactory(workingDir)
        } else {
            null
        }
        try {
            // AC-9 baseline: captured for *both* arms, after any harness-side connection has
            // already been opened (see above), so a post-run diff answers "did anything in this
            // working copy change *because of the run*" without caring whether the change looks
            // like a known artefact name (see WorkingCopyVerifier's doc comment for why presence
            // alone was the wrong check here for the WITH_TOOLS arm, and for a read-only CLI
            // leak in either arm).
            val preRunFingerprint = WorkingCopyVerifier.fingerprint(workingDir)

            val context = AgentRunContext(
                question = question,
                arm = arm,
                repeatIndex = repeatIndex,
                systemPrompt = systemPrompt,
                model = config.models.agentModel,
                toolCallCeiling = config.toolCallCeiling,
                workingDir = workingDir,
                bashExecutor = bashExecutor,
                extraTools = bridge?.tools().orEmpty(),
                invokeExtraTool = bridge?.let { b -> { name: String, input: String -> b.invoke(name, input) } }
                    ?: { name, _ -> error("no extra tools available in arm $arm (attempted: $name)") },
                env = sanitizedEnv,
                maxBudgetUsd = config.maxBudgetUsdPerRun
            )

            val startNanos = System.nanoTime()
            val outcome = agentClient.run(context)
            val wallClockMillis = (System.nanoTime() - startNanos) / 1_000_000

            val hitCeiling = outcome.toolCallCount >= config.toolCallCeiling

            // AC-9, in both arms: did the working copy change at all between the two
            // fingerprints. A change proves the block mechanism failed for real -- something
            // actually ran and actually wrote. It does *not* fire for a command the sentinel
            // caught (see below): the sentinel's whole point is that those never reach the real
            // CLI, so nothing changes, and marking them contaminated would misreport a
            // successfully-blocked attempt as a compromised run.
            val postRunFingerprint = WorkingCopyVerifier.fingerprint(workingDir)
            val contaminated = preRunFingerprint != postRunFingerprint
            if (contaminated) {
                logger.warn { "contamination detected: working copy $workingDir changed during a $arm run (block mechanism failure, AC-9)" }
            }

            // cliInvocationAttempts (AC-8) combines both signals a command can trip: the regex
            // pre-check (ContaminationGuard, string-level, blocks before any process starts) and
            // the sentinel (PATH-resolution-level, catches indirection the regex missed). The
            // two are mutually exclusive per command -- a regex-caught command is denied before
            // a shell ever runs, so it can never also reach the sentinel -- so summing them
            // double-counts nothing.
            val sentinelInvocations = sentinel.invocationCount()
            if (sentinelInvocations > 0) {
                logger.warn {
                    "contamination guard's regex missed $sentinelInvocations Bash invocation(s) that the PATH sentinel " +
                        "caught instead during a $arm run -- the command string(s) evaded ContaminationGuard's pattern"
                }
            }

            if (requireExtraToolUse && arm == Arm.WITH_TOOLS &&
                outcome.toolNameCounts.keys.none { it.startsWith(graphTool.toolPrefix) }
            ) {
                throw ExtraToolsUnusedException(
                    "${question.id}/$arm was required to use ${graphTool.id}'s MCP tools and called " +
                        "none (tools actually used: ${outcome.toolNameCounts.keys.sorted()}). " +
                        "Scoring this run would credit or blame ${graphTool.id} for an answer it " +
                        "played no part in."
                )
            }

            return AgentRunRecord(
                id = UUID.randomUUID().toString(),
                questionId = question.id,
                arm = arm,
                repeatIndex = repeatIndex,
                inputTokens = outcome.inputTokens,
                outputTokens = outcome.outputTokens,
                toolCallCount = outcome.toolCallCount,
                fileReadCount = outcome.fileReadCount,
                wallClockMillis = wallClockMillis,
                costUsd = outcome.costUsd,
                finalAnswer = outcome.finalAnswer,
                hitCeiling = hitCeiling,
                contaminated = contaminated,
                cliInvocationAttempts = guard.attemptCount + sentinelInvocations,
                toolNameCounts = outcome.toolNameCounts
            )
        } finally {
            bridge?.close()
            sentinel.cleanup()
        }
    }

    private fun buildSanitizedEnv(sentinelBinDir: Path): Map<String, String> {
        val env = System.getenv().toMutableMap()
        val sanitized = PathSanitizer.sanitize(rawPathEnv())
        // The sentinel directory goes first so PATH resolution reaches it before anything else
        // -- see CliSentinel's doc comment for why this has to be a prepend, not just a presence
        // check after sanitization.
        env["PATH"] = "$sentinelBinDir${File.pathSeparator}$sanitized"
        return env
    }
}
