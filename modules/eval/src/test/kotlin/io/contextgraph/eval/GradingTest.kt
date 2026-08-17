package io.contextgraph.eval

import io.contextgraph.mcp.ExploreBlastRadiusHit
import io.contextgraph.mcp.ExploreEdgeView
import io.contextgraph.mcp.ExploreModule
import io.contextgraph.mcp.ExploreResponse
import io.contextgraph.mcp.ExploreSymbol
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [Grading] is the pure scoring function behind the explore side of the eval harness: no
 * database, no network, no LiteLLM -- just "does this [ExploreResponse] surface one of the
 * question's known-correct paths." Fast unit tests, independent of the live-repo run that
 * `io.contextgraph.eval.Main` performs.
 */
class GradingTest : FunSpec({
    val question = EvalQuestion(
        id = "T1",
        text = "irrelevant for this test",
        type = QuestionType.LOCATE,
        correctAnswer = "modules/query/src/main/kotlin/io/contextgraph/query/VerbatimSource.kt",
        expectedPathFragments = listOf("query/src/main/kotlin/io/contextgraph/query/VerbatimSource.kt")
    )

    fun symbol(path: String?) = ExploreSymbol(
        id = "id", label = "VerbatimSource", nodeType = "class", fqn = "VerbatimSource",
        path = path, lineStart = 1, lineEnd = 2, confidence = 0.9, elided = false,
        source = null, edges = emptyList(), blastRadius = emptyList()
    )

    fun module(path: String) = ExploreModule(
        id = "m1", name = "query", path = path, description = null, undescribed = true,
        descriptionStale = false, matchMethod = "keyword", score = 1.0
    )

    fun response(symbols: List<ExploreSymbol> = emptyList(), modules: List<ExploreModule> = emptyList()) =
        ExploreResponse(
            question = question.text, tokenBudget = 15_000, estimatedTokensUsed = 42,
            blastRadiusConfidenceFloor = 0.8, modules = modules, symbols = symbols,
            truncated = false, empty = symbols.isEmpty() && modules.isEmpty()
        )

    test("hits via a matching symbol path") {
        val result = Grading.grade(question, response(symbols = listOf(symbol("modules/query/src/main/kotlin/io/contextgraph/query/VerbatimSource.kt"))))
        result.hit shouldBe true
        result.matchedVia shouldBe "symbol"
    }

    test("hits via a matching module path when no symbol matches") {
        val result = Grading.grade(question, response(modules = listOf(module("modules/query"))))
        result.hit shouldBe false // module path "modules/query" does not contain the full fragment
    }

    test("hits via a module path that does contain the fragment") {
        val result = Grading.grade(
            question,
            response(modules = listOf(module("modules/query/src/main/kotlin/io/contextgraph/query/VerbatimSource.kt")))
        )
        result.hit shouldBe true
        result.matchedVia shouldBe "module"
    }

    test("misses when nothing matches") {
        val result = Grading.grade(question, response(symbols = listOf(symbol("modules/cli/src/main/kotlin/io/contextgraph/cli/Main.kt"))))
        result.hit shouldBe false
        result.matchedVia shouldBe null
    }

    test("misses when a symbol has no provenance path at all") {
        val result = Grading.grade(question, response(symbols = listOf(symbol(null))))
        result.hit shouldBe false
    }

    test("records exactly one tool call and the response's estimated token cost") {
        val result = Grading.grade(question, response())
        result.toolCalls shouldBe 1
        result.estimatedTokens shouldBe 42
    }

    test("hits via a matched symbol's edge target -- explore's own answer to 'what calls this'") {
        val edgeSymbol = symbol("modules/other/Other.kt").copy(
            edges = listOf(
                ExploreEdgeView(
                    type = "calls", direction = "incoming",
                    nodeId = "modules/query/src/main/kotlin/io/contextgraph/query/VerbatimSource.kt#VerbatimSource.read",
                    label = "read", confidence = 0.9, rung = "file_imports"
                )
            )
        )
        val result = Grading.grade(question, response(symbols = listOf(edgeSymbol)))
        result.hit shouldBe true
        result.matchedVia shouldBe "edge"
    }

    test("hits via a matched symbol's blast radius -- reverse-BFS impact analysis") {
        val radiusSymbol = symbol("modules/other/Other.kt").copy(
            blastRadius = listOf(
                ExploreBlastRadiusHit(
                    nodeId = "modules/query/src/main/kotlin/io/contextgraph/query/VerbatimSource.kt#VerbatimSource.read",
                    label = "read", nodeType = "function", hops = 1, viaEdgeType = "calls", confidence = 0.87
                )
            )
        )
        val result = Grading.grade(question, response(symbols = listOf(radiusSymbol)))
        result.hit shouldBe true
        result.matchedVia shouldBe "blast_radius"
    }
})
