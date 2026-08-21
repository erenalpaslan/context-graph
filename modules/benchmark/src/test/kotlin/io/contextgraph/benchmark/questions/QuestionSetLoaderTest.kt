package io.contextgraph.benchmark.questions

import io.contextgraph.benchmark.model.Evidence
import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Task 03 acceptance criteria, exercised at the [QuestionSetLoader.loadText]
 * seam: a valid question set loads fully into the slice-01 model, and every
 * rejection scenario from the task file throws [QuestionSetValidationException]
 * with a message that names the offending question/fact.
 */
class QuestionSetLoaderTest : FunSpec({

    test("a well-formed question set loads into the model") {
        val yaml = """
            questions:
              - id: q1
                repoId: excalidraw
                text: "Which files call renderScene?"
                category: GRAPH_HEAVY
                goldFacts:
                  - id: q1-f1
                    statement: "App.tsx calls renderScene"
                    evidence: "src/App.tsx:120"
                  - id: q1-f2
                    statement: "Renderer.ts defines renderScene"
                    evidence: "src/Renderer.ts:42"
                  - id: q1-f3
                    statement: "Scene.ts imports renderScene"
                    evidence: "src/Scene.ts:8"
        """.trimIndent()

        val questions = QuestionSetLoader.loadText(yaml, source = "test.yaml")

        questions.size shouldBe 1
        val q = questions.single()
        q.id shouldBe "q1"
        q.repoId shouldBe "excalidraw"
        q.category shouldBe QuestionCategory.GRAPH_HEAVY
        q.goldFacts.size shouldBe 3
        q.goldFacts[0].evidence shouldBe Evidence("src/App.tsx", 120)
    }

    test("a gold fact missing evidence is rejected, naming the fact id") {
        val yaml = """
            questions:
              - id: q1
                repoId: excalidraw
                text: "Which files call renderScene?"
                category: GRAPH_HEAVY
                goldFacts:
                  - id: q1-f1
                    statement: "App.tsx calls renderScene"
                    evidence: "src/App.tsx:120"
                  - id: q1-f2
                    statement: "Renderer.ts defines renderScene"
                  - id: q1-f3
                    statement: "Scene.ts imports renderScene"
                    evidence: "src/Scene.ts:8"
        """.trimIndent()

        val ex = shouldThrow<QuestionSetValidationException> {
            QuestionSetLoader.loadText(yaml, source = "test.yaml")
        }
        ex.message shouldContain "q1-f2"
    }

    test("a gold fact with a malformed file:line citation is rejected, naming the fact id") {
        val yaml = """
            questions:
              - id: q1
                repoId: excalidraw
                text: "Which files call renderScene?"
                category: GRAPH_HEAVY
                goldFacts:
                  - id: q1-f1
                    statement: "App.tsx calls renderScene"
                    evidence: "src/App.tsx:120"
                  - id: q1-f2
                    statement: "Renderer.ts defines renderScene"
                    evidence: "not-a-citation"
                  - id: q1-f3
                    statement: "Scene.ts imports renderScene"
                    evidence: "src/Scene.ts:8"
        """.trimIndent()

        val ex = shouldThrow<QuestionSetValidationException> {
            QuestionSetLoader.loadText(yaml, source = "test.yaml")
        }
        ex.message shouldContain "q1-f2"
    }

    test("a question with fewer than 3 gold facts is rejected") {
        val yaml = """
            questions:
              - id: q1
                repoId: excalidraw
                text: "Which files call renderScene?"
                category: GRAPH_HEAVY
                goldFacts:
                  - id: q1-f1
                    statement: "App.tsx calls renderScene"
                    evidence: "src/App.tsx:120"
                  - id: q1-f2
                    statement: "Renderer.ts defines renderScene"
                    evidence: "src/Renderer.ts:42"
        """.trimIndent()

        val ex = shouldThrow<QuestionSetValidationException> {
            QuestionSetLoader.loadText(yaml, source = "test.yaml")
        }
        ex.message shouldContain "q1"
        ex.message shouldContain "2"
    }

    test("a question with more than 6 gold facts is rejected") {
        val facts = (1..7).joinToString("\n") { i ->
            """
              - id: q1-f$i
                statement: "fact $i"
                evidence: "src/File$i.kt:$i"
            """.trimIndent().prependIndent("    ")
        }
        val yaml = """
            questions:
              - id: q1
                repoId: excalidraw
                text: "Which files call renderScene?"
                category: GRAPH_HEAVY
                goldFacts:
        """.trimIndent() + "\n" + facts

        val ex = shouldThrow<QuestionSetValidationException> {
            QuestionSetLoader.loadText(yaml, source = "test.yaml")
        }
        ex.message shouldContain "q1"
        ex.message shouldContain "7"
    }

    test("an unrecognized category is rejected") {
        val yaml = """
            questions:
              - id: q1
                repoId: excalidraw
                text: "Which files call renderScene?"
                category: SUPER_DUPER_HEAVY
                goldFacts:
                  - id: q1-f1
                    statement: "App.tsx calls renderScene"
                    evidence: "src/App.tsx:120"
                  - id: q1-f2
                    statement: "Renderer.ts defines renderScene"
                    evidence: "src/Renderer.ts:42"
                  - id: q1-f3
                    statement: "Scene.ts imports renderScene"
                    evidence: "src/Scene.ts:8"
        """.trimIndent()

        val ex = shouldThrow<QuestionSetValidationException> {
            QuestionSetLoader.loadText(yaml, source = "test.yaml")
        }
        ex.message shouldContain "SUPER_DUPER_HEAVY"
    }

    test("a duplicate question id within one file is rejected") {
        val yaml = """
            questions:
              - id: q1
                repoId: excalidraw
                text: "Which files call renderScene?"
                category: GRAPH_HEAVY
                goldFacts:
                  - id: q1-f1
                    statement: "App.tsx calls renderScene"
                    evidence: "src/App.tsx:120"
                  - id: q1-f2
                    statement: "Renderer.ts defines renderScene"
                    evidence: "src/Renderer.ts:42"
                  - id: q1-f3
                    statement: "Scene.ts imports renderScene"
                    evidence: "src/Scene.ts:8"
              - id: q1
                repoId: excalidraw
                text: "Duplicate id question"
                category: NEUTRAL
                goldFacts:
                  - id: q1b-f1
                    statement: "Something"
                    evidence: "src/Other.ts:1"
                  - id: q1b-f2
                    statement: "Something else"
                    evidence: "src/Other.ts:2"
                  - id: q1b-f3
                    statement: "Yet more"
                    evidence: "src/Other.ts:3"
        """.trimIndent()

        val ex = shouldThrow<QuestionSetValidationException> {
            QuestionSetLoader.loadText(yaml, source = "test.yaml")
        }
        ex.message shouldContain "q1"
        ex.message shouldContain "Duplicate"
    }
})
