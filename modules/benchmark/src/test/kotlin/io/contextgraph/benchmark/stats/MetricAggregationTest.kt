package io.contextgraph.benchmark.stats

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the median definition for the even-sample-size case (AC-15, task
 * 06's notes): average of the two middle values once sorted. 4 is the
 * suite's default repeat count, so the 4-value case is the load-bearing one.
 */
class MetricAggregationTest : FunSpec({

    test("returns null for an empty group rather than NaN") {
        summarizeMetric(emptyList()) shouldBe null
    }

    test("a single value is its own median, min and max") {
        val summary = summarizeMetric(listOf(7.0))!!
        summary.median shouldBe 7.0
        summary.min shouldBe 7.0
        summary.max shouldBe 7.0
        summary.sampleSize shouldBe 1
    }

    test("odd sample size (3) uses the single middle element") {
        val summary = summarizeMetric(listOf(9.0, 1.0, 5.0))!!
        summary.median shouldBe 5.0
        summary.min shouldBe 1.0
        summary.max shouldBe 9.0
        summary.sampleSize shouldBe 3
    }

    test("pinned: even sample size (4, the suite's default repeat count) averages the two middle values") {
        val summary = summarizeMetric(listOf(40.0, 10.0, 30.0, 20.0))!!
        summary.median shouldBe 25.0 // (20 + 30) / 2, not 20.0 or 30.0
        summary.min shouldBe 10.0
        summary.max shouldBe 40.0
        summary.sampleSize shouldBe 4
    }

    test("even sample size with duplicate middle values still averages them") {
        val summary = summarizeMetric(listOf(2.0, 2.0, 2.0, 2.0))!!
        summary.median shouldBe 2.0
        summary.min shouldBe 2.0
        summary.max shouldBe 2.0
        summary.sampleSize shouldBe 4
    }

    test("input order does not affect the result") {
        val ascending = summarizeMetric(listOf(1.0, 2.0, 3.0, 4.0))!!
        val shuffled = summarizeMetric(listOf(3.0, 1.0, 4.0, 2.0))!!
        shuffled shouldBe ascending
    }
})
