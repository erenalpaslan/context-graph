package io.contextgraph.treesitter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LanguageRegistryTest : FunSpec({

    val expectedByExtension = mapOf(
        "java" to "java",
        "py" to "python",
        "js" to "javascript",
        "ts" to "typescript",
        "tsx" to "tsx",
        "kt" to "kotlin",
        "swift" to "swift",
        "m" to "objc",
        "h" to "objc",
    )

    for ((ext, expectedId) in expectedByExtension) {
        test("resolves .$ext to the $expectedId language") {
            LanguageRegistry.resolve("Example.$ext")?.id shouldBe expectedId
        }
    }

    test("resolves an unknown extension to null without throwing") {
        LanguageRegistry.resolve("Podfile.lock") shouldBe null
        LanguageRegistry.resolve("README.md") shouldBe null
        LanguageRegistry.resolve("no-extension-at-all") shouldBe null
    }

    test("resolution is case-insensitive on the extension") {
        LanguageRegistry.resolve("Example.JAVA")?.id shouldBe "java"
    }

    test("resolves against a full repo-relative path, not just a bare file name") {
        LanguageRegistry.resolve("Auth/UserService.java")?.id shouldBe "java"
    }
})
