package io.contextgraph.benchmark.questions

import io.contextgraph.benchmark.model.Evidence
import io.contextgraph.benchmark.model.GoldFact
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * AC-5: repo-level category distribution (~60% graph-heavy / ~25% neutral /
 * ~15% negative-control over ~8 questions) must be auditable, and a
 * deviation from target must be reported, not silently ignored.
 */
class CategoryDistributionAuditorTest : FunSpec({

    fun fact(id: String, line: Int) =
        GoldFact(id = id, statement = "statement $id", evidence = Evidence("src/File.kt", line))

    fun question(id: String, repoId: String, category: QuestionCategory) = Question(
        id = id,
        repoId = repoId,
        text = "question $id",
        category = category,
        goldFacts = listOf(fact("$id-f1", 1), fact("$id-f2", 2), fact("$id-f3", 3))
    )

    test("a set matching the target ratios is reported within tolerance") {
        // 8 questions: 5 graph-heavy (62.5%), 2 neutral (25%), 1 negative-control (12.5%)
        val questions = listOf(
            question("q1", "excalidraw", QuestionCategory.GRAPH_HEAVY),
            question("q2", "excalidraw", QuestionCategory.GRAPH_HEAVY),
            question("q3", "excalidraw", QuestionCategory.GRAPH_HEAVY),
            question("q4", "excalidraw", QuestionCategory.GRAPH_HEAVY),
            question("q5", "excalidraw", QuestionCategory.GRAPH_HEAVY),
            question("q6", "excalidraw", QuestionCategory.NEUTRAL),
            question("q7", "excalidraw", QuestionCategory.NEUTRAL),
            question("q8", "excalidraw", QuestionCategory.NEGATIVE_CONTROL)
        )

        val report = CategoryDistributionAuditor.audit(questions, repoId = "excalidraw")

        report.total shouldBe 8
        report.withinTolerance shouldBe true
    }

    test("a set skewed heavily toward one category is flagged as deviating") {
        // All 8 graph-heavy: 100% vs a 60% target is far outside any reasonable tolerance.
        val questions = (1..8).map { question("q$it", "excalidraw", QuestionCategory.GRAPH_HEAVY) }

        val report = CategoryDistributionAuditor.audit(questions, repoId = "excalidraw")

        report.withinTolerance shouldBe false
        report.counts[QuestionCategory.GRAPH_HEAVY] shouldBe 8
        report.counts[QuestionCategory.NEGATIVE_CONTROL] shouldBe 0
    }

    test("audit is scoped to the requested repoId, ignoring other repos") {
        val questions = listOf(
            question("q1", "excalidraw", QuestionCategory.GRAPH_HEAVY),
            question("q2", "gin", QuestionCategory.NEGATIVE_CONTROL)
        )

        val report = CategoryDistributionAuditor.audit(questions, repoId = "excalidraw")

        report.total shouldBe 1
        report.counts[QuestionCategory.GRAPH_HEAVY] shouldBe 1
    }

    test("a target category dropping to zero fails the audit even though aggregate drift is within tolerance") {
        // 6 graph-heavy / 2 neutral / 0 negative-control = 75% / 25% / 0%.
        // Every per-category deviation (15, 0, 15 points) sits under the default
        // 20-point band, so the tolerance check alone would pass this silently.
        // A whole category going missing must be caught regardless.
        val questions = listOf(
            question("q1", "excalidraw", QuestionCategory.GRAPH_HEAVY),
            question("q2", "excalidraw", QuestionCategory.GRAPH_HEAVY),
            question("q3", "excalidraw", QuestionCategory.GRAPH_HEAVY),
            question("q4", "excalidraw", QuestionCategory.GRAPH_HEAVY),
            question("q5", "excalidraw", QuestionCategory.GRAPH_HEAVY),
            question("q6", "excalidraw", QuestionCategory.GRAPH_HEAVY),
            question("q7", "excalidraw", QuestionCategory.NEUTRAL),
            question("q8", "excalidraw", QuestionCategory.NEUTRAL)
        )

        val report = CategoryDistributionAuditor.audit(questions, repoId = "excalidraw")

        report.withinTolerance shouldBe true // the aggregate-drift band alone does pass this
        report.missingTargetCategories shouldBe setOf(QuestionCategory.NEGATIVE_CONTROL)
        report.passes shouldBe false // but the audit as a whole must not
    }

    test("a zeroed target category still fails the audit no matter how wide the tolerance is set") {
        // Proves the zero-count check is not just another tolerance band that a
        // future edit to DEFAULT_CATEGORY_TOLERANCE could quietly widen past.
        val questions = listOf(
            question("q1", "excalidraw", QuestionCategory.GRAPH_HEAVY),
            question("q2", "excalidraw", QuestionCategory.GRAPH_HEAVY),
            question("q3", "excalidraw", QuestionCategory.NEUTRAL)
        )

        val report = CategoryDistributionAuditor.audit(questions, repoId = "excalidraw", tolerance = 0.99)

        report.withinTolerance shouldBe true
        report.missingTargetCategories shouldBe setOf(QuestionCategory.NEGATIVE_CONTROL)
        report.passes shouldBe false
    }

    test("a set with every target category represented has no missing categories") {
        val questions = listOf(
            question("q1", "excalidraw", QuestionCategory.GRAPH_HEAVY),
            question("q2", "excalidraw", QuestionCategory.NEUTRAL),
            question("q3", "excalidraw", QuestionCategory.NEGATIVE_CONTROL)
        )

        val report = CategoryDistributionAuditor.audit(questions, repoId = "excalidraw")

        report.missingTargetCategories shouldBe emptySet()
    }
})
