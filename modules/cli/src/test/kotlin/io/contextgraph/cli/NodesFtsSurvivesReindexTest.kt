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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Indexing a project twice must leave the search index the same size, not larger.
 *
 * This is the property `nodes_fts` lacked for three runs: `INSERT OR REPLACE` on an FTS5 table
 * has no unique key to conflict against, so every re-upsert appended a row and nothing ever
 * removed one. It is asserted here through the real pipeline rather than through the storage
 * adapter alone, because that is the level at which the defect was actually observed --
 * excalidraw's index carried 10,602 search rows against 10,383 nodes.
 *
 * **Why the second case exists.** Re-indexing an *unchanged* project is nearly a no-op:
 * extraction skips any artifact whose checksum still matches, which is why a second cold index
 * of excalidraw grew the pollution by only 6 rows out of 10,608. A test that only re-indexed
 * unchanged files would therefore have passed against the broken code and proved nothing. The
 * modified-file case is the one that forces real deletion and re-upsert, and it is the one that
 * fails loudly without the fix.
 */
class NodesFtsSurvivesReindexTest : FunSpec({

    fun scalar(dbPath: Path, sql: String): Long =
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().executeQuery(sql).use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }

    fun nodeCount(dbPath: Path) = scalar(dbPath, "SELECT count(*) FROM nodes")
    fun ftsCount(dbPath: Path) = scalar(dbPath, "SELECT count(*) FROM nodes_fts")

    /** Search rows with no live node behind them: the orphans, which counting alone hides. */
    fun orphanCount(dbPath: Path) = scalar(
        dbPath,
        "SELECT count(*) FROM nodes_fts f WHERE NOT EXISTS (SELECT 1 FROM nodes n WHERE n.rowid = f.rowid)"
    )

    /** One full index of [root] into [dbPath], the way a separate `index` invocation would. */
    fun index(root: Path, dbPath: Path): Int {
        val storage = SqliteStorageAdapter(dbPath)
        try {
            val pipeline = IngestPipeline(
                discovery = FileDiscovery(ContextGraphConfig()),
                registry = ExtractorRegistry(
                    listOf(MarkdownExtractor(), TreeSitterExtractor(), PdfExtractor(), SqlExtractor(), ConfigExtractor())
                ),
                checksumTracker = ChecksumTracker(),
                storage = storage,
                context = ExtractionContext(root, ContextGraphConfig())
            )
            return runBlocking { pipeline.index(root) }.nodeCount
        } finally {
            storage.close()
        }
    }

    /** A private, writable copy of a fixture -- the tracked one is never modified. */
    fun copyOfFixture(name: String): Path {
        val source = findFixtureRoot(name)
        val destination = Files.createTempDirectory("fts-reindex-$name")
        source.toFile().copyRecursively(destination.toFile(), overwrite = true)
        return destination
    }

    test("re-indexing an unchanged project leaves the search index exactly the same size") {
        val project = copyOfFixture("kotlin-project")
        val dbPath = Files.createTempDirectory("fts-reindex-db").resolve("graph.db")

        index(project, dbPath) shouldBeGreaterThan 0
        val nodesAfterFirst = nodeCount(dbPath)
        val ftsAfterFirst = ftsCount(dbPath)

        withClue("the fixture indexed something at all") { nodesAfterFirst shouldBeGreaterThan 0L }
        withClue("one search row per node after the first index") { ftsAfterFirst shouldBe nodesAfterFirst }

        index(project, dbPath)

        withClue("nodes unchanged by a second index") { nodeCount(dbPath) shouldBe nodesAfterFirst }
        withClue("search rows unchanged by a second index") { ftsCount(dbPath) shouldBe ftsAfterFirst }
        withClue("no orphaned search rows") { orphanCount(dbPath) shouldBe 0L }

        project.toFile().deleteRecursively()
        dbPath.parent.toFile().deleteRecursively()
    }

    test("re-indexing after a file changes still leaves one search row per node") {
        val project = copyOfFixture("kotlin-project")
        val dbPath = Files.createTempDirectory("fts-reindex-changed-db").resolve("graph.db")

        index(project, dbPath)
        val ftsAfterFirst = ftsCount(dbPath)
        withClue("one search row per node after the first index") {
            ftsAfterFirst shouldBe nodeCount(dbPath)
        }

        // Defeat the checksum skip: without this the second index re-extracts almost nothing
        // and the defect this test exists for never gets a chance to appear.
        val changed = Files.walk(project).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }.findFirst()
        }.orElseThrow { AssertionError("fixture has no .kt file to modify") }
        changed.writeText(changed.readText() + "\n// touched, so this artifact is really re-extracted\n")

        index(project, dbPath)

        withClue("still one search row per node after a real re-extraction") {
            ftsCount(dbPath) shouldBe nodeCount(dbPath)
        }
        withClue("no orphaned search rows after delete-and-rewrite") { orphanCount(dbPath) shouldBe 0L }

        // A third index, unchanged again, to show the count settles rather than drifting.
        val ftsAfterChange = ftsCount(dbPath)
        index(project, dbPath)
        withClue("search rows stable across a further index") { ftsCount(dbPath) shouldBe ftsAfterChange }

        project.toFile().deleteRecursively()
        dbPath.parent.toFile().deleteRecursively()
    }
})

/**
 * Test working directory is Gradle's per-module convention, which differs between an IDE run
 * and `./gradlew test`. Walk up to the repo root rather than assume a fixed relative depth --
 * the same approach `FixtureGenerationTest` takes, for the same reason.
 */
private fun findFixtureRoot(name: String): Path {
    var dir = Path.of("").toAbsolutePath()
    while (dir.parent != null && !dir.resolve("settings.gradle.kts").exists()) {
        dir = dir.parent
    }
    val fixture = dir.resolve("test-fixtures").resolve(name)
    check(fixture.isDirectory()) { "Fixture source directory not found: $fixture" }
    return fixture
}
