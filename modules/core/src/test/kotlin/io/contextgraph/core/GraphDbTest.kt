package io.contextgraph.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class GraphDbTest : FunSpec({

    test("exists is false when neither baseline nor overlay is present") {
        val root = Files.createTempDirectory("graphdb-test")

        GraphDb.exists(root) shouldBe false
    }

    test("exists is true when only the baseline is present") {
        val root = Files.createTempDirectory("graphdb-test")
        GraphDb.baseline(root).parent!!.createDirectories()
        GraphDb.baseline(root).writeText("baseline")

        GraphDb.exists(root) shouldBe true
    }

    test("exists is true when only the overlay is present") {
        val root = Files.createTempDirectory("graphdb-test")
        GraphDb.overlay(root).parent!!.createDirectories()
        GraphDb.overlay(root).writeText("overlay")

        GraphDb.exists(root) shouldBe true
    }

    test("forRead resolves to the baseline when no overlay exists") {
        val root = Files.createTempDirectory("graphdb-test")
        GraphDb.baseline(root).parent!!.createDirectories()
        GraphDb.baseline(root).writeText("baseline")

        GraphDb.forRead(root) shouldBe GraphDb.baseline(root)
    }

    test("forRead resolves to the overlay when it exists") {
        val root = Files.createTempDirectory("graphdb-test")
        GraphDb.overlay(root).parent!!.createDirectories()
        GraphDb.overlay(root).writeText("overlay")
        GraphDb.baseline(root).writeText("baseline")

        GraphDb.forRead(root) shouldBe GraphDb.overlay(root)
    }

    test("forLocalWrite seeds the overlay by copying the baseline on first use") {
        val root = Files.createTempDirectory("graphdb-test")
        GraphDb.baseline(root).parent!!.createDirectories()
        GraphDb.baseline(root).writeText("committed contents")

        val writePath = GraphDb.forLocalWrite(root)

        writePath shouldBe GraphDb.overlay(root)
        GraphDb.overlay(root).exists() shouldBe true
        Files.readString(GraphDb.overlay(root)) shouldBe "committed contents"
        // The baseline itself must be untouched by seeding.
        Files.readString(GraphDb.baseline(root)) shouldBe "committed contents"
    }

    test("forLocalWrite never returns the baseline path") {
        val root = Files.createTempDirectory("graphdb-test")
        GraphDb.baseline(root).parent!!.createDirectories()
        GraphDb.baseline(root).writeText("committed contents")

        GraphDb.forLocalWrite(root) shouldBe GraphDb.overlay(root)
    }

    test("forLocalWrite leaves an existing overlay untouched") {
        val root = Files.createTempDirectory("graphdb-test")
        GraphDb.baseline(root).parent!!.createDirectories()
        GraphDb.baseline(root).writeText("committed contents")
        GraphDb.overlay(root).writeText("already has local writes")

        val writePath = GraphDb.forLocalWrite(root)

        writePath shouldBe GraphDb.overlay(root)
        Files.readString(GraphDb.overlay(root)) shouldBe "already has local writes"
    }

    test("forLocalWrite starts an empty overlay when no baseline exists yet") {
        val root = Files.createTempDirectory("graphdb-test")

        val writePath = GraphDb.forLocalWrite(root)

        writePath shouldBe GraphDb.overlay(root)
        GraphDb.overlay(root).exists() shouldBe false
        GraphDb.baseline(root).exists() shouldBe false
    }

    test("deleting the overlay is safe: a later forLocalWrite reseeds it from the baseline") {
        val root = Files.createTempDirectory("graphdb-test")
        GraphDb.baseline(root).parent!!.createDirectories()
        GraphDb.baseline(root).writeText("committed contents")
        GraphDb.forLocalWrite(root)
        Files.delete(GraphDb.overlay(root))

        val writePath = GraphDb.forLocalWrite(root)

        writePath shouldBe GraphDb.overlay(root)
        Files.readString(GraphDb.overlay(root)) shouldBe "committed contents"
    }
})
