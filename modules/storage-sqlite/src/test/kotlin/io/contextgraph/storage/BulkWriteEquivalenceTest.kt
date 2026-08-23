package io.contextgraph.storage

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ArtifactWriteBatch
import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.core.UnresolvedReference
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.sql.DriverManager

/** Joins a row's columns for comparison. Unit Separator, so no column value can contain it. */
private const val COLUMN_SEPARATOR = "\u001F"

/**
 * The bulk write verbs exist purely to change what a write *costs*. This proves they did not
 * change what a write *means*.
 *
 * Two databases are built from identical inputs -- one through the per-row verbs the pipeline
 * used to call, one through the bulk verbs it calls now -- and every row of every table is
 * compared. Read back through raw SQL rather than through this adapter's own getters, because
 * a getter that lost a column would hide the loss from both sides equally.
 *
 * This is the unit-level form of the whole-corpus graph diff the change was measured against;
 * it runs in a second and fails on the same class of defect.
 */
class BulkWriteEquivalenceTest : FunSpec({

    fun now() = Clock.System.now()

    fun provenanceOf(artifactId: String, path: String, line: Int) = Provenance(
        artifactId = ArtifactId(artifactId),
        path = path,
        lineStart = line,
        lineEnd = line + 3,
        page = null,
        textSpan = if (line % 2 == 0) null else "span-$line",
        extractor = "tree-sitter",
        extractedAt = now()
    )

    /**
     * Deliberately awkward inputs: an apostrophe (the character the FTS statements escape by
     * hand), a camelCase identifier (which the FTS label splitter expands), a node with no
     * provenance and one with several, and a nested JSON property blob.
     */
    fun sampleBatch(artifactId: String, clearExisting: Boolean): ArtifactWriteBatch {
        val artifact = Artifact(
            id = ArtifactId(artifactId),
            type = NodeType.CodeFile,
            path = artifactId,
            checksum = "sum-$artifactId",
            size = 4096L,
            lastModified = now(),
            indexedAt = now()
        )
        val nodes = listOf(
            GraphNode(
                id = NodeId("$artifactId#OrderStateMachine"),
                type = NodeType.Class,
                label = "OrderStateMachine",
                properties = mapOf(
                    "fqn" to JsonPrimitive("com.example.OrderStateMachine"),
                    "supertypes" to JsonArray(listOf(JsonPrimitive("Machine"), JsonPrimitive("Stateful")))
                ),
                provenance = listOf(provenanceOf(artifactId, artifactId, 10), provenanceOf(artifactId, artifactId, 40)),
                confidence = 0.91
            ),
            GraphNode(
                id = NodeId("$artifactId#it's_a_name"),
                type = NodeType.Method,
                label = "it's a name",
                properties = mapOf("returns" to JsonPrimitive("Don't")),
                provenance = emptyList(),
                confidence = 1.0
            ),
            GraphNode(
                id = NodeId("$artifactId#plain"),
                type = NodeType.Custom("Interface"),
                label = "plain",
                provenance = listOf(provenanceOf(artifactId, artifactId, 77)),
                confidence = 0.5
            )
        )
        val edges = listOf(
            GraphEdge(
                id = EdgeId("contains:$artifactId:OrderStateMachine"),
                source = NodeId(artifactId),
                target = NodeId("$artifactId#OrderStateMachine"),
                type = EdgeType.Contains,
                properties = mapOf("lines" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))),
                confidence = 1.0
            ),
            GraphEdge(
                id = EdgeId("imports:$artifactId:Machine"),
                source = NodeId(artifactId),
                target = NodeId("$artifactId#Machine"),
                type = EdgeType.Imports,
                confidence = 0.8
            )
        )
        val references = listOf(
            UnresolvedReference(
                referenceName = "advance",
                referringSymbolId = NodeId("$artifactId#OrderStateMachine.run()"),
                repoRelativePath = artifactId,
                artifactId = ArtifactId(artifactId),
                line = 12,
                receiverType = "Machine",
                receiverCall = null
            ),
            UnresolvedReference(
                referenceName = "getId",
                referringSymbolId = NodeId("$artifactId#OrderStateMachine.run()"),
                repoRelativePath = artifactId,
                artifactId = ArtifactId(artifactId),
                line = 13,
                receiverType = null,
                receiverCall = "getParentSession"
            )
        )
        return ArtifactWriteBatch(artifact, clearExisting, nodes, edges, references)
    }

    /** Every row of every table, as text, in a deterministic order. */
    fun dump(dbPath: java.nio.file.Path): Map<String, List<String>> {
        val queries = mapOf(
            "artifacts" to "SELECT id,type,path,checksum,size FROM artifacts ORDER BY id",
            "nodes" to "SELECT id,type,label,properties,confidence FROM nodes ORDER BY id",
            "edges" to "SELECT id,source_id,target_id,type,properties,confidence FROM edges ORDER BY id",
            "provenance" to "SELECT entity_id,entity_kind,artifact_id,path,line_start,line_end,page,text_span,extractor FROM provenance ORDER BY entity_id,line_start",
            "unresolved_references" to "SELECT artifact_id,repo_relative_path,reference_name,referring_symbol_id,line,receiver_type,receiver_call FROM unresolved_references ORDER BY line",
            "nodes_fts" to "SELECT id,label,properties FROM nodes_fts ORDER BY id"
        )
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            return queries.mapValues { (_, sql) ->
                conn.createStatement().executeQuery(sql).use { rs ->
                    val cols = rs.metaData.columnCount
                    val rows = mutableListOf<String>()
                    while (rs.next()) {
                        rows += (1..cols).joinToString(COLUMN_SEPARATOR) { rs.getString(it) ?: "<null>" }
                    }
                    rows
                }
            }
        }
    }

    fun freshDbPath(name: String) = Files.createTempDirectory("bulk-equivalence-$name").resolve("graph.db")

    test("writeArtifactBatch persists exactly what the per-row calls it replaced persisted") {
        val batches = listOf(sampleBatch("src/A.kt", clearExisting = true), sampleBatch("src/B.kt", clearExisting = true))

        val rowByRowPath = freshDbPath("rows")
        SqliteStorageAdapter(rowByRowPath).use { storage ->
            batches.forEach { batch ->
                // The exact sequence IngestPipeline's consumer used to run, in the same order.
                storage.deleteNodesForArtifact(batch.artifact.id)
                storage.deleteUnresolvedReferencesForArtifact(batch.artifact.id)
                storage.upsertArtifact(batch.artifact)
                batch.nodes.forEach { storage.upsertNode(it) }
                batch.edges.forEach { storage.upsertEdge(it) }
                batch.references.forEach { storage.insertUnresolvedReference(it) }
                batch.nodes.forEach { n -> n.provenance.forEach { storage.upsertProvenance(n.id.value, "node", it) } }
            }
        }

        val bulkPath = freshDbPath("bulk")
        SqliteStorageAdapter(bulkPath).use { storage ->
            batches.forEach { storage.writeArtifactBatch(it) }
        }

        val expected = dump(rowByRowPath)
        val actual = dump(bulkPath)
        expected.keys.forEach { table ->
            withClue(table) { actual[table] shouldBe expected[table] }
        }
    }

    test("upsertNodes and upsertEdges persist exactly what upsertNode and upsertEdge persisted") {
        // More nodes than one FTS statement carries, so the chunking boundary is exercised.
        val nodes = (0 until 640).map { i ->
            GraphNode(
                id = NodeId("n$i"),
                type = if (i % 3 == 0) NodeType.Method else NodeType.Class,
                label = if (i % 5 == 0) "Node'With${i}Quote" else "SomeCamelCaseName$i",
                properties = mapOf("i" to JsonPrimitive(i)),
                confidence = 1.0 - (i % 7) / 10.0
            )
        }
        val edges = (1 until 640).map { i ->
            GraphEdge(EdgeId("e$i"), NodeId("n${i - 1}"), NodeId("n$i"), EdgeType.Calls, confidence = 0.6)
        }

        val singlePath = freshDbPath("single")
        SqliteStorageAdapter(singlePath).use { storage ->
            nodes.forEach { storage.upsertNode(it) }
            edges.forEach { storage.upsertEdge(it) }
        }

        val bulkPath = freshDbPath("many")
        SqliteStorageAdapter(bulkPath).use { storage ->
            storage.upsertNodes(nodes)
            storage.upsertEdges(edges)
        }

        val expected = dump(singlePath)
        val actual = dump(bulkPath)
        listOf("nodes", "edges", "nodes_fts").forEach { table ->
            withClue(table) { actual[table] shouldBe expected[table] }
        }
        withClue("every node reached the search index") { actual.getValue("nodes_fts").size shouldBe nodes.size }
    }

    test("a batch that re-indexes an artifact clears the previous run's rows exactly once") {
        val dbPath = freshDbPath("reindex")
        SqliteStorageAdapter(dbPath).use { storage ->
            storage.writeArtifactBatch(sampleBatch("src/A.kt", clearExisting = true))

            // A second extractor's result for the SAME artifact: clearExisting = false, because
            // clearing again would wipe the rows the first result just wrote.
            val second = sampleBatch("src/A.kt", clearExisting = false).let { batch ->
                batch.copy(
                    nodes = listOf(
                        GraphNode(
                            id = NodeId("src/A.kt#SecondExtractorNode"),
                            type = NodeType.Concept,
                            label = "SecondExtractorNode",
                            provenance = listOf(provenanceOf("src/A.kt", "src/A.kt", 99))
                        )
                    ),
                    edges = emptyList(),
                    references = emptyList()
                )
            }
            storage.writeArtifactBatch(second)

            val nodeIds = dump(dbPath).getValue("nodes").map { it.substringBefore(COLUMN_SEPARATOR) }
            withClue("first result's nodes survived the second result") {
                nodeIds.contains("src/A.kt#OrderStateMachine") shouldBe true
            }
            withClue("second result's node was added") {
                nodeIds.contains("src/A.kt#SecondExtractorNode") shouldBe true
            }
        }
    }
})

private fun <T> SqliteStorageAdapter.use(block: (SqliteStorageAdapter) -> T): T = try {
    block(this)
} finally {
    close()
}
