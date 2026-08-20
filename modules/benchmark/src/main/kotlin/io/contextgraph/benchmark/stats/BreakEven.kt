package io.contextgraph.benchmark.stats

import io.contextgraph.benchmark.model.BreakEvenResult
import io.contextgraph.benchmark.model.MetricSummary

/**
 * AC-21: "ingest cost amortizes in N questions". Compares a repo's ingest
 * cost against the per-question cost saving the WITH_TOOLS arm shows over
 * WITHOUT_TOOLS, using each arm's median [MetricSummary.costUsd].
 *
 * Never divides when the saving is zero or negative — task 06's notes
 * require that case to be stated plainly rather than attempted as a
 * division. [BreakEvenResult.NotAmortizing] carries the (non-positive)
 * saving so a caller can still see how far off amortizing the repo is,
 * without a `questionsToBreakEven` value that would otherwise be infinite
 * or negative.
 */
fun computeBreakEven(
    ingestCostUsd: Double,
    withToolsCost: MetricSummary?,
    withoutToolsCost: MetricSummary?
): BreakEvenResult {
    if (withToolsCost == null || withoutToolsCost == null) {
        return BreakEvenResult.InsufficientData(
            reason = "missing WITH_TOOLS or WITHOUT_TOOLS cost data for this repo"
        )
    }

    val savingPerQuestionUsd = withoutToolsCost.median - withToolsCost.median
    if (savingPerQuestionUsd <= 0.0) {
        return BreakEvenResult.NotAmortizing(
            ingestCostUsd = ingestCostUsd,
            savingPerQuestionUsd = savingPerQuestionUsd
        )
    }

    return BreakEvenResult.Amortizes(
        ingestCostUsd = ingestCostUsd,
        savingPerQuestionUsd = savingPerQuestionUsd,
        questionsToBreakEven = ingestCostUsd / savingPerQuestionUsd
    )
}
