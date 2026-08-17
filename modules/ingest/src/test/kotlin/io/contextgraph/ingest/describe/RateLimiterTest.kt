package io.contextgraph.ingest.describe

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import kotlinx.coroutines.runBlocking

/**
 * `litellm.rateLimitPerMinute` used to be dead config -- settable, read by nothing, with only a
 * hardcoded `Semaphore(3)` doing any throttling (see SemanticExtractor). This is the real
 * rate limiter describe-modules wires it into. [waitMillis] is the pure decision function
 * (exhaustively unit-testable without touching a clock or a coroutine); [acquire] is the thin
 * real-time wrapper around it exercised by the one timing-based test below.
 */
class RateLimiterTest : FunSpec({

    test("waitMillis is zero while under the per-window budget") {
        val limiter = RateLimiter(maxPerMinute = 2, windowMs = 60_000)
        limiter.waitMillis(now = 0, recentCallTimestamps = emptyList()) shouldBe 0
        limiter.waitMillis(now = 0, recentCallTimestamps = listOf(0L)) shouldBe 0
    }

    test("waitMillis is positive once the window is full, sized to free the oldest slot") {
        val limiter = RateLimiter(maxPerMinute = 2, windowMs = 60_000)
        val wait = limiter.waitMillis(now = 1_000, recentCallTimestamps = listOf(0L, 500L))
        wait shouldBe 59_000
    }

    test("a call outside the window no longer counts against the budget") {
        val limiter = RateLimiter(maxPerMinute = 1, windowMs = 60_000)
        limiter.waitMillis(now = 61_000, recentCallTimestamps = listOf(0L)) shouldBe 0
    }

    test("maxPerMinute <= 0 means unlimited: never waits") {
        val limiter = RateLimiter(maxPerMinute = 0, windowMs = 60_000)
        limiter.waitMillis(now = 0, recentCallTimestamps = List(100) { 0L }) shouldBe 0
    }

    test("acquire lets the first N calls through immediately, then actually delays") {
        // Short real window so the test runs fast while still exercising real delay() timing.
        val limiter = RateLimiter(maxPerMinute = 2, windowMs = 200)
        val elapsed = runBlocking {
            val start = System.nanoTime()
            limiter.acquire()
            limiter.acquire()
            limiter.acquire() // must wait roughly until the first call's slot frees
            (System.nanoTime() - start) / 1_000_000
        }
        elapsed shouldBeGreaterThanOrEqual 150
        elapsed shouldBeLessThan 2_000
    }
})
