package io.contextgraph.benchmark.judge

import java.security.MessageDigest
import kotlin.math.ceil

/**
 * Picks a reproducible ~20% subsample of scored run ids for kappa
 * validation (AC-14): the same set of ids always yields the same subsample,
 * regardless of the order they're passed in, and without any system
 * randomness, wall clock, or caller-supplied seed to keep in sync.
 *
 * Reproducibility is achieved by ranking each id on a stable hash of its own
 * content rather than shuffling the list with a seeded RNG -- a seeded
 * shuffle would still depend on the input's iteration order, which callers
 * (e.g. a `Map` of scored runs) don't generally guarantee.
 */
object KappaSubsampleSelector {
    const val DEFAULT_FRACTION = 0.2

    fun select(runIds: List<String>, fraction: Double = DEFAULT_FRACTION): List<String> {
        require(fraction in 0.0..1.0) { "fraction must be within [0, 1], was $fraction" }

        val distinctIds = runIds.distinct()
        val sampleSize = ceil(distinctIds.size * fraction).toInt()
        return distinctIds
            .sortedBy { stableRank(it) }
            .take(sampleSize)
    }

    private fun stableRank(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
