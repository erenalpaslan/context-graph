package io.contextgraph.benchmark.report

import io.contextgraph.benchmark.judge.KappaValidationResult
import io.contextgraph.benchmark.model.AgentClientKind
import io.contextgraph.benchmark.model.AggregatedMetrics
import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.BenchmarkRun
import io.contextgraph.benchmark.model.BreakEvenResult
import io.contextgraph.benchmark.model.MetricSummary
import io.contextgraph.benchmark.model.QuestionCategory
import io.contextgraph.benchmark.runner.ModelPricing
import java.util.Locale

/**
 * Renders `BENCHMARKS.md` from a [BenchmarkRun]. Pure formatting: every
 * number printed here already exists on [BenchmarkRun] or on
 * [BenchmarkRun.summary] (built by
 * `io.contextgraph.benchmark.stats.BenchmarkStats.summarize`, slice 06).
 * This generator does not aggregate repeats, compute a median, or decide
 * break-even -- it decides how to print numbers that are already decided.
 * The two exceptions are noted at their call sites below, and both are
 * plain counts/sums over raw fields already on [BenchmarkRun], never a
 * statistic (no median, no variance, no break-even math).
 *
 * Deterministic by construction: every list this generator walks is either
 * already sorted by [BenchmarkRun]'s producers (slice 06 sorts
 * `perQuestionArm`/`perRepoArm`/`perCategoryArm`) or explicitly sorted here
 * before rendering, and no wall-clock or random value is read. The same
 * [BenchmarkRun] always renders to the same string -- see
 * `BenchmarksReportGeneratorTest`'s determinism test, which round-trips a
 * run through JSON twice and diffs the two renders.
 *
 * Missing data never crashes this generator: an unrun repo, a run with no
 * [BenchmarkRun.summary] yet, or a kappa validation that was never executed
 * all render as an explicit "not run" / "no data" statement instead of a
 * `NullPointerException` or a silently blank section (task 07's notes).
 */
object BenchmarksReportGenerator {

    /**
     * [kappaValidation] is a separate optional parameter, not a
     * [BenchmarkRun] field, because [BenchmarkRun] does not carry one --
     * `io.contextgraph.benchmark.judge.KappaValidationResult` exists but
     * nothing wires it into the persisted run JSON yet (see that type's
     * kdoc: "report to the Lead if this needs folding into `BenchmarkRun`
     * later" -- this is that report). A caller that ran kappa validation
     * (slice 12's orchestration) can pass the result straight through; a
     * caller working from an archived result JSON alone gets the explicit
     * "not run" statement AC-19 requires, which is also what happens when
     * this parameter is simply omitted.
     */
    fun generate(run: BenchmarkRun, kappaValidation: KappaValidationResult? = null): String = buildString {
        renderHeader(run)
        renderMethodology(run)
        renderHeadline(run)
        renderPerRepo(run)
        renderNegativeControl(run)
        renderContamination(run)
        renderCeiling(run)
        renderJudgeValidation(run, kappaValidation)
        renderIngestAndBreakEven(run)
        renderReproduction(run)
    }

    // ---------------------------------------------------------------- header

