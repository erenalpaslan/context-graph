package io.contextgraph.benchmark.questions

import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Locale
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

/** AC-5 target distribution, per repo over ~8 questions. */
private val TARGET_RATIOS: Map<QuestionCategory, Double> = mapOf(
    QuestionCategory.GRAPH_HEAVY to 0.60,
    QuestionCategory.NEUTRAL to 0.25,
    QuestionCategory.NEGATIVE_CONTROL to 0.15
)

/**
 * Default tolerance, in absolute percentage points, before a repo's category
 * distribution is flagged as deviating from [TARGET_RATIOS]. Generous
 * because repo-sized samples (~8 questions, per AC-5) can't hit the target
 * ratios exactly — one question is already 12.5 points.
 */
const val DEFAULT_CATEGORY_TOLERANCE = 0.20

/**
 * Result of auditing one repo's question set against AC-5's target ratios.
 *
 * [withinTolerance] and [missingTargetCategories] are deliberately separate
 * axes, not one blended score: aggregate drift (a few points off 60/25/15
 * overall) is forgivable at ~8-question sample sizes and is what
 * [withinTolerance] measures against [DEFAULT_CATEGORY_TOLERANCE]. A target
 * category dropping to **zero** — e.g. no negative controls at all — is not
 * a matter of degree and is caught by [missingTargetCategories] regardless
 * of how wide the tolerance band is set, so nudging the tolerance constant
 * can never silently hide a whole category going missing. [passes] is the
 * overall verdict combining both.
 */
data class CategoryDistributionReport(
    val repoId: String,
    val total: Int,
    val counts: Map<QuestionCategory, Int>,
    val ratios: Map<QuestionCategory, Double>,
    val deviations: Map<QuestionCategory, Double>,
    val withinTolerance: Boolean,
    val missingTargetCategories: Set<QuestionCategory>
) {
    /** Overall audit verdict: aggregate drift within band AND no target category zeroed out. */
    val passes: Boolean get() = withinTolerance && missingTargetCategories.isEmpty()

    /** Human-readable summary, used for the warning log and available for CLI/report output. */
    fun describe(): String = buildString {
        appendLine("Category distribution for repo '$repoId' ($total questions):")
        for (category in QuestionCategory.entries) {
            val ratio = ratios[category] ?: 0.0
            val target = TARGET_RATIOS[category] ?: 0.0
            appendLine(
                "  ${category.name}: ${pct(ratio)}% (target ${pct(target)}%, count=${counts[category] ?: 0})"
            )
        }
        if (!withinTolerance) {
            appendLine("  DEVIATES from target by more than ${pct(DEFAULT_CATEGORY_TOLERANCE)} points")
        }
        if (missingTargetCategories.isNotEmpty()) {
            appendLine(
                "  MISSING category entirely (zero questions): " +
                    missingTargetCategories.joinToString { it.name }
            )
        }
    }

    private fun pct(ratio: Double): String = String.format(Locale.ROOT, "%.1f", ratio * 100)
}

/**
 * Audits a question set against AC-5's target category distribution (~60%
 * graph-heavy / ~25% neutral / ~15% negative-control per repo). Never throws
 * — a deviation is reported, not silently dropped (task 03), and callers
 * decide whether that deviation should be fatal for their use case.
 */
object CategoryDistributionAuditor {

    fun audit(
        questions: List<Question>,
        repoId: String,
        tolerance: Double = DEFAULT_CATEGORY_TOLERANCE
    ): CategoryDistributionReport {
        val scoped = questions.filter { it.repoId == repoId }
        val total = scoped.size
        val counts = QuestionCategory.entries.associateWith { cat -> scoped.count { it.category == cat } }
        val ratios = if (total == 0) {
            QuestionCategory.entries.associateWith { 0.0 }
        } else {
            counts.mapValues { (_, count) -> count.toDouble() / total }
        }
        val deviations = QuestionCategory.entries.associateWith { cat ->
            abs((ratios[cat] ?: 0.0) - (TARGET_RATIOS[cat] ?: 0.0))
        }
        val withinTolerance = total > 0 && deviations.values.all { it <= tolerance }
        // Independent of `tolerance`: a target category (positive target ratio) with
        // zero questions is always reported, no matter how wide the band is set.
        val missingTargetCategories = if (total == 0) {
            emptySet()
        } else {
            QuestionCategory.entries
                .filter { cat -> (TARGET_RATIOS[cat] ?: 0.0) > 0.0 && (counts[cat] ?: 0) == 0 }
                .toSet()
        }

        val report = CategoryDistributionReport(
            repoId, total, counts, ratios, deviations, withinTolerance, missingTargetCategories
        )
        if (!report.passes) {
            logger.warn { report.describe() }
        }
        return report
    }

    /** Audits every repoId present in [questions], one report per repo. */
    fun auditAll(
        questions: List<Question>,
        tolerance: Double = DEFAULT_CATEGORY_TOLERANCE
    ): Map<String, CategoryDistributionReport> =
        questions.map { it.repoId }.distinct().associateWith { repoId -> audit(questions, repoId, tolerance) }
}
