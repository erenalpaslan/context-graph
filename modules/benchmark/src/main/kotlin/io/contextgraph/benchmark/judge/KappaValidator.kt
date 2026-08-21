package io.contextgraph.benchmark.judge

import io.contextgraph.benchmark.model.JudgeScore
import io.contextgraph.benchmark.model.Question
import kotlinx.serialization.Serializable

/**
 * One already-scored run, as far as kappa validation needs to know about
 * it: enough to re-score it (question + answer) plus the primary judge's
 * verdict to compare the second judge against.
 */
data class ScoredRun(
    val runId: String,
    val question: Question,
    val answerText: String,
    val primaryScore: JudgeScore
)

/**
 * Output of one kappa validation pass (AC-14). Defined here rather than in
 * `model/` -- these are judge-internal types nothing else needs, and that
 * package (owned by slice 01 per `modules/benchmark/README.md`) was locked
 * for this round while 02/04/06 edited it concurrently. Report to the Lead
 * if this needs folding into `BenchmarkRun` later.
 */
@Serializable
data class KappaValidationResult(
    val primaryJudgeModel: String,
    val secondaryJudgeModel: String,
    val totalRuns: Int,
    val sampledRunIds: List<String>,
    val agreementRate: Double,
    val kappa: Double,
    val secondaryScores: List<JudgeScore>
)

/**
 * One-off confidence check on the judge (AC-14), not part of every-run
 * scoring: re-scores a reproducible ~20% subsample of already-scored runs
 * with a second, independent judge model, and reports how much the two
 * judges agree at the individual-fact level, both as a raw percentage and
 * as Cohen's kappa.
 */
class KappaValidator(private val scorer: JudgeScorer) {
    suspend fun validate(
        scoredRuns: List<ScoredRun>,
        secondaryJudgeModel: String,
        fraction: Double = KappaSubsampleSelector.DEFAULT_FRACTION
    ): KappaValidationResult {
        require(scoredRuns.isNotEmpty()) { "kappa validation needs at least one scored run" }

        val byId = scoredRuns.associateBy { it.runId }
        val sampledIds = KappaSubsampleSelector.select(scoredRuns.map { it.runId }, fraction)
        val sampled = sampledIds.map { byId.getValue(it) }

        val secondaryScores = sampled.map { run ->
            scorer.score(
                runId = run.runId,
                judgeModel = secondaryJudgeModel,
                question = run.question,
                answerText = run.answerText
            )
        }

        val pairs = sampled.zip(secondaryScores).flatMap { (run, secondary) ->
            val primaryByFactId = run.primaryScore.factScores.associateBy { it.factId }
            val secondaryByFactId = secondary.factScores.associateBy { it.factId }
            // The gold-fact set on the question, not whichever judge's
            // response happens to be complete, is the source of truth for
            // what's being paired: a fact id missing from either side is a
            // miss on that side, never a dropped pair (the same failure
            // mode JudgeScorer reconciles against, here at the pairing seam).
            run.question.goldFacts.map { fact ->
                val primaryHit = primaryByFactId[fact.id]?.hit ?: false
                val secondaryHit = secondaryByFactId[fact.id]?.hit ?: false
                primaryHit to secondaryHit
            }
        }
        val kappaResult = CohenKappa.compute(pairs)

        return KappaValidationResult(
            primaryJudgeModel = scoredRuns.first().primaryScore.judgeModel,
            secondaryJudgeModel = secondaryJudgeModel,
            totalRuns = scoredRuns.size,
            sampledRunIds = sampledIds,
            agreementRate = kappaResult.agreementRate,
            kappa = kappaResult.kappa,
            secondaryScores = secondaryScores
        )
    }
}
