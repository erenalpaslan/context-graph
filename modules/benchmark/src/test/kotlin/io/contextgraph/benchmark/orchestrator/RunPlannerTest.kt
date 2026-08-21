package io.contextgraph.benchmark.orchestrator

import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.Evidence
import io.contextgraph.benchmark.model.GoldFact
import io.contextgraph.benchmark.model.Profile
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Proves `full`'s scope (AC-17: 4 repos x ~8 questions x 2 arms x 4 repeats ~= 256) and `smoke`'s
 * scope (AC-16: 1 repo x 3 questions x 2 arms x 1 repeat = 6) *without running either* -- these
 * are pure functions over in-memory data, so "test full's planning without executing it"
 * (task 12's explicit instruction) is exactly what this file does.
 */
class RunPlannerTest : FunSpec({

    fun repo(id: String) = CorpusRepo(id = id, name = id, url = "https://example.invalid/$id.git", pinnedTag = "v1", pinnedSha = "0".repeat(40))

    fun fact(id: String) = GoldFact(id = id, statement = "statement", evidence = Evidence("src/Foo.kt", 1))

    fun question(repoId: String, index: Int, category: QuestionCategory) = Question(
        id = "$repoId-q$index",
        repoId = repoId,
        text = "question $index for $repoId",
        category = category,
        goldFacts = listOf(fact("f1"), fact("f2"), fact("f3"))
    )

    /** Mirrors the real gold sets: 8 questions per repo, 5 GRAPH_HEAVY / 2 NEUTRAL / 1 NEGATIVE_CONTROL. */
    fun eightQuestionsFor(repoId: String): List<Question> {
        val categories = listOf(
            QuestionCategory.GRAPH_HEAVY, QuestionCategory.GRAPH_HEAVY, QuestionCategory.GRAPH_HEAVY,
            QuestionCategory.GRAPH_HEAVY, QuestionCategory.GRAPH_HEAVY,
            QuestionCategory.NEUTRAL, QuestionCategory.NEUTRAL,
            QuestionCategory.NEGATIVE_CONTROL
        )
        return categories.mapIndexed { i, cat -> question(repoId, i + 1, cat) }
    }

    val fourRepoCatalog = listOf(repo("excalidraw"), repo("gin"), repo("calcom"), repo("keycloak"))
    val allQuestions = fourRepoCatalog.flatMap { eightQuestionsFor(it.id) }

    test("full only plans the repos its questions actually reference, so --questions-dir scopes the corpus too") {
        // Measured cost of not doing this: a full run whose questions covered three repos still
        // cloned and indexed the fourth (keycloak, the largest), spending ~55 minutes before the
        // first agent run and producing nothing that reached a result.
        val config = RunPlanner.configFor(Profile.FULL)
        val excalidrawOnly = eightQuestionsFor("excalidraw")

        val plan = RunPlanner.plan(Profile.FULL, fourRepoCatalog, excalidrawOnly, config)

        plan.repos.map { it.id } shouldBe listOf("excalidraw")
        plan.questions.map { it.repoId }.toSet() shouldBe setOf("excalidraw")
        plan.totalRuns shouldBe 8 * 2 * config.repeatsPerArm
    }

    test("AC-16: smoke plans exactly 1 repo x 3 questions x 2 arms x 1 repeat = 6 runs") {
        val config = RunPlanner.configFor(Profile.SMOKE)
        val plan = RunPlanner.plan(Profile.SMOKE, fourRepoCatalog, allQuestions, config)

        plan.repos.map { it.id } shouldBe listOf("gin")
        plan.questions shouldHaveSize 3
        plan.totalRuns shouldBe 6
        config.repeatsPerArm shouldBe 1
    }

    test("AC-16: smoke's plan covers both arms for each of its 3 questions, once each") {
        val config = RunPlanner.configFor(Profile.SMOKE)
        val plan = RunPlanner.plan(Profile.SMOKE, fourRepoCatalog, allQuestions, config)

        val combos = plan.plannedRuns.map { Triple(it.question.id, it.arm, it.repeatIndex) }
        combos.toSet() shouldHaveSize 6 // no duplicate (question, arm, repeat) combination
        plan.plannedRuns.count { it.arm == Arm.WITH_TOOLS } shouldBe 3
        plan.plannedRuns.count { it.arm == Arm.WITHOUT_TOOLS } shouldBe 3
    }

    test("AC-17: full plans exactly 4 repos x 8 questions x 2 arms x 4 repeats = 256 runs") {
        val config = RunPlanner.configFor(Profile.FULL)
        val plan = RunPlanner.plan(Profile.FULL, fourRepoCatalog, allQuestions, config)

        plan.repos.map { it.id } shouldContainExactlyInAnyOrder listOf("excalidraw", "gin", "calcom", "keycloak")
        plan.questions shouldHaveSize 32 // 4 repos x 8 questions, none dropped
        plan.totalRuns shouldBe 256
        config.repeatsPerArm shouldBe 4
    }

    test("AC-17: full's plan covers every (repo, question, arm) combination with exactly 4 repeats each") {
        val config = RunPlanner.configFor(Profile.FULL)
        val plan = RunPlanner.plan(Profile.FULL, fourRepoCatalog, allQuestions, config)

        val byQuestionArm = plan.plannedRuns.groupBy { it.question.id to it.arm }
        byQuestionArm.keys shouldHaveSize 64 // 32 questions x 2 arms
        byQuestionArm.values.all { it.size == 4 } shouldBe true
        byQuestionArm.values.all { group -> group.map { it.repeatIndex }.toSet() == setOf(0, 1, 2, 3) } shouldBe true

        // Every repo's questions are actually present in the plan -- no repo silently dropped.
        val repoIdsCovered = plan.plannedRuns.map { it.question.repoId }.toSet()
        repoIdsCovered shouldContainExactlyInAnyOrder listOf("excalidraw", "gin", "calcom", "keycloak")
    }

    test("smoke and full both always run both arms -- neither profile ever plans a one-armed run") {
        val smokeConfig = RunPlanner.configFor(Profile.SMOKE)
        val smokePlan = RunPlanner.plan(Profile.SMOKE, fourRepoCatalog, allQuestions, smokeConfig)
        val fullConfig = RunPlanner.configFor(Profile.FULL)
        val fullPlan = RunPlanner.plan(Profile.FULL, fourRepoCatalog, allQuestions, fullConfig)

        smokePlan.plannedRuns.map { it.arm }.toSet() shouldBe setOf(Arm.WITH_TOOLS, Arm.WITHOUT_TOOLS)
        fullPlan.plannedRuns.map { it.arm }.toSet() shouldBe setOf(Arm.WITH_TOOLS, Arm.WITHOUT_TOOLS)
    }

    test("smoke's question selection is deterministic: repeated calls select the same 3 questions") {
        val config = RunPlanner.configFor(Profile.SMOKE)
        val first = RunPlanner.plan(Profile.SMOKE, fourRepoCatalog, allQuestions, config).questions.map { it.id }
        val second = RunPlanner.plan(Profile.SMOKE, fourRepoCatalog, allQuestions, config).questions.map { it.id }

        first shouldBe second
        first shouldBe listOf("gin-q1", "gin-q2", "gin-q3") // sorted-by-id, capped at 3
    }
})
