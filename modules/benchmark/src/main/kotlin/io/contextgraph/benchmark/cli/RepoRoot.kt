package io.contextgraph.benchmark.cli

import java.nio.file.Files
import java.nio.file.Path

/**
 * Locates the repository root and resolves the corpus root against it — never against
 * whatever directory the JVM happened to be launched from.
 *
 * Regression this guards against (AC-1): [PrepareCorpusCommand]'s `--corpus-root` used to
 * default to the relative path `.benchmark-corpus` and resolve it with
 * `Path.of(corpusRoot).toAbsolutePath()`, i.e. against the caller's cwd. The Gradle
 * `prepareCorpus` `JavaExec` task runs with cwd `modules/benchmark`, so a default invocation
 * materialized a second, un-ignored corpus at `modules/benchmark/.benchmark-corpus/` instead
 * of the intended `<repo-root>/.benchmark-corpus/`. `RepoRoot` makes the resolution
 * deterministic: the same `--corpus-root` value (including the default) either always
 * resolves to the same absolute directory, or resolution fails loudly — it never guesses.
 */
object RepoRoot {
    /** Marker file present only at the repository root, never in a subdirectory. */
    private const val MARKER = "settings.gradle.kts"

    /**
     * System property the `prepareCorpus` Gradle task sets to `rootProject.projectDir`,
     * handing this command a root Gradle already knows with certainty at configuration time.
     * When set, it is used directly and the walk-up search below is skipped entirely — the
     * walk-up exists only as a fallback for a bare `java -cp ...` launch where no build tool
     * supplied the root. This avoids a directory walk silently anchoring to the wrong tree if
     * an unrelated Gradle root (its own `settings.gradle.kts`) ever sat between the launch
     * directory and this repo's true root.
     */
    const val REPO_ROOT_PROPERTY = "contextgraph.benchmark.repoRoot"

    /**
     * Determines the repository root.
     *
     * - If [explicitRoot] is given (from [REPO_ROOT_PROPERTY]), it is used after confirming it
     *   actually contains [MARKER] — the same invariant the walk-up below enforces. Gradle is a
     *   trusted source for this value in the intended call path, but trusting it unconditionally
     *   would let *any* existing directory silently become the corpus root the moment
     *   `-D$REPO_ROOT_PROPERTY` pointed somewhere stale or wrong; checking the marker turns that
     *   into the same loud failure the walk-up gives a bad [startDir]. No walk is performed once
     *   [explicitRoot] is supplied — a passing marker check is itself the whole point of skipping
     *   the walk with certainty rather than rediscovering it.
     * - Otherwise, walks upward from [startDir] until it finds a directory containing [MARKER].
     *   Fails loudly rather than silently falling back to [startDir] itself — a wrong-but-quiet
     *   guess is exactly what produced the original defect.
     */
    fun find(startDir: Path, explicitRoot: Path? = null): Path {
        if (explicitRoot != null) {
            val normalized = explicitRoot.toAbsolutePath().normalize()
            if (!Files.exists(normalized.resolve(MARKER))) {
                throw IllegalStateException(
                    "Explicit repo root '$normalized' (from -D$REPO_ROOT_PROPERTY) does not " +
                        "contain '$MARKER', so it is not actually this repository's root. " +
                        "Refusing to silently anchor the corpus there."
                )
            }
            return normalized
        }

        var dir: Path? = startDir.toAbsolutePath().normalize()
        while (dir != null) {
            if (Files.exists(dir.resolve(MARKER))) return dir
            dir = dir.parent
        }
        throw IllegalStateException(
            "Could not locate the repository root: no '$MARKER' found walking up from " +
                "'${startDir.toAbsolutePath().normalize()}', and no -D$REPO_ROOT_PROPERTY was " +
                "set. Refusing to fall back to the current working directory."
        )
    }

    /**
     * Resolves [corpusRootArg] to an absolute directory.
     *
     * - If [corpusRootArg] is already absolute, it is returned as-is (normalized) — the
     *   caller opted out of repo-relative resolution explicitly.
     * - If [corpusRootArg] is relative (including the `.benchmark-corpus` default), it is
     *   resolved against the repository root from [find] (see [explicitRoot] there), not
     *   against [startDir] itself. This is what makes the result independent of the caller's
     *   working directory — or, if no root can be established, makes resolution fail loudly
     *   instead of silently falling back to [startDir].
     */
    fun resolveCorpusRoot(corpusRootArg: String, startDir: Path, explicitRoot: Path? = null): Path {
        val given = Path.of(corpusRootArg)
        return if (given.isAbsolute) {
            given.normalize()
        } else {
            find(startDir, explicitRoot).resolve(given).normalize()
        }
    }
}
