package io.contextgraph.benchmark.corpus

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

/** AC-7a: a control-arm working copy must carry none of these artifacts. */
class CleanCopyVerifierTest : FunSpec({

    test("findArtifacts is empty for a pristine directory") {
        val dir = Files.createTempDirectory("clean-copy-pristine-")
        try {
            Files.writeString(dir.resolve("README.md"), "hello")
            CleanCopyVerifier.findArtifacts(dir).shouldBeEmpty()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("findArtifacts detects each known code-graph artifact") {
        CleanCopyVerifier.KNOWN_ARTIFACT_NAMES.forEach { name ->
            val dir = Files.createTempDirectory("clean-copy-artifact-")
            try {
                val target = dir.resolve(name)
                // Both tools write their index into a dot-DIRECTORY at the project root.
                if (name.startsWith(".")) Files.createDirectory(target) else Files.writeString(target, "x")
                CleanCopyVerifier.findArtifacts(dir) shouldContainExactly listOf(name)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    test("a copy carrying CodeGraph's index is contaminated too, not just ContextGraph's") {
        // The control arm's guarantee is "a project NO code-graph tool has ever touched". Before
        // this, a verifier that knew only ContextGraph's artifacts would certify this directory as
        // clean while CodeGraph's index sat inside it — and the before/after check around indexing
        // would have been proving nothing about the second writer.
        val dir = Files.createTempDirectory("clean-copy-codegraph-")
        try {
            Files.createDirectory(dir.resolve(".codegraph"))
            Files.writeString(dir.resolve(".codegraph/codegraph.db"), "index")

            val ex = shouldThrow<CleanCopyContaminatedException> { CleanCopyVerifier.verifyClean(dir) }
            ex.found shouldContainExactly listOf(".codegraph")
            // The message must name the artifact, so an operator reading a failed run knows which
            // tool wrote where it should not have.
            ex.message!!.contains(".codegraph") shouldBe true
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("the artifact list covers both tools' index directories") {
        CleanCopyVerifier.KNOWN_ARTIFACT_NAMES.contains(".contextgraph") shouldBe true
        CleanCopyVerifier.KNOWN_ARTIFACT_NAMES.contains(".codegraph") shouldBe true
    }

    test("verifyClean throws naming every artifact found, and does nothing when clean") {
        val dir = Files.createTempDirectory("clean-copy-verify-")
        try {
            CleanCopyVerifier.verifyClean(dir) // no artifacts -> no throw

            Files.createDirectory(dir.resolve(".contextgraph"))
            Files.writeString(dir.resolve("GRAPH_REPORT.md"), "report")

            val ex = shouldThrow<CleanCopyContaminatedException> { CleanCopyVerifier.verifyClean(dir) }
            ex.found shouldContainExactly listOf(".contextgraph", "GRAPH_REPORT.md")
            ex.root shouldBe dir
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("fingerprint is stable when nothing changes and changes when a tracked file is touched") {
        val dir = Files.createTempDirectory("clean-copy-fingerprint-")
        try {
            Files.writeString(dir.resolve("a.txt"), "hello")
            Files.createDirectories(dir.resolve("nested"))
            Files.writeString(dir.resolve("nested/b.txt"), "world")

            val before = CleanCopyVerifier.fingerprint(dir)
            CleanCopyVerifier.fingerprint(dir) shouldBe before

            Files.writeString(dir.resolve("nested/b.txt"), "world!")
            CleanCopyVerifier.fingerprint(dir) shouldNotBe before
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("fingerprint ignores .git so worktree bookkeeping doesn't count as contamination") {
        val dir = Files.createTempDirectory("clean-copy-fingerprint-git-")
        try {
            LocalGitFixture.create(dir)
            val before = CleanCopyVerifier.fingerprint(dir)
            // Simulate git touching its own internals without changing tracked content.
            Files.writeString(dir.resolve(".git/some-internal-marker"), "noise")
            CleanCopyVerifier.fingerprint(dir) shouldBe before
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
})
