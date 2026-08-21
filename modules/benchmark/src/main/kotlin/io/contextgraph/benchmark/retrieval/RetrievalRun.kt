package io.contextgraph.benchmark.retrieval

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The versioned, whole-run result document for the retrieval axis (AC-23..AC-26) -- a sibling
 * of [io.contextgraph.benchmark.model.BenchmarkRun], not a field on it. The two are kept fully
 * separate on purpose: this axis is LLM-free, deterministic, and runnable without
 * `ANTHROPIC_API_KEY`, so tying its schema to the agent-A/B run's would force every reader of
 * one to understand the other. [schemaVersion] follows the same "historical result JSON must
 * stay distinguishable if the shape changes" reasoning `BenchmarkRun.SCHEMA_VERSION`'s KDoc
 * gives.
 */
@Serializable
data class RetrievalRun(
    val schemaVersion: Int = SCHEMA_VERSION,
    val runId: String,
    val generatedAt: Instant,
    val kValues: List<Int>,
    val results: List<RetrievalRunResult> = emptyList(),
    val skippedRepos: List<SkippedRepo> = emptyList(),
    val summary: RetrievalSummary? = null
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    /** Writes this run as `<directory>/<runId>.json`, creating the directory if needed. */
    fun writeTo(directory: Path): Path {
        directory.createDirectories()
        val file = directory.resolve("$runId.json")
        file.writeText(toJson())
        return file
    }

    companion object {
        const val SCHEMA_VERSION = 1

        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        fun fromJson(text: String): RetrievalRun = json.decodeFromString(serializer(), text)

        fun readFrom(file: Path): RetrievalRun = fromJson(file.readText())
    }
}
