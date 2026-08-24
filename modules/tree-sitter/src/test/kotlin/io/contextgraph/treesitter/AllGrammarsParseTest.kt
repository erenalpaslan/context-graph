package io.contextgraph.treesitter

import io.github.treesitter.ktreesitter.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Proves the parsing substrate every later slice stands on: all eight vendored grammars
 * (nine registry entries) load their compiled native library and parse a trivial
 * snippet with no error node at the root, on whatever platform this test runs on
 * (macOS arm64 or Linux x64 -- see the slice 01 task file).
 *
 * This is also the cheapest ABI check there is. ktreesitter 0.24.1's native runtime accepts
 * tree-sitter ABI 14 and rejects 15; a grammar pinned at a commit that generates 15 fails
 * here at `Language(...)` construction rather than silently returning empty parses. See the
 * python and go entries in `build.gradle.kts` for the two commits that were moved back a
 * minor version for exactly this reason.
 */
class AllGrammarsParseTest : FunSpec({

    val trivialSnippets = mapOf(
        "java" to "class Foo { void bar() {} }",
        "python" to "def foo():\n    return 1\n",
        "javascript" to "function foo() { return 1; }",
        "typescript" to "function foo(): number { return 1; }",
        "tsx" to "const x = () => <div>hi</div>;",
        "kotlin" to "fun main() { println(\"hi\") }",
        "swift" to "func foo() -> Int { return 1 }",
        "objc" to "@interface Foo : NSObject\n@end\n",
        "go" to "package main\n\nfunc foo() int { return 1 }\n",
    )

    test("every registered language has a trivial snippet fixture") {
        LanguageRegistry.all.map { it.id }.toSet() shouldBe trivialSnippets.keys
    }

    for (support in LanguageRegistry.all) {
        test("${support.id} grammar loads and parses a trivial snippet without an error node at the root") {
            val source = trivialSnippets.getValue(support.id)
            val parser = Parser(support.language())
            val tree = parser.parse(source)

            tree shouldNotBe null
            val root = tree.rootNode
            root shouldNotBe null
            root.isError shouldBe false
            root.hasError shouldBe false
        }
    }
})
