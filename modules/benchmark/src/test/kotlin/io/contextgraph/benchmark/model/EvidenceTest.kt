package io.contextgraph.benchmark.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class EvidenceTest : FunSpec({

    test("parses a valid file:line string") {
        val evidence = Evidence.parse("src/main/kotlin/Foo.kt:42")
        evidence.file shouldBe "src/main/kotlin/Foo.kt"
        evidence.line shouldBe 42
    }

    test("toString renders back to file:line") {
        Evidence("a/b/c.go", 7).toString() shouldBe "a/b/c.go:7"
    }

    test("parse is the inverse of toString") {
        val original = Evidence("modules/core/src/main/kotlin/Foo.kt", 123)
        Evidence.parse(original.toString()) shouldBe original
    }

    test("rejects a string with no line number") {
        shouldThrow<IllegalArgumentException> { Evidence.parse("src/Foo.kt") }
    }

    test("rejects a non-numeric line") {
        shouldThrow<IllegalArgumentException> { Evidence.parse("src/Foo.kt:abc") }
    }

    test("rejects a zero line number") {
        shouldThrow<IllegalArgumentException> { Evidence("src/Foo.kt", 0) }
    }

    test("rejects a negative line number") {
        shouldThrow<IllegalArgumentException> { Evidence("src/Foo.kt", -1) }
    }

    test("rejects a blank file path") {
        shouldThrow<IllegalArgumentException> { Evidence.parse(":42") }
    }

    test("serializes as a plain file:line JSON string") {
        val json = Json.encodeToString(Evidence.serializer(), Evidence("src/Foo.kt", 42))
        json shouldBe "\"src/Foo.kt:42\""
    }

    test("deserializes a well-formed JSON string") {
        val evidence = Json.decodeFromString(Evidence.serializer(), "\"src/Foo.kt:42\"")
        evidence shouldBe Evidence("src/Foo.kt", 42)
    }

    test("rejects a malformed evidence string at JSON decode time") {
        shouldThrow<SerializationException> {
            Json.decodeFromString(Evidence.serializer(), "\"no-line-number-here\"")
        }
    }
})