    private fun StringBuilder.renderHeader(run: BenchmarkRun) {
        appendLine("# BENCHMARKS")
        appendLine()
        // AC-18a: this warning is the whole point of agentClientKind existing. It runs before
        // anything else in the document -- immediately after the title, not folded into
        // Methodology or Reproduction -- and appears for anything that is not an explicit REAL
        // (including null/"not recorded", so an older result JSON that predates this field is
        // never silently read as a real measurement). It is absent only when agentClientKind is
        // exactly AgentClientKind.REAL -- see BenchmarksReportGeneratorTest for both directions.
        if (run.agentClientKind != AgentClientKind.REAL) {
            appendLine(
                "> **⚠ SYNTHETIC RESULT -- not a measurement.** " +
                    "The agent runs in this result were produced by a scripted stand-in client " +
                    "(it calls the real ContextGraph MCP tools, but no real coding agent ever " +
                    "ran a turn), not by a real agent. Every token count, cost, and accuracy " +
                    "score below is proof the harness pipeline runs end to end -- it is not " +
                    "evidence that ContextGraph helps or hurts on any question. Do not cite " +
                    "these numbers as a benchmark result; re-run with a real agent client to " +
                    "obtain one."
            )
            appendLine(">")
            appendLine(
                "> (`agentClientKind` = " +
                    (run.agentClientKind?.name ?: "not recorded -- predates this field") + ")"
            )
            appendLine()
        }
        // Task 18: the checkpoint BenchmarkOrchestrator writes before judging starts has
        // judgingComplete = false. A partial result is never rendered as if it were a finished
        // one -- this warning runs before anything else, same placement as the SYNTHETIC banner
        // above, and for the same reason: an incomplete thing must never look complete.
        if (!run.judgingComplete) {
            appendLine(
                "> **⚠ UNSCORED RESULT -- judging did not complete.** " +
                    "This result was captured before the judging phase finished (or after it " +
                    "aborted) -- `judgeScores` and the statistics below are empty, not zero. The " +
                    "agent runs themselves (tokens, cost, tool calls) are real and complete; " +
                    "there is no accuracy data to report yet. Re-run judging against this " +
                    "result's agent runs to obtain one."
            )
            appendLine()
        }
        appendLine(
            "_Generated from result `${run.runId}` (schema v${run.schemaVersion}, " +
                "profile `${run.profile.name.lowercase(Locale.ROOT)}`) at ${run.generatedAt}. " +
                "This document is derived from that result's JSON, not hand-written -- " +
                "re-run the report generator against a new result to update it._"
        )
        appendLine()
    }

    // ----------------------------------------------------------- methodology

    private fun StringBuilder.renderMethodology(run: BenchmarkRun) {
        appendLine("## Methodology")
        appendLine()
        appendLine(
            "Every question runs in two arms that differ in exactly one way (AC-7): whether " +
                "the agent has ContextGraph's MCP tools available."
        )
        appendLine()
        appendLine(
            "- **WITH_TOOLS** -- the agent has ContextGraph's MCP server available, working " +
                "in a copy of the repo that has already been indexed (`.contextgraph/graph.db` present)."
        )
        appendLine(
            "- **WITHOUT_TOOLS** -- the control arm, representing a project ContextGraph has " +
                "never touched. This is **not** the same agent with tools switched off: it runs " +
                "in a separate, independently checked-out working copy of the same repo at the " +
                "same pinned SHA that has never been indexed and carries no ContextGraph artefact " +
                "at all (`.contextgraph/`, `graph.db`, `graph.local.db`, `GRAPH_REPORT.md`, " +
                "`graph.html`) -- verified artefact-free before every run (AC-7a). Two working " +
                "copies exist because ContextGraph writes its graph into the project root itself " +
                "(`GraphDb.baseline(root)` resolves to `root/.contextgraph/graph.db`) and that path " +
                "cannot be relocated outside the checkout, so an indexed checkout cannot also serve " +
                "as its own control."
        )
        appendLine()
        appendLine("Everything else is identical between arms: same model, same system prompt, same question text, same budget.")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Agent backend | `${run.agentBackend ?: "not recorded"}` |")
        appendLine("| Measurement | ${describeMeasurement(run.measurement)} |")
        appendLine("| Agent model | `${run.config.models.agentModel}` |")
        appendLine("| Judge model | `${run.config.models.judgeModel}` |")
        // The budget line has to describe the budget that was actually in force. An external
        // agent runs its own loop and cannot be held to a tool-call count, so printing the
        // configured ceiling for such a run states a constraint that was never applied.
        appendLine(
            if (run.agentBackend == "claude-code") {
                val budget = run.config.maxBudgetUsdPerRun
                "| Budget per run | " +
                    (budget?.let { "$$it (same in both arms)" } ?: "unlimited -- no per-run cap was set") +
                    " |"
            } else {
                "| Tool-call ceiling | ${run.config.toolCallCeiling} (same in both arms) |"
            }
        )
        appendLine("| Repeats per (question, arm) | ${run.config.repeatsPerArm} |")
        appendLine()
        appendLine(renderToolUsage(run))

        // Task 20: cost is unavailable for a model ModelPricing has no rate for (e.g. the
        // gpt-4.1-nano default), and that must read as "not recorded" here, not as a silent
        // absence a reader could mistake for "$0.00" further down. See renderIngestAndBreakEven
        // for the matching explicit statement in the break-even verdict itself.
        if (!ModelPricing.hasRate(run.config.models.agentModel)) {
            appendLine(
                "> **Cost not recorded for this run.** No pricing entry exists for agent model " +
                    "`${run.config.models.agentModel}` in `ModelPricing`, so every agent run's " +
                    "`costUsd` was intentionally left unset rather than reported as `\$0.0000` -- " +
                    "token and tool-call counts above are real and unaffected. See \"Ingest Cost " +
                    "& Break-Even\" below, which is correspondingly reported as insufficient data " +
                    "rather than a break-even claim."
            )
            appendLine()
        }

        if (run.ingestRecords.isNotEmpty()) {
            appendLine("Indexing config used for the WITH_TOOLS arm, per repo (AC-2, kept separate from query-time cost):")
            appendLine()
            appendLine("| Repo | `litellm.enabled` | Duration | Tokens | Cost |")
            appendLine("|---|---|---|---|---|")
            run.ingestRecords.sortedBy { it.repoId }.forEach { ingest ->
                appendLine(
                    "| ${ingest.repoId} | ${ingest.config.litellm.enabled} | " +
                        "${fmtDurationMillis(ingest.durationMillis.toDouble())} | " +
                        "${fmtCount(ingest.tokensUsed.toDouble())} | ${fmtCost(ingest.costUsd)} |"
                )
            }
            appendLine()
        }
    }

