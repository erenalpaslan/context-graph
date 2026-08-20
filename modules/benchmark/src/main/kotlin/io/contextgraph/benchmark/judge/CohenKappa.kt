package io.contextgraph.benchmark.judge

/** Raw agreement rate and Cohen's kappa over a set of two-rater hit/miss verdicts. */
data class KappaResult(
    val n: Int,
    val agreementRate: Double,
    val kappa: Double
)

/**
 * Pure Cohen's kappa computation over paired boolean verdicts -- (rater A's
 * hit/miss, rater B's hit/miss) for the same fact. No LLM call, no I/O; see
 * `CohenKappaTest` for the worked values (including the textbook 50-item
 * example) this is checked against.
 *
 * `pe` is the chance-agreement rate expected from each rater's own marginal
 * hit rate. When both raters are perfectly uniform on the same category
 * (`pe == 1.0`), the standard `(po - pe) / (1 - pe)` formula is undefined
 * (0/0); that can only happen when `po == 1.0` too (raters agreeing on every
 * item), so it's treated as perfect agreement rather than left as NaN.
 */
object CohenKappa {
    fun compute(pairs: List<Pair<Boolean, Boolean>>): KappaResult {
        require(pairs.isNotEmpty()) { "Cohen's kappa needs at least one rated pair" }

        val n = pairs.size
        val agree = pairs.count { (a, b) -> a == b }
        val po = agree.toDouble() / n

        val aHitRate = pairs.count { it.first }.toDouble() / n
        val bHitRate = pairs.count { it.second }.toDouble() / n
        val pe = (aHitRate * bHitRate) + ((1.0 - aHitRate) * (1.0 - bHitRate))

        val kappa = if (pe >= 1.0) 1.0 else (po - pe) / (1.0 - pe)
        return KappaResult(n = n, agreementRate = po, kappa = kappa)
    }
}
