package io.contextgraph.benchmark.retrieval

import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A `codegraph` invocation could not be started, timed out, or exited with a real error. */
class CodeGraphExecutionException(message: String, val timedOut: Boolean = false) : RuntimeException(message)

/**
 * Thin wrapper around shelling out to `codegraph`, the same shape as [RipgrepProcess] for `rg`
 * and [io.contextgraph.benchmark.corpus.GitOps] for `git`: one place owning the `ProcessBuilder`,
 * the working directory, the timeout, and the exit-code interpretation.
 *
 * CodeGraph is driven as a CLI rather than as an MCP stdio server on purpose. Its own README says
 * `codegraph explore <query>` gives the "same output as the `codegraph_explore` MCP tool" -- and
 * the binary confirms it, routing the CLI command straight into the MCP tool handler -- so
 * shelling out keeps this side of the measurement deterministic and LLM-free, exactly as the
 * ripgrep baseline is. Spawning an MCP subprocess would buy nothing and add a protocol to go
 * wrong.
 *
 * **Exit-code semantics, measured against v1.5.0 rather than assumed:** `explore` exits `0` both
 * when it finds files and when it prints `No relevant code found for "<query>"`, and `1` on a
 * real error such as an unreadable project path. So `0` is "ran cleanly" -- empty output
 * included -- and anything else is a failure that throws.
 *
 * That distinction is the single most important thing this class does. An invocation that failed
 * and an invocation that honestly found nothing must never collapse into the same value: scoring
 * a failure as an empty ranked list would publish "CodeGraph retrieves nothing", which is
 * indistinguishable from a real finding and would hand ContextGraph a win it did not earn.
 * [RipgrepProcess]'s KDoc makes the identical argument for the baseline; it holds here for the
 * same reason.
 */
object CodeGraphProcess {

    /** Long enough for `explore` on a large index, short enough that a hung call cannot stall a whole run. */
    val DEFAULT_TIMEOUT: Duration = 5.minutes

    /**
     * True if [codegraphPath] resolves to a runnable `codegraph` binary. Used to decide whether to
     * index at all, and to report an honest reason when not -- never to silently skip.
     */
    fun isAvailable(codegraphPath: String): Boolean = try {
        val process = ProcessBuilder(codegraphPath, "--version").redirectErrorStream(true).start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        finished && process.exitValue() == 0
    } catch (e: Exception) {
        false
    }

    /**
     * Runs `codegraph <args>` and returns its stdout verbatim.
     *
     * There is deliberately no working-directory parameter: every `codegraph` subcommand this
     * suite uses takes its project through `-p`/an explicit path argument, so a cwd would be a
     * second, silent way to say the same thing.
     *
     * [onProgress] receives each stderr line as it arrives, so a long `index` is visibly working
     * rather than indistinguishable from a hung one -- the brief's own instruction, and the
     * difference between waiting out a slow index and killing a healthy one.
     *
     * Throws [CodeGraphExecutionException] if the process cannot start, exceeds [timeout] (with
     * `timedOut = true`, so a caller can report the budget it blew rather than a generic error),
     * or exits non-zero.
     */
    fun run(
        args: List<String>,
        codegraphPath: String = "codegraph",
        timeout: Duration = DEFAULT_TIMEOUT,
        onProgress: (String) -> Unit = {}
    ): String {
        val command = listOf(codegraphPath) + args
        val process = try {
            ProcessBuilder(command).start()
        } catch (e: Exception) {
            throw CodeGraphExecutionException(
                "Could not start '$codegraphPath' (looked for it on PATH) -- is CodeGraph " +
                    "installed? Original error: ${e.message}"
            )
        }

        // Drained on separate threads: a subprocess that fills its stdout or stderr pipe blocks
        // forever, which would look exactly like the hang the timeout is here to catch.
        val stdoutBuffer = StringBuilder()
        val stderrBuffer = StringBuilder()
        val stdoutReader = Thread {
            process.inputStream.bufferedReader().forEachLine { stdoutBuffer.append(it).append('\n') }
        }.apply { isDaemon = true; start() }
        val stderrReader = Thread {
            process.errorStream.bufferedReader().forEachLine {
                stderrBuffer.append(it).append('\n')
                onProgress(it)
            }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw CodeGraphExecutionException(
                "'$codegraphPath ${args.joinToString(" ")}' exceeded its $timeout budget and was " +
                    "killed. Nothing it may have written is treated as a usable index: a " +
                    "timed-out index must never be scored as a complete one.",
                timedOut = true
            )
        }
        stdoutReader.join(5_000)
        stderrReader.join(5_000)

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw CodeGraphExecutionException(
                "'$codegraphPath ${args.joinToString(" ")}' failed (exit $exitCode): " +
                    stderrBuffer.toString().trim().ifBlank { stdoutBuffer.toString().trim() }
            )
        }
        return stdoutBuffer.toString()
    }
}
