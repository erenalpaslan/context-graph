package io.contextgraph.benchmark.stats

import io.contextgraph.benchmark.model.BreakEvenResult
import io.contextgraph.benchmark.model.MetricSummary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun cost(median: Double) = MetricSummary(median = median, min = median, max = median, sampleSize = 4)

class BreakEvenTest : FunSpec({

    test("pinned: a positive per-question saving amortizes the ingest cost over a known number of questions") {
        val result = computeBreakEven(
            ingestCostUsd = 10.0,
            withToolsCost = cost(1.0),
            withoutToolsCost = cost(3.0)
        )
        val amortizes = result.shouldBeInstanceOf<BreakEvenResult.Amortizes>()
        amortizes.ingestCostUsd shouldBe 10.0
        amortizes.savingPerQuestionUsd shouldBe 2.0
        amortizes.questionsToBreakEven shouldBe (5.0 plusOrMinus 1e-9) // 10.0 / 2.0
    }

    test("zero saving does not divide and is reported as not amortizing") {
        val result = computeBreakEven(
            ingestCostUsd = 10.0,
            withToolsCost = cost(2.0),
            withoutToolsCost = cost(2.0)
        )
        val notAmortizing = result.shouldBeInstanceOf<BreakEvenResult.NotAmortizing>()
        notAmortizing.savingPerQuestionUsd shouldBe 0.0
        notAmortizing.ingestCostUsd shouldBe 10.0
    }

    test("negative saving (WITH_TOOLS costs more per question) does not divide and is reported as not amortizing") {
        val result = computeBreakEven(
            ingestCostUsd = 10.0,
            withToolsCost = cost(5.0),
            withoutToolsCost = cost(2.0)
        )
        val notAmortizing = result.shouldBeInstanceOf<BreakEvenResult.NotAmortizing>()
        notAmortizing.savingPerQuestionUsd shouldBe -3.0
    }

    test("missing WITH_TOOLS cost data yields InsufficientData rather than an exception") {
        val result = computeBreakEven(ingestCostUsd = 10.0, withToolsCost = null, withoutToolsCost = cost(2.0))
        result.shouldBeInstanceOf<BreakEvenResult.InsufficientData>()
    }

    test("missing WITHOUT_TOOLS cost data yields InsufficientData rather than an exception") {
        val result = computeBreakEven(ingestCostUsd = 10.0, withToolsCost = cost(2.0), withoutToolsCost = null)
        result.shouldBeInstanceOf<BreakEvenResult.InsufficientData>()
    }

    test("missing cost data on both arms yields InsufficientData") {
        val result = computeBreakEven(ingestCostUsd = 10.0, withToolsCost = null, withoutToolsCost = null)
        result.shouldBeInstanceOf<BreakEvenResult.InsufficientData>()
    }
})
