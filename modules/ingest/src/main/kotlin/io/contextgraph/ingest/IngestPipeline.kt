package io.contextgraph.ingest

import io.contextgraph.core.Artifact
import io.contextgraph.core.ArtifactId
import io.contextgraph.core.ExtractionContext
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

class IngestPipeline(
    private val discovery: FileDiscovery,
    private val registry: ExtractorRegistry,
    private val checksumTracker: ChecksumTracker,
    private val storage: StorageAdapter,
    private val context: ExtractionContext
) {
    suspend fun index(root: Path): IndexStats {
        val stats = IndexStats()

        coroutineScope {
            val resultChannel = Channel<ExtractionResult>(capacity = 100)

            // Producer: concurrent extraction, sequential DB writes stay in consumer
            launch {
                coroutineScope {
                    discovery.discover(root).collect { path ->
                        launch(Dispatchers.IO) {
                            try {
                                extractFile(path, stats, resultChannel)
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to process $path" }
                                stats.incrementFailed()
                            }
                        }
                    }
                }
                resultChannel.close()
            }

            // Consumer: all DB writes happen sequentially here to avoid SQLite lock contention
            for (result in resultChannel) {
                try {
                    storage.deleteNodesForArtifact(result.artifact.id)
                    storage.upsertArtifact(result.artifact)
                    result.nodes.forEach { storage.upsertNode(it) }
                    result.edges.forEach { storage.upsertEdge(it) }
                    result.nodes.forEach { node ->
                        node.provenance.forEach { p -> storage.upsertProvenance(node.id.value, "node", p) }
                    }
                    stats.incrementArtifacts()
                    stats.addNodes(result.nodes.size)
                    stats.addEdges(result.edges.size)
                    logger.debug { "Indexed ${result.artifact.path}: ${result.nodes.size} nodes, ${result.edges.size} edges" }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to store result for ${result.artifact.path}" }
                    stats.incrementFailed()
                }
            }
        }

        return stats
    }

    private suspend fun extractFile(path: Path, stats: IndexStats, channel: Channel<ExtractionResult>) {
        val type = ArtifactTypeDetector.detect(path)
        val extractors = registry.findExtractors(type)
        if (extractors.isEmpty()) return

        val checksum = checksumTracker.checksum(path)
        val attrs = withContext(Dispatchers.IO) { path.readAttributes<BasicFileAttributes>() }

        val artifact = Artifact(
            id = ArtifactId(path.toAbsolutePath().toString()),
            type = type,
            path = path.toAbsolutePath().toString(),
            checksum = checksum,
            size = path.fileSize(),
            lastModified = attrs.lastModifiedTime().toInstant().toKotlinInstant(),
            indexedAt = Clock.System.now()
        )

        val existing = storage.getArtifact(artifact.id)
        if (existing?.checksum == checksum) {
            stats.incrementSkipped()
            return
        }

        for (extractor in extractors) {
            try {
                val result = extractor.extract(artifact, context)
                channel.send(result)
            } catch (e: Exception) {
                logger.warn(e) { "Extractor ${extractor.id} failed on $path" }
            }
        }
    }
}
