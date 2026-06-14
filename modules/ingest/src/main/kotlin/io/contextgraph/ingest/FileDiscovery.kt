package io.contextgraph.ingest

import io.contextgraph.core.ContextGraphConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher

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
        val excludeMatchers = buildMatchers(gitignorePatterns + config.excludePatterns)

        val paths = withContext(Dispatchers.IO) {
            val result = mutableListOf<Path>()
            Files.walk(normalizedRoot).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .forEach { path ->
                        val normalized = path.toAbsolutePath().normalize()
                        if (!normalized.startsWith(normalizedRoot)) return@forEach
                        if (shouldExclude(normalized, normalizedRoot, excludeMatchers)) return@forEach
                        if (config.ignoreSecrets && isSensitive(normalized)) return@forEach
                        if (Files.size(normalized) > config.maxFileSizeBytes) return@forEach
                        result.add(normalized)
                    }
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

    private fun buildMatchers(patterns: List<String>): List<PathMatcher> {
        val fs = FileSystems.getDefault()
        return patterns.mapNotNull { pattern ->
            try {
                val glob = if (!pattern.startsWith("**/") && !pattern.startsWith("/")) "glob:**/$pattern" else "glob:$pattern"
                fs.getPathMatcher(glob)
            } catch (_: Exception) { null }
        }
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
