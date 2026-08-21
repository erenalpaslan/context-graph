package io.contextgraph.benchmark.orchestrator

import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.model.Question

/** One (question, arm, repeat) the orchestrator must run -- the atomic unit [RunPlanner] enumerates. */
data class PlannedRun(
    val question: Question,
    val arm: Arm,
    val repeatIndex: Int
)

/**
 * The full scope of one profile's execution, computed by [RunPlanner.plan] before anything runs.
 * Announcing [totalRuns] (and the repo/question/arm/repeat breakdown it is built from) before
 * execution starts is what AC-17 means by "`full` plans and announces its run count before
 * executing anything" -- this type is what gets announced, and it is pure data: building one
 * costs nothing and touches no filesystem, network, or agent.
 */
data class RunPlan(
    val repos: List<CorpusRepo>,
    val questions: List<Question>,
    val plannedRuns: List<PlannedRun>
) {
    val totalRuns: Int get() = plannedRuns.size
}