    // -------------------------------------------------------------- headline

    private fun StringBuilder.renderHeadline(run: BenchmarkRun) {
        appendLine("## Headline Results")
        appendLine()
        appendLine(
            "Pools every GRAPH_HEAVY and NEUTRAL question across all repos. NEGATIVE_CONTROL " +
                "questions are never folded in here (AC-20) -- see \"Negative Controls\" below for " +
                "those. Median across ${run.config.repeatsPerArm} repeat(s), with min/max shown " +
                "alongside every median -- no median is published without its variance."
        )
        appendLine()
        val headline = run.summary?.headline.orEmpty()
        if (headline.isEmpty()) {
            appendLine("_Not run yet -- no headline-category agent runs recorded for this result._")
            appendLine()
            return
        }
        appendMetricsTable(headline.associate { it.arm to it.metrics })
        appendLine()
    }

    // -------------------------------------------------------------- per-repo

    private fun StringBuilder.renderPerRepo(run: BenchmarkRun) {
        appendLine("## Per-Repo Results")
        appendLine()
        if (run.corpusRepos.isEmpty()) {
            appendLine("_No corpus repos recorded for this result._")
            appendLine()
            return
        }
        val perRepoArm = run.summary?.perRepoArm.orEmpty()
        run.corpusRepos.sortedBy { it.id }.forEach { repo ->
            appendLine("### ${repo.name} (`${repo.id}`)")
            appendLine()
            appendLine("Measured at tag `${repo.pinnedTag}`, commit `${repo.pinnedSha}`.")
            appendLine()
            val byArm = perRepoArm.filter { it.repoId == repo.id }.associate { it.arm to it.metrics }
            if (byArm.isEmpty()) {
                appendLine("_Not run yet -- no agent runs recorded for this repo._")
            } else {
                appendMetricsTable(byArm)
            }
            appendLine()
        }
    }

    // ------------------------------------------------------- negative control

