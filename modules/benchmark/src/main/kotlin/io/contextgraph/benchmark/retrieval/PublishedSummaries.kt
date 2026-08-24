package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.retrieval.RetrievalFormats.fmtBytes
import io.contextgraph.benchmark.retrieval.RetrievalFormats.fmtDuration
import io.contextgraph.benchmark.runner.GraphTool
import java.util.Locale

/**
 * The two short, reader-facing summaries of the retrieval axis -- `README.md`'s Benchmarks section
 * and the documentation site's one-paragraph digest -- rendered from the same result document
 * [RetrievalReportGenerator] renders the full report from (AC-19, AC-21).
 *
 * **Why these are generated rather than written.** The full report is already pinned to its result
 * document by `PublishedReportIsGeneratedTest`, but the README is where almost every reader
 * actually meets these numbers, and until now it was typed by hand. That is exactly the surface a
 * stale figure survives on: the section this replaces still described a nine-question, one-repo,
 * three-side run -- and three of its four out-of-date claims *understated* what had since been
 * measured, because nobody re-typed them when the measurement moved. A figure a human retyped is a
 * figure that will be wrong after the next run, whichever direction the error happens to point.
 *
 * So every number below comes out of [RetrievalRun]. The only hand-written figures are the two
 * historical ones this section cites from other documents ([EARLIER_EXCALIDRAW_MRR] and the ingest
 * history), each carrying the document it is a citation of; both are pinned in one place here
 * rather than scattered through prose in three files.
 *
 * This is a **separate file from [RetrievalReportGenerator] on purpose**: the two are written and
 * changed by different work at different times, and the full report's generator is large enough
 * without a second audience's rendering inside it.
 */
object PublishedSummaries {

    /** Markers delimiting the generated Benchmarks section in `README.md`. */
    const val README_START = "<!-- benchmarks:start -->"
    const val README_END = "<!-- benchmarks:end -->"

    /** Markers delimiting the generated digest paragraph in the site's introduction page. */
    const val SITE_START = "<!-- benchmarks-digest:start -->"
    const val SITE_END = "<!-- benchmarks-digest:end -->"

    /** Where the full report lives, relative to the repository root. */
    private const val FULL_REPORT = "modules/benchmark/results/four-way/BENCHMARKS.md"

    /**
     * The interactive per-question page, taken from [RetrievalSitePage] rather than retyped: the
     * README's link and the page's own home cannot then disagree about where it is.
     */
    private val SITE_PAGE = RetrievalSitePage.PAGE_PATH

    /** The width the rest of `README.md` is hand-wrapped to. */
    private const val WRAP_WIDTH = 92

    /**
     * Excalidraw's ContextGraph MRR at the two earlier published points on the same nine questions,
     * cited from `docs/retrieval-ranking-ablation.md` and `docs/identifier-segment-vocabulary.md`.
     * The third point of that progression is *not* here -- it is read from this run's own result,
     * so the sentence cannot claim a current figure the current measurement does not carry.
     */
    private const val EARLIER_EXCALIDRAW_MRR_BEFORE_QUERY_AWARE_RANKING = "0.133"
    private const val EARLIER_EXCALIDRAW_MRR_AFTER_QUERY_AWARE_RANKING = "0.482"

    /** Keycloak's own ingest, before and after the batch-write fix, cited from `docs/ingest-cost.md`. */
    private const val KEYCLOAK_INGEST_BEFORE_BATCHING = "142m 55.6s"
    private const val KEYCLOAK_INGEST_AFTER_BATCHING = "8m 16.4s"
    private const val KEYCLOAK_INGEST_SPEEDUP = "17.3×"

