package io.contextgraph.benchmark.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import io.contextgraph.benchmark.model.BenchmarkRun
import io.contextgraph.benchmark.report.BenchmarksReportGenerator
import io.contextgraph.benchmark.stats.BenchmarkStats
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText

/**
 * Rewrites `BENCHMARKS.md` from a result JSON that already exists.
 *
 * The report generator improves after results are produced -- a column turns out to be missing, a
 * line turns out to state a constraint that was never applied -- and without this the only way to
 * pick up the improvement is to re-run the benchmark, paying again in money and hours for numbers
 * that are already on disk. The result JSON is the record; `BENCHMARKS.md` is a rendering of it,
 * and a rendering should be reproducible from its source.
 *
 * That reproducibility has to reach the summary too, which is why this recomputes it rather than
 * re-rendering the one the run stored. `summary` is derived from `agentRuns` and `judgeScores`, not
 * recorded alongside them -- so a stale one is not evidence, it is a cached answer from whatever
 * version of the aggregation happened to run that day. Leaving it alone meant a fix to
 * [io.contextgraph.benchmark.stats.BenchmarkStats] could never reach a result already on disk: the
 * run that found this had ten judge scores in its own JSON and a report that said "Accuracy: no
 * data", and regenerating it changed nothing. Nothing new is invented here -- the same function the
 * run itself called is called again over the same records.
 *
 * [measurement] and [backend] exist for one narrow job: backfilling results captured before those
 * fields were recorded, where the correct value is known from how the run was launched. They
 * overwrite whatever the JSON carries, so passing a value the run did not actually use would put a
 * false claim in the report -- use them only to fill a genuine blank.
 */
class RegenerateReportCommand : CliktCommand(name = "regenerate-report") {
    override fun help(context: Context) =
        "Rewrite BENCHMARKS.md next to an existing benchmark result JSON"

    private val resultPath by argument(
        name = "result",
        help = "Path to a benchmark result JSON (run-*.json)"
    )

    private val measurement by option(
        "--measurement",
        help = "Backfill the measurement (ADOPTION/EFFICACY) for a result recorded before the " +
            "field existed. Only use where the value is known from how the run was launched."
    )

    private val backend by option(
        "--backend",
        help = "Backfill the agent backend (claude-code/openai) for a result recorded before the " +
            "field existed."
    )

    override fun run() {
        val path = Path.of(resultPath)
        val original = BenchmarkRun.readFrom(path)
        val backfilled = original.copy(
            measurement = measurement ?: original.measurement,
            agentBackend = backend ?: original.agentBackend
        )
        // Only for a run that got as far as judging. A checkpoint (judgingComplete=false) has agent
        // runs but no scores, and summarizing it would replace an honestly absent summary with one
        // that reports every accuracy as missing -- the same blank, now wearing a computed look.
        val run = if (backfilled.judgingComplete) {
            backfilled.copy(summary = BenchmarkStats.summarize(backfilled))
        } else {
            backfilled
        }

        // Written back so the JSON and the report cannot disagree about what was measured: a
        // backfilled value that lived only in the rendering would be lost the next time anyone
        // regenerated it.
        if (run != original) run.writeTo(path.parent)

        val reportPath = path.parent.resolve("BENCHMARKS.md")
        reportPath.writeText(BenchmarksReportGenerator.generate(run))
        echo("Rewrote ${reportPath.absolutePathString()} from ${path.absolutePathString()}")
    }
}

fun main(args: Array<String>) = RegenerateReportCommand().main(args)
