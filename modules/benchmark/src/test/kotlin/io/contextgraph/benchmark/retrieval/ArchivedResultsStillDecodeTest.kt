package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.cli.RepoRoot
import io.contextgraph.benchmark.model.BenchmarkRun
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * The archive is evidence, and this is what keeps it readable.
 *
 * `modules/benchmark/results/` accumulates every result the suite has ever produced, and those
 * files are cited by reports and by the research runs that read them. Adding a third comparator
 * changed both result shapes, and the failure mode of getting that wrong is quiet: a required
 * field added anywhere in the tree -- including *inside* `summary`, several levels down -- makes
 * an old file stop decoding, and nothing notices until someone tries to read a result from
 * months ago and gets an exception instead of a number.
 *
 * So this test reads the **real committed files**, not fixtures. A fixture would prove that the
 * shape decodes; only the real files prove that *these* results still do. It walks the whole
 * tree rather than naming files, so a result added later is covered without anyone remembering
 * to add it here.
 */
class ArchivedResultsStillDecodeTest : FunSpec({

    fun resultsDir(): Path =
        RepoRoot.find(Path.of(System.getProperty("user.dir"))).resolve("modules/benchmark/results")

    fun archivedFiles(prefix: String): List<Path> =
        Files.walk(resultsDir()).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.name.startsWith(prefix) && it.name.endsWith(".json") }
                .sorted()
                .toList()
        }

    test("every archived retrieval result still decodes, and the v1 ones report no CodeGraph side") {
        val files = archivedFiles("retrieval-")
        files.shouldNotBeEmpty()

        files.forEach { file ->
            val run = withClue(file) { RetrievalRun.readFrom(file) }

            if (run.schemaVersion == 1) {
                // A v1 result predates the third comparator entirely. Every added field must read
                // as "absent", never as an empty measurement: `codeGraph == null` says CodeGraph
                // was not in the run, which is the truth, where a zeroed SideResult would say it
                // ran and found nothing.
                run.results.forEach { result -> result.codeGraph shouldBe null }
                run.summary?.headline?.codeGraph shouldBe null
                run.goldFileCoverage shouldBe emptyList()
                run.ingestCosts shouldBe emptyList()
            }
        }
    }

    test("every archived benchmark run still decodes, and the ones predating graphTool report it as null") {
        val files = archivedFiles("run-")
        files.shouldNotBeEmpty()

        files.forEach { file ->
            val run = withClue(file) { BenchmarkRun.readFrom(file) }

            // Not "defaults to CONTEXTGRAPH". Inferring the tool is the report's job, and it says
            // out loud when it inferred -- see BenchmarksReportGenerator. A default here would put
            // the guess in the data, where nothing downstream could tell it from a recorded fact.
            if (!file.toString().contains("graphTool")) {
                run.schemaVersion shouldBe 1
            }
        }
    }

    test("the archive is not empty -- this test would pass vacuously if the results tree moved") {
        // Lower bounds, not exact counts. The tree GROWS every time anyone runs the benchmark --
        // this run alone added two retrieval results -- so pinning an exact number would make a
        // successful measurement look like a regression. What actually needs guarding is that the
        // walk still finds the archive at all: with the directory moved or renamed, every test
        // above would pass while checking nothing.
        //
        // The floors were last reset on 2026-08-26, when the archive was pruned for publication:
        // every result set nothing cited was deleted, leaving the ones the benchmark page, the
        // ablation write-ups and the report code actually read -- 20 retrieval results and 3
        // benchmark runs. Those files are committed, so from here the counts can only rise again.
        (archivedFiles("retrieval-").size >= 20) shouldBe true
        (archivedFiles("run-").size >= 3) shouldBe true
    }

    test("at least one archived retrieval result is still schema v1 -- the case the guard exists for") {
        // If every v1 result were ever regenerated to v2, the backward-compatibility assertions
        // above would silently stop exercising the thing they were written for.
        archivedFiles("retrieval-").any { RetrievalRun.readFrom(it).schemaVersion == 1 } shouldBe true
    }
})

/** Names the file under test in an assertion failure; walking a tree otherwise reports only the value. */
private fun <T> withClue(file: Path, block: () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        throw AssertionError("archived result $file no longer decodes: ${e.message}", e)
    }
