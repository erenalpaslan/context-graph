package io.contextgraph.extractors.snapshot

import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Locates the repo root from wherever Gradle happens to set a test's working directory,
 * by walking up until `settings.gradle.kts` is found. Test fixtures live at
 * `<repo root>/test-fixtures/...`, one directory tree shared across every module's tests
 * (see `test-fixtures/kotlin-project`, `test-fixtures/docs-project`).
 */
object RepoRoot {
    val path: Path by lazy {
        var dir = Path.of(".").toAbsolutePath().normalize()
        while (!dir.resolve("settings.gradle.kts").exists()) {
            dir = dir.parent ?: error("Could not locate repo root (no settings.gradle.kts found in any parent directory)")
        }
        dir
    }

    fun fixture(relative: String): Path = path.resolve("test-fixtures").resolve(relative)
}
