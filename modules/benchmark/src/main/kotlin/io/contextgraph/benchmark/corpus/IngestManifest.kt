package io.contextgraph.benchmark.corpus

import io.contextgraph.benchmark.retrieval.ToolIngestCost
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

/**
 * What each tool's index for one repo cost to build, written by corpus preparation into
 * `<corpusRoot>/<repoId>/ingest.json` and read back, read-only, by the retrieval run.
 *
 * The file exists because of a constraint worth keeping rather than working around: the retrieval
 * runner never indexes, clones, or writes anything, and that read-only property is what lets it
 * observe a corpus another process is still indexing without racing or corrupting it. Measuring
 * ingest cost inside the runner would either break that property or force a re-index -- ~50
 * minutes on Keycloak -- for a number corpus preparation already had in hand. A small manifest is
 * the cheapest thing that keeps both.
 *
 * Both tools are recorded in the same file, deliberately, so a reader sees ContextGraph's and
 * CodeGraph's ingest side by side. That comparability is the point: a tool that indexes slowly
 * but answers well, or the reverse, should not be able to hide either half.
 */
@Serializable
data class IngestManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    val repoId: String,
    val costs: List<ToolIngestCost> = emptyList()
) {
    fun writeTo(repoDir: Path): Path {
        repoDir.createDirectories()
        val file = repoDir.resolve(FILE_NAME)
        file.writeText(json.encodeToString(serializer(), this))
        return file
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "ingest.json"

        private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

        /**
         * Reads `<corpusRoot>/<repoId>/ingest.json`, or returns `null` when there is none.
         *
         * A missing or unreadable manifest is never an error and never a zero. Corpus preparation
         * may simply predate this file, or a repo may have been prepared by an older build; the
         * report prints "not recorded" for that, which is the truth, where a zero would read as
         * "indexing this repo costs nothing".
         */
        fun readFrom(corpusRoot: Path, repoId: String): IngestManifest? {
            val file = corpusRoot.resolve(repoId).resolve(FILE_NAME)
            if (!Files.isRegularFile(file)) return null
            return try {
                json.decodeFromString(serializer(), file.readText())
            } catch (e: Exception) {
                logger.warn(e) { "ingest manifest at $file could not be read; reporting ingest cost as not recorded" }
                null
            }
        }

        /** Every repo's costs, flattened, skipping repos with no manifest. Order follows [repoIds]. */
        fun readAll(corpusRoot: Path, repoIds: List<String>): List<ToolIngestCost> =
            repoIds.mapNotNull { readFrom(corpusRoot, it) }.flatMap { it.costs }
    }
}
