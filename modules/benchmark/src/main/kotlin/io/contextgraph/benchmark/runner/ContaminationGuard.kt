package io.contextgraph.benchmark.runner

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Executable basenames PATH sanitization strips a directory for carrying. */
private val BLOCKED_EXECUTABLE_NAMES = setOf("contextgraph", "contextgraph.bat", "contextgraph.exe")

/**
 * Layer 1 of the two-layer contamination defense (task 04's "İki katmanlı olmalı"): removes
 * every PATH entry that would let a shell resolve the bare word `contextgraph` to a runnable
 * program. This alone is not sufficient -- an agent can still invoke the CLI by absolute or
 * relative path, which never went through PATH resolution in the first place -- which is why
 * [CliInvocationDetector] exists as the second, independent layer.
 */
object PathSanitizer {

    /**
     * [dirLister] is injectable so tests can assert the sanitizer's decision without touching
     * the real filesystem; the real caller passes a lister backed by [File.list].
     */
    fun sanitize(
        rawPath: String,
        pathSeparator: Char = File.pathSeparatorChar,
        dirLister: (String) -> List<String> = { dir -> File(dir).list()?.toList().orEmpty() }
    ): String =
        rawPath
            .split(pathSeparator)
            .filter { entry -> entry.isNotBlank() }
            .filterNot { entry -> dirLister(entry).any { it in BLOCKED_EXECUTABLE_NAMES } }
            .joinToString(pathSeparator.toString())
}

/**
 * Layer 2: inspects a literal Bash command string an agent is about to run and decides whether
 * it invokes the `contextgraph` CLI -- by bare name (which layer 1 would already have made
 * unresolvable, but a hook must not assume layer 1 always ran first) or by an explicit relative
 * or absolute path (which layer 1 cannot catch at all, since path lookup never touches PATH).
 * Matches at the start of the command or immediately after a shell separator (`;`, `&&`, `||`,
 * `|`, a newline, or an opening paren/backtick), so `grep contextgraph src/` -- a legitimate
 * search for the word, not an invocation of the binary -- is not flagged.
 */
object CliInvocationDetector {

    private val INVOCATION_PATTERN = Regex(
        """(?:^|[;&|`(\n]\s*)(?:\S*/)?contextgraph(?:\.bat|\.exe)?(?=\s|$)"""
    )

    fun matches(command: String): Boolean = INVOCATION_PATTERN.containsMatchIn(command)
}

/** The hook's verdict on one Bash command, mirroring what a Claude Code `PreToolUse` hook returns. */
sealed interface GuardDecision {
    data object Allow : GuardDecision
    data class Deny(val reason: String) : GuardDecision
}

/**
 * Owns AC-8's contamination bookkeeping for one agent run: every Bash command the agent
 * attempts is evaluated here before [GuardedBashExecutor] will run it. [attemptCount] is what
 * becomes [io.contextgraph.benchmark.model.AgentRunRecord.cliInvocationAttempts] -- it counts
 * every command [CliInvocationDetector] flags, allowed or not, so the number reflects every
 * *attempt* the agent made, not just the ones that happened to be denied.
 */
class ContaminationGuard {
    private val attempts = AtomicInteger(0)

    val attemptCount: Int
        get() = attempts.get()

    /** Called once per Bash tool call the agent's client issues, before it may run. */
    fun evaluateBashCommand(command: String): GuardDecision {
        if (!CliInvocationDetector.matches(command)) return GuardDecision.Allow
        attempts.incrementAndGet()
        return GuardDecision.Deny(
            "contextgraph CLI calls are blocked in benchmark runs (AC-8) -- attempted command: $command"
        )
    }
}
