package io.contextgraph.eval

import kotlinx.serialization.Serializable

/**
 * The full, re-generatable output of one `explore`-side eval pass: enough to reconstruct the
 * per-question report table without re-running anything, plus [rungDistribution] (slice 10's
 * per-language `Calls` resolution breakdown) since the task file calls out recording which
 * confidence floor blast radius used and whether results are sensitive to it.
 */
@Serializable
data class ExploreEvalReport(
    val generatedAt: String,
    val dbPath: String,
    val projectRoot: String,
    val litellmEnabled: Boolean,
    val blastRadiusConfidenceFloor: Double,
    val rungDistribution: Map<String, Map<String, Int>>,
    val results: List<ExploreGradeResult>
)
