package io.contextgraph.benchmark.corpus

import io.contextgraph.benchmark.model.Evidence
import io.contextgraph.benchmark.model.GoldFact
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files

private fun question(repoId: String, evidenceFile: String) = Question(
    id = "$repoId-q1",
    repoId = repoId,
    text = "irrelevant to this test",
    category = QuestionCategory.GRAPH_HEAVY,
    goldFacts = listOf(GoldFact(id = "$repoId-f1", statement = "irrelevant", evidence = Evidence(evidenceFile, 1)))
)

/**
 * AC-2a's regression test: a graph missing a file that the repo's gold facts cite is rejected,
 * not silently accepted because the directory looks healthy. Indexes a real
 * [LocalGitFixture] repo (README.md, src/Foo.kt) through the real [CorpusIndexer] so the graph
 * being checked is a genuine SQLite-backed index, not a stub.
 */
class IndexIntegrityGateTest : FunSpec({

    test("passes when every gold-fact-cited file is present in the graph") {
        val root = Files.createTempDirectory("integrity-gate-pass-")
        try {
            val repo = LocalGitFixture.create(root.resolve("with"))
            CorpusIndexer.index("fixture-repo", repo.path)

            // Should not throw.
            IndexIntegrityGate.verify(
                repoId = "fixture-repo",
                indexedWorkingDir = repo.path,
                questions = listOf(question("fixture-repo", "src/Foo.kt"), question("fixture-repo", "README.md"))
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("rejects a graph that does not contain a file cited by a gold fact") {
        val root = Files.createTempDirectory("integrity-gate-fail-")
        try {
            val repo = LocalGitFixture.create(root.resolve("with"))
            CorpusIndexer.index("fixture-repo", repo.path)

            val exception = shouldThrow<IndexIntegrityGate.IndexIncompleteException> {
                IndexIntegrityGate.verify(
                    repoId = "fixture-repo",
                    indexedWorkingDir = repo.path,
                    questions = listOf(
                        question("fixture-repo", "src/Foo.kt"),
                        question("fixture-repo", "src/NeverIndexed.kt")
                    )
                )
            }

            exception.repoId shouldBe "fixture-repo"
            exception.missingFiles shouldContainExactly listOf("src/NeverIndexed.kt")
            val message = exception.message ?: ""
            message.contains("src/NeverIndexed.kt") shouldBe true
            message.contains("fixture-repo") shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("checks the graph, not the filesystem: a file that exists on disk but was never indexed is still rejected") {
        val root = Files.createTempDirectory("integrity-gate-fs-vs-graph-")
        try {
            val repo = LocalGitFixture.create(root.resolve("with"))
            CorpusIndexer.index("fixture-repo", repo.path)

            // A file genuinely on disk (added after indexing ran) must still be treated as
            // missing -- proves the check queries the graph, not the working copy's filesystem.
            Files.writeString(repo.path.resolve("src/AddedAfterIndexing.kt"), "fun late(): Int = 1\n")

            shouldThrow<IndexIntegrityGate.IndexIncompleteException> {
                IndexIntegrityGate.verify(
                    repoId = "fixture-repo",
                    indexedWorkingDir = repo.path,
                    questions = listOf(question("fixture-repo", "src/AddedAfterIndexing.kt"))
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("a repo with no cited files passes trivially, including questions for other repos") {
        val root = Files.createTempDirectory("integrity-gate-empty-")
        try {
            val repo = LocalGitFixture.create(root.resolve("with"))
            CorpusIndexer.index("fixture-repo", repo.path)

            // Should not throw, and should not even need a graph -- but one exists here anyway.
            IndexIntegrityGate.verify(
                repoId = "fixture-repo",
                indexedWorkingDir = repo.path,
                questions = listOf(question("some-other-repo", "does/not/exist.kt"))
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("fails loudly rather than silently creating an empty graph when the working dir was never indexed") {
        val root = Files.createTempDirectory("integrity-gate-never-indexed-")
        try {
            Files.createDirectories(root)

            shouldThrow<IllegalStateException> {
                IndexIntegrityGate.verify(
                    repoId = "fixture-repo",
                    indexedWorkingDir = root,
                    questions = listOf(question("fixture-repo", "src/Foo.kt"))
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