    /**
     * Display text for the four sides, read straight from [RetrievalSide.label]. [sides],
     * [Loss.leaders] and every identity check below carry a [RetrievalSide], never one of these
     * four strings -- they exist only so a table row does not have to go back through the enum
     * every time it is about to print one.
     *
     * This section used to re-word [RetrievalSide.RIPGREP] as "ripgrep (third-party)" here while
     * the enum's own label still said "(baseline)" -- the same side under two names on two
     * published surfaces, exactly the split this project's own review caught (Spec AC-9). Fixed
     * at the source instead of papered over here a second time: [RetrievalSide.RIPGREP]'s label
     * now says "third-party" itself, so this file, `RetrievalReportGenerator` and
     * `RetrievalSitePage` all print the same word without a second mapping to keep in sync.
     */
    private val CONTEXT_GRAPH = RetrievalSide.CONTEXT_GRAPH.label
    private val CODE_GRAPH = RetrievalSide.CODE_GRAPH.label
    private val BASH = RetrievalSide.BASH.label
    private val RIPGREP = RetrievalSide.RIPGREP.label

    // ------------------------------------------------------------- upsert

    /**
     * Replaces the block between [start] and [end] in [document] with [block].
     *
     * Deliberately **throws** when the markers are absent, where
     * [RetrievalReportGenerator.upsert] appends. That generator owns a whole file and can create
     * its section; this one edits one section of a document a human wrote the rest of, and
     * appending a Benchmarks section to the end of `README.md` -- below Configuration, below
     * Building -- would be a silent wrong answer rather than a loud missing one.
     */
    fun upsert(document: String, block: String, start: String, end: String): String {
        val from = document.indexOf(start)
        val to = document.indexOf(end)
        require(from >= 0 && to > from) {
            "document carries no '$start' ... '$end' block to replace; refusing to guess where it goes"
        }
        return document.substring(0, from) + start + "\n" + block + end + document.substring(to + end.length)
    }

    // ------------------------------------------------------- README section

