package io.contextgraph.benchmark.questions

import io.contextgraph.benchmark.cli.RepoRoot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/**
 * The permanent standing check for the real gold question sets under
 * `modules/benchmark/questions/{excalidraw,gin,calcom,keycloak}.yaml` (slices 08-11's data,
 * task 12's wiring). During their production each author validated their own file with a
 * throwaway scratch test and deleted it -- see `agent-team/tasks/12.md`'s note -- which meant
 * nothing caught a later hand-edit to any of the four files. This test is that catch: it loads
 * the *real* directory (not a fixture under `src/test/resources`, unlike every other test in
 * this package) through [QuestionSetLoader.loadDirectory] and audits the merged set with
 * [CategoryDistributionAuditor], exactly as [io.contextgraph.benchmark.orchestrator.BenchmarkOrchestrator]
 * does at the start of every real run.
 *
 * If a real gold-set file ever fails to load or fails the distribution audit, that content
 * belongs to slices 08-11 (see `modules/benchmark/README.md`'s package map) -- this test's job
 * is only to catch and report the regression, not to repair the YAML.
 */
class GoldQuestionSetsTest : FunSpec({

    fun questionsDir(): Path =
        RepoRoot.find(Path.of(System.getProperty("user.dir"))).resolve("modules/benchmark/questions")

    test("every real gold question set loads without error via QuestionSetLoader.loadDirectory") {
        val questions = QuestionSetLoader.loadDirectory(questionsDir())
        questions.shouldNotBeEmpty()
    }

    test("question ids are unique across all four repos' real gold sets") {
        val questions = QuestionSetLoader.loadDirectory(questionsDir())
        val ids = questions.map { it.id }
        ids.size shouldBe ids.toSet().size
    }

    test("all four repos are present and the merged set passes CategoryDistributionAuditor") {
        val questions = QuestionSetLoader.loadDirectory(questionsDir())
        val repoIds = questions.map { it.repoId }.toSet()
        repoIds shouldBe setOf("excalidraw", "gin", "calcom", "keycloak")

        val reports = CategoryDistributionAuditor.auditAll(questions)
        reports.isEmpty() shouldBe false
        reports.forEach { (repoId, report) ->
            assert(report.passes) { "category distribution audit failed for repo '$repoId':\n${report.describe()}" }
        }
    }
})
