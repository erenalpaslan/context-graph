package io.contextgraph.ingest

import io.contextgraph.core.ContextGraphConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class FileDiscovery(private val config: ContextGraphConfig) {
    private val sensitiveNames = setOf(".env", ".env.local", ".env.production", ".env.staging")
    private val sensitiveExtensions = setOf("key", "pem", "p12", "pfx", "cert", "crt")
    private val sensitivePatterns = listOf(
        Regex(".*secret.*", RegexOption.IGNORE_CASE),
        Regex(".*password.*", RegexOption.IGNORE_CASE),
        Regex(".*credentials.*", RegexOption.IGNORE_CASE),
        Regex(".*\\.env(\\..*)?$")
    )

    fun discover(root: Path): Flow<Path> = flow {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val gitignorePatterns = loadGitignorePatterns(root)
        val allPatterns = gitignorePatterns + config.excludePatterns
        val excludeMatchers = buildMatchers(allPatterns)
        // Directory-shaped variants of the same patterns, used to prune the walk itself:
        // descending into `.git/`, `build/`, etc. only to filter every file back out again
        // is wasted work (and, for `.git/`, needlessly slow).
        val dirExcludeMatchers = buildDirMatchers(allPatterns)

        val paths = withContext(Dispatchers.IO) {
            val result = mutableListOf<Path>()
            if (Files.exists(normalizedRoot)) {
                Files.walkFileTree(normalizedRoot, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val normalized = dir.toAbsolutePath().normalize()
                        if (normalized != normalizedRoot && shouldExclude(normalized, normalizedRoot, dirExcludeMatchers)) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                        val normalized = file.toAbsolutePath().normalize()
                        if (!normalized.startsWith(normalizedRoot)) return FileVisitResult.CONTINUE
                        // Also test against the directory-shaped matchers: in a git WORKTREE (as
                        // opposed to a plain clone), `.git` is a regular FILE — a short gitlink
                        // pointer to the real git dir — not a directory, so it never reaches
                        // preVisitDirectory and never gets pruned there. An entry excluded as a
                        // directory (e.g. `**/.git/**`, normalised to a bare `.git`) must exclude
                        // a same-named file too, or the shape of the entry on disk silently
                        // decides whether the exclude rule applies.
                        if (shouldExclude(normalized, normalizedRoot, excludeMatchers)) return FileVisitResult.CONTINUE
                        if (shouldExclude(normalized, normalizedRoot, dirExcludeMatchers)) return FileVisitResult.CONTINUE
                        if (config.ignoreSecrets && isSensitive(normalized)) return FileVisitResult.CONTINUE
                        if (attrs.size() > config.maxFileSizeBytes) return FileVisitResult.CONTINUE
                        result.add(normalized)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                })
            }
            result
        }
        paths.forEach { emit(it) }
    }

    private fun loadGitignorePatterns(root: Path): List<String> {
        val gitignore = root.resolve(".gitignore").toFile()
        return if (gitignore.exists()) {
            gitignore.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.trim() }
        } else emptyList()
    }

    private fun buildMatchers(patterns: List<String>): List<PathMatcher> =
        compileGlobs(patterns.flatMap { fileGlobVariants(it) })

    private fun buildDirMatchers(patterns: List<String>): List<PathMatcher> =
        compileGlobs(patterns.flatMap { dirGlobVariants(it) })

    private fun compileGlobs(globs: List<String>): List<PathMatcher> {
        val fs = FileSystems.getDefault()
        return globs.mapNotNull { glob ->
            try { fs.getPathMatcher(glob) } catch (_: Exception) { null }
        }
    }

    /**
     * A pattern with no leading double-star-slash (or leading slash) is meant to match anywhere
     * in the tree, including at the repo root. The JDK's glob matcher requires a real path
     * separator after a double-star-slash prefix to consume, so a nested-form glob built from
     * `build` matches `sub/build` but NOT a root-level `build` (there is no separator for the
     * prefix to anchor on). Emitting both the prefixed form (nested occurrences) and the bare
     * form (root-level occurrences) covers both without weakening either — confirmed
     * empirically: prefixed-`build` vs `build` -> false, vs `sub/build` -> true; bare `build`
     * vs `build` -> true, vs `sub/build` -> false. Together they cover both.
     */
    private fun fileGlobVariants(pattern: String): List<String> {
        if (pattern.startsWith("/")) return listOf("glob:$pattern")
        val bare = pattern.removePrefix("**/")
        return listOf("glob:**/$bare", "glob:$bare")
    }

    /**
     * Same reasoning as [fileGlobVariants], applied to the directory itself rather than its
     * contents: a pattern like double-star-slash-`build`-slash-double-star (anything under a
     * `build/` directory) is turned into `build` / prefixed-`build` so [discover] can prune the
     * directory from the walk instead of descending into it and filtering every file back out
     * one by one.
     */
    private fun dirGlobVariants(pattern: String): List<String> {
        val trimmed = pattern.trimEnd('/').removeSuffix("/**").removeSuffix("/*")
        return fileGlobVariants(trimmed)
    }

    private fun shouldExclude(path: Path, root: Path, matchers: List<PathMatcher>): Boolean {
        val relative = root.relativize(path)
        return matchers.any { it.matches(relative) }
    }

    private fun isSensitive(path: Path): Boolean {
        val name = path.fileName?.toString()?.lowercase() ?: return false
        if (name in sensitiveNames) return true
        val ext = name.substringAfterLast(".", "")
        if (ext in sensitiveExtensions) return true
        return sensitivePatterns.any { it.matches(name) }
    }
}
