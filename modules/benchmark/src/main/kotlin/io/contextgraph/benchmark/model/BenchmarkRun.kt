package io.contextgraph.benchmark.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The whole-run result document — the contract every other slice writes to
 * and reads from. See `modules/benchmark/README.md` for the package map this
 * type anchors.
 *
 * [schemaVersion] is an explicit field, not inferred: AC-18 requires that
 * historical result files (which accumulate in the repo across runs) stay
 * distinguishable if this shape changes later.
 */
@Serializable
data class BenchmarkRun(
    val schemaVersion: Int = SCHEMA_VERSION,
    val runId: String,
    val profile: Profile,
    val generatedAt: Instant,
    val config: BenchmarkConfig,
    val corpusRepos: List<CorpusRepo> = emptyList(),
    val questions: List<Question> = emptyList(),
    val ingestRecords: List<IngestRecord> = emptyList(),
    val agentRuns: List<AgentRunRecord> = emptyList(),
    val judgeScores: List<JudgeScore> = emptyList()
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

        fun fromJson(text: String): BenchmarkRun = json.decodeFromString(serializer(), text)

        fun readFrom(file: Path): BenchmarkRun = fromJson(file.readText())
    }
}
