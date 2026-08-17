package io.contextgraph.extractors.snapshot

import io.contextgraph.core.ExtractionDiagnostic
import io.contextgraph.core.NodeType
import io.contextgraph.treesitter.requireFqnOnDeclarations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonPrimitive

/**
 * Slice 08's golden-snapshot test: proves Objective-C extraction against a fixture
 * project (`test-fixtures/objc-grammar`) covering AC-1/AC-2/AC-32 -- classes, categories,
 * protocols, properties and methods with correct start/end lines; a class's `@interface`
 * (header) and `@implementation` (`.m`) staying two distinct declaration-site nodes that
 * nonetheless share one `fqn`; and a macro-broken file still producing a file node, a
 * diagnostic, and not affecting the rest of the run.
 *
 * Every assertion here goes through [io.contextgraph.extractors.TreeSitterExtractor] --
 * the same class `IngestPipeline` calls per file -- not a parallel test-only code path.
 */
class ObjectiveCGrammarSnapshotTest : FunSpec({
    val fixtureRoot = RepoRoot.fixture("objc-grammar")
    val snapshotFile = fixtureRoot.resolve(GoldenSnapshotHarness.SNAPSHOT_FILE_NAME)

    test("extraction matches the committed golden snapshot") {
        GoldenSnapshotHarness.assertMatchesGoldenSnapshot(fixtureRoot, snapshotFile)
    }

    test("AC-1: a class, its methods, properties and an import all carry correct start/end lines") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val classNode = extracted.nodes.first { it.id.value == "Widgets/Widget.h#Widget" }
        classNode.type shouldBe NodeType.Class
        val classProvenance = classNode.provenance.single()
        classProvenance.lineStart shouldBe 4 // "@interface Widget : NSObject <NSCopying>"
        classProvenance.lineEnd shouldBe 15 // "@end"

        val methodNode = extracted.nodes.first { it.id.value == "Widgets/Widget.h#Widget.doThing:with:" }
        methodNode.type shouldBe NodeType.Method
        methodNode.provenance.single().lineStart shouldBe 11
        methodNode.provenance.single().lineEnd shouldBe 11

        val propNode = extracted.nodes.first { it.id.value == "Widgets/Widget.h#Widget.name" }
        propNode.type shouldBe NodeType.Custom("Property")
        propNode.provenance.single().lineStart shouldBe 6

        val importNode = extracted.nodes.first { it.id.value == "Widgets/Widget.h#import:Foundation/Foundation.h" }
        importNode.type shouldBe NodeType.Module
        importNode.provenance.single().lineStart shouldBe 1
    }

    test("AC-2: @interface Foo in the header and @implementation Foo in the .m are two distinct nodes sharing one fqn") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val headerNode = extracted.nodes.first { it.id.value == "Widgets/Widget.h#Widget" }
        val implNode = extracted.nodes.first { it.id.value == "Widgets/Widget.m#Widget" }

        // Distinct declaration sites: different IDs, different files, different lines.
        headerNode.id shouldNotBe implNode.id
        headerNode.provenance.single().path shouldBe "Widgets/Widget.h"
        implNode.provenance.single().path shouldBe "Widgets/Widget.m"

        // Same grouping key.
        val headerFqn = (headerNode.properties["fqn"] as JsonPrimitive).content
        val implFqn = (implNode.properties["fqn"] as JsonPrimitive).content
        headerFqn shouldBe "Widget"
        implFqn shouldBe "Widget"
        headerFqn shouldBe implFqn

        // The same rule holds one level down, for a method declared in the header and
        // defined in the .m.
        val headerMethod = extracted.nodes.first { it.id.value == "Widgets/Widget.h#Widget.doThing:with:" }
        val implMethod = extracted.nodes.first { it.id.value == "Widgets/Widget.m#Widget.doThing:with:" }
        headerMethod.id shouldNotBe implMethod.id
        val headerMethodFqn = (headerMethod.properties["fqn"] as JsonPrimitive).content
        val implMethodFqn = (implMethod.properties["fqn"] as JsonPrimitive).content
        headerMethodFqn shouldBe "Widget.doThing:with:"
        headerMethodFqn shouldBe implMethodFqn
    }

    test("a category is extracted with the extended type identified, same shape in header and implementation") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val categoryHeaderNode = extracted.nodes.first { it.id.value == "Widgets/Widget+Extras.h#Widget.Extras" }
        categoryHeaderNode.type shouldBe NodeType.Custom("Category")
        categoryHeaderNode.label shouldBe "Widget (Extras)"
        (categoryHeaderNode.properties["extendedType"] as JsonPrimitive).content shouldBe "Widget"

        val categoryImplNode = extracted.nodes.first { it.id.value == "Widgets/Widget+Extras.m#Widget.Extras" }
        val headerFqn = (categoryHeaderNode.properties["fqn"] as JsonPrimitive).content
        val implFqn = (categoryImplNode.properties["fqn"] as JsonPrimitive).content
        headerFqn shouldBe "Widget.Extras"
        headerFqn shouldBe implFqn
    }

    test("a keyword method selector produces a usable, colon-joined name") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val method = extracted.nodes.first { it.id.value == "Widgets/Widget.h#Widget.doThing:with:" }
        method.label shouldBe "doThing:with:"
        (method.properties["selector"] as JsonPrimitive).content shouldBe "doThing:with:"
        (method.properties["kind"] as JsonPrimitive).content shouldBe "instance_method"

        val classMethod = extracted.nodes.first { it.id.value == "Widgets/Widget.h#Widget.widgetWithName:" }
        (classMethod.properties["kind"] as JsonPrimitive).content shouldBe "class_method"
    }

    test("a protocol declaration and its declared conformances are extracted") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val protocolNode = extracted.nodes.first { it.id.value == "Protocols/WidgetDelegate.h#WidgetDelegate" }
        protocolNode.type shouldBe NodeType.Custom("Protocol")
        (protocolNode.properties["protocols"] as JsonPrimitive).content shouldBe "NSObject"

        val requiredMethod = extracted.nodes.first { it.id.value == "Protocols/WidgetDelegate.h#WidgetDelegate.widgetDidFinish:" }
        requiredMethod.type shouldBe NodeType.Method

        // @optional method still extracted.
        val optionalMethod = extracted.nodes.first { it.id.value == "Protocols/WidgetDelegate.h#WidgetDelegate.widgetDidFail" }
        optionalMethod.type shouldBe NodeType.Method

        // Widget declares conformance to NSCopying.
        val widget = extracted.nodes.first { it.id.value == "Widgets/Widget.h#Widget" }
        (widget.properties["protocols"] as JsonPrimitive).content shouldBe "NSCopying"
    }

    test("node IDs are byte-identical across two extraction runs over unchanged source") {
        val first = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        val second = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        first.nodes.map { it.id.value }.sorted() shouldBe second.nodes.map { it.id.value }.sorted()
        first.edges.map { it.id.value }.sorted() shouldBe second.edges.map { it.id.value }.sorted()
    }

    test("a macro-broken file still produces a file node, a diagnostic, and other files still index") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val brokenFileNode = extracted.nodes.firstOrNull { it.id.value == "Broken/Broken.m" }
        brokenFileNode shouldNotBe null

        extracted.diagnostics.map { it.severity } shouldContain ExtractionDiagnostic.Severity.WARNING
        extracted.diagnostics.any { it.message.contains("Broken/Broken.m") } shouldBe true

        // Other files in the same fixture run still extracted fully.
        extracted.nodes.any { it.id.value == "Widgets/Widget.h#Widget" } shouldBe true
        extracted.nodes.any { it.id.value == "Protocols/WidgetDelegate.h#WidgetDelegate" } shouldBe true
    }

    test("NS_ASSUME_NONNULL / NS_ENUM defeat parsing locally but declarations after them still recover") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        extracted.diagnostics.any { it.message.contains("Macros/EnumTypes.h") } shouldBe true

        // Despite the diagnostic, the @interface after the broken NS_ENUM typedef is
        // still recovered by the recursive walk.
        val recovered = extracted.nodes.firstOrNull { it.id.value == "Macros/EnumTypes.h#WidgetFactory" }
        recovered shouldNotBe null
        recovered!!.type shouldBe NodeType.Class
    }

    test("every declaration node carries a non-blank fqn") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        requireFqnOnDeclarations(extracted.nodes)
    }
})
