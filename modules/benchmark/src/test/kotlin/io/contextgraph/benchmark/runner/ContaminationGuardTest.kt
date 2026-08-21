package io.contextgraph.benchmark.runner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

/**
 * AC-8's required test: a scenario that tries to call the `contextgraph` CLI is blocked and the
 * attempt counter increments. None of this needs a real LLM run -- the guard is a pure function
 * of the command string plus a mutable counter, so we drive it directly with the same kind of
 * command an agent's Bash tool call would carry.
 */
class ContaminationGuardTest : FunSpec({

    test("a bare 'contextgraph' invocation is denied and counted") {
        val guard = ContaminationGuard()
        val decision = guard.evaluateBashCommand("contextgraph search \"auth\"")
        decision.shouldBeInstanceOf<GuardDecision.Deny>()
        guard.attemptCount shouldBe 1
    }

    test("an absolute-path invocation is denied -- PATH sanitization alone would have missed this") {
        val guard = ContaminationGuard()
        val decision = guard.evaluateBashCommand("/usr/local/bin/contextgraph report")
        decision.shouldBeInstanceOf<GuardDecision.Deny>()
        guard.attemptCount shouldBe 1
    }

    test("an invocation chained after another command is still caught") {
        val guard = ContaminationGuard()
        val decision = guard.evaluateBashCommand("echo hi && ./contextgraph index .")
        decision.shouldBeInstanceOf<GuardDecision.Deny>()
        guard.attemptCount shouldBe 1
    }

    test("a benign command that merely mentions the word is allowed and not counted") {
        val guard = ContaminationGuard()
        val decision = guard.evaluateBashCommand("grep -r contextgraph README.md")
        decision.shouldBeInstanceOf<GuardDecision.Allow>()
        guard.attemptCount shouldBe 0
    }

    test("multiple attempts across a run all increment the same counter") {
        val guard = ContaminationGuard()
        guard.evaluateBashCommand("contextgraph search x")
        guard.evaluateBashCommand("cat README.md")
        guard.evaluateBashCommand("contextgraph search y")
        guard.attemptCount shouldBe 2
    }

    test("GuardedBashExecutor never invokes the underlying process runner for a denied command") {
        val guard = ContaminationGuard()
        val workingDir = Files.createTempDirectory("guarded-bash")
        var processRunnerInvocations = 0
        val executor = GuardedBashExecutor(
            guard = guard,
            workingDir = workingDir,
            sanitizedEnv = emptyMap(),
            processRunner = { _, _, _ ->
                processRunnerInvocations++
                BashExecutionResult(blocked = false, exitCode = 0, stdout = "should never happen", stderr = "")
            }
        )

        val result = executor.execute("contextgraph search leaked-secret")

        result.blocked shouldBe true
        result.exitCode shouldBe 126
        processRunnerInvocations shouldBe 0
        guard.attemptCount shouldBe 1
    }

    test("GuardedBashExecutor delegates an allowed command to the process runner") {
        val guard = ContaminationGuard()
        val workingDir = Files.createTempDirectory("guarded-bash-ok")
        var receivedCommand: String? = null
        val executor = GuardedBashExecutor(
            guard = guard,
            workingDir = workingDir,
            sanitizedEnv = emptyMap(),
            processRunner = { command, _, _ ->
                receivedCommand = command
                BashExecutionResult(blocked = false, exitCode = 0, stdout = "ok", stderr = "")
            }
        )

        val result = executor.execute("ls -la")

        result.blocked shouldBe false
        result.stdout shouldBe "ok"
        receivedCommand shouldBe "ls -la"
        guard.attemptCount shouldBe 0
    }

    test("PathSanitizer strips a directory that carries the contextgraph executable") {
        val realDir = "/usr/local/bin"
        val fakeListing = mapOf(
            realDir to listOf("contextgraph", "other-tool"),
            "/usr/bin" to listOf("ls", "cat")
        )
        val sanitized = PathSanitizer.sanitize(
            rawPath = "$realDir${File.pathSeparator}/usr/bin",
            dirLister = { dir -> fakeListing[dir].orEmpty() }
        )
        sanitized shouldBe "/usr/bin"
    }

    test("PathSanitizer leaves PATH untouched when no entry carries the executable") {
        val fakeListing = mapOf(
            "/usr/bin" to listOf("ls", "cat"),
            "/usr/local/bin" to listOf("git")
        )
        val sanitized = PathSanitizer.sanitize(
            rawPath = "/usr/bin${File.pathSeparator}/usr/local/bin",
            dirLister = { dir -> fakeListing[dir].orEmpty() }
        )
        sanitized shouldBe "/usr/bin${File.pathSeparator}/usr/local/bin"
    }
})