    private fun StringBuilder.renderNegativeControl(run: BenchmarkRun) {
        appendLine("## Negative Controls")
        appendLine()
        appendLine(
            "NEGATIVE_CONTROL questions are ones where grep+read is expected to clearly win " +
                "(AC-5). They are never folded into the headline above (AC-20). This section is " +
                "the report's source of credibility, not its weakness -- every place ContextGraph " +
                "loses is shown here exactly as measured."
        )
        appendLine()
        val negativeControl = run.summary?.negativeControl.orEmpty()
        if (negativeControl.isEmpty()) {
            appendLine("_Not run yet -- no negative-control agent runs recorded for this result._")
            appendLine()
            return
        }
        appendMetricsTable(negativeControl.associate { it.arm to it.metrics })
        appendLine()

        val perQuestion = run.summary?.perQuestionArm.orEmpty().filter { it.category == QuestionCategory.NEGATIVE_CONTROL }
        if (perQuestion.isNotEmpty()) {
            appendLine("Per-question breakdown:")
            appendLine()
            appendLine("| Question | Repo | WITH_TOOLS accuracy | WITHOUT_TOOLS accuracy | WITH_TOOLS cost | WITHOUT_TOOLS cost | Verdict |")
            appendLine("|---|---|---|---|---|---|---|")
            perQuestion.map { it.questionId }.distinct().sorted().forEach { questionId ->
                val withRow = perQuestion.firstOrNull { it.questionId == questionId && it.arm == Arm.WITH_TOOLS }
                val withoutRow = perQuestion.firstOrNull { it.questionId == questionId && it.arm == Arm.WITHOUT_TOOLS }
                val repoId = (withRow ?: withoutRow)?.repoId ?: "?"
                val withAccuracy = withRow?.metrics?.accuracyScore
                val withoutAccuracy = withoutRow?.metrics?.accuracyScore
                val withMedian = withAccuracy?.median
                val withoutMedian = withoutAccuracy?.median
                val verdict = when {
                    withMedian == null || withoutMedian == null -> "insufficient data"
                    withMedian > withoutMedian -> "ContextGraph wins on accuracy"
                    withMedian < withoutMedian -> "ContextGraph loses on accuracy"
                    else -> "tie on accuracy"
                }
                appendLine(
                    "| $questionId | $repoId | ${withAccuracy.describe(::fmtAccuracy)} | " +
                        "${withoutAccuracy.describe(::fmtAccuracy)} | " +
                        "${withRow?.metrics?.costUsd.describe(::fmtCost)} | " +
                        "${withoutRow?.metrics?.costUsd.describe(::fmtCost)} | $verdict |"
                )
            }
            appendLine()
        }
    }

    // -------------------------------------------------------- contamination

    /**
     * Contamination totals are the one place this generator counts rather
     * than formats a precomputed number: [AggregatedMetrics] carries
     * `excludedContaminatedRuns` per (question/repo/category, arm) group,
     * but not a whole-run total, and it does not carry
     * `cliInvocationAttempts` (AC-8's "attempt count") at all -- that field
     * has no aggregation anywhere in slice 06's summary. Both are simple
     * filters/sums over [io.contextgraph.benchmark.model.AgentRunRecord]
     * fields that are already on the run (no median, no variance, no
     * break-even math), so computing them here stays within "formatting",
     * but the absence of a summarized field for either is worth folding
     * into `AggregatedMetrics` later -- see the report handed back to the
     * Lead for this slice.
     */
    private fun StringBuilder.renderContamination(run: BenchmarkRun) {
        appendLine("## Contamination Report")
        appendLine()
        appendLine(
            "Both arms reject any attempt to invoke the `contextgraph` CLI directly through Bash " +
                "(sanitized PATH + PreToolUse hook, AC-8). A run where the block itself failed is " +
                "marked `contaminated` and excluded from every total in this document (AC-9) -- " +
                "this section is the only place those runs are still counted."
        )
        appendLine()
        appendLine(
            "Failed runs (crashed before producing a record at all -- distinct from `contaminated`, " +
                "and also excluded from every total above; the suite continues past a single failure " +
                "rather than aborting): " +
                (run.failedRunCount?.toString() ?: "not recorded for this result")
        )
        appendLine()
        if (run.agentRuns.isEmpty()) {
            appendLine("_No agent runs recorded for this result._")
            appendLine()
            return
        }
        appendLine("| Arm | Total runs | Contaminated (excluded from totals above) | CLI invocation attempts (all blocked) |")
        appendLine("|---|---|---|---|")
        Arm.entries.forEach { arm ->
            val runsForArm = run.agentRuns.filter { it.arm == arm }
            val contaminated = runsForArm.count { it.contaminated }
            val attempts = runsForArm.sumOf { it.cliInvocationAttempts }
            appendLine("| ${armLabel(arm)} | ${runsForArm.size} | $contaminated | $attempts |")
        }
        appendLine()
    }

    // -------------------------------------------------------------- ceiling

