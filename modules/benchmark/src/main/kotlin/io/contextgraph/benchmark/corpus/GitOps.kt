package io.contextgraph.benchmark.corpus

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A `git` invocation failed. [message] carries the command and stderr for diagnosis. */
class GitCommandException(message: String) : RuntimeException(message)

/**
 * Thin wrapper around shelling out to `git`. Every corpus operation (mirror clone, worktree
 * add/verify, tag listing) goes through here rather than each caller building its own
 * `ProcessBuilder` — one place to get quoting, working directory, and error reporting right.
 *
 * Deliberately not an interface with a fake implementation: tests exercise the real thing
 * against a local, file-path "remote" repo (a `git init`'d temp directory), which is fast,
 * fully offline, and proves the actual git plumbing works rather than a hand-rolled double
 * of it. See `CorpusPreparerTest` and `LatestTagResolverTest`.
 */
object GitOps {

    private const val TIMEOUT_MINUTES = 30L

    /** Runs `git <args>` in [cwd] (or the JVM's cwd when null), returning trimmed stdout. */
    fun run(args: List<String>, cwd: Path? = null): String {
        val command = listOf("git") + args
        val builder = ProcessBuilder(command)
        cwd?.let { builder.directory(it.toFile()) }
        val process = builder.start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            throw GitCommandException("git ${args.joinToString(" ")} timed out after ${TIMEOUT_MINUTES}m")
        }
        if (process.exitValue() != 0) {
            throw GitCommandException(
                "git ${args.joinToString(" ")} failed (exit ${process.exitValue()}): ${stderr.trim().ifBlank { stdout.trim() }}"
            )
        }
        return stdout.trim()
    }

    /** The commit SHA `HEAD` resolves to inside the working copy or bare repo at [dir]. */
    fun revParseHead(dir: Path): String = run(listOf("rev-parse", "HEAD"), cwd = dir)

    /** True if [dir] looks like a git worktree or repo (has a `.git` file or directory). */
    fun isGitDir(dir: Path): Boolean = Files.exists(dir.resolve(".git")) || Files.exists(dir.resolve("HEAD"))

    /**
     * Shallow bare-clones [url] pinned to [tag] into [dest]. Bare + depth-1 keeps the mirror
     * small: it holds exactly the one commit the tag resolves to, which is all two working
     * copies checked out from it will ever need (evidence citations only ever point at file
     * content *at the pinned SHA*, never at history).
     */
    fun cloneMirror(url: String, dest: Path, tag: String) {
        dest.parent?.let { Files.createDirectories(it) }
        run(listOf("clone", "--bare", "--depth", "1", "--branch", tag, url, dest.toAbsolutePath().toString()))
    }

    /**
     * Adds a working tree at [worktreeDir], checked out at [sha], sharing [mirrorDir]'s object
     * store. This is the mechanism behind AC-1a's two independent working copies from one
     * clone: `git worktree add` twice against the same mirror gives two directories with
     * identical file content and no shared mutable state between them, at a fraction of the
     * disk cost of two full clones.
     */
    fun worktreeAdd(mirrorDir: Path, worktreeDir: Path, sha: String) {
        worktreeDir.parent?.let { Files.createDirectories(it) }
        run(listOf("worktree", "add", worktreeDir.toAbsolutePath().toString(), sha), cwd = mirrorDir)
    }

    /**
     * Tags and the commit each resolves to, from [url]'s remote refs — no clone required.
     * Sorted descending by version (git's own `v:refname` sort), so index 0 is the highest
     * version tag. Annotated tags appear twice in raw `ls-remote` output (the tag object
     * itself, and a peeled `^{}` entry for the commit it targets); this resolves each tag
     * name to its target *commit* SHA, never a tag object SHA.
     */
    fun listTagsBySha(url: String): List<Pair<String, String>> {
        val output = run(listOf("ls-remote", "--tags", "--sort=-v:refname", url))
        if (output.isBlank()) return emptyList()

        val direct = LinkedHashMap<String, String>() // tag name -> sha, insertion order = version order
        val peeled = HashMap<String, String>() // tag name -> peeled commit sha

        output.lines().forEach { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) return@forEach
            val (sha, ref) = parts
            val name = ref.removePrefix("refs/tags/")
            if (name.endsWith("^{}")) {
                peeled[name.removeSuffix("^{}")] = sha
            } else if (!direct.containsKey(name)) {
                direct[name] = sha
            }
        }

        return direct.keys.map { name -> name to (peeled[name] ?: direct.getValue(name)) }
    }
}