    /** `README.md`'s whole Benchmarks section, markers excluded. */
    fun readmeSection(run: RetrievalRun): String = buildString {
        val summary = requireNotNull(run.summary) { "a run with no summary has nothing to publish" }
        val repos = summary.byRepo.toSortedMap()
        val topK = run.kValues.maxOrNull() ?: 10

        appendLine("## Benchmarks")
        appendLine()
        paragraph(
            "Four sides answered the same ${run.results.size} questions over the same " +
                "${words(repos.size)} repositories, scored against the same expected files — the " +
                "ones each question's own gold facts cite — by mean reciprocal rank and recall. " +
                "No LLM is in the loop anywhere: the same corpus and the same questions always " +
                "produce the same numbers."
        )
        bullet("**$CONTEXT_GRAPH** — this repository's own graph, queried through `QueryEngine.buildContext`.")
        bullet("**$CODE_GRAPH** — `@colbymchenry/codegraph` 1.5.0, driven as `codegraph explore`.")
        bullet(
            "**$BASH** — stock `grep` over a never-indexed checkout. **No third-party tool is " +
                "installed, invoked or assumed:** this is the floor a developer with nothing but " +
                "a shell already has, and the honest thing an index has to earn its cost against."
        )
        bullet(
            "**$RIPGREP** — `rg` over the same never-indexed checkout, given the same derived " +
                "tokens. A separate install, kept as the comparator the earlier runs used."
        )
        appendLine()
        paragraph(
            "**These rows all come from one measurement run.** Every side was handed the same raw " +
                "question text, against the same working copies at the same pinned commits, and " +
                "scored against the same gold-derived expected files with the same metrics; no " +
                "side's output was re-ranked, filtered or truncated before scoring. The section " +
                "this replaces had to warn that its comparator rows came from an earlier run. " +
                "That caveat is gone because the run it described has been superseded, not " +
                "because it stopped mattering."
        )
        paragraph("**Retrieval, per repository** — each repo's own questions, its negative control included:")
        renderPerRepoTable(repos, topK)
        paragraph(lossSentence(repos, topK))
        paragraph(
            "**Pooled over the ${summary.headline.questionCount} headline questions**, which " +
                "excludes the ${words(summary.negativeControl.questionCount)} negative-control " +
                "questions the full report scores separately — so the pooled row and the per-repo " +
                "rows above are different pools and do not average into each other:"
        )
        renderPooledTable(summary.headline, run.kValues)
        repos["excalidraw"]?.let { excalidraw ->
            paragraph(
                "This project's own progression on `excalidraw`'s ${words(excalidraw.questionCount)} " +
                    "questions is like-for-like across three published runs: " +
                    "**$EARLIER_EXCALIDRAW_MRR_BEFORE_QUERY_AWARE_RANKING** MRR before ranking " +
                    "became a function of the query, " +
                    "**$EARLIER_EXCALIDRAW_MRR_AFTER_QUERY_AWARE_RANKING** after, " +
                    "**${fmtScore(excalidraw.contextGraph.mrr)}** here, once identifier segments " +
                    "were materialised at index time."
            )
        }
        paragraph(keycloakGateParagraph(run))
        paragraph(coverageSentence(run))
        paragraph("**Ingest — what each index cost to build:**")
        renderIngestTable(run)
        paragraph(ingestSentence(run))
        paragraph(caveatParagraph(run, repos, topK))
        paragraph(
            "**The same run, question by question, as a page you can interrogate:** " +
                "[`$SITE_PAGE`]($SITE_PAGE) lists every one of the ${run.results.size} questions " +
                "with all four sides' scores, filters by repository and category, sorts by any " +
                "metric, and opens a question to show its expected files against each side's own " +
                "ranked answer. It is generated from the same result document as everything above " +
                "and needs no server: open it from a clone with the network off and it works."
        )
        paragraph(
            "Full methodology, the per-question breakdown, the flags both text-search sides were " +
                "given, every skip and every place this project loses: " +
                "[`$FULL_REPORT`]($FULL_REPORT). Per-signal ablation, the cost of each mechanism, " +
                "and the reasoning behind every signal that did *not* ship: " +
                "[`docs/retrieval-ranking-ablation.md`](docs/retrieval-ranking-ablation.md), " +
                "[`docs/identifier-segment-vocabulary.md`](docs/identifier-segment-vocabulary.md) " +
                "and [`docs/ingest-cost.md`](docs/ingest-cost.md)."
        )
    }

    /**
     * One paragraph, hard-wrapped and followed by a blank line.
     *
     * The rest of `README.md` is hand-wrapped at this width, and a generated block that arrives as
     * one 1,500-character line would announce itself as generated in every diff of the file --
     * worse, it would make every future diff of this section unreadable.
     *
     * Breaking on spaces is safe against both markdown constructs this emits. A link destination
     * cannot contain a space, so a greedy break can never land inside one; and a code span broken
     * at a space is still one code span, since CommonMark folds the line ending back into a space
     * when it renders. Only the source moves, never the rendering.
     */
    private fun StringBuilder.paragraph(text: String) {
        appendLine(wrap(text, ""))
        appendLine()
    }

    /** A list item, wrapped with the two-space continuation indent that keeps it one item. */
    private fun StringBuilder.bullet(text: String) {
        appendLine("- " + wrap(text, "  "))
    }

    private fun wrap(text: String, continuation: String): String {
        val out = StringBuilder()
        var column = continuation.length
        text.split(" ").filter { it.isNotEmpty() }.forEachIndexed { index, word ->
            when {
                index == 0 -> { out.append(word); column += word.length }
                column + 1 + word.length > WRAP_WIDTH -> {
                    out.append("\n").append(continuation).append(word)
                    column = continuation.length + word.length
                }
                else -> { out.append(" ").append(word); column += 1 + word.length }
            }
        }
        return out.toString()
    }

    // ---------------------------------------------------------- site digest

