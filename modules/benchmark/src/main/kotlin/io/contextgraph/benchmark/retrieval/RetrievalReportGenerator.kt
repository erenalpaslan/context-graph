package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory
import java.util.Locale

/**
 * Renders the retrieval axis's section of `BENCHMARKS.md` from a [RetrievalRun], and merges it
 * into that file without disturbing whatever the agent-A/B axis
 * ([io.contextgraph.benchmark.report.BenchmarksReportGenerator]) already wrote there.
 *
 * The spec is explicit that these are two different axes measuring two different things and must
 * never be presented as one table -- but both write into the *same* `BENCHMARKS.md` (a
 * requirement, not a convenience: a reader should find every result in one document). [upsert]
 * is how that's reconciled without either axis's generator needing to know the other exists:
 * this section is wrapped in a pair of HTML-comment markers ([SECTION_START]/[SECTION_END]) that
 * are invisible when the file renders as Markdown; a later run replaces only the text between
 * them (or appends the markers for the first time) and never touches anything outside them. The
 * agent-A/B generator fully regenerates the rest of the file from its own `BenchmarkRun` on its
 * own schedule; this generator never reads or depends on that content, only preserves it
 * byte-for-byte.
 */
object RetrievalReportGenerator {

    private const val SECTION_START = "<!-- retrieval-axis:start -->"
    private const val SECTION_END = "<!-- retrieval-axis:end -->"

    fun generate(run: RetrievalRun): String = buildString {
        appendLine(SECTION_START)
        appendLine()
        renderHeader(run)
        renderMethodology(run)
        renderSkipped(run)
        renderHeadline(run)
        renderCategoryBreakdown(run)
        renderRepoBreakdown(run)
        renderNegativeControl(run)
        renderReproduction(run)
        append(SECTION_END)
    }

    /**
     * Replaces the section between [SECTION_START]/[SECTION_END] inside [existingMarkdown] with
     * [sectionMarkdown] (or appends it, markers included, if not present yet). Pure string
     * surgery -- deterministic, no I/O -- so it can be (and is, in `RetrievalReportGeneratorTest`)
     * proven with known input/output pairs independent of any real file on disk.
     */
    fun upsert(existingMarkdown: String, sectionMarkdown: String): String {
        val startIdx = existingMarkdown.indexOf(SECTION_START)
        val endIdx = existingMarkdown.indexOf(SECTION_END)
        return if (startIdx >= 0 && endIdx >= startIdx) {
            existingMarkdown.substring(0, startIdx) + sectionMarkdown + existingMarkdown.substring(endIdx + SECTION_END.length)
        } else {
            val separator = when {
                existingMarkdown.isBlank() -> ""
                existingMarkdown.endsWith("\n\n") -> ""
                existingMarkdown.endsWith("\n") -> "\n"
                else -> "\n\n"
            }
            existingMarkdown + separator + sectionMarkdown + "\n"
        }
    }

    // ---------------------------------------------------------------- header

    private fun StringBuilder.renderHeader(run: RetrievalRun) {
        appendLine("## Retrieval Axis (LLM-free, deterministic)")
        appendLine()
        appendLine(
            "**A separate axis from the agent A/B results above -- do not sum, average, or " +
                "otherwise mix the two.** The agent axis measures whether ContextGraph makes a " +
                "*Claude agent* cheaper/faster/more accurate and requires `ANTHROPIC_API_KEY`. " +
                "This axis answers a narrower question with no model call anywhere in the loop: " +
                "does ContextGraph's own query surface " +
                "(`io.contextgraph.query.QueryEngine.buildContext`) return the right *files* for " +
                "a question, compared to a `ripgrep` baseline run over the same question text " +
                "against a clean, never-indexed checkout of the same repo. Deterministic: no LLM " +
                "call, no network access, the same corpus and question set always produce the " +
                "same numbers (proven by the determinism tests in `RetrievalBenchmarkRunnerTest`, " +
                "`RipgrepQueryDeriverTest` and `RipgrepBaselineRunnerTest`)."
        )
        appendLine()
        appendLine(
            "_Generated from retrieval result `${run.runId}` (schema v${run.schemaVersion}) at " +
                "${run.generatedAt}. Regenerate by re-running the retrieval measurement; this " +
                "section is not hand-edited._"
        )
        appendLine()
    }

    // ----------------------------------------------------------- methodology

