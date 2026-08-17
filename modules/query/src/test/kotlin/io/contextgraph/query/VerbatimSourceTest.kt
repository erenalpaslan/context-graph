package io.contextgraph.query

import io.contextgraph.core.ArtifactId
import io.contextgraph.core.Provenance
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock
import java.nio.file.Files

class VerbatimSourceTest : FunSpec({

    fun provenance(path: String, lineStart: Int?, lineEnd: Int?) = Provenance(
        artifactId = ArtifactId("art"),
        path = path,
        lineStart = lineStart,
        lineEnd = lineEnd,
        extractor = "tree-sitter",
        extractedAt = Clock.System.now()
    )

    test("reads exactly the recorded line range, verbatim") {
        val root = Files.createTempDirectory("verbatim-source-test")
        val file = root.resolve("Foo.kt")
        Files.write(file, listOf("line1", "line2", "line3", "line4", "line5"))

        val text = VerbatimSource.read(root, provenance("Foo.kt", 2, 4))

        text shouldBe "line2\nline3\nline4"
    }

    test("a single-line declaration reads just that line when lineEnd is null") {
        val root = Files.createTempDirectory("verbatim-source-test")
        val file = root.resolve("Foo.kt")
        Files.write(file, listOf("line1", "line2", "line3"))

        val text = VerbatimSource.read(root, provenance("Foo.kt", 2, null))

        text shouldBe "line2"
    }

    test("returns null when there is no recorded line range") {
        val root = Files.createTempDirectory("verbatim-source-test")

        VerbatimSource.read(root, provenance("Foo.kt", null, null)).shouldBeNull()
    }

    test("returns null when the file does not exist") {
        val root = Files.createTempDirectory("verbatim-source-test")

        VerbatimSource.read(root, provenance("Missing.kt", 1, 2)).shouldBeNull()
    }

    test("returns null when the recorded range no longer fits a shrunk file") {
        val root = Files.createTempDirectory("verbatim-source-test")
        val file = root.resolve("Foo.kt")
        Files.write(file, listOf("line1"))

        VerbatimSource.read(root, provenance("Foo.kt", 5, 8)).shouldBeNull()
    }

    test("clamps a recorded end line beyond the current file length instead of failing") {
        val root = Files.createTempDirectory("verbatim-source-test")
        val file = root.resolve("Foo.kt")
        Files.write(file, listOf("line1", "line2"))

        val text = VerbatimSource.read(root, provenance("Foo.kt", 1, 10))

        text shouldBe "line1\nline2"
    }
})
