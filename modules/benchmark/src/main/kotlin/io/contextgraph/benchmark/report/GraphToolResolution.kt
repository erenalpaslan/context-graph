package io.contextgraph.benchmark.report

import io.contextgraph.benchmark.model.BenchmarkRun
import io.contextgraph.benchmark.runner.GraphTool

/**
 * Which code-graph tool a stored [BenchmarkRun] measured, and how confidently we know.
 *
 * The distinction between the three cases is the whole point. A report that cannot tell a
 * recorded fact from a guess -- or a guess from an unknown -- is how
 * `results/codegraph-forced/BENCHMARKS.md` came to state, flatly and wrongly, that no tool was
 * ever called in a run that made 23 CodeGraph calls.
 */
sealed interface GraphToolResolution {

    /** The run stored which tool it used. */
    data class Recorded(val recorded: GraphTool) : GraphToolResolution

    /** The run predates the field, but exactly one known MCP server prefix appears in its tool counts. */
    data class Inferred(val inferred: GraphTool, val fromPrefix: String) : GraphToolResolution

    /**
     * Neither recorded nor inferable: no MCP prefixes at all, or more than one, or one that
     * belongs to no known tool. The report then reports **no tool-usage count**, rather than
     * picking a tool and counting against it.
     */
    data class NotRecorded(val observedPrefixes: Set<String>) : GraphToolResolution

    val tool: GraphTool?
        get() = when (this) {
            is Recorded -> recorded
            is Inferred -> inferred
            is NotRecorded -> null
        }

    companion object {

        /**
         * Resolves [run]'s tool: the stored field if present, else inference from the
         * `mcp__<server>__` prefixes observed across every agent run's tool-name counts.
         *
         * Inference resolves **only** when exactly one distinct known prefix appears. Zero, two,
         * or an unrecognised one all yield [NotRecorded].
         *
         * **It deliberately never falls back to ContextGraph.** Defaulting is precisely the bug
         * this exists to fix: with nothing recorded, the old generator assumed ContextGraph and
         * counted a prefix the run had never used. An honest "not recorded" costs a table cell; a
         * confident wrong answer cost the credibility of the one run that could have validated the
         * harness.
         */
        fun of(run: BenchmarkRun): GraphToolResolution {
            run.graphTool?.let { return Recorded(it) }

            val observed = run.agentRuns.flatMap { it.observedMcpPrefixes }.toSet()
            val known = GraphTool.entries.associateBy { it.toolPrefix }
            val matched = observed.mapNotNull { known[it] }.distinct()

            return if (matched.size == 1) {
                Inferred(matched.single(), matched.single().toolPrefix)
            } else {
                NotRecorded(observed)
            }
        }
    }
}
