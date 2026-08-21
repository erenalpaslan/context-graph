package io.contextgraph.benchmark.runner

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** What running a Bash command produced -- whether it ran at all, and if so, how it exited. */
data class BashExecutionResult(
    val blocked: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

/** Runs a real shell command in [workingDir] under [env]. The default [ProcessRunner] used by [GuardedBashExecutor]. */
fun runRealProcess(command: String, workingDir: Path, env: Map<String, String>): BashExecutionResult {
    val process = ProcessBuilder("/bin/sh", "-c", command)
        .directory(workingDir.toFile())
        .redirectErrorStream(false)
        .apply {
            environment().clear()
            environment().putAll(env)
        }
        .start()
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val finished = process.waitFor(120, TimeUnit.SECONDS)
    if (!finished) process.destroyForcibly()
    return BashExecutionResult(blocked = false, exitCode = process.exitValue(), stdout = stdout, stderr = stderr)
}

/**
 * The *only* way an [AgentClient] implementation is handed to run a Bash command -- by
 * construction, there is no raw [ProcessBuilder] reachable from a client implementation, only
 * this executor, so every Bash attempt is structurally forced through [ContaminationGuard]
 * before a process is ever spawned. This is what makes AC-8's "iki katmanlı" block real rather
 * than a convention a client implementation could forget to honour.
 */
class GuardedBashExecutor(
    private val guard: ContaminationGuard,
    private val workingDir: Path,
    private val sanitizedEnv: Map<String, String>,
    private val processRunner: (command: String, workingDir: Path, env: Map<String, String>) -> BashExecutionResult = ::runRealProcess
) {
    fun execute(command: String): BashExecutionResult {
        return when (val decision = guard.evaluateBashCommand(command)) {
            is GuardDecision.Allow -> processRunner(command, workingDir, sanitizedEnv)
            is GuardDecision.Deny -> BashExecutionResult(
                blocked = true,
                exitCode = 126,
                stdout = "",
                stderr = decision.reason
            )
        }
    }
}
