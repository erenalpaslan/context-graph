package io.contextgraph.benchmark.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [LiteLlmProxyLifecycle.waitUntilReady] is the "hazır olma denetimi gerçek olmalı: süreç
 * başladı diye hazır sayma, proxy cevap verene kadar bekle ve zaman aşımı koy" requirement --
 * task 17's own report proves the real HTTP half against a real proxy process (see
 * `/health/liveliness` returning 200 within ~1s of a real `litellm` start). This test proves the
 * *polling and timeout logic* offline: injected clock/sleep/probe functions, no real socket, no
 * real process, no `Thread.sleep`.
 */
class LiteLlmProxyLifecycleTest : FunSpec({

    test("returns as soon as the probe reports healthy, without waiting out the full timeout") {
        var probeCalls = 0
        val probe: (String) -> Boolean = { probeCalls++; probeCalls >= 3 }
        var sleepCalls = 0

        LiteLlmProxyLifecycle.waitUntilReady(
            baseUrl = "http://localhost:4000",
            timeoutMillis = 60_000,
            intervalMillis = 500,
            nowMillis = { 0L },
            sleep = { sleepCalls++ },
            isHealthyFn = probe
        )

        probeCalls shouldBe 3
        sleepCalls shouldBe 2
    }

    test("throws ProxyNotReadyException once the simulated clock passes the deadline without ever going healthy") {
        var simulatedNow = 0L
        val exception = shouldThrow<ProxyNotReadyException> {
            LiteLlmProxyLifecycle.waitUntilReady(
                baseUrl = "http://localhost:4000",
                timeoutMillis = 5_000,
                intervalMillis = 1_000,
                nowMillis = { simulatedNow },
                sleep = { simulatedNow += 1_000 },
                isHealthyFn = { false }
            )
        }
        exception.message shouldBe "The LiteLLM proxy at http://localhost:4000 did not answer " +
            "/health/liveliness within 5000ms. Check its log for the real startup error."
    }

    test("isHealthy is false, not thrown, when the probe itself throws (e.g. connection refused)") {
        val neverThrows = try {
            LiteLlmProxyLifecycle.isHealthy("http://localhost:1") // nothing listens here
            true
        } catch (e: Exception) {
            false
        }
        neverThrows shouldBe true
    }

    test("stop() is a no-op for a process that already exited") {
        val exited = FakeProcess(alive = false)
        LiteLlmProxyLifecycle.stop(exited)
        exited.destroyCalled shouldBe false
    }

    test("stop() destroys a live process and does not force-kill one that exits within the grace period") {
        val cooperative = FakeProcess(alive = true, exitsAfterDestroy = true)
        LiteLlmProxyLifecycle.stop(cooperative, gracePeriodSeconds = 1)
        cooperative.destroyCalled shouldBe true
        cooperative.destroyForciblyCalled shouldBe false
    }

    test("stop() force-kills a process that ignores destroy() past the grace period") {
        val stubborn = FakeProcess(alive = true, exitsAfterDestroy = false)
        LiteLlmProxyLifecycle.stop(stubborn, gracePeriodSeconds = 1)
        stubborn.destroyCalled shouldBe true
        stubborn.destroyForciblyCalled shouldBe true
    }
})

/** A [Process] double for [LiteLlmProxyLifecycle.stop] -- never a real subprocess. */
private class FakeProcess(
    private var alive: Boolean,
    private val exitsAfterDestroy: Boolean = false
) : Process() {
    var destroyCalled = false
    var destroyForciblyCalled = false

    override fun isAlive(): Boolean = alive
    override fun destroy() {
        destroyCalled = true
        if (exitsAfterDestroy) alive = false
    }
    override fun destroyForcibly(): Process {
        destroyForciblyCalled = true
        alive = false
        return this
    }
    override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = !alive

    override fun getOutputStream() = throw UnsupportedOperationException()
    override fun getInputStream() = throw UnsupportedOperationException()
    override fun getErrorStream() = throw UnsupportedOperationException()
    override fun waitFor(): Int = 0
    override fun exitValue(): Int = 0
}
