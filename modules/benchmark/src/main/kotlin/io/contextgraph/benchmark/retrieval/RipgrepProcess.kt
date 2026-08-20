package io.contextgraph.benchmark.retrieval

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A `rg` invocation failed to even start, or exited with a real error (not "no matches"). */
class RipgrepExecutionException(message: String) : RuntimeException(message)

/**
 * Thin wrapper around shelling out to `ripgrep`, the same shape as
 * [io.contextgraph.benchmark.corpus.GitOps] for `git`: one place that owns the
 * `ProcessBuilder`, working directory, timeout, and exit-code interpretation, rather than every
 * caller building its own.
 *
 * `rg` exits `0` when it finds at least one match and `1` when it runs cleanly but finds none --
 * both are success from this wrapper's point of view (an empty result is a real, meaningful
 * answer: this question's derived tokens don't appear anywhere in the checkout). Any other exit
 * code is a real failure (bad flags, unreadable path, `rg` missing entirely) and throws
 * [RipgrepExecutionException] rather than being silently swallowed as "no results" -- collapsing
 * those two cases would make a broken `rg` invocation indistinguishable from a true zero-recall
 * measurement, which is exactly the kind of quiet corruption AC-24's determinism requirement
 * exists to rule out.
 */
object RipgrepProcess {

    private const val TIMEOUT_SECONDS = 60L

    /** True if [rgPath] resolves to a runnable `rg` binary at all. Used to gate tests/CLI startup with a clear message rather than a raw process-launch exception. */
    fun isAvailable(rgPath: String): Boolean = try {
        val process = ProcessBuilder(rgPath, "--version").redirectErrorStream(true).start()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        finished && process.exitValue() == 0
    } catch (e: Exception) {
        false
    }

    /**
     * Runs `rg <args>` in [cwd], returning stdout split into lines (trailing blank lines
     * dropped). Throws [RipgrepExecutionException] naming [rgPath], [args], the exit code and
     * stderr if the process can't be started, times out, or exits with anything other than `0`
     * or `1`.
     */
    fun run(args: List<String>, cwd: Path, rgPath: String = "rg"): List<String> {
        val command = listOf(rgPath) + args
        val process = try {
            ProcessBuilder(command).directory(cwd.toFile()).start()
        } catch (e: Exception) {
            throw RipgrepExecutionException(
                "Could not start '$rgPath' (looked for it on PATH) -- is ripgrep installed? " +
                    "Original error: ${e.message}"
            )
        }

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw RipgrepExecutionException("'$rgPath ${args.joinToString(" ")}' timed out after ${TIMEOUT_SECONDS}s")
        }

        val exitCode = process.exitValue()
        if (exitCode != 0 && exitCode != 1) {
            throw RipgrepExecutionException(
                "'$rgPath ${args.joinToString(" ")}' failed (exit $exitCode): " +
                    stderr.trim().ifBlank { stdout.trim() }
            )
        }

        return stdout.lines().filter { it.isNotBlank() }
    }
}
