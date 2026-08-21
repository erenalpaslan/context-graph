package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.corpus.CorpusIndexer
import io.contextgraph.benchmark.corpus.LocalGitFixture
import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.Evidence
import io.contextgraph.benchmark.model.GoldFact
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * End-to-end proof of the retrieval axis's most load-bearing property (AC-24's "aynı girdi aynı
 * sayıyı verir" -- same input, same number): both the ContextGraph side (a real index, built by
 * the real [CorpusIndexer] the WITH arm uses, queried through the real
 * [io.contextgraph.query.QueryEngine]) and the whole [RetrievalBenchmarkRunner] pipeline run
 * against a tiny, fast, offline fixture -- never the real multi-hundred-MB corpus, so this test
 * runs unconditionally as part of `./gradlew build`/`check` the same way
 * `McpToolBridgeReadsIndexedGraphTest` does for the agent-A/B axis's WITH_TOOLS bridge.
 *
 * The fixture question's text (`"foo"` -- a single word, not a full sentence, the same shape
 * `McpToolBridgeReadsIndexedGraphTest` uses for the same reason: SqliteStorageAdapter.searchNodes
 * runs the question text as a literal SQLite FTS5 MATCH query with implicit-AND semantics
 * across every bareword token in it, so a full natural-language sentence usually matches
 * nothing at all -- a real, separate finding about buildContext's search surface that belongs
 * in the retrieval report generated from the real corpus, not something this wiring test
 * should fight around) is also chosen so its ripgrep side derives
 * *zero* tokens (`foo` is a plain lowercase word, not code-shaped by
 * [RipgrepQueryDeriver]'s heuristic) -- so this test never actually shells out to `rg` and has
 * no dependency on it being installed, while still exercising the real (empty-result) code path
 * end to end. [RipgrepBaselineRunnerTest] separately covers `rg` actually finding something,
 * gated on `rg`'s availability.
 */
class RetrievalBenchmarkRunnerTest : FunSpec({

    fun buildFixtureCorpus(): Triple<java.nio.file.Path, List<Question>, List<CorpusRepo>> {
        val corpusRoot = Files.createTempDirectory("retrieval-runner-fixture-")
        val withDir = corpusRoot.resolve("fixture-repo").resolve("with")
        val withoutDir = corpusRoot.resolve("fixture-repo").resolve("without")

        // Two independent copies of the same fixture content -- only "with" gets indexed,
        // mirroring the real corpus's WITH/WITHOUT split (AC-1a).
        LocalGitFixture.create(withDir)
        LocalGitFixture.create(withoutDir)
        CorpusIndexer.index("fixture-repo", withDir)

        val question = Question(
            id = "fixture-q1",
            repoId = "fixture-repo",
            text = "foo",
            category = QuestionCategory.GRAPH_HEAVY,
            goldFacts = listOf(
                GoldFact("fixture-q1-f1", "foo returns 42", Evidence.parse("src/Foo.kt:1")),
                GoldFact("fixture-q1-f2", "foo is declared in Foo.kt", Evidence.parse("src/Foo.kt:1")),
                GoldFact("fixture-q1-f3", "foo has no parameters", Evidence.parse("src/Foo.kt:1"))
            )
        )
        val catalog = listOf(
            CorpusRepo(id = "fixture-repo", name = "fixture-repo", url = "local", pinnedTag = "v1.0.0", pinnedSha = "n/a")
        )
        return Triple(corpusRoot, listOf(question), catalog)
    }

    test("scores both sides against a real index and a real (empty-token) ripgrep pass, end to end") {
        val (corpusRoot, questions, catalog) = buildFixtureCorpus()
        try {
            val runner = RetrievalBenchmarkRunner(corpusRoot, questions, catalog, kValues = listOf(5))
            val run = runner.run()

            run.results shouldHaveSize 1
            run.skippedRepos shouldBe emptyList()

            val result = run.results.single()
            result.expectedFiles shouldBe listOf("src/Foo.kt")
            result.ripgrepQueryTokens shouldBe emptyList()
            result.ripgrep.rankedFiles shouldBe emptyList()

            // The real index, queried for real: buildContext("foo") must find
            // the indexed foo() symbol and report its file as evidence.
            val contextGraph = result.contextGraph
            (contextGraph != null) shouldBe true
            contextGraph!!.rankedFiles shouldContain "src/Foo.kt"
        } finally {
            corpusRoot.toFile().deleteRecursively()
        }
    }

    test("deterministic: running the same measurement twice against the same corpus yields identical results") {
        val (corpusRoot, questions, catalog) = buildFixtureCorpus()
        try {
            val runner = RetrievalBenchmarkRunner(corpusRoot, questions, catalog, kValues = listOf(5))
            val first = runner.run()
            val second = runner.run()

            first.results shouldBe second.results
            first.skippedRepos shouldBe second.skippedRepos
            first.summary shouldBe second.summary
        } finally {
            corpusRoot.toFile().deleteRecursively()
        }
    }

    test("a repo with no WITHOUT working copy at all is skipped entirely, both sides, not silently") {
        val corpusRoot = Files.createTempDirectory("retrieval-runner-missing-without-")
        try {
            val withDir = corpusRoot.resolve("fixture-repo").resolve("with")
            LocalGitFixture.create(withDir)
            CorpusIndexer.index("fixture-repo", withDir)
            // Deliberately never create the "without" directory.

            val question = Question(
                id = "q1",
                repoId = "fixture-repo",
                text = "foo",
                category = QuestionCategory.GRAPH_HEAVY,
                goldFacts = listOf(
                    GoldFact("q1-f1", "s1", Evidence.parse("src/Foo.kt:1")),
                    GoldFact("q1-f2", "s2", Evidence.parse("src/Foo.kt:1")),
                    GoldFact("q1-f3", "s3", Evidence.parse("src/Foo.kt:1"))
                )
            )
            val catalog = listOf(
                CorpusRepo(id = "fixture-repo", name = "fixture-repo", url = "local", pinnedTag = "v1.0.0", pinnedSha = "n/a")
            )

            val runner = RetrievalBenchmarkRunner(corpusRoot, listOf(question), catalog, kValues = listOf(5))
            val run = runner.run()

            run.results shouldBe emptyList()
            run.skippedRepos shouldHaveSize 1
            run.skippedRepos.single().repoId shouldBe "fixture-repo"
        } finally {
            corpusRoot.toFile().deleteRecursively()
        }
    }

    test("a repo whose index is missing/incomplete skips only the ContextGraph side -- ripgrep still measured") {
        val corpusRoot = Files.createTempDirectory("retrieval-runner-bad-index-")
        try {
            val withDir = corpusRoot.resolve("fixture-repo").resolve("with")
            val withoutDir = corpusRoot.resolve("fixture-repo").resolve("without")
            // "with" exists on disk but is never indexed at all -- IndexIntegrityGate must reject it.
            Files.createDirectories(withDir)
            LocalGitFixture.create(withoutDir)

            val question = Question(
                id = "q1",
                repoId = "fixture-repo",
                text = "foo",
                category = QuestionCategory.GRAPH_HEAVY,
                goldFacts = listOf(
                    GoldFact("q1-f1", "s1", Evidence.parse("src/Foo.kt:1")),
                    GoldFact("q1-f2", "s2", Evidence.parse("src/Foo.kt:1")),
                    GoldFact("q1-f3", "s3", Evidence.parse("src/Foo.kt:1"))
                )
            )
            val catalog = listOf(
                CorpusRepo(id = "fixture-repo", name = "fixture-repo", url = "local", pinnedTag = "v1.0.0", pinnedSha = "n/a")
            )

            val runner = RetrievalBenchmarkRunner(corpusRoot, listOf(question), catalog, kValues = listOf(5))
            val run = runner.run()

            run.results shouldHaveSize 1
            run.results.single().contextGraph shouldBe null
            run.skippedRepos shouldHaveSize 1
            run.skippedRepos.single().reason.contains("ContextGraph side skipped") shouldBe true
        } finally {
            corpusRoot.toFile().deleteRecursively()
        }
    }
})
