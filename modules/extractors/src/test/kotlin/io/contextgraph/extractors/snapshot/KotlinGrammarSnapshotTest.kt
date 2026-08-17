package io.contextgraph.extractors.snapshot

import io.contextgraph.core.ExtractionDiagnostic
import io.contextgraph.core.NodeType
import io.contextgraph.treesitter.requireFqnOnDeclarations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonPrimitive

/**
 * Slice 04's golden-snapshot test: proves Kotlin extraction against a fixture project
 * (`test-fixtures/kotlin-grammar`) covering everything the slice's acceptance criteria
 * require -- types (class/interface/object/companion object/enum), top-level and member
 * functions and properties, extension function/property receiver grouping, the package
 * declaration folded into `fqn`, and a syntax error not taking down the rest of the file
 * or the rest of the run. It also pins down two grammar gaps found while probing this
 * community-maintained grammar (see `KotlinGrammar.kt`'s doc comment): `class` vs
 * `interface` has no dedicated node, and context receivers aren't supported at all.
 *
 * Every assertion here goes through [io.contextgraph.extractors.TreeSitterExtractor] --
 * the same class `IngestPipeline` calls per file -- not a parallel test-only code path.
 */
class KotlinGrammarSnapshotTest : FunSpec({
    val fixtureRoot = RepoRoot.fixture("kotlin-grammar")
    val snapshotFile = fixtureRoot.resolve(GoldenSnapshotHarness.SNAPSHOT_FILE_NAME)

    test("extraction matches the committed golden snapshot") {
        GoldenSnapshotHarness.assertMatchesGoldenSnapshot(fixtureRoot, snapshotFile)
    }

    test("AC: every declared type, function and property is extracted with correct start/end lines") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val classNode = extracted.nodes.first { it.id.value == "Auth/UserService.kt#UserService" }
        classNode.type shouldBe NodeType.Class
        val classProvenance = classNode.provenance.single()
        classProvenance.lineStart shouldBe 6 // "class UserService(...) {"
        classProvenance.lineEnd shouldBe 25  // closing brace of the class body

        val methodNode = extracted.nodes.first { it.id.value == "Auth/UserService.kt#UserService.save(String)" }
        methodNode.type shouldBe NodeType.Method
        methodNode.provenance.single().lineStart shouldBe 7
        methodNode.provenance.single().lineEnd shouldBe 9

        val propNode = extracted.nodes.first { it.id.value == "Auth/UserService.kt#UserService.name" }
        propNode.type shouldBe NodeType.Custom("Property")
        propNode.provenance.single().lineStart shouldBe 6

        val importNode = extracted.nodes.first { it.id.value == "Auth/UserService.kt#import:kotlin.collections.List" }
        importNode.type shouldBe NodeType.Module
        importNode.provenance.single().lineStart shouldBe 3
    }

    test("AC: top-level functions and properties are extracted with the file as their scope") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val topLevelFun = extracted.nodes.first { it.id.value == "TopLevel/Utils.kt#square(Int)" }
        topLevelFun.type shouldBe NodeType.Function
        topLevelFun.label shouldBe "square"

        val topLevelProp = extracted.nodes.first { it.id.value == "TopLevel/Utils.kt#defaultRetries" }
        topLevelProp.type shouldBe NodeType.Custom("Property")

        // Contained by the file node directly, not dropped and not nested under a synthetic type.
        val containsEdge = extracted.edges.first { it.target.value == "TopLevel/Utils.kt#square(Int)" }
        containsEdge.source.value shouldBe "TopLevel/Utils.kt"
    }

    test("AC: an extension function's fqn identifies the receiver type, grouping it with that type") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val shout = extracted.nodes.first { it.id.value == "Extensions/StringExtensions.kt#String.shout()" }
        val shoutFqn = (shout.properties["fqn"] as JsonPrimitive).content
        shoutFqn shouldBe "com.example.ext.String.shout"

        val shoutOverload = extracted.nodes.first { it.id.value == "Extensions/StringExtensions.kt#String.shout(Int)" }
        val shoutOverloadFqn = (shoutOverload.properties["fqn"] as JsonPrimitive).content
        shoutOverloadFqn shouldBe "com.example.ext.String.shout" // overloads share one fqn, distinct IDs above prove it isn't identity

        // A same-named extension on a different receiver never collides with String's.
        val intShout = extracted.nodes.first { it.id.value == "Extensions/StringExtensions.kt#Int.shout()" }
        (intShout.properties["fqn"] as JsonPrimitive).content shouldBe "com.example.ext.Int.shout"

        // An extension property follows the same rule.
        val extProp = extracted.nodes.first { it.id.value == "Extensions/StringExtensions.kt#String.firstOrEmpty" }
        (extProp.properties["fqn"] as JsonPrimitive).content shouldBe "com.example.ext.String.firstOrEmpty"
    }

    test("AC: companion object members are distinguishable from instance members") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val companion = extracted.nodes.first { it.id.value == "Auth/UserService.kt#UserService.Companion" }
        companion.type shouldBe NodeType.Custom("CompanionObject")

        val companionFun = extracted.nodes.first { it.id.value == "Auth/UserService.kt#UserService.Companion.of(String)" }
        companionFun.type shouldBe NodeType.Method

        val companionConst = extracted.nodes.first { it.id.value == "Auth/UserService.kt#UserService.Companion.MAX_RETRIES" }
        companionConst.type shouldBe NodeType.Custom("Property")

        // Distinct from an instance member with the same simple name/kind but no "Companion" segment.
        extracted.nodes.any { it.id.value == "Auth/UserService.kt#UserService.name" } shouldBe true
        extracted.nodes.none { it.id.value == "Auth/UserService.kt#UserService.MAX_RETRIES" } shouldBe true
    }

    test("AC: fqn incorporates the package declaration") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val shapeNode = extracted.nodes.first { it.id.value == "Shapes/Shapes.kt#Shape" }
        (shapeNode.properties["fqn"] as JsonPrimitive).content shouldBe "com.example.shapes.Shape"
    }

    test("AC: a Kotlin file the grammar cannot parse still produces a file node, a diagnostic, and other files continue to index") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val brokenFileNode = extracted.nodes.firstOrNull { it.id.value == "Broken/Broken.kt" }
        brokenFileNode shouldNotBe null

        extracted.diagnostics.map { it.severity } shouldContain ExtractionDiagnostic.Severity.WARNING
        extracted.diagnostics.any { it.message.contains("Broken/Broken.kt") } shouldBe true

        // Other files in the same fixture run still extracted fully.
        extracted.nodes.any { it.id.value == "Auth/UserService.kt#UserService" } shouldBe true
        extracted.nodes.any { it.id.value == "Shapes/Shapes.kt#Shape" } shouldBe true
    }

    test("grammar gap: class vs interface has no dedicated node; interface/enum/object/sealed all resolve correctly here") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        extracted.nodes.first { it.id.value == "Shapes/Shapes.kt#Shape" }.type shouldBe NodeType.Custom("Interface")
        extracted.nodes.first { it.id.value == "Shapes/Shapes.kt#Circle" }.type shouldBe NodeType.Class
        extracted.nodes.first { it.id.value == "Shapes/Shapes.kt#EmptyShape" }.type shouldBe NodeType.Custom("Object")
        extracted.nodes.first { it.id.value == "Shapes/Shapes.kt#Color" }.type shouldBe NodeType.Custom("Enum")
        extracted.nodes.first { it.id.value == "Shapes/Shapes.kt#Color.RED" }.type shouldBe NodeType.Custom("Property")
    }

    test("grammar gap: context receivers are silently misparsed, not flagged as a syntax error") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        // No error is raised for Gaps/ContextReceivers.kt -- the misparse is silent.
        extracted.diagnostics.any { it.message.contains("Gaps/ContextReceivers.kt") } shouldBe false

        // doWork is extracted as an ordinary top-level function with no trace of its
        // (misparsed) context receiver anywhere in its id or fqn.
        val doWork = extracted.nodes.first { it.id.value == "Gaps/ContextReceivers.kt#doWork()" }
        doWork.type shouldBe NodeType.Function
        (doWork.properties["fqn"] as JsonPrimitive).content shouldBe "com.example.gaps.doWork"
    }

    test("expect/actual declarations extract as ordinary, independent declaration sites in their own files") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val expectPlatform = extracted.nodes.first { it.id.value == "MultiPlatform/Platform.kt#Platform" }
        val actualPlatform = extracted.nodes.first { it.id.value == "MultiPlatform/PlatformJvm.kt#Platform" }
        expectPlatform.id.value shouldNotBe actualPlatform.id.value
    }

    test("a delegated property (`by lazy`) still extracts under its own name") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        extracted.nodes.any { it.id.value == "Async/Fetcher.kt#Fetcher.cachedGreeting" } shouldBe true
    }

    test("a suspend function extracts like any other member function") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        extracted.nodes.first { it.id.value == "Async/Fetcher.kt#Fetcher.fetch(String)" }.type shouldBe NodeType.Method
    }

    test("every declaration node carries a non-blank fqn (the guard slices 04-08 inherit by copying JavaGrammarSnapshotTest)") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        requireFqnOnDeclarations(extracted.nodes)
    }
})
