package io.contextgraph.benchmark.orchestrator

import io.contextgraph.benchmark.corpus.CorpusCatalog
import io.contextgraph.benchmark.corpus.CorpusPreparationStep
import io.contextgraph.benchmark.judge.JudgeClient
import io.contextgraph.benchmark.judge.JudgeScorer
import io.contextgraph.benchmark.judge.SetScorer
import io.contextgraph.benchmark.model.AgentClientKind
import io.contextgraph.benchmark.model.AgentRunRecord
import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.BenchmarkConfig
import io.contextgraph.benchmark.model.BenchmarkRun
import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.Profile
import io.contextgraph.benchmark.questions.CategoryDistributionAuditor
import io.contextgraph.benchmark.questions.QuestionSetLoader
import io.contextgraph.benchmark.runner.AgentClient
import io.contextgraph.benchmark.runner.AgentRunner
import io.contextgraph.benchmark.runner.Measurement
import io.contextgraph.benchmark.runner.AnthropicMessagesClient
import io.contextgraph.benchmark.runner.ClaudeCodeAgentClient
import io.contextgraph.benchmark.runner.ContaminatedWorkingCopyException
import io.contextgraph.benchmark.runner.GraphTool
import io.contextgraph.benchmark.runner.OpenAiChatCompletionsClient
import io.contextgraph.benchmark.stats.BenchmarkStats
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/** What one call to [BenchmarkOrchestrator.run] produced, beyond the [BenchmarkRun] itself. */
data class OrchestrationResult(
    val run: BenchmarkRun,
    val plannedRunCount: Int,
    val failedRunCount: Int,
    val failedJudgeCount: Int
)

/**
 * Task 18: thrown when every judge call in a run failed -- [failedJudgeCount] equals the number
 * of [io.contextgraph.benchmark.model.AgentRunRecord]s that were up for judging, none of which
 * ended up with a score. A run in this state has an accuracy column that is entirely absent, not
 * merely thin -- continuing to a green "0 answers scored" report would look like a measurement
 * that happened to find nothing, rather than what actually happened: the judge never worked even
 * once. This is deliberately a loud abort, the same posture task 14's
 * [io.contextgraph.benchmark.runner.ContaminatedWorkingCopyException] takes for a different
 * reason -- both are "this batch is not safe to report on", not an ordinary transient failure.
 * The agent runs themselves are not lost: [BenchmarkOrchestrator.run] hands its
 * `onAgentRunsComplete` checkpoint to the caller before judging ever starts, so this exception is
 * always thrown after that checkpoint already reached disk.
 */
class AllJudgeCallsFailedException(val failedJudgeCount: Int, val totalAgentRuns: Int) :
    IllegalStateException(
        "Every judge call failed ($failedJudgeCount/$totalAgentRuns) -- refusing to produce a " +
            "result with a completely empty accuracy column. The agent runs that were already " +
            "captured are not lost (see the checkpoint written before judging started); only " +
            "scoring did not happen."
    )

/**
 * A short, stable name for the backend that produced a run, so a result says which agent it came
 * from. Backends differ in tool set and budget unit, so results from two of them must never be
 * silently pooled -- and a reader can only avoid that if the result says which one it is.
 */
fun agentBackendOf(agentClient: AgentClient): String = when (agentClient) {
    is ClaudeCodeAgentClient -> "claude-code"
    is OpenAiChatCompletionsClient -> "openai"
    is AnthropicMessagesClient -> "anthropic"
    else -> "stub"
}

/**
 * AC-18a: derives the [AgentClientKind] this run's [BenchmarkRun.agentClientKind] gets stamped
 * with, from the [AgentClient] instance actually wired into [BenchmarkOrchestrator] -- not from a
 * second, independently-settable flag a caller could pass out of sync with the client it also
 * passed in. [AnthropicMessagesClient] and [OpenAiChatCompletionsClient] (task 20) are the two
 * production implementations of [AgentClient] -- real tool-use loops against a real provider,
 * differing only in which one; every other implementation reaching this class -- test lambdas,
 * `LiveSmokeOrchestrationTest`'s deterministic stub -- is by construction a stand-in, not a real
 * agent turn. This makes mislabeling structurally impossible rather than a discipline a caller has
 * to remember: exactly the gap this slice exists to close (task 13 / spec AC-18a's `smoke`
 * profile ran a fake client and reported it indistinguishably from a real measurement).
 */
fun agentClientKindOf(agentClient: AgentClient): AgentClientKind =
    if (agentClient is AnthropicMessagesClient ||
        agentClient is OpenAiChatCompletionsClient ||
        agentClient is ClaudeCodeAgentClient
    ) {
        AgentClientKind.REAL
    } else {
        AgentClientKind.SYNTHETIC
    }

