package io.contextgraph.benchmark.runner

import java.nio.file.Files
import java.nio.file.Path

/**
 * The third thing standing between an agent and the real `contextgraph` CLI, and the only one of
 * the three that reacts to what actually *executes* rather than to the literal command string.
 *
 * [ContaminationGuard]'s regex is a fast pre-check on the command string an [AgentClient] is
 * about to run, and it can be fooled by indirection that never puts the literal word
 * `contextgraph` next to a shell separator in the string it sees -- `X=contextgraph; $X search
 * foo` is a one-line example: the assignment doesn't match, and by the time `$X` expands to
 * `contextgraph` the regex has already run. [PathSanitizer] removes the real binary from PATH,
 * so a *bare* `contextgraph` -- however it was constructed -- fails with "not found" once
 * sanitized... provided nothing else on the sanitized PATH resolves that name. This class makes
 * that provision positive rather than a byproduct: it places a directory containing a script
 * literally named `contextgraph` at the very front of the PATH [AgentRunner] hands to the
 * process, so *any* invocation that resolves the bare name `contextgraph` through PATH --
 * regardless of how indirectly the command constructed that name -- executes this script instead
 * of failing silently or (if some other resolution path existed) reaching the real CLI. The
 * script records that it ran and exits non-zero, so the agent sees a failure and no real graph
 * data ever crosses back to it.
 *
 * What this does **not** catch, and is not meant to: a command that reaches the real CLI by an
 * *absolute or relative path* built through the same kind of shell indirection (`X=/usr/local/
 * bin/contextgraph; $X search foo`) never touches PATH resolution at all, so neither
 * [PathSanitizer] nor this sentinel sees it. [ContaminationGuard]'s regex is the only layer that
 * can catch an *unobfuscated* absolute-path invocation; an obfuscated one is a residual gap this
 * benchmark's three layers do not close. See [AgentRunner] for how that is surfaced rather than
 * silently assumed away.
 */
class CliSentinel private constructor(val binDir: Path) {
    private val markerFile = binDir.resolve(".invocations")

    /** How many times the shell actually resolved and ran the sentinel during this run. */
    fun invocationCount(): Int =
        if (Files.exists(markerFile)) Files.readAllLines(markerFile).count { it.isNotBlank() } else 0

    fun cleanup() {
        binDir.toFile().deleteRecursively()
    }

    companion object {
        /** A fresh, empty-of-invocations sentinel in its own temp directory. */
        fun createTemp(): CliSentinel {
            val binDir = Files.createTempDirectory("contextgraph-sentinel")
            val sentinel = CliSentinel(binDir)
            val scriptPath = binDir.resolve("contextgraph")
            // Deliberately built from shell builtins only (echo, redirection, exit) -- no
            // external binary (e.g. `date`) that could itself be missing from a PATH sanitized
            // down to almost nothing, which would be a self-defeating way for the sentinel to
            // fail. What is being intercepted here is a hostile-PATH scenario by construction.
            Files.writeString(
                scriptPath,
                """
                #!/bin/sh
                # Benchmark contamination sentinel (AC-9). Records that a shell invocation
                # resolved to "contextgraph" via PATH and refuses to do anything further.
                echo "blocked: ${'$'}*" >> "${sentinel.markerFile}"
                echo "contextgraph: command not found" >&2
                exit 127
                """.trimIndent() + "\n"
            )
            scriptPath.toFile().setExecutable(true, false)
            return sentinel
        }
    }
}
