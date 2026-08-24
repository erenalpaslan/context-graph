package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.cli.RepoRoot
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.Instant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * AC-21 for the two surfaces most readers actually reach: `README.md`'s Benchmarks section and the
 * documentation site's introduction paragraph.
 *
 * `PublishedReportIsGeneratedTest` already pins the full `BENCHMARKS.md` to its result document.
 * This is the same check for the two short summaries of it, and it exists because the README is
 * where the stale figures actually accumulated: the section this replaced still described a
 * nine-question, one-repo, three-side run months after a 33-question, four-repo, four-side one had
 * been measured and published — and three of its four out-of-date claims *understated* the project.
 * Nobody re-typed them, because nothing made them re-typeable. Now nothing has to be: both blocks
 * are rendered from the committed result JSON by [PublishedSummaries], and this test fails the
 * moment either the committed text or the generator moves without the other.
 *
 * **To regenerate rather than assert**, run the module's tests with
 * `-Dcontextgraph.publishedSummaries.update=true`. That writes both documents from the committed
 * result -- and then **fails**, naming what it rewrote, rather than reporting green. A CI job
 * that inherited this flag by accident must not silently rewrite three committed files and pass;
 * the intended flow is a human running with the flag, reading which file(s) it rewrote, and
 * re-running *without* the flag to confirm the write reproduces byte for byte.
 */
