package io.contextgraph.treesitter

import io.contextgraph.core.ArtifactId
import io.contextgraph.core.UnresolvedReference
import io.contextgraph.treesitter.grammars.GoLanguageSupport
import io.github.treesitter.ktreesitter.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant

/**
 * Pass 1's half of Go call-site resolution: does the walker record every call, on the line
 * the *called name* sits on, with the receiver's type when the file declares one?
 *
 * The receiver hint matters more in Go than the language's size suggests. `Next`, `Use`,
 * `Write` and `Name` are declared on many types across a single package, so a name-only
 * candidate set clears the ambiguity cap and the call resolves to nothing at all. Go's
 * receiver is an ordinary typed parameter, which makes the hint unusually cheap to read.
 */
class GoReceiverTypeTest : FunSpec({

    fun extract(source: String) = GoLanguageSupport.extract(
        SymbolExtractionRequest(
            tree = Parser(GoLanguageSupport.language()).parse(source),
            source = source,
            sourceText = SourceText(source),
            repoRelativePath = "server/caller.go",
            artifactId = ArtifactId("server/caller.go"),
            extractorId = "tree-sitter",
            extractedAt = Instant.parse("2026-08-24T00:00:00Z")
        )
    )

    fun referencesIn(source: String): Map<String, UnresolvedReference> =
        extract(source).references.associateBy { it.referenceName }

    test("a method receiver and a parameter both type the calls made on them") {
        val refs = referencesIn(
            """
            package server

            func (engine *Engine) handleHTTPRequest(c *Context) {
                engine.rebuild404Handlers()
                c.Next()
            }
            """.trimIndent()
        )
        // The receiver is a named, typed parameter like any other -- there is no `this` in Go.
        refs.getValue("rebuild404Handlers").receiverType shouldBe "Engine"
        refs.getValue("Next").receiverType shouldBe "Context"
    }

    test("an explicitly typed local is used; a short variable declaration is left null rather than guessed") {
        val refs = referencesIn(
            """
            package server

            func run() {
                var c *Context
                c.Next()
                group := newGroup()
                group.Use(nil)
            }
            """.trimIndent()
        )
        refs.getValue("Next").receiverType shouldBe "Context"
        // `:=` writes no type: the answer is the right-hand side's result type, which is
        // usually declared in another file. Pass 2 has the symbol table; pass 1 declines.
        refs.getValue("Use").receiverType shouldBe null
    }

    test("a package-local call is recorded with no receiver, and an imported package is not mistaken for one") {
        val refs = referencesIn(
            """
            package server

            func run(c *Context) {
                serveError(c, default404Body)
                fmt.Sprintf("%d", 1)
                http.StatusText(404)
            }
            """.trimIndent()
        )
        refs.getValue("serveError").receiverType shouldBe null
        // `fmt` and `http` are package names, not receiver types. Go's export convention --
        // lower-case means unexported, so never a package-level type name -- is what separates
        // them from a genuine bare type receiver.
        refs.getValue("Sprintf").receiverType shouldBe null
        refs.getValue("StatusText").receiverType shouldBe null
    }

    test("a bare upper-case receiver that is not in scope is taken as that type") {
        val refs = referencesIn(
            """
            package server

            func run() {
                Default().Use(nil)
                Engine.Reset()
            }
            """.trimIndent()
        )
        refs.getValue("Reset").receiverType shouldBe "Engine"
        // Chained off a call: no declared type here, only the name of the call that produced
        // the receiver, which is the half pass 2 cannot reconstruct once the syntax is gone.
        refs.getValue("Use").receiverType shouldBe null
        refs.getValue("Use").receiverCall shouldBe "Default"
    }

    test("a call reports the line of its own name, not the line its receiver chain starts on") {
        // A chained call written across several lines is the shape that silently corrupted this
        // project's benchmark ground truth once already: a `call_expression` starts at the
        // beginning of the whole chain, so every call in it would claim the first line.
        val refs = referencesIn(
            """
            package server

            func run(engine *Engine) {
                engine.
                    Group("/v1").
                    Use(nil)
            }
            """.trimIndent()
        )
        refs.getValue("Group").line shouldBe 5
        refs.getValue("Use").line shouldBe 6
    }

    test("calls inside a func literal attribute to the function that contains it") {
        val extraction = extract(
            """
            package server

            func (engine *Engine) Run() {
                engine.walk(func(c *Context) {
                    c.Next()
                })
            }
            """.trimIndent()
        )
        // A func literal gets no declaration node of its own, so there is no more specific
        // owner for `Next` to belong to -- and no double attribution to guard against.
        extraction.references.map { it.referringSymbolId.value }.toSet() shouldBe
            setOf("server/caller.go#Engine.Run()")
    }
})
