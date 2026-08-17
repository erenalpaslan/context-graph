package io.contextgraph.cli

import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractorRegistry
import io.contextgraph.extractors.ConfigExtractor
import io.contextgraph.extractors.MarkdownExtractor
import io.contextgraph.extractors.PdfExtractor
import io.contextgraph.extractors.SqlExtractor
import io.contextgraph.extractors.TreeSitterExtractor
import io.contextgraph.ingest.ChecksumTracker
import io.contextgraph.ingest.FileDiscovery
import io.contextgraph.ingest.IngestPipeline
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * `test-fixtures/` holds source-only fixture projects, never pre-built `graph.db`
 * files: `.gitignore`'s doubly-starred `.contextgraph` rule excludes every nested
 * `.contextgraph` directory, including under `test-fixtures/`. (Only the repo
 * root's own `.contextgraph/graph.db` is a deliberate exception — the committed
 * baseline described in `GraphDb.kt` — and that exception does not reach into
 * fixture subdirectories.)
 * Any test that needs a graph.db for a fixture must build it from that source at
 * test time — this is the proof that doing so is fast, deterministic, and requires
 * no state beyond a fresh `git clone`. Golden-snapshot tests (see spec AC-32/AC-33)
 * should follow this same generate-on-demand pattern rather than depend on a
 * committed binary.
 */
class FixtureGenerationTest : FunSpec({

    test("kotlin-project fixture graph.db builds from source on a fresh checkout, with no pre-existing state") {
        val fixtureRoot = findFixture("kotlin-project")
        val workDir = Files.createTempDirectory("contextgraph-fixture-")
        val dbPath = workDir.resolve("graph.db")

        val storage = SqliteStorageAdapter(dbPath)
        try {
            val registry = ExtractorRegistry(
                listOf(MarkdownExtractor(), TreeSitterExtractor(), PdfExtractor(), SqlExtractor(), ConfigExtractor())
            )
            val pipeline = IngestPipeline(
                discovery = FileDiscovery(ContextGraphConfig()),
                registry = registry,
                checksumTracker = ChecksumTracker(),
                storage = storage,
                context = ExtractionContext(fixtureRoot, ContextGraphConfig())
            )

            val stats = runBlocking { pipeline.index(fixtureRoot) }

            stats.artifactCount shouldBeGreaterThan 0
            stats.nodeCount shouldBeGreaterThan 0

            val graphStats = storage.getStats()
            graphStats.artifactCount shouldBeGreaterThan 0
            graphStats.nodeCount shouldBeGreaterThan 0
        } finally {
            storage.close()
            workDir.toFile().deleteRecursively()
        }
    }
})

/**
 * Test working directory is Gradle's per-module convention (e.g. `modules/cli`), which
 * differs between an IDE run and `./gradlew test`. Walk up to the repo root — identified
 * by `settings.gradle.kts` — rather than assume a fixed relative depth.
 */
private fun findFixture(name: String): Path {
    var dir = Path.of("").toAbsolutePath()
    while (dir.parent != null && !dir.resolve("settings.gradle.kts").exists()) {
        dir = dir.parent
    }
    val fixture = dir.resolve("test-fixtures").resolve(name)
    check(fixture.isDirectory()) { "Fixture source directory not found: $fixture" }
    return fixture
}
