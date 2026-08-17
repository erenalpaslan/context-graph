package io.contextgraph.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Regression test for a real bug found in review: `SqliteStorageAdapter`'s constructor
 * unconditionally creates and schema-migrates whatever path it is handed, and
 * `GraphDb.forRead` used to resolve to the baseline path even when nothing exists there
 * yet. Combined, a read-only command (e.g. `search`) run against a repo with no graph
 * at all would silently materialize `.contextgraph/graph.db` — the file the whole
 * baseline/overlay split exists to make CI the *only* writer of. That defeats the
 * single-writer invariant the split depends on.
 *
 * This runs the real CLI entry point (`MainKt`) in a subprocess against an empty
 * directory, rather than calling command classes in-process, for two reasons: (1) Clikt's
 * `main()` extension calls `exitProcess` on error, which would kill the test JVM if
 * invoked directly, and (2) only a real process boundary proves the file genuinely never
 * touches disk — a mocked or partial invocation could miss a side effect anywhere in the
 * real startup path.
 */
class ReadOnlyCommandsDoNotCreateBaselineTest : FunSpec({

    fun runCli(cwd: Path, vararg args: String): Pair<Int, String> {
        val classpath = System.getProperty("java.class.path")
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            listOf(javaBin, "-cp", classpath, "io.contextgraph.cli.MainKt") + args
        )
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return exitCode to output
    }

    test("search against a repo with neither baseline nor overlay creates no graph.db") {
        val root = Files.createTempDirectory("cli-no-graph-")

        val (exitCode, output) = runCli(root, "search", "anything")

        exitCode shouldBe 1
        output.contains("index", ignoreCase = true) shouldBe true
        root.resolve(".contextgraph").resolve("graph.db").exists() shouldBe false
        root.resolve(".contextgraph").exists() shouldBe false
    }

    test("node, expand, path, report, and export against an empty repo create no graph.db either") {
        val root = Files.createTempDirectory("cli-no-graph-")

        val commands = listOf(
            listOf("node", "some-id"),
            listOf("expand", "some-id"),
            listOf("path", "a", "b"),
            listOf("report"),
            listOf("export")
        )
        for (args in commands) {
            val (exitCode, _) = runCli(root, *args.toTypedArray())
            exitCode shouldBe 1
        }

        root.resolve(".contextgraph").resolve("graph.db").exists() shouldBe false
        root.resolve(".contextgraph").exists() shouldBe false
    }
})
