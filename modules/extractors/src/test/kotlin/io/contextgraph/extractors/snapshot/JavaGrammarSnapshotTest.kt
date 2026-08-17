package io.contextgraph.extractors.snapshot

import io.contextgraph.core.ExtractionDiagnostic
import io.contextgraph.core.NodeType
import io.contextgraph.treesitter.requireFqnOnDeclarations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Slice 02's golden-snapshot test: proves Java extraction against a fixture project
 * (`test-fixtures/java-grammar`) covering everything AC-1/AC-3/AC-7/AC-8 require --
 * types, methods, fields, imports with true line spans; two same-named declarations in
 * different files staying distinct; stable IDs across reindexes; and a syntax error not
 * taking down the rest of the file or the rest of the run.
 *
 * Every assertion here goes through [io.contextgraph.extractors.TreeSitterExtractor] --
 * the same class `IngestPipeline` calls per file -- not a parallel test-only code path.
 */
class JavaGrammarSnapshotTest : FunSpec({
    val fixtureRoot = RepoRoot.fixture("java-grammar")
    val snapshotFile = fixtureRoot.resolve(GoldenSnapshotHarness.SNAPSHOT_FILE_NAME)

    test("extraction matches the committed golden snapshot") {
        GoldenSnapshotHarness.assertMatchesGoldenSnapshot(fixtureRoot, snapshotFile)
    }

    test("AC-1: a type, its methods, fields and an import all carry correct start/end lines") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val typeNode = extracted.nodes.first { it.id.value == "Auth/UserService.java#UserService" }
        typeNode.type shouldBe NodeType.Class
        val typeProvenance = typeNode.provenance.single()
        typeProvenance.lineStart shouldBe 6  // "public class UserService {"
        typeProvenance.lineEnd shouldBe 26   // closing brace of the class body

        val methodNode = extracted.nodes.first { it.id.value == "Auth/UserService.java#UserService.save(String)" }
        methodNode.type shouldBe NodeType.Method
        methodNode.provenance.single().lineStart shouldBe 14
        methodNode.provenance.single().lineEnd shouldBe 16

        val fieldNode = extracted.nodes.first { it.id.value == "Auth/UserService.java#UserService.name" }
        fieldNode.type shouldBe NodeType.Custom("Field")
        fieldNode.provenance.single().lineStart shouldBe 7

        val importNode = extracted.nodes.first { it.id.value == "Auth/UserService.java#import:java.util.List" }
        importNode.type shouldBe NodeType.Module
        importNode.provenance.single().lineStart shouldBe 3
    }

    test("AC-7 / AC-8: two files declaring UserService produce two distinct nodes with distinct IDs and their own file") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        val userServiceNodes = extracted.nodes.filter { it.label == "UserService" && it.type == NodeType.Class }

        userServiceNodes shouldHaveSize 2
        val ids = userServiceNodes.map { it.id.value }.toSet()
        ids shouldBe setOf("Auth/UserService.java#UserService", "Billing/UserService.java#UserService")

        val paths = userServiceNodes.map { it.provenance.single().path }.toSet()
        paths shouldBe setOf("Auth/UserService.java", "Billing/UserService.java")
    }

    test("node IDs are byte-identical across two extraction runs over unchanged source") {
        val first = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        val second = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        first.nodes.map { it.id.value }.sorted() shouldBe second.nodes.map { it.id.value }.sorted()
        first.edges.map { it.id.value }.sorted() shouldBe second.edges.map { it.id.value }.sorted()
    }

    test("AC-3: a syntax error still produces a file node, a diagnostic, and does not affect other files") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val brokenFileNode = extracted.nodes.firstOrNull { it.id.value == "Broken/Broken.java" }
        brokenFileNode shouldNotBe null

        extracted.diagnostics.map { it.severity } shouldContain ExtractionDiagnostic.Severity.WARNING
        extracted.diagnostics.any { it.message.contains("Broken/Broken.java") } shouldBe true

        // Other files in the same fixture run still extracted fully.
        extracted.nodes.any { it.id.value == "Auth/UserService.java#UserService" } shouldBe true
        extracted.nodes.any { it.id.value == "Shapes/Shapes.java#Shape" } shouldBe true
    }

    test("interface, enum and record all produce type nodes with the right kind") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        extracted.nodes.first { it.id.value == "Shapes/Shapes.java#Shape" }.type shouldBe NodeType.Custom("Interface")
        extracted.nodes.first { it.id.value == "Shapes/Shapes.java#Color" }.type shouldBe NodeType.Custom("Enum")
        extracted.nodes.first { it.id.value == "Shapes/Shapes.java#Point" }.type shouldBe NodeType.Custom("Record")
        extracted.nodes.first { it.id.value == "Shapes/Shapes.java#Color.RED" }.type shouldBe NodeType.Custom("Field")
    }

    test("fqn is populated as a grouping key, not identity") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val method = extracted.nodes.first { it.id.value == "Auth/UserService.java#UserService.save(String)" }
        val overload = extracted.nodes.first { it.id.value == "Auth/UserService.java#UserService.save(String,int)" }

        val methodFqn = (method.properties["fqn"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val overloadFqn = (overload.properties["fqn"] as? kotlinx.serialization.json.JsonPrimitive)?.content

        methodFqn shouldBe "com.example.auth.UserService.save"
        overloadFqn shouldBe "com.example.auth.UserService.save" // same fqn: a grouping key, distinct IDs above prove it isn't identity
    }

    test("every declaration node carries a non-blank fqn (the guard slices 04-08 inherit by copying this test)") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        // Throws if any node whose ID is not the bare file ID (i.e. every declaration and
        // import) is missing io.contextgraph.treesitter.FQN_PROPERTY or has it blank.
        requireFqnOnDeclarations(extracted.nodes)
    }
})
