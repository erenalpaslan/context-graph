package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * Median + variance (min/max) of one metric across a group of repeats
 * (AC-15). [sampleSize] is the count of values the summary was built from —
 * carried explicitly so a reader never has to guess whether a small number
 * behind a median is 4 repeats or 1.
 *
 * [median] uses the "average the two middle values" definition for an even
 * sample size, pinned here because [BenchmarkConfig.repeatsPerArm] defaults
 * to 4 — an even number, where "the median" is otherwise ambiguous. See
 * `io.contextgraph.benchmark.stats.summarizeMetric` for the function that
 * builds this and `MetricSummaryTest` for the pinned example.
 */
@Serializable
data class MetricSummary(
    val median: Double,
    val min: Double,
    val max: Double,
    val sampleSize: Int
)
