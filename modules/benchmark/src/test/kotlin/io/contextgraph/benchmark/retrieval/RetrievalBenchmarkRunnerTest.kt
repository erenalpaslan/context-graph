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

    test("scores the ContextGraph and ripgrep sides end to end, and says out loud why CodeGraph's is absent") {
        val (corpusRoot, questions, catalog) = buildFixtureCorpus()
        try {
            val runner = RetrievalBenchmarkRunner(corpusRoot, questions, catalog, kValues = listOf(5))
            val run = runner.run()

            run.results shouldHaveSize 1
            val result = run.results.single()
            result.expectedFiles shouldBe listOf("src/Foo.kt")
            result.ripgrepQueryTokens shouldBe emptyList()
            result.ripgrep.rankedFiles shouldBe emptyList()

            // The real index, queried for real: buildContext("foo") must find
            // the indexed foo() symbol and report its file as evidence.
            val contextGraph = result.contextGraph
            (contextGraph != null) shouldBe true
            contextGraph!!.rankedFiles shouldContain "src/Foo.kt"

            // This fixture corpus has only with/without, so there is no CodeGraph index to ask.
            // That absence must be null-and-recorded, never an empty ranked list scored as zero:
            // a missing comparator that silently reads as "found nothing" is exactly the failure
            // this instrument must not produce.
            result.codeGraph shouldBe null
            run.skippedRepos shouldHaveSize 1
            run.skippedRepos.single().reason.contains("${RetrievalSide.CODE_GRAPH.label} side skipped") shouldBe true
            run.summary!!.headline.codeGraph shouldBe null
        } finally {
            corpusRoot.toFile().deleteRecursively()
        }
    }

    test("a CodeGraph call that fails leaves that side unmeasured and names the question -- never a zero") {
        // The single most damaging thing this instrument could do is let a broken invocation read
        // as "CodeGraph retrieves nothing". So: a codegraph binary that runs and exits non-zero
        // must produce an ABSENT side plus a skip naming the question, not an empty ranked list
        // that would be averaged in as 0.0 and published as a real finding.
        val (corpusRoot, questions, catalog) = buildFixtureCorpus()
        try {
            // Give the repo a codegraph copy that looks indexed, so the per-repo gate lets the
            // runner get as far as actually invoking the binary per question.
            val codeGraphCopy = corpusRoot.resolve("fixture-repo").resolve("codegraph")
            Files.createDirectories(codeGraphCopy.resolve(".codegraph"))

            val failing = corpusRoot.resolve("failing-codegraph.sh")
            Files.writeString(failing, "#!/bin/sh\nexit 3\n")
            failing.toFile().setExecutable(true)

            val run = RetrievalBenchmarkRunner(
                corpusRoot, questions, catalog,
                kValues = listOf(5),
                codegraphPath = failing.toAbsolutePath().toString()
            ).run()

            val result = run.results.single()
            result.codeGraph shouldBe null
            // The other two sides are still scored: one broken comparator must not sink the run.
            (result.contextGraph != null) shouldBe true

            val skip = run.skippedRepos.single { it.reason.contains("${RetrievalSide.CODE_GRAPH.label} side unmeasured") }
            skip.repoId shouldBe "fixture-repo"
            skip.reason.contains("fixture-q1") shouldBe true

            // Excluded from the average rather than dragging it to zero.
            run.summary!!.headline.codeGraph shouldBe null
        } finally {
            corpusRoot.toFile().deleteRecursively()
        }
    }

    test("with the real CodeGraph binary, an indexed copy yields ranked files and a real coverage fraction") {
        if (!CodeGraphProcess.isAvailable("codegraph")) return@test

        val (corpusRoot, questions, catalog) = buildFixtureCorpus()
        try {
            // A real third working copy, really indexed by the real tool.
            val codeGraphCopy = corpusRoot.resolve("fixture-repo").resolve("codegraph")
            LocalGitFixture.create(codeGraphCopy)
            CodeGraphProcess.run(listOf("init", codeGraphCopy.toAbsolutePath().toString()))

            val run = RetrievalBenchmarkRunner(corpusRoot, questions, catalog, kValues = listOf(5)).run()
            val result = run.results.single()

            // Ran, and produced a real measurement rather than an absence.
            (result.codeGraph != null) shouldBe true
            run.skippedRepos.none { it.reason.contains("CodeGraph") } shouldBe true

            // `codegraph files --json` is readable, so coverage is a real fraction here, not the
            // explicit unknown the coverage remedy falls back to.
            val coverage = run.goldFileCoverage.single { it.side == RetrievalSide.CODE_GRAPH }
            coverage.basis shouldBe CoverageBasis.INDEX_QUERY
            (coverage.fraction != null) shouldBe true
        } finally {
            corpusRoot.toFile().deleteRecursively()
        }
    }

    test("gold-file coverage is reported for all three sides, with ripgrep's stated as by-construction") {
        val (corpusRoot, questions, catalog) = buildFixtureCorpus()
        try {
            val run = RetrievalBenchmarkRunner(corpusRoot, questions, catalog, kValues = listOf(5)).run()

            val coverage = run.goldFileCoverage.associateBy { it.side }
            coverage.keys shouldBe setOf(
                RetrievalSide.CONTEXT_GRAPH, RetrievalSide.CODE_GRAPH, RetrievalSide.RIPGREP
            )

            // ContextGraph's index really holds the one cited file.
            coverage[RetrievalSide.CONTEXT_GRAPH]!!.presentFileCount shouldBe 1
            coverage[RetrievalSide.CONTEXT_GRAPH]!!.basis shouldBe CoverageBasis.INDEX_QUERY

            // ripgrep reads the working tree, so it can reach every cited file by construction --
            // said explicitly, so 100% does not read as a suspiciously perfect measurement.
            coverage[RetrievalSide.RIPGREP]!!.basis shouldBe CoverageBasis.READS_WORKING_TREE

            // No CodeGraph index here, so an explicit unknown -- not 0%, which would be a claim.
            coverage[RetrievalSide.CODE_GRAPH]!!.basis shouldBe CoverageBasis.NOT_DETERMINABLE
            coverage[RetrievalSide.CODE_GRAPH]!!.fraction shouldBe null
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

            // The ripgrep side is still measured — it reads the never-indexed working tree, so no
            // index failure can block it. Both graph sides are absent here for their own separate
            // reasons, and each says which.
            run.results.single().ripgrep.rankedFiles shouldBe emptyList()
            run.skippedRepos.count { it.reason.contains("${RetrievalSide.CONTEXT_GRAPH.label} side skipped") } shouldBe 1
            run.skippedRepos.count { it.reason.contains("${RetrievalSide.CODE_GRAPH.label} side skipped") } shouldBe 1
        } finally {
            corpusRoot.toFile().deleteRecursively()
        }
    }
})
