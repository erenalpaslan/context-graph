package io.contextgraph.eval

import io.contextgraph.mcp.ExploreEngine

/**
 * Runs every [EvalTaskSet.QUESTIONS] question through a live [ExploreEngine] and grades each
 * response with [Grading]. This is the re-runnable half of AC-34: re-run against a freshly
 * indexed graph after a later change and the numbers regenerate. The other half -- answering
 * the same questions via plain Read/Grep -- has no equivalent programmatic form (that *is*
 * the thing being compared against) and is recorded separately in the harness's report.
 */
class ExploreEvalRunner(private val engine: ExploreEngine) {
    suspend fun run(questions: List<EvalQuestion> = EvalTaskSet.QUESTIONS): List<ExploreGradeResult> =
        questions.map { question -> Grading.grade(question, engine.explore(question.text)) }
}
