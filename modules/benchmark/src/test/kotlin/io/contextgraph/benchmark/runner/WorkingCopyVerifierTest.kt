package io.contextgraph.benchmark.runner

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

/**
 * AC-7a's own required test: "kasten bir `.contextgraph/` dizini konulduğunda koşu
 * reddediliyor" -- put an artefact in a directory that is supposed to be a clean copy and
 * confirm the check refuses rather than silently cleaning up.
 */
class WorkingCopyVerifierTest : FunSpec({

    test("a directory with no artefacts is clean") {
        val dir = Files.createTempDirectory("wc-clean")
        shouldNotThrowAny { WorkingCopyVerifier.verifyClean(dir) }
    }

    test("a directory with a .contextgraph directory is rejected") {
        val dir = Files.createTempDirectory("wc-dirty")
        Files.createDirectory(dir.resolve(".contextgraph"))

        val ex = shouldThrow<ContaminatedWorkingCopyException> { WorkingCopyVerifier.verifyClean(dir) }
        ex.artefacts shouldContain ".contextgraph"
        ex.dir shouldBe dir
    }

    test("a directory with a stray graph.db file is rejected") {
        val dir = Files.createTempDirectory("wc-dirty-db")
        Files.createFile(dir.resolve("graph.db"))

        val ex = shouldThrow<ContaminatedWorkingCopyException> { WorkingCopyVerifier.verifyClean(dir) }
        ex.artefacts shouldContain "graph.db"
    }

    test("findArtefacts reports every artefact present, not just the first") {
        val dir = Files.createTempDirectory("wc-dirty-multi")
        Files.createDirectory(dir.resolve(".contextgraph"))
        Files.createFile(dir.resolve("GRAPH_REPORT.md"))

        val found = WorkingCopyVerifier.findArtefacts(dir)
        found shouldContain ".contextgraph"
        found shouldContain "GRAPH_REPORT.md"
        found.size shouldBe 2
    }

    test("fingerprint is stable across two calls when nothing changed") {
        val dir = Files.createTempDirectory("fp-stable")
        Files.writeString(dir.resolve("a.txt"), "hello")
        Files.createDirectory(dir.resolve("sub"))
        Files.writeString(dir.resolve("sub").resolve("b.txt"), "world")

        WorkingCopyVerifier.fingerprint(dir) shouldBe WorkingCopyVerifier.fingerprint(dir)
    }

    test("fingerprint changes when an existing tracked file's content changes") {
        val dir = Files.createTempDirectory("fp-content-change")
        val file = dir.resolve("a.txt")
        Files.writeString(file, "hello")
        val before = WorkingCopyVerifier.fingerprint(dir)

        Files.writeString(file, "goodbye")
        val after = WorkingCopyVerifier.fingerprint(dir)

        before shouldNotBe after
    }

    test("fingerprint changes when a new file appears, even inside a directory that already carried an artefact") {
        val dir = Files.createTempDirectory("fp-new-file")
        Files.createDirectory(dir.resolve(".contextgraph"))
        val before = WorkingCopyVerifier.fingerprint(dir)

        Files.writeString(dir.resolve(".contextgraph").resolve("graph.db"), "graph-bytes")
        val after = WorkingCopyVerifier.fingerprint(dir)

        before shouldNotBe after
    }

    test("fingerprint changes when a tracked file is deleted") {
        val dir = Files.createTempDirectory("fp-delete")
        val file = dir.resolve("a.txt")
        Files.writeString(file, "hello")
        val before = WorkingCopyVerifier.fingerprint(dir)

        Files.delete(file)
        val after = WorkingCopyVerifier.fingerprint(dir)

        before shouldNotBe after
    }

    test("fingerprint ignores .git") {
        val dir = Files.createTempDirectory("fp-ignore-git")
        Files.createDirectory(dir.resolve(".git"))
        Files.writeString(dir.resolve(".git").resolve("HEAD"), "ref: refs/heads/main")
        val before = WorkingCopyVerifier.fingerprint(dir)

        Files.writeString(dir.resolve(".git").resolve("HEAD"), "ref: refs/heads/other")
        val after = WorkingCopyVerifier.fingerprint(dir)

        before shouldBe after
    }
})