    private fun StringBuilder.renderCeiling(run: BenchmarkRun) {
        appendLine("## Tool-Call Ceiling Report")
        appendLine()
        appendLine(
            "The ceiling is ${run.config.toolCallCeiling} tool calls, identical in both arms " +
                "(Q2). A run that hits it is truncated, not run to a natural stop -- if the " +
                "control arm hits the ceiling often, its numbers above were obtained *by being " +
                "cut off*, and that is shown here rather than hidden (AC-10a)."
        )
        appendLine()
        if (run.agentRuns.isEmpty()) {
            appendLine("_No agent runs recorded for this result._")
            appendLine()
            return
        }
        appendLine("| Arm | Total runs | Ceiling-hit runs |")
        appendLine("|---|---|---|")
        Arm.entries.forEach { arm ->
            val runsForArm = run.agentRuns.filter { it.arm == arm }
            val hit = runsForArm.count { it.hitCeiling }
            appendLine("| ${armLabel(arm)} | ${runsForArm.size} | $hit |")
        }
        appendLine()
    }

    // ------------------------------------------------------ judge validation

    private fun StringBuilder.renderJudgeValidation(run: BenchmarkRun, kappaValidation: KappaValidationResult?) {
        appendLine("## Judge Validation")
        appendLine()
        appendLine(
            "The judge (`${run.config.models.judgeModel}`) scores every answer against that " +
                "question's gold key-facts, blind to which arm produced the answer -- no tool " +
                "trace, tool-call dump, or arm label reaches it (AC-11). Output is a per-fact " +
                "hit/miss and the accuracy score derived from it, not free text (AC-13)."
        )
        appendLine()
        // Task 18: one answer the judge failed to score is not fatal on its own (see the
        // top-of-document warning for when *every* answer failed to score); it still needs to be
        // visible so a run with a high failure rate is not silently indistinguishable from one
        // that judged cleanly. Same "not recorded" vs. explicit-zero distinction failedRunCount
        // already uses above.
        appendLine(
            "Judge scoring failures (this answer could not be scored and is excluded from the " +
                "accuracy statistics above, same as a contaminated run is excluded from the " +
                "totals above; not fatal on its own -- only every answer failing aborts the run): " +
                (run.failedJudgeCount?.toString() ?: "not recorded for this result")
        )
        appendLine()
        if (kappaValidation == null) {
            appendLine(
                "**Kappa validation was not run for this result.** No second judge model " +
                    "re-scored a subsample, so there is no inter-rater agreement percentage or " +
                    "Cohen's kappa to report here."
            )
            appendLine()
            return
        }
        appendLine("A ~20% subsample of already-scored runs was independently re-scored by a second model (AC-14):")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Primary judge | `${kappaValidation.primaryJudgeModel}` |")
        appendLine("| Secondary judge | `${kappaValidation.secondaryJudgeModel}` |")
        appendLine("| Runs scored by the primary judge | ${kappaValidation.totalRuns} |")
        appendLine("| Runs re-scored for validation | ${kappaValidation.sampledRunIds.size} |")
        appendLine("| Agreement rate | ${fmtAccuracy(kappaValidation.agreementRate)} |")
        appendLine("| Cohen's kappa | ${String.format(Locale.ROOT, "%.3f", kappaValidation.kappa)} |")
        appendLine()
    }

    // ----------------------------------------------------- ingest/break-even

