package io.contextgraph.benchmark.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.contextgraph.benchmark.corpus.CorpusCatalog
import io.contextgraph.benchmark.questions.QuestionSetLoader
import io.contextgraph.benchmark.retrieval.BashProcess
import io.contextgraph.benchmark.retrieval.RetrievalBenchmarkRunner
import io.contextgraph.benchmark.retrieval.RetrievalReportGenerator
import io.contextgraph.benchmark.retrieval.RetrievalRun
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
        "Measure four sides against each other over the same question set, LLM-free and " +
            "deterministic: ContextGraph (this project), CodeGraph (third-party), bash " +
            "(base-system shell only, no third-party tools) and ripgrep (third-party, retained " +
            "for comparison). Every side gets the same raw question text and is scored against " +
            "the same gold-derived expected files with the same metrics. Requires an " +
            "already-prepared corpus (see prepareCorpus), `rg` on PATH, and — for the CodeGraph " +
            "side — `codegraph` on PATH. The bash side needs nothing installed, which is the " +
            "point of it."

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

    private val codegraphPath by option(
        "--codegraph-path",
        help = "Path to the CodeGraph binary (default: 'codegraph', resolved via PATH). A repo " +
            "whose codegraph working copy is missing or unindexed is skipped on that side alone, " +
            "with the reason recorded — the other three sides are still measured."
    ).default("codegraph")

    private val grepPath by option(
        "--grep-path",
        help = "Path to the grep binary for the bash side (default: " +
            "${BashProcess.BASE_SYSTEM_GREP}, an absolute path, deliberately NOT resolved via " +
            "PATH — a PATH lookup can pick up a Homebrew or nix GNU grep, which would silently " +
            "turn this side back into a third-party measurement)."
    ).default(BashProcess.BASE_SYSTEM_GREP)

    private val kValuesArg by option(
        "--k-values",
        help = "Comma-separated k values for precision@k/recall@k. Default: 5,10 (see " +
            "RetrievalBenchmarkRunner.DEFAULT_K_VALUES for why)."
    )

    private val fromResult by option(
        "--from-result",
        help = "Rewrite BENCHMARKS.md from an existing retrieval result JSON and exit, without " +
            "re-running the measurement. No corpus, no `rg`, no `codegraph`, and no number " +
            "changes: the report is re-rendered from what that file already records. The report " +
            "is written next to the result document, NOT under --output-dir. A relative path " +
            "resolves against the repository root, the same rule --corpus-root follows."
    )

    override fun run() {
        fromResult?.let {
            regenerateFrom(it)
            return
        }
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
            codegraphPath = codegraphPath,
            grepPath = grepPath,
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

    /**
     * Re-renders a published report from the result document it was published from -- the supported
     * way to roll a generator change forward into `BENCHMARKS.md` (AC-11).
     *
     * Without it, the report claims in its own header to be generated rather than hand-edited, and
     * the only way to make good on that claim after changing the generator is throwaway code that
     * is not in the repository. `PublishedReportIsGeneratedTest` can already *detect* the drift; this
     * is what fixes it, using the same [RetrievalReportGenerator.generate]/[RetrievalReportGenerator.upsert]
     * pair [run] does, so a regeneration and a measurement can never produce different renderings.
     *
     * **The report is written beside the result document, never under `--output-dir`.** That option
     * resolves against the caller's working directory -- `modules/benchmark` for the Gradle task,
     * not the repository root -- and has already silently written a report into
     * `modules/benchmark/modules/benchmark/results/`. A result document and the report rendered from
     * it belong in the same directory anyway, so deriving one path from the other removes the
     * question rather than answering it. The result document itself is only ever read.
     */
    private fun regenerateFrom(resultArg: String) {
        val given = Path.of(resultArg)
        val resultPath = if (given.isAbsolute) {
            given.normalize()
        } else {
            RepoRoot.find(startDir, explicitRepoRoot).resolve(given).normalize()
        }
        val run = RetrievalRun.readFrom(resultPath)

        val reportPath = resultPath.resolveSibling("BENCHMARKS.md")
        val existing = if (Files.exists(reportPath)) reportPath.readText() else ""
        reportPath.writeText(RetrievalReportGenerator.upsert(existing, RetrievalReportGenerator.generate(run)))

        echo(
            "Regenerated ${reportPath.absolutePathString()} from " +
                "${resultPath.absolutePathString()} — no measurement was run and no number changed."
        )
    }
}

fun main(args: Array<String>) = RetrievalCommand(
    startDir = Path.of(System.getProperty("user.dir")),
    explicitRepoRoot = System.getProperty(RepoRoot.REPO_ROOT_PROPERTY)?.let(Path::of)
).main(args)
