package io.contextgraph.ingest

import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.ExtractionContext
import io.contextgraph.core.ExtractorRegistry
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * Guards the instrument that made a 107x ingest-cost regression visible at all.
 *
 * The failure this protects against is not "the numbers are wrong" -- a wall-clock is not
 * something to assert an exact value on -- but "the numbers quietly stopped being produced".
 * An unmeasured phase reports zero, which reads identically to a phase that was genuinely
 * instant, so the breakdown would go on printing while meaning nothing. These tests assert the
 * shape instead: every phase is wired, the parts plus the stated remainder are exactly the
 * whole, and the remainder is never negative -- a negative one would mean two phases charged
 * themselves the same nanoseconds.
 */
class IngestPhaseTimingTest : FunSpec({

    fun indexFixture(): IndexStats {
        val root = Files.createTempDirectory("ingest-phase-timing")
        root.resolve("settings.gradle.kts").writeText("""include(":app")""")
        root.resolve("README.md").writeText("# A fixture\n\nSome prose so a run has work to do.\n")

        val dbPath = Files.createTempDirectory("ingest-phase-timing-db").resolve("graph.db")
        val storage = SqliteStorageAdapter(dbPath)
        return try {
            val pipeline = IngestPipeline(
                discovery = FileDiscovery(ContextGraphConfig()),
                registry = ExtractorRegistry(emptyList()),
                checksumTracker = ChecksumTracker(),
                storage = storage,
                context = ExtractionContext(root, ContextGraphConfig())
            )
            runBlocking { pipeline.index(root) }
        } finally {
            storage.close()
        }
    }

    test("the run's total is measured and no sequential phase claims more than it") {
        val stats = indexFixture()

        withClue("total nanos") { (stats.totalNanos > 0L) shouldBe true }
        IngestPhase.sequential.forEach { phase ->
            withClue("$phase >= 0") { (stats.nanosOf(phase) >= 0L) shouldBe true }
            withClue("$phase <= total") { (stats.nanosOf(phase) <= stats.totalNanos) shouldBe true }
        }
        // Passes 2 and 3 run unconditionally, so an all-zero split would mean nothing is wired.
        withClue("some sequential phase took measurable time") {
            (IngestPhase.sequential.sumOf { stats.nanosOf(it) } > 0L) shouldBe true
        }
    }

    test("the sequential phases plus the remainder are exactly the total, and the remainder is not negative") {
        val stats = indexFixture()

        val sequential = IngestPhase.sequential.sumOf { stats.nanosOf(it) }
        (sequential + stats.unattributedNanos) shouldBe stats.totalNanos
        withClue("remainder not negative (would mean two phases double-counted)") {
            (stats.unattributedNanos >= 0L) shouldBe true
        }
    }

    test("the breakdown names every phase and the remainder") {
        val stats = indexFixture()
        val report = stats.timingReport()

        withClue("report is not empty") { report.isNotEmpty() shouldBe true }
        (IngestPhase.sequential + IngestPhase.withinPass1).forEach { phase ->
            withClue("report mentions $phase") { report.any { it.contains(phase.label) } shouldBe true }
        }
        withClue("report states the remainder") { report.any { it.contains("unattributed") } shouldBe true }
        withClue("first line carries the total") { report.first().contains("total") shouldBe true }
    }

    test("a run that was never timed reports nothing rather than a page of zeroes") {
        IndexStats().timingReport() shouldBe emptyList()
    }
})
