package io.contextgraph.query

import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * The scoring rules themselves, with no database and no graph in the way -- one term at a time,
 * each stated as the comparison it is meant to make.
 *
 * Every fixture here is invented for this file. None of the paths, identifiers or extensions comes
 * from the benchmark's question sets: the rules have to hold for a repository nobody has written
 * questions for, and a test that borrowed the gold set's vocabulary could not tell the difference.
 */
class QueryRelevanceTest : FunSpec({

    fun node(label: String, type: NodeType = NodeType.Class, id: String = label) =
        GraphNode(NodeId(id), type, label)

    fun relevance(query: String, seeds: List<NodeId> = emptyList()) = QueryRelevance.of(query, seeds)

    context("the search layer's ordering") {
        test("the best search hit scores above the worst, and both above a candidate that was not one") {
            val first = node("First")
            val last = node("Last")
            val neighbour = node("Neighbour")
            val r = relevance("anything", seeds = listOf(first.id, last.id))

            r.score(first, null) shouldBeGreaterThan r.score(last, null)
            r.score(last, null) shouldBeGreaterThan r.score(neighbour, null)
            r.score(neighbour, null) shouldBe 0.0
        }
    }

    context("documentation and tests are de-prioritised") {
        val r = relevance("how does the widget move")

        test("a prose file scores below an ordinary source file") {
            r.score(node("Guide"), "src/guide.md") shouldBeGreaterThan Double.NEGATIVE_INFINITY
            r.score(node("Widget"), "src/widget.kt") shouldBeGreaterThan r.score(node("Guide"), "src/guide.md")
        }

        test("a compound documentation directory is recognised without being named") {
            // dev-docs, developer_docs, api-documentation: one rule about the words of a path
            // segment, not a list of the directory names one repository happens to use.
            listOf("dev-docs/page.html", "developer_docs/page.html", "api-documentation/page.html")
                .forEach { path ->
                    r.score(node("Page"), "src/widget.kt") shouldBeGreaterThan r.score(node("Page"), path)
                }
        }

        test("a test file scores below an ordinary source file") {
            listOf("src/WidgetTest.kt", "src/widget.test.kt", "test/widget.kt", "src/__tests__/widget.kt")
                .forEach { path ->
                    r.score(node("Widget"), "src/widget.kt") shouldBeGreaterThan r.score(node("Widget"), path)
                }
        }

        test("a filename that merely ends in those letters is not a test") {
            // `latest` ends in "test"; `contest` contains it. Whole words only.
            r.score(node("Latest"), "src/latest.kt") shouldBe r.score(node("Latest"), "src/widget.kt")
        }

        test("the artefact type alone is enough, with or without a path") {
            r.score(node("Readme", NodeType.MarkdownFile), null) shouldBeGreaterThan Double.NEGATIVE_INFINITY
            r.score(node("Widget"), null) shouldBeGreaterThan r.score(node("Readme", NodeType.MarkdownFile), null)
        }
    }

    context("the waiver") {
        test("a query about documentation does not de-prioritise documentation") {
            val asking = relevance("where is the setup documentation")
            asking.score(node("Guide"), "src/guide.md") shouldBe asking.score(node("Widget"), "src/widget.kt")
        }

        test("a query about tests does not de-prioritise tests") {
            val asking = relevance("which test covers the widget")
            asking.score(node("Widget"), "src/WidgetTest.kt") shouldBe asking.score(node("Widget"), "src/widget.kt")
        }

        test("the waivers are independent -- asking about tests still de-prioritises documentation") {
            val asking = relevance("which test covers the widget")
            asking.score(node("Widget"), "src/widget.kt") shouldBeGreaterThan asking.score(node("Guide"), "src/guide.md")
        }
    }
})