    private fun StringBuilder.renderIngestAndBreakEven(run: BenchmarkRun) {
        appendLine("## Ingest Cost & Break-Even")
        appendLine()
        appendLine(
            "Indexing cost is recorded separately from query-time cost (AC-2). Break-even " +
                "answers: after how many questions does the WITH_TOOLS arm's lower per-question " +
                "cost pay back what indexing cost up front (AC-21)?"
        )
        appendLine()
        if (run.ingestRecords.isEmpty()) {
            appendLine("_No indexing recorded for this result._")
            appendLine()
            return
        }
        val breakEvenByRepo = run.summary?.breakEvenByRepo.orEmpty().associateBy { it.repoId }
        // Task 20: when the agent model has no ModelPricing entry, every per-repo verdict below
        // is InsufficientData for the same underlying reason (no query-time costUsd was ever
        // recorded, not that runs are missing) -- stated once here rather than repeated
        // per-repo, but the per-repo row's own reason string is still replaced below so a reader
        // scanning the table in isolation sees the real cause too, not a generic "missing data".
        val costNotRecorded = !ModelPricing.hasRate(run.config.models.agentModel)
        if (costNotRecorded) {
            appendLine(
                "**Break-even cannot be computed for this result: cost was not recorded** (no " +
                    "`ModelPricing` entry for agent model `${run.config.models.agentModel}`) -- " +
                    "not because WITH_TOOLS or WITHOUT_TOOLS is missing runs."
            )
            appendLine()
        }
        appendLine("| Repo | Ingest cost | Break-even |")
        appendLine("|---|---|---|")
        run.ingestRecords.sortedBy { it.repoId }.forEach { ingest ->
            val verdict = when (val result = breakEvenByRepo[ingest.repoId]?.result) {
                is BreakEvenResult.Amortizes ->
                    "Amortizes -- saves ${fmtCost(result.savingPerQuestionUsd)}/question, breaks even at " +
                        "${String.format(Locale.ROOT, "%.1f", result.questionsToBreakEven)} questions"
                is BreakEvenResult.NotAmortizing ->
                    "Does not amortize on cost alone -- per-question saving is " +
                        "${fmtSignedCost(result.savingPerQuestionUsd)} (WITH_TOOLS costs the same or more per question)"
                is BreakEvenResult.InsufficientData ->
                    if (costNotRecorded) {
                        "Insufficient data -- cost not recorded for agent model `${run.config.models.agentModel}` (no ModelPricing entry), not a missing run"
                    } else {
                        "Insufficient data -- ${result.reason}"
                    }
                null -> "Not computed for this result"
            }
            appendLine("| ${ingest.repoId} | ${fmtCost(ingest.costUsd)} | $verdict |")
        }
        appendLine()
    }

    // ------------------------------------------------------------- reproduce

    private fun StringBuilder.renderReproduction(run: BenchmarkRun) {
        appendLine("## Reproduction")
        appendLine()
        appendLine("Reproduce this result end to end, same profile as this run:")
        appendLine()
        appendLine("```bash")
        appendLine("./gradlew :modules:benchmark:run --args=\"--profile ${run.profile.name.lowercase(Locale.ROOT)}\"")
        appendLine("```")
        appendLine()
        if (run.corpusRepos.isNotEmpty()) {
            appendLine("Corpus repos measured, pinned to the SHA shown under \"Per-Repo Results\" above:")
            appendLine()
            run.corpusRepos.sortedBy { it.id }.forEach { repo ->
                appendLine("- `${repo.name}` (`${repo.id}`) -- ${repo.url} @ `${repo.pinnedTag}` (`${repo.pinnedSha}`)")
            }
            appendLine()
        }
        appendLine(
            "Fast local iteration on the harness itself uses the smoke profile instead " +
                "(1 repo x 3 questions x 1 repeat per arm):"
        )
        appendLine()
        appendLine("```bash")
        appendLine("./gradlew :modules:benchmark:run --args=\"--profile smoke\"")
        appendLine("```")
        appendLine()
        appendLine("`./gradlew build` and `./gradlew check` never run the benchmark (AC-22) -- results are only produced by the commands above.")
        appendLine()
    }

    // ------------------------------------------------------------- shared UI

    private fun armLabel(arm: Arm): String = when (arm) {
        Arm.WITH_TOOLS -> "WITH_TOOLS (ContextGraph MCP)"
        Arm.WITHOUT_TOOLS -> "WITHOUT_TOOLS (control -- separate clean checkout, ContextGraph absent)"
    }

    private fun StringBuilder.appendMetricsTable(metricsByArm: Map<Arm, AggregatedMetrics?>) {
        val arms = Arm.entries.toList()
        appendLine("| Metric | ${arms.joinToString(" | ") { armLabel(it) } } |")
        appendLine("|---|${arms.joinToString("|") { "---" } }|")

        fun row(label: String, formatter: (Double) -> String, extract: (AggregatedMetrics) -> MetricSummary?) {
            appendLine(
                "| $label | " +
                    arms.joinToString(" | ") { arm -> metricsByArm[arm]?.let { extract(it) }.describe(formatter) } +
                    " |"
            )
        }

        row("Total tokens", ::fmtCount) { it.totalTokens }
        row("Input tokens", ::fmtCount) { it.inputTokens }
        row("Output tokens", ::fmtCount) { it.outputTokens }
        row("Tool calls", ::fmtCount) { it.toolCallCount }
        row("File reads", ::fmtCount) { it.fileReadCount }
        row("Wall-clock", ::fmtDurationMillis) { it.wallClockMillis }
        row("Cost", ::fmtCost) { it.costUsd }
        row("Accuracy", ::fmtAccuracy) { it.accuracyScore }

        appendLine(
            "| Runs (total / included / contaminated-excluded / ceiling-hit) | " +
                arms.joinToString(" | ") { arm ->
                    metricsByArm[arm]?.let {
                        "${it.totalRuns} / ${it.includedRuns} / ${it.excludedContaminatedRuns} / ${it.ceilingHitRuns}"
                    } ?: "not run"
                } +
                " |"
        )
    }

