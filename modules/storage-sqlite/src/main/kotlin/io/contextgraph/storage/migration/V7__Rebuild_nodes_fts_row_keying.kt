package io.contextgraph.storage.migration

import io.contextgraph.storage.FtsIndexText
import io.github.oshai.kotlinlogging.KotlinLogging
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

private val logger = KotlinLogging.logger {}

/** Nodes read, and search rows written, per round trip. Bounds memory on a large graph. */
private const val REBUILD_PAGE_SIZE = 2_000

/**
 * Repairs a `nodes_fts` table built before search rows were keyed on `nodes.rowid`.
 *
 * Every database written by an earlier version carries duplicate search rows -- one extra per
 * re-upsert of a node, because `INSERT OR REPLACE` had no unique key to conflict against on an
 * FTS5 table (`SqliteStorageAdapter.writeFtsRows` explains the defect in full) -- plus orphaned
 * rows for nodes that were later deleted, which nothing ever removed. Measured on excalidraw:
 * 10,602 search rows against 10,383 nodes.
 *
 * **Repairing is not optional, and re-indexing is not a substitute.** `IngestPipeline` skips
 * any artifact whose checksum still matches, so re-running `index` over an unchanged repository
 * rewrites almost no nodes -- a second cold excalidraw index moved the count by 6 rows out of
 * 10,608. Left alone, a database keeps its duplicates indefinitely; and since its legacy rows
 * sit at rowids unrelated to any node's, the fixed write path would add a row *beside* each of
 * them rather than replacing it. Without this migration the fix would be inert on every
 * database that already exists.
 *
 * Rebuilding wholesale rather than trying to detect and patch the broken rows: identifying
 * which of several identically-keyed rows is the live one costs more than simply writing the
 * only correct answer, which is derivable from `nodes` alone. On a fresh database `nodes` is
 * empty when this runs, so it costs nothing and the common path pays no per-open check.
 *
 * Registered explicitly through `Flyway.configure().javaMigrations(...)` rather than left to
 * classpath scanning of `db.migration`, so it cannot silently fail to be discovered in a
 * packaged distribution -- a repair that quietly does not run is worse than one that fails
 * loudly. Flyway runs it inside its own transaction and records it in the schema history, so it
 * happens exactly once per database and a process killed mid-repair leaves the database
 * untouched, to be repaired on the next open.
 *
 * The class name is the version: `BaseJavaMigration`'s constructor parses `V7__…` into version
 * 7 and a description, and it does so *before* any override could supply them, so the name is
 * load-bearing rather than stylistic. It follows the `V6__name_segment_vocab.sql` sequence for
 * the same reason -- a reader looking for migration 7 should find it by looking for "V7".
 */
class V7__Rebuild_nodes_fts_row_keying : BaseJavaMigration() {

    override fun migrate(context: Context) {
        val connection = context.connection

        connection.createStatement().use { it.executeUpdate("DELETE FROM nodes_fts") }

        var lastRowId = 0L
        var rebuilt = 0L
        while (true) {
            val page = readPage(connection, lastRowId)
            if (page.isEmpty()) break
            writePage(connection, page)
            lastRowId = page.last().rowId
            rebuilt += page.size
        }

        logger.info { "nodes_fts rebuilt around nodes.rowid: $rebuilt row(s)" }
    }

    private class NodeRow(val rowId: Long, val id: String, val label: String, val properties: String)

    /**
     * One page of nodes after [afterRowId], fully materialised before it is returned.
     *
     * Paged by rowid rather than streamed, and read completely before anything is written,
     * because reading and writing through one JDBC connection while a result set is still open
     * is exactly the interleaving SQLite is least forgiving about. Keyset pagination rather
     * than `OFFSET` so each page costs a seek, not a re-scan of everything before it.
     */
    private fun readPage(connection: Connection, afterRowId: Long): List<NodeRow> {
        val page = ArrayList<NodeRow>(REBUILD_PAGE_SIZE)
        connection.prepareStatement(
            "SELECT rowid, id, label, properties FROM nodes WHERE rowid > ? ORDER BY rowid LIMIT $REBUILD_PAGE_SIZE"
        ).use { statement ->
            statement.setLong(1, afterRowId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    page += NodeRow(
                        rowId = rows.getLong(1),
                        id = rows.getString(2),
                        label = rows.getString(3),
                        properties = rows.getString(4) ?: "{}"
                    )
                }
            }
        }
        return page
    }

    /**
     * Writes one search row per node of [page], at the node's own rowid, with exactly the text
     * the live write path would have written -- both go through [FtsIndexText.labelFor], so a
     * repaired index and a freshly built one are searchable identically by construction.
     */
    private fun writePage(connection: Connection, page: List<NodeRow>) {
        connection.prepareStatement(
            "INSERT INTO nodes_fts(rowid, id, label, properties) VALUES (?, ?, ?, ?)"
        ).use { statement ->
            page.forEach { node ->
                statement.setLong(1, node.rowId)
                statement.setString(2, node.id)
                statement.setString(3, FtsIndexText.labelFor(node.label))
                statement.setString(4, node.properties)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}
