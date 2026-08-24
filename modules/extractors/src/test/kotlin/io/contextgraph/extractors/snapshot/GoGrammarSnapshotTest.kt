package io.contextgraph.extractors.snapshot

import io.contextgraph.core.EdgeType
import io.contextgraph.core.ExtractionDiagnostic
import io.contextgraph.core.NodeType
import io.contextgraph.treesitter.requireFqnOnDeclarations
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Golden-snapshot test for Go extraction against `test-fixtures/go-grammar`, a fixture
 * modelled on the shapes gin actually uses: an `Engine` struct embedding a `RouterGroup`,
 * methods spread across several files of one package, an interface whose method set is what
 * impact analysis starts from, and package-level `const`/`var` blocks.
 *
 * Every assertion goes through [io.contextgraph.extractors.TreeSitterExtractor] -- the same
 * class `IngestPipeline` calls per file -- not a parallel test-only code path.
 */
class GoGrammarSnapshotTest : FunSpec({
    val fixtureRoot = RepoRoot.fixture("go-grammar")
    val snapshotFile = fixtureRoot.resolve(GoldenSnapshotHarness.SNAPSHOT_FILE_NAME)

    test("extraction matches the committed golden snapshot") {
        GoldenSnapshotHarness.assertMatchesGoldenSnapshot(fixtureRoot, snapshotFile)
    }

    test("a Go source tree produces a non-zero number of function and method nodes") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        // The whole point of the slice: before Go was registered, both of these were empty for
        // every Go file in the corpus, so gin's column measured extraction coverage rather
        // than retrieval quality.
        extracted.nodes.filter { it.type == NodeType.Function }.shouldNotBeEmpty()
        extracted.nodes.filter { it.type == NodeType.Method }.shouldNotBeEmpty()
        extracted.nodes.filter { it.type == NodeType.Class }.shouldNotBeEmpty()
        extracted.nodes.filter { it.type == NodeType.Custom("Interface") }.shouldNotBeEmpty()
        extracted.nodes.filter { it.type == NodeType.Module }.shouldNotBeEmpty()
    }

    test("same-named methods on different receivers stay three distinct nodes") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        // `Use` is declared on Engine, on RouterGroup and on Context. Two of those are in the
        // same file, so the file path alone cannot be what separates them -- the receiver type
        // in the scope chain is.
        val uses = extracted.nodes.filter { it.label == "Use" }.map { it.id.value }.sorted()
        uses shouldContainExactly listOf(
            "server/engine.go#Engine.Use(HandlerFunc)",
            "server/router.go#Context.Use(HandlerFunc)",
            "server/router.go#RouterGroup.Use(HandlerFunc)"
        )
        extracted.nodes.filter { it.label == "Use" }.map { it.properties["fqn"] }.toSet().size shouldBe 3
    }

    test("a method whose receiver type this file declares hangs off the type; one declared elsewhere falls back to the file") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        val contains = extracted.edges.filter { it.type == EdgeType.Contains }.associateBy { it.target.value }

        // Context is declared in router.go, so Context.Next lands under the type node.
        contains.getValue("server/router.go#Context.Next()").source.value shouldBe "server/router.go#Context"

        // Context.Abort is declared in a different file of the same package. The identity still
        // leads with the receiver -- that is what keeps it distinct from any other Abort -- but
        // the containment edge can only come from the file, which is the honest answer.
        val abort = extracted.nodes.first { it.id.value == "server/context_abort.go#Context.Abort()" }
        (abort.properties["fqn"] as JsonPrimitive).content shouldBe "server.Context.Abort"
        (abort.properties["receiver"] as JsonPrimitive).content shouldBe "Context"
        contains.getValue("server/context_abort.go#Context.Abort()").source.value shouldBe "server/context_abort.go"
    }

    test("declarations carry correct start/end lines, and imports resolve to their quoted path") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val engine = extracted.nodes.first { it.id.value == "server/engine.go#Engine" }
        engine.type shouldBe NodeType.Class
        engine.provenance.single().lineStart shouldBe 19 // "type Engine struct {"
        engine.provenance.single().lineEnd shouldBe 24 // "}"

        val serveHTTP = extracted.nodes
            .first { it.id.value == "server/engine.go#Engine.ServeHTTP(http.ResponseWriter,*http.Request)" }
        serveHTTP.type shouldBe NodeType.Method
        serveHTTP.provenance.single().lineStart shouldBe 34
        serveHTTP.provenance.single().lineEnd shouldBe 37

        // A parenthesised import block: the quotes are stripped, the slashes are not.
        val import = extracted.nodes.first { it.id.value == "server/engine.go#import:net/http" }
        import.type shouldBe NodeType.Module
        import.label shouldBe "net/http"
        import.provenance.single().lineStart shouldBe 4
    }

    test("struct embedding is recorded as a supertype and as a named field") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val engine = extracted.nodes.first { it.id.value == "server/engine.go#Engine" }
        (engine.properties["supertypes"] as JsonArray)
            .map { (it as JsonPrimitive).content } shouldContainExactly listOf("RouterGroup")

        // An embedded field has no name of its own in the source; the embedded type's name is
        // how the rest of the package refers to it, so that is the label.
        val embedded = extracted.nodes.first { it.id.value == "server/engine.go#Engine.RouterGroup" }
        (embedded.properties["kind"] as JsonPrimitive).content shouldBe "embedded_field"

        // Interface embedding is the same relation written differently.
        val validating = extracted.nodes.first { it.id.value == "binding/binding.go#Validating" }
        (validating.properties["supertypes"] as JsonArray)
            .map { (it as JsonPrimitive).content } shouldContainExactly listOf("Binding")
    }

    test("an interface's declared method set is extracted, distinct from a concrete implementation's") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val declared = extracted.nodes.first { it.id.value == "binding/binding.go#Binding.Bind(*http.Request,any)" }
        declared.type shouldBe NodeType.Method
        (declared.properties["kind"] as JsonPrimitive).content shouldBe "interface_method"
        (declared.properties["returns"] as JsonPrimitive).content shouldBe "error"

        // The concrete implementation is a separate declaration site with the same shape.
        val implemented = extracted.nodes
            .first { it.id.value == "binding/binding.go#jsonBinding.Bind(*http.Request,any)" }
        (implemented.properties["kind"] as JsonPrimitive).content shouldBe "method"
        declared.id shouldNotBe implemented.id
    }

    test("package-level const and var names are extracted, from both the bare and block forms") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        val byId = extracted.nodes.associateBy { it.id.value }

        // A bare `const EnvMode = ...` and a parenthesised `const ( ... )` block.
        (byId.getValue("server/engine.go#EnvMode").properties["kind"] as JsonPrimitive).content shouldBe "const"
        (byId.getValue("server/engine.go#DebugMode").properties["kind"] as JsonPrimitive).content shouldBe "const"
        byId.getValue("server/engine.go#ReleaseMode").type shouldBe NodeType.Custom("Constant")

        // `var default404Body = []byte(...)` -- the shape gin's 404/405 literals use.
        byId.getValue("server/engine.go#default404Body").type shouldBe NodeType.Custom("Variable")
        // A `var ( ... )` block, one node per name.
        byId.getValue("binding/binding.go#JSON").type shouldBe NodeType.Custom("Variable")
        byId.getValue("binding/binding.go#XML").type shouldBe NodeType.Custom("Variable")
    }

    test("node IDs are byte-identical across two extraction runs over unchanged source") {
        val first = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        val second = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        first.nodes.map { it.id.value }.sorted() shouldBe second.nodes.map { it.id.value }.sorted()
        first.edges.map { it.id.value }.sorted() shouldBe second.edges.map { it.id.value }.sorted()
    }

    test("a syntax error still produces a file node, a diagnostic, and does not cost its siblings") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        extracted.nodes.firstOrNull { it.id.value == "broken/broken.go" } shouldNotBe null
        extracted.diagnostics.map { it.severity } shouldContain ExtractionDiagnostic.Severity.WARNING
        extracted.diagnostics.any { it.message.contains("broken/broken.go") } shouldBe true

        // The well-formed function after the broken one is still recovered by the walk.
        extracted.nodes.any { it.id.value == "broken/broken.go#Fine()" } shouldBe true
        extracted.nodes.any { it.id.value == "server/engine.go#Engine" } shouldBe true
    }

    test("a method whose receiver cannot be reduced to a type is emitted at file scope, not dropped (H3)") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        val contains = extracted.edges.filter { it.type == EdgeType.Contains }.associateBy { it.target.value }

        // `func (1) BrokenReceiver()` -- the receiver is not reducible to any type. The method
        // still gets a node, scoped to the file exactly like a package-level function: no
        // receiver segment in its ID, no "receiver" property, and a Contains edge from the file
        // rather than from any type node (there is none to hang off).
        val broken = extracted.nodes.first { it.id.value == "broken/broken.go#BrokenReceiver()" }
        broken.type shouldBe NodeType.Method
        (broken.properties["kind"] as JsonPrimitive).content shouldBe "method"
        broken.properties.containsKey("receiver") shouldBe false
        contains.getValue("broken/broken.go#BrokenReceiver()").source.value shouldBe "broken/broken.go"
    }

    test("a type alias to an anonymous struct is a bare TypeAlias node, not a struct declaration (S1)") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        // `type AliasAnonymousStruct = struct { A int }` -- a legal Go alias to an anonymous
        // struct. `kind` is "type_alias" here (decided from spec.type == "type_alias" before
        // `underlying` is even inspected), and dispatch must follow `kind`, not
        // `underlying.type` ("struct_type") -- otherwise this alias would gain Field children
        // as if the line had declared a new struct type, which it did not.
        val alias = extracted.nodes.first { it.id.value == "binding/binding.go#AliasAnonymousStruct" }
        alias.type shouldBe NodeType.Custom("TypeAlias")
        (alias.properties["kind"] as JsonPrimitive).content shouldBe "type_alias"
        extracted.nodes.none { it.id.value.startsWith("binding/binding.go#AliasAnonymousStruct.") } shouldBe true
    }

    test("every declaration node carries a non-blank fqn") {
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)
        requireFqnOnDeclarations(extracted.nodes)
    }
})
