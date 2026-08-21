package io.contextgraph.benchmark.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import io.contextgraph.benchmark.corpus.CorpusCatalog
import io.contextgraph.benchmark.judge.JudgeClient
import io.contextgraph.benchmark.judge.JudgeHttpConfig
import io.contextgraph.benchmark.judge.LlmJudgeClient
import io.contextgraph.benchmark.model.BenchmarkRun
import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.Profile
import io.contextgraph.benchmark.orchestrator.BenchmarkOrchestrator
import io.contextgraph.benchmark.orchestrator.OrchestrationResult
import io.contextgraph.benchmark.orchestrator.RunPlanner
import io.contextgraph.benchmark.proxy.LiteLlmProxyConfig
import io.contextgraph.benchmark.proxy.LiteLlmProxyManager
import io.contextgraph.benchmark.report.BenchmarksReportGenerator
import io.contextgraph.benchmark.judge.ClaudeCodeJudgeClient
import io.contextgraph.benchmark.runner.AgentClient
import io.contextgraph.benchmark.runner.ClaudeCodeAgentClient
import io.contextgraph.benchmark.runner.GraphTool
import io.contextgraph.benchmark.runner.Measurement
import io.contextgraph.benchmark.runner.OpenAiChatCompletionsClient
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Command-line entry point for the benchmark suite: `--profile smoke|full` runs the whole
 * sequence slice 12 wires up (corpus -> questions -> agent runs -> judging -> stats -> JSON ->
 * `BENCHMARKS.md`) via [BenchmarkOrchestrator], for the real thing every real invocation of this
 * command is meant to do.
 *
 * [agentClient] defaults to the live [OpenAiChatCompletionsClient] (task 20 -- the Anthropic key
 * on this account came back invalid, so the default moved off
 * [io.contextgraph.benchmark.runner.AnthropicMessagesClient], which remains available as an
 * explicit constructor argument) -- the only reason it is a constructor parameter at all is so a
 * test (or a deliberately reproducible smoke proof) can inject a deterministic stub at exactly
 * this boundary without this class or [BenchmarkOrchestrator] knowing the difference. [judgeClient] follows the same seam, but
 * defaults to `null` rather than eagerly constructing a live [LlmJudgeClient]: when a caller
 * supplies one (every test does), it is used exactly as before, with no proxy involved at all.
 * When it is left `null` -- every real invocation of this command -- [run] wraps the orchestrated
 * sequence in [LiteLlmProxyManager.withProxy] (task 17) so the judge always has a live LiteLLM
 * proxy to call, started and torn down around the run rather than assumed to already be there.
 * [catalog] and [startDir]/[explicitRepoRoot] are the same kind of seam, narrower still, for
 * pointing corpus resolution at a test fixture instead of the real four-repo catalog and the real
 * repository root -- see `BenchmarkCliTest`.
 */
