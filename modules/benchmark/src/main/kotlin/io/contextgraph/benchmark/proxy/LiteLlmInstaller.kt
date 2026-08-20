package io.contextgraph.benchmark.proxy

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/** What one real process invocation produced. */
data class ProcessRunResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Runs [command] in [workingDir] (or the JVM's own cwd when null), merging [env] onto the current process environment (venv/pip need PATH, HOME, etc. -- this never clears them). */
fun runInstallCommand(
    command: List<String>,
    workingDir: Path?,
    env: Map<String, String>,
    timeoutSeconds: Long = 300
): ProcessRunResult {
    val builder = ProcessBuilder(command)
    workingDir?.let { builder.directory(it.toFile()) }
    builder.environment().putAll(env)
    val process = builder.start()
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return ProcessRunResult(-1, stdout, "'${command.joinToString(" ")}' timed out after ${timeoutSeconds}s")
    }
    return ProcessRunResult(process.exitValue(), stdout, stderr)
}

/**
 * Ensures a gitignored venv at [venvDir] carries exactly [LiteLlmPin.VERSION] of `litellm[proxy]`
 * -- never the system Python (task 17's hard constraint: nothing gets installed outside the
 * repo). [pythonExecutable] is checked against [LiteLlmPin] *before* anything is installed or
 * even a venv is created, so an incompatible interpreter fails with a clear message instead of a
 * confusing pip dependency-resolution error partway through.
 *
 * [runner] is the sole seam to the outside world (process execution, therefore network and disk)
 * -- [LiteLlmInstallerTest] injects a fake here to prove every branch (unsupported Python,
 * already-pinned skip, fresh install, stale-version reinstall, failed install) without ever
 * touching a real interpreter, venv, or pip.
 */
object LiteLlmInstaller {
    fun ensureInstalled(
        venvDir: Path,
        pythonExecutable: String = "/usr/bin/python3",
        runner: (List<String>, Path?, Map<String, String>) -> ProcessRunResult = ::runInstallCommand
    ) {
        val versionResult = runner(listOf(pythonExecutable, "--version"), null, emptyMap())
        // Python has printed its version to stdout on modern releases, stderr on some old ones --
        // whichever is non-blank is the real answer.
        val versionText = versionResult.stdout.ifBlank { versionResult.stderr }
        val version = PythonVersion.parse(versionText)
        LiteLlmPin.requireSupported(version)

        val venvPython = venvDir.resolve("bin/python")
        if (isAlreadyPinned(venvPython, runner)) return

        if (!venvDir.exists()) {
            val venvResult = runner(listOf(pythonExecutable, "-m", "venv", venvDir.toString()), null, emptyMap())
            if (venvResult.exitCode != 0) {
                throw LiteLlmInstallException(
                    "Could not create a virtualenv at '$venvDir' (exit ${venvResult.exitCode}): " +
                        venvResult.stderr.ifBlank { venvResult.stdout }
                )
            }
        }

        val installResult = runner(
            listOf(venvPython.toString(), "-m", "pip", "install", "--quiet", "litellm[proxy]==${LiteLlmPin.VERSION}"),
            null,
            emptyMap()
        )
        if (installResult.exitCode != 0) {
            throw LiteLlmInstallException(
                "pip install litellm[proxy]==${LiteLlmPin.VERSION} into '$venvDir' failed (exit " +
                    "${installResult.exitCode}): ${installResult.stderr.ifBlank { installResult.stdout }}"
            )
        }
    }

    /** True only if [venvPython] exists and `pip show litellm` reports exactly [LiteLlmPin.VERSION]. Any other outcome (missing venv, litellm absent, a different version) means "not pinned yet". */
    private fun isAlreadyPinned(
        venvPython: Path,
        runner: (List<String>, Path?, Map<String, String>) -> ProcessRunResult
    ): Boolean {
        if (!venvPython.exists()) return false
        val show = runner(listOf(venvPython.toString(), "-m", "pip", "show", "litellm"), null, emptyMap())
        if (show.exitCode != 0) return false
        val installedVersion = show.stdout.lineSequence()
            .firstOrNull { it.startsWith("Version:") }
            ?.substringAfter("Version:")
            ?.trim()
        return installedVersion == LiteLlmPin.VERSION
    }
}
