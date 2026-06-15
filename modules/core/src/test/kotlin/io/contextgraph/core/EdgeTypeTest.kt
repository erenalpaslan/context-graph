package io.contextgraph.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class EdgeTypeTest : FunSpec({

    test("fromString is case-insensitive for known types") {
        EdgeType.fromString("contains")   shouldBe EdgeType.Contains
        EdgeType.fromString("CONTAINS")   shouldBe EdgeType.Contains
        EdgeType.fromString("DependsOn")  shouldBe EdgeType.DependsOn
        EdgeType.fromString("depends_on") shouldBe EdgeType.DependsOn
        EdgeType.fromString("similar_to") shouldBe EdgeType.SimilarTo
        EdgeType.fromString("SimilarTo")  shouldBe EdgeType.SimilarTo
    }

    test("fromString unknown value returns Custom") {
        val result = EdgeType.fromString("owns")
        result.shouldBeInstanceOf<EdgeType.Custom>()
        (result as EdgeType.Custom).name shouldBe "owns"
    }

    test("stringify roundtrips all built-in edge types") {
        val types = listOf(
            EdgeType.Contains, EdgeType.Defines, EdgeType.Imports, EdgeType.Calls,
            EdgeType.DependsOn, EdgeType.Implements, EdgeType.Tests, EdgeType.References,
            EdgeType.Cites, EdgeType.Supports, EdgeType.Contradicts, EdgeType.Explains,
            EdgeType.Uses, EdgeType.SimilarTo, EdgeType.DerivedFrom
        )
        types.forEach { type ->
            EdgeType.fromString(EdgeType.stringify(type)) shouldBe type
        }
    }
})