class BenchmarkCli(
    private val agentClient: AgentClient? = null,
    private val judgeClient: JudgeClient? = null,
    private val catalog: List<CorpusRepo> = CorpusCatalog.DEFAULT,
    private val startDir: Path = Path.of(System.getProperty("user.dir")),
    private val explicitRepoRoot: Path? = System.getProperty(RepoRoot.REPO_ROOT_PROPERTY)?.let(Path::of)
) : CliktCommand(name = "benchmark") {
    override fun help(context: Context) =
        "ContextGraph benchmark suite - measures the agent-facing benefit of ContextGraph's MCP tools"

    private val profile by option(
        "--profile",
        help = "Benchmark scope: 'smoke' (fast, small) or 'full' (complete suite)"
    ).choice("smoke" to Profile.SMOKE, "full" to Profile.FULL).required()

    private val outputDir by option(
        "--output-dir",
        help = "Directory result JSON and BENCHMARKS.md are written to"
    ).default("results")

    private val corpusRootArg by option(
        "--corpus-root",
        help = "Directory the corpus is prepared under. A relative value (including the " +
            "default) resolves against the repository root, not the caller's working " +
            "directory -- see RepoRoot. Default: .benchmark-corpus"
    ).default(".benchmark-corpus")

    private val questionsDirArg by option(
        "--questions-dir",
        help = "Directory of gold question-set YAML files. Default: modules/benchmark/questions " +
            "under the repository root."
    )

    /**
     * The smoke profile deliberately runs one repo so it stays fast and deterministic, but
     * pinning *which* repo made it unusable for the thing smoke is most often needed for:
     * validating a model or client change before paying for a full run. The default repo is
     * `gin`, whose Go extraction currently yields zero symbols, so its WITH_TOOLS arm has nothing
     * to find and a smoke there cannot distinguish "the agent ignored the tools" from "the tools
     * had nothing to return" -- exactly the question a model change needs answered.
     *
     * Overriding the repo, not the determinism: whatever is passed here, the planner still takes
     * the same first [RunPlanner.SMOKE_QUESTIONS_PER_REPO] questions sorted by id.
     */
    private val smokeRepoArg by option(
        "--smoke-repo",
        help = "Repo id the smoke profile runs against. Ignored by --profile full. " +
            "Default: ${RunPlanner.DEFAULT_SMOKE_REPO_ID}"
    ).default(RunPlanner.DEFAULT_SMOKE_REPO_ID)

    /**
     * Which agent actually answers the questions.
     *
     * `claude-code` is the default because it is the only one that measures the product as
     * shipped: ContextGraph is an MCP server, so the arms become one real Claude Code session with
     * the server attached and one without, rather than a tool-use loop written by this repo. It
     * also gives the control arm Claude Code's own Read/Grep/Glob instead of a single hand-rolled
     * bash tool -- a far stronger baseline, and therefore a far more meaningful one to beat.
     *
     * `openai` keeps the previous in-process client reachable. Results from the two are *not*
     * interchangeable: different agent, different tool set, and a budget denominated in dollars
     * rather than tool calls.
     */
    private val backend by option(
        "--backend",
        help = "Agent backend: 'claude-code' (real Claude Code sessions, MCP-attached) or " +
            "'openai' (the in-process OpenAI tool-use loop)"
    ).choice("claude-code" to Backend.CLAUDE_CODE, "openai" to Backend.OPENAI).default(Backend.CLAUDE_CODE)

    private val maxBudgetUsd by option(
        "--max-budget-usd",
        help = "Per-run spend limit for the claude-code backend, applied identically to both " +
            "arms. Omit for no limit."
    )

    /**
     * Which of the two questions this run answers. They are not interchangeable, and a run that
     * does not say which one it measured cannot be read -- see [Measurement].
     */
    private val measurement by option(
        "--measurement",
        help = "'adoption' (say nothing -- does an agent reach for ContextGraph unprompted?), " +
            "'efficacy' (both arms told a graph server may be present -- does it help once " +
            "used?), or 'forced' (both arms told they must use it when present; a WITH_TOOLS run " +
            "that calls none fails rather than being scored)"
    ).choice(
        "adoption" to Measurement.ADOPTION,
        "efficacy" to Measurement.EFFICACY,
        "forced" to Measurement.FORCED
    ).default(Measurement.ADOPTION)

    /**
     * Overrides the profile's repeat count.
     *
     * The profiles pair scope with repetition -- `full` runs every question four times, for the
     * median-of-4 protocol. That coupling is wrong when the open question is *which questions
     * discriminate at all*: four repeats of a question that saturates costs four times as much and
     * says the same thing. Breadth first, then repeats where the breadth pass found signal.
     */
    private val repeatsArg by option(
        "--repeats",
        help = "Repeats per (question, arm), overriding the profile default."
    )

    /**
     * Which graph server the WITH_TOOLS arm gets. `codegraph` is the positive control -- see
     * [GraphTool] for why a null result needs one before it can be believed.
     */
    private val graphTool by option(
        "--graph-tool",
        help = "Graph MCP server for the WITH_TOOLS arm: 'contextgraph' (this project) or " +
            "'codegraph' (third-party, repo-locally installed -- a positive control on the harness)"
    ).choice("contextgraph" to GraphTool.CONTEXTGRAPH, "codegraph" to GraphTool.CODEGRAPH)
        .default(GraphTool.CONTEXTGRAPH)

    /**
     * Calibration mode: run the control arm alone.
     *
     * Two independently built graph tools both measured flat on this gold set, and the reason is
     * visible in the set itself -- every one of its 175 facts carries a `file:line` citation, so
     * each is reachable by opening one known file, and the baseline already scores ~0.89. An
     * instrument whose baseline sits that high has almost no room left to register a gain from
     * anything.
     *
     * Screening finds which questions still have room. It is half the cost of an A/B (one arm),
     * needs no graph at all, and every later comparison is worth more for having run it.
     */
    private val controlOnly by option(
        "--control-only",
        help = "Calibration pass: run only the WITHOUT_TOOLS arm, to measure each question's " +
            "difficulty for the baseline. Builds no graph and compares nothing."
    ).flag()

    private val agentModelArg by option(
        "--agent-model",
        help = "Model the agent runs on. Defaults per backend: ${Backend.CLAUDE_CODE.defaultAgentModel} " +
            "for claude-code, or ModelConfig's own default for openai."
    )

    /**
     * Kept separate from [agentModelArg], and defaulted to a *different* model, because both roles
     * now run through one CLI and nothing but this separation stops them collapsing onto the same
     * model -- which would reopen the objection AC-12 exists to close: an answer graded by the
     * model that produced it.
     */
    private val judgeModelArg by option(
        "--judge-model",
        help = "Model the judge runs on. Must differ from --agent-model. Defaults per backend: " +
            "${Backend.CLAUDE_CODE.defaultJudgeModel} for claude-code."
    )

    override fun run() {
        val corpusRoot = RepoRoot.resolveCorpusRoot(corpusRootArg, startDir, explicitRepoRoot)
        // Only walks for the repo root when it's actually needed (no explicit --questions-dir) --
        // an absolute --corpus-root plus an absolute --questions-dir (the shape a test fixture
        // uses) never needs a repo root at all, so this never fails a test pointed at a bare
        // temp directory that carries no settings.gradle.kts of its own.
        val questionsDir = questionsDirArg?.let { Path.of(it) }
            ?: RepoRoot.find(startDir, explicitRepoRoot).resolve("modules/benchmark/questions")

        val baseConfig = RunPlanner.configFor(profile)
        val agentModel = agentModelArg ?: backend.defaultAgentModel ?: baseConfig.models.agentModel
        val judgeModel = judgeModelArg ?: backend.defaultJudgeModel ?: baseConfig.models.judgeModel
        require(agentModel != judgeModel) {
            "The agent and the judge must not share a model ($agentModel): an answer graded by the " +
                "model that produced it is what AC-12's independent judge exists to prevent. Pass a " +
                "different --judge-model."
        }
        val config = baseConfig.copy(
            repeatsPerArm = repeatsArg?.toIntOrNull() ?: baseConfig.repeatsPerArm,
            maxBudgetUsdPerRun = maxBudgetUsd?.toDoubleOrNull(),
            models = baseConfig.models.copy(agentModel = agentModel, judgeModel = judgeModel)
        )

        val outputDirPath = Path.of(outputDir)

        // Resolved here rather than in the constructor default so that choosing the Claude Code
        // backend never constructs an OpenAI client -- which would demand an API key the
        // claude-code path has no use for.
        val resolvedAgentClient = agentClient ?: when (backend) {
            Backend.OPENAI -> OpenAiChatCompletionsClient()
            Backend.CLAUDE_CODE -> RepoRoot.find(startDir, explicitRepoRoot).let { root ->
                ClaudeCodeAgentClient(
                    contextGraphCliPath = root.resolve(CONTEXTGRAPH_CLI_RELATIVE_PATH),
                    graphTool = graphTool,
                    externalToolCliPath = root.resolve(EXTERNAL_GRAPH_TOOL_RELATIVE_PATH)
                )
            }
        }

        // Task 18: persisted the moment the (expensive) agent-run phase finishes, before a
        // single judge call happens -- so a judge-phase crash (a single stuck-open 429, or
        // AllJudgeCallsFailedException if every judge call failed) still leaves this batch of
        // real agent runs on disk, readable, and explicitly marked judgingComplete=false rather
        // than silently absent. Written to the same `<runId>.json` path the final result uses, so
        // a run that completes judging overwrites its own checkpoint with the finished result
        // rather than leaving two files behind.
        fun writeCheckpoint(checkpoint: BenchmarkRun) {
            outputDirPath.createDirectories()
            val written = checkpoint.writeTo(outputDirPath)
            echo(
                "Checkpoint: wrote ${checkpoint.agentRuns.size} agent run(s) to " +
                    "${written.absolutePathString()} before judging (judgingComplete=false)."
            )
        }

        fun runOrchestration(judge: JudgeClient): OrchestrationResult =
            BenchmarkOrchestrator(
                agentClient = resolvedAgentClient,
                judgeClient = judge,
                corpusRoot = corpusRoot,
                questionsDir = questionsDir,
                catalog = catalog,
                smokeRepoId = smokeRepoArg,
                measurement = measurement,
                graphTool = graphTool,
                arms = if (controlOnly) listOf(Arm.WITHOUT_TOOLS) else Arm.entries,
                progress = { echo(it) },
                onAgentRunsComplete = ::writeCheckpoint
            ).run(profile, config)

        val result = if (judgeClient != null) {
            runOrchestration(judgeClient)
        } else if (backend == Backend.CLAUDE_CODE) {
            // No proxy, no virtualenv, no pinned LiteLLM release: `--json-schema` makes the
            // verdict shape a generation constraint rather than a request in a prompt, so the
            // whole transport those pieces existed to provide has nothing left to do.
            echo("Judging through the Claude Code CLI (no LiteLLM proxy needed)")
            runOrchestration(ClaudeCodeJudgeClient())
        } else {
            val repoRoot = RepoRoot.find(startDir, explicitRepoRoot)
            val proxyConfig = LiteLlmProxyConfig(repoRoot = repoRoot)
            LiteLlmProxyManager().withProxy(listOf(config.models.judgeModel), proxyConfig) { handle ->
                echo(
                    if (handle.alreadyRunning) "Reusing already-running LiteLLM proxy at ${handle.baseUrl}"
                    else "Started managed LiteLLM proxy at ${handle.baseUrl}"
                )
                runOrchestration(LlmJudgeClient(JudgeHttpConfig(baseUrl = handle.baseUrl)))
            }
        }

        outputDirPath.createDirectories()
        val written = result.run.writeTo(outputDirPath)
        val reportPath = outputDirPath.resolve("BENCHMARKS.md")
        reportPath.writeText(BenchmarksReportGenerator.generate(result.run))

        echo(
            "Wrote benchmark result to ${written.absolutePathString()} " +
                "(schemaVersion=${result.run.schemaVersion}, profile=${result.run.profile}, " +
                "questions=${result.run.questions.size}, agentRuns=${result.run.agentRuns.size})"
        )
        echo("Wrote report to ${reportPath.absolutePathString()}")
        echo(
            "Planned ${result.plannedRunCount} run(s): ${result.run.agentRuns.size} succeeded, " +
                "${result.failedRunCount} failed. Judging: ${result.run.judgeScores.size} scored, " +
                "${result.failedJudgeCount} failed."
        )
    }
}

