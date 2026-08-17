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
 * Slice 06's golden-snapshot test: proves Python extraction against a fixture project
 * (`test-fixtures/python-grammar`) covering everything the task requires -- classes,
 * functions/methods (including nested closures, `async def`, `__init__`, decorated
 * definitions), class attributes, module-level constants, and imports in every form
 * (absolute, aliased, relative with dots, wildcard) -- copying the shape of
 * `JavaGrammarSnapshotTest`.
 *
 * Every assertion here goes through [io.contextgraph.extractors.TreeSitterExtractor] --
 * the same class `IngestPipeline` calls per file -- not a parallel test-only code path.
 */
class PythonGrammarSnapshotTest : FunSpec({
    val fixtureRoot = RepoRoot.fixture("python-grammar")
    val snapshotFile = fixtureRoot.resolve(GoldenSnapshotHarness.SNAPSHOT_FILE_NAME)

    test("extraction matches the committed golden snapshot") {
        GoldenSnapshotHarness.assertMatchesGoldenSnapshot(fixtureRoot, snapshotFile)
    }

    test("AC: a class and its methods carry correct start/end lines") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val classNode = extracted.nodes.first { it.id.value == "pkg/auth/service.py#Service" }
        classNode.type shouldBe NodeType.Class
        val classProvenance = classNode.provenance.single()
        classProvenance.lineStart shouldBe 19 // "class Service:"

        val ctorNode = extracted.nodes.first { it.id.value == "pkg/auth/service.py#Service.__init__" }
        ctorNode.type shouldBe NodeType.Method
        ctorNode.provenance.single().lineStart shouldBe 23
        (ctorNode.properties["kind"] as JsonPrimitive).content shouldBe "constructor"
    }

    test("AC: nested functions/closures are scoped under the enclosing function, not colliding by name") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val outerInner = extracted.nodes.first { it.id.value == "standalone.py#make_adder.adder" }
        val processInner = extracted.nodes.first { it.id.value == "pkg/auth/service.py#process.transform" }

        outerInner.label shouldBe "adder"
        processInner.label shouldBe "transform"
        // Distinct IDs by construction (different enclosing scope chains) -- this is the point.
        outerInner.id shouldNotBe processInner.id
    }

    test("AC: async def is extracted and distinguishable from a synchronous declaration") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val fetch = extracted.nodes.first { it.id.value == "pkg/auth/service.py#Service.fetch" }
        val save = extracted.nodes.first { it.id.value == "pkg/auth/service.py#Service.save" }
        val process = extracted.nodes.first { it.id.value == "pkg/auth/service.py#process" }

        (fetch.properties["async"] as JsonPrimitive).content shouldBe "true"
        (save.properties["async"] as JsonPrimitive).content shouldBe "false"
        (process.properties["async"] as JsonPrimitive).content shouldBe "true"
    }

    test("AC: decorators are captured on the declaration they decorate") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val flaky = extracted.nodes.first { it.id.value == "pkg/auth/service.py#flaky" }
        val decorators = (flaky.properties["decorators"] as kotlinx.serialization.json.JsonArray)
            .map { (it as JsonPrimitive).content }
        decorators shouldBe listOf("retry(times=3)")

        val config = extracted.nodes.first { it.id.value == "pkg/auth/service.py#Config" }
        val configDecorators = (config.properties["decorators"] as kotlinx.serialization.json.JsonArray)
            .map { (it as JsonPrimitive).content }
        configDecorators shouldBe listOf("dataclass")

        val helper = extracted.nodes.first { it.id.value == "pkg/auth/service.py#Service.helper" }
        val helperDecorators = (helper.properties["decorators"] as kotlinx.serialization.json.JsonArray)
            .map { (it as JsonPrimitive).content }
        helperDecorators shouldBe listOf("staticmethod")
    }

    test("AC: relative imports record a resolved target module, not just the literal text") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        // "from . import helpers" from pkg/auth/service.py resolves to the sibling module.
        val sibling = extracted.nodes.first { it.id.value == "pkg/auth/service.py#import:pkg.auth.helpers" }
        (sibling.properties["relative"] as JsonPrimitive).content shouldBe "true"
        sibling.label shouldBe "pkg.auth.helpers"

        // "from .. import models" ascends one package level from pkg.auth to pkg.
        val ascended = extracted.nodes.first { it.id.value == "pkg/auth/service.py#import:pkg.models" }
        ascended.label shouldBe "pkg.models"

        // "from ..models import User" ascends and appends the extra path segment.
        val ascendedWithSegment =
            extracted.nodes.first { it.id.value == "pkg/auth/service.py#import:pkg.models.User" }
        ascendedWithSegment.label shouldBe "pkg.models.User"

        // "from ....deep import thing" over-ascends past the package root; resolution
        // clamps rather than throwing or guessing at a nonexistent ancestor.
        val clamped = extracted.nodes.first { it.id.value == "pkg/auth/service.py#import:deep.thing" }
        clamped.label shouldBe "deep.thing"
    }

    test("AC: the fqn property reflects the package path derived from directory structure") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val service = extracted.nodes.first { it.id.value == "pkg/auth/service.py#Service" }
        (service.properties["fqn"] as JsonPrimitive).content shouldBe "pkg.auth.service.Service"

        // __init__.py's package identity is its containing directory, not a "__init__" member.
        val pkgMarker = extracted.nodes.first { it.id.value == "pkg/__init__.py#PkgMarker" }
        (pkgMarker.properties["fqn"] as JsonPrimitive).content shouldBe "pkg.PkgMarker"
    }

    test("class attributes and module-level constants are extracted") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val cache = extracted.nodes.first { it.id.value == "pkg/auth/service.py#Service._cache" }
        cache.type shouldBe NodeType.Custom("Field")
        (cache.properties["kind"] as JsonPrimitive).content shouldBe "class_attribute"

        val name = extracted.nodes.first { it.id.value == "pkg/auth/service.py#Service.name" }
        (name.properties["kind"] as JsonPrimitive).content shouldBe "class_attribute"

        val maxRetries = extracted.nodes.first { it.id.value == "pkg/auth/service.py#MAX_RETRIES" }
        maxRetries.type shouldBe NodeType.Custom("Constant")
        (maxRetries.properties["kind"] as JsonPrimitive).content shouldBe "constant"

        // Tuple-target module-level assignment produces one constant node per target.
        val host = extracted.nodes.first { it.id.value == "pkg/auth/service.py#DEFAULT_HOST" }
        val port = extracted.nodes.first { it.id.value == "pkg/auth/service.py#DEFAULT_PORT" }
        host.label shouldBe "DEFAULT_HOST"
        port.label shouldBe "DEFAULT_PORT"

        // self.name = name inside __init__ is an instance attribute assignment, not a
        // class-body-level attribute -- deliberately not extracted as its own node.
        extracted.nodes.none {
            it.id.value == "pkg/auth/service.py#Service.__init__.name" && it.label == "name"
        } shouldBe true
    }

    test("a syntax error still produces a file node, a diagnostic, and does not affect other files") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val brokenFileNode = extracted.nodes.firstOrNull { it.id.value == "broken.py" }
        brokenFileNode shouldNotBe null

        extracted.diagnostics.map { it.severity } shouldContain ExtractionDiagnostic.Severity.WARNING
        extracted.diagnostics.any { it.message.contains("broken.py") } shouldBe true

        extracted.nodes.any { it.id.value == "pkg/auth/service.py#Service" } shouldBe true
    }

    test("every declaration node carries a non-blank fqn") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        requireFqnOnDeclarations(extracted.nodes)
    }
})
