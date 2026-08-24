package io.contextgraph.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * `contextgraph mcp bind` (spec `release-distribution-chain`, AC-21..24).
 *
 * Tested at the CLI process boundary -- the same seam [FreshnessTest] uses -- because the
 * criterion is about what a *caller* sees: what lands on stdout, what lands on stderr, and
 * what the exit code is. A test that called the command class directly could not observe the
 * stream split, which is the whole point of AC-21: `contextgraph mcp bind > .mcp.json` has to
 * produce a valid file, so nothing but the JSON may reach stdout.
 *
 * [FreshnessTest]'s own `runCli` merges stderr into stdout (`redirectErrorStream(true)`),
 * which would make that split unobservable, so this file keeps the streams apart.
 */
class McpBindTest : FunSpec({

    /**
     * Runs the real `MainKt` entry point in a subprocess, keeping stdout and stderr separate.
     *
     * Both streams are redirected to files rather than read from pipes: reading one pipe to
     * EOF before the other can deadlock on a child that fills the second one, and it would
     * also make the timeout below unreachable, since the read would block until the child
     * exited anyway.
     */
    fun runCli(cwd: Path, vararg args: String): Triple<Int, String, String> {
        val classpath = System.getProperty("java.class.path")
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val out = File.createTempFile("cli-stdout", ".txt").also { it.deleteOnExit() }
        val err = File.createTempFile("cli-stderr", ".txt").also { it.deleteOnExit() }

        val process = ProcessBuilder(
            listOf(javaBin, "-cp", classpath, "io.contextgraph.cli.MainKt") + args
        ).directory(cwd.toFile())
            .redirectOutput(out)
            .redirectError(err)
            .start()

        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return Triple(-1, out.readText(), "TIMED OUT")
        }
        return Triple(process.exitValue(), out.readText(), err.readText())
    }

    test("mcp bind prints the Claude Code mcpServers block on stdout and exits 0") {
        val dir = tempdir().toPath()

        val (exit, stdout, _) = runCli(dir, "mcp", "bind")

        exit shouldBe 0

        // Parsing (rather than string-matching) is what proves the pipe-clean property:
        // anything else printed to stdout would make this throw.
        val parsed = Json.parseToJsonElement(stdout).jsonObject
        val server = parsed["mcpServers"]!!.jsonObject["contextgraph"]!!.jsonObject

        server["command"]!!.jsonPrimitive.content shouldBe "sh"
        server["args"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf(
            "-c",
            "cd \"\$(git rev-parse --show-toplevel 2>/dev/null || pwd)\" && exec contextgraph serve-mcp"
        )
    }

    test("mcp bind emits the same bytes from a bare directory and from a git repo that already has a graph") {
        // AC-22: the command inspects nothing, so two structurally different working
        // directories must produce byte-identical output. Comparing a bare temp directory
        // against a real git repository with a populated `.contextgraph/` is what makes this
        // falsifiable -- were the output ever derived from the surroundings (an absolute path,
        // a detected root, a "no graph found" note), these two would differ.
        val bare = tempdir().toPath()

        val repo = tempdir().toPath()
        val gitInit = ProcessBuilder("git", "init").directory(repo.toFile()).start()
        gitInit.waitFor(60, TimeUnit.SECONDS) shouldBe true
        // Checked, not assumed: a failed `git init` would quietly turn this arm into a second
        // bare directory, and the comparison would then pass while proving nothing.
        gitInit.exitValue() shouldBe 0
        Files.createDirectories(repo.resolve(".contextgraph"))
        Files.writeString(repo.resolve(".contextgraph").resolve("config.json"), "{}")

        val (bareExit, bareOut, bareErr) = runCli(bare, "mcp", "bind")
        val (repoExit, repoOut, repoErr) = runCli(repo, "mcp", "bind")

        repoOut shouldBe bareOut
        repoExit shouldBe 0
        bareExit shouldBe 0

        // Nothing may reach stderr either: `contextgraph mcp bind > .mcp.json` is the
        // documented usage, and a warning printed alongside it would be noise a user cannot
        // silence.
        bareErr shouldBe ""
        repoErr shouldBe ""
    }

    test("mcp without a subcommand lists bind") {
        // AC-24. `mcp` is a group, so discovering `bind` has to be possible from the CLI
        // itself rather than only from the README.
        val (_, stdout, stderr) = runCli(tempdir().toPath(), "mcp")

        (stdout + stderr) shouldContain "bind"
    }
})
