package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.Evidence
import io.contextgraph.benchmark.model.GoldFact
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** AC-23: the expected file set is derived from gold-fact evidence, never a second ground truth. */
class ExpectedFileSetTest : FunSpec({

    test("derives the distinct set of files cited by a question's gold facts") {
        val question = Question(
            id = "q1",
            repoId = "repo",
            text = "irrelevant",
            category = QuestionCategory.GRAPH_HEAVY,
            goldFacts = listOf(
                GoldFact("q1-f1", "statement 1", Evidence.parse("a.kt:10")),
                GoldFact("q1-f2", "statement 2", Evidence.parse("b.kt:20")),
                // Same file as f1, different line -- must still count as one file.
                GoldFact("q1-f3", "statement 3", Evidence.parse("a.kt:30"))
            )
        )

        ExpectedFileSet.of(question) shouldBe setOf("a.kt", "b.kt")
    }
})
