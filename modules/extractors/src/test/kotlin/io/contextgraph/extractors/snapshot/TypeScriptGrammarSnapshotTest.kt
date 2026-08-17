package io.contextgraph.extractors.snapshot

import io.contextgraph.core.EdgeType
import io.contextgraph.core.ExtractionDiagnostic
import io.contextgraph.core.NodeType
import io.contextgraph.treesitter.requireFqnOnDeclarations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonPrimitive

/**
 * Slice 05's golden-snapshot test for the TypeScript dialect (`test-fixtures/typescript-grammar`),
 * covering AC-1/AC-32 for `.ts`: interfaces, type aliases, enums, a decorated + overloaded
 * class, arrow functions bound to `const`, a named and an anonymous default export, and a
 * barrel-style re-export.
 *
 * Every assertion goes through [io.contextgraph.extractors.TreeSitterExtractor] -- the same
 * class `IngestPipeline` calls per file -- not a parallel test-only code path.
 */
class TypeScriptGrammarSnapshotTest : FunSpec({
    val fixtureRoot = RepoRoot.fixture("typescript-grammar")
    val snapshotFile = fixtureRoot.resolve(GoldenSnapshotHarness.SNAPSHOT_FILE_NAME)

    test("extraction matches the committed golden snapshot") {
        GoldenSnapshotHarness.assertMatchesGoldenSnapshot(fixtureRoot, snapshotFile)
    }

    test("interface, type alias and enum are extracted and distinguishable from runtime values") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        extracted.nodes.first { it.id.value == "Auth/UserService.ts#IUserService" }.type shouldBe NodeType.Custom("Interface")
        extracted.nodes.first { it.id.value == "Utils/helpers.ts#formatUser(string)" }.type shouldBe NodeType.Function
        extracted.nodes.first { it.id.value == "Auth/UserService.ts#UserId" }.type shouldBe NodeType.Custom("TypeAlias")
        extracted.nodes.first { it.id.value == "Auth/UserService.ts#Role" }.type shouldBe NodeType.Custom("Enum")

        val userServiceClass = extracted.nodes.first { it.id.value == "Auth/UserService.ts#UserService" }
        userServiceClass.type shouldBe NodeType.Class // extracted correctly despite the @Injectable() decorator
    }

    test("two overloaded methods on the decorated class produce two distinct declaration-site nodes") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val singleArg = extracted.nodes.firstOrNull { it.id.value == "Auth/UserService.ts#UserService.save(string)" }
        val twoArg = extracted.nodes.firstOrNull { it.id.value == "Auth/UserService.ts#UserService.save(string,number)" }
        singleArg shouldNotBe null
        twoArg shouldNotBe null
        singleArg!!.id.value shouldNotBe twoArg!!.id.value
    }

    test("AC: an arrow function assigned to a const is extracted as a named function, not skipped") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val multiply = extracted.nodes.first { it.id.value == "Utils/helpers.ts#multiply" }
        multiply.type shouldBe NodeType.Function
        multiply.label shouldBe "multiply"
        (multiply.properties["kind"] as JsonPrimitive).content shouldBe "arrow_function"
    }

    test("AC: a named default export is extracted with the declaration's own name") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        // `export default function formatUser(...) {}` parses with the export_statement's
        // `declaration` field (not `value` -- see TsFamilyExtractor.extractExportStatement's
        // doc comment), so it goes through the same extractFunction() path as any other
        // named function_declaration and keeps kind "function" with a param-signature ID.
        // Only a value-default-export with no name of its own (AnonymousDefault.ts, below)
        // takes the extractDefaultExportValue() path and gets kind "default_export".
        val defaultFn = extracted.nodes.first { it.id.value == "Utils/helpers.ts#formatUser(string)" }
        defaultFn.label shouldBe "formatUser"
        (defaultFn.properties["kind"] as JsonPrimitive).content shouldBe "function"
    }

    test("AC: an anonymous default export derives a usable name from the file, never left anonymous") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val anonymousDefault = extracted.nodes.first { it.id.value == "Utils/AnonymousDefault.ts#AnonymousDefault" }
        anonymousDefault.label shouldBe "AnonymousDefault"
        anonymousDefault.type shouldBe NodeType.Function
    }

    test("AC: a re-export produces an import/reference edge naming the source module (barrel files)") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val starReexport = extracted.nodes.first { it.id.value == "Auth/index.ts#export:./UserService" }
        starReexport.type shouldBe NodeType.Module
        starReexport.label shouldBe "./UserService"

        val namedReexport = extracted.nodes.filter { it.type == NodeType.Module && it.label == "./UserService" }
        namedReexport.size shouldBe 1 // export * and export {..} from the same module share one node (same scope segment)

        val fileToReexport = extracted.edges.filter {
            it.source.value == "Auth/index.ts" && it.type == EdgeType.Imports
        }
        fileToReexport.map { it.target.value } shouldContain "Auth/index.ts#export:./UserService"
    }

    test("a regular import also produces a module reference edge") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val importNode = extracted.nodes.first { it.id.value == "Auth/UserService.ts#import:./Logger" }
        importNode.type shouldBe NodeType.Module
        extracted.edges.any {
            it.source.value == "Auth/UserService.ts" && it.target.value == "Auth/UserService.ts#import:./Logger" && it.type == EdgeType.Imports
        } shouldBe true
    }

    test("a syntax error still produces a file node, a diagnostic, and does not affect other files") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        extracted.nodes.any { it.id.value == "Broken/Broken.ts" } shouldBe true
        extracted.diagnostics.map { it.severity } shouldContain ExtractionDiagnostic.Severity.WARNING
        extracted.diagnostics.any { it.message.contains("Broken/Broken.ts") } shouldBe true
        extracted.nodes.any { it.id.value == "Auth/UserService.ts#UserService" } shouldBe true
    }

    test("node IDs are byte-identical across two extraction runs over unchanged source") {
        val first = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        val second = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        first.nodes.map { it.id.value }.sorted() shouldBe second.nodes.map { it.id.value }.sorted()
        first.edges.map { it.id.value }.sorted() shouldBe second.edges.map { it.id.value }.sorted()
    }

    test("every declaration node carries a non-blank fqn") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        requireFqnOnDeclarations(extracted.nodes)
    }
})
