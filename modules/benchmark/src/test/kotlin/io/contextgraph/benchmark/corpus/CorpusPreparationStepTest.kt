package io.contextgraph.benchmark.corpus

import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.Evidence
import io.contextgraph.benchmark.model.GoldFact
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.contextgraph.benchmark.retrieval.CodeGraphProcess
import io.contextgraph.benchmark.runner.GraphTool
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end proof of the whole corpus step (AC-1, AC-1a, AC-2) against a local git fixture:
 * clone-or-verify all three copies, index the WITH copy with ContextGraph, and confirm the
 * WITHOUT copy is bit-for-bit unchanged and carries no code-graph artifact -- the exact invariant
 * AC-7a's "control arm represents a project no code-graph tool has ever touched" depends on.
 *
 * Every test here passes an explicit `codegraphPath`. The default resolves the real binary on
 * PATH, which would make this suite behave one way on a machine with CodeGraph installed and
 * another on a machine without -- and a test whose meaning depends on the developer's laptop is
 * not a test. The one case that genuinely needs the real binary says so and skips when it is
 * absent.
 */
class CorpusPreparationStepTest : FunSpec({

    // Any string that cannot resolve to an executable. Used to exercise the recorded-absence path
    // deterministically, on every machine.
    val noCodeGraph = "codegraph-not-installed-for-this-test"

    test("indexes only the WITH copy; the WITHOUT copy stays byte-for-byte unchanged and artifact-free") {
        val root = Files.createTempDirectory("corpus-step-")
        try {
            val remote = LocalGitFixture.create(root.resolve("remote"), tag = "v1.0.0")
            val entry = CorpusRepo(
                id = "fixture-repo",
                name = "Fixture Repo",
                url = remote.path.toString(),
                pinnedTag = remote.tag,
                pinnedSha = remote.sha
            )
            val corpusRoot = root.resolve("corpus")

            // Fingerprint the WITHOUT copy before the step runs at all, from an independent
            // preparer call, so the "before" snapshot doesn't depend on CorpusPreparationStep
            // internals having already run once.
            val withoutPathBeforeIndexing = Path.of(
                requireNotNull(CorpusPreparer().prepare(entry, corpusRoot).workingCopyWithoutPath)
            )
            val fingerprintBefore = CleanCopyVerifier.fingerprint(withoutPathBeforeIndexing)
            CleanCopyVerifier.findArtifacts(withoutPathBeforeIndexing).shouldBeEmpty()

            val results = CorpusPreparationStep.run(
                corpusRoot,
                repos = listOf(entry),
                codegraphPath = noCodeGraph
            )

            results shouldHaveSize 1
            val result = results.single()
            // Non-null is part of the assertion, not a formality: this call left `indexWithCopy`
            // at its default, so a null record here would mean the WITH copy was never indexed.
            requireNotNull(result.ingestRecord).repoId shouldBe entry.id

            val withPath = Path.of(requireNotNull(result.repo.workingCopyWithPath))
            val withoutPath = Path.of(requireNotNull(result.repo.workingCopyWithoutPath))

            // WITH copy is now indexed: carries the artifact the WITHOUT copy must never have.
            CleanCopyVerifier.findArtifacts(withPath) shouldBe listOf(".contextgraph")

            // WITHOUT copy: still no artifacts, and its content is exactly what it was before
            // the step ran -- not merely "these five names are absent" but genuinely untouched.
            CleanCopyVerifier.findArtifacts(withoutPath).shouldBeEmpty()
            CleanCopyVerifier.fingerprint(withoutPath) shouldBe fingerprintBefore
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("AC-2a wiring: the step rejects a repo whose gold facts cite a file absent from its indexed graph") {
        val root = Files.createTempDirectory("corpus-step-integrity-")
        try {
            val remote = LocalGitFixture.create(root.resolve("remote"), tag = "v1.0.0")
            val entry = CorpusRepo(
                id = "fixture-repo",
                name = "Fixture Repo",
                url = remote.path.toString(),
                pinnedTag = remote.tag,
                pinnedSha = remote.sha
            )
            val corpusRoot = root.resolve("corpus")
            val questions = listOf(
                Question(
                    id = "q1",
                    repoId = "fixture-repo",
                    text = "irrelevant to this test",
                    category = QuestionCategory.GRAPH_HEAVY,
                    goldFacts = listOf(
                        GoldFact(id = "f1", statement = "irrelevant", evidence = Evidence("src/NeverIndexed.kt", 1))
                    )
                )
            )

            val exception = shouldThrow<IndexIntegrityGate.IndexIncompleteException> {
                CorpusPreparationStep.run(
                    corpusRoot,
                    repos = listOf(entry),
                    questions = questions,
                    codegraphPath = noCodeGraph
                )
            }
            exception.repoId shouldBe "fixture-repo"
            exception.missingFiles shouldBe listOf("src/NeverIndexed.kt")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("a missing CodeGraph binary records an absence with its reason and does not fail preparation") {
        // The existing single-tool workflow must keep working on a machine that has never heard of
        // CodeGraph. The absence is recorded rather than swallowed, which is SkippedRepo's
        // no-silent-omission property applied on the preparation side.
        val root = Files.createTempDirectory("corpus-step-no-codegraph-")
        try {
            val remote = LocalGitFixture.create(root.resolve("remote"), tag = "v1.0.0")
            val entry = CorpusRepo(
                id = "fixture-repo",
                name = "Fixture Repo",
                url = remote.path.toString(),
                pinnedTag = remote.tag,
                pinnedSha = remote.sha
            )
            val corpusRoot = root.resolve("corpus")

            val result = CorpusPreparationStep.run(
                corpusRoot,
                repos = listOf(entry),
                codegraphPath = noCodeGraph
            ).single()

            // The working copy exists regardless — its absence was the original defect.
            Path.of(requireNotNull(GraphTool.CODEGRAPH.withToolsDir(result.repo))).toFile().exists() shouldBe true

            val codeGraphCost = result.toolIngestCosts.single { it.tool == GraphTool.CODEGRAPH }
            codeGraphCost.absentReason!!.contains(noCodeGraph) shouldBe true
            // Not a zero-cost index: "was never built" and "cost nothing" are different claims.
            codeGraphCost.durationMillis shouldBe null

            val contextGraphCost = result.toolIngestCosts.single { it.tool == GraphTool.CONTEXTGRAPH }
            contextGraphCost.absentReason shouldBe null
            (contextGraphCost.indexSizeBytes!! > 0) shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("preparation writes an ingest manifest carrying both tools, readable afterwards") {
        val root = Files.createTempDirectory("corpus-step-manifest-")
        try {
            val remote = LocalGitFixture.create(root.resolve("remote"), tag = "v1.0.0")
            val entry = CorpusRepo(
                id = "fixture-repo",
                name = "Fixture Repo",
                url = remote.path.toString(),
                pinnedTag = remote.tag,
                pinnedSha = remote.sha
            )
            val corpusRoot = root.resolve("corpus")

            CorpusPreparationStep.run(corpusRoot, repos = listOf(entry), codegraphPath = noCodeGraph)

            val manifest = requireNotNull(IngestManifest.readFrom(corpusRoot, entry.id))
            manifest.repoId shouldBe entry.id
            manifest.costs.map { it.tool }.toSet() shouldBe setOf(GraphTool.CONTEXTGRAPH, GraphTool.CODEGRAPH)

            // The retrieval run reads this file read-only; that it survives a round trip through
            // disk is the whole contract between the two halves.
            IngestManifest.readAll(corpusRoot, listOf(entry.id)) shouldHaveSize 2
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("a corpus with no manifest reports ingest cost as absent, not as zero") {
        val root = Files.createTempDirectory("corpus-step-no-manifest-")
        try {
            IngestManifest.readFrom(root, "never-prepared") shouldBe null
            IngestManifest.readAll(root, listOf("never-prepared")).shouldBeEmpty()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("with the real CodeGraph binary present, its index lands only in the codegraph copy") {
        // The one test that needs the real tool. Skipped rather than faked where it is absent:
        // asserting on a stub would prove the stub, not the property.
        if (!CodeGraphProcess.isAvailable("codegraph")) return@test

        val root = Files.createTempDirectory("corpus-step-real-codegraph-")
        try {
            val remote = LocalGitFixture.create(root.resolve("remote"), tag = "v1.0.0")
            val entry = CorpusRepo(
                id = "fixture-repo",
                name = "Fixture Repo",
                url = remote.path.toString(),
                pinnedTag = remote.tag,
                pinnedSha = remote.sha
            )
            val corpusRoot = root.resolve("corpus")

            val withoutBefore = Path.of(
                requireNotNull(CorpusPreparer().prepare(entry, corpusRoot).workingCopyWithoutPath)
            )
            val fingerprintBefore = CleanCopyVerifier.fingerprint(withoutBefore)

            val result = CorpusPreparationStep.run(corpusRoot, repos = listOf(entry)).single()

            val codeGraphCopy = Path.of(requireNotNull(GraphTool.CODEGRAPH.withToolsDir(result.repo)))
            val withPath = Path.of(requireNotNull(result.repo.workingCopyWithPath))
            val withoutPath = Path.of(requireNotNull(result.repo.workingCopyWithoutPath))

            // Each tool's artefact is in its own copy, and neither is in the control arm.
            CleanCopyVerifier.findArtifacts(codeGraphCopy) shouldBe listOf(".codegraph")
            CleanCopyVerifier.findArtifacts(withPath) shouldBe listOf(".contextgraph")
            CleanCopyVerifier.findArtifacts(withoutPath).shouldBeEmpty()
            CleanCopyVerifier.fingerprint(withoutPath) shouldBe fingerprintBefore

            val cost = result.toolIngestCosts.single { it.tool == GraphTool.CODEGRAPH }
            cost.absentReason shouldBe null
            (cost.indexSizeBytes!! > 0) shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
