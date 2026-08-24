package io.contextgraph.storage

import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Every database built before search rows were keyed on `nodes.rowid` carries duplicates and
 * orphans. This is the proof that opening one repairs it, without a re-index.
 *
 * A re-index would not do the job: extraction skips any artifact whose checksum still matches,
 * so re-running `index` over an unchanged repository rewrites almost nothing and the stale rows
 * simply stay. Worse, a legacy row sits at whatever rowid FTS5 auto-assigned it, which has no
 * relation to its node's -- so the fixed write path would insert *beside* it rather than over
 * it. Without the repair the fix would be inert on every database that already exists.
 *
 * The legacy state is reconstructed rather than described: search rows written the old way (no
 * rowid supplied, so FTS5 assigns its own), duplicated per node the way a re-upsert used to
 * duplicate them, an orphan left behind for a node that no longer exists, and the migration's
 * own history row removed so that opening the database runs it exactly as it would run on a
 * database that predates it.
 */
class NodesFtsRepairTest : FunSpec({

    fun freshDbPath(name: String): Path =
        Files.createTempDirectory("fts-repair-$name").resolve("graph.db")

    fun connect(dbPath: Path): Connection =
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")

    fun scalar(dbPath: Path, sql: String): Long = connect(dbPath).use { connection ->
        connection.createStatement().executeQuery(sql).use { rows ->
            rows.next()
            rows.getLong(1)
        }
    }

    fun ftsCount(dbPath: Path) = scalar(dbPath, "SELECT count(*) FROM nodes_fts")
    fun nodeCount(dbPath: Path) = scalar(dbPath, "SELECT count(*) FROM nodes")
    fun orphanCount(dbPath: Path) = scalar(
        dbPath,
        "SELECT count(*) FROM nodes_fts f WHERE NOT EXISTS (SELECT 1 FROM nodes n WHERE n.rowid = f.rowid)"
    )
    fun matchCount(dbPath: Path, term: String) =
        scalar(dbPath, "SELECT count(*) FROM nodes_fts WHERE nodes_fts MATCH '\"$term\"'")
    fun repairHistoryRows(dbPath: Path) = scalar(
        dbPath,
        "SELECT count(*) FROM flyway_schema_history WHERE version = '7'"
    )

    /**
     * Turns a correctly-built database into what a pre-fix one looks like, and rewinds the
     * migration history so the repair has not run on it yet.
     */
    fun makeLegacy(dbPath: Path, duplicatesPerNode: Int) {
        connect(dbPath).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM nodes_fts")
                statement.executeUpdate("DELETE FROM flyway_schema_history WHERE version = '7'")
            }
            connection.createStatement().use { statement ->
                // The old write: no rowid supplied, so `INSERT OR REPLACE` had nothing to
                // conflict against and every repetition appended another row.
                repeat(duplicatesPerNode) {
                    statement.executeUpdate(
                        "INSERT OR REPLACE INTO nodes_fts(id, label, properties) " +
                            "SELECT id, label, properties FROM nodes"
                    )
                }
                // And an orphan: a search row for a node that has since been deleted, which
                // nothing in the pre-fix code ever removed.
                statement.executeUpdate(
                    "INSERT INTO nodes_fts(id, label, properties) VALUES ('deleted-long-ago', 'GhostSymbol', '{}')"
                )
            }
        }
    }

    fun node(id: String, label: String) = GraphNode(
        id = NodeId(id),
        type = NodeType.Class,
        label = label,
        properties = mapOf("marker" to JsonPrimitive(id)),
        confidence = 1.0
    )

    test("opening a polluted database repairs it, with no re-index") {
        val dbPath = freshDbPath("polluted")
        val nodes = (0 until 120).map { node("n$it", "RungDistribution$it") }

        SqliteStorageAdapter(dbPath).use { it.upsertNodes(nodes) }
        makeLegacy(dbPath, duplicatesPerNode = 3)

        withClue("the legacy state really is polluted before we open it") {
            ftsCount(dbPath) shouldBe (nodes.size * 3 + 1).toLong()
        }
        withClue("but not every polluted row *looks* wrong, which is why counting alone cannot repair") {
            // FTS5 hands the legacy rows rowids 1..361 in insertion order, so the first 120 of
            // them land on the same rowids the 120 live nodes happen to occupy and pass an
            // orphan check while still being the wrong rows. Only the remaining 241 are
            // detectable as orphans. This is exactly why the repair rebuilds from `nodes`
            // rather than trying to find and patch the bad rows.
            orphanCount(dbPath) shouldBe (nodes.size * 2 + 1).toLong()
        }

        // Opening is the whole repair: no index run, no explicit call.
        SqliteStorageAdapter(dbPath).use { }

        withClue("one search row per node after opening") { ftsCount(dbPath) shouldBe nodeCount(dbPath) }
        withClue("the orphan is gone") { orphanCount(dbPath) shouldBe 0L }
        withClue("the ghost row's term is gone from the index") {
            matchCount(dbPath, "GhostSymbol") shouldBe 0L
        }
        withClue("search still works on a whole label") { matchCount(dbPath, "RungDistribution7") shouldBe 1L }
        withClue("and by an identifier segment -- the repair writes the augmented text, not the raw label") {
            // "RungDistribution7" splits to Rung / Distribution / 7, so the segment reaches
            // every one of these nodes. Zero here would mean the repair had written the plain
            // label and quietly made compound identifiers unfindable by their parts.
            matchCount(dbPath, "Distribution") shouldBe nodes.size.toLong()
        }
    }

    test("the repair runs once, not on every open") {
        val dbPath = freshDbPath("once")
        SqliteStorageAdapter(dbPath).use { it.upsertNodes((0 until 10).map { i -> node("n$i", "SomeName$i") }) }
        makeLegacy(dbPath, duplicatesPerNode = 2)

        SqliteStorageAdapter(dbPath).use { }
        val afterFirstOpen = ftsCount(dbPath)

        SqliteStorageAdapter(dbPath).use { }
        SqliteStorageAdapter(dbPath).use { }

        withClue("further opens change nothing") { ftsCount(dbPath) shouldBe afterFirstOpen }
        withClue("and the repair is recorded exactly once") { repairHistoryRows(dbPath) shouldBe 1L }
    }

    test("a repaired database is byte-for-byte what a freshly indexed one would hold") {
        // The repair and the write path must produce the same searchable text; if they ever
        // diverged, a repaired database would answer queries differently from a rebuilt one and
        // nothing would notice until something stopped being found.
        val nodes = (0 until 50).map { node("n$it", "ParseHTTPResponse$it") }

        val repaired = freshDbPath("repaired")
        SqliteStorageAdapter(repaired).use { it.upsertNodes(nodes) }
        makeLegacy(repaired, duplicatesPerNode = 2)
        SqliteStorageAdapter(repaired).use { }

        val fresh = freshDbPath("fresh")
        SqliteStorageAdapter(fresh).use { it.upsertNodes(nodes) }

        fun ftsDump(dbPath: Path): List<String> = connect(dbPath).use { connection ->
            connection.createStatement()
                .executeQuery("SELECT rowid, id, label, properties FROM nodes_fts ORDER BY rowid")
                .use { rows ->
                    val dumped = mutableListOf<String>()
                    while (rows.next()) {
                        dumped += "${rows.getLong(1)}|${rows.getString(2)}|${rows.getString(3)}|${rows.getString(4)}"
                    }
                    dumped
                }
        }

        ftsDump(repaired) shouldBe ftsDump(fresh)
    }

    test("a brand new database is untouched by the repair") {
        val dbPath = freshDbPath("new")
        SqliteStorageAdapter(dbPath).use { }

        withClue("nothing to repair, and the migration still recorded so it never runs again") {
            ftsCount(dbPath) shouldBe 0L
            repairHistoryRows(dbPath) shouldBe 1L
        }
    }
})

/** `StorageAdapter` declares `close()` without extending `AutoCloseable`; same helper as the
 *  neighbouring tests use, kept file-private for the same reason. */
private fun <T> SqliteStorageAdapter.use(block: (SqliteStorageAdapter) -> T): T = try {
    block(this)
} finally {
    close()
}
