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
    val summary: RetrievalSummary? = null,
    /**
     * Per-repo, per-tool gold-file coverage -- see [GoldFileCoverage] for why this is published
     * rather than the CodeGraph side being gated the way the ContextGraph one is. Empty for an
     * archived schema-v1 result, which predates the field.
     */
    val goldFileCoverage: List<GoldFileCoverage> = emptyList(),
    /**
     * Per-repo, per-tool indexing cost, read from the manifest corpus preparation wrote. Empty
     * when no manifest was found, which the report prints as "not recorded" rather than as zero.
     *
     * Read here rather than measured here on purpose: this run never indexes anything, and that
     * read-only property is what lets it observe a corpus another process is still indexing
     * without racing it. Measuring ingest cost inline would either break that or force a
     * re-index costing ~50 minutes on Keycloak for a number corpus prep already knew.
     */
    val ingestCosts: List<ToolIngestCost> = emptyList()
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
        /**
         * 3 since the axis grew a fourth comparator: the base-system bash baseline.
         *
         * **What a reader can conclude from seeing 3 rather than 2: a v3 document is the output
         * of a four-sided run, so a `bash` field that is null in it means that side was not
         * measured for that question, whereas in a v2 document the field is absent everywhere
         * because the side did not yet exist.** That is the whole reason the number moves. Every
         * field added in this change -- [RetrievalRunResult.bash] and [RetrievalAggregate.bash]
         * -- is nullable with a default, exactly as the third comparator's fields were, so every
         * archived v1 and v2 result still decodes unchanged; without the version bump a reader
         * would have to inspect all 33 questions to tell "no bash side in this run" from "the
         * bash side happened to be unmeasured on the ones I looked at".
         *
         * The same reasoning moved it to 2 for the third comparator, whose fields --
         * [RetrievalRunResult.codeGraph], [RetrievalAggregate.codeGraph], [goldFileCoverage],
         * [ingestCosts] -- are likewise still nullable or defaulted and still decode.
         */
        const val SCHEMA_VERSION = 3

        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        fun fromJson(text: String): RetrievalRun = json.decodeFromString(serializer(), text)

        fun readFrom(file: Path): RetrievalRun = fromJson(file.readText())
    }
}
