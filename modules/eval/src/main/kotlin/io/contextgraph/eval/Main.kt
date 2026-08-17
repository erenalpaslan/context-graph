package io.contextgraph.eval

import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.ingest.RungDistribution
import io.contextgraph.ingest.describe.LiteLlmModuleEmbedder
import io.contextgraph.mcp.ExploreEngine
import io.contextgraph.storage.SqliteStorageAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant

/**
 * Entry point for the task eval harness (slice 19, AC-34). Runs every question in
 * [EvalTaskSet] against a live [ExploreEngine] pointed at a real, already-indexed graph, and
 * writes a re-generatable [ExploreEvalReport]. Deliberately does not index anything itself --
 * indexing is [io.contextgraph.ingest.IngestPipeline]'s job (this harness is read-only over
 * whatever `.contextgraph/{graph.db,graph.local.db}` it is pointed at) and this module does
 * not own `IngestPipeline.kt` or `Main.kt`'s CLI wiring.
 *
 * Args: `<dbPath> <projectRoot> [outputJsonPath] [blastRadiusConfidenceFloor]`. `dbPath` and
 * `projectRoot` have no defaults on purpose -- silently falling back to "." would make it too
 * easy to grade against the wrong graph. The floor defaults to [ExploreEngine]'s own default
 * ([ConfidenceDefaults.CALL_RESOLUTION_REPO_UNIQUE_NAME]) -- passing a different value
 * constructs a second, differently-configured [ExploreEngine] (a public constructor
 * parameter, not an edit to [ExploreEngine] itself) to check whether results are sensitive
 * to it, per the task file's explicit ask.
 */
fun main(args: Array<String>) {
    require(args.size >= 2) {
        "Usage: eval <dbPath> <projectRoot> [outputJsonPath] [blastRadiusConfidenceFloor]"
    }
    val dbPath = Path.of(args[0]).toAbsolutePath().normalize()
    val projectRoot = Path.of(args[1]).toAbsolutePath().normalize()
    val outputPath = Path.of(args.getOrElse(2) { "eval-results.json" })
    val floor = args.getOrNull(3)?.toDouble() ?: ConfidenceDefaults.CALL_RESOLUTION_REPO_UNIQUE_NAME

    val config = ContextGraphConfig()
    val storage = SqliteStorageAdapter(dbPath)
    val engine = ExploreEngine(storage, projectRoot, LiteLlmModuleEmbedder(), config.litellm, blastRadiusConfidenceFloor = floor)

    val results = runBlocking { ExploreEvalRunner(engine).run() }
    val rungDistribution = RungDistribution.compute(storage)

    val report = ExploreEvalReport(
        generatedAt = Instant.now().toString(),
        dbPath = dbPath.toString(),
        projectRoot = projectRoot.toString(),
        litellmEnabled = config.litellm.enabled,
        blastRadiusConfidenceFloor = floor,
        rungDistribution = rungDistribution,
        results = results
    )

    val json = Json { prettyPrint = true; encodeDefaults = true }
    outputPath.toFile().writeText(json.encodeToString(ExploreEvalReport.serializer(), report))

    println("Wrote ${results.size} results to ${outputPath.toAbsolutePath()}")
    println()
    println("%-4s %-10s %-4s %-7s %-8s %s".format("ID", "TYPE", "HIT", "TOKENS", "VIA", "MATCHED PATH"))
    results.forEach { r ->
        val question = EvalTaskSet.QUESTIONS.first { it.id == r.questionId }
        println(
            "%-4s %-10s %-4s %-7d %-8s %s".format(
                r.questionId,
                question.type.name,
                if (r.hit) "YES" else "NO",
                r.estimatedTokens,
                r.matchedVia ?: "-",
                r.matchedPath ?: "-"
            )
        )
    }
    println()
    println("Explore hits: ${results.count { it.hit }}/${results.size}")
    println("litellm.enabled=${config.litellm.enabled} (module matching falls back to keyword/tokenized scoring when false)")
    println("blastRadiusConfidenceFloor=${report.blastRadiusConfidenceFloor}")
    println("Rung distribution by language: $rungDistribution")
}
