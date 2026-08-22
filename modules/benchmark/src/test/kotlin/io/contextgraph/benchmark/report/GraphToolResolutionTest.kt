package io.contextgraph.benchmark.report

import io.contextgraph.benchmark.model.AgentRunRecord
import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.BenchmarkConfig
import io.contextgraph.benchmark.model.BenchmarkRun
import io.contextgraph.benchmark.model.Profile
import io.contextgraph.benchmark.runner.GraphTool
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.Instant

private fun agentRun(id: String, tools: Map<String, Int>, arm: Arm = Arm.WITH_TOOLS) = AgentRunRecord(
    id = id,
    questionId = "q1",
    arm = arm,
    repeatIndex = 0,
    inputTokens = 100,
    outputTokens = 20,
    toolCallCount = tools.values.sum(),
    fileReadCount = 0,
    wallClockMillis = 1_000,
    costUsd = 0.01,
    finalAnswer = "answer",
    hitCeiling = false,
    contaminated = false,
    cliInvocationAttempts = 0,
    toolNameCounts = tools
)

private fun runWith(
    graphTool: GraphTool? = null,
    vararg agentRuns: AgentRunRecord
) = BenchmarkRun(
    runId = "run-tool-resolution",
    profile = Profile.SMOKE,
    generatedAt = Instant.parse("2026-08-22T00:00:00Z"),
    config = BenchmarkConfig(),
    agentRuns = agentRuns.toList(),
    graphTool = graphTool
)

/**
 * The report used to hardcode `mcp__contextgraph__`, so a run that used a *different* graph tool
 * was published as though no tool had been called at all. These tests pin the three outcomes that
 * replaced that assumption, and in particular pin the one that must never come back: **inference
 * failing does not mean ContextGraph.**
 */
class GraphToolResolutionTest : FunSpec({

    test("a recorded tool is used as recorded, with no inference involved") {
        val resolution = GraphToolResolution.of(
            runWith(GraphTool.CODEGRAPH, agentRun("r1", mapOf("mcp__contextgraph__search_nodes" to 5)))
        )

        // Even though the observed prefix says otherwise, a recorded value is a fact and inference
        // is only ever a fallback for its absence.
        resolution shouldBe GraphToolResolution.Recorded(GraphTool.CODEGRAPH)
    }

    test("exactly one known MCP prefix is inferred, and reported as an inference") {
        val resolution = GraphToolResolution.of(
            runWith(
                null,
                agentRun("r1", mapOf("mcp__codegraph__codegraph_explore" to 8, "Read" to 3)),
                agentRun("r2", mapOf("mcp__codegraph__codegraph_explore" to 15, "Bash" to 2))
            )
        )

        resolution shouldBe GraphToolResolution.Inferred(GraphTool.CODEGRAPH, "mcp__codegraph__")
    }

    test("no MCP prefixes at all is 'not recorded', never a default to ContextGraph") {
        val resolution = GraphToolResolution.of(runWith(null, agentRun("r1", mapOf("Read" to 3, "Bash" to 1))))

        resolution.tool shouldBe null
        (resolution as GraphToolResolution.NotRecorded).observedPrefixes shouldBe emptySet()
    }

    test("two different known prefixes is 'not recorded' -- ambiguity is not resolved by picking one") {
        val resolution = GraphToolResolution.of(
            runWith(
                null,
                agentRun("r1", mapOf("mcp__contextgraph__search_nodes" to 2)),
                agentRun("r2", mapOf("mcp__codegraph__codegraph_explore" to 2))
            )
        )

        resolution.tool shouldBe null
        (resolution as GraphToolResolution.NotRecorded).observedPrefixes shouldBe
            setOf("mcp__contextgraph__", "mcp__codegraph__")
    }

    test("an MCP prefix belonging to no known tool is 'not recorded'") {
        val resolution = GraphToolResolution.of(
            runWith(null, agentRun("r1", mapOf("mcp__obsidian__search" to 4)))
        )

        resolution.tool shouldBe null
    }

    // ------------------------------------------------------- rendered output

    test("a CodeGraph run counts CodeGraph's calls and says so, instead of claiming none happened") {
        val report = BenchmarksReportGenerator.generate(
            runWith(
                null,
                agentRun("r1", mapOf("mcp__codegraph__codegraph_explore" to 8)),
                agentRun("r2", mapOf("mcp__codegraph__codegraph_explore" to 15))
            )
        )

        report shouldContain "CodeGraph (third-party) tool usage"
        report shouldContain "| CodeGraph (third-party) tool calls | 23 |"
        report shouldContain "**inferred**, not recorded"
        // The exact false claim this work exists to remove.
        report shouldNotContain "No ContextGraph tool was ever called in this run."
    }

    test("a run that genuinely called nothing still gets the warning -- for the right tool") {
        val report = BenchmarksReportGenerator.generate(
            runWith(GraphTool.CONTEXTGRAPH, agentRun("r1", mapOf("Read" to 3)))
        )

        // The warning is load-bearing: a zero-call WITH_TOOLS arm makes the headline meaningless,
        // and losing it in the refactor would be a worse regression than the bug being fixed.
        report shouldContain "**No ContextGraph (this project) tool was ever called in this run.**"
    }

    test("an unresolvable tool prints no usage count at all, and says why") {
        val report = BenchmarksReportGenerator.generate(
            runWith(
                null,
                agentRun("r1", mapOf("mcp__contextgraph__search_nodes" to 2)),
                agentRun("r2", mapOf("mcp__codegraph__codegraph_explore" to 2))
            )
        )

        report shouldContain "is not recorded, and could not be inferred"
        report shouldContain "`mcp__codegraph__`"
        report shouldContain "`mcp__contextgraph__`"
        // No count is printed, because counting requires knowing whose calls to count.
        report shouldNotContain "tool calls |"
    }

    test("call counting is per tool, so one tool's prefix never counts another's calls") {
        val record = agentRun("r1", mapOf("mcp__codegraph__codegraph_explore" to 23, "Read" to 4))

        record.graphToolCalls(GraphTool.CODEGRAPH) shouldBe 23
        record.graphToolCalls(GraphTool.CONTEXTGRAPH) shouldBe 0
        record.observedMcpPrefixes shouldBe setOf("mcp__codegraph__")
    }
})
