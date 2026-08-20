package io.contextgraph.benchmark.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class RepoRootTest : FunSpec({

    // The real repo root, found independently of the JVM's cwd so this test itself doesn't
    // depend on where the test runner happens to launch from.
    val repoRoot = RepoRoot.find(Path.of(System.getProperty("user.dir")))

    test(
        "resolveCorpusRoot yields the same absolute directory for a relative --corpus-root " +
            "regardless of the caller's working directory (regression for the second-corpus defect)"
    ) {
        val fromRepoRoot = RepoRoot.resolveCorpusRoot(".benchmark-corpus", startDir = repoRoot)
        val fromModuleDir = RepoRoot.resolveCorpusRoot(
            ".benchmark-corpus",
            startDir = repoRoot.resolve("modules/benchmark")
        )
        val fromNestedDir = RepoRoot.resolveCorpusRoot(
            ".benchmark-corpus",
            startDir = repoRoot.resolve("modules/benchmark/src/main/kotlin")
        )

        val expected = repoRoot.resolve(".benchmark-corpus").normalize()
        fromRepoRoot shouldBe expected
        fromModuleDir shouldBe expected
        fromNestedDir shouldBe expected
    }

    test("an absolute --corpus-root is taken as-is regardless of the caller's working directory") {
        val absolute = createTempDirectory("repo-root-test-absolute").toAbsolutePath().normalize()

        RepoRoot.resolveCorpusRoot(absolute.toString(), startDir = repoRoot) shouldBe absolute
        RepoRoot.resolveCorpusRoot(
            absolute.toString(),
            startDir = repoRoot.resolve("modules/benchmark")
        ) shouldBe absolute
    }

    test("find fails loudly, rather than falling back to the start directory, when no repo-root marker exists") {
        val orphan = createTempDirectory("repo-root-test-orphan")

        shouldThrow<IllegalStateException> {
            RepoRoot.find(orphan)
        }
    }

    test(
        "an explicit repo root wins over the directory walk, even when startDir would walk up " +
            "to a different (real) root — guards against silently anchoring to the wrong tree " +
            "when an unrelated settings.gradle.kts sits between the launch dir and this repo's root"
    ) {
        val explicitRoot = createTempDirectory("repo-root-test-explicit").toAbsolutePath().normalize()
        // Must look like a real repo root (carry the marker) now that find() validates it —
        // otherwise this would trip the "not actually this repository's root" check below
        // rather than testing precedence.
        explicitRoot.resolve("settings.gradle.kts").writeText("")

        // startDir here genuinely resolves to a *different*, real root via the walk-up —
        // proving the explicit root pre-empts the walk rather than merely agreeing with it.
        RepoRoot.find(startDir = repoRoot, explicitRoot = explicitRoot) shouldBe explicitRoot
    }

    test("find falls back to the directory walk when no explicit repo root is supplied (bare java -cp launch)") {
        RepoRoot.find(
            startDir = repoRoot.resolve("modules/benchmark"),
            explicitRoot = null
        ) shouldBe repoRoot
    }

    test(
        "an explicit repo root that does not exist fails loudly rather than silently falling " +
            "back to the directory walk"
    ) {
        val missing = repoRoot.resolve("__repo_root_test_does_not_exist__")

        // startDir would succeed via the walk (it's the real repo root) — proving the failure
        // comes from refusing the bad explicit value, not from an unrelated walk failure.
        shouldThrow<IllegalStateException> {
            RepoRoot.find(startDir = repoRoot, explicitRoot = missing)
        }
    }

    test(
        "an explicit repo root that EXISTS but is not actually this repository's root (no " +
            "marker) is rejected, not silently accepted — Gradle being a trusted source for " +
            "the property doesn't exempt the value it hands over from the same check the " +
            "walk-up applies to itself"
    ) {
        val wrongRepo = createTempDirectory("repo-root-test-wrong-repo")
        // Deliberately no settings.gradle.kts written here.

        shouldThrow<IllegalStateException> {
            RepoRoot.find(startDir = repoRoot, explicitRoot = wrongRepo)
        }
    }
})