    private fun StringBuilder.renderMethodology(run: RetrievalRun) {
        appendLine("### Methodology")
        appendLine()
        appendLine(
            "For every question, the expected file set is derived from its own gold facts' " +
                "`file:line` evidence -- never a hand-written second ground truth (AC-23). Both " +
                "sides are scored against that same set with precision@k, recall@k, and " +
                "reciprocal rank (`RetrievalMetrics`, unit-tested against known input/output " +
                "pairs)."
        )
        appendLine()
        appendLine(
            "`k` = ${run.kValues.joinToString(", ")}: the real gold set's expected-file-set size " +
                "across all questions has a median of 3 and a maximum of 5, so k=5 is the " +
                "smallest k at which every question's recall@k can reach 1.0 in principle; k=10 " +
                "is a softer, twice-as-generous ceiling."
        )
        appendLine()
        appendLine(
            "**ContextGraph side**: `QueryEngine.buildContext(question.text)` against the WITH " +
                "(indexed) working copy -- the surface method closest to what an agent's own " +
                "tool call makes (`ContextGraphMcpToolBridge`'s and the MCP server's " +
                "`build_context` tool both call the same method). The ranked file list is the " +
                "evidence list's paths, de-duplicated in rank order -- see " +
                "`ContextGraphRetrievalRunner`."
        )
        appendLine()
        appendLine(
            "**This axis has already found and driven two product defects. Read both before the " +
                "numbers below.**\n" +
                "\n" +
                "1. **Natural-language queries returned nothing at all -- found here, since fixed.** " +
                "`buildContext`'s seed search passed the *entire* question sentence to SQLite FTS5 " +
                "as one literal `MATCH` expression. FTS5 gives bareword queries implicit-AND " +
                "semantics, so every token had to co-occur in one indexed row; punctuation in the " +
                "sentence could also be parsed as FTS5 query syntax and throw, and that exception " +
                "was swallowed into a `label LIKE '%<whole sentence>%'` fallback that could not " +
                "match either. Measured on excalidraw's real index at the time: full sentence 0 " +
                "rows, LIKE fallback 0 rows, the same words OR'd 28 rows. The first run of this " +
                "axis therefore scored ContextGraph 0.0% on precision@5/10, recall@5/10 and MRR " +
                "across all 22 measurable questions. `searchNodes` now tokenizes the query and " +
                "OR's the terms as quoted phrases ranked by bm25; the numbers below are from after " +
                "that fix. This is what the axis is for: it found a defect on the exact path MCP's " +
                "`build_context` tool uses, and made the repair measurable.\n" +
                "\n" +
                "2. **Go source yields no symbols at all -- found here, NOT yet fixed.** gin's " +
                "index contains 847 `Document`, 206 `Concept` and 128 file-level nodes and " +
                "*zero* `Function`, `Method`, `Class`, `Interface` or `Module` nodes; " +
                "`ServeHTTP`, `handleHTTPRequest`, `combineHandlers` and `Engine` all return 0 " +
                "hits. The TypeScript repos in the same corpus extract thousands of each " +
                "(excalidraw 1284 functions, calcom 5969). gin's flat 0.0% below is that gap, not " +
                "a retrieval-quality result: there is nothing indexed for the query to find. Any " +
                "Go row in the tables below should be read as measuring extraction coverage, not " +
                "retrieval."
        )
        appendLine()
        appendLine(
            "**ripgrep side**: `rg -F -w --count -e <token1> -e <token2> ...` against the " +
                "WITHOUT (clean, never-indexed) working copy. The tokens are never the raw " +
                "question sentence -- `RipgrepQueryDeriver` is the single place that derives " +
                "them, extracting quoted spans, identifier-shaped words (camelCase, " +
                "`snake_case`, dotted symbols, file-like tokens), and bare numeric literals from " +
                "the question text, exactly what a human reading the question and reaching for " +
                "`rg` would notice and search for. Files are ranked by matching-line count, " +
                "descending. A question with no derivable tokens (a purely conceptual question " +
                "naming no symbol, file, or constant) yields an empty ripgrep result -- reported " +
                "as such, not padded."
        )
        appendLine()
    }

    // ------------------------------------------------------------ skipped

    private fun StringBuilder.renderSkipped(run: RetrievalRun) {
        if (run.skippedRepos.isEmpty()) return
        appendLine("### Skipped")
        appendLine()
        appendLine(
            "Not silently omitted -- every repo this run could not fully measure, and why:"
        )
        appendLine()
        appendLine("| Repo | Reason |")
        appendLine("|---|---|")
        run.skippedRepos.forEach { skip ->
            appendLine("| ${skip.repoId} | ${skip.reason} |")
        }
        appendLine()
    }

    // -------------------------------------------------------------- headline

    private fun StringBuilder.renderHeadline(run: RetrievalRun) {
        appendLine("### Headline (GRAPH_HEAVY + NEUTRAL)")
        appendLine()
        appendLine(
            "Negative-control questions are excluded here on purpose (AC-26) -- see " +
                "\"Negative Controls\" below."
        )
        appendLine()
        val summary = run.summary
        if (summary == null || summary.headline.questionCount == 0) {
            appendLine("_No headline questions measured in this run._")
            appendLine()
            return
        }
        appendAggregateTable(run, summary.headline)
    }

    // ------------------------------------------------------- category breakdown

    private fun StringBuilder.renderCategoryBreakdown(run: RetrievalRun) {
        appendLine("### By Category")
        appendLine()
        val summary = run.summary
        if (summary == null) {
            appendLine("_Not run yet._")
            appendLine()
            return
        }
        QuestionCategory.entries.forEach { category ->
            val aggregate = summary.byCategory[category]
            appendLine("#### ${category.name}")
            appendLine()
            if (aggregate == null || aggregate.questionCount == 0) {
                appendLine("_No questions in this category measured in this run._")
                appendLine()
            } else {
                appendAggregateTable(run, aggregate)
            }
        }
    }

