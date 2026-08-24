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
import io.kotest.matchers.shouldNotBe
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

    /**
     * [questionText] defaults to the zero-token `"foo"` the class KDoc above explains; only the
     * bash-failure test overrides it, because a question deriving no tokens never reaches either
     * text-search binary at all and so cannot exercise a failing one.
     */
    fun buildFixtureCorpus(
        questionText: String = "foo"
    ): Triple<java.nio.file.Path, List<Question>, List<CorpusRepo>> {
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
            text = questionText,
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

    test("scores the ContextGraph, bash and ripgrep sides end to end, and says out loud why CodeGraph's is absent") {
        val (corpusRoot, questions, catalog) = buildFixtureCorpus()
        try {
            val runner = RetrievalBenchmarkRunner(corpusRoot, questions, catalog, kValues = listOf(5))
            val run = runner.run()

            run.results shouldHaveSize 1
            val result = run.results.single()
            result.expectedFiles shouldBe listOf("src/Foo.kt")
            result.ripgrepQueryTokens shouldBe emptyList()
            result.ripgrep.rankedFiles shouldBe emptyList()

            // Both text-search baselines were handed the *same* raw question and the same derived
            // tokens, so both report the same true zero -- and the bash side reports it as a
            // measurement (an empty ranked list) rather than as an absence. A question no grep
            // query can be formed for is exactly the honest floor this fourth side exists to
            // measure; nulling it here would quietly remove that floor from the published mean.
            val bash = result.bash
            (bash != null) shouldBe true
            bash!!.rankedFiles shouldBe emptyList()
            bash.reciprocalRank shouldBe 0.0
            run.summary!!.headline.bash shouldNotBe null
            run.summary!!.headline.bash!!.measuredCount shouldBe 1

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

    test("a grep call that fails leaves the bash side unmeasured and names the question -- never a zero") {
        // The exact mirror of the CodeGraph case above, for the side this axis was extended to
        // measure. A `grep` that exits outside its 0/1 contract must produce an ABSENT bash side
        // plus a skip naming the question -- never an empty ranked list averaged in as 0.0, which
        // would publish as "a shell alone retrieves nothing" and hand the project an unearned win
        // over the very floor this fourth side exists to establish.
        //
        // The question text is code-shaped on purpose. The `"foo"` every other test here uses
        // derives *no* tokens, so BashBaselineRunner returns early and never reaches a binary at
        // all; `handleFoo` has an internal capital, so RipgrepQueryDeriver yields one token and
        // both text-search sides really do spawn a process.
        val (corpusRoot, questions, catalog) = buildFixtureCorpus(questionText = "handleFoo")
        try {
            // Exit 2: outside the `0` (matched) / `1` (ran clean, matched nothing) pair BashProcess
            // treats as success, so this throws BashCommandExecutionException. `/usr/bin/false`
            // would NOT serve here -- its exit 1 is grep's legitimate "no match", which is a real
            // measured zero and must stay one.
            val failingGrep = corpusRoot.resolve("failing-grep.sh")
            Files.writeString(failingGrep, "#!/bin/sh\nexit 2\n")
            failingGrep.toFile().setExecutable(true)

            // The ripgrep side gets a stub exiting 1 -- `rg`'s own "ran clean, matched nothing" --
            // so this test still shells out to no real `rg` and stays runnable on a machine without
            // one, exactly as the first test in this class is, while that side is genuinely
            // measured rather than skipped and can be checked as the isolation control below.
            val silentRg = corpusRoot.resolve("silent-rg.sh")
            Files.writeString(silentRg, "#!/bin/sh\nexit 1\n")
            silentRg.toFile().setExecutable(true)

            val run = RetrievalBenchmarkRunner(
                corpusRoot, questions, catalog,
                kValues = listOf(5),
                rgPath = silentRg.toAbsolutePath().toString(),
                grepPath = failingGrep.toAbsolutePath().toString()
            ).run()

            // The run completed rather than aborting: one question's failed subprocess must not
            // sink a measurement that spans four repos.
            run.results shouldHaveSize 1
            val result = run.results.single()

            // Both text-search sides really were handed the same single derived token, which is
            // what makes "the bash side reached the binary and the binary failed" the only reading
            // of the absence below.
            result.ripgrepQueryTokens shouldBe listOf("handleFoo")

            // Absent, not a zeroed SideResult.
            result.bash shouldBe null

            // The other sides for this same question are still scored -- the failure is isolated to
            // the one tool that failed.
            (result.contextGraph != null) shouldBe true
            result.ripgrep.rankedFiles shouldBe emptyList()

            val skip = run.skippedRepos.single { it.reason.contains("${RetrievalSide.BASH.label} side unmeasured") }
            skip.repoId shouldBe "fixture-repo"
            skip.reason.contains("fixture-q1") shouldBe true

            // The one that matters: excluded from every published average rather than dragging one
            // to zero. A SideAggregate here -- zeroed, or measured-and-scoring-zero -- would mean
            // RetrievalStats.aggregateIfMeasured had been bypassed, and would read downstream as a
            // real finding about what a shell alone retrieves. Asserted on all three groupings the
            // report prints, since a bypass in any one of them publishes the same false claim.
            run.summary!!.headline.bash shouldBe null
            run.summary!!.byRepo.getValue("fixture-repo").bash shouldBe null
            run.summary!!.byCategory.getValue(QuestionCategory.GRAPH_HEAVY).bash shouldBe null

            // ...while ripgrep, handed the identical token, keeps its measurement.
            run.summary!!.headline.ripgrep.measuredCount shouldBe 1
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

    test("gold-file coverage is reported for all four sides, with both working-tree sides stated as by-construction") {
        val (corpusRoot, questions, catalog) = buildFixtureCorpus()
        try {
            val run = RetrievalBenchmarkRunner(corpusRoot, questions, catalog, kValues = listOf(5)).run()

            val coverage = run.goldFileCoverage.associateBy { it.side }
            // Every side gets a row. An omitted one renders as NOT_DETERMINABLE, which for a
            // working-tree side would be a false statement rather than a missing one.
            coverage.keys shouldBe setOf(
                RetrievalSide.CONTEXT_GRAPH, RetrievalSide.CODE_GRAPH,
                RetrievalSide.BASH, RetrievalSide.RIPGREP
            )

            // ContextGraph's index really holds the one cited file.
            coverage[RetrievalSide.CONTEXT_GRAPH]!!.presentFileCount shouldBe 1
            coverage[RetrievalSide.CONTEXT_GRAPH]!!.basis shouldBe CoverageBasis.INDEX_QUERY

            // Both text-search baselines read the working tree, so each can reach every cited file
            // by construction -- said explicitly, so 100% does not read as a suspiciously perfect
            // measurement, and so bash's row is never mistaken for an unknown.
            coverage[RetrievalSide.RIPGREP]!!.basis shouldBe CoverageBasis.READS_WORKING_TREE
            coverage[RetrievalSide.BASH]!!.basis shouldBe CoverageBasis.READS_WORKING_TREE
            coverage[RetrievalSide.BASH]!!.presentFileCount shouldBe coverage[RetrievalSide.BASH]!!.citedFileCount
            coverage[RetrievalSide.BASH]!!.fraction shouldBe 1.0

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