    /**
     * The site introduction's one paragraph about the measurement, as an HTML `<p>`.
     *
     * Generated for the same reason the README section is: it carried the same stale "the
     * comparator figures come from an earlier measurement run" caveat, in a page a reader meets
     * before the README. It names a loss as well as a lead, because a summary that only survives
     * by omitting the losses is the kind of number this axis exists not to produce.
     */
    fun siteIntroParagraph(run: RetrievalRun): String {
        val summary = requireNotNull(run.summary) { "a run with no summary has nothing to publish" }
        val repos = summary.byRepo.toSortedMap()
        val topK = run.kValues.maxOrNull() ?: 10
        val head = summary.headline
        val mrrLoss = firstLoss(repos, mrrMetric())
        val recallLoss = firstLoss(repos, recallMetric(topK))
        return buildString {
            append("  <p>Most graph tools then hand an agent a list of pointers and let it go read files anyway, ")
            append("which spends the tokens the graph was supposed to save. ContextGraph returns the source itself, ")
            append("ranked against the question that was asked, capped at a token budget. Across ")
            append("${run.results.size} questions over ${words(repos.size)} repositories, all four sides measured in one run, ")
            append("its mean reciprocal rank over the ${head.questionCount} headline questions is ")
            append("${fmtScore(head.contextGraph.mrr)}, against ${fmtScore(requireNotNull(head.codeGraph).mrr)} for ")
            append("$CODE_GRAPH, ${fmtScore(requireNotNull(head.bash).mrr)} for a stock ")
            append("<code>grep</code> and ${fmtScore(head.ripgrep.mrr)} for $RIPGREP. ")
            // Printed only when there is a loss to print. A run this project swept would otherwise
            // publish "It does not win everywhere:" followed by nothing at all.
            val clauses = listOfNotNull(
                mrrLoss?.let {
                    "on <code>${it.repoId}</code> ${leadersText(it)} ${leadVerb(it)} on MRR, " +
                        "${it.leadingValue} against ${it.contextGraphValue}"
                },
                recallLoss?.let {
                    "on <code>${it.repoId}</code> ${leadersText(it)} ${leadVerb(it)} on recall@$topK, " +
                        "${it.leadingValue} against ${it.contextGraphValue}"
                }
            )
            if (clauses.isNotEmpty()) append("It does not win everywhere: ${clauses.joinToString("; ")}. ")
            append("The <a href=\"https://github.com/erenalpaslan/context-graph#benchmarks\" target=\"_blank\">project README</a> ")
            append("spells out what the numbers do and do not establish.</p>")
            appendLine()
        }
    }

    // ------------------------------------------------------------- tables

    private fun StringBuilder.renderPerRepoTable(
        repos: Map<String, RetrievalAggregate>,
        topK: Int
    ) {
        appendLine("| Repo | Metric | $CONTEXT_GRAPH | $CODE_GRAPH | $BASH | $RIPGREP |")
        appendLine("|---|---|---|---|---|---|")
        repos.forEach { (repoId, aggregate) ->
            listOf(mrrMetric(), recallMetric(topK)).forEach { metric ->
                val cells = renderCells(aggregate, metric)
                appendLine("| `$repoId` (${aggregate.questionCount}) | ${metric.label} | ${cells.joinToString(" | ")} |")
            }
        }
        appendLine()
        paragraph(
            "The number after each repo is how many questions it contributes. **Bold is the " +
                "leading column in that row**, whoever it is."
        )
    }

    private fun StringBuilder.renderPooledTable(headline: RetrievalAggregate, kValues: List<Int>) {
        appendLine("| Metric | $CONTEXT_GRAPH | $CODE_GRAPH | $BASH | $RIPGREP |")
        appendLine("|---|---|---|---|---|")
        val metrics = kValues.sorted().map { precisionMetric(it) } +
            kValues.sorted().map { recallMetric(it) } +
            mrrMetric()
        metrics.forEach { metric ->
            appendLine("| ${metric.label} | ${renderCells(headline, metric).joinToString(" | ")} |")
        }
        appendLine()
    }

