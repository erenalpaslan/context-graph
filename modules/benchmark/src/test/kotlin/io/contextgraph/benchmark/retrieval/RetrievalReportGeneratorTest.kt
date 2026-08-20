package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory
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
})
