package io.contextgraph.benchmark.retrieval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * The documentation site's interactive per-question page, rendered from a [RetrievalRun]
 * (AC-20, AC-21).
 *
 * **Why this is generated rather than written.** The page carries 33 questions times four sides
 * times six metrics, plus every side's ranked answer. Typed by hand it would be correct exactly
 * once -- until the next measurement -- and nobody re-typing 800 cells would notice which of them
 * had gone stale. Every figure it shows therefore comes out of the committed result document, and
 * `PublishedBenchmarkPageIsGeneratedTest` fails the moment the published page and this generator
 * disagree, exactly as `PublishedReportIsGeneratedTest` does for `BENCHMARKS.md`.
 *
 * **Why the data is embedded rather than fetched.** The alternative the slice allowed -- publish a
 * JSON beside the page and `fetch` it -- was rejected for one concrete reason: a `fetch` of a
 * sibling file from a `file://` page is blocked as a cross-origin request in every mainstream
 * browser, so the page would render empty for anyone auditing it from a local clone rather than
 * from the published site. A sceptic who has just cloned the repository is precisely the reader
 * this page exists for. Embedding the data in a `<script type="application/json">` block makes the
 * page work identically from `file://`, from GitHub Pages and from an offline machine, and it
 * keeps the page and its numbers in one artefact that cannot half-update.
 *
 * **Why the shell is a resource rather than a string literal here.** The static half of the page --
 * markup, stylesheet, the vanilla-JS view -- is HTML, and HTML embedded in Kotlin string literals
 * is HTML nobody can lint, format or read. It lives in `src/main/resources` as real HTML with two
 * placeholders, and this object fills them. **No repository, question, side, metric or figure is
 * named in the template**: every one of those comes from the data block, so the template cannot go
 * stale when the corpus does. That is the same discipline
 * [RetrievalReportGenerator.renderExtractionDisclosure] and friends are held to.
 *
 * **Presentation rules, carried over from the report and enforced here rather than in the view.**
 * A side with no measurement is a `null` in the data and renders as an absence, never as a zero.
 * A side that ran and found nothing is a real zero and says so in different words. Questions are
 * emitted in [PerQuestionBreakdown]'s own order -- repo, then question id -- and never by score, so
 * a question this project loses is exactly as easy to find as one it wins. Neither graph tool's
 * name is ever written bare: the labels come from [RetrievalSide], which spells out which is which.
 */
object RetrievalSitePage {

    /** Where the generated page is published, relative to the repository root. */
    const val PAGE_PATH = "docs/benchmarks/index.html"

    private const val TEMPLATE_RESOURCE = "/site/retrieval-benchmarks.template.html"
    private const val DATA_PLACEHOLDER = "{{DATA}}"
    private const val PROVENANCE_PLACEHOLDER = "{{PROVENANCE}}"

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    // ------------------------------------------------------------- rendering

    fun generate(run: RetrievalRun): String {
        val template = template()
        require(template.contains(DATA_PLACEHOLDER) && template.contains(PROVENANCE_PLACEHOLDER)) {
            "the page template has lost one of its placeholders; refusing to publish a page with a " +
                "hole where its data or its provenance should be"
        }
        return template
            .replace(PROVENANCE_PLACEHOLDER, provenance(run))
            .replace(DATA_PLACEHOLDER, embeddable(dataJson(run)))
    }

    private fun template(): String =
        RetrievalSitePage::class.java.getResourceAsStream(TEMPLATE_RESOURCE)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("page template $TEMPLATE_RESOURCE is missing from the benchmark module's resources")

    /**
     * Which result document this page was rendered from, and when -- stated in the page's own
     * markup rather than only in the data block, so it is readable with scripting off and visible
     * in the committed file's diff.
     */
    private fun provenance(run: RetrievalRun): String =
        "Generated from retrieval result <code>${escapeHtml(run.runId)}</code> (schema " +
            "v${run.schemaVersion}) at <code>${escapeHtml(run.generatedAt.toString())}</code>. " +
            "Every figure below is rendered from that document; none is typed."

    /**
     * The JSON made safe to sit inside a `<script>` element. `<` cannot appear outside a JSON
     * string literal, so escaping every one of them is both sufficient and harmless -- and it is
     * what stops a path containing `</script` from ending the block early.
     */
    private fun embeddable(dataJson: String): String = dataJson.replace("<", "\\u003c")

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    // ------------------------------------------------------------ data block

