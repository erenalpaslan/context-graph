package io.contextgraph.benchmark.retrieval

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Known input/output pairs for [RetrievalMetrics] -- the AC-24 requirement that
 * "Metrik hesapları (precision/recall/MRR) bilinen girdi-çıktı çiftleriyle birim testinden
 * geçiyor" (metric calculations pass a unit test with known input/output pairs).
 */
class RetrievalMetricsTest : FunSpec({

    val tolerance = 0.0001

    context("precisionAtK") {
        test("2 of top 4 in expected -> precision@4 = 0.5") {
            val ranked = listOf("a.kt", "b.kt", "c.kt", "d.kt")
            val expected = setOf("a.kt", "c.kt", "z.kt")
            RetrievalMetrics.precisionAtK(ranked, expected, 4) shouldBe (0.5 plusOrMinus tolerance)
        }

        test("all top-k hits -> precision@k = 1.0") {
            val ranked = listOf("a.kt", "b.kt")
            val expected = setOf("a.kt", "b.kt", "c.kt")
            RetrievalMetrics.precisionAtK(ranked, expected, 2) shouldBe (1.0 plusOrMinus tolerance)
        }

        test("no hits -> precision@k = 0.0") {
            val ranked = listOf("a.kt", "b.kt")
            val expected = setOf("z.kt")
            RetrievalMetrics.precisionAtK(ranked, expected, 2) shouldBe (0.0 plusOrMinus tolerance)
        }

        test("fewer results than k -- unfilled slots count as misses, divides by k not by result count") {
            val ranked = listOf("a.kt")
            val expected = setOf("a.kt")
            RetrievalMetrics.precisionAtK(ranked, expected, 5) shouldBe (0.2 plusOrMinus tolerance)
        }

        test("empty ranked list -> precision@k = 0.0") {
            RetrievalMetrics.precisionAtK(emptyList(), setOf("a.kt"), 5) shouldBe (0.0 plusOrMinus tolerance)
        }

        test("duplicate paths in ranked list count once") {
            val ranked = listOf("a.kt", "a.kt", "a.kt", "b.kt")
            val expected = setOf("a.kt")
            // distinct() -> ["a.kt", "b.kt"]; top-2 has 1 hit; precision@2 = 0.5
            RetrievalMetrics.precisionAtK(ranked, expected, 2) shouldBe (0.5 plusOrMinus tolerance)
        }

        test("k must be positive") {
            runCatching { RetrievalMetrics.precisionAtK(listOf("a.kt"), setOf("a.kt"), 0) }
                .isFailure shouldBe true
        }
    }

    context("recallAtK") {
        test("2 of 4 expected found in top-k -> recall@k = 0.5") {
            val ranked = listOf("a.kt", "z.kt", "c.kt")
            val expected = setOf("a.kt", "b.kt", "c.kt", "d.kt")
            RetrievalMetrics.recallAtK(ranked, expected, 3) shouldBe (0.5 plusOrMinus tolerance)
        }

        test("every expected file found -> recall@k = 1.0") {
            val ranked = listOf("a.kt", "b.kt", "z.kt")
            val expected = setOf("a.kt", "b.kt")
            RetrievalMetrics.recallAtK(ranked, expected, 3) shouldBe (1.0 plusOrMinus tolerance)
        }

        test("expected file beyond k is not counted") {
            val ranked = listOf("z.kt", "y.kt", "a.kt")
            val expected = setOf("a.kt")
            RetrievalMetrics.recallAtK(ranked, expected, 2) shouldBe (0.0 plusOrMinus tolerance)
        }

        test("empty expected set -> recall@k = 1.0 (nothing to find, nothing missed)") {
            RetrievalMetrics.recallAtK(listOf("a.kt"), emptySet(), 5) shouldBe (1.0 plusOrMinus tolerance)
        }
    }

    context("reciprocalRank") {
        test("first ranked item is the hit -> RR = 1.0") {
            RetrievalMetrics.reciprocalRank(listOf("a.kt", "b.kt"), setOf("a.kt")) shouldBe (1.0 plusOrMinus tolerance)
        }

        test("hit at rank 3 -> RR = 1/3") {
            val ranked = listOf("x.kt", "y.kt", "a.kt", "b.kt")
            RetrievalMetrics.reciprocalRank(ranked, setOf("a.kt")) shouldBe (1.0 / 3.0 plusOrMinus tolerance)
        }

        test("no hit anywhere -> RR = 0.0") {
            RetrievalMetrics.reciprocalRank(listOf("x.kt", "y.kt"), setOf("a.kt")) shouldBe (0.0 plusOrMinus tolerance)
        }

        test("empty ranked list -> RR = 0.0") {
            RetrievalMetrics.reciprocalRank(emptyList(), setOf("a.kt")) shouldBe (0.0 plusOrMinus tolerance)
        }

        test("duplicates before the first real hit don't change the rank") {
            val ranked = listOf("x.kt", "x.kt", "x.kt", "a.kt")
            // distinct() -> ["x.kt", "a.kt"]; "a.kt" is at rank 2
            RetrievalMetrics.reciprocalRank(ranked, setOf("a.kt")) shouldBe (0.5 plusOrMinus tolerance)
        }
    }
})
