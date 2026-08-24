package io.contextgraph.storage

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ArtifactWriteBatch
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * The invariant `nodes_fts` never had: **one search row per node, no matter how many times the
 * node is written, and none once it is deleted.**
 *
 * `nodes_fts` is `fts5(id UNINDEXED, label, properties)`. `UNINDEXED` means "not searchable",
 * not "unique", so the `INSERT OR REPLACE` this table was written with for three runs had no
 * key to conflict against and never replaced: every re-upsert appended another row, and no code
 * path ever deleted one. On excalidraw that read as 10,602 search rows against 10,383 nodes.
 *
 * Every assertion here fails against that code. They are written against the invariant rather
 * than against a row count, so a fixture gaining a node does not make them lie.
 */
class NodesFtsOneRowPerNodeTest : FunSpec({

    fun freshDbPath(name: String): Path =
        Files.createTempDirectory("fts-one-row-$name").resolve("graph.db")

    fun node(id: String, label: String, extra: String = "x") = GraphNode(
        id = NodeId(id),
        type = NodeType.Class,
        label = label,
        properties = mapOf("marker" to JsonPrimitive(extra)),
        confidence = 1.0
    )

    /** One scalar out of a built database, read through raw SQL rather than this adapter. */
    fun scalar(dbPath: Path, sql: String): Long =
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().executeQuery(sql).use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }

    fun ftsRowCount(dbPath: Path) = scalar(dbPath, "SELECT count(*) FROM nodes_fts")
    fun nodeRowCount(dbPath: Path) = scalar(dbPath, "SELECT count(*) FROM nodes")

    /** Search rows whose rowid matches no live node -- the orphans nothing used to clean up. */
    fun orphanedFtsRowCount(dbPath: Path) = scalar(
        dbPath,
        "SELECT count(*) FROM nodes_fts f WHERE NOT EXISTS (SELECT 1 FROM nodes n WHERE n.rowid = f.rowid)"
    )

    fun matchCount(dbPath: Path, term: String) = scalar(
        dbPath,
        "SELECT count(*) FROM nodes_fts WHERE nodes_fts MATCH '\"$term\"'"
    )

    /**
     * Both directions of the invariant at once: as many search rows as nodes, and every one of
     * them behind a live node. Counting alone is not enough -- one duplicate plus one orphan
     * sums to the right total while being wrong twice.
     */
    fun assertOneRowPerNode(dbPath: Path, clue: String) {
        withClue("$clue: nodes_fts row count equals nodes row count") {
            ftsRowCount(dbPath) shouldBe nodeRowCount(dbPath)
        }
        withClue("$clue: no search row without a live node behind it") {
            orphanedFtsRowCount(dbPath) shouldBe 0L
        }
    }

    test("upserting one node repeatedly through the single-row path leaves one search row") {
        val dbPath = freshDbPath("single")
        SqliteStorageAdapter(dbPath).use { storage ->
            repeat(5) { storage.upsertNode(node("n1", "AlphaBeta")) }
        }

        assertOneRowPerNode(dbPath, "five identical upserts")
        ftsRowCount(dbPath) shouldBe 1L
    }

    test("re-upserting with a new label removes the superseded value's terms from the index") {
        val dbPath = freshDbPath("supersede")
        SqliteStorageAdapter(dbPath).use { storage ->
            storage.upsertNode(node("n1", "OriginalName"))
            storage.upsertNode(node("n1", "ReplacementName"))
        }

        assertOneRowPerNode(dbPath, "after a rename")
        withClue("the replaced label is gone from the index, not merely outranked") {
            matchCount(dbPath, "OriginalName") shouldBe 0L
        }
        withClue("the current label is findable") {
            matchCount(dbPath, "ReplacementName") shouldBe 1L
        }
        withClue("and still findable by a component word -- the segment augmentation survives") {
            matchCount(dbPath, "Replacement") shouldBe 1L
        }
    }

    test("the bulk path holds the same invariant, including a node repeated within one batch") {
        val dbPath = freshDbPath("bulk")
        // More nodes than one FTS statement carries, so the chunking boundary is crossed.
        val nodes = (0 until 640).map { node("n$it", "SomeCamelCaseName$it") }

        SqliteStorageAdapter(dbPath).use { storage ->
            storage.upsertNodes(nodes)
            storage.upsertNodes(nodes)
            // The same node twice inside a single batch: last value wins, one row results.
            storage.upsertNodes(listOf(node("n0", "FirstWins"), node("n0", "LastWins")))
        }

        assertOneRowPerNode(dbPath, "640 nodes written three times")
        ftsRowCount(dbPath) shouldBe 640L
        withClue("the last value in a batch is the one indexed") {
            matchCount(dbPath, "LastWins") shouldBe 1L
            matchCount(dbPath, "FirstWins") shouldBe 0L
        }
    }

    test("deleting an artifact's nodes leaves no search rows behind") {
        val dbPath = freshDbPath("delete")
        val artifactId = "src/Gone.kt"
        val nodes = (0 until 3).map { index ->
            node("$artifactId#Symbol$index", "DoomedSymbol$index").copy(
                provenance = listOf(
                    Provenance(
                        artifactId = ArtifactId(artifactId),
                        path = artifactId,
                        lineStart = index,
                        lineEnd = index + 1,
                        page = null,
                        textSpan = null,
                        extractor = "test",
                        extractedAt = Clock.System.now()
                    )
                )
            )
        }

        SqliteStorageAdapter(dbPath).use { storage ->
            storage.writeArtifactBatch(
                ArtifactWriteBatch(
                    artifact = Artifact(
                        id = ArtifactId(artifactId),
                        type = NodeType.CodeFile,
                        path = artifactId,
                        checksum = "c1",
                        size = 1,
                        lastModified = Clock.System.now(),
                        indexedAt = Clock.System.now()
                    ),
                    clearExisting = true,
                    nodes = nodes,
                    edges = emptyList(),
                    references = emptyList()
                )
            )
            assertOneRowPerNode(dbPath, "after the first write")
            withClue("the nodes really were indexed") { ftsRowCount(dbPath) shouldBe 3L }

            storage.deleteNodesForArtifact(ArtifactId(artifactId))
        }

        withClue("a deleted node occupies zero search rows, not one") {
            ftsRowCount(dbPath) shouldBe 0L
        }
        assertOneRowPerNode(dbPath, "after deletion")
    }

    test("delete and re-insert reuses no stale row, even though the node gets a new rowid") {
        // The path a reindex of a *changed* file takes: writeArtifactBatch(clearExisting = true)
        // deletes the artifact's nodes and writes them again. A re-inserted node gets a fresh
        // rowid, so a search row left at the old one would be an orphan the fixed write path
        // would never overwrite -- the original defect, wearing a different hat.
        val dbPath = freshDbPath("reinsert")
        val artifactId = "src/Churn.kt"

        fun batch(revision: Int) = ArtifactWriteBatch(
            artifact = Artifact(
                id = ArtifactId(artifactId),
                type = NodeType.CodeFile,
                path = artifactId,
                checksum = "checksum-$revision",
                size = revision.toLong(),
                lastModified = Clock.System.now(),
                indexedAt = Clock.System.now()
            ),
            clearExisting = true,
            nodes = (0 until 4).map { index ->
                node("$artifactId#Symbol$index", "RevisionMarker${revision}Symbol$index").copy(
                    provenance = listOf(
                        Provenance(
                            artifactId = ArtifactId(artifactId),
                            path = artifactId,
                            lineStart = index,
                            lineEnd = index + 1,
                            page = null,
                            textSpan = null,
                            extractor = "test",
                            extractedAt = Clock.System.now()
                        )
                    )
                )
            },
            edges = emptyList(),
            references = emptyList()
        )

        SqliteStorageAdapter(dbPath).use { storage ->
            repeat(4) { revision -> storage.writeArtifactBatch(batch(revision)) }
        }

        assertOneRowPerNode(dbPath, "after four delete-and-rewrite cycles")
        ftsRowCount(dbPath) shouldBe 4L
        withClue("only the newest revision is in the index") {
            matchCount(dbPath, "RevisionMarker3Symbol0") shouldBe 1L
            matchCount(dbPath, "RevisionMarker0Symbol0") shouldBe 0L
        }
    }

    test("the search index passes FTS5's own integrity check after all of it") {
        val dbPath = freshDbPath("integrity")
        SqliteStorageAdapter(dbPath).use { storage ->
            storage.upsertNodes((0 until 300).map { node("n$it", "CheckedName$it") })
            storage.upsertNodes((0 until 300).map { node("n$it", "RewrittenName$it") })
        }

        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                // Throws if the index and its content disagree -- the check that catches a
                // half-replaced row, which counting alone cannot see.
                statement.executeUpdate("INSERT INTO nodes_fts(nodes_fts) VALUES('integrity-check')")
            }
        }
        assertOneRowPerNode(dbPath, "after 300 nodes rewritten")
    }
})

/** `StorageAdapter` declares `close()` without extending `AutoCloseable`; same helper as the
 *  neighbouring tests use, kept file-private for the same reason. */
private fun <T> SqliteStorageAdapter.use(block: (SqliteStorageAdapter) -> T): T = try {
    block(this)
} finally {
    close()
}
