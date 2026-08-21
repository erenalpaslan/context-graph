package io.contextgraph.benchmark.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.contextgraph.benchmark.corpus.CorpusCatalog
import io.contextgraph.benchmark.questions.QuestionSetLoader
import io.contextgraph.benchmark.retrieval.RetrievalBenchmarkRunner
import io.contextgraph.benchmark.retrieval.RetrievalReportGenerator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Standalone entry point for the retrieval axis (task 15, AC-23..AC-26). Wired as its own
 * `CliktCommand`/`main()`, the same reasoning [PrepareCorpusCommand] and
 * [KappaValidationCommand] give for being separate from [BenchmarkCli]: this measures something
 * fundamentally different (deterministic, LLM-free, no `ANTHROPIC_API_KEY` required) on a
 * different schedule, against an already-prepared corpus it never clones, indexes, or
 * re-indexes itself. Never wired into `build`/`check` (AC-22), same as every other benchmark
 * entry point.
 */
class RetrievalCommand(
    private val startDir: Path,
    private val explicitRepoRoot: Path?
) : CliktCommand(name = "retrieval") {
    override fun help(context: Context) =
        "Measure ContextGraph's QueryEngine.buildContext against a ripgrep baseline over the " +
            "same question set, LLM-free and deterministic (AC-23..AC-26). Requires an " +
            "already-prepared corpus (see prepareCorpus) and `rg` on PATH."

    private val corpusRootArg by option(
        "--corpus-root",
        help = "Directory the corpus was prepared under (see prepareCorpus). Default: .benchmark-corpus"
    ).default(".benchmark-corpus")

    private val questionsDirArg by option(
        "--questions-dir",
        help = "Directory of gold question-set YAML files. Default: modules/benchmark/questions " +
            "under the repository root."
    )

    private val outputDir by option(
        "--output-dir",
        help = "Directory the retrieval result JSON and BENCHMARKS.md section are written to"
    ).default("results")

    private val rgPath by option(
        "--rg-path",
        help = "Path to the ripgrep binary (default: 'rg', resolved via PATH)"
    ).default("rg")

    private val kValuesArg by option(
        "--k-values",
        help = "Comma-separated k values for precision@k/recall@k. Default: 5,10 (see " +
            "RetrievalBenchmarkRunner.DEFAULT_K_VALUES for why)."
    )

    override fun run() {
        val corpusRoot = RepoRoot.resolveCorpusRoot(corpusRootArg, startDir, explicitRepoRoot)
        val questionsDir = questionsDirArg?.let { Path.of(it) }
            ?: RepoRoot.find(startDir, explicitRepoRoot).resolve("modules/benchmark/questions")

        val questions = QuestionSetLoader.loadDirectory(questionsDir)
        val kValues = kValuesArg?.split(",")?.map { it.trim().toInt() }
            ?: RetrievalBenchmarkRunner.DEFAULT_K_VALUES

        echo("Scoring ${questions.size} question(s) from $questionsDir against corpus at $corpusRoot")

        val runner = RetrievalBenchmarkRunner(
            corpusRoot = corpusRoot,
            questions = questions,
            catalog = CorpusCatalog.DEFAULT,
            kValues = kValues,
            rgPath = rgPath,
            progress = { echo(it) }
        )
        val run = runner.run()

        val outputDirPath = Path.of(outputDir)
        outputDirPath.createDirectories()
        val written = run.writeTo(outputDirPath)

        val reportPath = outputDirPath.resolve("BENCHMARKS.md")
        val existing = if (Files.exists(reportPath)) reportPath.readText() else ""
        reportPath.writeText(RetrievalReportGenerator.upsert(existing, RetrievalReportGenerator.generate(run)))

        echo("Wrote retrieval result to ${written.absolutePathString()}")
        echo("Updated report: ${reportPath.absolutePathString()}")
        if (run.skippedRepos.isNotEmpty()) {
            echo("Skipped ${run.skippedRepos.size} repo(s): ${run.skippedRepos.joinToString(", ") { "${it.repoId} (${it.reason})" }}")
        }
        echo("Scored ${run.results.size} question(s) across ${run.results.map { it.repoId }.distinct().size} repo(s).")
    }
}

fun main(args: Array<String>) = RetrievalCommand(
    startDir = Path.of(System.getProperty("user.dir")),
    explicitRepoRoot = System.getProperty(RepoRoot.REPO_ROOT_PROPERTY)?.let(Path::of)
).main(args)
