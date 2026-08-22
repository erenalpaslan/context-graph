package io.contextgraph.benchmark.retrieval

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The parser is tested against **real committed `codegraph explore` output**, not hand-written
 * markdown, because the failure this guards against is precisely that the real format differs
 * from what someone assumed it looked like. See `src/test/resources/codegraph/README.md` for how
 * each fixture was captured, and which one is synthesized and why.
 *
 * A parser that quietly matches nothing publishes as "CodeGraph retrieves nothing" -- a
 * fabricated win for ContextGraph, in a comparison whose entire purpose is that the answer might
 * be CodeGraph. So these assert exact lists, not "not empty".
 */
class CodeGraphExploreParserTest : FunSpec({

    fun fixture(name: String): String =
        checkNotNull(this::class.java.getResourceAsStream("/codegraph/$name")) {
            "missing fixture /codegraph/$name"
        }.bufferedReader().readText()

    test("a real two-file explore output yields both paths, repo-relative, in emission order") {
        val output = fixture("explore-small.md")

        CodeGraphExploreParser.rankedFiles(output) shouldContainExactly listOf(
            "src/main/kotlin/com/example/UserService.kt",
            "src/main/kotlin/com/example/AuthController.kt"
        )
    }

    test("a real larger explore output yields exactly the files it rendered source for, in order") {
        val output = fixture("explore-large.md")

        // CodeGraph's own header says "Found 6 symbols across 4 files" -- the count is driven by
        // the paths it actually rendered, so four is the number the tool itself claims.
        output shouldContain "across 4 files"

        CodeGraphExploreParser.rankedFiles(output) shouldContainExactly listOf(
            "src/main/kotlin/io/contextgraph/benchmark/retrieval/RetrievalBenchmarkRunner.kt",
            "src/main/kotlin/io/contextgraph/benchmark/retrieval/RipgrepBaselineRunner.kt",
            "src/main/kotlin/io/contextgraph/benchmark/retrieval/RipgrepProcess.kt",
            "src/main/kotlin/io/contextgraph/benchmark/retrieval/RipgrepQueryDeriver.kt"
        )
    }

    test("blast-radius annotations are not counted as results, and the fixture really does contain some") {
        val output = fixture("explore-large.md")

        // Guard the guard: if a future fixture had an empty blast-radius section this test would
        // pass vacuously while proving nothing about the exclusion.
        output shouldContain "Blast radius"
        output shouldContain "src/main/kotlin/io/contextgraph/benchmark/judge/JudgeScorer.kt"

        val ranked = CodeGraphExploreParser.rankedFiles(output)
        ranked shouldNotContain "src/main/kotlin/io/contextgraph/benchmark/judge/JudgeScorer.kt"
        ranked shouldNotContain "src/test/kotlin/io/contextgraph/benchmark/judge/JudgeScorerTest.kt"
        ranked shouldNotContain "src/main/kotlin/io/contextgraph/benchmark/orchestrator/BenchmarkOrchestrator.kt"
    }

    test("the low-confidence marker neither truncates the list nor contributes paths of its own") {
        val output = fixture("explore-low-confidence.md")
        output shouldContain CodeGraphExploreParser.LOW_CONFIDENCE_MARKER

        val ranked = CodeGraphExploreParser.rankedFiles(output)

        // Every file section above the marker is still part of the answer -- a parser that
        // stopped at the sentinel would silently drop a ranked list's tail.
        ranked shouldContainExactly listOf(
            "src/main/kotlin/com/example/UserService.kt",
            "src/main/kotlin/com/example/AuthController.kt"
        )

        // The section itself names only DIRECTORIES, as advice about where to look next. Those
        // are not results, and the heading shape is what keeps them out.
        output shouldContain "`codegraph_files` a likely area:"
        ranked shouldNotContain "src/main/kotlin/com/example"
        ranked shouldNotContain "src/test/kotlin/com/example"
    }

    test("a real 'no relevant code found' response is an empty list, not an error") {
        val output = fixture("explore-no-results.md")
        output shouldContain "No relevant code found"

        CodeGraphExploreParser.rankedFiles(output) shouldBe emptyList()
    }

    test("output that is empty, blank, or structureless yields an empty list") {
        CodeGraphExploreParser.rankedFiles("") shouldBe emptyList()
        CodeGraphExploreParser.rankedFiles("   \n\n  ") shouldBe emptyList()
        CodeGraphExploreParser.rankedFiles("some prose with a `path/like.kt` span in it") shouldBe emptyList()
    }

    test("a file rendered under two headings is ranked once, at its first position") {
        val output = """
            **`a/One.kt`** — foo(method)
            **`b/Two.kt`** — bar(method)
            **`a/One.kt`** — baz(method)
        """.trimIndent()

        CodeGraphExploreParser.rankedFiles(output) shouldContainExactly listOf("a/One.kt", "b/Two.kt")
    }

    test("a heading with no symbol suffix still parses, and a leading ./ is stripped") {
        CodeGraphExploreParser.rankedFiles("**`src/Main.kt`**") shouldContainExactly listOf("src/Main.kt")
        CodeGraphExploreParser.rankedFiles("**`./src/Main.kt`** — x(method)") shouldContainExactly listOf("src/Main.kt")
    }

    test("bold prose and section titles are not mistaken for file headings") {
        val output = fixture("explore-large.md")
        val ranked = CodeGraphExploreParser.rankedFiles(output)

        // These are all bold-at-line-start in the real output.
        ranked.none { it.contains("Exploration") } shouldBe true
        ranked.none { it.contains("Source Code") } shouldBe true
        ranked.none { it.contains("Blast radius") } shouldBe true
    }
})
