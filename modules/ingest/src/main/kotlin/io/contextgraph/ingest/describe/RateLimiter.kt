package io.contextgraph.ingest.describe

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Enforces `litellm.rateLimitPerMinute` for real, with a sliding window over actual call
 * timestamps -- the config option was dead until this: settable in
 * [io.contextgraph.core.LiteLlmConfig] but read by nothing, with only a hardcoded `Semaphore(3)`
 * (see `SemanticExtractor`) doing any throttling at all. `maxPerMinute <= 0` is treated as
 * unlimited: it is not a sensible value for anyone to set expecting "block everything," and a
 * throttle with no cap should not be a footgun.
 *
 * [waitMillis] is the pure decision function, exercised directly by most tests. [acquire] is a
 * thin coroutine wrapper: it records a call the moment it is allowed to proceed, blocking with a
 * real [delay] otherwise, so a caller behind a full window pays wall-clock time, not the caller
 * ahead of it re-executing anything.
 */
class RateLimiter(
    private val maxPerMinute: Int,
    private val windowMs: Long = 60_000,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val mutex = Mutex()
    private val timestamps = ArrayDeque<Long>()

    /**
     * Milliseconds to wait, given [recentCallTimestamps] (any age, unfiltered) and the current
     * time [now], before one more call is allowed under [maxPerMinute] per [windowMs]. Zero
     * means "allowed now."
     */
    fun waitMillis(now: Long, recentCallTimestamps: List<Long>): Long {
        if (maxPerMinute <= 0) return 0
        val inWindow = recentCallTimestamps.filter { now - it < windowMs }
        if (inWindow.size < maxPerMinute) return 0
        val oldest = inWindow.min()
        return (windowMs - (now - oldest)).coerceAtLeast(0)
    }

    suspend fun acquire() {
        if (maxPerMinute <= 0) return
        while (true) {
            val wait = mutex.withLock {
                val now = clock()
                while (timestamps.isNotEmpty() && now - timestamps.first() >= windowMs) timestamps.removeFirst()
                val w = waitMillis(now, timestamps)
                if (w == 0L) timestamps.addLast(now)
                w
            }
            if (wait == 0L) return
            delay(wait)
        }
    }
}
