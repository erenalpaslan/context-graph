package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.Instant

private val KS = listOf(5, 10)

private fun scored(rr: Double, p: Double = rr, r: Double = rr) = SideResult(
    rankedFiles = listOf("src/A.kt", "src/B.kt"),
    precisionAtK = KS.associateWith { p },
    recallAtK = KS.associateWith { r },
    reciprocalRank = rr
)

private val foundNothing = SideResult(
    rankedFiles = emptyList(),
    precisionAtK = KS.associateWith { 0.0 },
    recallAtK = KS.associateWith { 0.0 },
    reciprocalRank = 0.0
)

private fun fourSided(
    id: String,
    bash: SideResult? = scored(0.2),
    category: QuestionCategory = QuestionCategory.GRAPH_HEAVY,
    repoId: String = "keycloak"
) = RetrievalRunResult(
    questionId = id,
    repoId = repoId,
    category = category,
    expectedFiles = listOf("src/A.kt"),
    ripgrepQueryTokens = listOf("ServeHTTP"),
    contextGraph = scored(1.0),
    ripgrep = scored(0.25),
    codeGraph = scored(0.5),
    bash = bash
)

/**
 * The schema half of the *four*-way comparison, and the sibling of [RetrievalSchemaV2Test]: that a
 * fourth side -- the base-system bash baseline -- can be carried, aggregated at every level the
 * other three are aggregated at, and round-tripped, without a single archived result losing the
 * ability to decode.
 *
 * The bash side is additive for exactly the reason the CodeGraph side was: a result JSON written
 * before this field existed genuinely has no bash measurement, and the only honest way to say so
 * is `null`. Defaulting it to a zeroed [SideResult] would have made every archived run assert
 * something about a tool that never ran in it -- the same conflation of *absent* with *zero* that
 * [RetrievalSchemaV2Test] guards for CodeGraph, one comparator later.
 */
