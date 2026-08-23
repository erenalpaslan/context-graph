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
            // The neighbour keeps whatever its kind is worth and gains nothing for existing.
            r.score(neighbour, null) shouldBe relevance("anything").score(neighbour, null)
        }
    }

    context("a candidate's name is deliberately not a signal") {
        test("sharing one word with a long question earns nothing") {
            // CodeGraph awards 60 here -- more than the best full-text hit's 40 -- and on a
            // question asked in prose that is a coincidence detector rather than a match:
            // measured, MRR 0.4259 -> 0.3000 with the tier in. See the note in QueryRelevance.kt.
            val r = relevance("how does the cart decide which items to keep")
            r.score(node("Cart"), null) shouldBe r.score(node("Unrelated"), null)
        }

        test("nor does a name the query is a prefix or substring of") {
            relevance("cart").score(node("CartService"), null) shouldBe
                relevance("cart").score(node("Unrelated"), null)
            relevance("cartservice").score(node("CartService"), null) shouldBe
                relevance("cartservice").score(node("Unrelated"), null)
        }

    }

    context("the kind ladder") {
        val r = relevance("anything at all")

        test("behaviour ranks above structure, structure above containers, containers above files") {
            val function = r.score(node("X", NodeType.Function), null)
            val klass = r.score(node("X", NodeType.Class), null)
            val module = r.score(node("X", NodeType.Module), null)
            val file = r.score(node("X", NodeType.CodeFile), null)

            function shouldBeGreaterThan klass
            klass shouldBeGreaterThan module
            module shouldBeGreaterThan file
        }

        test("a file node gets nothing from its kind -- exactly zero, not merely little") {
            r.score(node("X", NodeType.CodeFile), null) shouldBe 0.0
        }

        test("a node type nobody anticipated gets nothing rather than an accidental rung") {
            r.score(node("X", NodeType.Custom("SomethingNew")), null) shouldBe 0.0
        }
    }

    // Every comparison in this block holds the label fixed and varies only the file, so it is the
    // path rule under test and never the name ladder leaking in.
    context("documentation and tests are de-prioritised") {
        val r = relevance("how does the widget move")
        val plain = "src/thing.kt"

        test("a prose file scores below an ordinary source file") {
            r.score(node("Thing"), plain) shouldBeGreaterThan r.score(node("Thing"), "src/thing.md")
        }

        test("a compound documentation directory is recognised without being named") {
            // dev-docs, developer_docs, api-documentation: one rule about the words of a path
            // segment, not a list of the directory names one repository happens to use.
            listOf("dev-docs/thing.html", "developer_docs/thing.html", "api-documentation/thing.html")
                .forEach { path ->
                    r.score(node("Thing"), plain) shouldBeGreaterThan r.score(node("Thing"), path)
                }
        }

        test("a test file scores below an ordinary source file") {
            listOf("src/ThingTest.kt", "src/thing.test.kt", "test/thing.kt", "src/__tests__/thing.kt")
                .forEach { path ->
                    r.score(node("Thing"), plain) shouldBeGreaterThan r.score(node("Thing"), path)
                }
        }

        test("a filename that merely ends in those letters is not a test") {
            // `latest` ends in "test". Whole words only.
            r.score(node("Thing"), "src/latest.kt") shouldBe r.score(node("Thing"), plain)
        }

        test("the artefact type alone is enough, with no path at all") {
            r.score(node("Thing"), null) shouldBeGreaterThan r.score(node("Thing", NodeType.MarkdownFile), null)
        }
    }

    context("the waiver") {
        test("a query about documentation does not de-prioritise documentation") {
            val asking = relevance("where is the setup documentation")
            asking.score(node("Thing"), "src/thing.md") shouldBe asking.score(node("Thing"), "src/thing.kt")
        }

        test("a query about tests does not de-prioritise tests") {
            val asking = relevance("which test covers the widget")
            asking.score(node("Thing"), "src/ThingTest.kt") shouldBe asking.score(node("Thing"), "src/thing.kt")
        }

        test("the waivers are independent -- asking about tests still de-prioritises documentation") {
            val asking = relevance("which test covers the widget")
            asking.score(node("Thing"), "src/thing.kt") shouldBeGreaterThan asking.score(node("Thing"), "src/thing.md")
        }
    }
})
