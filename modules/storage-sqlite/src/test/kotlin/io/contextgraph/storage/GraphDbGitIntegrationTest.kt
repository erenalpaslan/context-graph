package io.contextgraph.storage

import io.contextgraph.core.GraphDb
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * [GraphDbOverlaySeedingTest] proves the baseline/overlay seam against plain temp-dir
 * files. That is enough to prove the file-resolution logic, but AC-29 and AC-30 are
 * claims about *git's* behaviour ("a fresh clone", "git status shows no modification")
 * — claims that a temp-dir copy cannot exercise, because there is no git repository,
 * no `.gitignore`, and no clone involved. This suite drives real `git init`/`add`/
 * `commit`/`clone`/`checkout`/`status` against the project's actual `.gitignore`, so
 * the git-level guarantees are proven with git itself as the judge, not simulated.
 *
 * Skips at runtime (rather than failing the module) if `git` is not on PATH.
 */
class GraphDbGitIntegrationTest : FunSpec({

    fun gitAvailable(): Boolean = try {
        ProcessBuilder("git", "--version").start().waitFor() == 0
    } catch (e: Exception) {
        false
    }

    fun git(cwd: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "git ${args.joinToString(" ")} (in $cwd) failed [$exitCode]: $output" }
        return output
    }

    fun currentBranch(cwd: Path): String = git(cwd, "branch", "--show-current").trim()

    fun node(id: String, label: String) = GraphNode(NodeId(id), NodeType.Class, label, confidence = 1.0)

    /** Walk up from the test's working directory to find the real repo root and its `.gitignore`. */
    fun realGitignore(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (dir.parent != null && !dir.resolve("settings.gradle.kts").exists()) {
            dir = dir.parent
        }
        val gitignore = dir.resolve(".gitignore")
        check(gitignore.exists()) { "Could not locate repo .gitignore from $dir" }
        return gitignore
    }

    /**
     * Builds a throwaway git repo whose `.gitignore` is a copy of the real project
     * `.gitignore`, with a baseline already committed at `.contextgraph/graph.db`.
     */
    fun initRepoWithCommittedBaseline(root: Path, label: String): Path {
        root.createDirectories()
        git(root, "init", "-q")
        git(root, "config", "user.email", "test@example.com")
        git(root, "config", "user.name", "GraphDbGitIntegrationTest")
        realGitignore().copyTo(root.resolve(".gitignore"))

        val storage = SqliteStorageAdapter(GraphDb.baseline(root))
        storage.upsertNode(node("UserService", label))
        storage.close()

        git(root, "add", ".gitignore", ".contextgraph/graph.db")
        git(root, "commit", "-q", "-m", "commit baseline: $label")
        return root
    }

    test("a real `git clone` answers a query from the committed baseline with no local index") {
        if (!gitAvailable()) return@test
        val origin = Files.createTempDirectory("gitdb-origin-")
        initRepoWithCommittedBaseline(origin, "origin-baseline")

        val cloneParent = Files.createTempDirectory("gitdb-clone-parent-")
        val clone = cloneParent.resolve("clone")
        git(cloneParent, "clone", "-q", origin.toString(), clone.toString())

        // Fresh clone: no local indexing has happened, so no overlay exists.
        GraphDb.overlay(clone).exists() shouldBe false
        GraphDb.baseline(clone).exists() shouldBe true

        val reader = SqliteStorageAdapter(GraphDb.forRead(clone))
        reader.getNode(NodeId("UserService")).shouldNotBeNull().label shouldBe "origin-baseline"
        reader.close()
    }

    test("after a local index, git status shows no modification to the committed baseline") {
        if (!gitAvailable()) return@test
        val origin = Files.createTempDirectory("gitdb-origin-")
        initRepoWithCommittedBaseline(origin, "origin-baseline")
        val cloneParent = Files.createTempDirectory("gitdb-clone-parent-")
        val clone = cloneParent.resolve("clone")
        git(cloneParent, "clone", "-q", origin.toString(), clone.toString())

        val writer = SqliteStorageAdapter(GraphDb.forLocalWrite(clone))
        writer.upsertNode(node("NewLocalClass", "local-only"))
        writer.close()

        // Git-level proof, not file-level: ask git itself whether the tracked baseline changed.
        val baselineStatus = git(clone, "status", "--porcelain", "--", ".contextgraph/graph.db")
        baselineStatus.trim() shouldBe ""

        // The overlay must never appear as a change to commit — it's gitignored, not just unmodified.
        val fullStatus = git(clone, "status", "--porcelain")
        fullStatus.contains("graph.local.db") shouldBe false

        // And the overlay is where the write actually landed.
        GraphDb.overlay(clone).exists() shouldBe true
    }

    test("switching branches with different committed baselines leaves the system coherent") {
        if (!gitAvailable()) return@test
        val origin = Files.createTempDirectory("gitdb-origin-")
        initRepoWithCommittedBaseline(origin, "main-baseline")
        val mainBranch = currentBranch(origin)

        // A second branch commits a different baseline.
        git(origin, "checkout", "-q", "-b", "other-branch")
        val otherStorage = SqliteStorageAdapter(GraphDb.baseline(origin))
        otherStorage.upsertNode(node("UserService", "other-branch-baseline"))
        otherStorage.close()
        git(origin, "add", ".contextgraph/graph.db")
        git(origin, "commit", "-q", "-m", "other branch baseline")

        // Seed an overlay while on the other branch (it's gitignored, so branch
        // switches must not touch it).
        val overlayWriter = SqliteStorageAdapter(GraphDb.forLocalWrite(origin))
        overlayWriter.getNode(NodeId("UserService")).shouldNotBeNull().label shouldBe "other-branch-baseline"
        overlayWriter.close()
        val overlayContentBeforeSwitch = Files.readAllBytes(GraphDb.overlay(origin))

        // Switch back to the main branch, which carries a different committed baseline.
        git(origin, "checkout", "-q", mainBranch)

        // The gitignored overlay survives the branch switch untouched (git does not
        // manage it), so reads still resolve to it rather than crashing or silently
        // switching to the new branch's baseline underneath the caller.
        GraphDb.overlay(origin).exists() shouldBe true
        Files.readAllBytes(GraphDb.overlay(origin)) shouldBe overlayContentBeforeSwitch
        val readerAfterSwitch = SqliteStorageAdapter(GraphDb.forRead(origin))
        readerAfterSwitch.getNode(NodeId("UserService")).shouldNotBeNull().label shouldBe "other-branch-baseline"
        readerAfterSwitch.close()

        // Deleting the overlay after the switch and reindexing reseeds from whichever
        // baseline is actually checked out now (main's), not stale data from the
        // branch the overlay was originally seeded on.
        Files.delete(GraphDb.overlay(origin))
        val reseeded = SqliteStorageAdapter(GraphDb.forLocalWrite(origin))
        reseeded.getNode(NodeId("UserService")).shouldNotBeNull().label shouldBe "main-baseline"
        reseeded.close()

        // Throughout, git status on the tracked baseline stays clean — no branch
        // switch, seed, or reseed ever dirtied the file git owns.
        git(origin, "status", "--porcelain", "--", ".contextgraph/graph.db").trim() shouldBe ""
    }
})
