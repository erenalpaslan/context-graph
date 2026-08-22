package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory
import io.contextgraph.benchmark.runner.GraphTool
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.Instant

private val K = listOf(5, 10)

private fun side(rr: Double, p: Double = rr, r: Double = rr) = SideResult(
    rankedFiles = listOf("src/A.kt", "src/B.kt"),
    precisionAtK = K.associateWith { p },
    recallAtK = K.associateWith { r },
    reciprocalRank = rr
)

private fun result(
    id: String,
    contextGraph: SideResult? = side(1.0),
    codeGraph: SideResult? = side(0.5),
    category: QuestionCategory = QuestionCategory.GRAPH_HEAVY
) = RetrievalRunResult(
    questionId = id,
    repoId = "keycloak",
    category = category,
    expectedFiles = listOf("src/A.kt"),
    ripgrepQueryTokens = listOf("A"),
    contextGraph = contextGraph,
    ripgrep = side(0.25),
    codeGraph = codeGraph
)

/**
 * The schema half of the three-way comparison: that a third side can be carried, aggregated and
 * round-tripped, and -- the part that actually matters -- that "not measured" never collapses
 * into "measured zero" anywhere along the way.
 *
 * That distinction is the whole reason these fields are nullable rather than defaulted. A
 * CodeGraph invocation that fails is an infrastructure fact; a CodeGraph invocation that
 * succeeds and returns nothing is a retrieval fact. Averaging the first in as 0.0 would let a
 * broken subprocess masquerade as evidence that CodeGraph does not work -- which is the single
 * most damaging way this instrument could mislead the run it serves.
 */
class RetrievalSchemaV2Test : FunSpec({

    fun run(vararg results: RetrievalRunResult) = RetrievalRun(
        runId = "retrieval-test",
        generatedAt = Instant.parse("2026-08-22T12:00:00Z"),
        kValues = K,
        results = results.toList(),
        summary = RetrievalStats.summarize(results.toList(), K)
    )

    test("the retrieval schema is v2 and the agent-A/B schema deliberately stays v1") {
        RetrievalRun.SCHEMA_VERSION shouldBe 2
        io.contextgraph.benchmark.model.BenchmarkRun.SCHEMA_VERSION shouldBe 1
    }

    test("a run carrying all three sides round-trips through JSON unchanged") {
        val original = run(result("q1"), result("q2")).copy(
            goldFileCoverage = listOf(
                GoldFileCoverage("keycloak", RetrievalSide.CONTEXT_GRAPH, 22, 16, CoverageBasis.INDEX_QUERY),
                GoldFileCoverage("keycloak", RetrievalSide.RIPGREP, 22, 22, CoverageBasis.READS_WORKING_TREE),
                GoldFileCoverage("keycloak", RetrievalSide.CODE_GRAPH, 22, null, CoverageBasis.NOT_DETERMINABLE)
            ),
            ingestCosts = listOf(
                ToolIngestCost("keycloak", GraphTool.CONTEXTGRAPH, durationMillis = 3_000_000, indexSizeBytes = 1_100_000_000),
                ToolIngestCost("keycloak", GraphTool.CODEGRAPH, absentReason = "codegraph binary did not resolve")
            )
        )

        RetrievalRun.fromJson(original.toJson()) shouldBe original
    }

    test("a question with no CodeGraph side is excluded from that side's mean, not counted as zero") {
        val summary = run(
            result("q1", codeGraph = side(1.0)),
            result("q2", codeGraph = null)
        ).summary!!

        val codeGraph = summary.headline.codeGraph!!
        // Two questions in the group, one measurable side. The mean is over the one, and the
        // smaller denominator is visible rather than implied.
        summary.headline.questionCount shouldBe 2
        codeGraph.measuredCount shouldBe 1
        codeGraph.mrr shouldBe (1.0 plusOrMinus 1e-9)

        // Had the unmeasured question been folded in as 0.0, this would read 0.5.
        codeGraph.mrr shouldNotBe (0.5 plusOrMinus 1e-9)
    }

    test("a run where CodeGraph was never present reports a null aggregate, not a zeroed one") {
        val summary = run(result("q1", codeGraph = null), result("q2", codeGraph = null)).summary!!

        // "CodeGraph was not in this run" and "CodeGraph ran and scored 0" are different claims,
        // and only the second is evidence about CodeGraph.
        summary.headline.codeGraph shouldBe null
        summary.headline.ripgrep.measuredCount shouldBe 2
    }

    test("a CodeGraph side that ran and found nothing is a real zero, kept distinct from absence") {
        val foundNothing = SideResult(
            rankedFiles = emptyList(),
            precisionAtK = K.associateWith { 0.0 },
            recallAtK = K.associateWith { 0.0 },
            reciprocalRank = 0.0
        )
        val summary = run(result("q1", codeGraph = foundNothing)).summary!!

        val codeGraph = summary.headline.codeGraph!!
        codeGraph.measuredCount shouldBe 1
        codeGraph.mrr shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("coverage expresses all three states, and an unknown is never a zero") {
        val queried = GoldFileCoverage("keycloak", RetrievalSide.CONTEXT_GRAPH, 22, 16, CoverageBasis.INDEX_QUERY)
        val byConstruction = GoldFileCoverage("keycloak", RetrievalSide.RIPGREP, 22, 22, CoverageBasis.READS_WORKING_TREE)
        val unknown = GoldFileCoverage("keycloak", RetrievalSide.CODE_GRAPH, 22, null, CoverageBasis.NOT_DETERMINABLE)

        queried.fraction!! shouldBe (16.0 / 22.0 plusOrMinus 1e-9)
        byConstruction.fraction!! shouldBe (1.0 plusOrMinus 1e-9)
        unknown.fraction shouldBe null

        // A genuine 0% is a different value from an unknown, and both are different from 100%.
        GoldFileCoverage("gin", RetrievalSide.CONTEXT_GRAPH, 5, 0, CoverageBasis.INDEX_QUERY)
            .fraction!! shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("a coverage figure cannot claim a count and an unknown basis at once") {
        shouldThrow<IllegalArgumentException> {
            GoldFileCoverage("keycloak", RetrievalSide.CODE_GRAPH, 22, 16, CoverageBasis.NOT_DETERMINABLE)
        }
        shouldThrow<IllegalArgumentException> {
            GoldFileCoverage("keycloak", RetrievalSide.CODE_GRAPH, 22, null, CoverageBasis.INDEX_QUERY)
        }
    }

    test("an absent index cannot be recorded as a zero-cost one") {
        shouldThrow<IllegalArgumentException> {
            ToolIngestCost("keycloak", GraphTool.CODEGRAPH, durationMillis = 0, absentReason = "binary missing")
        }
        shouldThrow<IllegalArgumentException> {
            ToolIngestCost("keycloak", GraphTool.CODEGRAPH)
        }
    }

    test("the three sides' labels always carry their qualifier") {
        // The bare words differ by two letters; the parenthetical is what a reader skimming a
        // table -- or a table pasted alone into another document -- actually disambiguates on.
        RetrievalSide.CONTEXT_GRAPH.label shouldBe "ContextGraph (this project)"
        RetrievalSide.CODE_GRAPH.label shouldBe "CodeGraph (third-party)"
        RetrievalSide.RIPGREP.label shouldBe "ripgrep (baseline)"
        RetrievalSide.entries.forEach { (it.label.contains("(") && it.label.contains(")")) shouldBe true }
    }
})
