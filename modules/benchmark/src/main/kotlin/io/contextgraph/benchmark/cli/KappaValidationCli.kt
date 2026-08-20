package io.contextgraph.benchmark.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import io.contextgraph.benchmark.judge.JudgeClient
import io.contextgraph.benchmark.judge.JudgeHttpConfig
import io.contextgraph.benchmark.judge.JudgeScorer
import io.contextgraph.benchmark.judge.KappaSubsampleSelector
import io.contextgraph.benchmark.judge.KappaValidationResult
import io.contextgraph.benchmark.judge.KappaValidator
import io.contextgraph.benchmark.judge.LlmJudgeClient
import io.contextgraph.benchmark.judge.ScoredRun
import io.contextgraph.benchmark.model.BenchmarkRun
import io.contextgraph.benchmark.proxy.LiteLlmProxyConfig
import io.contextgraph.benchmark.proxy.LiteLlmProxyManager
import io.contextgraph.benchmark.report.BenchmarksReportGenerator
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText

/**
 * Standalone entry point for AC-14's kappa validation mode (task 14): [KappaValidator],
 * [CohenKappa][io.contextgraph.benchmark.judge.CohenKappa] and [KappaSubsampleSelector] were
 * written and unit-tested by slice 05 but had no production caller -- nothing ever wired them
 * into a real invocation, so `BENCHMARKS.md` could only ever say "kappa validation was not run"
 * and AC-14 could never be satisfied at runtime. This command is that caller: it re-scores a
 * reproducible ~20% subsample of an *already-produced* result's judged runs with a second,
 * independent judge model, and regenerates that result's `BENCHMARKS.md` with the agreement
 * rate and Cohen's kappa folded in.
 *
 * Wired as a separate `CliktCommand`/`main()` from [BenchmarkCli], not a flag on it, for the
 * same reason [PrepareCorpusCommand] is separate (see its own KDoc): this is a one-off
 * confidence check on the judge, not a step every benchmark run performs (task 14's explicit
 * "her koşuda çalışmasın, tek seferlik güven tesisi adımı" instruction) -- gated behind a
 * command name a caller has to invoke on purpose, never wired into `build`/`check` (AC-22) or
 * into [io.contextgraph.benchmark.orchestrator.BenchmarkOrchestrator]'s own run.
 *
 * [judgeClient] defaults to `null` rather than eagerly constructing a live [LlmJudgeClient] --
 * the same seam [BenchmarkCli] uses for its own judge client (task 17): a test-supplied value is
 * used exactly as before with no proxy involved, while a real invocation (`null`, every real use
 * of this command) wraps the re-scoring pass in [LiteLlmProxyManager.withProxy] so the secondary
 * judge always has a live LiteLLM proxy to call.
 */
class KappaValidationCommand(
    private val judgeClient: JudgeClient? = null,
    private val startDir: Path = Path.of(System.getProperty("user.dir")),
    private val explicitRepoRoot: Path? = System.getProperty(RepoRoot.REPO_ROOT_PROPERTY)?.let(Path::of)
) : CliktCommand(name = "validate-kappa") {
    override fun help(context: Context) =
        "Re-score a reproducible ~20% subsample of an existing result's already-judged runs " +
            "with a second, independent judge model, and fold the agreement rate / Cohen's " +
            "kappa into that result's BENCHMARKS.md (AC-14). A one-off confidence check on the " +
            "judge, not part of an ordinary benchmark run."

    private val resultPath by option(
        "--result",
        help = "Path to a result JSON previously written by BenchmarkCli (e.g. results/run-....json)."
    ).required()

    private val secondaryJudgeModel by option(
        "--secondary-judge-model",
        help = "The second, independent judge model to re-score the subsample with -- must " +
            "differ from the result's own primary judge model to mean anything."
    ).required()

    private val fraction by option(
        "--fraction",
        help = "Fraction of already-scored runs to re-score (default: " +
            "${KappaSubsampleSelector.DEFAULT_FRACTION})."
    ).double().default(KappaSubsampleSelector.DEFAULT_FRACTION)

    override fun run() {
        val runFile = Path.of(resultPath)
        val run = BenchmarkRun.readFrom(runFile)

        val scoredRuns = toScoredRuns(run)
        echo(
            "Validating kappa for ${scoredRuns.size} already-scored run(s) from " +
                "${runFile.absolutePathString()} against secondary judge $secondaryJudgeModel"
        )

        fun validateWith(judge: JudgeClient): KappaValidationResult {
            val validator = KappaValidator(JudgeScorer(judge))
            return runBlocking { validator.validate(scoredRuns, secondaryJudgeModel, fraction) }
        }

        val result = if (judgeClient != null) {
            validateWith(judgeClient)
        } else {
            val repoRoot = RepoRoot.find(startDir, explicitRepoRoot)
            val proxyConfig = LiteLlmProxyConfig(repoRoot = repoRoot)
            LiteLlmProxyManager().withProxy(listOf(secondaryJudgeModel), proxyConfig) { handle ->
                echo(
                    if (handle.alreadyRunning) "Reusing already-running LiteLLM proxy at ${handle.baseUrl}"
                    else "Started managed LiteLLM proxy at ${handle.baseUrl}"
                )
                validateWith(LlmJudgeClient(JudgeHttpConfig(baseUrl = handle.baseUrl)))
            }
        }

        echo(
            "Kappa validation complete: ${result.sampledRunIds.size}/${result.totalRuns} run(s) " +
                "re-scored, agreement=${result.agreementRate}, kappa=${result.kappa}"
        )

        val reportPath = (runFile.toAbsolutePath().normalize().parent ?: Path.of(".")).resolve("BENCHMARKS.md")
        reportPath.writeText(BenchmarksReportGenerator.generate(run, result))
        echo("Updated report: ${reportPath.absolutePathString()}")
    }
}

/**
 * Rebuilds [ScoredRun]s -- what [KappaValidator] needs to re-score a run -- from a persisted
 * [BenchmarkRun]'s own [BenchmarkRun.agentRuns]/[BenchmarkRun.judgeScores]/[BenchmarkRun.questions],
 * joining on the ids those three already carry (never recomputing or guessing any of them).
 * An [io.contextgraph.benchmark.model.AgentRunRecord] with no matching primary [io.contextgraph.benchmark.model.JudgeScore]
 * (e.g. a run that failed before judging) or no matching [io.contextgraph.benchmark.model.Question]
 * is skipped rather than crashing the whole validation pass over one incomplete entry -- the same
 * missing-data tolerance [BenchmarksReportGenerator] itself is built to have (see its own class doc).
 * Filters [BenchmarkRun.judgeScores] down to the run's own recorded primary judge model
 * ([BenchmarkRun.config]'s `models.judgeModel`) so a second `validate-kappa` invocation over the
 * same result file can never accidentally treat a prior validation's secondary-judge scores as
 * the primary verdict to compare against.
 */
internal fun toScoredRuns(run: BenchmarkRun): List<ScoredRun> {
    val questionById = run.questions.associateBy { it.id }
    val primaryJudgeModel = run.config.models.judgeModel
    val primaryScoreByRunId = run.judgeScores
        .filter { it.judgeModel == primaryJudgeModel }
        .associateBy { it.runId }

    return run.agentRuns.mapNotNull { record ->
        val question = questionById[record.questionId] ?: return@mapNotNull null
        val primaryScore = primaryScoreByRunId[record.id] ?: return@mapNotNull null
        ScoredRun(runId = record.id, question = question, answerText = record.finalAnswer, primaryScore = primaryScore)
    }
}

fun main(args: Array<String>) = KappaValidationCommand().main(args)
