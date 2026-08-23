package io.contextgraph.storage

import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ArtifactWriteBatch
import io.contextgraph.core.Artifact
import io.contextgraph.core.GraphNode
import io.contextgraph.core.IdentifierSplitter
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Proves the write half of the identifier segment vocabulary: a durable
 * `name_segment_vocab(segment, name)` table populated on the node write path, excluding file
 * nodes, idempotent across repeated indexing, and identical whether written through the
 * single-node or the bulk verb. The read path -- proposals re-verified against `nodes` -- is
 * `SegmentCandidatesTest`'s job and is not exercised here.
 */
class SegmentVocabularyTest : FunSpec({

    fun now() = Clock.System.now()

    fun freshDbPath(name: String) = Files.createTempDirectory("segment-vocab-$name").resolve("graph.db")

    /** Every `(segment, name)` row currently in the table, read back through raw SQL. */
    fun segmentRows(dbPath: Path): List<Pair<String, String>> =
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().executeQuery("SELECT segment, name FROM name_segment_vocab").use { rs ->
                val rows = mutableListOf<Pair<String, String>>()
                while (rs.next()) rows += rs.getString("segment") to rs.getString("name")
                rows
            }
        }

    fun makeNode(id: String, label: String, type: NodeType = NodeType.Class) =
        GraphNode(NodeId(id), type, label, confidence = 1.0)

    fun makeArtifact(id: String, type: NodeType = NodeType.CodeFile) = Artifact(
        id = ArtifactId(id),
        type = type,
        path = id,
        checksum = "sum-$id",
        size = 10L,
        lastModified = now(),
        indexedAt = now()
    )

    test("a compound name gets one row per distinct sub-word, paired with the name") {
        val dbPath = freshDbPath("compound")
        SqliteStorageAdapter(dbPath).use { storage ->
            storage.upsertNode(makeNode("n1", "RungDistribution"))
        }
        // Segments are lower-cased at write time (writeSegmentVocab) -- see
        // SegmentCandidatesTest's non-ASCII test for why this fold happens in Kotlin rather
        // than in SQL.
        segmentRows(dbPath) shouldContainExactlyInAnyOrder listOf(
            "rung" to "RungDistribution",
            "distribution" to "RungDistribution"
        )
    }

    test("a file node's basename contributes no row to the vocabulary") {
        val dbPath = freshDbPath("file-excluded")
        SqliteStorageAdapter(dbPath).use { storage ->
            // CodeFile is a file-level NodeType (an "Artifact-level type" per NodeType's own
            // grouping); its label is a compound basename, exactly the case the exclusion guards.
            storage.upsertNode(makeNode("f1", "OrderStateMachine.kt", type = NodeType.CodeFile))
            // A non-file node sharing a near-identical compound label, to prove the table isn't
            // simply empty by construction.
            storage.upsertNode(makeNode("n1", "OrderStateService", type = NodeType.Class))
        }
        val rows = segmentRows(dbPath)
        rows.none { (_, name) -> name == "OrderStateMachine.kt" } shouldBe true
        rows shouldContainExactlyInAnyOrder listOf(
            "order" to "OrderStateService",
            "state" to "OrderStateService",
            "service" to "OrderStateService"
        )
    }

    test("all artifact-level types: every file-node type is excluded, not just CodeFile") {
        val dbPath = freshDbPath("all-file-types")
        val fileTypes = listOf(
            NodeType.CodeFile, NodeType.Document, NodeType.MarkdownFile, NodeType.PDF,
            NodeType.Image, NodeType.Diagram, NodeType.DatabaseSchema, NodeType.ConfigFile,
            NodeType.ResearchPaper, NodeType.TestFile, NodeType.PackageFile
        )
        SqliteStorageAdapter(dbPath).use { storage ->
            fileTypes.forEachIndexed { i, type -> storage.upsertNode(makeNode("f$i", "CompoundFileLabel$i", type = type)) }
        }
        segmentRows(dbPath).shouldBeEmpty()
    }

    test("indexing the same project twice leaves the row count identical") {
        val dbPath = freshDbPath("idempotent")
        val batch = ArtifactWriteBatch(
            artifact = makeArtifact("src/A.kt"),
            clearExisting = true,
            nodes = listOf(
                makeNode("src/A.kt#OrderStateMachine", "OrderStateMachine", NodeType.Class),
                makeNode("src/A.kt#advanceState", "advanceState", NodeType.Method),
                makeNode("src/A.kt", "A.kt", NodeType.CodeFile)
            ),
            edges = emptyList(),
            references = emptyList()
        )

        SqliteStorageAdapter(dbPath).use { storage ->
            storage.writeArtifactBatch(batch)
            val afterFirst = segmentRows(dbPath)
            // OrderStateMachine -> Order, State, Machine (3); advanceState -> advance, State (2);
            // A.kt is a CodeFile (a file node) and contributes nothing.
            afterFirst.size shouldBe 5

            // Re-index the same project a second time, no source change.
            storage.writeArtifactBatch(batch)
            val afterSecond = segmentRows(dbPath)

            afterSecond.size shouldBe afterFirst.size
            afterSecond shouldContainExactlyInAnyOrder afterFirst
        }
    }

    test("single-node path: re-upserting the same node twice leaves the row count identical") {
        val dbPath = freshDbPath("idempotent-single")
        val node = makeNode("n1", "RungDistribution")
        SqliteStorageAdapter(dbPath).use { storage ->
            storage.upsertNode(node)
            val afterFirst = segmentRows(dbPath)
            storage.upsertNode(node)
            val afterSecond = segmentRows(dbPath)
            afterSecond.size shouldBe afterFirst.size
            afterSecond shouldContainExactlyInAnyOrder afterFirst
        }
    }

    test("single-node and bulk write paths populate the vocabulary identically") {
        val nodes = listOf(
            makeNode("n1", "RungDistribution", NodeType.Class),
            makeNode("n2", "advanceState", NodeType.Method),
            makeNode("n3", "plain", NodeType.Concept),
            makeNode("f1", "SomeCompoundFileName.kt", NodeType.CodeFile)
        )

        val singlePath = freshDbPath("single")
        SqliteStorageAdapter(singlePath).use { storage -> nodes.forEach { storage.upsertNode(it) } }

        val bulkPath = freshDbPath("bulk")
        SqliteStorageAdapter(bulkPath).use { storage -> storage.upsertNodes(nodes) }

        val rows = segmentRows(singlePath)
        rows.isEmpty() shouldBe false
        rows.none { (_, name) -> name == "SomeCompoundFileName.kt" } shouldBe true
        segmentRows(bulkPath) shouldContainExactlyInAnyOrder rows
    }

    test("write half: removing a node via deleteNodesForArtifact leaves its segment rows behind as orphans") {
        val dbPath = freshDbPath("orphans")
        SqliteStorageAdapter(dbPath).use { storage ->
            val node = GraphNode(
                id = NodeId("src/A.kt#RungDistribution"),
                type = NodeType.Class,
                label = "RungDistribution",
                provenance = listOf(
                    Provenance(
                        artifactId = ArtifactId("src/A.kt"),
                        path = "src/A.kt",
                        lineStart = 1,
                        lineEnd = 2,
                        page = null,
                        textSpan = null,
                        extractor = "test",
                        extractedAt = now()
                    )
                ),
                confidence = 1.0
            )
            storage.upsertArtifact(makeArtifact("src/A.kt"))
            storage.upsertNode(node)
            storage.upsertProvenance(node.id.value, "node", node.provenance.first())

            segmentRows(dbPath).size shouldBe 2 // Rung, Distribution

            storage.deleteNodesForArtifact(ArtifactId("src/A.kt"))

            storage.getNode(node.id) shouldBe null
            // Deliberately not swept: no foreign key, no cascade, no sweep pass. The row is a
            // proposal; re-verification against `nodes` at read time is SegmentCandidatesTest's
            // job.
            segmentRows(dbPath).size shouldBe 2
        }
    }

    test("a name containing a single quote round-trips intact through the raw-SQL write, both paths") {
        // The apostrophe can only ever land in the `name` column (a segment is always a run of
        // letters/digits, since SEPARATORS treats the apostrophe as a boundary) -- so this is
        // exactly where the hand-escaping in writeSegmentVocab's raw exec() text has to be
        // correct: a broken escape here either truncates the row at the quote or breaks the
        // VALUES clause outright, neither of which a happy-path fixture would catch.
        val label = "Node'WithQuote"
        val expectedRows = IdentifierSplitter.split(label).map { it.lowercase() }.distinct().map { it to label }
        expectedRows.size shouldBe 3 // node, with, quote -- the apostrophe splits Node from WithQuote

        val singlePath = freshDbPath("quote-single")
        SqliteStorageAdapter(singlePath).use { storage -> storage.upsertNode(makeNode("n1", label)) }
        segmentRows(singlePath) shouldContainExactlyInAnyOrder expectedRows

        val bulkPath = freshDbPath("quote-bulk")
        SqliteStorageAdapter(bulkPath).use { storage -> storage.upsertNodes(listOf(makeNode("n1", label))) }
        segmentRows(bulkPath) shouldContainExactlyInAnyOrder expectedRows
    }

    test("a name with no internal boundary produces exactly the one row it should, unmangled") {
        val dbPath = freshDbPath("no-boundary")
        SqliteStorageAdapter(dbPath).use { storage ->
            storage.upsertNode(makeNode("n1", "plain"))
        }
        segmentRows(dbPath) shouldBe listOf("plain" to "plain")
    }

    test("bulk path over many nodes exercises the chunking boundary and matches the per-row path") {
        // More rows than one INSERT OR IGNORE statement carries (FTS_CHUNK_SIZE = 250), so the
        // row-based chunking in writeSegmentVocab is exercised, not just the node-count case.
        val nodes = (0 until 300).map { i -> makeNode("n$i", "SomeCompoundName$i", NodeType.Class) }

        val singlePath = freshDbPath("single-many")
        SqliteStorageAdapter(singlePath).use { storage -> nodes.forEach { storage.upsertNode(it) } }

        val bulkPath = freshDbPath("bulk-many")
        SqliteStorageAdapter(bulkPath).use { storage -> storage.upsertNodes(nodes) }

        val expected = segmentRows(singlePath)
        val expectedTotal = nodes.sumOf { IdentifierSplitter.split(it.label).distinct().size }
        expectedTotal shouldBe expected.size
        // FTS_CHUNK_SIZE (SqliteStorageAdapter.kt) is 250; 300 nodes x 4 segments each is well
        // past that, so this proves the row-chunking boundary is actually crossed, not just the
        // node-count case a smaller fixture would exercise.
        (expectedTotal > 250) shouldBe true
        segmentRows(bulkPath) shouldContainExactlyInAnyOrder expected
    }
})

private fun <T> SqliteStorageAdapter.use(block: (SqliteStorageAdapter) -> T): T = try {
    block(this)
} finally {
    close()
}
