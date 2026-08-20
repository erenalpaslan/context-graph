package io.contextgraph.benchmark.corpus

import io.contextgraph.benchmark.model.CorpusRepo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import java.nio.file.Files
import java.nio.file.Path

/**
 * AC-1 / AC-1a: pinned-SHA clone, idempotent re-run, mismatch detection, and two
 * independent working copies. Runs against a local git repo standing in for a GitHub
 * remote — see `LocalGitFixture` — so this is fully offline and fast enough to run on
 * every `./gradlew build`.
 */
class CorpusPreparerTest : FunSpec({

    fun remoteRepo(root: Path): CreatedRepo = LocalGitFixture.create(root.resolve("remote"), tag = "v1.0.0")

    fun catalogEntry(remote: CreatedRepo): CorpusRepo = CorpusRepo(
        id = "fixture-repo",
        name = "Fixture Repo",
        url = remote.path.toString(),
        pinnedTag = remote.tag,
        pinnedSha = remote.sha
    )

    test("prepares two independent working copies at the pinned SHA") {
        val root = Files.createTempDirectory("corpus-preparer-")
        try {
            val remote = remoteRepo(root)
            val corpusRoot = root.resolve("corpus")

            val prepared = CorpusPreparer().prepare(catalogEntry(remote), corpusRoot)

            val withPath = Path.of(requireNotNull(prepared.workingCopyWithPath))
            val withoutPath = Path.of(requireNotNull(prepared.workingCopyWithoutPath))

            withPath shouldNotBe withoutPath
            withPath.toFile().shouldExist()
            withoutPath.toFile().shouldExist()
            GitOps.revParseHead(withPath) shouldBe remote.sha
            GitOps.revParseHead(withoutPath) shouldBe remote.sha
            withPath.resolve("src/Foo.kt").toFile().shouldExist()
            withoutPath.resolve("src/Foo.kt").toFile().shouldExist()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("the two working copies are independent: a write to one never appears in the other") {
        val root = Files.createTempDirectory("corpus-preparer-independence-")
        try {
            val remote = remoteRepo(root)
            val corpusRoot = root.resolve("corpus")
            val prepared = CorpusPreparer().prepare(catalogEntry(remote), corpusRoot)

            val withPath = Path.of(requireNotNull(prepared.workingCopyWithPath))
            val withoutPath = Path.of(requireNotNull(prepared.workingCopyWithoutPath))

            Files.writeString(withPath.resolve("only-in-with.txt"), "marker")

            Files.exists(withPath.resolve("only-in-with.txt")) shouldBe true
            Files.exists(withoutPath.resolve("only-in-with.txt")) shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("running prepare twice does not re-clone and succeeds identically (idempotent)") {
        val root = Files.createTempDirectory("corpus-preparer-idempotent-")
        try {
            val remote = remoteRepo(root)
            val corpusRoot = root.resolve("corpus")
            val entry = catalogEntry(remote)

            val first = CorpusPreparer().prepare(entry, corpusRoot)
            val mirrorModifiedAfterFirst = Files.getLastModifiedTime(
                CorpusPreparer.mirrorDir(corpusRoot, entry.id).resolve("HEAD")
            )

            val second = CorpusPreparer().prepare(entry, corpusRoot)

            second shouldBe first
            // The mirror's HEAD file must not have been touched by a re-clone.
            Files.getLastModifiedTime(
                CorpusPreparer.mirrorDir(corpusRoot, entry.id).resolve("HEAD")
            ) shouldBe mirrorModifiedAfterFirst
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("a working copy deliberately moved to a different commit is reported, not silently fixed") {
        val root = Files.createTempDirectory("corpus-preparer-mismatch-")
        try {
            val remote = remoteRepo(root)
            val corpusRoot = root.resolve("corpus")
            val entry = catalogEntry(remote)

            val prepared = CorpusPreparer().prepare(entry, corpusRoot)
            val withPath = Path.of(requireNotNull(prepared.workingCopyWithPath))

            // Simulate an operator (or a bug) moving the WITH checkout off the pin.
            Files.writeString(withPath.resolve("drift.txt"), "unpinned change")
            GitOps.run(listOf("add", "."), cwd = withPath)
            GitOps.run(listOf("commit", "-q", "-m", "drift"), cwd = withPath)
            val driftedSha = GitOps.revParseHead(withPath)
            driftedSha shouldNotBe remote.sha

            val ex = shouldThrow<CorpusShaMismatchException> {
                CorpusPreparer().prepare(entry, corpusRoot)
            }

            ex.repoId shouldBe entry.id
            ex.role shouldBe "with"
            ex.expectedSha shouldBe remote.sha
            ex.actualSha shouldBe driftedSha
            ex.message.shouldNotBeEmpty()
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
