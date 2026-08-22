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
        renderIngestCost(run)
        renderCoverage(run)
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
                "otherwise mix the two.** The agent axis measures whether a code-graph tool makes " +
                "a *Claude agent* cheaper/faster/more accurate and requires `ANTHROPIC_API_KEY`. " +
                "This axis answers a narrower question with no model call anywhere in the loop: " +
                "asked the same question, which *files* does each tool put in front of you? " +
                "Deterministic: no LLM call, the same corpus and question set always produce the " +
                "same numbers (proven by the determinism tests in `RetrievalBenchmarkRunnerTest`, " +
                "`RipgrepQueryDeriverTest` and `RipgrepBaselineRunnerTest`)."
        )
        appendLine()
        appendLine("Three sides are compared, and they are named this way everywhere below:")
        appendLine()
        appendLine("| Side | What it is | What it was asked |")
        appendLine("|---|---|---|")
        appendLine(
            "| **${RetrievalSide.CONTEXT_GRAPH.label}** | this repository's own graph, queried " +
                "in-process through `QueryEngine.buildContext` | the raw question text |"
        )
        appendLine(
            "| **${RetrievalSide.CODE_GRAPH.label}** | `@colbymchenry/codegraph` v1.5.0, driven " +
                "as a CLI via `codegraph explore` | the raw question text |"
        )
        appendLine(
            "| **${RetrievalSide.RIPGREP.label}** | plain `rg` over a clean, never-indexed " +
                "checkout | tokens derived from the raw question text by `RipgrepQueryDeriver` |"
        )
        appendLine()
        appendLine(
            "**The two graph tools' names differ by two letters, so they are never written bare " +
                "in a table header, a column label, or a verdict.** \"ContextGraph\" is this " +
                "project; \"CodeGraph\" is the third-party tool being compared against it. A " +
                "result favouring CodeGraph is a legitimate outcome of this measurement, not an " +
                "error in it."
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
                "`file:line` evidence -- never a hand-written second ground truth, and never a " +
                "per-tool one. All three sides are scored against that same set with " +
                "precision@k, recall@k, and reciprocal rank (`RetrievalMetrics`, unit-tested " +
                "against known input/output pairs)."
        )
        appendLine()
        appendLine(
            "**Fairness invariants.** Each side is handed the same raw `question.text`, with " +
                "nothing pre-filtered and nothing lifted from the gold facts; each is scored " +
                "against the same expected set with the same metrics; and no side's output is " +
                "re-ranked, filtered or truncated before scoring -- each runner projects paths in " +
                "its tool's own emission order and stops. A number obtained by breaking one of " +
                "these is worse than no number."
        )
        appendLine()
        appendLine(
            "**Absence is never scored as zero.** A question a tool could not be *asked* -- an " +
                "index that failed its integrity check, a `codegraph explore` call that timed out " +
                "or errored -- is excluded from that side's average and listed under " +
                "\"Skipped\" below, rather than folded in as 0.0. Counting it as zero would " +
                "blame the tool for an infrastructure failure instead of a retrieval one. A tool " +
                "that *ran* and returned nothing is a real zero and is counted as one. Each " +
                "side's table shows how many questions it actually measured, so a smaller " +
                "denominator is visible rather than implied."
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
            "**${RetrievalSide.CONTEXT_GRAPH.label} side**: `QueryEngine.buildContext(question.text)` against the WITH " +
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
            "**${RetrievalSide.CODE_GRAPH.label} side**: `codegraph explore -- <raw question " +
                "text>` against a third working copy of the same pinned checkout, indexed by " +
                "CodeGraph and by nothing else. Driven as a CLI rather than as an MCP server " +
                "because its own `explore` command routes into the same handler as its " +
                "`codegraph_explore` MCP tool, which keeps this side deterministic and LLM-free " +
                "exactly as the baseline is.\n" +
                "\n" +
                "Two parsing decisions were made here, and both move the numbers, so both are " +
                "stated rather than left to be inferred. `explore` has **no `--json` flag**, so " +
                "the ranked list is parsed out of its markdown.\n" +
                "\n" +
                "1. **The ranked files are the ones CodeGraph rendered source for** -- its " +
                "`Source Code` section, in emission order. Those are the files its own header " +
                "counts (\"Found N symbols across M files\") and the ones its output tells an " +
                "agent to treat as already read. The paths named in the preceding **blast-radius** " +
                "section are *not* counted: they are annotations about impact on those same " +
                "symbols. On a representative real response that is 4 ranked files against 7 " +
                "further paths mentioned only in blast radius, so counting them would raise " +
                "CodeGraph's recall and lower its precision.\n" +
                "2. **The `### ⚠️ Low-confidence match` sentinel neither truncates the list nor " +
                "adds to it.** Files above it stay ranked. The section itself contains no file " +
                "paths at all -- only advisory prose and *directory* hints -- and in v1.5.0 " +
                "`codegraph explore` cannot emit it in the first place (only its `ContextBuilder` " +
                "path does, which the CLI does not reach). The parser handles it defensively so a " +
                "future version routing `explore` through that builder cannot silently truncate a " +
                "ranked list."
        )
        appendLine()
        appendLine(
            "**${RetrievalSide.RIPGREP.label} side**: `rg -F -w --count -e <token1> -e <token2> ...` against the " +
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

    // --------------------------------------------------------- ingest cost

    /**
     * Ingest cost is read from the manifest corpus preparation wrote, never measured here -- the
     * retrieval run is strictly read-only, which is what lets it observe a corpus another process
     * is still indexing.
     */
    private fun StringBuilder.renderIngestCost(run: RetrievalRun) {
        appendLine("### Ingest cost")
        appendLine()
        appendLine(
            "What it cost to build each tool's index, so query-time results are read next to the " +
                "price of getting there. `ripgrep (baseline)` has no ingest step at all -- it " +
                "searches the working tree directly, which is exactly why it is the baseline."
        )
        appendLine()
        if (run.ingestCosts.isEmpty()) {
            appendLine("_Not recorded — this corpus carries no ingest manifest (prepared by an older build, or not at all)._")
            appendLine()
            return
        }
        appendLine("| Repo | Tool | Index build time | Index size |")
        appendLine("|---|---|---|---|")
        run.ingestCosts.sortedWith(compareBy({ it.repoId }, { it.tool.id })).forEach { cost ->
            val label = RetrievalSide.of(cost.tool).label
            if (cost.absentReason != null) {
                appendLine("| `${cost.repoId}` | ${label} | _not built_ | _${cost.absentReason}_ |")
            } else {
                appendLine(
                    "| `${cost.repoId}` | ${label} | ${fmtDuration(cost.durationMillis)} | " +
                        "${fmtBytes(cost.indexSizeBytes)} |"
                )
            }
        }
        appendLine()
    }

    // ------------------------------------------------------------ coverage

    /**
     * The published answer to an asymmetry that would otherwise sit invisibly in ContextGraph's
     * favour. Says so in the document, including the part that is still uneven.
     */
    private fun StringBuilder.renderCoverage(run: RetrievalRun) {
        appendLine("### Gold-file coverage")
        appendLine()
        appendLine(
            "How much of each repo's gold-fact-cited file set each tool's index can answer for at " +
                "all. A tool cannot retrieve a file it never indexed, so a low figure here caps " +
                "that tool's recall below and should be read *with* the scores, not after them."
        )
        appendLine()
        appendLine(
            "**Why this is published rather than gated.** `IndexIntegrityGate` refuses to score " +
                "the ${RetrievalSide.CONTEXT_GRAPH.label} side of a repo whose index is missing " +
                "gold-cited files. It applies to that side only. Left unstated, that would bias " +
                "the comparison toward this project: an incomplete ContextGraph index is dropped " +
                "from the scores, while an equally incomplete CodeGraph index would be scored. " +
                "The gate was deliberately **not** loosened -- weakening it to obtain a number is " +
                "precisely the manufactured result this exercise exists to avoid -- so the " +
                "remedy is to print both tools' coverage instead. **The residual, stated plainly: " +
                "a repo failing the gate is still absent from the ${RetrievalSide.CONTEXT_GRAPH.label} " +
                "column while its ${RetrievalSide.CODE_GRAPH.label} column is scored.** Coverage " +
                "makes that visible; it does not make it symmetric."
        )
        appendLine()
        if (run.goldFileCoverage.isEmpty()) {
            appendLine("_Not recorded in this run._")
            appendLine()
            return
        }
        appendLine("| Repo | Side | Gold-cited files in index | Coverage | How it was determined |")
        appendLine("|---|---|---|---|---|")
        run.goldFileCoverage.sortedWith(compareBy({ it.repoId }, { it.side.ordinal })).forEach { c ->
            val counts = c.presentFileCount?.let { "$it / ${c.citedFileCount}" } ?: "_not determinable_"
            val basis = when (c.basis) {
                CoverageBasis.INDEX_QUERY -> "queried the index, file by file"
                CoverageBasis.READS_WORKING_TREE -> "100% by construction — reads the working tree directly"
                // Deliberately covers both causes rather than naming one. This state is reached
                // when the index is absent AND when it exists but exposes no per-file read
                // surface, and the renderer cannot tell them apart from the figure alone --
                // claiming the wrong one would be a small, confident falsehood in a document
                // whose whole value is that it does not make those.
                CoverageBasis.NOT_DETERMINABLE -> "no index, or no readable per-file index surface"
            }
            appendLine("| `${c.repoId}` | ${c.side.label} | $counts | ${fmtPercent(c.fraction)} | $basis |")
        }
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
        appendLine(
            "| Question | Repo | ripgrep tokens | ${RetrievalSide.CONTEXT_GRAPH.label} | " +
                "${RetrievalSide.CODE_GRAPH.label} | ${RetrievalSide.RIPGREP.label} | Verdict |"
        )
        appendLine("|---|---|---|---|---|---|---|")
        negativeControlResults.sortedBy { it.questionId }.forEach { result ->
            val k = run.kValues.max()
            val cgRecall = result.contextGraph?.recallAtK?.get(k)
            val codeRecall = result.codeGraph?.recallAtK?.get(k)
            val rgRecall = result.ripgrep.recallAtK[k] ?: 0.0
            // Named in full, because "CodeGraph wins" and "ContextGraph wins" differ by two
            // letters and this column is exactly where a reader skims.
            val best = listOfNotNull(
                cgRecall?.let { RetrievalSide.CONTEXT_GRAPH to it },
                codeRecall?.let { RetrievalSide.CODE_GRAPH to it },
                RetrievalSide.RIPGREP to rgRecall
            ).maxByOrNull { it.second }
            val verdict = when {
                best == null -> "nothing measured (see Skipped)"
                best.second == 0.0 -> "no side found a gold file"
                listOfNotNull(cgRecall, codeRecall, rgRecall).count { it == best.second } > 1 -> "tie at ${fmtPercent(best.second)}"
                else -> "${best.first.label} leads"
            }
            appendLine(
                "| ${result.questionId} | ${result.repoId} | " +
                    (result.ripgrepQueryTokens.takeIf { it.isNotEmpty() }?.joinToString(", ") { "`$it`" } ?: "_none derivable_") +
                    " | ${fmtPercent(cgRecall)} | ${fmtPercent(codeRecall)} | ${fmtPercent(rgRecall)} | $verdict |"
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
        appendLine("./gradlew :modules:benchmark:prepareCorpus --args=\"--repos keycloak,excalidraw\"")
        appendLine("./gradlew :modules:benchmark:runRetrieval  --args=\"--output-dir results/three-way\"")
        appendLine("```")
        appendLine()
        appendLine(
            "`runRetrieval` never clones, indexes, or re-indexes anything -- it reads the corpus " +
                "`prepareCorpus` left behind, which is what lets it observe a corpus another " +
                "process is still indexing without racing it. `./gradlew build` and " +
                "`./gradlew check` run neither task."
        )
        appendLine()
        appendLine(
            "**This instrument is not self-contained, and a fresh machine cannot reproduce it " +
                "from this repository alone.** Two things must be installed by hand first:"
        )
        appendLine()
        appendLine("- `ripgrep` (`rg`) on `PATH`, for the baseline side.")
        appendLine(
            "- `@colbymchenry/codegraph` v1.5.0 on `PATH` (`npm i -g @colbymchenry/codegraph@1.5.0`), " +
                "for the ${RetrievalSide.CODE_GRAPH.label} side. It was installed on the host " +
                "rather than vendored into the repository, deliberately, so that producing these " +
                "numbers needed no package-registry access from the measuring environment. That " +
                "is a real, accepted cost of the setup and is recorded here rather than left for " +
                "a reader to discover: without it, the CodeGraph column is absent — and it is " +
                "absent *visibly*, as a recorded skip, never as a zero."
        )
        appendLine()
        appendLine(
            "Without `codegraph`, `prepareCorpus` still succeeds: the third working copy is " +
                "created and left unindexed, with the reason recorded in that repo's " +
                "`ingest.json` and echoed to the console."
        )
        appendLine()
    }

    // ------------------------------------------------------------- shared UI

    private fun StringBuilder.appendAggregateTable(run: RetrievalRun, aggregate: RetrievalAggregate) {
        val codeGraphMeasured = aggregate.codeGraph?.let { "${it.measuredCount}/${aggregate.questionCount}" }
            ?: "not in this run"
        appendLine(
            "n=${aggregate.questionCount} question(s). Measured: " +
                "${RetrievalSide.CONTEXT_GRAPH.label} " +
                "${aggregate.contextGraph.measuredCount}/${aggregate.questionCount}; " +
                "${RetrievalSide.CODE_GRAPH.label} $codeGraphMeasured; " +
                "${RetrievalSide.RIPGREP.label} ${aggregate.ripgrep.measuredCount}/${aggregate.questionCount}. " +
                "Where a count is short of n, those questions are listed under \"Skipped\" — they " +
                "are excluded from that column's mean, not counted as zero."
        )
        appendLine()
        appendLine(
            "| Metric | ${RetrievalSide.CONTEXT_GRAPH.label} | ${RetrievalSide.CODE_GRAPH.label} | " +
                "${RetrievalSide.RIPGREP.label} |"
        )
        appendLine("|---|---|---|---|")
        run.kValues.forEach { k ->
            appendLine(
                "| precision@$k | ${fmtPercent(aggregate.contextGraph.meanPrecisionAtK[k])} | " +
                    "${fmtPercent(aggregate.codeGraph?.meanPrecisionAtK?.get(k))} | " +
                    "${fmtPercent(aggregate.ripgrep.meanPrecisionAtK[k])} |"
            )
        }
        run.kValues.forEach { k ->
            appendLine(
                "| recall@$k | ${fmtPercent(aggregate.contextGraph.meanRecallAtK[k])} | " +
                    "${fmtPercent(aggregate.codeGraph?.meanRecallAtK?.get(k))} | " +
                    "${fmtPercent(aggregate.ripgrep.meanRecallAtK[k])} |"
            )
        }
        appendLine(
            "| MRR | ${fmtScore(aggregate.contextGraph.mrr)} | " +
                "${aggregate.codeGraph?.let { fmtScore(it.mrr) } ?: "n/a"} | " +
                "${fmtScore(aggregate.ripgrep.mrr)} |"
        )
        appendLine()
    }

    /** `n/a` for an absent measurement, never `0.0%` — an unknown must not read as a result. */
    private fun fmtPercent(value: Double?): String =
        if (value == null) "n/a" else String.format(Locale.ROOT, "%.1f%%", value * 100.0)

    private fun fmtScore(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private fun fmtDuration(millis: Long?): String = when {
        millis == null -> "n/a"
        millis == 0L -> "reused existing index"
        millis < 1_000 -> "${millis}ms"
        millis < 60_000 -> String.format(Locale.ROOT, "%.1fs", millis / 1000.0)
        else -> String.format(Locale.ROOT, "%dm %ds", millis / 60_000, (millis % 60_000) / 1000)
    }

    private fun fmtBytes(bytes: Long?): String = when {
        bytes == null -> "_size unknown_"
        bytes >= 1_000_000_000 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.ROOT, "%.1f kB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
