package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory
import io.contextgraph.benchmark.runner.GraphTool
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.Instant

private fun fixtureRun(): RetrievalRun {
    val results = listOf(
        RetrievalRunResult(
            questionId = "gin-q1",
            repoId = "gin",
            category = QuestionCategory.GRAPH_HEAVY,
            expectedFiles = listOf("gin.go"),
            ripgrepQueryTokens = listOf("ServeHTTP"),
            contextGraph = SideResult(listOf("gin.go"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0),
            ripgrep = SideResult(listOf("gin.go"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0)
        ),
        RetrievalRunResult(
            questionId = "gin-q8",
            repoId = "gin",
            category = QuestionCategory.NEGATIVE_CONTROL,
            expectedFiles = listOf("gin.go"),
            ripgrepQueryTokens = listOf("404", "405"),
            contextGraph = SideResult(emptyList(), mapOf(5 to 0.0), mapOf(5 to 0.0), 0.0),
            ripgrep = SideResult(listOf("gin.go"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0)
        )
    )
    return RetrievalRun(
        runId = "retrieval-test-fixture",
        generatedAt = Instant.parse("2026-08-17T00:00:00Z"),
        kValues = listOf(5),
        results = results,
        skippedRepos = listOf(SkippedRepo("keycloak", "WITHOUT working copy not found")),
        summary = RetrievalStats.summarize(results, listOf(5))
    )
}

class RetrievalReportGeneratorTest : FunSpec({

    test("deterministic: the same run renders to the same string every time") {
        val run = fixtureRun()
        RetrievalReportGenerator.generate(run) shouldBe RetrievalReportGenerator.generate(run)
    }

    test("the section is bounded by start/end markers") {
        val section = RetrievalReportGenerator.generate(fixtureRun())
        section shouldContain "<!-- retrieval-axis:start -->"
        section shouldContain "<!-- retrieval-axis:end -->"
    }

    test("negative controls are reported separately from the headline") {
        val section = RetrievalReportGenerator.generate(fixtureRun())
        section shouldContain "### Headline (GRAPH_HEAVY + NEUTRAL)"
        section shouldContain "### Negative Controls"
        section shouldContain "gin-q8"
    }

    test("category breakdown covers all three categories") {
        val section = RetrievalReportGenerator.generate(fixtureRun())
        section shouldContain "GRAPH_HEAVY"
        section shouldContain "NEUTRAL"
        section shouldContain "NEGATIVE_CONTROL"
    }

    test("skipped repos are reported explicitly, not silently omitted") {
        val section = RetrievalReportGenerator.generate(fixtureRun())
        section shouldContain "### Skipped"
        section shouldContain "keycloak"
    }

    test("upsert appends the section (with markers) when the target file has no existing section") {
        val existing = "# BENCHMARKS\n\nsome pre-existing agent-A/B content\n"
        val section = RetrievalReportGenerator.generate(fixtureRun())

        val merged = RetrievalReportGenerator.upsert(existing, section)

        merged shouldContain "some pre-existing agent-A/B content"
        merged shouldContain "<!-- retrieval-axis:start -->"
        merged shouldContain "gin-q8"
    }

    test("upsert replaces only the marked section, leaving surrounding content untouched") {
        val existing = "# BENCHMARKS\n\nbefore\n\n<!-- retrieval-axis:start -->\nSTALE CONTENT\n<!-- retrieval-axis:end -->\n\nafter\n"
        val section = RetrievalReportGenerator.generate(fixtureRun())

        val merged = RetrievalReportGenerator.upsert(existing, section)

        merged shouldContain "before"
        merged shouldContain "after"
        merged shouldContain "gin-q8"
        (merged.contains("STALE CONTENT")) shouldBe false
    }

    test("upsert is idempotent: running it twice with the same section yields the same file") {
        val existing = "# BENCHMARKS\n\nagent axis content\n"
        val section = RetrievalReportGenerator.generate(fixtureRun())

        val once = RetrievalReportGenerator.upsert(existing, section)
        val twice = RetrievalReportGenerator.upsert(once, section)

        once shouldBe twice
    }

    // ------------------------------------------------- the three-way comparison

    test("every metric table carries all three sides, with their qualifiers") {
        val section = RetrievalReportGenerator.generate(threeWayRun())

        section shouldContain "| Metric | ContextGraph (this project) | CodeGraph (third-party) | ripgrep (baseline) |"
        section shouldContain "ContextGraph (this project)"
        section shouldContain "CodeGraph (third-party)"
        section shouldContain "ripgrep (baseline)"
    }

    test("neither graph tool is ever named bare in a table header or a verdict") {
        // The two names differ by two letters, and a reader skimming a table is exactly who gets
        // that wrong. Any occurrence of either bare word inside a table row is a regression.
        val section = RetrievalReportGenerator.generate(threeWayRun())

        val offending = section.lines()
            .filter { it.trimStart().startsWith("|") }
            .filter { line ->
                Regex("ContextGraph(?! \\(this project\\))").containsMatchIn(line) ||
                    Regex("CodeGraph(?! \\(third-party\\))").containsMatchIn(line)
            }
            // `codegraph explore` / `codegraph_explore` in a code span is a command name, not a label.
            .filterNot { it.contains("`codegraph") || it.contains("codegraph_") }

        offending shouldBe emptyList()
    }

    test("each side reports how many questions it actually measured, so a short denominator is visible") {
        val section = RetrievalReportGenerator.generate(threeWayRun())
        section shouldContain "Measured:"
        section shouldContain "excluded from that column's mean, not counted as zero"
    }

    test("gold-file coverage is published for all three sides, and an unknown renders as one") {
        val section = RetrievalReportGenerator.generate(threeWayRun())

        section shouldContain "### Gold-file coverage"
        section shouldContain "queried the index, file by file"
        section shouldContain "100% by construction"
        section shouldContain "_not determinable_"
        // The unknown must not claim a cause it cannot distinguish: this state is reached both
        // when no index exists and when one exists without a per-file read surface.
        section shouldContain "no index, or no readable per-file index surface"
        // The asymmetry it exists to answer, and the part of it that remains.
        section shouldContain "was deliberately **not** loosened"
        section shouldContain "still absent from the ContextGraph (this project)"
    }

    test("ingest cost is reported per tool, and a missing index reads as not built rather than free") {
        val section = RetrievalReportGenerator.generate(threeWayRun())

        section shouldContain "### Ingest cost"
        section shouldContain "1.10 GB"
        section shouldContain "_not built_"
        section shouldContain "binary did not resolve"
    }

    test("a run with no ingest manifest says 'not recorded' rather than showing zeros") {
        val section = RetrievalReportGenerator.generate(fixtureRun())
        section shouldContain "Not recorded"
    }

    test("the report states that the instrument is not self-contained and names what to install") {
        val section = RetrievalReportGenerator.generate(threeWayRun())

        section shouldContain "not self-contained"
        section shouldContain "@colbymchenry/codegraph"
        section shouldContain "ripgrep"
    }

    test("the two parsing decisions that move CodeGraph's numbers are both stated") {
        val section = RetrievalReportGenerator.generate(threeWayRun())

        section shouldContain "blast-radius"
        section shouldContain "Low-confidence match"
        section shouldContain "no `--json` flag"
    }

    test("a run measuring no CodeGraph side renders n/a, never 0.0%") {
        val section = RetrievalReportGenerator.generate(fixtureRun())

        section shouldContain "CodeGraph (third-party) not in this run"
        // The MRR row must show n/a for the absent side rather than a score.
        section.lines().filter { it.startsWith("| MRR ") }.forEach { it shouldContain "n/a" }
    }
})

/** A run with all three sides measured, plus coverage and ingest cost, as a real three-way run has. */
private fun threeWayRun(): RetrievalRun {
    val results = listOf(
        RetrievalRunResult(
            questionId = "kc-q1",
            repoId = "keycloak",
            category = QuestionCategory.GRAPH_HEAVY,
            expectedFiles = listOf("services/Auth.java"),
            ripgrepQueryTokens = listOf("Auth"),
            contextGraph = SideResult(listOf("services/Auth.java"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0),
            ripgrep = SideResult(listOf("services/Auth.java"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0),
            codeGraph = SideResult(listOf("services/Auth.java"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0)
        ),
        RetrievalRunResult(
            questionId = "kc-q2",
            repoId = "keycloak",
            category = QuestionCategory.NEGATIVE_CONTROL,
            expectedFiles = listOf("services/Auth.java"),
            ripgrepQueryTokens = listOf("404"),
            contextGraph = SideResult(emptyList(), mapOf(5 to 0.0), mapOf(5 to 0.0), 0.0),
            ripgrep = SideResult(listOf("services/Auth.java"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0),
            // Unmeasured on this question -- excluded from the mean, listed under Skipped.
            codeGraph = null
        )
    )
    return RetrievalRun(
        runId = "retrieval-three-way-fixture",
        generatedAt = Instant.parse("2026-08-22T00:00:00Z"),
        kValues = listOf(5),
        results = results,
        skippedRepos = listOf(
            SkippedRepo("keycloak", "${RetrievalSide.CODE_GRAPH.label} side unmeasured for question kc-q2: timed out")
        ),
        summary = RetrievalStats.summarize(results, listOf(5)),
        goldFileCoverage = listOf(
            GoldFileCoverage("keycloak", RetrievalSide.CONTEXT_GRAPH, 22, 16, CoverageBasis.INDEX_QUERY),
            GoldFileCoverage("keycloak", RetrievalSide.CODE_GRAPH, 22, null, CoverageBasis.NOT_DETERMINABLE),
            GoldFileCoverage("keycloak", RetrievalSide.RIPGREP, 22, 22, CoverageBasis.READS_WORKING_TREE)
        ),
        ingestCosts = listOf(
            ToolIngestCost("keycloak", GraphTool.CONTEXTGRAPH, durationMillis = 3_000_000, indexSizeBytes = 1_100_000_000),
            ToolIngestCost("keycloak", GraphTool.CODEGRAPH, absentReason = "${RetrievalSide.CODE_GRAPH.label} binary did not resolve")
        )
    )
}
