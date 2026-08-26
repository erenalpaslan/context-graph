package io.contextgraph.benchmark.corpus

import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.runner.GraphTool
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

    test("prepares three independent working copies at the pinned SHA") {
        val root = Files.createTempDirectory("corpus-preparer-")
        try {
            val remote = remoteRepo(root)
            val corpusRoot = root.resolve("corpus")
            val entry = catalogEntry(remote)

            val prepared = CorpusPreparer().prepare(entry, corpusRoot)

            val withPath = Path.of(requireNotNull(prepared.workingCopyWithPath))
            val withoutPath = Path.of(requireNotNull(prepared.workingCopyWithoutPath))
            val codeGraphPath = Path.of(requireNotNull(GraphTool.CODEGRAPH.withToolsDir(prepared)))

            listOf(withPath, withoutPath, codeGraphPath).distinct().size shouldBe 3
            listOf(withPath, withoutPath, codeGraphPath).forEach { path ->
                path.toFile().shouldExist()
                GitOps.revParseHead(path) shouldBe remote.sha
                path.resolve("src/Foo.kt").toFile().shouldExist()
            }

            // The path the tool enum resolves and the path preparation creates must be the same
            // directory. They disagreeing is the original defect: withToolsDir pointed at a
            // `codegraph` sibling that nothing had ever created.
            codeGraphPath shouldBe CorpusPreparer.worktreeDir(corpusRoot, entry.id, "codegraph")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("the codegraph copy is created even though nothing here checks for a CodeGraph binary") {
        // Unconditional by construction: preparation never asks whether `codegraph` is installed,
        // so the corpus layout is the same on every machine. Whether the copy then gets *indexed*
        // is CorpusPreparationStep's decision, recorded either way.
        val root = Files.createTempDirectory("corpus-preparer-unconditional-")
        try {
            val remote = remoteRepo(root)
            val corpusRoot = root.resolve("corpus")
            val entry = catalogEntry(remote)

            val prepared = CorpusPreparer().prepare(entry, corpusRoot)

            CorpusPreparer.worktreeDir(corpusRoot, entry.id, "codegraph").toFile().shouldExist()
            // ...and it is a real checkout, not an empty directory.
            GitOps.revParseHead(Path.of(GraphTool.CODEGRAPH.withToolsDir(prepared)!!)) shouldBe remote.sha
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("a write to the codegraph copy never reaches the other two") {
        // CodeGraph indexes this copy, so its artifacts must not leak into the control arm — the
        // property CleanCopyVerifier then proves from the other direction.
        val root = Files.createTempDirectory("corpus-preparer-codegraph-independence-")
        try {
            val remote = remoteRepo(root)
            val corpusRoot = root.resolve("corpus")
            val prepared = CorpusPreparer().prepare(catalogEntry(remote), corpusRoot)

            val withPath = Path.of(requireNotNull(prepared.workingCopyWithPath))
            val withoutPath = Path.of(requireNotNull(prepared.workingCopyWithoutPath))
            val codeGraphPath = Path.of(requireNotNull(GraphTool.CODEGRAPH.withToolsDir(prepared)))

            Files.createDirectories(codeGraphPath.resolve(".codegraph"))
            Files.writeString(codeGraphPath.resolve(".codegraph/codegraph.db"), "index")

            Files.exists(withPath.resolve(".codegraph")) shouldBe false
            Files.exists(withoutPath.resolve(".codegraph")) shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("a codegraph copy moved off the pin is reported, not silently fixed") {
        val root = Files.createTempDirectory("corpus-preparer-codegraph-mismatch-")
        try {
            val remote = remoteRepo(root)
            val corpusRoot = root.resolve("corpus")
            val entry = catalogEntry(remote)

            val prepared = CorpusPreparer().prepare(entry, corpusRoot)
            val codeGraphPath = Path.of(requireNotNull(GraphTool.CODEGRAPH.withToolsDir(prepared)))

            Files.writeString(codeGraphPath.resolve("drift.txt"), "unpinned change")
            LocalGitFixture.commitAll(codeGraphPath, "drift")

            val ex = shouldThrow<CorpusShaMismatchException> {
                CorpusPreparer().prepare(entry, corpusRoot)
            }
            ex.role shouldBe "codegraph"
            ex.expectedSha shouldBe remote.sha
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
            LocalGitFixture.commitAll(withPath, "drift")
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
