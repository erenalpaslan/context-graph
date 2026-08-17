package io.contextgraph.extractors.snapshot

import io.contextgraph.core.ExtractionDiagnostic
import io.contextgraph.core.NodeType
import io.contextgraph.treesitter.requireFqnOnDeclarations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain as shouldContainString
import kotlinx.serialization.json.JsonPrimitive

/**
 * Slice 07's golden-snapshot test: proves Swift extraction against a fixture project
 * (`test-fixtures/swift-grammar`) -- the first Swift coverage this project has ever had.
 * Covers types (class/struct/enum/protocol/actor/extension), nested types, enum cases
 * with associated values, functions/methods/initialisers, computed and stored
 * properties, type aliases, imports, declared conformances, a syntax-error file, and the
 * risk areas called out in the task: SwiftUI-style property wrappers and `body` blocks,
 * generics with `where` clauses, `async`/`throws`, and a custom operator declaration.
 *
 * Every assertion here goes through [io.contextgraph.extractors.TreeSitterExtractor] --
 * the same class `IngestPipeline` calls per file -- not a parallel test-only code path.
 */
class SwiftGrammarSnapshotTest : FunSpec({
    val fixtureRoot = RepoRoot.fixture("swift-grammar")
    val snapshotFile = fixtureRoot.resolve(GoldenSnapshotHarness.SNAPSHOT_FILE_NAME)

    test("extraction matches the committed golden snapshot") {
        GoldenSnapshotHarness.assertMatchesGoldenSnapshot(fixtureRoot, snapshotFile)
    }

    test("a type, its methods, properties and an import all carry correct start/end lines") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val typeNode = extracted.nodes.first { it.id.value == "Auth/UserService.swift#UserService" }
        typeNode.type shouldBe NodeType.Class
        val typeProvenance = typeNode.provenance.single()
        typeProvenance.lineStart shouldBe 3
        typeProvenance.lineEnd shouldBe 23

        val methodNode = extracted.nodes.first { it.id.value == "Auth/UserService.swift#UserService.save(String)" }
        methodNode.type shouldBe NodeType.Method
        methodNode.provenance.single().lineStart shouldBe 11
        methodNode.provenance.single().lineEnd shouldBe 13

        val propNode = extracted.nodes.first { it.id.value == "Auth/UserService.swift#UserService.loginCount" }
        propNode.type shouldBe NodeType.Custom("Field")
        propNode.provenance.single().lineStart shouldBe 5

        val importNode = extracted.nodes.first { it.id.value == "Auth/UserService.swift#import:Foundation" }
        importNode.type shouldBe NodeType.Module
        importNode.provenance.single().lineStart shouldBe 1
    }

    test("an extension's members carry the extended type's fqn, grouping them with the primary declaration") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        fun fqnOf(nodeId: String) =
            (extracted.nodes.first { it.id.value == nodeId }.properties["fqn"] as JsonPrimitive).content

        // Primary declaration in Auth/UserService.swift.
        fqnOf("Auth/UserService.swift#UserService") shouldBe "UserService"
        fqnOf("Auth/UserService.swift#UserService.save(String)") shouldBe "UserService.save"

        // Extension in a *different* file: the extension's own node and its member both
        // carry fqn "UserService" / "UserService.greet" -- identical to what a member
        // declared directly inside UserService would get.
        val extensionMembers = extracted.nodes.filter { it.id.value.startsWith("Auth/UserServiceGreetable.swift#") }
        extensionMembers.map { it.id.value }.toSet() shouldBe setOf(
            "Auth/UserServiceGreetable.swift#UserService+ext@1",
            "Auth/UserServiceGreetable.swift#UserService+ext@1.greet()",
            "Auth/UserServiceGreetable.swift#UserService+ext@7",
            "Auth/UserServiceGreetable.swift#UserService+ext@7.Config",
            "Auth/UserServiceGreetable.swift#UserService+ext@7.Config.retries"
        )
        fqnOf("Auth/UserServiceGreetable.swift#UserService+ext@1") shouldBe "UserService"
        fqnOf("Auth/UserServiceGreetable.swift#UserService+ext@1.greet()") shouldBe "UserService.greet"
        // A type nested inside an extension is grouped under the extended type too.
        fqnOf("Auth/UserServiceGreetable.swift#UserService+ext@7.Config") shouldBe "UserService.Config"
        fqnOf("Auth/UserServiceGreetable.swift#UserService+ext@7.Config.retries") shouldBe "UserService.Config.retries"
    }

    test("two extension blocks of the same type in one file get distinct declaration-site IDs, never merged") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        val extensionTypeNodes = extracted.nodes.filter {
            it.id.value.startsWith("Auth/UserServiceGreetable.swift#UserService+ext@") &&
                (it.properties["kind"] as? JsonPrimitive)?.content == "extension"
        }
        extensionTypeNodes.map { it.id.value }.toSet() shouldBe setOf(
            "Auth/UserServiceGreetable.swift#UserService+ext@1",
            "Auth/UserServiceGreetable.swift#UserService+ext@7"
        )
        val lines = extensionTypeNodes.map { it.provenance.single().lineStart }.toSet()
        lines shouldBe setOf(1, 7)
    }

    test("protocol declarations and a type's declared conformances are both extracted") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val protocolNode = extracted.nodes.first { it.id.value == "Shapes/Shapes.swift#Shape" }
        protocolNode.type shouldBe NodeType.Custom("Protocol")

        val extensionNode = extracted.nodes.first { it.id.value == "Auth/UserServiceGreetable.swift#UserService+ext@1" }
        val conformances = extensionNode.properties["conformances"]
        conformances shouldNotBe null
    }

    test("nested types are extracted with the enclosing type in their scope chain") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        val nested = extracted.nodes.firstOrNull { it.id.value == "Auth/UserService.swift#UserService.AuditLog" }
        nested shouldNotBe null
        nested!!.type shouldBe NodeType.Class
        val recordMethod = extracted.nodes.firstOrNull {
            it.id.value == "Auth/UserService.swift#UserService.AuditLog.record()"
        }
        recordMethod shouldNotBe null
    }

    test("enum cases with associated values are extracted without breaking the parse") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val successCase = extracted.nodes.first { it.id.value == "Shapes/Shapes.swift#Outcome.success" }
        successCase.type shouldBe NodeType.Custom("Field")
        val associatedValues = (successCase.properties["associatedValues"] as? JsonPrimitive)?.content
        associatedValues shouldNotBe null
        associatedValues!! shouldContainString "value"

        val simpleCase = extracted.nodes.first { it.id.value == "Shapes/Shapes.swift#Color.red" }
        simpleCase.properties["associatedValues"] shouldBe null
    }

    test("SwiftUI property wrappers, generics with where clauses, async/throws and a custom operator all parse cleanly") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        // property wrapper + `some View` body
        extracted.nodes.any { it.id.value == "SwiftUI/ContentView.swift#ContentView.body" } shouldBe true
        extracted.nodes.any { it.id.value == "SwiftUI/ContentView.swift#ContentView.count" } shouldBe true

        // async/throws method still extracted
        extracted.nodes.any { it.id.value == "SwiftUI/ContentView.swift#ContentView.increment()" } shouldBe true

        // actor
        extracted.nodes.first { it.id.value == "SwiftUI/ContentView.swift#Counter" }.type shouldBe NodeType.Custom("Actor")

        // generics + where clause didn't break the parse; the type is still extracted
        extracted.nodes.any { it.id.value == "SwiftUI/ContentView.swift#Container" } shouldBe true

        // typealias
        extracted.nodes.any { it.id.value == "SwiftUI/ContentView.swift#Handler" } shouldBe true

        // custom operator function declaration parses and is extracted as a top-level function
        extracted.nodes.any { it.id.value.startsWith("SwiftUI/ContentView.swift#+++(") } shouldBe true

        // No diagnostic for this file: none of the risk-area constructs broke the parse.
        extracted.diagnostics.none { it.message.contains("SwiftUI/ContentView.swift") } shouldBe true
    }

    test("a Swift file the grammar cannot parse still produces a file node, a diagnostic, and other files continue to index") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val brokenFileNode = extracted.nodes.firstOrNull { it.id.value == "Broken/Broken.swift" }
        brokenFileNode shouldNotBe null

        extracted.diagnostics.map { it.severity } shouldContain ExtractionDiagnostic.Severity.WARNING
        extracted.diagnostics.any { it.message.contains("Broken/Broken.swift") } shouldBe true

        // Other files in the same fixture run still extracted fully.
        extracted.nodes.any { it.id.value == "Auth/UserService.swift#UserService" } shouldBe true
        extracted.nodes.any { it.id.value == "Shapes/Shapes.swift#Shape" } shouldBe true
    }

    test("fqn is populated as a grouping key: two overloads share one fqn but have distinct IDs") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val method = extracted.nodes.first { it.id.value == "Auth/UserService.swift#UserService.save(String)" }
        val overload = extracted.nodes.first { it.id.value == "Auth/UserService.swift#UserService.save(String,Int)" }

        val methodFqn = (method.properties["fqn"] as? JsonPrimitive)?.content
        val overloadFqn = (overload.properties["fqn"] as? JsonPrimitive)?.content

        methodFqn shouldBe "UserService.save"
        overloadFqn shouldBe "UserService.save"
    }

    test("every declaration node carries a non-blank fqn") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        requireFqnOnDeclarations(extracted.nodes)
    }
})
