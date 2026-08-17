package io.contextgraph.cli

import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.GraphDb
import io.contextgraph.ingest.ReindexPrimitive
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Slice 13 (freshness triggers). Covers the three wrappers over [ReindexPrimitive]:
 *
 *  - The CLI (`refresh`) and CI (`ci-reindex`) never target the wrong file -- proven by
 *    running the real `MainKt` entry point in a subprocess, the same technique
 *    [ReadOnlyCommandsDoNotCreateBaselineTest] uses, since Clikt's `main()` calls
 *    `exitProcess` on error and only a real process boundary proves a file genuinely was
 *    or wasn't written.
 *  - [FileWatcher] triggers a reindex from a real filesystem event with no explicit
 *    command, and never starts unless asked.
 *  - [ReindexPrimitive]'s locking keeps two triggers landing on the same overlay from
 *    corrupting it or deadlocking.
 *  - [ciWriteAllowed] is the pure predicate behind CI's TTY refusal.
 */
private val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class FreshnessTest : FunSpec({

    fun runCli(cwd: Path, env: Map<String, String> = emptyMap(), vararg args: String): Pair<Int, String> {
        val classpath = System.getProperty("java.class.path")
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val builder = ProcessBuilder(
            listOf(javaBin, "-cp", classpath, "io.contextgraph.cli.MainKt") + args
        )
            .directory(cwd.toFile())
            .redirectErrorStream(true)
        builder.environment().putAll(env)
        val process = builder.start()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return -1 to "TIMED OUT"
        }
        val output = process.inputStream.bufferedReader().readText()
        return process.exitValue() to output
    }

    fun writeSourceFile(root: Path, relativePath: String, content: String) {
        val file = root.resolve(relativePath)
        Files.createDirectories(file.parent)
        file.writeText(content)
    }

    test("refresh writes only the local overlay; a pre-existing committed baseline is left untouched") {
        val root = Files.createTempDirectory("freshness-refresh-")
        writeSourceFile(root, "README.md", "# Hello\n\nSome content for indexing.\n")

        // A baseline that would already exist if CI had run before.
        val (initExit, _) = runCli(root, args = arrayOf("init"))
        initExit shouldBe 0
        val baselinePath = GraphDb.baseline(root)
        Files.createDirectories(baselinePath.parent)
        io.contextgraph.storage.SqliteStorageAdapter(baselinePath).close()
        val baselineModifiedBefore = Files.getLastModifiedTime(baselinePath)

        val (exit, output) = runCli(root, args = arrayOf("refresh"))
        exit shouldBe 0
        output shouldContain "Refreshing"

        GraphDb.overlay(root).exists().shouldBeTrue()
        Files.getLastModifiedTime(baselinePath) shouldBe baselineModifiedBefore
    }

    test("refresh re-parses only changed files: a second run with nothing changed skips the file it already indexed") {
        val root = Files.createTempDirectory("freshness-refresh-incremental-")
        writeSourceFile(root, "README.md", "# Hello\n\nSome content for indexing.\n")

        val (firstExit, firstOutput) = runCli(root, args = arrayOf("refresh"))
        firstExit shouldBe 0
        // README.md matches both MarkdownExtractor and SemanticExtractor (SemanticExtractor
        // no-ops with litellm disabled), so one file yields two extraction results -- this
        // assertion only pins down that *something* got processed the first time round.
        val firstDoneLine = firstOutput.lines().single { it.startsWith("Done:") }
        val firstArtifacts = Regex("""Done: (\d+) artifacts""").find(firstDoneLine)!!.groupValues[1].toInt()
        firstArtifacts shouldBeGreaterThan 0

        val (secondExit, secondOutput) = runCli(root, args = arrayOf("refresh"))
        secondExit shouldBe 0
        // Re-parsing only changed files, per AC-26: with nothing changed, the one file present
        // is skipped rather than re-extracted -- checksum comparison happens once per file,
        // independent of how many extractors match it.
        secondOutput shouldContain "Skipped (unchanged): 1"
        secondOutput.contains("Done: 0 artifacts") shouldBe true

        // Leaves the graph matching the working tree: a newly added file is picked up on the
        // very next refresh, while the untouched one is still skipped.
        writeSourceFile(root, "MORE.md", "# More\n\nA second file added after the first refresh.\n")
        val (thirdExit, thirdOutput) = runCli(root, args = arrayOf("refresh"))
        thirdExit shouldBe 0
        thirdOutput shouldContain "Skipped (unchanged): 1"
        val thirdDoneLine = thirdOutput.lines().single { it.startsWith("Done:") }
        val thirdArtifacts = Regex("""Done: (\d+) artifacts""").find(thirdDoneLine)!!.groupValues[1].toInt()
        thirdArtifacts shouldBe firstArtifacts
    }

    test("ci-reindex (headless, no console attached to a subprocess) writes the committed baseline, never the overlay") {
        val root = Files.createTempDirectory("freshness-ci-")
        writeSourceFile(root, "README.md", "# Hello\n\nSome content for indexing.\n")

        val (exit, output) = runCli(root, args = arrayOf("ci-reindex"))
        exit shouldBe 0
        output shouldContain "\"status\":\"ok\""

        GraphDb.baseline(root).exists().shouldBeTrue()
        GraphDb.overlay(root).exists().shouldBeFalse()
    }

    test("ci-reindex emits machine-readable JSON with artifact/node/edge counts") {
        val root = Files.createTempDirectory("freshness-ci-json-")
        writeSourceFile(root, "README.md", "# Hello\n\nSome content for indexing.\n")

        val (exit, output) = runCli(root, args = arrayOf("ci-reindex"))
        exit shouldBe 0
        val jsonLine = output.lines().singleOrNull { it.trim().startsWith("{") }
        jsonLine.shouldNotBeNull()
        val parsed = testJson.decodeFromString(CiReindexResult.serializer(), jsonLine!!.trim())
        parsed.status shouldBe "ok"
        parsed.artifacts shouldBeGreaterThan 0
    }

    test("a CI run with LiteLLM enabled in the committed config still succeeds with no credentials configured") {
        val root = Files.createTempDirectory("freshness-ci-litellm-")
        writeSourceFile(root, "README.md", "# Hello\n\nSome content for indexing.\n")
        Files.createDirectories(root.resolve(".contextgraph"))
        val config = ContextGraphConfig(litellm = ContextGraphConfig().litellm.copy(enabled = true))
        root.resolve(".contextgraph/config.json").writeText(
            testJson.encodeToString(ContextGraphConfig.serializer(), config)
        )

        val (exit, output) = runCli(root, args = arrayOf("ci-reindex"))
        exit shouldBe 0
        output shouldContain "\"status\":\"ok\""
    }

    test("watch exits immediately without starting anything when watcher.enabled is false (the default)") {
        val root = Files.createTempDirectory("freshness-watch-disabled-")
        val (exit, output) = runCli(root, args = arrayOf("watch"))
        exit shouldBe 0
        output shouldContain "disabled"
        // No watcher process left anything behind: no overlay, no lock file.
        GraphDb.overlay(root).exists().shouldBeFalse()
    }

    test("ciWriteAllowed refuses an interactive terminal unless explicitly forced, and always allows headless") {
        ciWriteAllowed(hasConsole = false, forceEnv = null).shouldBeTrue()
        ciWriteAllowed(hasConsole = true, forceEnv = null).shouldBeFalse()
        ciWriteAllowed(hasConsole = true, forceEnv = "true").shouldBeTrue()
        ciWriteAllowed(hasConsole = true, forceEnv = "false").shouldBeFalse()
    }

    test("FileWatcher: with the watcher enabled, creating a source file updates the graph with no explicit command") {
        val root = Files.createTempDirectory("freshness-filewatcher-")
        val dbPath = GraphDb.overlay(root)
        val config = ContextGraphConfig()

        val watcher = FileWatcher(
            root = root,
            config = config,
            dbPath = dbPath,
            debounceMillis = 150,
            fallbackIntervalMillis = 60_000
        )

        val reindexed = CountDownLatch(1)
        var lastArtifactCount = 0
        val thread = Thread {
            watcher.run { stats ->
                lastArtifactCount = stats.artifactCount
                if (stats.artifactCount > 0) reindexed.countDown()
            }
        }
        thread.isDaemon = true
        thread.start()

        try {
            // Give the watcher a moment to finish its initial directory registration before
            // the file shows up, then create a new source file -- no CLI command involved.
            Thread.sleep(300)
            writeSourceFile(root, "docs/NOTES.md", "# Notes\n\nWritten while the watcher was running.\n")

            val fired = reindexed.await(15, TimeUnit.SECONDS)
            fired.shouldBeTrue()
            lastArtifactCount shouldBeGreaterThan 0
            dbPath.exists().shouldBeTrue()
        } finally {
            watcher.stop()
            thread.interrupt()
            thread.join(5_000)
        }
    }

    test("ReindexPrimitive: two triggers firing close together on the same overlay complete without corrupting the database or deadlocking") {
        val root = Files.createTempDirectory("freshness-concurrency-")
        writeSourceFile(root, "README.md", "# Hello\n\nConcurrency test content.\n")
        val dbPath = GraphDb.overlay(root)
        val config = ContextGraphConfig()

        val results = runBlocking(Dispatchers.IO) {
            val a = async { ReindexPrimitive.run(root, config, dbPath) }
            val b = async { ReindexPrimitive.run(root, config, dbPath) }
            awaitAll(a, b)
        }

        // Both callers completed (no exception, no hang -- the test itself times out otherwise)
        // and the database is left in a readable, consistent state. Whichever caller the
        // lock let through first does the real work (artifactCount > 0); the other finds
        // the same checksum already stored and legitimately skips it -- that is the
        // checksum-skip mechanism working correctly under contention, not corruption.
        results.size shouldBe 2
        results.forEach { (it.artifactCount + it.skipped) shouldBeGreaterThan 0 }
        results.sumOf { it.artifactCount } shouldBeGreaterThan 0

        val reopened = io.contextgraph.storage.SqliteStorageAdapter(dbPath)
        try {
            reopened.getAllArtifacts().size shouldBeGreaterThan 0
        } finally {
            reopened.close()
        }
    }
})