    private fun StringBuilder.renderIngestTable(run: RetrievalRun) {
        appendLine("| Repo | $CONTEXT_GRAPH | $CODE_GRAPH |")
        appendLine("|---|---|---|")
        run.ingestCosts.map { it.repoId }.distinct().sorted().forEach { repoId ->
            appendLine(
                "| `$repoId` | ${fmtCost(cost(run, repoId, GraphTool.CONTEXTGRAPH))} | " +
                    "${fmtCost(cost(run, repoId, GraphTool.CODEGRAPH))} |"
            )
        }
        appendLine()
    }

    // ---------------------------------------------------------- paragraphs

    /**
     * The sentence naming every row this project does not lead, or -- when there is none -- saying
     * so rather than leaving the reader to check. Generated from the table it describes, so it
     * cannot fall out of step with it the way a hand-written one did.
     */
    private fun lossSentence(repos: Map<String, RetrievalAggregate>, topK: Int): String {
        val losses = listOf(mrrMetric(), recallMetric(topK)).flatMap { metric ->
            repos.entries.mapNotNull { (repoId, aggregate) -> loss(repoId, aggregate, metric) }
        }
        if (losses.isEmpty()) {
            return "No column leads $CONTEXT_GRAPH in that table. That is this run's result on this " +
                "corpus and these questions, not a general claim; the caveats below are the ones that bound it."
        }
        val clauses = losses.joinToString("; ") { l ->
            "on `${l.repoId}` ${leadersText(l)} ${leadVerb(l)} on ${l.metricLabel}, " +
                "${l.leadingValue} against ${l.contextGraphValue}"
        }
        // The sharpest of these losses is the one to a side that builds no index at all, and it is
        // printed only when there actually is one -- a sentence about `grep` outperforming the
        // index would otherwise survive into a run where it did not. Compared as the enum, not as
        // a label: leaders is a List<RetrievalSide> (see Loss), so re-wording BASH's published
        // label can never make this sentence silently stop appearing.
        val toBash = losses.firstOrNull { RetrievalSide.BASH in it.leaders }
        val sting = toBash?.let {
            " Plain `grep` pays nothing for an index and still retrieves more than this project's " +
                "index does on `${it.repoId}`."
        } ?: ""
        return "**$CONTEXT_GRAPH does not lead every row, and the rows it loses are in the table " +
            "above rather than only in the full report:** $clauses.$sting"
    }

    private fun keycloakGateParagraph(run: RetrievalRun): String {
        val coverage = run.goldFileCoverage
            .firstOrNull { it.repoId == "keycloak" && it.side == RetrievalSide.CONTEXT_GRAPH }
            ?: return "**Keycloak's ContextGraph side carries no gold-file coverage figure in this run.**"
        return "**Keycloak is measured this time.** The previous run published its retrieval side " +
            "as unmeasured: `IndexIntegrityGate` refused it over a single gold-cited file, a " +
            "`META-INF/services/` provider-configuration entry that was missing from the index. " +
            "The gate was not relaxed and no gold fact was dropped — the indexing gap behind it " +
            "was fixed. File discovery had been treating " +
            "`org.keycloak.credential.hash.PasswordHashProviderFactory` as a secret, because the " +
            "sensitive-filename heuristic matched words in it; a ServiceLoader registration file " +
            "is named after the interface it registers and never after its own contents, so those " +
            "entries are now exempt from that heuristic. Keycloak's index now holds " +
            "${coverage.presentFileCount} of ${coverage.citedFileCount} gold-cited files and the " +
            "gate passes on its own terms."
    }