    private fun MetricSummary?.describe(formatter: (Double) -> String): String =
        this?.let { "${formatter(it.median)} (min ${formatter(it.min)}, max ${formatter(it.max)}, n=${it.sampleSize})" }
            ?: "no data"

    private fun fmtCount(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded == Math.floor(rounded) && !rounded.isInfinite()) {
            String.format(Locale.ROOT, "%,d", rounded.toLong())
        } else {
            String.format(Locale.ROOT, "%,.1f", rounded)
        }
    }

    private fun fmtCost(value: Double): String = String.format(Locale.ROOT, "$%,.4f", value)

    private fun fmtSignedCost(value: Double): String {
        val sign = if (value < 0.0) "-" else "+"
        return "$sign${fmtCost(kotlin.math.abs(value))}"
    }

    private fun fmtDurationMillis(value: Double): String {
        val seconds = value / 1000.0
        return if (seconds >= 60.0) {
            String.format(Locale.ROOT, "%.1f min", seconds / 60.0)
        } else {
            String.format(Locale.ROOT, "%.1fs", seconds)
        }
    }

    private fun fmtAccuracy(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value * 100.0)
}

/**
 * How often the WITH_TOOLS arm actually called ContextGraph.
 *
 * Without this the report's most dangerous reading is also its most natural one. A run where the
 * agent never touched ContextGraph produces the same "no difference" table as a run where it used
 * it heavily and gained nothing -- and the two mean opposite things: the first says nothing at all
 * about the graph, while only the second is evidence about it. This section is what makes a
 * headline of "+0.00" falsifiable rather than merely disappointing.
 */
private fun renderToolUsage(run: BenchmarkRun): String = buildString {
    val withTools = run.agentRuns.filter { it.arm == Arm.WITH_TOOLS }
    if (withTools.isEmpty()) return@buildString
    val calls = withTools.sumOf { it.contextGraphToolCalls }
    val runsUsing = withTools.count { it.contextGraphToolCalls > 0 }

    appendLine("### ContextGraph tool usage (WITH_TOOLS arm)")
    appendLine()
    appendLine("| | |")
    appendLine("|---|---|")
    appendLine("| ContextGraph tool calls | $calls |")
    appendLine("| Runs that called it at least once | $runsUsing / ${withTools.size} |")
    appendLine()
    if (calls == 0) {
        appendLine(
            "**No ContextGraph tool was ever called in this run.** The two arms were therefore " +
                "behaviourally identical, and any accuracy difference or lack of one below is " +
                "evidence about tool *adoption*, not about whether the graph improves answers. " +
                "Do not read the headline as a verdict on ContextGraph's usefulness."
        )
        appendLine()
    } else if (runsUsing < withTools.size) {
        appendLine(
            "Usage was uneven: ${withTools.size - runsUsing} of ${withTools.size} runs never " +
                "called ContextGraph at all. Per-question figures for those runs carry no " +
                "information about the graph."
        )
        appendLine()
    }
}

/** Spells out which question a result answers, since the two produce identically shaped tables. */
private fun describeMeasurement(measurement: String?): String = when (measurement) {
    "ADOPTION" -> "`adoption` -- nothing was said about ContextGraph; this measures whether an " +
        "agent reaches for it unprompted"
    "EFFICACY" -> "`efficacy` -- both arms were told a code-graph server may be present; this " +
        "measures whether the graph improves answers once used"
    null -> "not recorded (result predates the distinction)"
    else -> "`$measurement`"
}
