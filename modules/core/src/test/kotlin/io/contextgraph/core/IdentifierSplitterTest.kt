package io.contextgraph.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class IdentifierSplitterTest : FunSpec({

    test("splits camelCase into component words") {
        IdentifierSplitter.split("RungDistribution") shouldBe listOf("Rung", "Distribution")
    }

    test("splits acronym runs at the acronym/word boundary") {
        IdentifierSplitter.split("SqliteStorageAdapter") shouldBe listOf("Sqlite", "Storage", "Adapter")
        IdentifierSplitter.split("HTTPServerFactory") shouldBe listOf("HTTP", "Server", "Factory")
    }

    test("splits snake_case on underscores") {
        IdentifierSplitter.split("rate_limit_per_minute") shouldBe listOf("rate", "limit", "per", "minute")
    }

    test("splits kebab-case on hyphens") {
        IdentifierSplitter.split("min-confidence") shouldBe listOf("min", "confidence")
    }

    test("splits dotted names on dots") {
        IdentifierSplitter.split("io.contextgraph.core") shouldBe listOf("io", "contextgraph", "core")
    }

    test("splits at letter/digit boundaries") {
        IdentifierSplitter.split("Base64Encoder") shouldBe listOf("Base", "64", "Encoder")
    }

    test("single-word identifier is returned unchanged, not mangled") {
        IdentifierSplitter.split("rung") shouldBe listOf("rung")
        IdentifierSplitter.split("distribution") shouldBe listOf("distribution")
        IdentifierSplitter.split("sqlite") shouldBe listOf("sqlite")
    }

    test("empty string returns an empty list") {
        IdentifierSplitter.split("") shouldBe emptyList()
    }

    test("mixed separators and casing combine correctly") {
        IdentifierSplitter.split("get_HTTPResponse-code") shouldBe listOf("get", "HTTP", "Response", "code")
    }
})
