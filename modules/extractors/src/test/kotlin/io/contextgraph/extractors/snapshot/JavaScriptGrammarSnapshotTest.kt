package io.contextgraph.extractors.snapshot

import io.contextgraph.core.EdgeType
import io.contextgraph.core.NodeType
import io.contextgraph.treesitter.requireFqnOnDeclarations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Slice 05's golden-snapshot test for the JavaScript dialect
 * (`test-fixtures/javascript-grammar`), covering AC-1/AC-32 for `.js` and `.jsx`: a barrel
 * re-export, a plain function, an arrow function bound to `const`, a class, a named default
 * export, and a `.jsx` file routed to the JavaScript grammar with its component identified.
 */
class JavaScriptGrammarSnapshotTest : FunSpec({
    val fixtureRoot = RepoRoot.fixture("javascript-grammar")
    val snapshotFile = fixtureRoot.resolve(GoldenSnapshotHarness.SNAPSHOT_FILE_NAME)

    test("extraction matches the committed golden snapshot") {
        GoldenSnapshotHarness.assertMatchesGoldenSnapshot(fixtureRoot, snapshotFile)
    }

    test("AC: .js and .jsx files both produce symbol nodes, routed to the JavaScript grammar") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        extracted.nodes.any { it.id.value == "Utils/math.js#add(a,b)" } shouldBe true
        extracted.nodes.any { it.id.value == "Widgets/Widget.jsx#Widget" } shouldBe true
    }

    test("AC: a re-export produces an import/reference edge naming the source module") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val reexport = extracted.nodes.first { it.id.value == "Utils/math.js#export:./extra" }
        reexport.type shouldBe NodeType.Module
        reexport.label shouldBe "./extra"
        extracted.edges.any {
            it.source.value == "Utils/math.js" && it.target.value == "Utils/math.js#export:./extra" && it.type == EdgeType.Imports
        } shouldBe true
    }

    test("AC: an arrow function assigned to a const is extracted as a named function, not skipped") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val subtract = extracted.nodes.first { it.id.value == "Utils/math.js#subtract" }
        subtract.type shouldBe NodeType.Function
        subtract.label shouldBe "subtract"
    }

    test("a class and its method are extracted") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        extracted.nodes.first { it.id.value == "Utils/math.js#Calculator" }.type shouldBe NodeType.Class
        extracted.nodes.first { it.id.value == "Utils/math.js#Calculator.add(amount)" }.type shouldBe NodeType.Method
    }

    test("AC: a named default export is extracted with the declaration's own name") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val square = extracted.nodes.first { it.id.value == "Utils/math.js#square(x)" }
        square.label shouldBe "square"
    }

    test("AC: a JSX component in a .jsx file is extracted and identifiable as a component") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        extracted.nodes.first { it.id.value == "Widgets/Widget.jsx#Widget" }.type shouldBe NodeType.Component
        extracted.nodes.first { it.id.value == "Widgets/Widget.jsx#Panel(props)" }.type shouldBe NodeType.Component
    }

    test("every declaration node carries a non-blank fqn") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        requireFqnOnDeclarations(extracted.nodes)
    }
})