class PublishedSummariesAreGeneratedTest : FunSpec({

    fun repoRoot(): Path = RepoRoot.find(Path.of(System.getProperty("user.dir")))

    fun publishedDir(): Path = repoRoot().resolve("modules/benchmark/results/four-way")

    /** The one result document the published four-way surfaces all render from. */
    fun resultDocument(): Path =
        Files.list(publishedDir()).use { stream ->
            stream.filter { it.name.startsWith("retrieval-") && it.name.endsWith(".json") }
                .sorted()
                .toList()
        }.also {
            check(it.size == 1) { "expected exactly one retrieval result in ${publishedDir()}, found $it" }
        }.single()

    fun run(): RetrievalRun = RetrievalRun.readFrom(resultDocument())

    val updating = System.getProperty("contextgraph.publishedSummaries.update") == "true"

    /**
     * Regenerates both documents in place when asked to, so the escape hatch the KDoc promises is
     * the same code path the assertions below check rather than a second one that could drift.
     */
    fun publish(file: Path, block: String, start: String, end: String) {
        file.writeText(PublishedSummaries.upsert(file.readText(), block, start, end))
    }

    test("README's Benchmarks section regenerates byte for byte from the committed result document") {
        val readme = repoRoot().resolve("README.md")
        val section = PublishedSummaries.readmeSection(run())

        if (updating) {
            publish(readme, section, PublishedSummaries.README_START, PublishedSummaries.README_END)
            fail(
                "wrote README.md's Benchmarks section from the committed result document -- " +
                    "re-run without -Dcontextgraph.publishedSummaries.update=true to confirm the write reproduces green"
            )
        }

        val published = readme.readText()
        val regenerated = PublishedSummaries.upsert(
            published,
            section,
            PublishedSummaries.README_START,
            PublishedSummaries.README_END
        )

        regenerated shouldBe published
        regenerated.toByteArray(Charsets.UTF_8).toList() shouldBe published.toByteArray(Charsets.UTF_8).toList()
    }

    test("the site's introduction paragraph regenerates byte for byte from the committed result document") {
        val page = repoRoot().resolve("docs/getting-started/introduction/index.html")
        val paragraph = PublishedSummaries.siteIntroParagraph(run())

        if (updating) {
            publish(page, paragraph, PublishedSummaries.SITE_START, PublishedSummaries.SITE_END)
            fail(
                "wrote docs/getting-started/introduction/index.html's digest paragraph from the " +
                    "committed result document -- re-run without -Dcontextgraph.publishedSummaries.update=true " +
                    "to confirm the write reproduces green"
            )
        }

        val published = page.readText()
        val regenerated = PublishedSummaries.upsert(
            published,
            paragraph,
            PublishedSummaries.SITE_START,
            PublishedSummaries.SITE_END
        )

        regenerated shouldBe published
    }

    test("the check has teeth: both documents really do carry the generated markers") {
        // Without this, a document whose markers had been deleted would make `upsert` throw --
        // which is a loud failure and fine -- but a document whose markers sat adjacent, wrapping
        // nothing, would compare equal to itself-plus-an-empty-block and pass vacuously.
        val readme = repoRoot().resolve("README.md").readText()
        val page = repoRoot().resolve("docs/getting-started/introduction/index.html").readText()

        readme.substringAfter(PublishedSummaries.README_START).substringBefore(PublishedSummaries.README_END)
            .shouldContain("## Benchmarks")
        page.substringAfter(PublishedSummaries.SITE_START).substringBefore(PublishedSummaries.SITE_END)
            .shouldContain("<p>")
    }

    test("upsert refuses a document with no block to replace, rather than appending one") {
        // RetrievalReportGenerator owns its whole file and may append. This one edits one section
        // of a document a human wrote the rest of: appending a Benchmarks section below
        // "Building" would be a silent wrong answer where a thrown exception is a loud right one.
        shouldThrow<IllegalArgumentException> {
            PublishedSummaries.upsert(
                "# A README with no markers\n",
                "## Benchmarks\n",
                PublishedSummaries.README_START,
                PublishedSummaries.README_END
            )
        }
    }

    test("a row this project loses is bolded for the side that won it and named in prose") {
        // The guarantee that makes this section publishable at all: the losing rows are rendered
        // by the same code as the winning ones, from the same figures, so no future edit can
        // quietly drop one. Asserted on a synthetic run rather than on the committed one so it
        // still holds when the real numbers move.
        val losing = SideAggregate(2, mapOf(5 to 0.1), mapOf(5 to 0.1), mrr = 0.100)
        val winning = SideAggregate(2, mapOf(5 to 0.9), mapOf(5 to 0.9), mrr = 0.900)
        val aggregate = RetrievalAggregate(
            questionCount = 2,
            contextGraph = losing,
            ripgrep = losing,
            codeGraph = winning,
            bash = losing
        )
        val run = RetrievalRun(
            runId = "retrieval-synthetic",
            generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            kValues = listOf(5),
            summary = RetrievalSummary(
                headline = aggregate,
                negativeControl = aggregate,
                byCategory = emptyMap(),
                byRepo = mapOf("somerepo" to aggregate)
            )
        )

        val section = PublishedSummaries.readmeSection(run)

        section shouldContain "| `somerepo` (2) | MRR | 0.100 | **0.900** | 0.100 | 0.100 |"
        // Prose is hard-wrapped to the width the rest of the README uses, so the sentence is
        // asserted against the unwrapped text rather than against wherever the breaks landed.
        section.replace("\n", " ") shouldContain
            "on `somerepo` ${RetrievalSide.CODE_GRAPH.label} leads on MRR, 0.900 against 0.100"
    }

    test("the grep-beats-the-index sting is pinned to the BASH side, not to a label that happens to match it") {
        // H1: the sting sentence used to key off `RetrievalSide.BASH.label in it.leaders`, a
        // string built from a local label constant compared against an enum's label -- true only
        // because the two constants were never deliberately allowed to diverge (unlike ripgrep's,
        // which the same file re-words on purpose). This pins the sentence to bash actually
        // leading a row, driven by `RetrievalSide` identity rather than by any string at all, so
        // re-wording a published label can never make it vanish.
        val bashWins = SideAggregate(2, mapOf(5 to 0.9), mapOf(5 to 0.9), mrr = 0.900)
        val everyoneElse = SideAggregate(2, mapOf(5 to 0.1), mapOf(5 to 0.1), mrr = 0.100)
        val aggregate = RetrievalAggregate(
            questionCount = 2,
            contextGraph = everyoneElse,
            ripgrep = everyoneElse,
            codeGraph = everyoneElse,
            bash = bashWins
        )
        val run = RetrievalRun(
            runId = "retrieval-synthetic-bash-wins",
            generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            kValues = listOf(5),
            summary = RetrievalSummary(
                headline = aggregate,
                negativeControl = aggregate,
                byCategory = emptyMap(),
                byRepo = mapOf("somerepo" to aggregate)
            )
        )

        val section = PublishedSummaries.readmeSection(run).replace("\n", " ")

        section shouldContain
            "Plain `grep` pays nothing for an index and still retrieves more than this " +
            "project's index does on `somerepo`."
        // The leader is named by its published label, still driven off the same RetrievalSide.
        section shouldContain "on `somerepo` ${RetrievalSide.BASH.label} leads on MRR"
    }

    test("a loss to CodeGraph alone never triggers the grep-specific sting") {
        // The other half of the same pin: a row this project loses to a side that is NOT bash
        // must not print the grep sting, however similarly the labels might read.
        val losing = SideAggregate(2, mapOf(5 to 0.1), mapOf(5 to 0.1), mrr = 0.100)
        val winning = SideAggregate(2, mapOf(5 to 0.9), mapOf(5 to 0.9), mrr = 0.900)
        val aggregate = RetrievalAggregate(
            questionCount = 2,
            contextGraph = losing,
            ripgrep = losing,
            codeGraph = winning,
            bash = losing
        )
        val run = RetrievalRun(
            runId = "retrieval-synthetic-codegraph-wins",
            generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            kValues = listOf(5),
            summary = RetrievalSummary(
                headline = aggregate,
                negativeControl = aggregate,
                byCategory = emptyMap(),
                byRepo = mapOf("somerepo" to aggregate)
            )
        )

        val section = PublishedSummaries.readmeSection(run)

        section shouldNotContain "Plain `grep` pays nothing for an index"
    }
})