    /** Both graph tools' gold-file coverage, in one sentence, in the same repo order as the tables. */
    private fun coverageSentence(run: RetrievalRun): String {
        fun figures(side: RetrievalSide) = run.goldFileCoverage
            .filter { it.side == side }
            .sortedBy { it.repoId }
            .joinToString(", ") { "${it.presentFileCount ?: 0}/${it.citedFileCount} on `${it.repoId}`" }
        return "**An index cannot retrieve a file it never indexed**, so coverage of the gold-cited " +
            "files is published beside the scores rather than after them. $CONTEXT_GRAPH holds " +
            "${figures(RetrievalSide.CONTEXT_GRAPH)}; $CODE_GRAPH holds " +
            "${figures(RetrievalSide.CODE_GRAPH)}. Both text-search sides read the working tree " +
            "directly and so reach every file by construction."
    }

    /**
     * What ingest actually cost, stated as the measurement rather than as a headline.
     *
     * The ratios are computed only over repos where **both** tools were timed in this run. A row
     * whose index this run found already built is a sentinel, not a duration, and comparing
     * against it would manufacture a speed claim out of a measurement nobody made -- in this
     * project's favour, which is exactly the direction that needs the discipline.
     */
    private fun ingestSentence(run: RetrievalRun): String = buildString {
        val comparable = run.ingestCosts.map { it.repoId }.distinct().sorted().mapNotNull { repoId ->
            val ours = cost(run, repoId, GraphTool.CONTEXTGRAPH)?.durationMillis ?: return@mapNotNull null
            val theirs = cost(run, repoId, GraphTool.CODEGRAPH)?.durationMillis ?: return@mapNotNull null
            if (ours <= 0L || theirs <= 0L) null else Triple(repoId, ours, theirs)
        }
        val reused = run.ingestCosts.filter { it.absentReason == null && it.durationMillis == 0L }
        if (comparable.isNotEmpty()) {
            val slower = comparable.filter { (_, ours, theirs) -> ours > theirs }
            val faster = comparable.filter { (_, ours, theirs) -> ours < theirs }
            if (slower.size == comparable.size) {
                append("**Building the index costs this project more than it costs $CODE_GRAPH.** ")
                append("On every repo where both indexes were built in this run it is slower, by ")
                append(slower.joinToString(", ") { (repoId, ours, theirs) -> "${fmtRatio(ours, theirs)} on `$repoId`" })
                append(". ")
            } else {
                append("**Building the index costs the two tools differently, repo by repo.** ")
                append("This project is slower by ")
                append(slower.joinToString(", ") { (repoId, ours, theirs) -> "${fmtRatio(ours, theirs)} on `$repoId`" })
                append(", and faster by ")
                append(faster.joinToString(", ") { (repoId, ours, theirs) -> "${fmtRatio(theirs, ours)} on `$repoId`" })
                append(". ")
            }
            append(
                "The claim this replaces — that ingest was *roughly an order of magnitude* slower " +
                    "than the third-party tool's — rested on an earlier run's figures and no " +
                    "longer holds; the widest gap measured here is "
            )
            val widest = comparable.maxByOrNull { (_, ours, theirs) -> ours.toDouble() / theirs.toDouble() }!!
            append(
                "`${widest.first}`, at ${fmtDuration(widest.second)} against " +
                    "${fmtDuration(widest.third)}. "
            )
        }
        if (reused.isNotEmpty()) {
            append(
                "Not every row is a comparison: " +
                    reused.joinToString(", ") { "`${it.repoId}`'s ${RetrievalSide.of(it.tool).label} index" } +
                    " was already built when this run found it and was not rebuilt, so its cost " +
                    "belongs to the run that paid it and is deliberately not carried forward. "
            )
        }
        val biggest = run.ingestCosts
            .filter { it.tool == GraphTool.CONTEXTGRAPH && it.indexSizeBytes != null }
            .maxByOrNull { it.indexSizeBytes!! }
        val theirBiggest = biggest?.let { cost(run, it.repoId, GraphTool.CODEGRAPH) }
        if (biggest != null && theirBiggest?.indexSizeBytes != null) {
            append(
                "On `${biggest.repoId}`, the largest index here, it also occupies " +
                    "${fmtRatio(biggest.indexSizeBytes!!, theirBiggest.indexSizeBytes!!)} the disk " +
                    "(${fmtBytes(biggest.indexSizeBytes)} against ${fmtBytes(theirBiggest.indexSizeBytes)}). "
            )
        }
        append(
            "Both text-search sides have no ingest step at all and pay nothing before the first " +
                "query — which is the number every row above is being compared against. Earlier " +
                "work on ingest cost ([`docs/ingest-cost.md`](docs/ingest-cost.md)) took Keycloak " +
                "from $KEYCLOAK_INGEST_BEFORE_BATCHING to $KEYCLOAK_INGEST_AFTER_BATCHING, " +
                "$KEYCLOAK_INGEST_SPEEDUP, by batching SQLite writes; the figure in the table is " +
                "this run's own measurement on its own corpus and machine, not a further speedup " +
                "claimed on top of it."
        )
    }

