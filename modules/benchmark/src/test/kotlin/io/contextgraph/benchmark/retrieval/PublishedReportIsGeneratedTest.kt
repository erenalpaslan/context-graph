package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.cli.RepoRoot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * AC-11 turned from a convention into a check.
 *
 * The retrieval section of the published `BENCHMARKS.md` claims, in its own header, that it is
 * generated from the result document and never hand-edited. Until this test existed, that claim
 * rested entirely on whoever last touched the file having regenerated rather than typed -- and the
 * regenerations in the run that produced it were done with throwaway code that is not in the
 * repository, so nothing here could have caught a single hand-edited digit. A number a reader
 * cannot re-derive is a number they have to take on trust, which is the one thing this whole axis
 * exists not to ask of them.
 *
 * So: read the committed result JSON, put it through the **same [RetrievalReportGenerator.generate]
 * / [RetrievalReportGenerator.upsert] pair** `RetrievalCommand` calls when it writes the file, and
 * require the bytes to come back identical. A hand-edit anywhere between the markers fails this. So
 * does a generator change that was never rolled forward into the published rendering -- which is
 * the more likely mistake, and the one that would leave the document quietly describing an older
 * version of itself.
 *
 * **Only the current publication is pinned.** `modules/benchmark/results/` also holds the archives
 * of every earlier run, whose `BENCHMARKS.md` files were rendered by earlier versions of this
 * generator. Those are historical artefacts: regenerating them would rewrite what an earlier run
 * actually published, and pinning them would freeze the generator forever. What is claimed --
 * and therefore what is checked -- is that *this* run's published report is a faithful rendering of
 * *this* run's result document.
 */
class PublishedReportIsGeneratedTest : FunSpec({

    /** The directory holding the report this run published, and the result document behind it. */
    fun publishedDir(): Path = RepoRoot.find(Path.of(System.getProperty("user.dir")))
        .resolve("modules/benchmark/results/four-way")

    fun resultDocument(): Path =
        Files.list(publishedDir()).use { stream ->
            stream.filter { it.name.startsWith("retrieval-") && it.name.endsWith(".json") }
                .sorted()
                .toList()
        }.also {
            // Exactly one, or the pairing is a guess. Two result documents in one directory would
            // mean the published report renders one of them and silently ignores the other.
            check(it.size == 1) { "expected exactly one retrieval result in ${publishedDir()}, found $it" }
        }.single()

    test("the published report regenerates byte for byte from the committed result document") {
        val published = publishedDir().resolve("BENCHMARKS.md").readText()

        val run = RetrievalRun.readFrom(resultDocument())
        val regenerated = RetrievalReportGenerator.upsert(
            published,
            RetrievalReportGenerator.generate(run)
        )

        regenerated shouldBe published
        regenerated.toByteArray(Charsets.UTF_8).toList() shouldBe published.toByteArray(Charsets.UTF_8).toList()
    }

    test("the check has teeth: the published report really does carry a generated retrieval section") {
        // Without this, the assertion above would pass vacuously on a file whose markers had been
        // removed -- `upsert` would append a fresh section rather than replace one, and a
        // comparison of the file against itself-plus-a-section would fail loudly, but a file that
        // had *never* carried the markers would still be reported as "regenerates identically" if
        // the section it grew happened to be empty.
        val published = publishedDir().resolve("BENCHMARKS.md").readText()

        published.contains("<!-- retrieval-axis:start -->") shouldBe true
        published.contains("<!-- retrieval-axis:end -->") shouldBe true
        (published.indexOf("<!-- retrieval-axis:start -->") < published.indexOf("<!-- retrieval-axis:end -->")) shouldBe true
    }
})