    /**
     * The whole run as the page's view consumes it.
     *
     * Assembled as text rather than encoded in one call so that each question lands on its own
     * line: the published page is committed, and a data block emitted as one very long line is a
     * diff nobody can review. Every value inside those lines is encoded by
     * `kotlinx.serialization`, so nothing here hand-writes JSON syntax around data.
     */
    private fun dataJson(run: RetrievalRun): String {
        val questions = PerQuestionBreakdown.project(run)
        val previewDepth = previewDepth(run)
        val header = buildJsonObject {
            put("runId", run.runId)
            put("schemaVersion", run.schemaVersion)
            put("generatedAt", run.generatedAt.toString())
            put("kValues", JsonArray(run.kValues.map { JsonPrimitive(it) }))
            put("previewDepth", previewDepth)
            put("questionCount", questions.size)
            put("sides", sides())
            put("metrics", metrics(run))
            put("repos", JsonArray(questions.map { it.repoId }.distinct().sorted().map { JsonPrimitive(it) }))
            put("categories", JsonArray(questions.map { it.category.name }.distinct().map { JsonPrimitive(it) }))
            put("aggregates", aggregates(run))
        }
        val rendered = header.entries.joinToString(",\n") { (key, value) ->
            "${json.encodeToString(JsonElement.serializer(), JsonPrimitive(key))}:" +
                json.encodeToString(JsonElement.serializer(), value)
        }
        val rows = questions.joinToString(",\n") { question ->
            json.encodeToString(JsonElement.serializer(), question(question, run, previewDepth))
        }
        return "{\n$rendered,\n\"questions\":[\n$rows\n]}\n"
    }

    /**
     * How far into each side's ranked answer the page shows.
     *
     * Twice the deepest `k` the run scored, derived rather than chosen: everything any metric here
     * looks at lives at or above `k`, so a preview twice that deep can never hide a figure the page
     * prints. The one thing that can sit deeper -- the first gold-cited file, which reciprocal rank
     * is not capped at -- is carried separately and shown at its true rank whatever that is. The
     * full length of every list is printed beside its preview, so a side whose answer runs to
     * hundreds of files says so rather than looking like one that returned a handful.
     */
    private fun previewDepth(run: RetrievalRun): Int = (run.kValues.maxOrNull() ?: 10) * 2

    private fun sides(): JsonArray = buildJsonArray {
        RetrievalSide.entries.forEach { side ->
            add(buildJsonObject {
                put("id", side.name)
                put("label", side.label)
            })
        }
    }

    /**
     * The metrics the side columns can be switched between, in the order they are offered.
     *
     * `dir` is the direction that puts the better result first, so a reader sorting a column gets
     * the sort they meant on the first click -- and it is a property of the metric, applied
     * identically to all four sides.
     */
    private fun metrics(run: RetrievalRun): JsonArray = buildJsonArray {
        add(metric("rr", "reciprocal rank", "desc", "The per-question figure every MRR row is the mean of."))
        add(metric("rank", "first gold-cited file", "asc", "How deep in its own answer that side first returned a gold-cited file."))
        run.kValues.sorted().forEach { k ->
            add(metric("p$k", "precision@$k", "desc", "Of the first $k files it returned, the share that are gold-cited."))
        }
        run.kValues.sorted().forEach { k ->
            add(metric("r$k", "recall@$k", "desc", "Of the gold-cited files, the share it returned in its first $k."))
        }
    }

    private fun metric(id: String, label: String, dir: String, hint: String): JsonObject = buildJsonObject {
        put("id", id)
        put("label", label)
        put("dir", dir)
        put("hint", hint)
    }

    // ------------------------------------------------------------- questions

    private fun question(question: PerQuestionBreakdown, run: RetrievalRun, previewDepth: Int): JsonObject =
        buildJsonObject {
            put("id", question.questionId)
            put("repo", question.repoId)
            put("category", question.category.name)
            put("goldCount", question.expectedFileCount)
            put("tokens", JsonArray(question.queryTokens.map { JsonPrimitive(it) }))
            put("missedByAll", question.missedByEverySide)
            put("leaders", JsonArray(question.leaders.map { JsonPrimitive(it.side.name) }))
            put("verdict", verdict(question))
            put("expected", expected(question))
            put("sides", buildJsonObject {
                RetrievalSide.entries.forEach { side ->
                    val score = question.scoreFor(side)
                    // The absent side is emitted as an explicit null rather than omitted: the view
                    // iterates the sides list and renders every one of them, so an absence arrives
                    // as an absence instead of as a column that quietly disappeared.
                    put(side.name, score?.let { sideScore(it, question, run, previewDepth) } ?: JsonNull)
                }
            })
        }

