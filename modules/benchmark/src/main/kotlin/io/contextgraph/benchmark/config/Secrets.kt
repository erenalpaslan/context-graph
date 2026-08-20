package io.contextgraph.benchmark.config

import io.contextgraph.benchmark.cli.RepoRoot
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Resolves benchmark secrets from the environment, falling back to a gitignored
 * `local.properties` at the repository root.
 *
 * Precedence is environment-first on purpose: CI sets the variable and must win, while
 * `local.properties` is the developer convenience that saves re-exporting a key into every
 * shell. Reversing that would let a stale file on one machine silently override the value CI
 * was configured with -- and the resulting run would look completely normal.
 *
 * Resolution never throws. A missing or unreadable `local.properties`, or a working directory
 * from which the repository root cannot be located, simply means "no fallback available" and
 * leaves the caller with whatever the environment provided. The loud failure belongs at the
 * point of *use* (where the caller knows the key was actually required), not here -- the
 * retrieval axis, for instance, runs entirely without an API key and must not be broken by a
 * secrets lookup it never needed.
 *
 * `local.properties` is gitignored. Never commit it, and never log a resolved value.
 */
object Secrets {

    const val FILE_NAME = "local.properties"

    /** Property/environment name for the key both the agent and judge clients need. */
    const val ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY"

    /**
     * Property/environment name for the OpenAI key (task 20): the Anthropic key on this account
     * came back invalid (401, confirmed directly), so both
     * [io.contextgraph.benchmark.runner.OpenAiChatCompletionsClient] and the LiteLLM proxy
     * (fronting the judge) resolve their key through this name instead.
     */
    const val OPENAI_API_KEY = "OPENAI_API_KEY"

    /**
     * Returns [name] from the environment, else from `local.properties`, else null.
     *
     * Blank values are treated as absent in both sources: an exported-but-empty variable is a
     * misconfiguration, not an intentional empty secret, and silently accepting it would push
     * the failure into an HTTP 401 far from its cause.
     */
    fun resolve(
        name: String,
        env: (String) -> String? = System::getenv,
        startDir: Path = Path.of(System.getProperty("user.dir")),
        explicitRepoRoot: Path? = System.getProperty(RepoRoot.REPO_ROOT_PROPERTY)?.let(Path::of)
    ): String? =
        env(name)?.takeIf { it.isNotBlank() }
            ?: fromLocalProperties(name, startDir, explicitRepoRoot)

    /** Where the fallback file would be read from, or null if the repo root can't be located. */
    fun localPropertiesPath(
        startDir: Path = Path.of(System.getProperty("user.dir")),
        explicitRepoRoot: Path? = System.getProperty(RepoRoot.REPO_ROOT_PROPERTY)?.let(Path::of)
    ): Path? = repoRootOrNull(startDir, explicitRepoRoot)?.resolve(FILE_NAME)

    /**
     * The message to show when a required secret is missing, naming both places it was looked
     * for. Callers own the throwing; this only keeps the wording in one place so the two client
     * classes cannot drift into describing different setup steps.
     */
    fun missingMessage(name: String, path: Path? = localPropertiesPath()): String =
        "$name is not set. Provide it either as an environment variable, or as a line " +
            "`$name=<value>` in ${path ?: FILE_NAME} (gitignored)."

    private fun fromLocalProperties(name: String, startDir: Path, explicitRepoRoot: Path?): String? {
        val file = repoRootOrNull(startDir, explicitRepoRoot)?.resolve(FILE_NAME) ?: return null
        if (!Files.isRegularFile(file)) return null
        val properties = Properties()
        try {
            Files.newInputStream(file).use(properties::load)
        } catch (_: Exception) {
            // An unreadable or malformed file is a missing fallback, not a hard failure --
            // see the class doc on why resolution stays silent.
            return null
        }
        return properties.getProperty(name)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun repoRootOrNull(startDir: Path, explicitRepoRoot: Path?): Path? =
        try {
            RepoRoot.find(startDir, explicitRepoRoot)
        } catch (_: IllegalStateException) {
            null
        }
}
