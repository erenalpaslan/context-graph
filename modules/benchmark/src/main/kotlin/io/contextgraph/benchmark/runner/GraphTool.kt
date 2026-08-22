package io.contextgraph.benchmark.runner

import io.contextgraph.benchmark.model.CorpusRepo
import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * Which code-graph MCP server the WITH_TOOLS arm is given.
 *
 * This exists to answer a question the benchmark could not otherwise answer about *itself*. Every
 * measurement so far has come back at or below zero, and there are two incompatible explanations:
 * ContextGraph genuinely does not help a strong agent, or this harness cannot detect help from any
 * graph tool -- because the questions are answerable by reading, or the judge is insensitive, or
 * the agent ignores tool output. Those call for opposite responses, and no amount of re-running
 * ContextGraph distinguishes them.
 *
 * A second, independently built graph tool run through the identical harness does. If it shows a
 * gain on the same questions, the harness can detect gains and the finding is about ContextGraph.
 * If it comes back flat too, the harness is the first suspect. That is a positive control, and it
 * is the cheapest way to stop trusting a null result on faith.
 *
 * Everything except the server is held identical: same questions, same gold facts, same judge,
 * same agent and model, same prompt, same control arm.
 */
@Serializable
enum class GraphTool(
    val id: String,
    /** MCP server key; Claude Code exposes its tools as `mcp__<serverName>__<tool>`. */
    val serverName: String,
    /** Directory name of the WITH_TOOLS working copy this tool's index lives in. */
    val workingCopyDirName: String,
    /**
     * How this tool is named in any document a human reads -- the single source of truth for it.
     *
     * The qualifier is not decoration. "ContextGraph" and "CodeGraph" differ by two letters, and
     * every report they appear in together is one where that difference decides which tool won.
     * Defining the strings here, once, is what stops one table out of six from losing the
     * qualifier: the retrieval report, its ingest-cost table and the agent-A/B report all read
     * this field instead of each mapping the enum to a string of its own.
     */
    val label: String
) {
    CONTEXTGRAPH(
        id = "contextgraph",
        serverName = "contextgraph",
        workingCopyDirName = "with",
        label = "ContextGraph (this project)"
    ),

    /**
     * `@colbymchenry/codegraph`, installed repo-locally under `.benchmark-tools/` rather than
     * globally, and indexed into its own working copy so neither tool ever sees the other's
     * artefacts and the control arm stays clean for both.
     */
    CODEGRAPH(
        id = "codegraph",
        serverName = "codegraph",
        workingCopyDirName = "codegraph",
        label = "CodeGraph (third-party)"
    );

    /** Prefix every tool call from this server carries, used to prove the arm actually used it. */
    val toolPrefix: String get() = "mcp__${serverName}__"

    /**
     * Where the WITH_TOOLS arm runs. Corpus prep owns `with`/`without`; a third tool's copy is a
     * sibling of those, so a repo can carry one indexed copy per tool without either disturbing
     * the other or the never-indexed control.
     */
    fun withToolsDir(repo: CorpusRepo): String? = when (this) {
        CONTEXTGRAPH -> repo.workingCopyWithPath
        else -> repo.workingCopyWithPath?.let { Path.of(it).parent.resolve(workingCopyDirName).toString() }
    }

    companion object {
        fun byId(id: String): GraphTool =
            entries.firstOrNull { it.id == id }
                ?: error("unknown graph tool '$id' (known: ${entries.joinToString { it.id }})")
    }
}