    /**
     * Who won the question, by name and including ties -- the same rule
     * [RetrievalReportGenerator.verdict] applies, so the page and the report never name different
     * winners for the same question. Losing to two sides is not a smaller loss than losing to one,
     * so every leading side is named.
     */
    private fun verdict(question: PerQuestionBreakdown): String {
        if (question.measured.isEmpty()) return "nothing measured"
        val leaders = question.leaders
        if (leaders.isEmpty()) return "no side found a gold-cited file"
        return if (leaders.size == 1) {
            "${leaders.single().side.label} leads"
        } else {
            "${leaders.joinToString(", ") { it.side.label }} tie at rank ${leaders.first().firstGoldHitRank}"
        }
    }

    /**
     * The gold set the question was scored against, and -- per file -- which sides actually
     * returned it and at what rank. Computed from the same de-duplicated ranked lists the scores
     * were computed from, so "found by nobody" on this list and a zero in the table are the same
     * fact told twice rather than two derivations that could disagree.
     */
    private fun expected(question: PerQuestionBreakdown): JsonArray = buildJsonArray {
        question.expectedFiles.forEach { path ->
            add(buildJsonObject {
                put("path", path)
                put("foundBy", buildJsonArray {
                    RetrievalSide.entries.forEach { side ->
                        val rank = question.scoreFor(side)?.rankedFiles?.indexOf(path)?.takeIf { it >= 0 }
                        if (rank != null) {
                            add(buildJsonObject {
                                put("side", side.name)
                                put("rank", rank + 1)
                            })
                        }
                    }
                })
            })
        }
    }

    private fun sideScore(
        score: QuestionSideScore,
        question: PerQuestionBreakdown,
        run: RetrievalRun,
        previewDepth: Int
    ): JsonObject {
        val gold = question.expectedFiles.toSet()
        return buildJsonObject {
            put("found", score.foundGoldFile)
            put("returned", score.rankedFiles.size)
            put("metrics", buildJsonObject {
                put("rr", cell(score.reciprocalRank, questionScoreText(score)))
                put(
                    "rank",
                    cell(
                        score.firstGoldHitRank?.toDouble(),
                        score.firstGoldHitRank?.let { "rank $it" } ?: "not found"
                    )
                )
                run.kValues.sorted().forEach { k ->
                    put("p$k", cell(score.precisionAtK[k], fmtPercent(score.precisionAtK[k])))
                    put("r$k", cell(score.recallAtK[k], fmtPercent(score.recallAtK[k])))
                }
            })
            put("preview", buildJsonArray {
                score.rankedFiles.take(previewDepth).forEachIndexed { index, path ->
                    add(buildJsonObject {
                        put("r", index + 1)
                        put("p", path)
                        put("g", path in gold)
                    })
                }
            })
            put("hidden", (score.rankedFiles.size - previewDepth).coerceAtLeast(0))
            val deep = score.firstGoldHitRank?.takeIf { it > previewDepth }
            put(
                "deepGold",
                deep?.let {
                    buildJsonObject {
                        put("r", it)
                        put("p", score.rankedFiles[it - 1])
                    }
                } ?: JsonNull
            )
        }
    }

    /**
     * One side's cell for one question, the way [RetrievalReportGenerator] prints it: the score,
     * then where in that side's own answer the first gold-cited file sat. `0.000 (not found)` is a
     * side that ran and reached nothing; the absent side never gets here at all, because it has no
     * score object to format.
     */
    private fun questionScoreText(score: QuestionSideScore): String =
        if (score.firstGoldHitRank == null) {
            "${fmtScore(score.reciprocalRank)} (not found)"
        } else {
            "${fmtScore(score.reciprocalRank)} (rank ${score.firstGoldHitRank})"
        }

    /**
     * A figure as the page sorts it and as the page prints it, together.
     *
     * Both halves are carried because the view must never derive one from the other: sorting on the
     * printed string would order `0.100` above `0.062` correctly and `rank 10` above `rank 9`
     * wrongly, and formatting in the browser would risk a page that rounds a figure differently
     * from the report beside it. So Kotlin formats every figure with the report's own formatters,
     * and the browser only ever compares `v` and prints `t`.
     */
    private fun cell(value: Double?, text: String): JsonObject = buildJsonObject {
        put("v", value?.let { JsonPrimitive(it) } ?: JsonNull)
        put("t", text)
    }

    // ------------------------------------------------------------ aggregates

