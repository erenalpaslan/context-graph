package io.contextgraph.benchmark.corpus

import io.contextgraph.benchmark.retrieval.CodeGraphExecutionException
import io.contextgraph.benchmark.retrieval.CodeGraphProcess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.hours

/**
 * The timeout path, which is the one failure mode this suite cannot afford to get wrong.
 *
 * `IndexIntegrityGate` exists because a Keycloak index that a stopped Gradle daemon truncated
 * after an hour looked healthy on every filesystem-level signal -- directory present, 1.1 GB,
 * 228,587 nodes -- while 6 of the 22 gold-cited files had never been written. A `codegraph init`
 * that blows its budget is the same hazard wearing a different hat: it leaves a real, plausible,
 * incomplete `.codegraph/` directory behind. If that were recorded as a successful ingest, the
 * retrieval run would score a truncated index as a complete one and publish the result.
 *
 * So the contract is: fail loudly, record nothing, and never retry. These tests hold it against a
 * stub binary rather than the real one, so they run everywhere and take milliseconds instead of
 * the four hours the real budget allows.
 */
class CodeGraphIndexerTimeoutTest : FunSpec({

    /**
     * A stand-in `codegraph` that answers `--version` immediately but sleeps far past any budget
     * on real work.
     *
     * The `--version` case is load-bearing, not decoration: `CodeGraphIndexer` first asks
     * `CodeGraphProcess.isAvailable`, which runs `--version`. A stub that slept on *that* too
     * would be reported as an uninstalled binary and return a recorded-absence cost -- so the
     * test would pass through the wrong branch entirely and prove nothing about timeouts.
     */
    fun sleepingBinary(dir: Path): String {
        val script = dir.resolve("slow-codegraph.sh")
        Files.writeString(script, "#!/bin/sh\ncase \"\$1\" in --version) echo 1.5.0; exit 0;; esac\nsleep 60\n")
        script.toFile().setExecutable(true)
        return script.toAbsolutePath().toString()
    }

    /** A stand-in that "succeeds" without writing an index -- the quieter half of the same hazard. */
    fun lyingBinary(dir: Path): String {
        val script = dir.resolve("lying-codegraph.sh")
        Files.writeString(script, "#!/bin/sh\ncase \"\$1\" in --version) echo 1.5.0; exit 0;; esac\nexit 0\n")
        script.toFile().setExecutable(true)
        return script.toAbsolutePath().toString()
    }

    test("a timed-out index fails loudly, names the budget, and records no ingest") {
        val dir = Files.createTempDirectory("codegraph-timeout-")
        try {
            val workingCopy = Files.createDirectories(dir.resolve("copy"))

            val ex = shouldThrow<CodeGraphIndexFailedException> {
                CodeGraphIndexer.index(
                    repoId = "fixture-repo",
                    workingCopy = workingCopy,
                    codegraphPath = sleepingBinary(dir),
                    timeout = 300.milliseconds
                )
            }

            ex.repoId shouldBe "fixture-repo"
            ex.message!! shouldContain "exceeded its"
            // Not retried: the truncated-index incident happened *because* a slow index was
            // re-run, so the message says so rather than leaving it to convention.
            ex.message!! shouldContain "Not retried"

            // Nothing partial was left behind that a later run could mistake for a real index.
            CodeGraphIndexer.isIndexed(workingCopy) shouldBe false
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("a timeout throws rather than returning an absent-cost record -- the two are not interchangeable") {
        // A missing BINARY is a recorded absence and must not break corpus prep for everyone.
        // A timed-out RUN is a failure and must stop it. Collapsing the two would let a blown
        // budget slide through as "not indexed", which reads as benign.
        val dir = Files.createTempDirectory("codegraph-timeout-vs-absent-")
        try {
            val workingCopy = Files.createDirectories(dir.resolve("copy"))

            val absent = CodeGraphIndexer.index(
                repoId = "fixture-repo",
                workingCopy = workingCopy,
                codegraphPath = "codegraph-does-not-exist-on-this-machine"
            )
            absent.absentReason!! shouldContain "did not resolve"
            absent.durationMillis shouldBe null

            shouldThrow<CodeGraphIndexFailedException> {
                CodeGraphIndexer.index(
                    repoId = "fixture-repo",
                    workingCopy = workingCopy,
                    codegraphPath = sleepingBinary(dir),
                    timeout = 300.milliseconds
                )
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("an index that reports success but wrote nothing is refused, not recorded") {
        val dir = Files.createTempDirectory("codegraph-lying-")
        try {
            val workingCopy = Files.createDirectories(dir.resolve("copy"))

            val ex = shouldThrow<CodeGraphIndexFailedException> {
                CodeGraphIndexer.index(
                    repoId = "fixture-repo",
                    workingCopy = workingCopy,
                    codegraphPath = lyingBinary(dir),
                    timeout = 1.hours
                )
            }
            ex.message!! shouldContain "wrote no ${CodeGraphIndexer.INDEX_DIR_NAME} directory"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("the process wrapper marks a timeout as such, so the caller can say which budget blew") {
        val dir = Files.createTempDirectory("codegraph-process-timeout-")
        try {
            val ex = shouldThrow<CodeGraphExecutionException> {
                CodeGraphProcess.run(
                    args = listOf("anything"),
                    codegraphPath = sleepingBinary(dir),
                    timeout = 300.milliseconds
                )
            }
            ex.timedOut shouldBe true
            ex.message!! shouldContain "must never be scored as a complete one"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("a non-zero exit is a failure but NOT a timeout -- the flag distinguishes them") {
        val dir = Files.createTempDirectory("codegraph-process-exit-")
        try {
            val failing = dir.resolve("failing.sh")
            Files.writeString(failing, "#!/bin/sh\necho 'bad project path' >&2\nexit 2\n")
            failing.toFile().setExecutable(true)

            val ex = shouldThrow<CodeGraphExecutionException> {
                CodeGraphProcess.run(listOf("explore"), codegraphPath = failing.toAbsolutePath().toString())
            }
            ex.timedOut shouldBe false
            ex.message!! shouldContain "exit 2"
            ex.message!! shouldContain "bad project path"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
})
