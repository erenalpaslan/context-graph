package io.contextgraph.benchmark.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * AC-4: a gold fact without evidence must be unrepresentable / load-rejected.
 * `GoldFact.evidence` being a non-optional `Evidence` makes the first half a
 * compile-time property; these tests cover the load-rejection half, which is
 * the part that's actually exercisable at runtime (e.g. from a data file
 * with a missing or malformed evidence field, which slice 03's loader will
 * do for real question sets).
 */
class GoldFactTest : FunSpec({

    val json = Json { encodeDefaults = true }

    test("a gold fact with valid evidence loads") {
        val text = """{"id":"f1","statement":"Foo does X","evidence":"src/Foo.kt:10"}"""
        val fact = json.decodeFromString(GoldFact.serializer(), text)
        fact.evidence shouldBe Evidence("src/Foo.kt", 10)
    }

    test("a gold fact with a missing evidence field is rejected") {
        val text = """{"id":"f1","statement":"Foo does X"}"""
        shouldThrow<SerializationException> { json.decodeFromString(GoldFact.serializer(), text) }
    }

    test("a gold fact with a malformed evidence field is rejected") {
        val text = """{"id":"f1","statement":"Foo does X","evidence":"not-a-citation"}"""
        shouldThrow<SerializationException> { json.decodeFromString(GoldFact.serializer(), text) }
    }

    test("a gold fact with a null evidence field is rejected") {
        val text = """{"id":"f1","statement":"Foo does X","evidence":null}"""
        shouldThrow<SerializationException> { json.decodeFromString(GoldFact.serializer(), text) }
    }

    test("round-trips through JSON") {
        val fact = GoldFact(id = "f1", statement = "Foo does X", evidence = Evidence("src/Foo.kt", 10))
        val text = json.encodeToString(GoldFact.serializer(), fact)
        json.decodeFromString(GoldFact.serializer(), text) shouldBe fact
    }
})