    /** What is still unproven — rewritten for a larger claim, not dropped because the numbers improved. */
    private fun caveatParagraph(
        run: RetrievalRun,
        repos: Map<String, RetrievalAggregate>,
        topK: Int
    ): String = "**What these numbers do not say.** ${run.results.size} questions over " +
        "${words(repos.size)} repositories is a much larger claim than the single repository this " +
        "section used to report, and it is still not a proof: " +
        "${words(repos.size)} repositories are not \"code in general\"; the questions and the gold " +
        "facts they are scored against were written by this project, which is the single largest " +
        "thing a reader should discount for; and each figure is one run rather than a distribution. " +
        "The two text-search columns come out identical at every `k` measured here, so the margin " +
        "over $RIPGREP on this corpus is a margin over `grep` — ripgrep's own engineering bought " +
        "nothing a stock shell did not already reach, and the baseline should be read as a plain " +
        "one rather than a strong one. Nothing here measures answer quality: the metric is which " +
        "files a tool puts in front of you, not what an agent then does with them, and the " +
        "agent-level axis is measured separately and never mixed with this one. Ingest was timed " +
        "once per repo per tool on one machine, with no repeats and no variance. And the rows " +
        "this project loses — ${lossListing(repos, topK)} — are part of the result, not " +
        "exceptions to it."

    private fun lossListing(repos: Map<String, RetrievalAggregate>, topK: Int): String {
        val losses = listOf(mrrMetric(), recallMetric(topK)).flatMap { metric ->
            repos.entries.mapNotNull { (repoId, aggregate) -> loss(repoId, aggregate, metric) }
        }
        if (losses.isEmpty()) return "none in this run"
        return losses.joinToString(", ") { "`${it.repoId}`'s ${it.metricLabel}" }
    }

    // -------------------------------------------------------------- model

    private class Metric(
        val label: String,
        val value: (SideAggregate) -> Double?,
        val format: (Double) -> String
    )

    private fun mrrMetric() = Metric("MRR", { it.mrr }, ::fmtScore)

    private fun recallMetric(k: Int) = Metric("recall@$k", { it.meanRecallAtK[k] }, ::fmtPercent)

    private fun precisionMetric(k: Int) = Metric("precision@$k", { it.meanPrecisionAtK[k] }, ::fmtPercent)

    /**
     * Every side, keyed by [RetrievalSide] rather than by its display label -- through
     * [RetrievalAggregate.sideAggregate], the accessor [RetrievalReportGenerator] and
     * [RetrievalSitePage] already share, so this is the third surface that needs "which field of
     * an aggregate does this side name" and the third that reaches the same place for it rather
     * than re-deriving it. Keying by the enum rather than by its display label is what keeps
     * [loss] identity-safe: a leader is *found* by comparing figures, never by comparing strings,
     * so re-wording a label can never change who counts as having led a row.
     */
    private fun sides(aggregate: RetrievalAggregate): List<Pair<RetrievalSide, SideAggregate?>> =
        RetrievalSide.entries.map { it to aggregate.sideAggregate(it) }

