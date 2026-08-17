package io.contextgraph.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists

/**
 * Resolves the two-file graph database topology inside `.contextgraph/`:
 *
 *  - **`graph.db`** — the committed baseline. Written only by CI. Answers queries on a
 *    fresh clone before any local indexing has happened.
 *  - **`graph.local.db`** — the gitignored developer/watcher overlay. Seeded by copying
 *    the baseline the first time a local write is needed, then owned by the CLI and the
 *    watcher from then on.
 *
 * This is the single seam every caller (CLI, MCP server, watcher, CI script) goes through
 * to learn which physical file to open. The single-writer invariant on the baseline — the
 * whole reason this split exists — depends on non-CI callers only ever asking for
 * [forRead] or [forLocalWrite], never opening [baseline] directly. A CI writer that wants
 * to update the committed baseline asks for [baseline] explicitly instead.
 *
 * [forRead] only *resolves a path*; it never touches disk. Storage adapters (e.g.
 * `SqliteStorageAdapter`) unconditionally create and schema-migrate whatever path they
 * are handed, so a read-only caller must check [exists] before opening storage on the
 * result of [forRead] — otherwise a query against a repo with no graph at all would
 * silently materialize an empty `.contextgraph/graph.db` at the path only CI is allowed
 * to write.
 */
object GraphDb {
    const val BASELINE_FILE_NAME = "graph.db"
    const val OVERLAY_FILE_NAME = "graph.local.db"

    /** The committed baseline path. CI is the only writer of this file. */
    fun baseline(root: Path): Path = root.resolve(".contextgraph").resolve(BASELINE_FILE_NAME)

    /** The gitignored local overlay path. */
    fun overlay(root: Path): Path = root.resolve(".contextgraph").resolve(OVERLAY_FILE_NAME)

    /**
     * True if there is any graph at all to read — baseline or overlay. Callers that only
     * want to read (never to seed or create) must check this before opening storage on
     * [forRead]'s result: opening storage on a nonexistent path creates it.
     */
    fun exists(root: Path): Boolean = overlay(root).exists() || baseline(root).exists()

    /**
     * The path to open for reads: the overlay when it exists, the committed baseline
     * otherwise. Transparent to callers — nothing about which file answered the query
     * is exposed.
     *
     * This is a pure path computation and may return a path that exists on disk. When
     * neither file exists yet, it still returns the baseline path — callers must guard
     * with [exists] first rather than opening storage unconditionally on the result.
     */
    fun forRead(root: Path): Path {
        val overlayPath = overlay(root)
        return if (overlayPath.exists()) overlayPath else baseline(root)
    }

    /**
     * The path to open for a local write (developer CLI index, watcher). If the overlay
     * does not exist yet, it is seeded by copying the baseline so the first local index
     * starts warm instead of from scratch. If the baseline doesn't exist either (e.g. a
     * brand new repo before CI has ever produced one), the overlay starts empty and the
     * caller's storage adapter creates the schema on open. Never returns [baseline] — a
     * local write must never land on the file CI owns.
     *
     * The seed copy lands in a temp file in the same directory first, then an atomic
     * rename publishes it as the overlay. A process killed mid-copy therefore leaves
     * either nothing (retried next time, since the temp file is never mistaken for the
     * overlay) or a complete overlay — never a truncated file sitting at the overlay
     * path, which the `!overlayPath.exists()` guard above would otherwise mistake for
     * an already-seeded overlay and never retry.
     */
    fun forLocalWrite(root: Path): Path {
        val overlayPath = overlay(root)
        if (!overlayPath.exists()) {
            val baselinePath = baseline(root)
            val overlayDir = overlayPath.parent
            overlayDir?.let { Files.createDirectories(it) }
            if (baselinePath.exists()) {
                val tempPath = Files.createTempFile(overlayDir, OVERLAY_FILE_NAME, ".seeding")
                try {
                    Files.copy(baselinePath, tempPath, StandardCopyOption.REPLACE_EXISTING)
                    Files.move(tempPath, overlayPath, StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(tempPath)
                }
            }
        }
        return overlayPath
    }
}