/**
 * Ties slices 02 through 07 together behind `--profile smoke|full` (task 12): corpus prep ->
 * question loading -> per (repo, question, arm, repeat) agent runs -> blind judging -> stats
 * (contaminated runs excluded by [BenchmarkStats] itself) -> a [BenchmarkRun] ready to be
 * written and reported. This class does not measure, score, aggregate, or format anything
 * itself -- every one of those steps delegates to the slice that owns it; this class only
 * decides the order and hands each step's output to the next.
 *
 * [agentClient] and [judgeClient] are the only injection seam this class defines on purpose:
 * they are the two calls that reach a real model, so swapping them (e.g. for a deterministic
 * stub in a smoke proof) changes nothing about corpus prep, contamination guarding, scoring
 * reconciliation, or statistics -- every one of those stays the real implementation regardless
 * of what runs behind these two interfaces. Production callers ([io.contextgraph.benchmark.cli.BenchmarkCli])
 * default them to the live [io.contextgraph.benchmark.runner.AnthropicMessagesClient] /
 * [io.contextgraph.benchmark.judge.LlmJudgeClient].
 *
 * [catalog] and [smokeRepoId] are a second, narrower seam for tests only: the real catalog is
 * the four pinned repos ([CorpusCatalog.DEFAULT]) and the real smoke repo is gin
 * ([RunPlanner.DEFAULT_SMOKE_REPO_ID]) -- production code never overrides either. A test that
 * needs to avoid the real multi-gigabyte corpus (and the network clone it would otherwise
 * require) substitutes a small local git fixture catalog here; see `BenchmarkOrchestratorTest`.
 *
 * Kotlin's `smoke` and `full` never diverge in the body of [run] -- every branch that varies by
 * profile lives in [RunPlanner], which builds the same shape of [RunPlan] either way; this class
 * consumes that plan without knowing which profile produced it.
 *
 * [onAgentRunsComplete] is task 18's survival seam: called exactly once, right after the
 * agent-run loop finishes and before the first judge call, with a [BenchmarkRun] snapshot that
 * carries every [io.contextgraph.benchmark.model.AgentRunRecord] captured so far and
 * `judgingComplete = false`. This class does not write files itself (same "no I/O beyond
 * [agentClient]/[judgeClient]" boundary [progress] already keeps) -- production
 * ([io.contextgraph.benchmark.cli.BenchmarkCli]) supplies a callback that persists this
 * checkpoint to disk, so an expensive batch of agent runs is never only reachable through a
 * judging phase that might still throw.
 */
