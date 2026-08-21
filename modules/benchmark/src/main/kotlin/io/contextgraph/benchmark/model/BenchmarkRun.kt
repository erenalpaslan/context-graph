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
    val judgeScores: List<JudgeScore> = emptyList(),
    /**
     * Slice 06's aggregation/statistics output — null until
     * `io.contextgraph.benchmark.stats.BenchmarkStats.summarize` has been run
     * over this run's raw records. Additive field with a default so result
     * JSON written before this field existed still round-trips.
     */
    val summary: BenchmarkSummary? = null,
    /**
     * How many planned agent runs threw before producing an [AgentRunRecord]
     * (slice 12's resilience requirement: one failed run must not sink the
     * rest of the suite, and its count must still reach the report). `null`
     * means this result predates the field, not "zero failures" — the two
     * are kept distinguishable rather than defaulting to `0`, the same
     * reasoning [summary] being nullable already establishes for this class.
     * Additive with a default so historical result JSON still round-trips.
     */
    val failedRunCount: Int? = null,
    /**
     * AC-18a: what produced [agentRuns] -- see [AgentClientKind] for the two values and why
     * `null` means "not recorded" rather than defaulting to [AgentClientKind.REAL]. Additive
     * field with a default, same convention as [failedRunCount], so result JSON written before
     * this field existed still round-trips instead of failing to decode.
     */
    val agentClientKind: AgentClientKind? = null,
    /**
     * Task 18: `false` while [agentRuns] has been captured but the judging phase has not yet
     * finished (or aborted) -- the checkpoint [io.contextgraph.benchmark.orchestrator.BenchmarkOrchestrator]
     * writes to disk right after the agent-run loop, before a single judge call is made, so an
     * expensive batch of agent runs survives a judge-phase crash. `true` is the default rather
     * than nullable: every result JSON written before this field existed could only ever reach
     * disk *after* judging had fully completed (there was no earlier write path), so treating an
     * old file as complete is accurate, not an assumption. A caller must never read [judgeScores]
     * or [summary] as "the whole story" without checking this flag first -- see
     * `BenchmarksReportGenerator`'s top-of-document warning for the case where it is `false`.
     */
    val judgingComplete: Boolean = true,
    /**
     * Task 18: how many [agentRuns] the judge failed to score (each one caught and counted
     * individually so one bad judge call does not abort the whole run -- mirrors [failedRunCount]'s
     * "one failure must not sink the batch" reasoning, one phase later). `null` means "not
     * recorded" (predates this field, or judging never started), never "zero" -- same convention
     * [failedRunCount] already establishes on this class. A run whose every judge call failed
     * (this count equals [agentRuns].size, agentRuns non-empty) is never produced silently: the
     * orchestrator aborts loudly instead, per task 18's "doğruluk kolu tamamen boş bir koşu
     * üretmek yerine sesli biçimde dur."
     */
    val failedJudgeCount: Int? = null,
    /**
     * Which question this run answered -- `ADOPTION` or `EFFICACY` -- or null for runs written
     * before the distinction existed.
     *
     * Recorded because two results that look identical in every other field can mean opposite
     * things. A run where the agent was told nothing about ContextGraph and one where both arms
     * were told to look for it produce the same table shape; without this field a reader has no
     * way to tell which question a "+0.00 difference" is the answer to.
     */
    val measurement: String? = null,
    /**
     * Which agent backend produced the runs (`claude-code`, `openai`), or null for older results.
     *
     * Not cosmetic: the backends differ in agent, in available tools, and in the unit their budget
     * is denominated in, so numbers from the two are not comparable and a report that does not say
     * which one it came from invites exactly that comparison.
     */
    val agentBackend: String? = null
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
