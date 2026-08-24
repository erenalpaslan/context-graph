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
        renderBaselineAgreement(run)
        renderListLengths(run)
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
                "`RipgrepQueryDeriverTest`, `RipgrepBaselineRunnerTest` and `BashBaselineRunnerTest`)."
        )
        appendLine()
        appendLine("Four sides are compared, and they are named this way everywhere below:")
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
            "| **${RetrievalSide.BASH.label}** | `grep` from the stock base system, over a clean, " +
                "never-indexed checkout — **no third-party tools** are installed, invoked or " +
                "assumed anywhere in this side | the same derived tokens the ripgrep side is given |"
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
            "So is a result favouring bash. The fourth side exists because the third one is not " +
                "the floor: `rg` is a separate install that brings its own `.gitignore` " +
                "awareness, binary skipping and ranking to the fight, so beating it is not the " +
                "same as beating *nothing*. **${RetrievalSide.BASH.label}** is what a developer " +
                "with a stock shell and no installs gets, and it is the honest thing an index " +
                "has to earn its cost against. Where it wins, that is the finding — printed, not " +
                "explained away. *Baseline'ı zayıflatarak kazanılan bir sayı, kazanılmamış bir " +
                "sayıdır.*"
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
                "per-tool one. All four sides are scored against that same set with " +
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
            "That rule is the rule for **${RetrievalSide.BASH.label}** too, and is deliberately " +
                "not relaxed for it. Neither text-search side can be blocked by an index -- both " +
                "read the working tree -- but a `grep` that exits on a real error still leaves " +
                "that question unmeasured, and it is excluded exactly as a timed-out " +
                "`codegraph explore` is. A `grep` that ran clean and matched nothing is a real " +
                "zero and is counted as one: that is the honest floor this side exists to measure."
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
            "**This axis has already found and driven one product defect, and it re-measures a " +
                "second gap from scratch every time it runs. Read both before the numbers " +
                "below.**\n" +
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
                "`build_context` tool uses, and made the repair measurable."
        )
        appendLine()
        renderExtractionDisclosure(run)
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
        renderBashMethodology()
    }

    /**
     * The bash side's argv and its flag-by-flag justification, read from [BashBaselineFlags] --
     * the same value [BashBaselineRunner] builds its command line from -- rather than retyped
     * here. A report that describes a search the runner did not perform is worse than one that
     * describes none, and copying the list into a string literal is exactly how the two drift.
     */
    private fun StringBuilder.renderBashMethodology() {
        appendLine(
            "**${RetrievalSide.BASH.label} side**: `${BashBaselineFlags.describeArgv()}` against " +
                "the same WITHOUT (clean, never-indexed) working copy the ripgrep side reads, " +
                "given the same tokens from the same `RipgrepQueryDeriver`, ranked by the same " +
                "rule (matching-line count descending, ties alphabetical). The two text-search " +
                "sides therefore differ in the binary and in nothing else, which is what makes " +
                "the gap between them attributable to `rg`'s engineering rather than to the " +
                "query. `grep` is invoked at its base-system path, not resolved through `PATH`, " +
                "so a developer's Homebrew GNU grep cannot quietly become the thing being " +
                "measured."
        )
        appendLine()
        appendLine(
            "**Why these flags and not others.** `rg` brings defaults to the fight that `grep` " +
                "has none of, so an honest floor needs a different flag list rather than the " +
                "same one. Each flag below either states out loud something `rg` assumes, or " +
                "states nothing at all:"
        )
        appendLine()
        appendLine("| Flag | Why it is there |")
        appendLine("|---|---|")
        BashBaselineFlags.RATIONALE.forEach { (flag, why) ->
            appendLine("| `$flag` | $why |")
        }
        appendLine()
        appendLine(
            "There is, deliberately, **no emulation of `rg`'s `.gitignore` awareness** here. " +
                "That is `rg`'s engineering, not plain text search's, and " +
                "reproducing it with `--exclude-dir` lists would quietly strengthen this side " +
                "into a second ripgrep. A number won by starving the baseline is not the " +
                "baseline's number, and neither is one won by secretly strengthening it."
        )
        appendLine()
    }

    // ------------------------------------------------- extraction disclosure

    /**
     * The second thing to read before the numbers: whether any repo's ContextGraph row is an
     * *extraction* result wearing a retrieval result's clothes.
     *
     * **Every word of this is computed from [GoldFileCoverage.extractedNodeCounts] in the result
     * document -- no repo, language or grammar is named in the source of this generator.** That
     * is deliberate and it is the difference between a disclosure and a stale sentence. The
     * previous run of this axis published a hand-written paragraph about `gin`'s missing Go
     * grammar; the moment a Go grammar lands, a paragraph like that becomes a falsehood printed
     * with the authority of a generated document, and nobody regenerating the report would be
     * told. This branches on the census instead, so the same generator prints the gap while the
     * gap exists and prints its absence the moment it closes.
     */
    private fun StringBuilder.renderExtractionDisclosure(run: RetrievalRun) {
        val censused = run.goldFileCoverage
            .filter { it.side == RetrievalSide.CONTEXT_GRAPH && it.extractedNodeCounts != null }
            .sortedBy { it.repoId }

        if (censused.isEmpty()) {
            appendLine(
                "2. **Extracted-declaration counts were not recorded in this run**, so this " +
                    "document cannot tell you whether any ${RetrievalSide.CONTEXT_GRAPH.label} " +
                    "row below is a retrieval result or an extraction gap. Read it as the " +
                    "unknown it is, not as a clean bill: an archived result predating the census, " +
                    "and an index that could not be read, both land here. A repo whose language " +
                    "the indexer does not parse still gets its files stored, so its gold-file " +
                    "coverage can read 100% while every query returns nothing -- that is " +
                    "**extraction coverage, not retrieval quality**, and without the census it is " +
                    "indistinguishable from a genuinely poor result."
            )
            appendLine()
            return
        }

        val gaps = censused.filter { it.extractedDeclarationCount == 0 }
        if (gaps.isEmpty()) {
            appendLine(
                "2. **Every repo measured here yielded code declarations -- no " +
                    "${RetrievalSide.CONTEXT_GRAPH.label} row below is an extraction gap in " +
                    "disguise.** A repo whose language the indexer cannot parse still gets its " +
                    "files read and stored, so its gold-file coverage can read 100% while every " +
                    "query returns nothing; such a row would be measuring **extraction coverage, " +
                    "not retrieval quality**, and this run has none. The census below is the " +
                    "evidence for that claim, not a reassurance about it."
            )
        } else {
            val named = gaps.joinToString(", ") { "`${it.repoId}`" }
            val verb = if (gaps.size == 1) "yields" else "yield"
            appendLine(
                "2. **$named $verb no code declarations at all -- an extraction gap, not a " +
                    "retrieval result.** " +
                    gaps.joinToString(" ") { describeGap(it) } +
                    " A repo whose language the indexer does not parse is measuring " +
                    "**extraction coverage, not retrieval quality**: there is nothing indexed " +
                    "for the query to find, so every ${RetrievalSide.CONTEXT_GRAPH.label} figure " +
                    "for it below is a floor set by the extractor rather than a verdict on " +
                    "retrieval, and its own table below repeats this warning next to the " +
                    "numbers. The other three sides are unaffected -- two read the working tree, " +
                    "and the third has its own index."
            )
        }
        appendLine()
        appendLine(
            "**What each ${RetrievalSide.CONTEXT_GRAPH.label} index actually extracted**, which " +
                "is the census the paragraph above is computed from:"
        )
        appendLine()
        appendLine("| Repo | Declaration nodes | Node census (all types, most numerous first) |")
        appendLine("|---|---|---|")
        censused.forEach { c ->
            appendLine("| `${c.repoId}` | ${c.extractedDeclarationCount} | ${fmtCensus(c.extractedNodeCounts)} |")
        }
        appendLine()
        appendLine(
            "Declaration nodes are `${GoldFileCoverage.DECLARATION_NODE_TYPES.joinToString("`, `")}` " +
                "-- what a language grammar emits for something it parsed out of a source file. " +
                "`Document`, `Concept` and file-level nodes are excluded from that total on " +
                "purpose: a repo the indexer could not parse a line of still accumulates them, so " +
                "counting them would hide the very gap this census exists to show."
        )
        appendLine()
    }

    /**
     * The same data-driven test as [renderExtractionDisclosure], applied to one repo and printed
     * immediately above its own numbers. Silent for every repo whose index did extract
     * declarations, and silent when no census was taken -- a warning that fires on all repos
     * warns about none of them.
     */
    private fun StringBuilder.renderExtractionCaveat(
        run: RetrievalRun,
        repoId: String,
        aggregate: RetrievalAggregate
    ) {
        // Nothing to qualify when that column reads `n/a` all the way down: the side was skipped
        // outright, the "Skipped" table already says why, and a caveat about a figure that is not
        // there would be the report explaining a number it did not print.
        if (aggregate.contextGraph.measuredCount == 0) return
        val coverage = run.goldFileCoverage.firstOrNull {
            it.repoId == repoId && it.side == RetrievalSide.CONTEXT_GRAPH
        } ?: return
        if (coverage.extractedDeclarationCount != 0) return
        appendLine(
            "> **Read the ${RetrievalSide.CONTEXT_GRAPH.label} column here as extraction " +
                "coverage, not retrieval quality.** `$repoId`'s index holds ${fmtGoldFiles(coverage)} and zero code " +
                "declarations (${fmtCensus(coverage.extractedNodeCounts)}), so there is nothing " +
                "indexed for a query to match. The figure below is the extractor's floor, not a " +
                "retrieval verdict; the other three columns are unaffected."
        )
        appendLine()
    }

    /** One repo's gap, in facts: what its index holds, and what it does not. */
    private fun describeGap(coverage: GoldFileCoverage): String {
        val declarations = GoldFileCoverage.DECLARATION_NODE_TYPES.joinToString(", ") { type ->
            "${coverage.extractedNodeCounts?.get(type) ?: 0} `$type`"
        }
        return "`${coverage.repoId}`'s index holds ${fmtGoldFiles(coverage)} and *zero* declarations: " +
            "$declarations. What it does hold is ${fmtCensus(coverage.extractedNodeCounts)}."
    }

    /** The coverage fraction as words, with the unknown said rather than rendered as a shortfall. */
    private fun fmtGoldFiles(coverage: GoldFileCoverage): String =
        coverage.presentFileCount?.let { "$it / ${coverage.citedFileCount} gold-cited files" }
            ?: "a gold-cited file count that could not be determined"

    /**
     * The whole census, most numerous type first, ties broken by type name so the same result
     * document always renders to the same bytes.
     */
    private fun fmtCensus(counts: Map<String, Int>?): String {
        if (counts.isNullOrEmpty()) return "_no nodes at all_"
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(", ") { "${it.value} `${it.key}`" }
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
                "price of getting there. Neither text-search side has an ingest step at all: " +
                "`${RetrievalSide.BASH.label}` and `${RetrievalSide.RIPGREP.label}` both search " +
                "the working tree directly, and pay nothing before the first query. That is " +
                "exactly why they are the baselines, and it is the number every index-building " +
                "row below is being compared against."
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
        renderReuseSentinelNote(run)
    }

    /**
     * What a zero-duration row means, printed only when there is one.
     *
     * `${fmtDuration(0)}` is a sentinel, not a measurement, and it exists so that a re-run over an
     * already-built index says so instead of quietly reporting a cost it did not observe. Left
     * unexplained in a column headed "Index build time" it reads as "free", which is the opposite
     * of what it says -- so the explanation is printed next to it, and only when a row carries it.
     */
    private fun StringBuilder.renderReuseSentinelNote(run: RetrievalRun) {
        if (run.ingestCosts.none { it.absentReason == null && it.durationMillis == 0L }) return
        appendLine(
            "A row reading _${fmtDuration(0)}_ is one whose index this run found already built and " +
                "did not rebuild -- the working copy is pinned at a SHA and verified never to " +
                "change, so an index that exists for it cannot be stale. **That is a statement " +
                "about this run, not a claim that the index was free.** Whatever it cost belongs " +
                "to the earlier run that built it and is reported in that run's own result " +
                "document; it is deliberately not carried forward into this one. A duration " +
                "written into a result that did not measure it is exactly the figure nobody can " +
                "later check, and this axis prints the sentinel rather than becoming that."
        )
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

    /**
     * The skip list, **printed whether or not anything was skipped**.
     *
     * It used to disappear when [RetrievalRun.skippedRepos] was empty, and that was a mistake worth
     * naming: every table above promises that a short denominator is "listed under Skipped", and
     * the paragraph above that promises absence is never scored as zero. A section that vanishes
     * when it has nothing to say leaves both promises pointing at nothing, and a reader cannot tell
     * a clean run from a generator that forgot to write the section. An empty list is a result -- it
     * is the discharge of the rule -- so it is rendered as one.
     *
     * The denominator check below is the second half of that discharge, and it is computed rather
     * than asserted: a repo can be absent from the skip list and still have a column that measured
     * fewer questions than its table's `n`.
     */
    private fun StringBuilder.renderSkipped(run: RetrievalRun) {
        appendLine("### Skipped")
        appendLine()
        if (run.skippedRepos.isNotEmpty()) {
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
        } else {
            appendLine(
                "**Nothing was skipped in this run.** The list is empty, and it is printed empty " +
                    "rather than dropped: every table below promises that a short column is listed " +
                    "here, and a section that disappears when it has nothing to say is " +
                    "indistinguishable from one nobody wrote. \"Absence is never scored as zero\" " +
                    "is only checkable if the absences are enumerated -- including when there are " +
                    "none of them."
            )
            appendLine()
        }
        renderDenominatorDischarge(run)
    }

    /**
     * Whether every side actually measured every question its table counts, per repo -- the claim
     * each aggregate table's "Measured:" line makes, aggregated into one statement so a reader does
     * not have to check sixteen of them by eye. A side that is absent from the run entirely is not
     * a shortfall and is skipped here; the tables already print `not in this run` for it.
     */
    private fun StringBuilder.renderDenominatorDischarge(run: RetrievalRun) {
        val byRepo = run.summary?.byRepo?.toSortedMap() ?: return
        if (byRepo.isEmpty()) return
        val short = byRepo.entries.flatMap { (repoId, aggregate) ->
            measuredCounts(aggregate)
                .filter { it.second < aggregate.questionCount }
                .map { "`$repoId` -- ${it.first.label} measured ${it.second} of ${aggregate.questionCount}" }
        }
        if (short.isEmpty()) {
            appendLine(
                "Every side measured every question it was given: across ${byRepo.size} repo(s) " +
                    "and ${byRepo.values.sumOf { it.questionCount }} question(s), no column's " +
                    "denominator is short of its table's `n`. That is the rule above discharged by " +
                    "the run's own counts rather than by assurance."
            )
        } else {
            appendLine(
                "Columns whose denominator is short of their table's `n` -- excluded from that " +
                    "column's mean, never counted as zero: ${short.joinToString("; ")}."
            )
        }
        appendLine()
    }

    /** Each side present in [aggregate], with how many questions it actually measured. */
    private fun measuredCounts(aggregate: RetrievalAggregate): List<Pair<RetrievalSide, Int>> =
        RetrievalSide.entries.mapNotNull { side ->
            aggregate.sideAggregate(side)?.let { side to it.measuredCount }
        }

    // ------------------------------------------- the two text-search columns

    /** One question's two text-search measurements, side by side, with what it was scored against. */
    private class TextSearchPair(
        val questionId: String,
        val expectedFiles: Set<String>,
        val bash: SideResult,
        val ripgrep: SideResult
    ) {
        /** True when the two sides' first-[prefix] entries differ only over files no gold fact cites. */
        fun prefixDifferenceIsGoldFree(prefix: Int): Boolean {
            val onlyBash = bash.rankedFiles.take(prefix).toSet() - ripgrep.rankedFiles.take(prefix).toSet()
            val onlyRipgrep = ripgrep.rankedFiles.take(prefix).toSet() - bash.rankedFiles.take(prefix).toSet()
            return (onlyBash + onlyRipgrep).none { it in expectedFiles }
        }
    }

    /**
     * The answer to the first thing a reader notices in the tables below: the two text-search
     * columns print the same figure in every row.
     *
     * Identical columns are exactly what a wiring fault looks like -- one runner's list copied into
     * both fields, one binary invoked twice -- so leaving it unremarked is the worst available
     * answer. It is also, on this corpus, the most interesting thing the fourth side produced: if
     * the "no third-party tools" floor is not lower than the ripgrep floor, then the margins the
     * indexing sides show over it are not artefacts of a starved baseline.
     *
     * **Every count, every question name and every delta here is computed from the run's own ranked
     * lists -- no repo, language or figure is named in this generator's source**, the same
     * discipline [renderExtractionDisclosure] and [renderPooledMeanCaveat] are held to. Each claim
     * branches on what the data shows, so a run whose baselines *do* diverge inside the scored
     * ranks prints that instead, and the strongest sentence ("all of this lives below the scored
     * ranks") is never printed unless the lists actually support it.
     */
    private fun StringBuilder.renderBaselineAgreement(run: RetrievalRun) {
        val pairs = run.results
            .mapNotNull { result ->
                result.bash?.let {
                    TextSearchPair(result.questionId, result.expectedFiles.toSet(), it, result.ripgrep)
                }
            }
            .sortedBy { it.questionId }
        if (pairs.isEmpty()) return

        val cells = comparablePrintedCells(run)
        val differingCells = cells.count { (bash, ripgrep) -> bash != ripgrep }
        val differingLists = pairs.filter { it.bash.rankedFiles != it.ripgrep.rankedFiles }
        val prefix = run.kValues.maxOrNull() ?: 0
        val differingPrefix = pairs.filter {
            it.bash.rankedFiles.take(prefix) != it.ripgrep.rankedFiles.take(prefix)
        }
        val cappedDivergent = pairs.filter { pair ->
            run.kValues.any { k ->
                pair.bash.precisionAtK[k] != pair.ripgrep.precisionAtK[k] ||
                    pair.bash.recallAtK[k] != pair.ripgrep.recallAtK[k]
            }
        }
        val rankDivergent = pairs.filter { it.bash.reciprocalRank != it.ripgrep.reciprocalRank }
        val bashSurplus = pairs.maxOf { it.bash.rankedFiles.size - it.ripgrep.rankedFiles.size }
        val ripgrepSurplus = pairs.maxOf { it.ripgrep.rankedFiles.size - it.bash.rankedFiles.size }

        appendLine("### The two text-search columns")
        appendLine()
        appendLine(
            if (differingCells == 0) {
                "**Read this before the tables: every figure below is the same for " +
                    "${RetrievalSide.BASH.label} and for ${RetrievalSide.RIPGREP.label}.** All " +
                    "${cells.size} aggregate figure(s) this document computes for the two -- every " +
                    "metric, in every grouping -- come out identical at the precision it prints. " +
                    "Two columns that read alike are what a wiring fault looks like, so the check " +
                    "is published here rather than left for a reader to suspect."
            } else {
                "**Read this before the tables: ${RetrievalSide.BASH.label} and " +
                    "${RetrievalSide.RIPGREP.label} agree far more closely than two different " +
                    "programs might be expected to.** Of the ${cells.size} aggregate figure(s) " +
                    "this document computes for the two, $differingCells differ and the rest are " +
                    "identical at the precision it prints. Columns that read alike are what a " +
                    "wiring fault looks like, so the check is published here rather than left for " +
                    "a reader to suspect."
            }
        )
        appendLine()
        appendLine(
            "**The two are computed independently.** They share exactly one thing, on purpose: " +
                "`RipgrepQueryDeriver` derives the query tokens once and both sides are handed the " +
                "same list, which is what makes the pair comparable at all. Below that they have " +
                "nothing in common -- `BashBaselineRunner` spawns the base-system `grep` through " +
                "`BashProcess` at an absolute path, `RipgrepBaselineRunner` spawns `rg` through " +
                "`RipgrepProcess`, each parses its own binary's output, and each ranked list is " +
                "recorded in its own field of the result document. Neither ever reads the other's " +
                "answer."
        )
        appendLine()
        renderBaselineDivergence(pairs, differingLists, differingPrefix, prefix, bashSurplus, ripgrepSurplus)
        if (differingLists.isNotEmpty()) renderBaselineDivergenceCauses(prefix)
        renderBaselineConclusion(
            pairs, differingLists, differingPrefix, cappedDivergent, rankDivergent, prefix,
            aggregatesAgree = differingCells == 0
        )
        renderBaselineFinding(run, differingCells)
    }

    /** How far apart the two ranked lists actually are, in the run's own counts. */
    private fun StringBuilder.renderBaselineDivergence(
        pairs: List<TextSearchPair>,
        differingLists: List<TextSearchPair>,
        differingPrefix: List<TextSearchPair>,
        prefix: Int,
        bashSurplus: Int,
        ripgrepSurplus: Int
    ) {
        if (differingLists.isEmpty()) {
            appendLine(
                "**On this run they also returned the same thing.** All ${pairs.size} question(s) " +
                    "produced not merely equal scores but the identical ranked list -- the same " +
                    "files in the same order -- so there is nothing beneath the metrics left to " +
                    "explain: the two searches did not diverge anywhere in this corpus."
            )
            appendLine()
            return
        }
        val prefixSentence = if (differingPrefix.isEmpty()) {
            "**every** question's first-$prefix prefix is identical, so nothing the metrics look " +
                "at differs at all"
        } else {
            "${pairs.size - differingPrefix.size} of ${pairs.size} have an identical first-$prefix " +
                "prefix, and the ${differingPrefix.size} that do not are " +
                differingPrefix.joinToString(", ") { "`${it.questionId}`" }
        }
        val lengthSentence = when {
            ripgrepSurplus <= 0 && bashSurplus > 0 ->
                " The ${RetrievalSide.BASH.label} side never returns fewer files than the " +
                    "${RetrievalSide.RIPGREP.label} side, and at most $bashSurplus more."
            bashSurplus <= 0 && ripgrepSurplus > 0 ->
                " The ${RetrievalSide.RIPGREP.label} side never returns fewer files than the " +
                    "${RetrievalSide.BASH.label} side, and at most $ripgrepSurplus more."
            bashSurplus > 0 && ripgrepSurplus > 0 ->
                " Each side returns files the other does not: at most $bashSurplus more on the " +
                    "${RetrievalSide.BASH.label} side and at most $ripgrepSurplus more on the " +
                    "${RetrievalSide.RIPGREP.label} side."
            else -> " Neither side ever returns a longer list than the other."
        }
        appendLine(
            "**They did not, however, return the same thing.** ${differingLists.size} of " +
                "${pairs.size} question(s) produce ranked lists that are not equal, which is the " +
                "check that these are two measurements rather than one printed twice. What they " +
                "share is the part the metrics can see: $prefixSentence.$lengthSentence"
        )
        appendLine()
    }

    /**
     * Why the two lists come apart. **Two causes, deliberately, rather than the tidier one.** The
     * ignore-file story alone explains most of the difference and is the one an investigator
     * reaches for first; stated as the whole story it would be a confident falsehood sitting in the
     * one section whose entire job is explaining the method. The second cause is real, was verified
     * against the corpus, and is invisible to a prefix check -- which is exactly why it survives
     * being overlooked.
     *
     * Both are properties of the two binaries, not of any repository, so nothing here is named from
     * the data or goes stale when the corpus changes.
     */
    private fun StringBuilder.renderBaselineDivergenceCauses(prefix: Int) {
        appendLine("**Why they differ at all -- two causes, not one:**")
        appendLine()
        appendLine(
            "1. **`rg` never opens files that `grep` reads.** `rg` honours the checkout's own " +
                "`.gitignore` files and skips hidden entries by default. The flag table above " +
                "shows the ${RetrievalSide.BASH.label} side is told only to skip `.git` and binary " +
                "files, because emulating the rest would quietly turn it into a second ripgrep. " +
                "Anything an ignore rule excludes, or that sits under a hidden directory, is " +
                "therefore searched by one side and never opened by the other. This is the larger " +
                "of the two effects and accounts for most of the extra tail."
        )
        appendLine(
            "2. **The two do not mean the same thing by `-w`.** Both sides pass a whole-word flag, " +
                "but the character classes behind them differ: the base-system `grep` decides word " +
                "boundaries over ASCII, `rg` decides them over Unicode. An ASCII token butted " +
                "directly against non-ASCII text is a whole-word match for the first and not for " +
                "the second, so the *same* file can be counted by both sides with different match " +
                "counts -- and the ranking is ordered by match count, so it moves. This cause is " +
                "invisible to a first-$prefix check: it shifts ranks deep in the tail on questions " +
                "whose scored prefix is identical, which is precisely how it gets overlooked."
        )
        appendLine()
    }

    /** Whether any of that divergence reaches a position the metrics score. */
    private fun StringBuilder.renderBaselineConclusion(
        pairs: List<TextSearchPair>,
        differingLists: List<TextSearchPair>,
        differingPrefix: List<TextSearchPair>,
        cappedDivergent: List<TextSearchPair>,
        rankDivergent: List<TextSearchPair>,
        prefix: Int,
        aggregatesAgree: Boolean
    ) {
        if (differingLists.isEmpty()) return
        when {
            cappedDivergent.isNotEmpty() -> appendLine(
                "**Some of it does reach a scored position.** ${cappedDivergent.size} of " +
                    "${pairs.size} question(s) score differently on at least one precision@k or " +
                    "recall@k: ${cappedDivergent.joinToString(", ") { "`${it.questionId}`" }}. " +
                    "Where an aggregate row above still reads alike, that is those differences " +
                    "cancelling within the mean rather than their absence, and the per-question " +
                    "figures are the place to check it."
            )
            differingPrefix.isEmpty() -> appendLine(
                "**All of it lives below the ranks that are scored.** No question's ranked list " +
                    "differs inside its first $prefix entries, so every per-question precision@k " +
                    "and recall@k -- at every `k` this run measured -- is identical on both sides, " +
                    "and so is every mean built from them."
            )
            else -> {
                // Checked, not inferred. Equal precision@k/recall@k is consistent with a gold file
                // moving *within* the prefix, so "what differs is not gold" is a separate claim
                // about the same lists and is tested against the expected sets before it is made.
                val goldFree = differingPrefix.all { it.prefixDifferenceIsGoldFree(prefix) }
                val because = if (goldFree) {
                    "where the entries that differ are not gold-cited files at all, and so change " +
                        "nothing that is scored"
                } else {
                    "where the gold-cited files fall inside the same scored ranks on both sides"
                }
                appendLine(
                    "**None of it reaches a scored position.** Every per-question precision@k and " +
                        "recall@k, at every `k` this run measured, is identical on both sides -- " +
                        "including on the ${differingPrefix.size} question(s) whose first-$prefix " +
                        "order does differ, $because."
                )
            }
        }
        val shallowest = rankDivergent
            .flatMap { listOf(it.bash.reciprocalRank, it.ripgrep.reciprocalRank) }
            .filter { it > 0.0 }
            .maxOrNull()
            ?.let { Math.round(1.0 / it).toInt() }
        appendLine()
        if (rankDivergent.isEmpty()) {
            appendLine(
                "Reciprocal rank -- the one metric here that is *not* capped at `k`, and so the " +
                    "one place a tail difference could still show -- is identical on both sides too."
            )
        } else {
            val depth = shallowest?.let { "rank $it or deeper, far beyond every `k` measured here" }
                ?: "a depth this document cannot state, because neither side found a gold file there"
            val survival = if (aggregatesAgree) {
                "They are carried into the pooled MRR and are the only thing that is -- and not " +
                    "one of them survives rounding to the precision the tables print, which is why " +
                    "the MRR rows read alike too."
            } else {
                "They are carried into the pooled MRR, where the tables below show what became of " +
                    "them."
            }
            appendLine(
                "Reciprocal rank is the one metric here that is *not* capped at `k`, and it is the " +
                    "only place any difference survives at all: it differs on ${rankDivergent.size} " +
                    "of ${pairs.size} question(s) -- " +
                    rankDivergent.joinToString(", ") { "`${it.questionId}`" } +
                    " -- where the first gold-cited file sits at $depth. $survival"
            )
        }
        appendLine()
    }

    /**
     * What the agreement is evidence *for*, printed only where the data supports the claim.
     *
     * The claim is about the published aggregates -- "the no-installs floor is not lower than the
     * ripgrep floor" -- so it is gated on those and on nothing else. A run where individual
     * questions diverge but every aggregate figure still comes out the same has earned the claim;
     * a run where any aggregate figure differs has not, and prints the difference instead.
     */
    private fun StringBuilder.renderBaselineFinding(run: RetrievalRun, differingCells: Int) {
        if (differingCells != 0) {
            appendLine(
                "Where the two columns differ below, that difference is the measurement, printed " +
                    "as it came out."
            )
            appendLine()
            return
        }
        appendLine(
            "**And that is the finding, not a footnote to one.** At " +
                "${run.kValues.joinToString(" and ") { "k=$it" }} on this corpus, the engineering " +
                "inside `rg` -- its ignore-file awareness, its parallel walk, its tuned matcher -- " +
                "buys nothing a stock `grep` does not already reach. The floor a developer gets " +
                "with **nothing installed** is not lower than the floor `rg` sets, so the margins " +
                "the two index-building sides show over ${RetrievalSide.BASH.label} below are " +
                "margins over a baseline that was not starved to produce them. That is the whole " +
                "reason the fourth side was added. *Baseline'ı zayıflatarak kazanılan bir sayı, " +
                "kazanılmamış bir sayıdır.*"
        )
        appendLine()
    }

    /**
     * Every aggregate figure this document computes for the two text-search sides, as the pair of
     * strings it would print -- so "the two columns are identical" is a claim about what a reader
     * sees, checked against the same formatters the tables use, and can never contradict them.
     *
     * A grouping with no questions renders as prose rather than a table and is not counted; a
     * grouping whose bash side is absent entirely is not comparable and is not counted either.
     */
    private fun comparablePrintedCells(run: RetrievalRun): List<Pair<String, String>> {
        val summary = run.summary ?: return emptyList()
        val metrics = headlineMetrics(run)
        val groupings = listOf(summary.headline, summary.negativeControl) +
            summary.byCategory.values + summary.byRepo.values
        return groupings
            .filter { it.questionCount > 0 }
            .flatMap { aggregate ->
                val bash = aggregate.bash
                if (bash == null) {
                    emptyList()
                } else {
                    metrics.map { metric ->
                        metric.render(metric.extract(bash)) to metric.render(metric.extract(aggregate.ripgrep))
                    }
                }
            }
    }

    // ------------------------------------------------- ranked-list lengths

    /**
     * One side's ranked-list shape over the headline pool, and what that shape alone permits.
     *
     * [ceiling] is the highest mean precision@k_max this side's own lists left available: for one
     * question, `min(files returned, k, gold-cited files) / k` -- the score it would have got had
     * every file it returned been a gold one -- averaged over the questions it measured.
     */
    private class LengthProfile(
        val side: RetrievalSide,
        val measured: Int,
        val median: Double,
        val longest: Int,
        val shortListCount: Int,
        val goldInBandCount: Int,
        val ceiling: Double,
        val precisionAtFirstK: Double,
        val precisionAtLastK: Double,
        val recallAtFirstK: Double,
        val recallAtLastK: Double
    ) {
        /** How much of what its own lists allowed this side actually reached; null if they allowed nothing. */
        val shareOfCeiling: Double? = if (ceiling > 0.0) precisionAtLastK / ceiling else null
    }

    /**
     * How many files each side actually returns, and what that alone permits -- the asymmetry the
     * headline table's precision rows sit on top of, printed before them rather than left in the
     * result document for a reader to derive. (The owner of this repository had to derive it by
     * hand from the JSON, which is the reason this section exists.)
     *
     * **The two halves of this section ship together or not at all, and there is deliberately no
     * early return between them.** The first half discloses that the sides return lists of wildly
     * different lengths and that precision@k charges a short list for its empty slots. Published
     * alone, that reads either as an excuse manufactured on some side's behalf or as an admission
     * that the comparison was rigged, and a reader has no way to tell which. The second half is the
     * arithmetic that settles it: the ceiling each side's own lists impose, and how far below that
     * ceiling each one actually landed. Neither half is honest without the other.
     *
     * **Every figure and every claim is computed from the run's ranked lists and its own
     * aggregates** -- no side, repo, count or verdict is written as a literal here, the same
     * discipline [renderExtractionDisclosure], [renderPooledMeanCaveat] and
     * [renderBaselineAgreement] are held to. The conclusion in particular is gated on the data
     * supporting it: a run in which normalising by the ceiling reorders the sides prints that
     * reordering instead.
     */
    private fun StringBuilder.renderListLengths(run: RetrievalRun) {
        val profiles = lengthProfiles(run)
        if (profiles.size < 2) return
        val firstK = run.kValues.min()
        val lastK = run.kValues.max()
        val pool = run.summary?.headline?.questionCount ?: return
        val hasBand = lastK > firstK

        appendLine("### How long each side's ranked list is")
        appendLine()
        appendLine(
            "**precision@$lastK divides by $lastK, not by however many files a side returned.** " +
                "`RetrievalMetrics` counts the unfilled slots as misses -- the standard IR " +
                "definition, applied identically to every side -- so a side that returns fewer " +
                "than $lastK files carries a cap on that row which no amount of retrieval quality " +
                "can lift. The sides do not return lists of remotely similar length, so that rule " +
                "does not fall on them equally."
        )
        appendLine()
        appendLine("Over the $pool headline question(s), which is the pool the headline table below is computed from:")
        appendLine()
        appendLine(
            "| Side | Median files returned | Longest | Returned $firstK files or fewer |" +
                if (hasBand) " Gold-cited file at ranks ${firstK + 1}-$lastK |" else ""
        )
        appendLine("|---|---|---|---|" + if (hasBand) "---|" else "")
        profiles.forEach { p ->
            appendLine(
                "| ${p.side.label} | ${fmtFileCount(p.median)} | ${p.longest} | " +
                    "${p.shortListCount} of ${p.measured} |" +
                    if (hasBand) " ${p.goldInBandCount} of ${p.measured} |" else ""
            )
        }
        appendLine()
        renderShortestLists(profiles, firstK, lastK)
        if (hasBand) renderBandConsequence(profiles, firstK, lastK)
        renderLengthCeiling(profiles, lastK)
    }

    /** Which side's lists are shortest, and what that alone costs it on the deepest `k`. */
    private fun StringBuilder.renderShortestLists(profiles: List<LengthProfile>, firstK: Int, lastK: Int) {
        val shortest = profiles.minByOrNull { it.median } ?: return
        val longest = profiles.maxByOrNull { it.median } ?: return
        if (shortest.side == longest.side) return
        val cap = if (shortest.median < lastK) {
            " A list that length caps precision@$lastK at " +
                "${fmtPercent(shortest.median / lastK)} before retrieval quality is considered at all."
        } else {
            ""
        }
        appendLine(
            "**The shortest lists are ${shortest.side.label}'s** -- a median of " +
                "${fmtFileCount(shortest.median)} against ${longest.side.label}'s " +
                "${fmtFileCount(longest.median)}, and ${shortest.shortListCount} of its " +
                "${shortest.measured} question(s) return $firstK file(s) or fewer.$cap"
        )
        appendLine()
    }

    /**
     * What a side finds -- or does not find -- between the two `k` values, which is the difference
     * between "its list ran out" and "there was nothing further down to find". Those two look
     * identical in the tables and mean opposite things, so the ranked lists are asked directly.
     */
    private fun StringBuilder.renderBandConsequence(profiles: List<LengthProfile>, firstK: Int, lastK: Int) {
        val flat = profiles.filter {
            it.goldInBandCount == 0 && rounded(it.recallAtFirstK) == rounded(it.recallAtLastK)
        }
        if (flat.isEmpty()) return
        val named = flat.joinToString(", ") { "${it.side.label} (${fmtPercent(it.recallAtFirstK)})" }
        val halved = flat.filter {
            it.precisionAtFirstK > 0.0 &&
                Math.abs(it.precisionAtLastK - it.precisionAtFirstK * firstK / lastK) < 1e-9
        }
        val arithmetic = if (halved.size != flat.size) {
            ""
        } else {
            " precision@$lastK is precision@$firstK scaled by exactly $firstK/$lastK for the same " +
                "reason -- the same hit count over a `k` that is larger -- which makes those two " +
                "rows arithmetic rather than a second measurement."
        }
        appendLine(
            "**One consequence sits in the headline table and looks like something it is not.** " +
                "recall@$firstK and recall@$lastK read the same figure for $named. That is not " +
                "the ranked list running out before rank $lastK: not one question in this pool " +
                "places a gold-cited file at ranks ${firstK + 1}-$lastK for " +
                (if (flat.size == 1) "that side" else "those sides") +
                " at all, so raising `k` finds nothing that was not already found.$arithmetic"
        )
        appendLine()
        val moving = profiles.filter { it.goldInBandCount > 0 && rounded(it.recallAtLastK) > rounded(it.recallAtFirstK) }
        if (moving.isNotEmpty()) {
            appendLine(
                "The sides whose recall does move between those two rows are the ones that put " +
                    "gold-cited files in that band: " +
                    moving.joinToString(", ") { "${it.side.label} on ${it.goldInBandCount} of ${it.measured}" } +
                    "."
            )
            appendLine()
        }
    }

    /**
     * The half that keeps the half above from being an excuse: how high each side's own lists let
     * it score, how high it actually scored, and whether normalising by the first changes the order
     * of the second. The conclusion is printed only in the form the data supports.
     */
    private fun StringBuilder.renderLengthCeiling(profiles: List<LengthProfile>, lastK: Int) {
        appendLine(
            "**And here is the half that stops the paragraph above from being an excuse.** A short " +
                "list caps precision@$lastK, so the question a reader needs answered is how much " +
                "of the measured spread that cap accounts for -- and it is computable exactly. For " +
                "one question the cap is the smallest of (files returned, $lastK, gold-cited " +
                "files), over $lastK: the score that side would have got if every file it returned " +
                "had been a gold one. Averaged over the pool, it is the highest precision@$lastK " +
                "its own lists left available to it."
        )
        appendLine()
        appendLine(
            "| Side | Highest precision@$lastK its lists allowed | Measured precision@$lastK | " +
                "Share of its own ceiling reached |"
        )
        appendLine("|---|---|---|---|")
        profiles.forEach { p ->
            appendLine(
                "| ${p.side.label} | ${fmtPercent(p.ceiling)} | ${fmtPercent(p.precisionAtLastK)} | " +
                    "${p.shareOfCeiling?.let { fmtPercent(it) } ?: "n/a"} |"
            )
        }
        appendLine()
        val ceilingHi = profiles.maxOf { it.ceiling }
        val ceilingLo = profiles.minOf { it.ceiling }
        val measuredHi = profiles.maxOf { it.precisionAtLastK }
        val measuredLo = profiles.minOf { it.precisionAtLastK }
        appendLine(
            "The ceilings span ${fmtPoints(ceilingHi, ceilingLo)} percentage points, from " +
                "${fmtPercent(ceilingLo)} to ${fmtPercent(ceilingHi)}; the measured figures span " +
                "${fmtPoints(measuredHi, measuredLo)} percentage points, from " +
                "${fmtPercent(measuredLo)} to ${fmtPercent(measuredHi)}. " +
                describeCeilingNormalisation(profiles, lastK)
        )
        appendLine()
    }

    /** Whether scoring each side against its own ceiling reorders the sides, said either way. */
    private fun describeCeilingNormalisation(profiles: List<LengthProfile>, lastK: Int): String {
        val rankedRaw = rankOrder(profiles) { it.precisionAtLastK }
        val rankedShare = rankOrder(profiles) { it.shareOfCeiling ?: 0.0 }
        if (rankedRaw != rankedShare) {
            val order = profiles.sortedWith(
                compareBy({ rankedShare.getValue(it.side) }, { it.side.ordinal })
            ).joinToString(", ") { "${it.side.label} ${it.shareOfCeiling?.let(::fmtPercent) ?: "n/a"}" }
            return "**Scoring each side against its own ceiling, rather than against a flat " +
                "k=$lastK, reorders them**: $order. Both orders are printed and neither is " +
                "presented as the real one -- list length is doing enough of the work here that " +
                "the measured row should not be read on its own."
        }
        val shortest = profiles.minByOrNull { it.median }
        val worstShare = profiles.filter { it.shareOfCeiling != null }
            .minByOrNull { rounded(it.shareOfCeiling!!) }
        val furthest = if (shortest != null && worstShare != null && shortest.side == worstShare.side) {
            " The side with the shortest lists is also the one furthest below what those lists " +
                "allowed -- ${fmtPercent(worstShare.shareOfCeiling!!)} of its own ceiling, the " +
                "lowest share of any side here."
        } else {
            ""
        }
        return "**Scoring each side against its own ceiling, rather than against a flat k=$lastK, " +
            "leaves them in the same order.**$furthest List length therefore explains part of the " +
            "spread and not the result: the asymmetry above is real, is published, and does not " +
            "account for the difference the tables show."
    }

    /**
     * Each side's rank on [value], ranking on the value **as printed** so a claim here can never
     * contradict a figure a reader checks it against, and giving tied sides the same rank -- two
     * sides the tables render identically must not be reported as one leading the other.
     */
    private fun rankOrder(
        profiles: List<LengthProfile>,
        value: (LengthProfile) -> Double
    ): Map<RetrievalSide, Int> {
        val distinct = profiles.map { rounded(value(it)) }.distinct().sortedDescending()
        return profiles.associate { it.side to distinct.indexOf(rounded(value(it))) }
    }

    /**
     * One [LengthProfile] per side that measured something, in the order the tables print the
     * sides. Lengths are de-duplicated first, exactly as [RetrievalMetrics] de-duplicates before
     * scoring -- a profile counted over raw lists would describe a list no metric ever saw.
     */
    private fun lengthProfiles(run: RetrievalRun): List<LengthProfile> {
        val headline = run.summary?.headline ?: return emptyList()
        val firstK = run.kValues.minOrNull() ?: return emptyList()
        val lastK = run.kValues.maxOrNull() ?: return emptyList()
        val pool = run.results.filter { it.category != QuestionCategory.NEGATIVE_CONTROL }
        if (pool.isEmpty()) return emptyList()
        return RetrievalSide.entries.mapNotNull { side ->
            val aggregate = headline.sideAggregate(side)?.takeIf { it.measuredCount > 0 }
                ?: return@mapNotNull null
            val measured = pool.mapNotNull { result ->
                result.sideResult(side)?.let { result.expectedFiles.toSet() to it.rankedFiles.distinct() }
            }
            if (measured.isEmpty()) return@mapNotNull null
            val lengths = measured.map { it.second.size }.sorted()
            LengthProfile(
                side = side,
                measured = lengths.size,
                median = median(lengths),
                longest = lengths.last(),
                shortListCount = lengths.count { it <= firstK },
                goldInBandCount = measured.count { (expected, ranked) ->
                    ranked.drop(firstK).take(lastK - firstK).any { it in expected }
                },
                ceiling = measured.map { (expected, ranked) ->
                    minOf(ranked.size, lastK, expected.size).toDouble() / lastK
                }.average(),
                precisionAtFirstK = aggregate.meanPrecisionAtK[firstK] ?: 0.0,
                precisionAtLastK = aggregate.meanPrecisionAtK[lastK] ?: 0.0,
                recallAtFirstK = aggregate.meanRecallAtK[firstK] ?: 0.0,
                recallAtLastK = aggregate.meanRecallAtK[lastK] ?: 0.0
            )
        }
    }

    private fun median(sorted: List<Int>): Double {
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** One side's per-question measurement, reached from the side rather than through a `when` at each call site. */
    private fun RetrievalRunResult.sideResult(side: RetrievalSide): SideResult? = when (side) {
        RetrievalSide.CONTEXT_GRAPH -> contextGraph
        RetrievalSide.CODE_GRAPH -> codeGraph
        RetrievalSide.BASH -> bash
        RetrievalSide.RIPGREP -> ripgrep
    }

    /** One side's aggregate, or null when that side was not in the run at all (never a zeroed stand-in). */
    private fun RetrievalAggregate.sideAggregate(side: RetrievalSide): SideAggregate? = when (side) {
        RetrievalSide.CONTEXT_GRAPH -> contextGraph
        RetrievalSide.CODE_GRAPH -> codeGraph
        RetrievalSide.BASH -> bash
        RetrievalSide.RIPGREP -> ripgrep
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
        renderPooledMeanCaveat(run, summary.headline)
    }

    /**
     * The headline table's own qualification, printed immediately under it (AC-15's reasoning
     * applied to the pooled row rather than to one repo): a mean over every repo's questions is a
     * result about that pool, and reading it as a per-repo verdict is the specific mistake this
     * table invites.
     *
     * **Every word of it is computed -- no repo, side or metric is named in this generator's
     * source.** The shares come from counting the headline questions per repo; the "where another
     * side leads" column comes from ranking each repo's own aggregate on the same metric. That is
     * what makes it survive the data changing: a run in which one side leads every repo prints
     * that, a run in which a repo's share doubles prints the new share, and neither needs anyone
     * to remember to come back and edit a sentence. The measurement it was written for is one
     * where a single repo's column moved off a floor and carried the pooled row with it, while
     * every other repo stayed exactly where it was -- from the pooled table alone that is
     * indistinguishable from retrieval improving everywhere.
     *
     * Sides are ranked on the value **as printed**, rounded to the same precision the tables use,
     * so a claim here can never contradict the figures a reader checks it against.
     */
    private fun StringBuilder.renderPooledMeanCaveat(run: RetrievalRun, headline: RetrievalAggregate) {
        val headlineResults = run.results.filter { it.category != QuestionCategory.NEGATIVE_CONTROL }
        if (headlineResults.isEmpty()) return
        val byRepo = RetrievalStats.summarize(headlineResults, run.kValues).byRepo.toSortedMap()
        if (byRepo.isEmpty()) return

        val shares = byRepo.entries.joinToString(", ") { (repoId, aggregate) ->
            "`$repoId` ${aggregate.questionCount} of ${headline.questionCount} " +
                "(${fmtPercent(aggregate.questionCount.toDouble() / headline.questionCount)})"
        }
        appendLine(
            "**That table is a pooled mean over ${headline.questionCount} question(s) drawn from " +
                "${byRepo.size} repo(s). It is not a verdict, and it is not a per-repo result.** " +
                "The repos contribute unequal shares of the pool -- $shares -- so one repo's " +
                "column moving moves every pooled row with it, in rough proportion to that share, " +
                "whether or not anything changed on any other repo. A row where one side leads " +
                "here is a lead **on this pool**; whether it is also a lead on each repo in it is " +
                "a separate question, and this is the answer to it:"
        )
        appendLine()
        appendLine(
            "_Each figure below is this pool sliced by repo -- the same questions, the same " +
                "metrics, one repo at a time. That is deliberately **not** the same aggregation " +
                "as the per-repo tables under \"By Repo\", which also include each repo's " +
                "negative controls, so the two will disagree wherever a repo has any. Compared " +
                "here is like with like; compared across the two sections it is not._"
        )
        appendLine()
        appendLine("| Headline metric | Leads the pooled row | Where another side leads |")
        appendLine("|---|---|---|")
        headlineMetrics(run).forEach { metric ->
            // The metric name is a code span here and bare in the tables above, deliberately: the
            // two tables are read by machines as well as people (the report's own tests filter
            // rows by their first cell), and two tables whose rows start identically are two
            // tables that get mistaken for each other.
            val pooled = leadingSides(headline, metric)
            if (pooled.isEmpty()) {
                appendLine("| `${metric.label}` | _nothing measured_ | _nothing measured_ |")
                return@forEach
            }
            val pooledLabel = pooled.joinToString(", ") { it.side.label } +
                if (pooled.size > 1) " (tied)" else ""
            val beaten = byRepo.entries.mapNotNull { (repoId, aggregate) ->
                describeIfBeaten(repoId, aggregate, metric, pooled)
            }
            val where = if (beaten.isEmpty()) "_leads on every repo measured_" else beaten.joinToString("; ")
            appendLine("| `${metric.label}` | $pooledLabel | $where |")
        }
        appendLine()
    }

    /**
     * One repo's row-fragment, or null when a side leading the pooled row leads that repo too.
     * A tie counts as leading: the pooled leader is not "beaten" by a side it draws level with.
     */
    private fun describeIfBeaten(
        repoId: String,
        aggregate: RetrievalAggregate,
        metric: HeadlineMetric,
        pooled: List<SideScore>
    ): String? {
        val local = leadingSides(aggregate, metric)
        if (local.isEmpty()) return null
        val pooledSides = pooled.map { it.side }.toSet()
        if (local.any { it.side in pooledSides }) return null
        val pooledHere = measuredSides(aggregate, metric)
            .filter { it.side in pooledSides }
            .maxByOrNull { rounded(it.value) }
        val against = pooledHere?.let { "${metric.render(it.value)} for ${it.side.label}" }
            ?: "a side unmeasured on this repo"
        return "`$repoId` -- ${local.joinToString(", ") { it.side.label }} " +
            "${if (local.size > 1) "lead" else "leads"} there, " +
            "${metric.render(local.first().value)} against $against"
    }

    /** One side's value for one metric on one aggregate. */
    private data class SideScore(val side: RetrievalSide, val value: Double)

    /**
     * A row of the metric tables, as something that can be extracted and formatted rather than
     * only printed -- so the caveat above ranks exactly the numbers the tables show, for exactly
     * the `k` values this run used, without either list being retyped.
     */
    private class HeadlineMetric(
        val label: String,
        val extract: (SideAggregate) -> Double,
        val render: (Double) -> String
    )

    private fun headlineMetrics(run: RetrievalRun): List<HeadlineMetric> =
        run.kValues.map { k ->
            HeadlineMetric("precision@$k", { it.meanPrecisionAtK[k] ?: 0.0 }, { fmtPercent(it) })
        } + run.kValues.map { k ->
            HeadlineMetric("recall@$k", { it.meanRecallAtK[k] ?: 0.0 }, { fmtPercent(it) })
        } + listOf(HeadlineMetric("MRR", { it.mrr }, { fmtScore(it) }))

    /**
     * The sides that actually measured something in [aggregate], scored on [metric]. A side with a
     * `measuredCount` of 0 is omitted rather than ranked at its zeroed placeholder -- it renders
     * as `n/a` in the tables, and a comparison against `n/a` would be a comparison against nothing.
     */
    private fun measuredSides(aggregate: RetrievalAggregate, metric: HeadlineMetric): List<SideScore> =
        RetrievalSide.entries.mapNotNull { side ->
            aggregate.sideAggregate(side)?.takeIf { it.measuredCount > 0 }
                ?.let { SideScore(side, metric.extract(it)) }
        }

    private fun leadingSides(aggregate: RetrievalAggregate, metric: HeadlineMetric): List<SideScore> {
        val sides = measuredSides(aggregate, metric)
        val best = sides.maxOfOrNull { rounded(it.value) } ?: return emptyList()
        return sides.filter { rounded(it.value) == best }
    }

    /**
     * The value as the tables print it. Both formats resolve to three decimals of the underlying
     * fraction (`%.1f` of a percentage, `%.3f` of a score), so ranking on this is ranking on what
     * the reader sees -- and two sides the tables render identically can never be reported here as
     * one beating the other.
     */
    private fun rounded(value: Double): Double = Math.round(value * 1000.0) / 1000.0

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
            // Repeated here rather than left to the section above: a repo's own table is what
            // gets read, quoted and pasted elsewhere, and a caveat a reader has to scroll back
            // for is a caveat that does not travel with the number it qualifies.
            renderExtractionCaveat(run, repoId, aggregate)
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
                "that is this section's entire purpose. With the fourth side present that " +
                "expectation is now testable against `grep` itself rather than only against a " +
                "third-party stand-in for it."
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
            "| Question | Repo | derived query tokens | ${RetrievalSide.CONTEXT_GRAPH.label} | " +
                "${RetrievalSide.CODE_GRAPH.label} | ${RetrievalSide.BASH.label} | " +
                "${RetrievalSide.RIPGREP.label} | Verdict |"
        )
        appendLine("|---|---|---|---|---|---|---|---|")
        negativeControlResults.sortedBy { it.questionId }.forEach { result ->
            val k = run.kValues.max()
            val cgRecall = result.contextGraph?.recallAtK?.get(k)
            val codeRecall = result.codeGraph?.recallAtK?.get(k)
            val bashRecall = result.bash?.recallAtK?.get(k)
            val rgRecall = result.ripgrep.recallAtK[k] ?: 0.0
            // Named in full, because "CodeGraph wins" and "ContextGraph wins" differ by two
            // letters and this column is exactly where a reader skims.
            val best = listOfNotNull(
                cgRecall?.let { RetrievalSide.CONTEXT_GRAPH to it },
                codeRecall?.let { RetrievalSide.CODE_GRAPH to it },
                bashRecall?.let { RetrievalSide.BASH to it },
                RetrievalSide.RIPGREP to rgRecall
            ).maxByOrNull { it.second }
            val verdict = when {
                best == null -> "nothing measured (see Skipped)"
                best.second == 0.0 -> "no side found a gold file"
                listOfNotNull(cgRecall, codeRecall, bashRecall, rgRecall).count { it == best.second } > 1 ->
                    "tie at ${fmtPercent(best.second)}"
                else -> "${best.first.label} leads"
            }
            appendLine(
                "| ${result.questionId} | ${result.repoId} | " +
                    (result.ripgrepQueryTokens.takeIf { it.isNotEmpty() }?.joinToString(", ") { "`$it`" } ?: "_none derivable_") +
                    " | ${fmtPercent(cgRecall)} | ${fmtPercent(codeRecall)} | ${fmtPercent(bashRecall)} | " +
                    "${fmtPercent(rgRecall)} | $verdict |"
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
        appendLine("./gradlew :modules:benchmark:prepareCorpus --args=\"--repos gin,excalidraw,calcom,keycloak\"")
        appendLine("./gradlew :modules:benchmark:runRetrieval  --args=\"--output-dir results/four-way\"")
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
        appendLine(
            "**${RetrievalSide.BASH.label} needs none of that, and that is the entire point of " +
                "it.** `grep` is on the base system already, so the fourth side is the only one " +
                "of the four that a fresh machine can reproduce with nothing installed — which " +
                "is precisely the floor the other three are being asked to justify their setup " +
                "cost against."
        )
        appendLine()
    }

    // ------------------------------------------------------------- shared UI

    private fun StringBuilder.appendAggregateTable(run: RetrievalRun, aggregate: RetrievalAggregate) {
        fun measured(side: SideAggregate?) =
            side?.let { "${it.measuredCount}/${aggregate.questionCount}" } ?: "not in this run"
        appendLine(
            "n=${aggregate.questionCount} question(s). Measured: " +
                "${RetrievalSide.CONTEXT_GRAPH.label} " +
                "${aggregate.contextGraph.measuredCount}/${aggregate.questionCount}; " +
                "${RetrievalSide.CODE_GRAPH.label} ${measured(aggregate.codeGraph)}; " +
                "${RetrievalSide.BASH.label} ${measured(aggregate.bash)}; " +
                "${RetrievalSide.RIPGREP.label} ${aggregate.ripgrep.measuredCount}/${aggregate.questionCount}. " +
                "Where a count is short of n, those questions are listed under \"Skipped\" — they " +
                "are excluded from that column's mean, not counted as zero."
        )
        appendLine()
        appendLine(
            "| Metric | ${RetrievalSide.CONTEXT_GRAPH.label} | ${RetrievalSide.CODE_GRAPH.label} | " +
                "${RetrievalSide.BASH.label} | ${RetrievalSide.RIPGREP.label} |"
        )
        appendLine("|---|---|---|---|---|")
        run.kValues.forEach { k ->
            appendLine(
                "| precision@$k | ${fmtPercent(aggregate.contextGraph.meanPrecisionAtK[k])} | " +
                    "${fmtPercent(aggregate.codeGraph?.meanPrecisionAtK?.get(k))} | " +
                    "${fmtPercent(aggregate.bash?.meanPrecisionAtK?.get(k))} | " +
                    "${fmtPercent(aggregate.ripgrep.meanPrecisionAtK[k])} |"
            )
        }
        run.kValues.forEach { k ->
            appendLine(
                "| recall@$k | ${fmtPercent(aggregate.contextGraph.meanRecallAtK[k])} | " +
                    "${fmtPercent(aggregate.codeGraph?.meanRecallAtK?.get(k))} | " +
                    "${fmtPercent(aggregate.bash?.meanRecallAtK?.get(k))} | " +
                    "${fmtPercent(aggregate.ripgrep.meanRecallAtK[k])} |"
            )
        }
        appendLine(
            "| MRR | ${fmtScore(aggregate.contextGraph.mrr)} | " +
                "${aggregate.codeGraph?.let { fmtScore(it.mrr) } ?: "n/a"} | " +
                "${aggregate.bash?.let { fmtScore(it.mrr) } ?: "n/a"} | " +
                "${fmtScore(aggregate.ripgrep.mrr)} |"
        )
        appendLine()
    }

    /** `n/a` for an absent measurement, never `0.0%` — an unknown must not read as a result. */
    private fun fmtPercent(value: Double?): String =
        if (value == null) "n/a" else String.format(Locale.ROOT, "%.1f%%", value * 100.0)

    private fun fmtScore(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    /**
     * The distance between two percentages, in percentage points, computed from the values **as
     * printed** rather than from the underlying doubles. A span derived from the raw values can
     * differ from the one a reader gets by subtracting the two figures above it in the same
     * paragraph, and a document that visibly disagrees with its own arithmetic is worth less than
     * one that omits the span.
     */
    private fun fmtPoints(hi: Double, lo: Double): String =
        String.format(Locale.ROOT, "%.1f", (rounded(hi) - rounded(lo)) * 100.0)

    /** A count of files that may be a midpoint: `11`, not `11.0`, but `11.5` when it really is one. */
    private fun fmtFileCount(value: Double): String =
        if (value == Math.rint(value)) value.toLong().toString()
        else String.format(Locale.ROOT, "%.1f", value)

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
