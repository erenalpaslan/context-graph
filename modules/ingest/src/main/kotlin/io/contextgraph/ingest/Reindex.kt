package io.contextgraph.ingest

import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractorRegistry
import io.contextgraph.extractors.ConfigExtractor
import io.contextgraph.extractors.MarkdownExtractor
import io.contextgraph.extractors.PdfExtractor
import io.contextgraph.extractors.SemanticExtractor
import io.contextgraph.extractors.SqlExtractor
import io.contextgraph.extractors.TreeSitterExtractor
import io.contextgraph.storage.SqliteStorageAdapter
import kotlinx.coroutines.runBlocking
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock

/**
 * The one reindex primitive every trigger wraps -- CLI `index`/`refresh`, the opt-in watcher,
 * `ci-reindex` (all three in `modules:cli`'s `Freshness.kt`), and MCP's `contextgraph.index_project`
 * tool (`modules:mcp-server`'s `McpServer.kt`). None of them re-implement extraction: they only
 * decide *which db path* to write and *when* to call [run].
 *
 * Lives in `modules:ingest` -- not `modules:cli`, where slice 13 first built it -- because
 * `modules:cli` depends on `modules:mcp-server` (for `ServeMcpCommand`), so MCP calling into an
 * `internal` primitive owned by `cli` would be a circular Gradle project dependency. Both `cli`
 * and `mcp-server` already depend on `modules:ingest` (home of [IngestPipeline] itself),
 * `modules:extractors`, and `modules:storage-sqlite`, and neither of those depends back on
 * `modules:ingest`, so collecting the primitive here needed no new dependency edge that didn't
 * already exist in one direction.
 */
object ReindexPrimitive {
    private val jvmLock = ReentrantLock()

    /**
     * The one place the production extractor list is registered and an [IngestPipeline] is
     * constructed for reindexing. `modules:cli`'s `Main.kt` and `modules:mcp-server`'s
     * `McpServer.kt` used to each build this list themselves; collapsing it here is what
     * guarantees the two module's extraction behavior can never drift apart.
     */
    fun buildPipeline(config: ContextGraphConfig, storage: SqliteStorageAdapter, projectPath: Path): IngestPipeline {
        val registry = ExtractorRegistry(
            listOf(
                MarkdownExtractor(), TreeSitterExtractor(), PdfExtractor(),
                SqlExtractor(), ConfigExtractor(), SemanticExtractor()
            )
        )
        return IngestPipeline(
            discovery = FileDiscovery(config),
            registry = registry,
            checksumTracker = ChecksumTracker(),
            storage = storage,
            context = ExtractionContext(projectPath, config)
        )
    }

    /**
     * Builds the pipeline via [buildPipeline] and runs [IngestPipeline]'s two-pass index
     * against [root], with all writes funnelled through [dbPath].
     *
     * Guards against two triggers firing close together corrupting the database or deadlocking:
     * a [ReentrantLock] serializes concurrent callers within this JVM (a second `FileChannel.lock()`
     * call from the *same* process would throw `OverlappingFileLockException` instead of blocking,
     * since OS file locks are only advisory across processes, not threads), and a sibling
     * `<dbPath>.lock` file, taken for the same critical section, serializes callers across
     * separate processes (e.g. a `refresh` invocation while the watcher is running). Both locks
     * are acquired and released around one bounded operation with no other lock taken inside it,
     * so there is nothing to deadlock against.
     */
    fun run(root: Path, config: ContextGraphConfig, dbPath: Path): IndexStats {
        dbPath.parent?.let { Files.createDirectories(it) }
        val lockPath = dbPath.resolveSibling("${dbPath.fileName}.lock")

        jvmLock.lock()
        try {
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val storage = SqliteStorageAdapter(dbPath)
                    try {
                        val pipeline = buildPipeline(config, storage, root)
                        return runBlocking { pipeline.index(root) }
                    } finally {
                        storage.close()
                    }
                }
            }
        } finally {
            jvmLock.unlock()
        }
    }
}