    /**
     * One table row's cells, with every cell equal to the row's best figure **as printed** in bold.
     *
     * Compared as printed rather than as doubles on purpose: the two text-search sides differ in
     * the sixth decimal on some rows, and bolding one of two columns a reader sees as identical
     * would be a distinction the document does not otherwise make.
     */
    private fun renderCells(aggregate: RetrievalAggregate, metric: Metric): List<String> {
        val printed = sides(aggregate).map { (_, side) -> side?.let(metric.value)?.let(metric.format) }
        val best = sides(aggregate).mapNotNull { (_, side) -> side?.let(metric.value) }
            .maxOrNull()?.let(metric.format)
        return printed.map { cell ->
            when {
                cell == null -> "n/a"
                cell == best -> "**$cell**"
                else -> cell
            }
        }
    }

    private class Loss(
        val repoId: String,
        val metricLabel: String,
        /** Kept as [RetrievalSide], not a display string -- see [sides]. */
        val leaders: List<RetrievalSide>,
        val leadingValue: String,
        val contextGraphValue: String
    )

    /** The row's loss, or null when this project leads it (ties count as leading). */
    private fun loss(repoId: String, aggregate: RetrievalAggregate, metric: Metric): Loss? {
        val printed = sides(aggregate).map { (side, agg) -> side to agg?.let(metric.value)?.let(metric.format) }
        // Explicit lookup rather than "position 0 is ContextGraph": true today only because
        // RetrievalSide.CONTEXT_GRAPH happens to be declared first, and nothing enforced that.
        val ours = printed.first { (side, _) -> side == RetrievalSide.CONTEXT_GRAPH }.second ?: return null
        val best = sides(aggregate).mapNotNull { (_, agg) -> agg?.let(metric.value) }
            .maxOrNull()?.let(metric.format) ?: return null
        if (ours == best) return null
        return Loss(
            repoId = repoId,
            metricLabel = metric.label,
            leaders = printed.filter { it.second == best }.map { it.first },
            leadingValue = best,
            contextGraphValue = ours
        )
    }

    private fun firstLoss(repos: Map<String, RetrievalAggregate>, metric: Metric): Loss? =
        repos.entries.firstNotNullOfOrNull { (repoId, aggregate) -> loss(repoId, aggregate, metric) }

    private fun leadersText(loss: Loss): String {
        val labels = loss.leaders.map { it.label }
        return when (labels.size) {
            1 -> labels.single()
            else -> labels.dropLast(1).joinToString(", ") + " and " + labels.last()
        }
    }

    private fun leadVerb(loss: Loss): String = if (loss.leaders.size > 1) "lead" else "leads"

    private fun cost(run: RetrievalRun, repoId: String, tool: GraphTool): ToolIngestCost? =
        run.ingestCosts.firstOrNull { it.repoId == repoId && it.tool == tool }

    // --------------------------------------------------------- formatting

    private fun fmtCost(cost: ToolIngestCost?): String = when {
        cost == null -> "_not recorded_"
        cost.absentReason != null -> "_${cost.absentReason}_"
        else -> "${fmtDuration(cost.durationMillis)}, ${fmtBytes(cost.indexSizeBytes)}"
    }

    /**
     * A small count as a word, which is how the rest of `README.md` writes one. Anything past the
     * list stays a numeral, where spelling it out would be harder to read rather than easier.
     */
    private fun words(count: Int): String = SMALL_NUMBERS.getOrNull(count) ?: count.toString()

    private val SMALL_NUMBERS = listOf(
        "zero", "one", "two", "three", "four", "five",
        "six", "seven", "eight", "nine", "ten", "eleven", "twelve"
    )

    private fun fmtScore(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private fun fmtPercent(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value * 100.0)

    private fun fmtRatio(numerator: Long, denominator: Long): String =
        String.format(Locale.ROOT, "%.2f×", numerator.toDouble() / denominator.toDouble())
}
