package io.contextgraph.benchmark.retrieval

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.opentest4j.TestAbortedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Exercises [RipgrepBaselineRunner] against the real `rg` binary and a tiny, fast, offline
 * fixture (not the real multi-hundred-MB corpus). Skipped -- not silently passed, see
 * [TestAbortedException] below -- on a machine with no `rg` on PATH, the same reasoning
 * `LiveSmokeOrchestrationTest` gives for its own env-gated skip: this must never make
 * `./gradlew build`/`check` depend on a tool this project does not otherwise require.
 */
class RipgrepBaselineRunnerTest : FunSpec({

    fun skipIfRgMissing() {
        if (!RipgrepProcess.isAvailable("rg")) {
            throw TestAbortedException("Skipped: 'rg' not found on PATH -- install ripgrep to run this test.")
        }
    }

    fun fixture(): Path {
        val root = Files.createTempDirectory("ripgrep-baseline-fixture-")
        root.resolve("gin.go").writeText(
            "package gin\n\nfunc (engine *Engine) ServeHTTP(w http.ResponseWriter, req *http.Request) {\n" +
                "\tengine.handleHTTPRequest(c)\n}\n"
        )
        root.resolve("other.go").writeText("package gin\n\nfunc unrelated() {}\n")
        return root
    }

    test("finds the file containing the derived token and ranks it, ignoring an unrelated file") {
        skipIfRgMissing()
        val root = fixture()
        try {
            val runner = RipgrepBaselineRunner()
            val outcome = runner.rankedFiles("What does Engine.ServeHTTP call?", root)

            outcome.tokens shouldContain "ServeHTTP"
            outcome.rankedFiles shouldContain "gin.go"
            (outcome.rankedFiles.contains("other.go")) shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("a question with no derivable tokens never invokes rg and returns an empty result") {
        skipIfRgMissing()
        val root = fixture()
        try {
            val runner = RipgrepBaselineRunner()
            val outcome = runner.rankedFiles(
                "Trace the call chain from a Ctrl+Z keydown through to the undo stack.",
                root
            )
            outcome.tokens.shouldBeEmpty()
            outcome.rankedFiles.shouldBeEmpty()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("deterministic: the same question against the same checkout always ranks files the same way") {
        skipIfRgMissing()
        val root = fixture()
        try {
            val runner = RipgrepBaselineRunner()
            val first = runner.rankedFiles("What does Engine.ServeHTTP call?", root)
            val second = runner.rankedFiles("What does Engine.ServeHTTP call?", root)
            first shouldBe second
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
