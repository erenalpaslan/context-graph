package io.contextgraph.benchmark.judge

import io.contextgraph.benchmark.model.FactScore

/**
 * The seam between [JudgeScorer] (and [KappaValidator]) and whatever
 * actually calls a model. Real implementations (see [LlmJudgeClient]) call
 * out to Opus 5 or a second, independent model over HTTP; tests substitute a
 * fake that returns canned verdicts, so the scorer and the kappa validator
 * are fully testable without a network call (task 05 note).
 *
 * The signature is also where AC-11 is enforced structurally: this method
 * can only ever be handed a [JudgeInput], never an `AgentRunRecord` or an
 * `Arm` -- there is no parameter here for a tool-call trace or a metric to
 * travel in on.
 *
 * Returns per-fact hit/miss, not free text (AC-13): [JudgeScorer] derives
 * the accuracy score itself from these, rather than trusting a model to
 * compute and report a number.
 */
interface JudgeClient {
    suspend fun scoreFacts(input: JudgeInput, judgeModel: String): List<FactScore>
}
