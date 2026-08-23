package io.contextgraph.ingest

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ArtifactWriteBatch
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractionDiagnostic
import io.contextgraph.core.ExtractionResult
import io.contextgraph.core.ExtractorRegistry
import io.contextgraph.core.StorageAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.toKotlinInstant
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.fileSize
import kotlin.io.path.readAttributes

private val logger = KotlinLogging.logger {}

/**
 * Runs [block], charging its elapsed nanoseconds to [phase].
 *
 * `inline` so it composes with `suspend` call sites without a wrapper coroutine, and so the
 * measurement costs two `nanoTime` reads and nothing else. Charged in a `finally` block, so a
 * phase that throws still reports the time it burned before failing -- which is exactly when
 * you want to know.
 */
private inline fun <T> IndexStats.timed(phase: IngestPhase, block: () -> T): T {
    val startedAt = System.nanoTime()
    try {
        return block()
    } finally {
        addPhaseNanos(phase, System.nanoTime() - startedAt)
    }
}

class IngestPipeline(
    private val discovery: FileDiscovery,
    private val registry: ExtractorRegistry,
    private val checksumTracker: ChecksumTracker,
    private val storage: StorageAdapter,
    private val context: ExtractionContext,
    private val moduleDetector: ModuleDetector = ModuleDetector()
) {
    suspend fun index(root: Path): IndexStats {
        val stats = IndexStats()
        val startedAt = System.nanoTime()

        stats.timed(IngestPhase.MODULE_DETECTION) { indexModules(root, stats) }

        stats.timed(IngestPhase.PASS_1) { runPass1(root, stats) }

        // Pass 2: resolve every persisted unresolved reference -- including ones belonging
        // to files pass 1 skipped this run -- against the current symbol table, and
        // materialise Calls edges. Runs only after pass 1's producer/consumer scope has
        // fully drained, so this is never a second concurrent writer -- it's the same
        // sequential caller, one step later.
        val resolution = stats.timed(IngestPhase.PASS_2_RESOLVE) { ReferenceResolver(storage).resolveAll() }
        stats.addEdges(resolution.resolved)
        logger.debug { "Pass 2 resolved ${resolution.resolved} Calls edges (${resolution.unresolved} references left unresolved)" }

        // Pass 3: group declaration sites that share an fqn -- a Swift type and its
        // extensions, an ObjC @interface/@implementation pair, a Kotlin expect/actual split
        // -- into SiblingOf edges. Like pass 2 this deletes and rebuilds its whole edge set
        // each run, so it inherits the same incremental correctness for free, and it runs in
        // the same sequential caller rather than as a second concurrent writer.
        val grouping = stats.timed(IngestPhase.PASS_3_GROUP) { SiblingGrouper(storage).groupAll() }
        stats.addEdges(grouping.edges)
        logger.debug { "Pass 3 linked ${grouping.edges} SiblingOf edges across ${grouping.groups} fqn group(s)" }

        stats.setTotalNanos(System.nanoTime() - startedAt)
        return stats
    }

    /**
     * Pass 1: discover files, extract them concurrently, and persist every result through one
     * writer.
     *
     * Its own function rather than a block inside [index] so that [index] reads as the four
     * phases it actually is -- and so the phase timer around it wraps something with a name.
     */
    private suspend fun runPass1(root: Path, stats: IndexStats) {
        // Every previously-indexed artifact's checksum, read once.
        //
        // This used to be one indexed lookup per discovered file, issued from whichever of the
        // extraction coroutines happened to be triaging it -- so on a repo of Keycloak's size,
        // twelve thousand concurrent single-row reads, each opening its own SQLite connection,
        // against the file the single write consumer is holding a lock on. The answers cannot
        // change while pass 1 runs: an artifact row is only written by the processing of that
        // same artifact, so no file's checksum decision can be affected by another file's.
        // Reading them all up front is therefore the same decision, taken off the contended
        // path -- one query instead of twelve thousand, and none of them racing the writer.
        val previousChecksums = storage.getAllArtifacts().associate { it.id to it.checksum }

        coroutineScope {
            val resultChannel = Channel<ExtractionResult>(capacity = 100)

            // Producer: concurrent extraction, sequential DB writes stay in consumer
            launch {
                coroutineScope {
                    discovery.discover(root).collect { path ->
                        launch(Dispatchers.IO) {
                            try {
                                extractFile(path, stats, resultChannel, previousChecksums)
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to process $path" }
                                stats.incrementFailed()
                            }
                        }
                    }
                }
                resultChannel.close()
            }

            // Consumer: all DB writes happen sequentially here to avoid SQLite lock contention.
            //
            // One artifact can match several extractors (e.g. a .plist matches both
            // MarkdownExtractor and SemanticExtractor -- see ArtifactTypeDetector), so this
            // channel can carry more than one ExtractionResult per artifact. Extraction runs
            // concurrently on Dispatchers.IO, so those results are not guaranteed to arrive
            // back-to-back -- another artifact's result can land in between. deleteNodesForArtifact
            // must therefore run exactly once per artifact per index() call: on its *first*
            // result, to clear whatever a previous index run left behind (preserving incremental
            // reindex semantics), never on a later result for the same artifact, which would
            // otherwise wipe the nodes an earlier result for that same artifact just wrote.
            // clearedArtifacts is local to this single-threaded consumer loop, so no locking is
            // needed and no second writer is introduced.
            val clearedArtifacts = HashSet<ArtifactId>()
            for (result in resultChannel) {
                val writeStartedAt = System.nanoTime()
                try {
                    // Surface pass 1's parse diagnostics (AC-3): a covered language whose
                    // grammar produced an error node gets logged and counted here, distinct
                    // from stats.incrementFailed() below, which means "the extractor threw."
                    // An uncovered file type raises no diagnostic at all (TreeSitterExtractor),
                    // so it never reaches this branch -- only a genuine grammar failure does.
                    // Logged (not persisted): no second writer, no new migration, and the
                    // per-file detail this needs already lives in the diagnostic's own message.
                    if (result.diagnostics.isNotEmpty()) {
                        result.diagnostics.forEach { diagnostic ->
                            when (diagnostic.severity) {
                                ExtractionDiagnostic.Severity.ERROR -> logger.error(diagnostic.cause) { diagnostic.message }
                                ExtractionDiagnostic.Severity.WARNING -> logger.warn(diagnostic.cause) { diagnostic.message }
                                ExtractionDiagnostic.Severity.INFO -> logger.info(diagnostic.cause) { diagnostic.message }
                            }
                        }
                        stats.addParseWarnings(result.diagnostics.size)
                    }
                    val firstResultForArtifact = clearedArtifacts.add(result.artifact.id)
                    if (firstResultForArtifact) {
                        // Counted here, on an artifact's first result only, for the same reason
                        // the clear is: several extractors can each yield a result for one
                        // artifact, and counting per result reports more artifacts than the
                        // graph actually holds (a .plist matching two extractors counted twice).
                        stats.incrementArtifacts()
                    }
                    // One call, not five loops: the adapter decides how to make it cheap, and
                    // its default implementation is exactly the loops this replaced, in the
                    // same order, so the rows written cannot differ.
                    storage.writeArtifactBatch(
                        ArtifactWriteBatch(
                            artifact = result.artifact,
                            clearExisting = firstResultForArtifact,
                            nodes = result.nodes,
                            edges = result.edges,
                            references = result.references
                        )
                    )
                    stats.addNodes(result.nodes.size)
                    stats.addEdges(result.edges.size)
                    logger.debug { "Indexed ${result.artifact.path}: ${result.nodes.size} nodes, ${result.edges.size} edges, ${result.references.size} unresolved references" }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to store result for ${result.artifact.path}" }
                    stats.incrementFailed()
                } finally {
                    stats.addPhaseNanos(IngestPhase.DB_WRITE, System.nanoTime() - writeStartedAt)
                }
            }
        }
    }

    /**
     * Detects module boundaries (build-file or config-declared) and persists their nodes through
     * the same [StorageAdapter] every other node goes through. Runs sequentially, before the
     * concurrent extraction phase starts, so it never competes with the single-consumer DB-write
     * channel below for SQLite access. Detection is a pure, deterministic read of build-file
     * text — no LLM calls, no network — so it stays on the indexing path unconditionally.
     */
    private suspend fun indexModules(root: Path, stats: IndexStats) {
        val tree = withContext(Dispatchers.IO) { moduleDetector.detect(root, context.config) }
        val moduleNodes = tree.toGraphNodes()
        storage.upsertNodes(moduleNodes)
        stats.addNodes(moduleNodes.size)
    }

    private suspend fun extractFile(
        path: Path,
        stats: IndexStats,
        channel: Channel<ExtractionResult>,
        previousChecksums: Map<ArtifactId, String>
    ) {
        val type = ArtifactTypeDetector.detect(path)
        val extractors = registry.findExtractors(type)
        if (extractors.isEmpty()) return

        val triageStartedAt = System.nanoTime()
        val checksum = checksumTracker.checksum(path)
        val attrs = withContext(Dispatchers.IO) { path.readAttributes<BasicFileAttributes>() }
        val repoRelativePath = repoRelativePathOf(context.projectRoot, path)

        val artifact = Artifact(
            id = ArtifactId(repoRelativePath),
            type = type,
            path = repoRelativePath,
            checksum = checksum,
            size = path.fileSize(),
            lastModified = attrs.lastModifiedTime().toInstant().toKotlinInstant(),
            indexedAt = Clock.System.now()
        )

        val previousChecksum = previousChecksums[artifact.id]
        stats.addPhaseNanos(IngestPhase.FILE_TRIAGE, System.nanoTime() - triageStartedAt)
        if (previousChecksum == checksum) {
            stats.incrementSkipped()
            return
        }

        for (extractor in extractors) {
            try {
                val result = stats.timed(IngestPhase.EXTRACTION) { extractor.extract(artifact, context) }
                channel.send(result)
            } catch (e: Exception) {
                // Count it, don't just log it. An extractor that throws drops the whole file
                // from the graph; without this the run still reports "Done: N artifacts" and
                // exits 0, so silent data loss looks identical to a clean index.
                logger.warn(e) { "Extractor ${extractor.id} failed on $path" }
                stats.incrementFailed()
            }
        }
    }

    /**
     * Repo-relative, forward-slash form of [filePath], e.g. `Auth/UserService.java`.
     *
     * [Artifact.id] and [Artifact.path] are keyed on this rather than an absolute path so
     * both stay portable across machines and worktrees: an absolute path bakes this
     * checkout's own filesystem location into every artifact row (and, transitively, into
     * `deleteNodesForArtifact` and checksum-skip identity), which made the committed graph
     * baseline non-reproducible on a different clone. Mirrors
     * `io.contextgraph.extractors.TreeSitterExtractor`'s private `repoRelativePathOf` --
     * same technique, kept local here rather than shared, since that file is outside this
     * slice's ownership.
     */
    private fun repoRelativePathOf(projectRoot: Path, filePath: Path): String {
        val root = projectRoot.toAbsolutePath().normalize()
        val file = filePath.toAbsolutePath().normalize()
        return root.relativize(file).toString().replace(java.io.File.separatorChar, '/')
    }
}
