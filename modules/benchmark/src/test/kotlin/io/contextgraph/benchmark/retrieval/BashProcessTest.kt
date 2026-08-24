package io.contextgraph.benchmark.retrieval

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * M4: [BashProcess.requireDrainThreadsFinished], the check that used to be a discarded
 * `join(5_000)` call.
 *
 * A real hung drain thread is not something a fast, deterministic unit test can reproduce through
 * [BashProcess.SYSTEM] itself -- that would mean either waiting out a real 5s budget or racing a
 * genuinely flaky pipe. [BashProcess.requireDrainThreadsFinished] is extracted precisely so this
 * does not have to: it is exercised directly against real [Thread] objects standing in for the
 * drain threads, with [joinMillis][BashProcess.requireDrainThreadsFinished] shrunk to a few
 * milliseconds so "still alive after the join budget" is forced rather than awaited.
 */
class BashProcessTest : FunSpec({

    /** A thread that is still running well past any millisecond-scale join budget. */
    fun stillRunningThread(): Thread =
        Thread { Thread.sleep(2_000) }.apply { isDaemon = true; start() }

    /** A thread that has already finished by the time anything joins it. */
    fun alreadyFinishedThread(): Thread =
        Thread {}.apply { isDaemon = true; start(); join() }

    test("a drain thread still alive after the join budget throws a named exception") {
        val thrown = shouldThrow<BashCommandExecutionException> {
            BashProcess.requireDrainThreadsFinished(
                command = listOf("grep", "-rc", "-e", "token", "."),
                readers = listOf(alreadyFinishedThread(), stillRunningThread()),
                joinMillis = 5
            )
        }
        thrown.message!! shouldContain "grep -rc -e token ."
        thrown.message!! shouldContain "1 of its 2 output-draining thread(s)"
        thrown.message!! shouldContain "cannot be trusted as complete"
    }

    test("every drain thread already finished returns quietly") {
        // Must not throw: the ordinary case, where the process's own exit already closed both
        // pipes and both readers hit EOF well inside the budget.
        BashProcess.requireDrainThreadsFinished(
            command = listOf("grep"),
            readers = listOf(alreadyFinishedThread(), alreadyFinishedThread()),
            joinMillis = 5_000
        )
    }

    test("both drain threads still alive names both in the count") {
        val thrown = shouldThrow<BashCommandExecutionException> {
            BashProcess.requireDrainThreadsFinished(
                command = listOf("grep"),
                readers = listOf(stillRunningThread(), stillRunningThread()),
                joinMillis = 5
            )
        }
        thrown.message!! shouldContain "2 of its 2 output-draining thread(s)"
    }

    test("an empty reader list -- the omitted-parameter call shape -- never throws") {
        // The default join budget (5_000ms, matching production) is exercised here rather than
        // asserted as a literal: there is nothing to observe about a budget's length except
        // whether waiting it out changes the outcome, and an empty list finishes instantly either
        // way. What this pins is the call shape [BashProcess.SYSTEM] actually uses --
        // `requireDrainThreadsFinished(command, readers)` with `joinMillis` omitted -- compiling
        // and returning normally.
        BashProcess.requireDrainThreadsFinished(command = listOf("grep"), readers = emptyList())
    }
})