    // ---------------------------------------------------------- repo breakdown

    private fun StringBuilder.renderRepoBreakdown(run: RetrievalRun) {
        appendLine("### By Repo")
        appendLine()
        val summary = run.summary
        if (summary == null || summary.byRepo.isEmpty()) {
            appendLine("_No repos measured in this run._")
            appendLine()
            return
        }
        summary.byRepo.toSortedMap().forEach { (repoId, aggregate) ->
            appendLine("#### `$repoId`")
            appendLine()
            appendAggregateTable(run, aggregate)
        }
    }

    // ------------------------------------------------------- negative control

    private fun StringBuilder.renderNegativeControl(run: RetrievalRun) {
        appendLine("### Negative Controls")
        appendLine()
        appendLine(
            "Questions where `grep` is expected to clearly win (AC-5, AC-26). Reported " +
                "separately from the headline above, including every place ContextGraph loses -- " +
                "that is this section's entire purpose."
        )
        appendLine()
        val summary = run.summary
        if (summary == null || summary.negativeControl.questionCount == 0) {
            appendLine("_No negative-control questions measured in this run._")
            appendLine()
        } else {
            appendAggregateTable(run, summary.negativeControl)
        }

        val negativeControlResults = run.results.filter { it.category == QuestionCategory.NEGATIVE_CONTROL }
        if (negativeControlResults.isEmpty()) return

        appendLine("Per-question breakdown (recall@${run.kValues.max()}, higher is better):")
        appendLine()
        appendLine("| Question | Repo | ripgrep tokens | ContextGraph recall | ripgrep recall | Verdict |")
        appendLine("|---|---|---|---|---|---|")
        negativeControlResults.sortedBy { it.questionId }.forEach { result ->
            val k = run.kValues.max()
            val cgRecall = result.contextGraph?.recallAtK?.get(k)
            val rgRecall = result.ripgrep.recallAtK[k] ?: 0.0
            val verdict = when {
                cgRecall == null -> "ContextGraph not measured (see Skipped)"
                cgRecall > rgRecall -> "ContextGraph wins"
                cgRecall < rgRecall -> "ripgrep wins (expected for a negative control)"
                else -> "tie"
            }
            appendLine(
                "| ${result.questionId} | ${result.repoId} | " +
                    (result.ripgrepQueryTokens.takeIf { it.isNotEmpty() }?.joinToString(", ") { "`$it`" } ?: "_none derivable_") +
                    " | ${cgRecall?.let(::fmtPercent) ?: "n/a"} | ${fmtPercent(rgRecall)} | $verdict |"
            )
        }
        appendLine()
    }

    // ------------------------------------------------------------- reproduce

    private fun StringBuilder.renderReproduction(run: RetrievalRun) {
        appendLine("### Reproduction")
        appendLine()
        appendLine("Reproduce this retrieval result, against an already-prepared corpus:")
        appendLine()
        appendLine("```bash")
        appendLine("./gradlew :modules:benchmark:runRetrieval")
        appendLine("```")
        appendLine()
        appendLine(
            "Requires `ripgrep` (`rg`) on `PATH` and a corpus already prepared via " +
                "`./gradlew :modules:benchmark:prepareCorpus` -- this command never clones, " +
                "indexes, or re-indexes anything itself. `./gradlew build` and `./gradlew check` " +
                "never run it either."
        )
        appendLine()
    }

    // ------------------------------------------------------------- shared UI

    private fun StringBuilder.appendAggregateTable(run: RetrievalRun, aggregate: RetrievalAggregate) {
        appendLine(
            "n=${aggregate.questionCount} question(s). ContextGraph measured " +
                "${aggregate.contextGraph.measuredCount}/${aggregate.questionCount} " +
                "(see \"Skipped\" for the rest, if any); ripgrep measured all " +
                "${aggregate.ripgrep.measuredCount}."
        )
        appendLine()
        appendLine("| Metric | ContextGraph | ripgrep |")
        appendLine("|---|---|---|")
        run.kValues.forEach { k ->
            appendLine(
                "| precision@$k | ${fmtPercent(aggregate.contextGraph.meanPrecisionAtK[k])} | " +
                    "${fmtPercent(aggregate.ripgrep.meanPrecisionAtK[k])} |"
            )
        }
        run.kValues.forEach { k ->
            appendLine(
                "| recall@$k | ${fmtPercent(aggregate.contextGraph.meanRecallAtK[k])} | " +
                    "${fmtPercent(aggregate.ripgrep.meanRecallAtK[k])} |"
            )
        }
        appendLine("| MRR | ${fmtScore(aggregate.contextGraph.mrr)} | ${fmtScore(aggregate.ripgrep.mrr)} |")
        appendLine()
    }

    private fun fmtPercent(value: Double?): String =
        if (value == null) "n/a" else String.format(Locale.ROOT, "%.1f%%", value * 100.0)

    private fun fmtScore(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
}
