package io.contextgraph.benchmark.stats

import io.contextgraph.benchmark.model.MetricSummary

/**
 * Summarizes a metric across a group of raw values into median + min/max
 * (AC-15). Returns `null` for an empty group rather than `NaN` — callers
 * (and `07`'s formatter downstream) should be able to tell "no data" apart
 * from "a real zero" without inspecting a floating point value.
 *
 * Median definition, pinned for the even-sample-size case — the suite's
 * default repeat count
 * ([io.contextgraph.benchmark.model.BenchmarkConfig.repeatsPerArm]) is 4, an
 * even number, where "the median" is otherwise ambiguous: sort the values
 * and average the two middle elements. For an odd sample size this reduces
 * to the standard single middle element. See `MetricAggregationTest` for the
 * pinned known-input/known-output cases.
 */
fun summarizeMetric(values: List<Double>): MetricSummary? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val n = sorted.size
    val median = if (n % 2 == 0) {
        (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    } else {
        sorted[n / 2]
    }
    return MetricSummary(median = median, min = sorted.first(), max = sorted.last(), sampleSize = n)
}
