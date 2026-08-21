package io.contextgraph.benchmark.questions

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

/**
 * Exercises [QuestionSetLoader] against real files on disk (task 03's
 * fixtures, under `src/test/resources` — see the task file's note that real
 * gold-set data belongs to slices 08-11, not here).
 *
 * AC-3 ("a new question needs no Kotlin change") is demonstrated by these
 * fixtures being pure data: this test file, and [QuestionSetLoader]'s
 * source, do not change when a fixture gains a new question.
 */
class QuestionSetLoaderFileTest : FunSpec({

    fun resourceDir(name: String): Path =
        Path.of(Thread.currentThread().contextClassLoader.getResource(name)!!.toURI())

    test("loadFile reads a single question-set file from disk") {
        val path = resourceDir("questions").resolve("excalidraw.yaml")

        val questions = QuestionSetLoader.loadFile(path)

        questions.size shouldBe 1
        questions.single().id shouldBe "excalidraw-q1"
    }

    test("loadDirectory merges every file in a directory") {
        val dir = resourceDir("questions-dir")

        val questions = QuestionSetLoader.loadDirectory(dir)

        questions.map { it.id } shouldContainExactlyInAnyOrder listOf("excalidraw-q1", "gin-q1")
    }

    test("loadDirectory rejects a question id duplicated across files") {
        val dir = resourceDir("questions-dir-dup")

        val ex = shouldThrow<QuestionSetValidationException> {
            QuestionSetLoader.loadDirectory(dir)
        }
        ex.message shouldContain "shared-id"
    }
})