/**
 * Which agent runs the arms. See `BenchmarkCli.backend` for why `claude-code` is the default.
 *
 * Each backend carries its own model defaults because the two speak different model namespaces --
 * `gpt-4.1-mini` is meaningless to the Claude Code CLI, and a stale cross-backend default would
 * fail every run with a model-not-found rather than anything diagnostic. `null` means "leave
 * [io.contextgraph.benchmark.model.ModelConfig]'s own default alone".
 *
 * The two Claude Code defaults are deliberately different models: agent and judge sharing one is
 * the failure AC-12 guards against, and defaults are where that would quietly creep back in.
 */
enum class Backend(val defaultAgentModel: String?, val defaultJudgeModel: String?) {
    CLAUDE_CODE(defaultAgentModel = "claude-sonnet-5", defaultJudgeModel = "claude-opus-5"),
    OPENAI(defaultAgentModel = null, defaultJudgeModel = null)
}

/**
 * Where `:modules:cli:installDist` puts the executable the WITH_TOOLS arm launches its MCP server
 * from. A jar started through Gradle would pay a daemon startup on every single run.
 */
const val CONTEXTGRAPH_CLI_RELATIVE_PATH: String = "modules/cli/build/install/cli/bin/cli"

/**
 * Repo-local install path for a third-party graph tool's executable. Deliberately inside the
 * repository (and gitignored) rather than on the machine's global PATH: a benchmark should not
 * leave software installed on the machine that ran it.
 */
const val EXTERNAL_GRAPH_TOOL_RELATIVE_PATH: String = ".benchmark-tools/node_modules/.bin/codegraph"

fun main(args: Array<String>) = BenchmarkCli().main(args)
