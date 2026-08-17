package io.contextgraph.ingest

import io.contextgraph.core.ContextGraphConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Regression coverage for the root-level exclude-matching defect: a repo-relative path with no
 * directory separator in it (e.g. a `build/` directory sitting directly under the repo root) was
 * silently indexed because `glob:**&#47;build` never matches a bare `build` — the JDK glob matcher
 * requires a real separator after a `**&#47;` prefix. These tests exercise the real [FileDiscovery]
 * output, not the matcher internals, so a regression here means real indexing runs pick up
 * `.git/`, `build/`, etc. at the repo root again.
 */
class FileDiscoveryTest : FunSpec({

    fun tempRepo(): Path = Files.createTempDirectory("file-discovery-test")

    fun discoverRelativePaths(root: Path, config: ContextGraphConfig = ContextGraphConfig()): List<String> =
        runBlocking {
            FileDiscovery(config).discover(root).toList()
                .map { root.relativize(it).toString().replace('\\', '/') }
        }

    test("a root-level excluded directory (build/) is not indexed") {
        val root = tempRepo()
        root.resolve("build").createDirectories()
        root.resolve("build/output.txt").writeText("generated")
        root.resolve("src").createDirectories()
        root.resolve("src/Main.kt").writeText("fun main() {}")

        val found = discoverRelativePaths(root)

        found shouldContainExactlyInAnyOrder listOf("src/Main.kt")
    }

    test("a nested excluded directory (sub/build/) is not indexed") {
        val root = tempRepo()
        root.resolve("sub/build").createDirectories()
        root.resolve("sub/build/output.txt").writeText("generated")
        root.resolve("sub/Keep.kt").writeText("class Keep")

        val found = discoverRelativePaths(root)

        found shouldContainExactlyInAnyOrder listOf("sub/Keep.kt")
    }

    test("a root-level dotdir (.git/) is not indexed") {
        val root = tempRepo()
        root.resolve(".git").createDirectories()
        root.resolve(".git/config").writeText("[core]")
        root.resolve("README.md").writeText("# demo")

        val found = discoverRelativePaths(root)

        found shouldContainExactlyInAnyOrder listOf("README.md")
    }

    test("a pattern that legitimately starts with **/ still excludes both root-level and nested occurrences") {
        val root = tempRepo()
        root.resolve("app.log").writeText("root log")
        root.resolve("sub").createDirectories()
        root.resolve("sub/app.log").writeText("nested log")
        root.resolve("Main.kt").writeText("fun main() {}")

        val config = ContextGraphConfig(excludePatterns = listOf("**/*.log"))
        val found = discoverRelativePaths(root, config)

        found shouldContainExactlyInAnyOrder listOf("Main.kt")
    }

    test("root-level and nested build directories are both pruned while unrelated files are kept") {
        val root = tempRepo()
        root.resolve("build").createDirectories()
        root.resolve("build/a.class").writeText("x")
        root.resolve("sub/build").createDirectories()
        root.resolve("sub/build/b.class").writeText("x")
        root.resolve("sub/Keep.kt").writeText("class Keep")
        root.resolve("Top.kt").writeText("class Top")

        val found = discoverRelativePaths(root)

        found shouldContainExactlyInAnyOrder listOf("sub/Keep.kt", "Top.kt")
    }

    test("a bare .git FILE (git worktree gitlink, not a directory) is not indexed") {
        val root = tempRepo()
        // In a git worktree, `.git` is a plain file containing a pointer to the real git dir
        // (e.g. "gitdir: /path/to/main/.git/worktrees/name"), not a directory. It never reaches
        // preVisitDirectory, so it must still be caught when tested as a file.
        root.resolve(".git").writeText("gitdir: /some/other/repo/.git/worktrees/example\n")
        root.resolve("README.md").writeText("# demo")

        val found = discoverRelativePaths(root)

        found shouldContainExactlyInAnyOrder listOf("README.md")
    }
})
