package io.contextgraph.eval

import io.contextgraph.mcp.ExploreResponse
import kotlinx.serialization.Serializable

/**
 * One question's scored outcome for the `explore` side of the eval (AC-34: "tool-calls and
 * tokens to a correct answer are recorded for both"). [toolCalls] is always 1 -- `explore` is
 * the one-fat-call design slice 17 built specifically so an agent never needs a second
 * round-trip. [hit] is the automatic, substring-based correctness signal; [Reporter] pairs it
 * with the human judgement call recorded in the harness's output for anyone auditing it.
 */
@Serializable
data class ExploreGradeResult(
    val questionId: String,
    val toolCalls: Int,
    val estimatedTokens: Int,
    val hit: Boolean,
    val matchedVia: String?,
    val matchedPath: String?,
    val truncated: Boolean,
    val blastRadiusConfidenceFloor: Double,
    val topSymbols: List<String>,
    val topModules: List<String>
)

/**
 * Pure scoring: does an [ExploreResponse] surface at least one of [EvalQuestion]'s known
 * ground-truth paths -- as a matched symbol's own provenance path, a matched module's path,
 * or (crucially for [QuestionType.IMPACT] questions, which ask "what depends on / what calls
 * this" rather than "where is this") one of that symbol's resolved **edges** or **blast
 * radius** hits. Those two are explore's actual answer mechanism for impact questions per
 * the slice 17 design (AC-37/interview): grading only `symbols[].path` would silently
 * penalise explore for correctly using the mechanism built for exactly this question type.
 * No I/O, no LiteLLM, no database -- keeps the correctness *signal* testable independent of
 * the live run that produces the responses being graded.
 */
object Grading {
    fun grade(question: EvalQuestion, response: ExploreResponse): ExploreGradeResult {
        val symbolHit = response.symbols.firstOrNull { symbol ->
            val path = symbol.path
            path != null && question.expectedPathFragments.any { path.contains(it) }
        }
        val moduleHit = response.modules.firstOrNull { module ->
            question.expectedPathFragments.any { module.path.contains(it) }
        }
        val edgeHit = response.symbols.asSequence()
            .flatMap { it.edges.asSequence() }
            .firstOrNull { edge -> question.expectedPathFragments.any { edge.nodeId.contains(it) } }
        val blastRadiusHit = response.symbols.asSequence()
            .flatMap { it.blastRadius.asSequence() }
            .firstOrNull { hit -> question.expectedPathFragments.any { hit.nodeId.contains(it) } }

        val matchedVia: String?
        val matchedPath: String?
        when {
            symbolHit != null -> {
                matchedVia = "symbol"
                matchedPath = symbolHit.path
            }
            moduleHit != null -> {
                matchedVia = "module"
                matchedPath = moduleHit.path
            }
            edgeHit != null -> {
                matchedVia = "edge"
                matchedPath = edgeHit.nodeId
            }
            blastRadiusHit != null -> {
                matchedVia = "blast_radius"
                matchedPath = blastRadiusHit.nodeId
            }
            else -> {
                matchedVia = null
                matchedPath = null
            }
        }

        return ExploreGradeResult(
            questionId = question.id,
            toolCalls = 1,
            estimatedTokens = response.estimatedTokensUsed,
            hit = matchedVia != null,
            matchedVia = matchedVia,
            matchedPath = matchedPath,
            truncated = response.truncated,
            blastRadiusConfidenceFloor = response.blastRadiusConfidenceFloor,
            // Wider than a typical "top 5" so a miss is reportable, not just a bare "NO" --
            // the task file requires losses to be as visible as wins, which means showing
            // what explore surfaced instead of the expected answer.
            topSymbols = response.symbols.take(15).map { "${it.label} (${it.path}:${it.lineStart})" },
            topModules = response.modules.take(5).map { "${it.name} (${it.path})" }
        )
    }
}