class BenchmarkOrchestrator(
    private val agentClient: AgentClient,
    private val judgeClient: JudgeClient,
    private val corpusRoot: Path,
    private val questionsDir: Path,
    private val catalog: List<CorpusRepo> = CorpusCatalog.DEFAULT,
    private val smokeRepoId: String = RunPlanner.DEFAULT_SMOKE_REPO_ID,
    private val measurement: Measurement = Measurement.ADOPTION,
    private val graphTool: GraphTool = GraphTool.CONTEXTGRAPH,
    /**
     * Which arms run. Both for any A/B; [Arm.WITHOUT_TOOLS] alone for a calibration pass that
     * measures how hard each question is for the baseline before any tool is compared on it.
     */
    private val arms: List<Arm> = Arm.entries,
    private val progress: (String) -> Unit = { logger.info { it } },
    private val onAgentRunsComplete: (BenchmarkRun) -> Unit = {}
) {

    fun run(profile: Profile, config: BenchmarkConfig = RunPlanner.configFor(profile)): OrchestrationResult {
        val runId = "run-${Clock.System.now().toEpochMilliseconds()}"
        progress("Loading question sets from $questionsDir")
        val allQuestions = QuestionSetLoader.loadDirectory(questionsDir)
        // Never fatal (CategoryDistributionAuditor's own contract): a deviation is logged, not
        // thrown. Auditing here is a straight call into slice 03's validator, not new logic.
        CategoryDistributionAuditor.auditAll(allQuestions)

        // AC-17: the plan -- and its run count -- is computed and announced before anything
        // that costs time, tokens, or touches the corpus runs.
        val plan = RunPlanner.plan(profile, catalog, allQuestions, config, smokeRepoId, arms)
        progress(
            "Profile ${profile.name.lowercase()} plans ${plan.totalRuns} agent run(s): " +
                "${plan.repos.size} repo(s) [${plan.repos.joinToString(", ") { it.id }}], " +
                "${plan.questions.size} question(s), ${arms.size} arm(s) [${arms.joinToString(", ")}], " +
                "${config.repeatsPerArm} repeat(s) per (question, arm)."
        )

        // An empty plan is always a configuration mistake, never a legitimate result: a repo id
        // that matches nothing in the catalog, or a --questions-dir holding no questions for the
        // repos this profile selected. Left unchecked it runs to completion, reports "0 succeeded,
        // 0 failed", and overwrites BENCHMARKS.md with an empty report -- a silent no-op wearing
        // a success exit code, which is the same failure shape as counting a failed API call as a
        // successful run. Fail here, before the corpus prep that would otherwise burn minutes
        // producing nothing.
        require(plan.totalRuns > 0) {
            "Profile ${profile.name.lowercase()} planned 0 agent runs: " +
                "${plan.repos.size} repo(s) [${plan.repos.joinToString(", ") { it.id }}] and " +
                "${plan.questions.size} question(s) after filtering. Check that --questions-dir " +
                "($questionsDir) contains question sets whose repoId matches a selected repo" +
                if (profile == Profile.SMOKE) ", and that --smoke-repo names a repo in the catalog." else "."
        }

        progress("Preparing corpus for: ${plan.repos.joinToString(", ") { it.id }}")
        // questions = plan.questions wires AC-2a's index integrity gate: CorpusPreparationStep
        // verifies every gold-fact-cited file for a repo is present in *that repo's* freshly
        // indexed graph before returning it, and throws (aborting this whole run, before any
        // agent executes) if one is missing.
        val corpusResults = CorpusPreparationStep.run(
            corpusRoot,
            repos = plan.repos,
            questions = plan.questions,
            indexWithCopy = Arm.WITH_TOOLS in arms
        )
        val preparedRepos = corpusResults.map { it.repo }
        val ingestRecords = corpusResults.map { it.ingestRecord }.filterNotNull()
        val preparedById = preparedRepos.associateBy { it.id }

        val agentRunner = AgentRunner(agentClient)
        val agentRuns = mutableListOf<AgentRunRecord>()
        var failedRunCount = 0

        plan.plannedRuns.forEachIndexed { index, planned ->
            progress(
                "[${index + 1}/${plan.totalRuns}] ${planned.question.repoId}/${planned.question.id} " +
                    "arm=${planned.arm} repeat=${planned.repeatIndex}"
            )
            val repo = preparedById.getValue(planned.question.repoId)
            val workingDirPath = if (planned.arm == Arm.WITH_TOOLS) graphTool.withToolsDir(repo) else repo.workingCopyWithoutPath
            val workingDir = Path.of(
                requireNotNull(workingDirPath) {
                    "repo '${repo.id}' has no working copy for arm ${planned.arm} -- corpus prep did not run for it"
                }
            )
            // Corpus prep builds `with` and `without`; a third-party tool's copy is created and
            // indexed outside it, so its absence is a setup mistake rather than a run-time
            // failure -- and one worth naming precisely, since the alternative is an agent
            // launched into a directory that does not exist.
            require(planned.arm != Arm.WITH_TOOLS || graphTool == GraphTool.CONTEXTGRAPH || workingDir.toFile().isDirectory) {
                "graph tool '${graphTool.id}' expects an indexed working copy at $workingDir for " +
                    "repo '${repo.id}', and there is none. Create it from the clean control copy " +
                    "and index it with that tool before running."
            }
            // Resilience: one failed run must not sink a ~256-run job. The failure is logged and
            // counted; no AgentRunRecord is produced for it, and the loop moves on to the next
            // planned run rather than propagating the exception.
            //
            // AC-7a/task 14: a ContaminatedWorkingCopyException is a different *kind* of
            // failure and is deliberately NOT caught by the blanket catch below. It means
            // AgentRunner's own pre-run gate (AC-7a) found a ContextGraph artefact in a working
            // copy that is required to stay untouched -- i.e. the contamination block mechanism
            // has already failed for real, for this repo, in this run. Folding that into
            // failedRunCount would make it indistinguishable from an ordinary transient error
            // (a network blip, a timeout) in the report, which is exactly the signal this run
            // exists to protect: the whole batch is not safe to keep measuring once that has
            // happened, so this aborts run() outright rather than being counted and continued
            // past. A transient agent error (anything else) is unaffected and still hits the
            // catch below, preserving the resilience this loop otherwise depends on.
            try {
                agentRuns += agentRunner.run(
                    planned.question, planned.arm, workingDir, planned.repeatIndex, config,
                    systemPrompt = measurement.systemPromptFor(graphTool),
                    requireExtraToolUse = measurement == Measurement.FORCED,
                    graphTool = graphTool
                )
            } catch (e: ContaminatedWorkingCopyException) {
                progress(
                    "  ABORTING (${planned.question.id}/${planned.arm}/repeat ${planned.repeatIndex}): " +
                        "working copy integrity violation -- ${e.message}"
                )
                logger.error(e) {
                    "aborting benchmark run: working copy integrity violation for " +
                        "${planned.question.id}/${planned.arm}/repeat ${planned.repeatIndex}"
                }
                throw e
            } catch (e: Exception) {
                failedRunCount++
                progress(
                    "  FAILED (${planned.question.id}/${planned.arm}/repeat ${planned.repeatIndex}): " +
                        "${e::class.simpleName}: ${e.message} -- marked failed, continuing"
                )
                logger.warn(e) { "agent run failed for ${planned.question.id}/${planned.arm}/repeat ${planned.repeatIndex}" }
            }
        }
        progress("Agent runs complete: ${agentRuns.size} succeeded, $failedRunCount failed of ${plan.totalRuns} planned.")

        // Task 18 checkpoint: everything captured so far, persisted (via onAgentRunsComplete)
        // before a single judge call happens. judgeScores/summary are empty/null and
        // judgingComplete is explicitly false -- this run is never mistaken for a scored one if
        // the caller reads this checkpoint back later (e.g. because judging crashed and this was
        // the last thing written).
        val checkpointRun = BenchmarkRun(
            runId = runId,
            profile = profile,
            generatedAt = Clock.System.now(),
            config = config,
            corpusRepos = preparedRepos,
            questions = plan.questions,
            ingestRecords = ingestRecords,
            agentRuns = agentRuns,
            failedRunCount = failedRunCount,
            agentClientKind = agentClientKindOf(agentClient),
            judgingComplete = false,
            measurement = measurement.name,
            agentBackend = agentBackendOf(agentClient)
        )
        onAgentRunsComplete(checkpointRun)

        val judgeScorer = JudgeScorer(judgeClient)
        val judgeModel = config.models.judgeModel
        val questionById = plan.questions.associateBy { it.id }
        progress("Judging ${agentRuns.size} answer(s) with $judgeModel")
        var failedJudgeCount = 0
        // Resilience, one phase later than the agent-run loop above: one answer the judge
        // couldn't score must not sink the whole run's accuracy column. The failure is logged
        // and counted (failedJudgeCount / AC "Başarısız puanlama sayısı BENCHMARKS.md'de
        // görünüyor"); no JudgeScore is produced for it, and BenchmarkStats.aggregate already
        // excludes any AgentRunRecord with no matching JudgeScore from the accuracy metric
        // (mapNotNull over accuracyByRunId) rather than defaulting it to zero.
        val judgeScores = runBlocking {
            agentRuns.mapNotNull { record ->
                val question = questionById.getValue(record.questionId)
                try {
                    // A question with a mechanically derived answer set is scored by comparison,
                    // not by a model: the answer is exactly right or exactly wrong on each
                    // element, and putting a grader in front of that would only add variance to a
                    // measurement that no longer needs any.
                    if (question.expectedSet != null) {
                        SetScorer.score(question, record)
                    } else {
                        judgeScorer.score(runId = record.id, judgeModel = judgeModel, question = question, answerText = record.finalAnswer)
                    }
                } catch (e: Exception) {
                    failedJudgeCount++
                    progress("  JUDGE FAILED (${record.id}): ${e::class.simpleName}: ${e.message} -- unscored, continuing")
                    logger.warn(e) { "judge call failed for agent run ${record.id}" }
                    null
                }
            }
        }
        progress("Judging complete: ${judgeScores.size} scored, $failedJudgeCount failed of ${agentRuns.size}.")

        // The bound this run() otherwise has no ceiling for: an accuracy column that is
        // completely empty is not a measurement, it's a judge outage wearing a report's clothes.
        // The checkpoint above already reached the caller, so nothing measured is lost by
        // stopping here instead of returning a "0 scored" result indistinguishable from a
        // negative finding.
        if (agentRuns.isNotEmpty() && failedJudgeCount == agentRuns.size) {
            throw AllJudgeCallsFailedException(failedJudgeCount, agentRuns.size)
        }

        val rawRun = checkpointRun.copy(
            generatedAt = Clock.System.now(),
            judgeScores = judgeScores,
            failedJudgeCount = failedJudgeCount,
            judgingComplete = true
        )
        val summary = BenchmarkStats.summarize(rawRun)
        progress("Statistics computed: ${summary.perQuestionArm.size} (question, arm) group(s) summarized.")

        return OrchestrationResult(
            run = rawRun.copy(summary = summary),
            plannedRunCount = plan.totalRuns,
            failedRunCount = failedRunCount,
            failedJudgeCount = failedJudgeCount
        )
    }
}
