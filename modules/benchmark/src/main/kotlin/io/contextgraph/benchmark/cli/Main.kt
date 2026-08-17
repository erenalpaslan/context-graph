package io.contextgraph.benchmark.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import io.contextgraph.benchmark.model.BenchmarkConfig
import io.contextgraph.benchmark.model.BenchmarkRun
import io.contextgraph.benchmark.model.Profile
import kotlinx.datetime.Clock
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * Command-line entry point for the benchmark suite.
 *
 * This is intentionally thin: it parses `--profile`, builds an empty
 * [BenchmarkRun] and writes it, which proves the model/serialization
 * contract round-trips end to end. It does NOT clone a corpus, run agents,
 * or call a judge — that orchestration is wired up by slice 12 (see
 * `modules/benchmark/README.md` for the full territory map). Extend this
 * file only for CLI-surface concerns (new flags, new subcommands); wiring
 * belongs in slice 12.
 */
class BenchmarkCli : CliktCommand(name = "benchmark") {
    override fun help(context: Context) =
        "ContextGraph benchmark suite - measures the agent-facing benefit of ContextGraph's MCP tools"

    private val profile by option(
        "--profile",
        help = "Benchmark scope: 'smoke' (fast, small) or 'full' (complete suite)"
    ).choice("smoke" to Profile.SMOKE, "full" to Profile.FULL).required()

    private val outputDir by option(
        "--output-dir",
        help = "Directory result JSON files are written to"
    ).default("results")

    override fun run() {
        val run = BenchmarkRun(
            runId = "run-${Clock.System.now().toEpochMilliseconds()}",
            profile = profile,
            generatedAt = Clock.System.now(),
            config = BenchmarkConfig()
        )
        val written = run.writeTo(Path.of(outputDir))
        echo(
            "Wrote benchmark result to ${written.absolutePathString()} " +
                "(schemaVersion=${run.schemaVersion}, profile=${run.profile}, " +
                "questions=${run.questions.size}, agentRuns=${run.agentRuns.size})"
        )
    }
}

fun main(args: Array<String>) = BenchmarkCli().main(args)
