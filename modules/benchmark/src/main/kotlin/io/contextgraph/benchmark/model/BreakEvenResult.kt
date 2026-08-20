package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * AC-21's "ingest cost amortizes in N questions" verdict for one repo.
 * Deliberately a closed set of outcomes rather than a nullable
 * `Double?` — task 06's notes require the zero/negative-saving case to be
 * stated plainly rather than attempted as a division, and a sealed result
 * makes that case a distinct, unmissable branch for callers (including
 * slice 07's formatter) rather than a number that happens to be `Infinity`
 * or negative.
 */
@Serializable
sealed interface BreakEvenResult {

    /** ContextGraph's WITH_TOOLS arm costs less per question than WITHOUT_TOOLS, so ingest amortizes. */
    @Serializable
    data class Amortizes(
        val ingestCostUsd: Double,
        val savingPerQuestionUsd: Double,
        val questionsToBreakEven: Double
    ) : BreakEvenResult

    /**
     * WITH_TOOLS costs the same or more per question than WITHOUT_TOOLS
     * ([savingPerQuestionUsd] is `<= 0`) — ingest never amortizes on cost
     * alone for this repo. No division is attempted.
     */
    @Serializable
    data class NotAmortizing(
        val ingestCostUsd: Double,
        val savingPerQuestionUsd: Double
    ) : BreakEvenResult

    /** One or both arms have no cost data for this repo yet, so break-even cannot be computed at all. */
    @Serializable
    data class InsufficientData(val reason: String) : BreakEvenResult
}
