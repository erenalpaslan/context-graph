package io.contextgraph.benchmark.corpus

import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds a tiny local git repo to stand in for a GitHub remote in corpus tests. `git`
 * accepts a plain filesystem path anywhere it accepts a URL (`clone`, `ls-remote`), so this
 * lets [GitOps], [LatestTagResolver], and [CorpusPreparer] be exercised against the real git
 * binary with no network dependency and no risk of slowing down or breaking `./gradlew build`
 * on a machine without internet access (see task 02's note on cal.com/Keycloak being slow —
 * the same argument applies to *every* automated test, not just the two big repos).
 */
object LocalGitFixture {

    /** One commit, tagged [tag], with a couple of small source files so a real index has something to extract. */
    fun create(dir: Path, tag: String = "v1.0.0"): CreatedRepo {
        Files.createDirectories(dir)
        GitOps.run(listOf("init", "-q", "-b", "main"), cwd = dir)
        GitOps.run(listOf("config", "user.email", "test@example.com"), cwd = dir)
        GitOps.run(listOf("config", "user.name", "Test"), cwd = dir)
        Files.writeString(dir.resolve("README.md"), "# fixture repo\n")
        Files.createDirectories(dir.resolve("src"))
        Files.writeString(dir.resolve("src/Foo.kt"), "fun foo(): Int = 42\n")
        GitOps.run(listOf("add", "."), cwd = dir)
        GitOps.run(listOf("commit", "-q", "-m", "initial"), cwd = dir)
        GitOps.run(listOf("tag", tag), cwd = dir)
        val sha = GitOps.revParseHead(dir)
        return CreatedRepo(dir, tag, sha)
    }

    /** Adds a second commit and lightweight [tag] on top of an existing fixture repo at [dir]. */
    fun addTag(dir: Path, tag: String, annotated: Boolean = false) {
        Files.writeString(dir.resolve("src/Foo.kt"), "fun foo(): Int = 43\n")
        GitOps.run(listOf("add", "."), cwd = dir)
        GitOps.run(listOf("commit", "-q", "-m", "update for $tag"), cwd = dir)
        if (annotated) {
            GitOps.run(listOf("tag", "-a", tag, "-m", "release $tag"), cwd = dir)
        } else {
            GitOps.run(listOf("tag", tag), cwd = dir)
        }
    }
}

data class CreatedRepo(val path: Path, val tag: String, val sha: String)
