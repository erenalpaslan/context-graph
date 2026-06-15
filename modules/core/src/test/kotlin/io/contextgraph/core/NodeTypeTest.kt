package io.contextgraph.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class NodeTypeTest : FunSpec({

    test("fromString returns correct built-in types") {
        NodeType.fromString("CodeFile") shouldBe NodeType.CodeFile
        NodeType.fromString("Class")    shouldBe NodeType.Class
        NodeType.fromString("Function") shouldBe NodeType.Function
        NodeType.fromString("Concept")  shouldBe NodeType.Concept
        NodeType.fromString("DatabaseTable") shouldBe NodeType.DatabaseTable
    }

    test("fromString unknown value returns Custom") {
        val result = NodeType.fromString("MyCoolType")
        result.shouldBeInstanceOf<NodeType.Custom>()
        (result as NodeType.Custom).name shouldBe "MyCoolType"
    }

    test("fromStringOrNull returns null for unknown values") {
        NodeType.fromStringOrNull("DoesNotExist") shouldBe null
    }

    test("fromStringOrNull returns type for known values") {
        NodeType.fromStringOrNull("Class") shouldBe NodeType.Class
    }

    test("stringify roundtrips all built-in types") {
        val types = listOf(
            NodeType.CodeFile, NodeType.MarkdownFile, NodeType.PDF, NodeType.DatabaseSchema,
            NodeType.ConfigFile, NodeType.Function, NodeType.Class, NodeType.Method,
            NodeType.DatabaseTable, NodeType.Column, NodeType.Concept, NodeType.Claim,
            NodeType.Decision, NodeType.Requirement
        )
        types.forEach { type ->
            NodeType.fromString(NodeType.stringify(type)) shouldBe type
        }
    }

    test("stringify Custom returns the custom name") {
        NodeType.stringify(NodeType.Custom("Widget")) shouldBe "Widget"
    }
})