class RetrievalSchemaV3Test : FunSpec({

    fun run(vararg results: RetrievalRunResult) = RetrievalRun(
        runId = "retrieval-four-way-test",
        generatedAt = Instant.parse("2026-08-24T12:00:00Z"),
        kValues = KS,
        results = results.toList(),
        summary = RetrievalStats.summarize(results.toList(), KS)
    )

    test("the retrieval schema is v3 -- a four-sided result is tellable from a three-sided one") {
        // The version is what a reader checks *once*, instead of inspecting all 33 questions for a
        // `bash` field that might merely have been null that day.
        RetrievalRun.SCHEMA_VERSION shouldBe 3
        io.contextgraph.benchmark.model.BenchmarkRun.SCHEMA_VERSION shouldBe 1
    }

    test("a run carrying all four sides round-trips through JSON unchanged") {
        val original = run(
            fourSided("q1"),
            fourSided("q2", bash = foundNothing),
            fourSided("q3", bash = null, category = QuestionCategory.NEGATIVE_CONTROL, repoId = "gin")
        )

        val decoded = RetrievalRun.fromJson(original.toJson())

        decoded shouldBe original
        decoded.schemaVersion shouldBe 3
        decoded.results[0].bash shouldBe scored(0.2)
        decoded.results[1].bash shouldBe foundNothing
        decoded.results[2].bash shouldBe null
        decoded.summary!!.headline.bash shouldNotBe null
    }

    test("the bash side is aggregated at every level the other sides are") {
        val summary = run(
            fourSided("q1", repoId = "gin"),
            fourSided("q2", repoId = "keycloak", category = QuestionCategory.NEUTRAL),
            fourSided("q3", repoId = "keycloak", category = QuestionCategory.NEGATIVE_CONTROL)
        ).summary!!

        // Headline, negative control, by category, by repo -- the same four places `ripgrep` and
        // `codeGraph` appear. A side aggregated in three of the four would leave one table with a
        // column that could never be filled.
        summary.headline.bash!!.measuredCount shouldBe 2
        summary.negativeControl.bash!!.measuredCount shouldBe 1
        summary.byCategory.getValue(QuestionCategory.GRAPH_HEAVY).bash!!.measuredCount shouldBe 1
        summary.byCategory.getValue(QuestionCategory.NEUTRAL).bash!!.measuredCount shouldBe 1
        summary.byRepo.getValue("gin").bash!!.measuredCount shouldBe 1
        summary.byRepo.getValue("keycloak").bash!!.measuredCount shouldBe 2
    }

    test("a question with no bash side is excluded from that side's mean, not counted as zero") {
        val summary = run(
            fourSided("q1", bash = scored(1.0)),
            fourSided("q2", bash = null)
        ).summary!!

        val bash = summary.headline.bash!!
        summary.headline.questionCount shouldBe 2
        // The smaller denominator is printable rather than implied -- same rule as the other sides.
        bash.measuredCount shouldBe 1
        bash.mrr shouldBe (1.0 plusOrMinus 1e-9)
        // Had the unmeasured question been folded in as 0.0, this would read 0.5.
        bash.mrr shouldNotBe (0.5 plusOrMinus 1e-9)
    }

    test("a run where bash was never present reports a null aggregate, not a zeroed one") {
        val summary = run(
            fourSided("q1", bash = null),
            fourSided("q2", bash = null, category = QuestionCategory.NEGATIVE_CONTROL)
        ).summary!!

        // "bash was not in this run" and "bash ran and scored 0" are different claims, and only
        // the second is evidence about the tool.
        summary.headline.bash shouldBe null
        summary.negativeControl.bash shouldBe null
        summary.byCategory.getValue(QuestionCategory.GRAPH_HEAVY).bash shouldBe null
        summary.byRepo.getValue("keycloak").bash shouldBe null
        // The sides that did run are unaffected.
        summary.headline.ripgrep.measuredCount shouldBe 1
    }

    test("a bash side that ran and found nothing is a real zero, kept distinct from absence") {
        val summary = run(fourSided("q1", bash = foundNothing)).summary!!

        val bash = summary.headline.bash!!
        bash.measuredCount shouldBe 1
        bash.mrr shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("a three-sided result document with no bash key at all still decodes, bash reading absent") {
        // Hand-written rather than generated: this is the literal shape every archived
        // `retrieval-*.json` has on disk, and the point is that no key had to be added to it.
        // ArchivedResultsStillDecodeTest proves the same thing against the real committed files;
        // this pins the shape so a future field cannot quietly become required between archives.
        val threeSided = """
            {
              "schemaVersion": 2,
              "runId": "retrieval-three-way",
              "generatedAt": "2026-08-22T12:00:00Z",
              "kValues": [5],
              "results": [
                {
                  "questionId": "q1",
                  "repoId": "keycloak",
                  "category": "GRAPH_HEAVY",
                  "expectedFiles": ["src/A.kt"],
                  "ripgrepQueryTokens": ["ServeHTTP"],
                  "contextGraph": null,
                  "ripgrep": {
                    "rankedFiles": ["src/A.kt"],
                    "precisionAtK": { "5": 1.0 },
                    "recallAtK": { "5": 1.0 },
                    "reciprocalRank": 1.0
                  },
                  "codeGraph": null
                }
              ]
            }
        """.trimIndent()

        val decoded = RetrievalRun.fromJson(threeSided)

        decoded.schemaVersion shouldBe 2
        decoded.results.single().bash shouldBe null
        decoded.results.single().codeGraph shouldBe null
        // Aggregating a three-sided run yields a null bash aggregate, never a zeroed one.
        RetrievalStats.summarize(decoded.results, decoded.kValues).headline.bash shouldBe null
    }

    test("the fourth side's label carries its qualifier like the other three") {
        // Same rule as RetrievalSchemaV2Test's: a bare word in a table header is what a skimming
        // reader misreads. "bash" also has to say that it is the *base-system* floor, not a shell
        // with tooling installed -- that distinction is the entire point of the fourth side.
        RetrievalSide.BASH.label shouldBe "bash (base-system shell only)"
        RetrievalSide.entries.forEach { (it.label.contains("(") && it.label.contains(")")) shouldBe true }
    }
})