    /**
     * The headline, negative-control, per-category and per-repo tables, so the page stands alone
     * rather than sending a reader to the markdown report for the means the rows beneath are of.
     *
     * The leading cell of each row is marked, whoever it is, on the figures **as printed** -- the
     * same comparison [PublishedSummaries] makes, so two sides a reader sees as identical are never
     * reported here as one beating the other.
     */
    private fun aggregates(run: RetrievalRun): JsonArray {
        val summary = run.summary ?: return JsonArray(emptyList())
        return buildJsonArray {
            add(aggregateGroup("headline", "Headline", "pool", summary.headline, run))
            add(aggregateGroup("negativeControl", "Negative controls", "pool", summary.negativeControl, run))
            summary.byCategory.entries.sortedBy { it.key.ordinal }.forEach { (category, group) ->
                add(aggregateGroup("category-${category.name}", category.name, "category", group, run))
            }
            summary.byRepo.toSortedMap().forEach { (repoId, group) ->
                add(aggregateGroup("repo-$repoId", repoId, "repo", group, run))
            }
        }
    }

    private fun aggregateGroup(
        id: String,
        title: String,
        kind: String,
        aggregate: RetrievalAggregate,
        run: RetrievalRun
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        put("kind", kind)
        put("questionCount", aggregate.questionCount)
        put("measured", buildJsonObject {
            RetrievalSide.entries.forEach { side ->
                val measured = aggregate.sideAggregate(side)
                put(
                    side.name,
                    measured?.let { JsonPrimitive("${it.measuredCount}/${aggregate.questionCount}") } ?: JsonNull
                )
            }
        })
        put("rows", buildJsonArray {
            run.kValues.sorted().forEach { k ->
                add(aggregateRow("precision@$k", aggregate) { it.meanPrecisionAtK[k] })
            }
            run.kValues.sorted().forEach { k ->
                add(aggregateRow("recall@$k", aggregate) { it.meanRecallAtK[k] })
            }
            add(aggregateRow("MRR", aggregate, ::fmtScoreOrNull) { it.mrr })
        })
    }

    private fun aggregateRow(
        label: String,
        aggregate: RetrievalAggregate,
        format: (Double?) -> String = ::fmtPercent,
        value: (SideAggregate) -> Double?
    ): JsonObject {
        // A side present but having measured nothing is printed as it stands and excluded from the
        // leadership comparison -- comparing against a column that measured no question would be a
        // comparison against nothing. The same exclusion RetrievalReportGenerator.measuredSides makes.
        val best = RetrievalSide.entries
            .mapNotNull { side -> aggregate.sideAggregate(side)?.takeIf { it.measuredCount > 0 } }
            .mapNotNull(value)
            .maxOrNull()
            ?.let { format(it) }
        return buildJsonObject {
            put("label", label)
            put("cells", buildJsonObject {
                RetrievalSide.entries.forEach { side ->
                    val sideAggregate = aggregate.sideAggregate(side)
                    if (sideAggregate == null) {
                        put(side.name, JsonNull)
                    } else {
                        val printed = format(value(sideAggregate))
                        put(side.name, buildJsonObject {
                            put("t", printed)
                            put("leads", sideAggregate.measuredCount > 0 && best != null && printed == best)
                        })
                    }
                }
            })
        }
    }

    // ------------------------------------------------------------ formatting

    /**
     * The [SideAggregate] a [RetrievalSide] names, `null` where that side is not in the run at all.
     *
     * A local copy of the `when` [RetrievalReportGenerator] keeps privately for the same purpose.
     * Sharing one would be better and is deliberately not done here: that generator's rendering is
     * pinned byte-for-byte to the published report, and reaching into it to hoist a helper is a
     * change to a file this work has no reason to touch.
     */
    private fun RetrievalAggregate.sideAggregate(side: RetrievalSide): SideAggregate? = when (side) {
        RetrievalSide.CONTEXT_GRAPH -> contextGraph
        RetrievalSide.CODE_GRAPH -> codeGraph
        RetrievalSide.BASH -> bash
        RetrievalSide.RIPGREP -> ripgrep
    }

    /** `n/a` for an absent figure, never `0.0%` -- an unknown must not read as a result. */
    private fun fmtPercent(value: Double?): String =
        if (value == null) "n/a" else String.format(Locale.ROOT, "%.1f%%", value * 100.0)

    private fun fmtScore(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private fun fmtScoreOrNull(value: Double?): String = if (value == null) "n/a" else fmtScore(value)
}
