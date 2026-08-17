package io.contextgraph.extractors.snapshot

import io.contextgraph.core.NodeType
import io.contextgraph.treesitter.requireFqnOnDeclarations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive

/**
 * Slice 05's golden-snapshot test for the TSX dialect (`test-fixtures/tsx-grammar`),
 * covering AC-1/AC-32 for `.tsx`: a plain functional component bound to `const`, a
 * HOC-wrapped (`memo(...)`) component, a styled-components value with no function body to
 * inspect, a plain (non-component) helper function, and a default-exported component.
 */
class TsxGrammarSnapshotTest : FunSpec({
    val fixtureRoot = RepoRoot.fixture("tsx-grammar")
    val snapshotFile = fixtureRoot.resolve(GoldenSnapshotHarness.SNAPSHOT_FILE_NAME)

    test("extraction matches the committed golden snapshot") {
        GoldenSnapshotHarness.assertMatchesGoldenSnapshot(fixtureRoot, snapshotFile)
    }

    test("AC: a JSX component bound to const is extracted and identifiable as a component") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val button = extracted.nodes.first { it.id.value == "Components/Button.tsx#Button" }
        button.type shouldBe NodeType.Component
        (button.properties["kind"] as JsonPrimitive).content shouldBe "arrow_function"
    }

    test("a HOC-wrapped component is still extracted under the outer const's name") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val memoButton = extracted.nodes.first { it.id.value == "Components/Button.tsx#MemoButton" }
        memoButton.type shouldBe NodeType.Component
        memoButton.label shouldBe "MemoButton"
    }

    test("a styled-components value with no inspectable function body still gets a declaration") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val styled = extracted.nodes.first { it.id.value == "Components/Button.tsx#StyledButton" }
        styled.type shouldBe NodeType.Component // uppercase-leading name heuristic, no JSX body to inspect
        (styled.properties["kind"] as JsonPrimitive).content shouldBe "value"
    }

    test("a plain helper function returning no JSX is not misclassified as a component") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val helper = extracted.nodes.first { it.id.value == "Components/Button.tsx#helperNotAComponent(number,number)" }
        helper.type shouldBe NodeType.Function
    }

    test("a default-exported component is extracted with its own name and identified as a component") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val icon = extracted.nodes.first { it.id.value == "Components/Button.tsx#Icon()" }
        icon.type shouldBe NodeType.Component
        icon.label shouldBe "Icon"
    }

    test("every declaration node carries a non-blank fqn") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        requireFqnOnDeclarations(extracted.nodes)
    }
})
