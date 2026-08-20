package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

private fun side(reciprocalRank: Double, precision: Double = 0.0, recall: Double = 0.0): SideResult =
    SideResult(
        rankedFiles = emptyList(),
        precisionAtK = mapOf(5 to precision),
        recallAtK = mapOf(5 to recall),
        reciprocalRank = reciprocalRank
    )

class RetrievalStatsTest : FunSpec({

    val tolerance = 0.0001

    test("MRR is the mean of per-question reciprocal ranks, separately per side") {
        val results = listOf(
            RetrievalRunResult(
                "q1", "repo", QuestionCategory.GRAPH_HEAVY, listOf("a.kt"), listOf("Foo"),
                contextGraph = side(reciprocalRank = 1.0), ripgrep = side(reciprocalRank = 0.5)
            ),
            RetrievalRunResult(
                "q2", "repo", QuestionCategory.GRAPH_HEAVY, listOf("b.kt"), listOf("Bar"),
                contextGraph = side(reciprocalRank = 0.0), ripgrep = side(reciprocalRank = 0.0)
            )
        )

        val summary = RetrievalStats.summarize(results, kValues = listOf(5))

        summary.headline.contextGraph.mrr shouldBe (0.5 plusOrMinus tolerance) // (1.0 + 0.0) / 2
        summary.headline.ripgrep.mrr shouldBe (0.25 plusOrMinus tolerance) // (0.5 + 0.0) / 2
        summary.headline.questionCount shouldBe 2
    }

    test("a null contextGraph side is excluded from the ContextGraph average, not counted as a zero") {
        val results = listOf(
            RetrievalRunResult(
                "q1", "repo", QuestionCategory.GRAPH_HEAVY, listOf("a.kt"), listOf("Foo"),
                contextGraph = side(reciprocalRank = 1.0), ripgrep = side(reciprocalRank = 1.0)
            ),
            // repo's index failed integrity -- contextGraph is null, ripgrep still measured.
            RetrievalRunResult(
                "q2", "repo", QuestionCategory.GRAPH_HEAVY, listOf("b.kt"), listOf("Bar"),
                contextGraph = null, ripgrep = side(reciprocalRank = 0.0)
            )
        )

        val summary = RetrievalStats.summarize(results, kValues = listOf(5))

        // If the null were counted as a zero, this would be 0.5 instead of 1.0.
        summary.headline.contextGraph.mrr shouldBe (1.0 plusOrMinus tolerance)
        summary.headline.contextGraph.measuredCount shouldBe 1
        summary.headline.ripgrep.measuredCount shouldBe 2
        summary.headline.questionCount shouldBe 2
    }

    test("negative-control questions are excluded from headline and reported separately") {
        val results = listOf(
            RetrievalRunResult(
                "q1", "repo", QuestionCategory.GRAPH_HEAVY, listOf("a.kt"), emptyList(),
                contextGraph = side(reciprocalRank = 1.0), ripgrep = side(reciprocalRank = 1.0)
            ),
            RetrievalRunResult(
                "q2", "repo", QuestionCategory.NEGATIVE_CONTROL, listOf("b.kt"), emptyList(),
                contextGraph = side(reciprocalRank = 0.0), ripgrep = side(reciprocalRank = 1.0)
            )
        )

        val summary = RetrievalStats.summarize(results, kValues = listOf(5))

        summary.headline.questionCount shouldBe 1
        summary.negativeControl.questionCount shouldBe 1
        summary.negativeControl.ripgrep.mrr shouldBe (1.0 plusOrMinus tolerance)
        summary.byCategory[QuestionCategory.NEGATIVE_CONTROL]?.questionCount shouldBe 1
        summary.byCategory[QuestionCategory.GRAPH_HEAVY]?.questionCount shouldBe 1
        summary.byCategory[QuestionCategory.NEUTRAL]?.questionCount shouldBe 0
    }

    test("byRepo groups by repoId") {
        val results = listOf(
            RetrievalRunResult(
                "q1", "repoA", QuestionCategory.NEUTRAL, listOf("a.kt"), emptyList(),
                contextGraph = side(reciprocalRank = 1.0), ripgrep = side(reciprocalRank = 1.0)
            ),
            RetrievalRunResult(
                "q2", "repoB", QuestionCategory.NEUTRAL, listOf("b.kt"), emptyList(),
                contextGraph = side(reciprocalRank = 0.0), ripgrep = side(reciprocalRank = 0.0)
            )
        )

        val summary = RetrievalStats.summarize(results, kValues = listOf(5))

        summary.byRepo.keys shouldBe setOf("repoA", "repoB")
        summary.byRepo.getValue("repoA").questionCount shouldBe 1
        summary.byRepo.getValue("repoB").questionCount shouldBe 1
    }

    test("an empty result list yields an all-zero summary, not a crash") {
        val summary = RetrievalStats.summarize(emptyList(), kValues = listOf(5, 10))

        summary.headline.questionCount shouldBe 0
        summary.headline.contextGraph.mrr shouldBe (0.0 plusOrMinus tolerance)
        summary.headline.contextGraph.meanPrecisionAtK shouldBe mapOf(5 to 0.0, 10 to 0.0)
    }
})
