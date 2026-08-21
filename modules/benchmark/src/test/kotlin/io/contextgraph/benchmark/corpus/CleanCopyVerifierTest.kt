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

    test("findArtifacts detects each known ContextGraph artifact") {
        CleanCopyVerifier.KNOWN_ARTIFACT_NAMES.forEach { name ->
            val dir = Files.createTempDirectory("clean-copy-artifact-")
            try {
                val target = dir.resolve(name)
                if (name == ".contextgraph") Files.createDirectory(target) else Files.writeString(target, "x")
                CleanCopyVerifier.findArtifacts(dir) shouldContainExactly listOf(name)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
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
