package io.contextgraph.benchmark.retrieval

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A base-system `grep` invocation failed to start, timed out, or exited with a real error (not "no match"). */
class BashCommandExecutionException(message: String) : RuntimeException(message)

/** One spawned command's raw outcome, before any interpretation of what its exit code means. */
data class BashCommandResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * The single seam through which the bash side reaches the operating system (AC-1). Everything it
 * spawns goes through here, so a test can substitute a fake, capture the argv, and prove the
 * program name is a base-system binary and never `rg` -- a claim that is otherwise only checkable
 * by reading the source and trusting it.
 *
 * It is deliberately *below* exit-code interpretation: a fake can hand back exit `2` and assert
 * the named exception, and hand back exit `1` and assert a real, empty answer, without needing a
 * `grep` that can be made to fail on demand.
 */
fun interface BashCommandLauncher {
    fun launch(command: List<String>, cwd: Path): BashCommandResult
}

/**
 * Thin wrapper around shelling out to the base-system `grep`, the same shape [RipgrepProcess] has
 * for `rg` and [CodeGraphProcess] has for `codegraph`: one place owning the `ProcessBuilder`, the
 * working directory, the timeout, and the exit-code interpretation.
 *
 * `grep` exits `0` when it selects at least one line and `1` when it runs cleanly and selects none
 * -- both are success here, because an empty result is a real, meaningful answer (this question's
 * derived tokens appear nowhere in the checkout). Any other status is a genuine failure -- a
 * rejected flag, an unreadable path -- and throws [BashCommandExecutionException] rather than
 * being swallowed as "no results". Collapsing those two cases would make a broken invocation
 * indistinguishable from a true zero-recall measurement, and would publish a baseline score of
 * zero that the baseline never actually earned. [RipgrepProcess]'s KDoc makes the identical
 * argument; it holds here for the same reason and must keep holding, because the two sides are
 * only readable against each other while they treat "found nothing" identically.
 */
object BashProcess {

    /**
     * The base-system `grep`, resolved by **absolute path** rather than through `PATH`.
     *
     * This is not defensiveness about a missing binary -- it is the point of the whole side. The
     * brief asks what a developer gets with "bash command (without thirdparty)"; a `PATH` on a
     * developer machine may well put a Homebrew or `nix` GNU grep ahead of `/usr/bin/grep`, and
     * measuring that would silently be measuring an installed third-party tool again, which is
     * exactly the flaw this side exists to remove from the ripgrep baseline.
     */
    val BASE_SYSTEM_GREP: String =
        listOf("/usr/bin/grep", "/bin/grep").firstOrNull { Files.isExecutable(Path.of(it)) } ?: "grep"

    /**
     * Generous compared with [RipgrepProcess]'s 60s, and deliberately so. `grep` has no index, no
     * parallelism and no `.gitignore` pruning, so it is legitimately slower than `rg` over the
     * same tree -- a budget tight enough for `rg` would convert a slow-but-honest bash answer into
     * a fabricated failure, which is a way of weakening the baseline by accident. The timeout
     * exists only to stop one hung call stalling a whole run, not to score the tool.
     */
    val DEFAULT_TIMEOUT: Duration = 5.minutes

    /** The real launcher: a `ProcessBuilder` in [Path] cwd, with both pipes drained before the wait. */
    val SYSTEM: BashCommandLauncher = BashCommandLauncher { command, cwd ->
        val process = try {
            ProcessBuilder(command).directory(cwd.toFile()).start()
        } catch (e: Exception) {
            throw BashCommandExecutionException(
                "Could not start '${command.firstOrNull()}' -- this should be a base-system " +
                    "binary present on every macOS and Linux install. Original error: ${e.message}"
            )
        }

        // Drained on separate threads: a subprocess that fills its stdout pipe blocks forever,
        // which would be indistinguishable from the hang the timeout is here to catch. `grep -rc`
        // prints one line per file scanned, so on a large checkout that pipe fills quickly.
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = Thread {
            process.inputStream.bufferedReader().forEachLine { stdout.append(it).append('\n') }
        }.apply { isDaemon = true; start() }
        val stderrReader = Thread {
            process.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(DEFAULT_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw BashCommandExecutionException(
                "'${command.joinToString(" ")}' exceeded its $DEFAULT_TIMEOUT budget and was killed."
            )
        }
        stdoutReader.join(5_000)
        stderrReader.join(5_000)

        BashCommandResult(process.exitValue(), stdout.toString(), stderr.toString())
    }

    /**
     * Runs `<grepPath> <args>` in [cwd] through [launcher], returning stdout split into lines with
     * blank lines dropped. Throws [BashCommandExecutionException] naming the command, its exit
     * code and its stderr for any status other than `0` or `1`.
     */
    fun run(
        args: List<String>,
        cwd: Path,
        grepPath: String = BASE_SYSTEM_GREP,
        launcher: BashCommandLauncher = SYSTEM
    ): List<String> {
        val command = listOf(grepPath) + args
        val result = launcher.launch(command, cwd)

        if (result.exitCode != 0 && result.exitCode != 1) {
            throw BashCommandExecutionException(
                "'${command.joinToString(" ")}' failed (exit ${result.exitCode}): " +
                    result.stderr.trim().ifBlank { result.stdout.trim() }
            )
        }

        return result.stdout.lines().filter { it.isNotBlank() }
    }
}
