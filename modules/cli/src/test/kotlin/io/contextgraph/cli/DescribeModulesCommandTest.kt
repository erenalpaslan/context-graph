package io.contextgraph.cli

import io.contextgraph.core.GraphDb
import io.contextgraph.core.NodeType
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * End-to-end coverage of `describe-modules` through the real CLI entry point (`MainKt`), the
 * same subprocess pattern [ReadOnlyCommandsDoNotCreateBaselineTest] uses -- Clikt's `main()`
 * extension calls `exitProcess`, so only a real process boundary can observe the command's real
 * exit code without killing the test JVM.
 *
 * This is also where the slice's non-negotiable acceptance criteria get exercised against the
 * actual production wiring, not just the service in isolation (see
 * `ModuleDescriptionServiceTest`, `LiteLlmModuleDescriberTest`, `SymbolInventoryTest` in
 * `modules/ingest`): running with no LiteLLM credentials at all -- the CI-representative case --
 * must still index a complete structural graph and exit 0, leaving modules undescribed rather
 * than failing.
 */
class DescribeModulesCommandTest : FunSpec({

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

    test("describe-modules against a repo with no graph exits 1 and creates no graph.db") {
        val root = Files.createTempDirectory("cli-describe-modules-no-graph-")

        val (exitCode, output) = runCli(root, "describe-modules")

        exitCode shouldBe 1
        output.contains("index", ignoreCase = true) shouldBe true
        root.resolve(".contextgraph").resolve("graph.db").exists() shouldBe false
        root.resolve(".contextgraph").exists() shouldBe false
    }

    test(
        "with LiteLLM disabled (the default, keyless config), index then describe-modules " +
            "exits 0, leaves the module undescribed, and writes no file into the source tree"
    ) {
        val root = Files.createTempDirectory("cli-describe-modules-disabled-")
        root.resolve("settings.gradle.kts").writeText("""include(":app")""")
        root.resolve("app").createDirectories()
        root.resolve("app/App.java").writeText(
            """
            package app;
            public class App {}
            """.trimIndent()
        )

        val (indexExit, indexOutput) = runCli(root, "index")
        indexExit shouldBe 0
        indexOutput.contains("Failed", ignoreCase = false) shouldBe false

        val (describeExit, describeOutput) = runCli(root, "describe-modules")

        describeExit shouldBe 0
        describeOutput.contains("Undescribed:  1") shouldBe true
        describeOutput.contains("litellm.enabled is false") shouldBe true

        // Nothing beyond .contextgraph/ was written -- no module_architecture.md or similar
        // landed in the source tree the way a human-authored description document would.
        val sourceTreeFiles = Files.walk(root).use { walk ->
            walk.filter { it.toFile().isFile && !it.startsWith(root.resolve(".contextgraph")) }
                .map { root.relativize(it).toString() }
                .toList()
        }
        sourceTreeFiles.shouldNotContainDescriptionArtifact()

        val storage = SqliteStorageAdapter(GraphDb.forRead(root))
        try {
            val moduleNode = storage.getAllNodes().first { it.type == NodeType.CodeModule }
            moduleNode.properties.containsKey("description") shouldBe false
            (moduleNode.properties["undescribed"] as kotlinx.serialization.json.JsonPrimitive).content shouldBe "true"
        } finally {
            storage.close()
        }
    }
})

private fun List<String>.shouldNotContainDescriptionArtifact() {
    this.none { it.contains("module_architecture", ignoreCase = true) } shouldBe true
}
