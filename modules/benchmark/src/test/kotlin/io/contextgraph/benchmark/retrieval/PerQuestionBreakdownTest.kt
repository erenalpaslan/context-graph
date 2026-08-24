package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant

/**
 * The per-question projection, tested at the seam its two consumers share.
 *
 * [RetrievalReportGenerator] renders it as a markdown table and the documentation site renders it
 * as an interactive page; the numbers in those two surfaces must agree, and the only way to
 * guarantee that is for neither of them to derive its own. So the derivation is tested here, once,
 * against hand-worked ranked lists whose expected ranks are countable by eye.
 */
private fun side(vararg ranked: String) =
    SideResult(ranked.toList(), mapOf(5 to 0.0), mapOf(5 to 0.0), 0.0)

private fun runOf(vararg results: RetrievalRunResult) = RetrievalRun(
    runId = "retrieval-projection-fixture",
    generatedAt = Instant.parse("2026-08-24T00:00:00Z"),
    kValues = listOf(5),
    results = results.toList(),
    summary = RetrievalStats.summarize(results.toList(), listOf(5))
)

class PerQuestionBreakdownTest : FunSpec({

    test("the first gold hit's rank is 1-based and counted over de-duplicated files") {
        // `a.kt` twice, then `miss.kt`, then the gold file: a reader counting distinct files sees
        // the gold file at rank 3, and RetrievalMetrics scores it that way too (it de-duplicates
        // before scoring), so a projection counting raw positions would report rank 4 and disagree
        // with the reciprocal rank printed beside it.
        val run = runOf(
            RetrievalRunResult(
                questionId = "alpha-q1",
                repoId = "alpha",
                category = QuestionCategory.GRAPH_HEAVY,
                expectedFiles = listOf("gold.kt"),
                ripgrepQueryTokens = listOf("resolve"),
                contextGraph = side("a.kt", "a.kt", "miss.kt", "gold.kt"),
                ripgrep = side("gold.kt")
            )
        )

        val question = PerQuestionBreakdown.project(run).single()

        question.scoreFor(RetrievalSide.CONTEXT_GRAPH)?.firstGoldHitRank shouldBe 3
        question.scoreFor(RetrievalSide.RIPGREP)?.firstGoldHitRank shouldBe 1
        // The answer is carried in the same de-duplicated form the rank counts over, so a surface
        // showing the list beneath the rank shows the list the rank refers to.
        question.scoreFor(RetrievalSide.CONTEXT_GRAPH)?.rankedFiles shouldBe
            listOf("a.kt", "miss.kt", "gold.kt")
    }

    test("a side that ran and found nothing has no rank; a side that did not run has no score") {
        // The distinction the aggregates already keep, kept here too: `null` score means the side
        // was never measured, while a measured side with a null rank ran and missed. Collapsing
        // them would let an absent side read as a zero-scoring one.
        val run = runOf(
            RetrievalRunResult(
                questionId = "alpha-q1",
                repoId = "alpha",
                category = QuestionCategory.GRAPH_HEAVY,
                expectedFiles = listOf("gold.kt"),
                ripgrepQueryTokens = listOf("resolve"),
                contextGraph = side("miss.kt"),
                ripgrep = side("gold.kt"),
                codeGraph = null,
                bash = null
            )
        )

        val question = PerQuestionBreakdown.project(run).single()

        question.scoreFor(RetrievalSide.CONTEXT_GRAPH)!!.firstGoldHitRank.shouldBeNull()
        question.scoreFor(RetrievalSide.CODE_GRAPH).shouldBeNull()
        question.scoreFor(RetrievalSide.BASH).shouldBeNull()
        question.measured.map { it.side } shouldBe listOf(RetrievalSide.CONTEXT_GRAPH, RetrievalSide.RIPGREP)
    }

    test("questions come out grouped by repo and ordered by question id, never by score") {
        val run = runOf(
            result("beta-q2", "beta", contextGraph = side("gold.kt")),
            result("alpha-q2", "alpha", contextGraph = side("gold.kt")),
            result("alpha-q1", "alpha", contextGraph = side("miss.kt"))
        )

        PerQuestionBreakdown.project(run).map { it.questionId } shouldBe
            listOf("alpha-q1", "alpha-q2", "beta-q2")
        PerQuestionBreakdown.byRepo(run).keys.toList() shouldBe listOf("alpha", "beta")
        PerQuestionBreakdown.byRepo(run).getValue("alpha").map { it.questionId } shouldBe
            listOf("alpha-q1", "alpha-q2")
    }

    test("a question every measured side missed is flagged; an unmeasured side does not make one") {
        val allMissed = runOf(result("alpha-q1", "alpha", contextGraph = side("miss.kt"), ripgrep = side("miss.kt")))
        PerQuestionBreakdown.project(allMissed).single().missedByEverySide shouldBe true

        // Three of the four sides absent is not "every side missed" -- absence is not a miss, the
        // same distinction the aggregates keep. One measured side finding the file clears the flag.
        val oneFound = runOf(result("alpha-q1", "alpha", ripgrep = side("gold.kt")))
        PerQuestionBreakdown.project(oneFound).single().missedByEverySide shouldBe false
    }

    test("the leaders are every side tied at the shallowest gold hit, and none when all missed") {
        val tied = runOf(
            result(
                "alpha-q1", "alpha",
                contextGraph = side("gold.kt"),
                codeGraph = side("miss.kt", "gold.kt"),
                bash = side("gold.kt"),
                ripgrep = side("miss.kt")
            )
        )
        PerQuestionBreakdown.project(tied).single().leaders.map { it.side } shouldBe
            listOf(RetrievalSide.CONTEXT_GRAPH, RetrievalSide.BASH)

        val allMissed = runOf(result("alpha-q1", "alpha", contextGraph = side("miss.kt"), ripgrep = side("miss.kt")))
        PerQuestionBreakdown.project(allMissed).single().leaders shouldBe emptyList()
    }
})

private fun result(
    questionId: String,
    repoId: String,
    contextGraph: SideResult? = null,
    codeGraph: SideResult? = null,
    bash: SideResult? = null,
    ripgrep: SideResult = side("miss.kt")
) = RetrievalRunResult(
    questionId = questionId,
    repoId = repoId,
    category = QuestionCategory.GRAPH_HEAVY,
    expectedFiles = listOf("gold.kt"),
    ripgrepQueryTokens = listOf("resolve"),
    contextGraph = contextGraph,
    ripgrep = ripgrep,
    codeGraph = codeGraph,
    bash = bash
)
