package io.contextgraph.extractors.snapshot

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.writeText

/**
 * Tests the harness itself, independent of Java: that it fails loudly with a diff on a
 * mismatch, and that a brand new snapshot test needs nothing from this file changed --
 * only a fixture directory, a snapshot file, and a call to [GoldenSnapshotHarness].
 *
 * `test-fixtures/java-grammar-reuse-check` exists solely to prove the second point: it
 * is a different fixture from `test-fixtures/java-grammar`, extracted and compared with
 * the exact same [GoldenSnapshotHarness] object slice 02's real Java test uses -- zero
 * lines of [GoldenSnapshotHarness] needed to touch Java, or this fixture, specifically.
 */
class GoldenSnapshotHarnessSelfTest : FunSpec({

    test("a second fixture gets a snapshot test by calling the harness, no harness changes") {
        val fixtureRoot = RepoRoot.fixture("java-grammar-reuse-check")
        val snapshotFile = fixtureRoot.resolve(GoldenSnapshotHarness.SNAPSHOT_FILE_NAME)

        GoldenSnapshotHarness.assertMatchesGoldenSnapshot(fixtureRoot, snapshotFile)
    }

    test("a mismatched snapshot fails loudly with a diff identifying what changed") {
        val fixtureRoot = RepoRoot.fixture("java-grammar-reuse-check")
        val tamperedSnapshot = kotlin.io.path.createTempFile("tampered-snapshot", ".json")
        try {
            tamperedSnapshot.writeText(
                GoldenSnapshotHarness.normalize(GoldenSnapshotHarness.extractFixture(fixtureRoot))
                    .replace("\"greet\"", "\"totally-different-label\"")
            )

            val error = shouldThrow<AssertionError> {
                GoldenSnapshotHarness.assertMatchesGoldenSnapshot(fixtureRoot, tamperedSnapshot)
            }

            error.message shouldContain "does not match golden snapshot"
            error.message shouldContain "totally-different-label"
            error.message shouldContain "greet"
        } finally {
            tamperedSnapshot.toFile().delete()
        }
    }

    test("a missing snapshot file fails with an actionable message instead of a raw exception") {
        val fixtureRoot = RepoRoot.fixture("java-grammar-reuse-check")
        val missing = fixtureRoot.resolve("does-not-exist.json")

        val error = shouldThrow<IllegalStateException> {
            GoldenSnapshotHarness.assertMatchesGoldenSnapshot(fixtureRoot, missing)
        }
        error.message shouldContain "No golden snapshot"
    }
})
