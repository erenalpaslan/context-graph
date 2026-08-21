package io.contextgraph.benchmark.runner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Direct proof that [CliSentinel] is what it claims to be: a real executable, resolvable via
 * PATH, that records an invocation and fails loudly. [AgentRunnerTest] covers the case this
 * exists for (regex-evading indirection); this file proves the primitive itself works in
 * isolation, with no ContaminationGuard or AgentRunner involved.
 */
class CliSentinelTest : FunSpec({

    test("a fresh sentinel has no recorded invocations") {
        val sentinel = CliSentinel.createTemp()
        try {
            sentinel.invocationCount() shouldBe 0
        } finally {
            sentinel.cleanup()
        }
    }

    test("running 'contextgraph' with the sentinel's directory on PATH executes the sentinel, not a real CLI") {
        val sentinel = CliSentinel.createTemp()
        try {
            val process = ProcessBuilder("/bin/sh", "-c", "contextgraph search something")
                .apply {
                    environment().clear()
                    environment()["PATH"] = sentinel.binDir.toString()
                }
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            exitCode shouldBe 127
            output shouldBe "contextgraph: command not found\n"
            sentinel.invocationCount() shouldBe 1
        } finally {
            sentinel.cleanup()
        }
    }

    test("indirection through a shell variable still resolves to the sentinel via PATH") {
        val sentinel = CliSentinel.createTemp()
        try {
            val process = ProcessBuilder("/bin/sh", "-c", "X=contextgraph; \$X search leaked-secret")
                .apply {
                    environment().clear()
                    environment()["PATH"] = sentinel.binDir.toString()
                }
                .start()
            process.waitFor()

            sentinel.invocationCount() shouldBe 1
        } finally {
            sentinel.cleanup()
        }
    }

    test("multiple invocations across a run all increment the same counter") {
        val sentinel = CliSentinel.createTemp()
        try {
            repeat(3) {
                ProcessBuilder("/bin/sh", "-c", "contextgraph report")
                    .apply {
                        environment().clear()
                        environment()["PATH"] = sentinel.binDir.toString()
                    }
                    .start()
                    .waitFor()
            }
            sentinel.invocationCount() shouldBe 3
        } finally {
            sentinel.cleanup()
        }
    }

    test("cleanup removes the sentinel's directory") {
        val sentinel = CliSentinel.createTemp()
        sentinel.cleanup()
        Files.exists(sentinel.binDir) shouldBe false
    }
})
