package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.cli.RepoRoot
import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * AC-20/AC-21 for the documentation site's interactive per-question page, held to exactly the
 * discipline `PublishedReportIsGeneratedTest` holds `BENCHMARKS.md` to and
 * `PublishedSummariesAreGeneratedTest` holds `README.md` to.
 *
 * The page carries 33 questions times four sides times six metrics, plus every side's ranked
 * answer. A single digit typed into it by hand would be indistinguishable from a measured one, and
 * would stay wrong through every subsequent run. So it is rendered from the committed result
 * document by [RetrievalSitePage], and this fails the moment the published file and the generator
 * disagree — whether because someone edited the page, or because someone changed the generator (or
 * its template) and never rolled the change forward.
 *
 * **To regenerate rather than assert**, run the module's tests with
 * `-Dcontextgraph.publishedSummaries.update=true` — the same flag that regenerates the README
 * section and the site's introduction paragraph. One flag for every published surface rendered from
 * this result is deliberate: those surfaces describe one run, and regenerating a subset of them is
 * how they come apart. That flag writes the page and then **fails**, naming what it rewrote,
 * rather than reporting green -- a CI job that inherited it by accident must not silently rewrite
 * a committed file and pass. Re-run without the flag to confirm the write reproduces byte for byte.
 */
class PublishedBenchmarkPageIsGeneratedTest : FunSpec({

    fun repoRoot(): Path = RepoRoot.find(Path.of(System.getProperty("user.dir")))

    fun publishedDir(): Path = repoRoot().resolve("modules/benchmark/results/four-way")

    /** The one result document every published four-way surface renders from. */
    fun resultDocument(): Path =
        Files.list(publishedDir()).use { stream ->
            stream.filter { it.name.startsWith("retrieval-") && it.name.endsWith(".json") }
                .sorted()
                .toList()
        }.also {
            check(it.size == 1) { "expected exactly one retrieval result in ${publishedDir()}, found $it" }
        }.single()

    fun run(): RetrievalRun = RetrievalRun.readFrom(resultDocument())

    fun pageFile(): Path = repoRoot().resolve(RetrievalSitePage.PAGE_PATH)

    val updating = System.getProperty("contextgraph.publishedSummaries.update") == "true"

    val dataOpen = "<script id=\"run-data\" type=\"application/json\">"

    /** The embedded data block, read back out of a rendered page exactly as the browser reads it. */
    fun dataOf(page: String): JsonObject {
        val start = page.indexOf(dataOpen)
        check(start >= 0) { "the page carries no embedded data block" }
        val from = start + dataOpen.length
        val to = page.indexOf("</script>", from)
        check(to > from) { "the page's data block is never closed" }
        return Json.parseToJsonElement(page.substring(from, to)).jsonObject
    }

    fun questionsOf(page: String): List<JsonObject> =
        dataOf(page)["questions"]!!.jsonArray.map { it.jsonObject }

    test("the published page regenerates byte for byte from the committed result document") {
        val page = pageFile()
        val rendered = RetrievalSitePage.generate(run())

        if (updating) {
            Files.createDirectories(page.parent)
            page.writeText(rendered)
            fail(
                "wrote ${RetrievalSitePage.PAGE_PATH} from the committed result document -- " +
                    "re-run without -Dcontextgraph.publishedSummaries.update=true to confirm the write reproduces green"
            )
        }

        val published = page.readText()
        rendered shouldBe published
        rendered.toByteArray(Charsets.UTF_8).toList() shouldBe published.toByteArray(Charsets.UTF_8).toList()
    }

    test("the check has teeth: the published page really carries every question the run scored") {
        // Without this, a generator that silently emitted an empty question list would still
        // "regenerate byte for byte" against a page it had itself emptied.
        val run = run()
        val questions = questionsOf(pageFile().readText())

        questions.size shouldBe run.results.size
        questions.map { it["id"]!!.jsonPrimitive.content } shouldContainAll
            run.results.map { it.questionId }
    }

    test("the page and the markdown report state the same score for the same question and side") {
        // The reason this page reuses PerQuestionBreakdown instead of deriving its own scores, made
        // checkable: a site and a report that disagreed about one question's score would discredit
        // both. Compared as *printed*, cell for cell, on the run both actually publish.
        val run = run()
        val report = RetrievalReportGenerator.generate(run)
        val page = questionsOf(pageFile().readText()).associateBy { it["id"]!!.jsonPrimitive.content }

        PerQuestionBreakdown.project(run).forEach { question ->
            // The per-question table's second cell is the category; the negative-control table's is
            // the repo, which is how the two tables' rows are told apart.
            val row = report.lineSequence()
                .filter { it.startsWith("| ${question.questionId} | ") }
                .map { it.trim('|').split(" | ").map(String::trim) }
                .single { it.getOrNull(1) == question.category.name }

            val pageQuestion = page.getValue(question.questionId)
            val sides = pageQuestion["sides"]!!.jsonObject

            row[2] shouldBe pageQuestion["goldCount"]!!.jsonPrimitive.content
            RetrievalSide.entries.forEachIndexed { index, side ->
                val cell = sides[side.name]!!
                val onPage = if (cell is JsonNull) {
                    "n/a"
                } else {
                    cell.jsonObject["metrics"]!!.jsonObject["rr"]!!.jsonObject["t"]!!.jsonPrimitive.content
                }
                row[3 + index] shouldBe onPage
            }
            row[3 + RetrievalSide.entries.size].replace("**", "") shouldBe
                pageQuestion["verdict"]!!.jsonPrimitive.content
        }
    }

    test("every question this project does not lead is on the page, rendered like every other") {
        // The rows that would be quietest to lose. Asserted structurally rather than by naming
        // them, so the check survives the numbers moving: whichever questions those are in this
        // run, each carries all four side keys and the same fields as any other row.
        val run = run()
        val page = questionsOf(pageFile().readText()).associateBy { it["id"]!!.jsonPrimitive.content }
        val notLed = PerQuestionBreakdown.project(run)
            .filter { question -> question.leaders.none { it.side == RetrievalSide.CONTEXT_GRAPH } }

        notLed.shouldNotBeEmptyOrAllLed()
        notLed.forEach { question ->
            val row = page.getValue(question.questionId)
            row["sides"]!!.jsonObject.keys shouldBe RetrievalSide.entries.map { it.name }.toSet()
            row["verdict"]!!.jsonPrimitive.content shouldNotBe ""
            row["expected"]!!.jsonArray.size shouldBe question.expectedFileCount
        }
    }

    test("an absent side renders as absent and a side that found nothing renders as a zero") {
        // On a synthetic run, so the distinction holds however the real numbers move. A zeroed
        // measurement and a missing one are different claims; a page that printed both as 0.000
        // would be making the claim the whole axis exists not to make.
        val ran = SideResult(rankedFiles = listOf("other.kt"), precisionAtK = mapOf(5 to 0.0), recallAtK = mapOf(5 to 0.0), reciprocalRank = 0.0)
        val found = SideResult(rankedFiles = listOf("gold.kt"), precisionAtK = mapOf(5 to 0.2), recallAtK = mapOf(5 to 1.0), reciprocalRank = 1.0)
        val run = RetrievalRun(
            runId = "retrieval-synthetic",
            generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            kValues = listOf(5),
            results = listOf(
                RetrievalRunResult(
                    questionId = "synthetic-q1",
                    repoId = "somerepo",
                    category = QuestionCategory.GRAPH_HEAVY,
                    expectedFiles = listOf("gold.kt"),
                    ripgrepQueryTokens = listOf("gold"),
                    contextGraph = null,
                    ripgrep = found,
                    codeGraph = ran,
                    bash = ran
                )
            )
        )

        val question = questionsOf(RetrievalSitePage.generate(run)).single()
        val sides = question["sides"]!!.jsonObject

        sides[RetrievalSide.CONTEXT_GRAPH.name] shouldBe JsonNull
        sides[RetrievalSide.CODE_GRAPH.name]!!.jsonObject["found"]!!.jsonPrimitive.boolean shouldBe false
        sides[RetrievalSide.CODE_GRAPH.name]!!.jsonObject["metrics"]!!.jsonObject["rr"]!!
            .jsonObject["t"]!!.jsonPrimitive.content shouldBe "0.000 (not found)"
        sides[RetrievalSide.RIPGREP.name]!!.jsonObject["metrics"]!!.jsonObject["rr"]!!
            .jsonObject["t"]!!.jsonPrimitive.content shouldBe "1.000 (rank 1)"
        // The gold file's own row says which sides reached it, and the absent one is not among them.
        question["expected"]!!.jsonArray.single().jsonObject["foundBy"]!!.jsonArray
            .map { it.jsonObject["side"]!!.jsonPrimitive.content } shouldBe listOf(RetrievalSide.RIPGREP.name)
    }

    test("the page depends on nothing outside itself and fetches nothing at load") {
        // The site is static and served from GitHub Pages, and the reader this page is built for is
        // one auditing it from a local clone. A single CDN script or web font would break it for
        // them; a fetch of a sibling JSON would break it *silently*, since a file:// page cannot
        // read its own directory.
        val published = pageFile().readText()

        listOf("<script src", "@import", "fetch(", "XMLHttpRequest", "//cdn", "fonts.").forEach { forbidden ->
            withClue(forbidden) { published.contains(forbidden) shouldBe false }
        }
        // Every absolute URL in the page is a link a reader may click, never something the page
        // loads: the only host it names at all is the project's own repository.
        Regex("""https?://[^"'\s)]+""").findAll(published)
            .map { it.value }
            .forEach { it.startsWith("https://github.com/erenalpaslan/context-graph") shouldBe true }
        published shouldContain "href=\"../shared/style.css\""
    }

    test("the page states which result it was built from, and the order its rows arrive in") {
        val run = run()
        val published = pageFile().readText()

        published shouldContain run.runId
        published shouldContain run.generatedAt.toString()
        published shouldContain "repository, then question id, never by score"
    }
})

/** Named for what its failure would mean: a run this project swept has nothing for the check to look at. */
private fun List<PerQuestionBreakdown>.shouldNotBeEmptyOrAllLed() {
    check(isNotEmpty()) {
        "this run has no question ContextGraph fails to lead, so the check above proves nothing; " +
            "assert the opposite deliberately rather than letting it pass vacuously"
    }
}
