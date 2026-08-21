package io.contextgraph.benchmark.corpus

import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.Evidence
import io.contextgraph.benchmark.model.GoldFact
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end proof of the whole corpus step (AC-1, AC-1a, AC-2) against a local git fixture:
 * clone-or-verify both copies, index only the WITH copy, and confirm the WITHOUT copy is
 * bit-for-bit unchanged and carries no ContextGraph artifact -- the exact invariant AC-7a's
 * "control arm represents a project ContextGraph has never touched" depends on.
 */
class CorpusPreparationStepTest : FunSpec({

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

            val results = CorpusPreparationStep.run(corpusRoot, repos = listOf(entry))

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
                CorpusPreparationStep.run(corpusRoot, repos = listOf(entry), questions = questions)
            }
            exception.repoId shouldBe "fixture-repo"
            exception.missingFiles shouldBe listOf("src/NeverIndexed.kt")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
